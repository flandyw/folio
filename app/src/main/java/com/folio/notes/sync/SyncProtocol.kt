package com.folio.notes.sync

import com.folio.notes.mistakes.isoTime
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The pure core of sync protocol v3, mirroring Focal's `src/lib/sync/reduce.ts` rule for
 * rule. Everything here is a total function of its arguments: no I/O, no clock, no
 * randomness, which is what lets [SyncConformanceTests] run the shared vectors against it.
 *
 * The rules, in the order they are applied:
 *  1. Our own change is already on disk; skip it.
 *  2. A change we have already surpassed is noise; skip it.
 *  3. A change that collides with an unpublished local edit is parked for the user.
 *  4. Otherwise apply it. Ordering is `(lamport, clientId)`, never a wall clock.
 */
object SyncProtocol {
    /**
     * Every entity either project allows. A client only applies the ones it understands;
     * the rest sit in its applied-state table and cost it nothing, which is what lets one
     * log carry Focal, ExamTrack and Folio data side by side.
     */
    val ENTITIES = listOf(
        "projects", "events", "study_sessions", "custom_subjects", "hidden_subjects",
        "timetable_config", "user_settings", "mistakes", "attempts", "user_state",
        "folio_notebooks", "folio_pages", "folio_strokes"
    )

    /** A change as the log stores it. [seq] is the only ordering authority. */
    data class Change(
        val seq: Long,
        val changeId: String,
        val clientId: String,
        val entity: String,
        val rowId: String,
        val operation: String,
        val payload: JSONObject?,
        val lamport: Long,
        val createdAt: String
    ) {
        val key: String get() = "$entity:$rowId"
        fun toJson(): JSONObject = JSONObject()
            .put("seq", seq)
            .put("changeId", changeId)
            .put("clientId", clientId)
            .put("entity", entity)
            .put("rowId", rowId)
            .put("operation", operation)
            .put("payload", payload ?: JSONObject.NULL)
            .put("lamport", lamport)
            .put("createdAt", createdAt)
    }

    /** The last version of a row this device has applied. */
    data class RowState(
        val entity: String,
        val rowId: String,
        val operation: String,
        val payload: JSONObject?,
        val lamport: Long,
        val clientId: String,
        val seq: Long
    ) {
        val key: String get() = "$entity:$rowId"
        fun toJson(): JSONObject = JSONObject()
            .put("entity", entity)
            .put("rowId", rowId)
            .put("operation", operation)
            .put("payload", payload ?: JSONObject.NULL)
            .put("lamport", lamport)
            .put("clientId", clientId)
            .put("seq", seq)
    }

    /** A change waiting to be published. One per row, so a burst collapses instead of growing. */
    data class QueuedChange(
        val changeId: String,
        val entity: String,
        val rowId: String,
        val operation: String,
        val payload: JSONObject?,
        val lamport: Long,
        val createdAt: String,
        val retryCount: Int = 0,
        val lastError: String? = null,
        val nextAttemptAt: String? = null,
        val blockedAt: String? = null
    ) {
        val key: String get() = "$entity:$rowId"

        fun toJson(): JSONObject = JSONObject()
            .put("changeId", changeId)
            .put("entity", entity)
            .put("rowId", rowId)
            .put("operation", operation)
            .put("payload", payload ?: JSONObject.NULL)
            .put("lamport", lamport)
            .put("createdAt", createdAt)
            .put("retryCount", retryCount)
            .put("lastError", lastError ?: JSONObject.NULL)
            .put("nextAttemptAt", nextAttemptAt ?: JSONObject.NULL)
            .put("blockedAt", blockedAt ?: JSONObject.NULL)

        companion object {
            fun fromJson(o: JSONObject) = QueuedChange(
                changeId = o.getString("changeId"),
                entity = o.getString("entity"),
                rowId = o.getString("rowId"),
                operation = o.getString("operation"),
                payload = o.optJSONObject("payload"),
                lamport = o.optLong("lamport"),
                createdAt = o.optString("createdAt"),
                retryCount = o.optInt("retryCount"),
                lastError = o.optStringOrNull("lastError"),
                nextAttemptAt = o.optStringOrNull("nextAttemptAt"),
                blockedAt = o.optStringOrNull("blockedAt")
            )
        }
    }

    enum class Decision { APPLY, OWN, STALE, DEFERRED }

    data class ReduceResult(
        val state: List<RowState>,
        val cursor: Long,
        val applied: List<Change>,
        val deferred: List<Change>
    )

    /** Total order on versions: lamport first, client id as the tiebreak. */
    fun compareOrder(leftLamport: Long, leftClientId: String, rightLamport: Long, rightClientId: String): Int {
        if (leftLamport != rightLamport) return if (leftLamport < rightLamport) -1 else 1
        if (leftClientId == rightClientId) return 0
        return if (leftClientId < rightClientId) -1 else 1
    }

    fun decide(change: Change, state: RowState?, pending: Set<String>, ownClientId: String): Decision {
        if (change.clientId == ownClientId) return Decision.OWN
        if (state != null &&
            compareOrder(state.lamport, state.clientId, change.lamport, change.clientId) >= 0
        ) return Decision.STALE
        if (change.key in pending) return Decision.DEFERRED
        return Decision.APPLY
    }

    fun toRowState(change: Change) = RowState(
        change.entity, change.rowId, change.operation, change.payload, change.lamport, change.clientId, change.seq
    )

