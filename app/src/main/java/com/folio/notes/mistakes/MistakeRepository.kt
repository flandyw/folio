package com.folio.notes.mistakes

import com.folio.notes.sync.ApplyResult
import com.folio.notes.sync.ReadResult
import com.folio.notes.sync.SyncProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** All state belongs to exactly one Supabase identity. Auth tokens never enter this store. */
data class MistakeCache(
    val mistakes: Map<String, ExamTrackMistake> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val tombstones: Map<String, String> = emptyMap(),
    val attempts: List<LocalMistakeReviewAttempt> = emptyList(),
    val contexts: Map<String, ExamContext> = emptyMap(),
    val lastSyncedAt: String? = null,
    /** Local soft-deletes still waiting for their remote `deleted_at` write. */
    val pendingDeletes: Set<String> = emptySet(),
    /** Durable cursor for the shared ordered sync feed. */
    val remoteCursor: Long = 0L,
    /** Last feed sequence per row, used as the RPC compare-and-set version. */
    val rowSequences: Map<String, Long> = emptyMap(),
    val versionsBootstrapped: Boolean = false,
    /** Stable, durable generic mutations; a lost response retries the same change id. */
    val outbox: Map<String, SyncProtocol.QueuedChange> = emptyMap(),
    val reviewPreferences: MistakeReviewPreferences = MistakeReviewPreferences(),
    val preferencesSequence: Long = 0L,
    val preferencesChange: SyncProtocol.QueuedChange? = null,
    val preferencesFeedBootstrapped: Boolean = false,
    val preferencesReplayCursor: Long? = null,
    val pendingDateOnly: Set<String> = emptySet()
)
data class ExamContext(val subject: String, val title: String, val paper: String)
data class RemoteMistakeRow(val id: String, val payload: String?, val updatedAt: String, val deletedAt: String?)
data class SyncResult(val total: Int, val updated: Int, val invalid: Int, val pending: Int) {
    override fun toString() = "Synced $total mistakes · $updated updated · $invalid invalid records skipped · $pending pending"
}
interface MistakeCacheStore {
    suspend fun load(userId: String): MistakeCache
    suspend fun save(userId: String, cache: MistakeCache)
}
interface ExamTrackRemote {
    suspend fun read(userId: String, cursor: Long, limit: Int = 500): ReadResult
    suspend fun apply(userId: String, change: SyncProtocol.QueuedChange): ApplyResult
}

