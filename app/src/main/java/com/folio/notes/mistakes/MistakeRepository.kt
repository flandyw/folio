package com.folio.notes.mistakes

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
    val lastSyncedAt: String? = null
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
    suspend fun fetch(userId: String): List<RemoteMistakeRow>
    /** Compare-and-set an existing active row. False means a concurrent web change: retry later. */
    suspend fun update(userId: String, expected: RemoteMistakeRow, payload: ExamTrackMistake): Boolean
    suspend fun contexts(userId: String): Map<String, ExamContext>
}

class MistakeRepository(private val store: MistakeCacheStore, private val remote: ExamTrackRemote) {
    private val lock = Mutex()
    suspend fun cache(user: String) = lock.withLock { store.load(user) }
    suspend fun addAttempt(user: String, attempt: LocalMistakeReviewAttempt) = lock.withLock {
        require(attempt.userId == user)
        val c = store.load(user)
        store.save(user, c.copy(attempts = c.attempts.filterNot { it.reviewId == attempt.reviewId } + attempt))
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
    suspend fun sync(user: String): SyncResult = lock.withLock {
        val initial = store.load(user)
        val mistakes = initial.mistakes.toMutableMap()
        val pending = initial.pending.toMutableSet()
        val tombstones = initial.tombstones.toMutableMap()
        var invalid = 0
        var updated = 0
        val rows = remote.fetch(user)
        val usable = mutableMapOf<String, RemoteMistakeRow>()
        for (row in rows) {
            if (runCatching { timestamp(row.deletedAt ?: row.updatedAt) }.isFailure) { invalid++; continue }
            if (row.deletedAt != null) {
                // Native reviews never undelete cards, even if an offline review is newer.
                if (mistakes.remove(row.id) != null) updated++
                pending.remove(row.id); tombstones[row.id] = row.deletedAt
                continue
            }
            val m = row.payload?.let { ExamTrackMistakeCodec.decode(it, row.id) }
            if (m == null) { invalid++; continue }
            if (tombstones[row.id]?.let { timestamp(it) >= timestamp(row.updatedAt) } == true) continue
            tombstones.remove(row.id)
            usable[row.id] = row
            val local = mistakes[row.id]
            if (local == null || timestamp(row.updatedAt) > timestamp(local.updatedAt)) {
                mistakes[row.id] = m; pending.remove(row.id); updated++
            }
        }
        var c = initial.copy(mistakes = mistakes.toMap(), pending = pending.toSet(), tombstones = tombstones.toMap())
        // Persist downloads/tombstones before uploading. An interrupted upload stays queued.
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
        val contexts = remote.contexts(user)
        store.save(user, c.copy(contexts = contexts, lastSyncedAt = isoTime()))
        SyncResult(mistakes.size, updated, invalid, pending.size)
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
    const val VERSION = 1
    fun encode(c: MistakeCache): String = JSONObject().put("version", VERSION).apply {
        put("mistakes", JSONArray(c.mistakes.values.map { JSONObject(it.originalJson) }))
        put("pending", JSONArray(c.pending.toList()))
        put("tombstones", JSONObject(c.tombstones))
        put("attempts", JSONArray(c.attempts.map { it.encode() }))
        put("contexts", JSONObject().apply { c.contexts.forEach { (id, v) -> put(id,
            JSONObject().put("subject", v.subject).put("title", v.title).put("paper", v.paper)) } })
        put("lastSyncedAt", c.lastSyncedAt)
    }.toString()
    fun decode(raw: String): MistakeCache {
        val o = JSONObject(raw)
        require(o.getInt("version") == VERSION) { "Unsupported mistake cache version" }
        val mistakes = o.getJSONArray("mistakes").let { a -> (0 until a.length()).mapNotNull {
            ExamTrackMistakeCodec.decode(a.getJSONObject(it).toString())
        } }.associateBy { it.id }
        val pending = o.getJSONArray("pending").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val tombstones = o.getJSONObject("tombstones").let { t -> t.keys().asSequence().associateWith { t.getString(it) } }
        val attempts = o.getJSONArray("attempts").let { a -> (0 until a.length()).map { LocalMistakeReviewAttempt.decode(a.getJSONObject(it)) } }
        val contexts = o.optJSONObject("contexts")?.let { c -> c.keys().asSequence().associateWith {
            val v = c.getJSONObject(it); ExamContext(v.getString("subject"), v.getString("title"), v.getString("paper"))
        } }.orEmpty()
        return MistakeCache(mistakes, pending, tombstones, attempts, contexts, o.opt("lastSyncedAt") as? String)
    }
}