    /**
     * Fold a batch of log rows into the applied state. The cursor advances past every row in
     * the batch, including the ones that were ignored: the log has been read, whatever was
     * decided about its contents. Deferred rows must be persisted by the caller before the
     * cursor is stored, or a crash loses them.
     */
    fun reduceChanges(
        ownClientId: String,
        cursor: Long,
        state: List<RowState>,
        pending: Set<String>,
        changes: List<Change>
    ): ReduceResult {
        val next = state.toMutableList()
        val applied = mutableListOf<Change>()
        val deferred = mutableListOf<Change>()
        var high = cursor
        for (change in changes.sortedBy { it.seq }) {
            if (change.seq <= cursor) continue
            val current = next.firstOrNull { it.key == change.key }
            when (decide(change, current, pending, ownClientId)) {
                Decision.APPLY -> {
                    val index = next.indexOfFirst { it.key == change.key }
                    val row = toRowState(change)
                    if (index == -1) next.add(row) else next[index] = row
                    applied.add(change)
                }
                Decision.DEFERRED -> deferred.add(change)
                else -> Unit
            }
            if (change.seq > high) high = change.seq
        }
        return ReduceResult(next, high, applied, deferred)
    }

    /**
     * A snapshot is the whole materialized state, so it replaces what this device knew,
     * except for rows with an unpublished local edit: those exist only here, and letting
     * the snapshot overwrite them would discard work that has not been published yet.
     */
    fun reduceSnapshot(
        pending: Set<String>,
        state: List<RowState>,
        rows: List<RowState>,
        head: Long
    ): ReduceResult {
        val next = state.filter { it.key in pending }.toMutableList()
        for (row in rows) {
            if (row.key in pending) continue
            val index = next.indexOfFirst { it.key == row.key }
            if (index == -1) next.add(row) else next[index] = row
        }
        return ReduceResult(next, head, emptyList(), emptyList())
    }

    /** One queued change per row, keeping the highest version, in queue order. */
    fun coalesce(changes: List<QueuedChange>): List<QueuedChange> {
        val newest = LinkedHashMap<String, QueuedChange>()
        for (change in changes) {
            val current = newest[change.key]
            if (current == null || compareOrder(current.lamport, "", change.lamport, change.createdAt) < 0) {
                newest[change.key] = change
            }
        }
        return newest.values.toList()
    }

    /** 5s doubling to a 5 minute ceiling. Deterministic, so it can be asserted. */
    fun retryDelayMs(attempts: Int): Long {
        if (attempts <= 0) return 0
        val shift = minOf(attempts - 1, 10)
        return minOf(300_000L, 5_000L * (1L shl shift))
    }

    fun isDue(change: QueuedChange, nowMillis: Long): Boolean {
        if (change.blockedAt != null) return false
        val next = change.nextAttemptAt ?: return true
        return runCatching { Instant.parse(next).toEpochMilli() }.getOrNull()?.let { it <= nowMillis } ?: true
    }

    fun retry(change: QueuedChange, error: String, nowMillis: Long, maxRetries: Int): QueuedChange {
        val attempts = change.retryCount + 1
        val next = change.copy(
            retryCount = attempts,
            lastError = error,
            nextAttemptAt = isoTime(nowMillis + retryDelayMs(attempts))
        )
        return if (attempts >= maxRetries) next.copy(nextAttemptAt = null, blockedAt = next.nextAttemptAt) else next
    }

    /** Newest version of each row in a batch, in sequence order. */
    fun latest(changes: List<Change>): List<Change> {
        val newest = LinkedHashMap<String, Change>()
        for (change in changes) {
            val current = newest[change.key]
            if (current == null || current.seq < change.seq) newest[change.key] = change
        }
        return newest.values.sortedBy { it.seq }
    }

    fun parseChange(o: JSONObject): Change? {
        val entity = o.optString("entity")
        val operation = o.optString("operation")
        val rowId = o.optString("row_id", o.optString("rowId"))
        val changeId = o.optString("change_id", o.optString("changeId"))
        val clientId = o.optString("client_id", o.optString("clientId"))
        if (entity !in ENTITIES || (operation != "put" && operation != "delete")) return null
        if (rowId.isEmpty() || changeId.isEmpty() || clientId.isEmpty()) return null
        if (!o.has("seq")) return null
        val payload = o.optJSONObject("payload")
        if (operation == "put" && payload == null) return null
        return Change(
            seq = o.optLong("seq"),
            changeId = changeId,
            clientId = clientId,
            entity = entity,
            rowId = rowId,
            operation = operation,
            payload = payload,
            lamport = o.optLong("lamport"),
            createdAt = o.optString("created_at", o.optString("createdAt"))
        )
    }

    fun parseRowState(o: JSONObject): RowState? {
        val entity = o.optString("entity")
        val operation = o.optString("operation")
        val rowId = o.optString("row_id", o.optString("rowId"))
        if (entity !in ENTITIES || (operation != "put" && operation != "delete")) return null
        if (rowId.isEmpty() || !o.has("seq")) return null
        val payload = o.optJSONObject("payload")
        if (operation == "put" && payload == null) return null
        return RowState(
            entity, rowId, operation, payload, o.optLong("lamport"),
            o.optString("client_id", o.optString("clientId")), o.optLong("seq")
        )
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