class MistakeRepository(private val store: MistakeCacheStore, private val remote: ExamTrackRemote) {
    private val lock = Mutex()
    suspend fun cache(user: String) = lock.withLock { store.load(user) }
    suspend fun addAttempt(user: String, attempt: LocalMistakeReviewAttempt) = lock.withLock {
        require(attempt.userId == user)
        val c = store.load(user)
        store.save(user, c.copy(attempts = c.attempts.filterNot { it.reviewId == attempt.reviewId } + attempt))
    }
    /** Drops cached review references whose notebooks were purged; handwriting is already gone. */
    suspend fun removeAttemptsForNotebooks(user: String, notebookIds: Set<String>) = lock.withLock {
        if (notebookIds.isEmpty()) return@withLock
        val c = store.load(user)
        val kept = c.attempts.filterNot { it.practiceNotebookId in notebookIds }
        if (kept.size != c.attempts.size) store.save(user, c.copy(attempts = kept))
    }
    suspend fun rate(user: String, attempt: LocalMistakeReviewAttempt, rating: ReviewRating, at: String): LocalMistakeReviewAttempt = lock.withLock {
        require(attempt.userId == user)
        val c = store.load(user)
        val m = requireNotNull(c.mistakes[attempt.mistakeId]) { "This mistake was removed. Your handwriting is saved." }
        val completed = attempt.copy(completedAt = at, rating = rating.wire)
        // A retry after process interruption must not add the same logical review twice.
        val next = if (m.reviewHistory.any { it.id == attempt.reviewId }) m else MistakeScheduler.recordMistakeReview(m, rating, at, attempt.reviewId, c.reviewPreferences, c.mistakes.values.toList())
        val change = queuedMistakeChange(m.id, "put", JSONObject(next.originalJson), c.rowSequences[m.id], at)
        store.save(user, c.copy(mistakes = c.mistakes + (m.id to next), pending = c.pending + m.id,
            attempts = c.attempts.filterNot { it.reviewId == attempt.reviewId } + completed,
            outbox = c.outbox + (m.id to change), pendingDateOnly = c.pendingDateOnly - m.id))
        completed
    }
    suspend fun saveReviewPreferences(user: String, preferences: MistakeReviewPreferences) = lock.withLock {
        val c = store.load(user)
        val next = preferences.normalized().copy(updatedAt = isoTime())
        val change = queuedPreferencesChange(next, c.preferencesSequence)
        store.save(user, c.copy(reviewPreferences = next, preferencesChange = change))
    }
    suspend fun reduceReviews(user: String, days: Int, dailyTarget: Int): Int = lock.withLock {
        val c = store.load(user)
        val now = System.currentTimeMillis()
        val dates = MistakeScheduler.reduceReviews(c.mistakes.values.toList(), days, dailyTarget, now)
        val mistakes = c.mistakes.toMutableMap()
        val outbox = c.outbox.toMutableMap()
        for ((id, dueAt) in dates) {
            val original = requireNotNull(mistakes[id])
            val json = JSONObject(original.originalJson).put("dueAt", dueAt).put("updatedAt", isoTime(now))
            mistakes[id] = requireNotNull(ExamTrackMistakeCodec.decode(json.toString()))
            outbox[id] = queuedMistakeChange(id, "put", json, c.rowSequences[id], isoTime(now))
        }
        val preferences = c.reviewPreferences.copy(lighterUntil = isoTime(MistakeScheduler.reviewDayStart(now, days.toLong())),
            lighterDailyTarget = dailyTarget, updatedAt = isoTime(now))
        store.save(user, c.copy(reviewPreferences = preferences,
            preferencesChange = queuedPreferencesChange(preferences, c.preferencesSequence),
            mistakes = mistakes, outbox = outbox, pending = c.pending + dates.keys,
            pendingDateOnly = c.pendingDateOnly + (dates.keys - c.pending)))
        dates.size
    }
    /**
     * Soft-deletes a card: it leaves the local list immediately, drops any queued rating,
     * and queues a remote `deleted_at` write. Handwriting and cached attempts are kept,
     * exactly as when ExamTrack deletes the card on the web. Idempotent.
     */
    suspend fun delete(user: String, mistakeId: String, at: String) = lock.withLock {
        runCatching { timestamp(at) }.getOrElse { throw IllegalArgumentException("Bad delete time") }
        val c = store.load(user)
        if (c.mistakes[mistakeId] == null && c.tombstones[mistakeId] == null && mistakeId !in c.pendingDeletes) return@withLock
        val change = queuedMistakeChange(mistakeId, "delete", null, c.rowSequences[mistakeId], at)
        store.save(user, c.copy(
            mistakes = c.mistakes - mistakeId,
            pending = c.pending - mistakeId,
            tombstones = c.tombstones + (mistakeId to at),
            pendingDeletes = c.pendingDeletes + mistakeId,
            outbox = c.outbox + (mistakeId to change), pendingDateOnly = c.pendingDateOnly - mistakeId,
        ))
    }
    suspend fun sync(user: String): SyncResult = lock.withLock {
        val initial = store.load(user)
        val mistakes = initial.mistakes.toMutableMap()
        val pending = initial.pending.toMutableSet()
        val pendingDateOnly = initial.pendingDateOnly.toMutableSet()
        val tombstones = initial.tombstones.toMutableMap()
        val pendingDeletes = initial.pendingDeletes.toMutableSet()
        val contexts = initial.contexts.toMutableMap()
        val rowSequences = initial.rowSequences.toMutableMap()
        val outbox = initial.outbox.toMutableMap()
        var versionsBootstrapped = initial.versionsBootstrapped
        var preferences = initial.reviewPreferences
        var preferencesSequence = initial.preferencesSequence
        var preferencesChange = initial.preferencesChange
        var invalid = 0
        var updated = 0
        // Old versions kept only a cursor. Replay from zero once to rebuild per-row CAS versions.
        var cursor = if (versionsBootstrapped && initial.preferencesFeedBootstrapped) initial.remoteCursor else initial.preferencesReplayCursor ?: 0L
        var preferencesFeedBootstrapped = initial.preferencesFeedBootstrapped
        var pages = 0

        fun applyRow(entity: String, id: String, operation: String, rawPayload: String?, deletedStamp: String?, seq: Long) {
            if (entity == "user_state" && id == MistakeReviewPreferences.ROW_ID) {
                preferencesSequence = seq
                if (preferencesChange == null) preferences = MistakeReviewPreferences.decode(
                    rawPayload?.let { runCatching { JSONObject(it).optJSONObject("value") }.getOrNull() })
                else preferencesChange = preferencesChange?.copy(changeId = java.util.UUID.randomUUID().toString(), expectedSeq = seq)
                return
            }
            if (entity == "mistakes") rowSequences[id] = seq
            if (operation == "delete") {
                if (entity == "mistakes") {
                    if (mistakes.remove(id) != null) updated++
                    pending.remove(id)
                    pendingDateOnly.remove(id)
                    pendingDeletes.remove(id)
                    outbox.remove(id)
                    tombstones[id] = deletedStamp ?: tombstones[id] ?: ""
                } else if (entity == "attempts") contexts.remove(id)
                return
            }
            val raw = rawPayload ?: run { invalid++; return }
            if (entity == "attempts") {
                val payload = runCatching { JSONObject(raw) }.getOrElse { invalid++; return }
                contexts[id] = ExamContext(payload.optString("subject", ""), payload.optString("title", ""), payload.optString("paper", ""))
                return
            }
            if (entity != "mistakes") return
            val incoming = ExamTrackMistakeCodec.decode(raw, id)
            if (incoming == null || runCatching { timestamp(incoming.updatedAt) }.isFailure) { invalid++; return }
            if (id in pendingDeletes) {
                outbox[id]?.let { queued -> outbox[id] = queued.copy(changeId = java.util.UUID.randomUUID().toString(), expectedSeq = seq) }
                return
            }
            val local = mistakes[id]
            val next = if (id in pending && local != null) {
                preserveRemoteFields(RemoteMistakeRow(id, raw, incoming.updatedAt, null), local, id in pendingDateOnly)
            } else incoming
            if (local?.originalJson != next.originalJson) updated++
            mistakes[id] = next
            if (id in pending) outbox[id]?.let { queued ->
                outbox[id] = queued.copy(changeId = java.util.UUID.randomUUID().toString(),
                    payload = JSONObject(next.originalJson), expectedSeq = seq)
            }
            tombstones.remove(id)
        }

        while (pages++ < 100) {
            val page = remote.read(user, cursor, 500)
            if (page.mode == "snapshot") {
                val localPending = mistakes.filterKeys { it in pending }
                mistakes.clear(); mistakes.putAll(localPending)
                contexts.clear()
                rowSequences.clear()
                preferencesSequence = 0L
                if (preferencesChange == null) preferences = MistakeReviewPreferences()
                tombstones.keys.retainAll(pendingDeletes)
                page.rows.forEach { row ->
                    if (row.entity == "mistakes" || row.entity == "attempts" || row.entity == "user_state")
                        applyRow(row.entity, row.rowId, row.operation, row.payload?.toString(), null, row.seq)
                }
                cursor = page.head
                versionsBootstrapped = true
                preferencesFeedBootstrapped = true
                break
            }
            page.changes.sortedBy { it.seq }.forEach { change ->
                if (change.entity == "user_state" && change.rowId == MistakeReviewPreferences.ROW_ID &&
                    preferencesChange?.changeId == change.changeId) preferencesChange = null
                if (change.entity != "mistakes" && change.entity != "attempts" && change.entity != "user_state") return@forEach
                val queued = outbox[change.rowId]
                if (queued?.changeId == change.changeId) {
                    outbox.remove(change.rowId)
                    if (change.operation == "delete") pendingDeletes.remove(change.rowId) else pending.remove(change.rowId)
                    pendingDateOnly.remove(change.rowId)
                }
                applyRow(change.entity, change.rowId, change.operation, change.payload?.toString(), change.createdAt, change.seq)
            }
            cursor = page.changes.fold(cursor) { value, change -> maxOf(value, change.seq) }
            if (cursor >= page.head || page.changes.size < 500) {
                if (cursor >= page.head) { versionsBootstrapped = true; preferencesFeedBootstrapped = true }
                break
            }
        }

        // Upgrade old queued sets into durable, idempotent mutations after the cursor rebuild.
        for (id in pending.toList()) if (id !in outbox && id in rowSequences) {
            mistakes[id]?.let { outbox[id] = queuedMistakeChange(id, "put", JSONObject(it.originalJson), rowSequences[id]!!, it.updatedAt) }
        }
        for (id in pendingDeletes.toList()) {
            if (id !in rowSequences) {
                pendingDeletes.remove(id)
                outbox.remove(id)
            } else if (id !in outbox) {
                outbox[id] = queuedMistakeChange(id, "delete", null, rowSequences[id]!!, tombstones[id] ?: isoTime())
            }
        }
        var cache = initial.copy(mistakes = mistakes.toMap(), pending = pending.toSet(),
            tombstones = tombstones.toMap(), contexts = contexts.toMap(), pendingDeletes = pendingDeletes.toSet(),
            remoteCursor = maxOf(initial.remoteCursor, cursor), rowSequences = rowSequences.toMap(),
            versionsBootstrapped = versionsBootstrapped, outbox = outbox.toMap(), pendingDateOnly = pendingDateOnly.toSet(),
            reviewPreferences = preferences, preferencesSequence = preferencesSequence,
            preferencesChange = preferencesChange, preferencesFeedBootstrapped = preferencesFeedBootstrapped,
            preferencesReplayCursor = if (preferencesFeedBootstrapped) null else cursor)
        // Feed state, per-row versions, and the cursor are durable before sending any mutation.
        store.save(user, cache)

        repeat(3) {
            val change = preferencesChange ?: return@repeat
            val result = remote.apply(user, change)
            val receipt = result.receipts.firstOrNull { it.changeId == change.changeId }
            if (receipt != null) {
                preferencesSequence = receipt.seq
                preferencesChange = null
            } else {
                val stale = result.stale.firstOrNull { it.changeId == change.changeId } ?: return@repeat
                preferencesSequence = stale.current?.seq ?: 0L
                preferencesChange = change.copy(changeId = java.util.UUID.randomUUID().toString(), expectedSeq = preferencesSequence)
            }
            cache = cache.copy(preferencesSequence = preferencesSequence, preferencesChange = preferencesChange)
            store.save(user, cache)
        }

        for ((id, initialChange) in outbox.toMap()) {
            var change = initialChange
            if (change.expectedSeq == null) {
                val seq = rowSequences[id]
                if (seq == null) {
                    if (change.operation == "delete") {
                        outbox.remove(id); pendingDeletes.remove(id)
                        cache = cache.copy(outbox = outbox.toMap(), pendingDateOnly = pendingDateOnly.toSet(), pendingDeletes = pendingDeletes.toSet())
                        store.save(user, cache)
                    }
                    continue // Never recreate a missing or malformed remote mistake.
                }
                change = change.copy(expectedSeq = seq)
                outbox[id] = change
            }
            var attempts = 0
            while (attempts < 3) {
                val result = remote.apply(user, change)
                val receipt = result.receipts.firstOrNull { it.changeId == change.changeId }
                if (receipt != null) {
                    rowSequences[id] = receipt.seq
                    outbox.remove(id)
                    if (change.operation == "delete") pendingDeletes.remove(id) else pending.remove(id)
                    pendingDateOnly.remove(id)
                    cache = cache.copy(mistakes = mistakes.toMap(), pending = pending.toSet(),
                        tombstones = tombstones.toMap(), pendingDeletes = pendingDeletes.toSet(),
                        rowSequences = rowSequences.toMap(), outbox = outbox.toMap(), pendingDateOnly = pendingDateOnly.toSet())
                    store.save(user, cache)
                    break
                }
                val stale = result.stale.firstOrNull { it.changeId == change.changeId } ?: break
                val current = stale.current
                if (current == null || current.operation == "delete") {
                    rowSequences.remove(id)
                    outbox.remove(id)
                    if (change.operation == "delete" || current?.operation == "delete") {
                        pendingDeletes.remove(id); pending.remove(id)
                        if (current?.operation == "delete") tombstones[id] = tombstones[id] ?: isoTime()
                    } else pending.remove(id) // Do not resurrect a row deleted by the web client.
                    if (change.operation != "delete") mistakes.remove(id)
                    cache = cache.copy(mistakes = mistakes.toMap(), pending = pending.toSet(),
                        tombstones = tombstones.toMap(), pendingDeletes = pendingDeletes.toSet(),
                        rowSequences = rowSequences.toMap(), outbox = outbox.toMap(), pendingDateOnly = pendingDateOnly.toSet())
                    store.save(user, cache)
                    break
                }
                rowSequences[id] = current.seq
                val remoteJson = current.payload?.toString()
                val remoteMistake = remoteJson?.let { ExamTrackMistakeCodec.decode(it, id) }
                if (remoteMistake == null || runCatching { timestamp(remoteMistake.updatedAt) }.isFailure) {
                    invalid++
                    break
                }
                val remoteRow = RemoteMistakeRow(id, remoteJson, remoteMistake.updatedAt, null)
                if (change.operation == "put") {
                    val local = mistakes[id]
                    val merged = if (local == null) remoteMistake else preserveRemoteFields(remoteRow, local, id in pendingDateOnly)
                    mistakes[id] = merged
                    pending.add(id)
                    change = queuedMistakeChange(id, "put", JSONObject(merged.originalJson), current.seq, merged.updatedAt)
                } else {
                    change = change.copy(changeId = java.util.UUID.randomUUID().toString(), expectedSeq = current.seq)
                }
                outbox[id] = change
                attempts++
                cache = cache.copy(mistakes = mistakes.toMap(), pending = pending.toSet(),
                    pendingDeletes = pendingDeletes.toSet(), rowSequences = rowSequences.toMap(), outbox = outbox.toMap(), pendingDateOnly = pendingDateOnly.toSet())
                store.save(user, cache) // Persist the rebase before retrying it.
            }
        }
        cache = cache.copy(mistakes = mistakes.toMap(), pending = pending.toSet(), tombstones = tombstones.toMap(),
            contexts = contexts.toMap(), pendingDeletes = pendingDeletes.toSet(), rowSequences = rowSequences.toMap(),
            versionsBootstrapped = versionsBootstrapped, outbox = outbox.toMap(), pendingDateOnly = pendingDateOnly.toSet(),
            lastSyncedAt = isoTime(), remoteCursor = maxOf(cache.remoteCursor, cursor))
        store.save(user, cache)
        SyncResult(mistakes.size, updated, invalid, pending.size + pendingDeletes.size + if (preferencesChange != null) 1 else 0)
    }
    private fun preserveRemoteFields(remote: RemoteMistakeRow, local: ExamTrackMistake, dateOnly: Boolean = false): ExamTrackMistake {
        val output = JSONObject(requireNotNull(remote.payload))
        val source = JSONObject(local.originalJson)
        if (dateOnly) {
            val due = source.getString("dueAt")
            val remoteDue = ExamTrackMistakeCodec.decode(requireNotNull(remote.payload))?.let { MistakeScheduler.getMistakeSchedule(it).dueAt }
            output.put("dueAt", if (remoteDue != null && timestamp(remoteDue) > timestamp(due)) remoteDue else due)
            output.put("updatedAt", maxOf(source.getString("updatedAt"), output.optString("updatedAt", "")))
            return requireNotNull(ExamTrackMistakeCodec.decode(output.toString()))
        }
        // Folio only edits scheduling. Keep the latest remote question, attachments and future fields.
        listOf("reviewHistory", "reviewState", "intervalDays", "easeFactor", "repetitions", "lapses",
            "lastReviewedAt", "resolved", "dueAt", "updatedAt").forEach { key ->
            if (source.has(key)) output.put(key, source.get(key))
        }
        return requireNotNull(ExamTrackMistakeCodec.decode(output.toString()))
    }
}


