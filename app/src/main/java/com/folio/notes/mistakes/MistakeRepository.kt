package com.folio.notes.mistakes

import com.folio.notes.sync.ReadResult
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
    val remoteCursor: Long = 0L
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
    /** Fetch only locally edited rows so direct-table CAS uses the current row stamp. */
    suspend fun fetch(userId: String, ids: Set<String>): List<RemoteMistakeRow>
    /** Compare-and-set an existing active row. False means a concurrent web change: retry later. */
    suspend fun update(userId: String, expected: RemoteMistakeRow, payload: ExamTrackMistake): Boolean
    /** Soft-delete an existing active row via `deleted_at`. False means retry later, like [update]. */
    suspend fun delete(userId: String, expected: RemoteMistakeRow, deletedAt: String): Boolean
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
        val next = if (m.reviewHistory.any { it.id == attempt.reviewId }) m else MistakeScheduler.recordMistakeReview(m, rating, at, attempt.reviewId)
        store.save(user, c.copy(mistakes = c.mistakes + (m.id to next), pending = c.pending + m.id,
            attempts = c.attempts.filterNot { it.reviewId == attempt.reviewId } + completed))
        completed
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
        store.save(user, c.copy(
            mistakes = c.mistakes - mistakeId,
            pending = c.pending - mistakeId,
            tombstones = c.tombstones + (mistakeId to at),
            pendingDeletes = c.pendingDeletes + mistakeId,
        ))
    }
    suspend fun sync(user: String): SyncResult = lock.withLock {
        val initial = store.load(user)
        val mistakes = initial.mistakes.toMutableMap()
        val pending = initial.pending.toMutableSet()
        val tombstones = initial.tombstones.toMutableMap()
        val pendingDeletes = initial.pendingDeletes.toMutableSet()
        val contexts = initial.contexts.toMutableMap()
        var invalid = 0
        var updated = 0
        var cursor = initial.remoteCursor
        var pages = 0

        fun applyRow(entity: String, id: String, operation: String, rawPayload: String?, deletedStamp: String?) {
            if (operation == "delete") {
                if (entity == "mistakes") {
                    if (mistakes.remove(id) != null) updated++
                    pending.remove(id)
                    pendingDeletes.remove(id)
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
            if (id in pendingDeletes) return
            val next = if (id in pending && mistakes[id] != null) {
                preserveRemoteFields(RemoteMistakeRow(id, raw, incoming.updatedAt, null), mistakes.getValue(id))
            } else incoming
            if (mistakes[id]?.originalJson != next.originalJson) updated++
            mistakes[id] = next
            // Feed order, rather than device timestamps, determines a later restore.
            tombstones.remove(id)
        }

        while (pages++ < 100) {
            val page = remote.read(user, cursor, 500)
            if (page.mode == "snapshot") {
                val localPending = mistakes.filterKeys { it in pending }
                mistakes.clear(); mistakes.putAll(localPending)
                contexts.clear()
                tombstones.keys.retainAll(pendingDeletes)
                page.rows.forEach { row ->
                    if (row.entity == "mistakes" || row.entity == "attempts")
                        applyRow(row.entity, row.rowId, row.operation, row.payload?.toString(), null)
                }
                cursor = page.head
                break
            }
            page.changes.sortedBy { it.seq }.forEach { change ->
                if (change.entity == "mistakes" || change.entity == "attempts")
                    applyRow(change.entity, change.rowId, change.operation, change.payload?.toString(), change.createdAt)
            }
            cursor = page.changes.fold(cursor) { value, change -> maxOf(value, change.seq) }
            if (cursor >= page.head || page.changes.size < 500) break
        }

        var c = initial.copy(mistakes = mistakes.toMap(), pending = pending.toSet(),
            tombstones = tombstones.toMap(), contexts = contexts.toMap(), pendingDeletes = pendingDeletes.toSet(),
            remoteCursor = maxOf(initial.remoteCursor, cursor))
        // Persist feed rows and their cursor atomically before any network write.
        store.save(user, c)

        val rows = remote.fetch(user, pending + pendingDeletes)
        val activeRemoteIds = rows.filter { it.deletedAt == null }.mapTo(mutableSetOf()) { it.id }
        val usable = mutableMapOf<String, RemoteMistakeRow>()
        for (row in rows) {
            if (runCatching { timestamp(row.deletedAt ?: row.updatedAt) }.isFailure) { invalid++; continue }
            if (row.deletedAt != null) {
                // Native reviews never undelete cards, even if an offline review is newer.
                if (mistakes.remove(row.id) != null) updated++
                pending.remove(row.id); pendingDeletes.remove(row.id); tombstones[row.id] = row.deletedAt
                continue
            }
            val m = row.payload?.let { ExamTrackMistakeCodec.decode(it, row.id) }
            if (m == null) { invalid++; continue }
            usable[row.id] = row
            if (row.id in pending) {
                val local = mistakes[row.id]
                if (local != null) mistakes[row.id] = preserveRemoteFields(row, local)
            }
            // A queued local delete wins over the still-active remote row: never resurrect it here.
            if (row.id in pendingDeletes) continue
        }
        c = c.copy(mistakes = mistakes.toMap(), pending = pending.toSet(), tombstones = tombstones.toMap(),
            pendingDeletes = pendingDeletes.toSet())
        // Persist downloads, the cursor and tombstones before uploading. An interrupted upload stays queued.
        store.save(user, c)
        for (id in pending.toList()) {
            val expected = usable[id] ?: continue // Never recreate a missing or malformed remote row.
            val local = mistakes[id] ?: continue
            val merged = preserveRemoteFields(expected, local)
            if (remote.update(user, expected, merged)) {
                mistakes[id] = merged; pending.remove(id)
                c = c.copy(mistakes = mistakes.toMap(), pending = pending.toSet())
                store.save(user, c)
            }
        }
        for (id in pendingDeletes.toList()) {
            val expected = usable[id]
            if (expected == null) {
                // A malformed or invalid active row cannot be safely compared and deleted.
                // Keep the tombstone queued instead of silently losing the user's deletion.
                if (id in activeRemoteIds) continue
                // Already gone remotely (or never existed): locally consistent, nothing to write.
                pendingDeletes.remove(id)
                c = c.copy(pendingDeletes = pendingDeletes.toSet())
                store.save(user, c)
                continue
            }
            val deletedAt = tombstones[id] ?: continue
            if (remote.delete(user, expected, deletedAt)) {
                pendingDeletes.remove(id)
                tombstones[id] = deletedAt
                c = c.copy(pendingDeletes = pendingDeletes.toSet(), tombstones = tombstones.toMap())
                store.save(user, c)
            }
        }
        store.save(user, c.copy(contexts = contexts, lastSyncedAt = isoTime(), remoteCursor = maxOf(c.remoteCursor, cursor)))
        SyncResult(mistakes.size, updated, invalid, pending.size + pendingDeletes.size)
    }
    private fun preserveRemoteFields(remote: RemoteMistakeRow, local: ExamTrackMistake): ExamTrackMistake {
        val output = JSONObject(requireNotNull(remote.payload))
        val source = JSONObject(local.originalJson)
        // Folio only edits scheduling. Keep the latest remote question, attachments and future fields.
        listOf("reviewHistory", "reviewState", "intervalDays", "easeFactor", "repetitions", "lapses",
            "lastReviewedAt", "resolved", "dueAt", "updatedAt").forEach { key ->
            if (source.has(key)) output.put(key, source.get(key))
        }
        return requireNotNull(ExamTrackMistakeCodec.decode(output.toString()))
    }
}

object MistakeCacheCodec {
    const val VERSION = 3
    fun encode(c: MistakeCache): String = JSONObject().put("version", VERSION).apply {
        put("mistakes", JSONArray(c.mistakes.values.map { JSONObject(it.originalJson) }))
        put("pending", JSONArray(c.pending.toList()))
        put("tombstones", JSONObject(c.tombstones))
        put("pendingDeletes", JSONArray(c.pendingDeletes.toList()))
        put("attempts", JSONArray(c.attempts.map { it.encode() }))
        put("contexts", JSONObject().apply { c.contexts.forEach { (id, v) -> put(id,
            JSONObject().put("subject", v.subject).put("title", v.title).put("paper", v.paper)) } })
        put("lastSyncedAt", c.lastSyncedAt)
        put("remoteCursor", c.remoteCursor)
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
        return MistakeCache(mistakes, pending, tombstones, attempts, contexts, o.opt("lastSyncedAt") as? String,
            pendingDeletes, o.optLong("remoteCursor").coerceAtLeast(0L))
    }
}