private fun queuedPreferencesChange(preferences: MistakeReviewPreferences, sequence: Long) = SyncProtocol.QueuedChange(
    changeId = java.util.UUID.randomUUID().toString(), entity = "user_state", rowId = MistakeReviewPreferences.ROW_ID,
    operation = "put", payload = JSONObject().put("value", preferences.encode()).put("updated_at", preferences.updatedAt),
    lamport = 0L, createdAt = preferences.updatedAt, expectedSeq = sequence,
)

private fun queuedMistakeChange(
    id: String,
    operation: String,
    payload: JSONObject?,
    expectedSeq: Long?,
    at: String
): SyncProtocol.QueuedChange = SyncProtocol.QueuedChange(
    changeId = java.util.UUID.randomUUID().toString(), entity = "mistakes", rowId = id,
    operation = operation, payload = payload, lamport = 0L, createdAt = at, expectedSeq = expectedSeq
)

object MistakeCacheCodec {
    const val VERSION = 4
    fun encode(c: MistakeCache): String = JSONObject().put("version", VERSION).apply {
        put("reviewPreferences", c.reviewPreferences.encode())
        put("preferencesSequence", c.preferencesSequence)
        put("preferencesChange", c.preferencesChange?.toJson())
        put("preferencesReplayCursor", c.preferencesReplayCursor)
        put("preferencesFeedBootstrapped", c.preferencesFeedBootstrapped)
        put("pendingDateOnly", JSONArray(c.pendingDateOnly.toList()))
        put("mistakes", JSONArray(c.mistakes.values.map { JSONObject(it.originalJson) }))
        put("pending", JSONArray(c.pending.toList()))
        put("tombstones", JSONObject(c.tombstones))
        put("pendingDeletes", JSONArray(c.pendingDeletes.toList()))
        put("attempts", JSONArray(c.attempts.map { it.encode() }))
        put("contexts", JSONObject().apply { c.contexts.forEach { (id, v) -> put(id,
            JSONObject().put("subject", v.subject).put("title", v.title).put("paper", v.paper)) } })
        put("lastSyncedAt", c.lastSyncedAt)
        put("remoteCursor", c.remoteCursor)
        put("rowSequences", JSONObject(c.rowSequences))
        put("versionsBootstrapped", c.versionsBootstrapped)
        put("outbox", JSONArray(c.outbox.values.map { it.toJson() }))
    }.toString()
    fun decode(raw: String): MistakeCache {
        val o = JSONObject(raw)
        val version = o.getInt("version")
        require(version in 1..VERSION) { "Unsupported mistake cache version" }
        val mistakes = o.getJSONArray("mistakes").let { a -> (0 until a.length()).mapNotNull {
            ExamTrackMistakeCodec.decode(a.getJSONObject(it).toString())
        } }.associateBy { it.id }
        val pending = o.getJSONArray("pending").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val tombstones = o.getJSONObject("tombstones").let { t -> t.keys().asSequence().associateWith { t.getString(it) } }
        val attempts = o.getJSONArray("attempts").let { a -> (0 until a.length()).map { LocalMistakeReviewAttempt.decode(a.getJSONObject(it)) } }
        val contexts = o.optJSONObject("contexts")?.let { c -> c.keys().asSequence().associateWith {
            val v = c.getJSONObject(it); ExamContext(v.getString("subject"), v.getString("title"), v.getString("paper"))
        } }.orEmpty()
        val pendingDeletes = o.optJSONArray("pendingDeletes")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        val rowSequences = o.optJSONObject("rowSequences")?.let { seqs -> seqs.keys().asSequence().mapNotNull { key ->
            val seq = seqs.optLong(key, -1L); if (seq >= 0) key to seq else null
        }.toMap() }.orEmpty()
        val outbox = o.optJSONArray("outbox")?.let { a -> (0 until a.length()).mapNotNull { index ->
            runCatching { SyncProtocol.QueuedChange.fromJson(a.getJSONObject(index)) }.getOrNull()
        }.associateBy { it.rowId } }.orEmpty()
        return MistakeCache(mistakes = mistakes, pending = pending, tombstones = tombstones, attempts = attempts,
            contexts = contexts, lastSyncedAt = o.opt("lastSyncedAt") as? String,
            pendingDeletes = pendingDeletes, remoteCursor = o.optLong("remoteCursor").coerceAtLeast(0L),
            rowSequences = rowSequences, versionsBootstrapped = o.optBoolean("versionsBootstrapped"), outbox = outbox,
            reviewPreferences = MistakeReviewPreferences.decode(o.optJSONObject("reviewPreferences")),
            preferencesSequence = o.optLong("preferencesSequence").coerceAtLeast(0),
            preferencesChange = o.optJSONObject("preferencesChange")?.let { SyncProtocol.QueuedChange.fromJson(it) },
            preferencesFeedBootstrapped = o.optBoolean("preferencesFeedBootstrapped"),
            preferencesReplayCursor = if (o.isNull("preferencesReplayCursor")) null else o.optLong("preferencesReplayCursor"),
            pendingDateOnly = o.optJSONArray("pendingDateOnly")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty())
    }
}
