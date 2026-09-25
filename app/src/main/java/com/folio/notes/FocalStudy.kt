package com.folio.notes

import android.content.Context
import android.util.AtomicFile
import com.folio.notes.mistakes.EncryptedExamTrackSession
import com.folio.notes.mistakes.ExamTrackSessionLifecycle
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Focal's built-in subject IDs are stable sync IDs, not Folio enum names. */
data class FocalSubject(val id: String, val name: String)

object FocalSubjects {
    val builtIn = listOf(
        FocalSubject("eng", "English"), FocalSubject("eng-lang", "English Language"),
        FocalSubject("lit", "Literature"), FocalSubject("mm", "Mathematical Methods"),
        FocalSubject("sm", "Specialist Mathematics"), FocalSubject("gm", "General Mathematics"),
        FocalSubject("csl", "Chinese Second Language"), FocalSubject("pe", "Physical Education"),
        FocalSubject("chem", "Chemistry"), FocalSubject("phys", "Physics"),
        FocalSubject("bio", "Biology"), FocalSubject("psych", "Psychology"),
        FocalSubject("hist", "History"), FocalSubject("geo", "Geography"),
        FocalSubject("econ", "Economics"), FocalSubject("bm", "Business Management")
    )
    private val folioIds = mapOf(
        VceSubject.GENERAL_MATHS to "gm", VceSubject.MATHS_METHODS to "mm",
        VceSubject.SPECIALIST_MATHS to "sm", VceSubject.PHYSICS to "phys",
        VceSubject.CHEMISTRY to "chem", VceSubject.BIOLOGY to "bio",
        VceSubject.PHYSICAL_EDUCATION to "pe", VceSubject.PSYCHOLOGY to "psych",
        VceSubject.ENGLISH to "eng", VceSubject.LITERATURE to "lit",
        VceSubject.ECONOMICS to "econ"
    )

    fun suggest(note: Notebook, subjects: List<FocalSubject> = builtIn): String? {
        folioIds[note.exam.subject]?.let { return it }
        val source = note.exam.subjectText.ifBlank { note.title }.trim()
        if (source.isBlank()) return null
        val normalized = source.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val exact = subjects.firstOrNull { it.name.equals(source, true) }
        if (exact != null) return exact.id
        // Longest names win: English Language before English, Specialist before Mathematics.
        return subjects.sortedByDescending { it.name.length }.firstOrNull { subject ->
            val name = subject.name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            name.length >= 5 && " $normalized ".contains(" $name ")
        }?.id ?: folioIds[VceSubject.match(source)]
    }
}

data class FocalStudyEntry(
    val id: String = UUID.randomUUID().toString(),
    val changeId: String = UUID.randomUUID().toString(),
    val notebookId: String?,
    val title: String,
    val subjectId: String?,
    val kind: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeMillis: Long,
    val notes: String = "",
    val confidence: Int? = null,
    val userId: String? = null,
    val synced: Boolean = false,
    val revision: Long = 0L,
    val completed: Boolean = true,
    val deleted: Boolean = false,
    val paused: Boolean = false,
    val examPhase: String? = null,
    val examPhaseBeforePause: String? = null,
    val intervals: List<FocalStudyInterval> = emptyList(),
    val remotePayload: String? = null,
    val notebookTitle: String? = null
) {
    val minutes: Int get() = (activeMillis / 60_000L).toInt().coerceAtLeast(if (completed) 1 else 0)
}

data class FocalStudyInterval(val startAt: Long, val endAt: Long?)

/** A stable, readable title for a regular study session. */
internal fun focalSessionTitle(subjectId: String?, subjects: List<FocalSubject>): String =
    (subjects.firstOrNull { it.id == subjectId }?.name ?: "Study") + " Focus"

/**
 * Count only the part of a session that overlaps the requested day. This matters for a session
 * that begins before midnight: the old UI added its whole duration whenever it ended today.
 * Completed exam rows already contain writing intervals only; regular study rows use their active
 * intervals as well.
 */
private const val MAX_REPORTED_SESSION_MILLIS = 24 * 60 * 60 * 1_000L

internal fun focalStudyMillisBetween(entries: Iterable<FocalStudyEntry>, from: Long, until: Long): Long =
    entries.asSequence()
        .filter { it.kind == "study" && it.completed && !focalIsCalendarPlaceholder(it) &&
            it.endedAt >= from && it.startedAt < until }
        .sumOf { focalActiveMillisBetween(it, from, until) }

/** Imported all-day calendar rows can masquerade as completed Focal study sessions. */
internal fun focalIsCalendarPlaceholder(entry: FocalStudyEntry): Boolean {
    val raw = entry.remotePayload ?: return false
    return runCatching {
        val payload = JSONObject(raw)
        if (payload.optJSONObject("integrations")?.optJSONObject("notion")?.optString("kind") == "event")
            return@runCatching true
        val intervals = payload.optJSONObject("execution")?.optJSONArray("intervals") ?: return@runCatching false
        if (intervals.length() != 1 || entry.intervals.size != 1) return@runCatching false
        val source = intervals.getJSONObject(0).optString("source")
        val imported = source == "imported" || (source.isBlank() && payload.optString("createdVia") == "notion")
        val interval = entry.intervals.single()
        imported && interval.endAt != null && interval.endAt - interval.startAt == MAX_REPORTED_SESSION_MILLIS
    }.getOrDefault(false)
}

internal fun focalActiveMillisBetween(entry: FocalStudyEntry, from: Long, until: Long): Long {
    if (entry.kind == "exam" && entry.examPhase == "reading") return 0L
    if (until <= from) return 0L
    val sessionEnd = entry.endedAt.coerceAtMost(until)
    if (sessionEnd <= from) return 0L
    val wallDuration = (entry.endedAt - entry.startedAt).coerceAtLeast(0L)
    // A corrupt remote row must not turn one day's dashboard into years of study time.
    val activeLimit = entry.activeMillis.coerceIn(0L, minOf(wallDuration, MAX_REPORTED_SESSION_MILLIS))
    val intervals = entry.intervals.ifEmpty {
        if (activeLimit <= 0L) emptyList() else listOf(
            FocalStudyInterval((entry.endedAt - activeLimit).coerceAtLeast(entry.startedAt), entry.endedAt)
        )
    }
    return intervals.sumOf { interval ->
        val start = interval.startAt.coerceIn(from, sessionEnd)
        val end = (interval.endAt ?: entry.endedAt).coerceIn(start, sessionEnd)
        (end - start).coerceAtLeast(0L)
    }.coerceAtMost(activeLimit)
}

/** Preserve one Focal row through reading, writing, pauses and completion. */
internal fun examStudyEntry(note: Notebook, timer: ExamTimerState, now: Long,
                            existing: FocalStudyEntry?, completed: Boolean = false,
                            subjects: List<FocalSubject> = FocalSubjects.builtIn): FocalStudyEntry {
    val started = requireNotNull(timer.startedAt)
    val subject = existing?.subjectId ?: FocalSubjects.suggest(note, subjects)
    val writing = timer.elapsedWriting(now).coerceAtLeast(0) * 1_000L
    val previousWriting = existing?.activeMillis ?: 0L
    val newWriting = (writing - previousWriting).coerceAtLeast(0L)
    val writingEnd = timer.pausedAt ?: now
    val intervals = (existing?.intervals ?: emptyList()).toMutableList()
    if (intervals.isEmpty() && writing > 0L) {
        val writingStart = (writingEnd - writing).coerceAtLeast(started)
        intervals.add(FocalStudyInterval(writingStart, if (timer.paused || completed) writingEnd else null))
    } else if (intervals.isNotEmpty() && (timer.paused || completed) && intervals.last().endAt == null) {
        intervals[intervals.lastIndex] = intervals.last().copy(endAt = writingEnd)
    } else if (!timer.paused && timer.phase == ExamTimerPhase.WRITING && newWriting > 0L && intervals.lastOrNull()?.endAt != null) {
        intervals.add(FocalStudyInterval((writingEnd - newWriting).coerceAtLeast(started), null))
    }
    return (existing ?: FocalStudyEntry(notebookId = note.id, title = focalSessionTitle(subject, subjects),
        subjectId = subject, kind = "exam", startedAt = started,
        endedAt = now, activeMillis = writing, notebookTitle = note.title)).copy(
        changeId = UUID.randomUUID().toString(),
        title = focalSessionTitle(subject, subjects), subjectId = subject,
        endedAt = now.coerceAtLeast(started + 1_000L), activeMillis = writing, notebookTitle = note.title,
        completed = completed, deleted = completed && writing == 0L,
        paused = timer.paused, examPhase = timer.phase.name.lowercase(), intervals = intervals,
        synced = false
    )
}

data class FocalFocus(
    val notebookId: String,
    val title: String,
    val subjectId: String?,
    val startedAt: Long,
    val resumedAt: Long?,
    val accumulatedMillis: Long = 0L,
    val sessionId: String = UUID.randomUUID().toString(),
    val intervals: List<FocalStudyInterval> = emptyList(),
    val notebookTitle: String? = null
) {
    fun elapsed(now: Long) = accumulatedMillis + (resumedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L)
    fun pause(now: Long) = if (resumedAt == null) this else copy(resumedAt = null,
        accumulatedMillis = elapsed(now), intervals = intervals.mapIndexed { i, interval ->
            if (i == intervals.lastIndex && interval.endAt == null) interval.copy(endAt = now) else interval
        })
    fun resume(now: Long) = if (resumedAt != null) this else copy(resumedAt = now,
        intervals = intervals + FocalStudyInterval(now, null))
}

data class FocalStudyState(
    val entries: List<FocalStudyEntry> = emptyList(),
    val focus: FocalFocus? = null,
    val remoteRevision: Long = 0L,
    val remoteRevisionUser: String? = null,
    val subjects: List<FocalSubject> = FocalSubjects.builtIn,
    val userId: String? = null,
    val email: String? = null,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val authMessage: String? = null,
    val configured: Boolean = !BuildConfig.FOCAL_SUPABASE_URL.contains("example.supabase.co") &&
        !BuildConfig.FOCAL_SUPABASE_PUBLISHABLE_KEY.contains("example_placeholder")
) {
    val visibleEntries get() = entries.filter { !it.deleted && (it.userId == null || it.userId == userId) }
    val pendingCount get() = entries.count { !it.synced && (it.userId == null || it.userId == userId) }
}

/** Durable local sessions and an idempotent Focal sync_changes outbox. */
class FocalStudyManager(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = AtomicFile(File(context.filesDir, "focal-study.json"))
    private val gate = Mutex()
    private val sessions = EncryptedExamTrackSession(context, "focal-session", "folio-focal")
    private val client = createSupabaseClient(BuildConfig.FOCAL_SUPABASE_URL, BuildConfig.FOCAL_SUPABASE_PUBLISHABLE_KEY) {
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            codeVerifierCache = MemoryCodeVerifierCache()
            sessionManager = sessions
            autoLoadFromStorage = false
            alwaysAutoRefresh = true
            enableLifecycleCallbacks = false
        }
        install(Postgrest)
    }
    private val lifecycle = ExamTrackSessionLifecycle(client, sessions)
    private val _state = MutableStateFlow(load())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            lifecycle.awaitRestoration()
            lifecycle.restoredUser?.let { user -> _state.update { it.copy(userId = user.id, email = user.email) } }
            client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        status.session.user?.let { user ->
                            _state.update { it.copy(userId = user.id, email = user.email, error = null) }
                            sync()
                        }
                    }
                    is SessionStatus.NotAuthenticated -> _state.update { it.copy(userId = null, email = null) }
                    else -> Unit
                }
            }
        }
        scope.launch {
            while (true) {
                delay(5_000)
                if (_state.value.focus?.resumedAt != null) persist(parkRunningAt = System.currentTimeMillis())
                if (_state.value.userId != null) sync()
            }
        }
    }

    private fun load(): FocalStudyState = runCatching {
        if (!file.baseFile.exists()) return@runCatching FocalStudyState()
        val data = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val rows = data.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until rows.length()).mapNotNull { index -> runCatching {
            val row = rows.getJSONObject(index)
            FocalStudyEntry(
                id = row.getString("id"), changeId = row.getString("changeId"),
                notebookId = row.optString("notebookId").ifBlank { null }, title = row.getString("title"),
                subjectId = row.optString("subjectId").ifBlank { null }, kind = row.getString("kind"),
                startedAt = row.getLong("startedAt"), endedAt = row.getLong("endedAt"),
                activeMillis = row.getLong("activeMillis"), notes = row.optString("notes"),
                confidence = row.optInt("confidence").takeIf { it in 1..5 },
                userId = row.optString("userId").ifBlank { null }, synced = row.optBoolean("synced"),
                revision = row.optLong("revision"),
                completed = row.optBoolean("completed", true), deleted = row.optBoolean("deleted"),
                paused = row.optBoolean("paused"), examPhase = row.optString("examPhase").lowercase().ifBlank { null },
                examPhaseBeforePause = row.optString("examPhaseBeforePause").lowercase().ifBlank { null },
                remotePayload = row.optString("remotePayload").ifBlank { null },
                notebookTitle = row.optString("notebookTitle").ifBlank { null },
                intervals = (row.optJSONArray("intervals") ?: JSONArray()).let { saved ->
                    (0 until saved.length()).mapNotNull { position -> runCatching {
                        val interval = saved.getJSONObject(position)
                        FocalStudyInterval(interval.getLong("startAt"), interval.optLong("endAt").takeIf { it > 0 })
                    }.getOrNull() }
                }
            )
        }.getOrNull() }
        val focus = data.optJSONObject("focus")?.let { row -> runCatching {
            FocalFocus(sessionId = row.optString("sessionId").ifBlank { UUID.randomUUID().toString() },
                notebookId = row.getString("notebookId"), title = row.getString("title"),
                subjectId = row.optString("subjectId").ifBlank { null }, startedAt = row.getLong("startedAt"),
                notebookTitle = row.optString("notebookTitle").ifBlank { null },
                resumedAt = row.optLong("resumedAt").takeIf { it > 0 }, accumulatedMillis = row.optLong("accumulatedMillis"),
                intervals = (row.optJSONArray("intervals") ?: JSONArray()).let { saved ->
                    (0 until saved.length()).mapNotNull { position -> runCatching {
                        val interval = saved.getJSONObject(position)
                        FocalStudyInterval(interval.getLong("startAt"), interval.optLong("endAt").takeIf { it > 0 })
                    }.getOrNull() }
                })
        }.getOrNull() }
        // A process death must not turn an unseen gap into study time.
        FocalStudyState(entries = entries, focus = focus?.copy(resumedAt = null),
            remoteRevision = data.optLong("remoteRevision"), remoteRevisionUser = data.optString("remoteRevisionUser").ifBlank { null })
    }.getOrDefault(FocalStudyState())

    @Synchronized private fun persist(parkRunningAt: Long? = null): Boolean {
        val snapshot = _state.value
        val rows = JSONArray()
        snapshot.entries.forEach { entry -> rows.put(JSONObject()
            .put("id", entry.id).put("changeId", entry.changeId).put("notebookId", entry.notebookId)
            .put("title", entry.title).put("subjectId", entry.subjectId).put("kind", entry.kind)
            .put("startedAt", entry.startedAt).put("endedAt", entry.endedAt)
            .put("activeMillis", entry.activeMillis).put("notes", entry.notes)
            .put("confidence", entry.confidence).put("userId", entry.userId).put("synced", entry.synced)
            .put("revision", entry.revision)
            .put("completed", entry.completed).put("deleted", entry.deleted)
            .put("paused", entry.paused).put("examPhase", entry.examPhase)
            .put("examPhaseBeforePause", entry.examPhaseBeforePause)
            .put("remotePayload", entry.remotePayload).put("notebookTitle", entry.notebookTitle)
            .put("intervals", JSONArray().also { array -> entry.intervals.forEach { interval ->
                array.put(JSONObject().put("startAt", interval.startAt).put("endAt", interval.endAt))
            } })) }
        val focus = snapshot.focus?.let { if (parkRunningAt == null) it else it.pause(parkRunningAt) }?.let { JSONObject().put("sessionId", it.sessionId).put("notebookId", it.notebookId)
            .put("title", it.title).put("subjectId", it.subjectId).put("notebookTitle", it.notebookTitle)
            .put("startedAt", it.startedAt)
            .put("resumedAt", it.resumedAt).put("accumulatedMillis", it.accumulatedMillis)
            .put("intervals", JSONArray().also { array -> it.intervals.forEach { interval ->
                array.put(JSONObject().put("startAt", interval.startAt).put("endAt", interval.endAt))
            } }) }
        val bytes = JSONObject().put("entries", rows).put("focus", focus)
            .put("remoteRevision", snapshot.remoteRevision).put("remoteRevisionUser", snapshot.remoteRevisionUser)
            .toString().toByteArray()
        return try {
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
            true
        }
        catch (_: Exception) {
            _state.update { it.copy(error = "Could not save study sessions on this device. Free storage and try again.") }
            false
        }
    }

    fun startFocus(note: Notebook, subjectId: String?, now: Long = System.currentTimeMillis()) {
        if (_state.value.focus != null || _state.value.visibleEntries.any { !it.completed && !it.deleted }) return
        val focus = FocalFocus(notebookId = note.id,
            title = focalSessionTitle(subjectId, _state.value.subjects), subjectId = subjectId,
            startedAt = now, resumedAt = now, intervals = listOf(FocalStudyInterval(now, null)), notebookTitle = note.title)
        _state.update { it.copy(focus = focus, error = null) }
        saveFocusEntry(focus, now)
    }
    fun toggleFocus(now: Long = System.currentTimeMillis()) {
        val focus = _state.value.focus ?: return
        val next = if (focus.resumedAt == null) focus.resume(now) else focus.pause(now)
        _state.update { it.copy(focus = next, error = null) }
        saveFocusEntry(next, now)
    }
    fun discardFocus(now: Long = System.currentTimeMillis()) {
        val focus = _state.value.focus ?: return
        _state.update { it.copy(focus = null) }
        saveFocusEntry(focus.pause(now), now, deleted = true)
    }
    fun finishFocus(notes: String, confidence: Int?, now: Long = System.currentTimeMillis()) {
        val focus = _state.value.focus ?: return
        val active = focus.elapsed(now)
        if (active < 1_000L) { discardFocus(); return }
        _state.update { it.copy(focus = null, error = null) }
        saveFocusEntry(focus.pause(now), now, completed = true, notes = notes.trim(), confidence = confidence)
    }

    private fun saveFocusEntry(focus: FocalFocus, now: Long, completed: Boolean = false, deleted: Boolean = false,
                               notes: String = "", confidence: Int? = null) {
        val entry = FocalStudyEntry(id = focus.sessionId, notebookId = focus.notebookId, title = focus.title,
            subjectId = focus.subjectId, kind = "study", startedAt = focus.startedAt,
            endedAt = now.coerceAtLeast(focus.startedAt + 1_000L), activeMillis = focus.elapsed(now),
            notes = notes, confidence = confidence, userId = _state.value.userId,
            completed = completed, deleted = deleted, paused = focus.resumedAt == null || completed || deleted,
            intervals = focus.intervals, changeId = UUID.randomUUID().toString(), notebookTitle = focus.notebookTitle)
        saveEntry(entry)
    }

    private fun saveEntry(entry: FocalStudyEntry) {
        _state.update { state -> state.copy(entries = listOf(entry) + state.entries.filterNot { it.id == entry.id }, error = null) }
        if (persist()) scope.launch { sync() }
    }

    fun controlEntry(id: String, action: String, now: Long = System.currentTimeMillis()) {
        val current = _state.value.entries.firstOrNull { it.id == id && !it.deleted } ?: return
        val focus = _state.value.focus
        if (focus?.sessionId == id) {
            when (action) {
                "pause", "resume" -> { toggleFocus(now); return }
                "finish" -> { finishFocus(current.notes, current.confidence, now); return }
                "discard" -> { discardFocus(now); return }
            }
        }
        val intervals = current.intervals.toMutableList()
        val last = intervals.lastOrNull()
        when (action) {
            "pause" -> if (last != null && last.endAt == null) intervals[intervals.lastIndex] = last.copy(endAt = now)
            "resume" -> if ((current.kind != "exam" || current.examPhaseBeforePause != "reading") &&
                (last == null || last.endAt != null)) intervals.add(FocalStudyInterval(now, null))
            "finish", "discard" -> if (last != null && last.endAt == null) intervals[intervals.lastIndex] = last.copy(endAt = now)
            else -> return
        }
        val total = intervals.sumOf { ((it.endAt ?: now) - it.startAt).coerceAtLeast(0L) }
        val source = current.remotePayload?.let { raw ->
            runCatching {
                val integrations = JSONObject(raw).optJSONObject("integrations")
                integrations?.optJSONObject("examtrack") ?: integrations?.optJSONObject("folio")
            }.getOrNull()
        }
        val oldPhase = source?.optString("phase")?.takeIf { it in setOf("reading", "writing") }
            ?: source?.optString("phaseBeforePause")?.takeIf { it in setOf("reading", "writing") }
            ?: current.examPhaseBeforePause?.takeIf { it in setOf("reading", "writing") }
            ?: current.examPhase?.takeIf { it in setOf("reading", "writing") }
            ?: "writing"
        val resumedPhase = source?.optString("phaseBeforePause")?.takeIf { it in setOf("reading", "writing") }
            ?: current.examPhaseBeforePause?.takeIf { it in setOf("reading", "writing") } ?: oldPhase
        val remotePayload = current.remotePayload?.let { raw -> runCatching {
            JSONObject(raw).apply {
                val integrations = optJSONObject("integrations")
                val integration = integrations?.optJSONObject("examtrack") ?: integrations?.optJSONObject("folio")
                integration?.let {
                    when (action) {
                        "pause" -> it.put("phaseBeforePause", oldPhase).put("phase", "paused")
                        "resume" -> it.put("phase", resumedPhase).remove("phaseBeforePause")
                    }
                }
            }.toString()
        }.getOrNull() } ?: current.remotePayload
        saveEntry(current.copy(changeId = UUID.randomUUID().toString(), endedAt = now.coerceAtLeast(current.startedAt + 1_000L),
            activeMillis = total, intervals = intervals, paused = action != "resume", completed = action == "finish",
            deleted = action == "discard", examPhase = when (action) {
                "pause" -> "paused"
                "resume" -> resumedPhase
                else -> current.examPhase
            }, examPhaseBeforePause = when (action) {
                "pause" -> oldPhase
                "resume" -> null
                else -> current.examPhaseBeforePause
            }, remotePayload = remotePayload, synced = false))
    }
    fun recordExamProgress(note: Notebook, timer: ExamTimerState, now: Long = System.currentTimeMillis(), force: Boolean = false) {
        val started = timer.startedAt ?: return
        if (!timer.active) return
        val existing = _state.value.entries.firstOrNull { it.kind == "exam" && it.notebookId == note.id && it.startedAt == started }
        if (existing?.completed == true) return
        if (!force && existing != null && existing.examPhase == timer.phase.name.lowercase() && existing.paused == timer.paused &&
            now - existing.endedAt < EXAM_SYNC_INTERVAL_MS) return
        updateExam(examStudyEntry(note, timer, now, existing, subjects = _state.value.subjects))
    }

    fun finishExam(note: Notebook, timer: ExamTimerState, now: Long = System.currentTimeMillis()) {
        val started = timer.startedAt ?: return
        val existing = _state.value.entries.firstOrNull { it.kind == "exam" && it.notebookId == note.id && it.startedAt == started }
        if (existing?.completed == true || (existing == null && timer.elapsedWriting(now) <= 0)) return
        updateExam(examStudyEntry(note, timer, now, existing, completed = true, subjects = _state.value.subjects))
    }

    private fun updateExam(entry: FocalStudyEntry) {
        _state.update { state ->
            val present = state.entries.any { it.id == entry.id }
            state.copy(entries = if (present) state.entries.map { if (it.id == entry.id) entry else it }
                else listOf(entry) + state.entries, error = null)
        }
        if (persist()) scope.launch { sync() }
    }
    fun logManual(note: Notebook, subjectId: String?, minutes: Int, notes: String,
                  confidence: Int?, now: Long = System.currentTimeMillis()) {
        if (minutes !in 1..1_440) return
        add(FocalStudyEntry(notebookId = note.id, title = focalSessionTitle(subjectId, _state.value.subjects),
            subjectId = subjectId, kind = "study", startedAt = now - minutes * 60_000L,
            endedAt = now, activeMillis = minutes * 60_000L, notebookTitle = note.title,
            notes = notes.trim(), confidence = confidence?.takeIf { it in 1..5 }, userId = _state.value.userId))
    }
    private fun add(entry: FocalStudyEntry) {
        _state.update { it.copy(entries = listOf(entry) + it.entries, error = null) }
        if (persist()) scope.launch { sync() }
    }
    fun retry() { scope.launch { sync() } }

    companion object { private const val EXAM_SYNC_INTERVAL_MS = 30_000L }

    fun signIn(email: String, password: String) {
        if (_state.value.busy || !_state.value.configured) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        scope.launch {
            try {
                lifecycle.awaitRestoration()
                client.auth.signInWith(Email) { this.email = email.trim(); this.password = password }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not sign in. Check your email, password and connection.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun signUp(email: String, password: String) {
        if (_state.value.busy || !_state.value.configured) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        scope.launch {
            try {
                lifecycle.awaitRestoration()
                client.auth.signUpWith(Email) { this.email = email.trim(); this.password = password }
                _state.update { it.copy(authMessage = "Account created. If asked, confirm your email, then sign in.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not create the account. Check your details and connection.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun resetPassword(email: String) {
        if (_state.value.busy || !_state.value.configured) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        scope.launch {
            try {
                lifecycle.awaitRestoration()
                client.auth.resetPasswordForEmail(email.trim())
                _state.update { it.copy(authMessage = "If this email has an account, a password reset link is on its way.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not request a reset. Check your connection and try again.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun signOut() {
        scope.launch {
            gate.withLock {
                client.auth.clearSession()
                sessions.deleteSession()
                _state.update { it.copy(userId = null, email = null, subjects = FocalSubjects.builtIn) }
            }
        }
    }

    private suspend fun sync() = gate.withLock {
        val user = _state.value.userId ?: return@withLock
        if (!_state.value.configured || client.auth.currentUserOrNull()?.id != user) return@withLock
        // Keep the last error visible while retrying. Clearing it at the start makes the chip
        // flash "synced" between every failed attempt, which is misleading and distracting.
        _state.update { it.copy(syncing = true) }
        try {
            // Focal v2 stores immutable changes. A fixed change ID makes retries idempotent.
            loadRemoteSessions(user)
            val pending = _state.value.entries.filter { !it.synced && (it.userId == null || it.userId == user) }.asReversed()
            for (entry in pending) {
                if (client.auth.currentUserOrNull()?.id != user) break
                if (_state.value.entries.none { it.id == entry.id && it.changeId == entry.changeId }) continue
                val owned = entry.copy(userId = user)
                _state.update { state -> state.copy(entries = state.entries.map {
                    if (it.id == entry.id && it.changeId == entry.changeId) owned else it
                }) }
                if (!persist()) break
                val change = JSONObject().put("user_id", user).put("change_id", owned.changeId)
                    .put("device_id", "folio-android").put("entity", "study_sessions")
                    .put("row_id", owned.id).put("operation", if (owned.deleted) "delete" else "put")
                    .put("payload", if (owned.deleted) JSONObject.NULL else focalPayload(owned))
                client.from("sync_changes").insert(Json.parseToJsonElement(change.toString()).jsonObject)
                _state.update { state -> state.copy(entries = state.entries.map {
                    if (it.id == entry.id && it.changeId == entry.changeId) it.copy(synced = true) else it
                }) }
                persist()
            }
            loadRemoteSessions(user)
            loadCustomSubjects(user)
            _state.update { it.copy(error = null) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = "Sessions are saved here. Focal sync will retry when connected.") } }
        finally { _state.update { it.copy(syncing = false) } }
    }

    private suspend fun loadRemoteSessions(user: String) {
        val priorState = _state.value
        val startRevision = priorState.remoteRevision.takeIf { priorState.remoteRevisionUser == user } ?: 0L
        val rows = JSONArray()
        var offset = 0L
        do {
            val page = JSONArray(client.from("sync_changes").select {
                filter { eq("user_id", user); eq("entity", "study_sessions"); gt("revision", startRevision) }
                order("revision", Order.ASCENDING)
                range(offset, offset + 499)
            }.data)
            repeat(page.length()) { rows.put(page.getJSONObject(it)) }
            offset += page.length()
        } while (page.length() == 500)
        val latestRevision = (0 until rows.length()).maxOfOrNull { rows.getJSONObject(it).optLong("revision") } ?: startRevision
        val latest = mutableMapOf<String, JSONObject>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.optString("row_id")
            if (id.isNotBlank() && (latest[id]?.optLong("revision") ?: -1L) < row.optLong("revision")) latest[id] = row
        }
        val remote = latest.values.mapNotNull { row -> runCatching {
            val id = row.optString("row_id")
            if (id.isBlank()) return@runCatching null
            if (row.optString("operation") == "delete") {
                val existing = _state.value.entries.firstOrNull { it.id == id }
                return@runCatching (existing ?: FocalStudyEntry(id = id, notebookId = null, title = "",
                    subjectId = null, kind = "study", startedAt = 0, endedAt = 0, activeMillis = 0,
                    userId = user)).copy(changeId = row.optString("change_id"), revision = row.optLong("revision"),
                    userId = user, synced = true, deleted = true)
            }
            val payload = row.optJSONObject("payload") ?: return@runCatching null
            val execution = payload.optJSONObject("execution") ?: JSONObject()
            val blocks = payload.optJSONObject("schedule")?.optJSONArray("blocks") ?: JSONArray()
            val now = System.currentTimeMillis()
            fun epoch(value: String?): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            val started = blocks.optJSONObject(0)?.optString("start")?.let(::epoch) ?: now
            val intervals = (execution.optJSONArray("intervals") ?: JSONArray()).let { source ->
                (0 until source.length()).mapNotNull { position -> runCatching {
                    val item = source.getJSONObject(position)
                    val begin = epoch(item.optString("start")) ?: return@runCatching null
                    FocalStudyInterval(begin, item.optString("end").takeIf { it.isNotBlank() }?.let(::epoch))
                }.getOrNull() }
            }
            val activeMillis = intervals.sumOf { ((it.endAt ?: now) - it.startAt).coerceAtLeast(0L) }
            val integrations = payload.optJSONObject("integrations")
            val integration = integrations?.optJSONObject("examtrack") ?: integrations?.optJSONObject("folio")
            val subjectId = payload.optJSONArray("subjectIds")?.optString(0)?.takeIf { it.isNotBlank() }
            val remoteDescription = payload.optString("description")
            val notebookTitle = remoteDescription.substringAfter(" · ", "").trim()
                .takeIf { it.isNotEmpty() && it !in setOf("reading", "writing", "paused") }
            val phase = integration?.optString("phase")?.takeIf { it.isNotBlank() }
                ?: remoteDescription.substringAfterLast("·", "").trim()
                    .takeIf { it in setOf("reading", "writing", "paused") }
            val executionState = execution.optString("state")
            FocalStudyEntry(id = id, changeId = row.optString("change_id"), notebookId = null,
                title = payload.optString("title", "Study session"), subjectId = subjectId,
                kind = if (integration != null || payload.optString("createdVia") == "examtrack") "exam" else "study",
                startedAt = started, endedAt = epoch(payload.optString("updated_at")) ?: now,
                activeMillis = activeMillis, notes = payload.optJSONObject("reflection")?.optString("notes").orEmpty(),
                confidence = payload.optJSONObject("reflection")?.optInt("confidence")?.takeIf { it in 1..5 },
                userId = user, synced = true, revision = row.optLong("revision"), completed = executionState == "completed",
                deleted = payload.opt("deleted_at") is String,
                paused = executionState == "in-progress" && (phase == "paused" ||
                    (phase == null && intervals.isNotEmpty() && intervals.last().endAt != null)),
                examPhase = phase,
                examPhaseBeforePause = integration?.optString("phaseBeforePause")?.takeIf { it in setOf("reading", "writing") },
                intervals = intervals, remotePayload = payload.toString(), notebookTitle = notebookTitle)
        }.getOrNull() }
        if (_state.value.userId != user) return
        _state.update { state ->
            val merged = state.entries.filter { it.synced && it.userId == user }.associateBy { it.id }.toMutableMap()
            remote.forEach { incoming ->
                if ((merged[incoming.id]?.revision ?: -1L) <= incoming.revision) merged[incoming.id] = incoming
            }
            state.entries.filter { !it.synced && (it.userId == null || it.userId == user) }.forEach { local ->
                if ((merged[local.id]?.revision ?: -1L) <= local.revision) merged[local.id] = local
            }
            val otherAccounts = state.entries.filter { it.userId != null && it.userId != user }
            val focus = state.focus?.let { current ->
                val session = merged[current.sessionId] ?: return@let current
                if (session.completed || session.deleted) return@let null
                val accumulated = session.intervals.filter { it.endAt != null }
                    .sumOf { ((it.endAt ?: it.startAt) - it.startAt).coerceAtLeast(0L) }
                val runningStart = session.intervals.lastOrNull()?.takeIf { it.endAt == null }?.startAt
                current.copy(intervals = session.intervals, accumulatedMillis = accumulated,
                    resumedAt = if (!session.paused) runningStart ?: System.currentTimeMillis() else null)
            }
            state.copy(entries = merged.values.toList() + otherAccounts, focus = focus,
                remoteRevision = maxOf(startRevision, latestRevision), remoteRevisionUser = user)
        }
        persist()
    }

    private suspend fun loadCustomSubjects(user: String) {
        val raw = client.from("sync_changes").select {
            filter { eq("user_id", user); eq("entity", "custom_subjects") }
        }.data
        val rows = JSONArray(raw)
        val latest = mutableMapOf<String, JSONObject>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.optString("row_id")
            if (id.isNotBlank() && (latest[id]?.optLong("revision") ?: -1L) < row.optLong("revision")) latest[id] = row
        }
        val custom = latest.values.mapNotNull { row ->
            if (row.optString("operation") == "delete") return@mapNotNull null
            val payload = row.optJSONObject("payload") ?: return@mapNotNull null
            val id = payload.optString("id").ifBlank { row.optString("row_id") }
            val name = payload.optString("name")
            if (id.isBlank() || name.isBlank()) null else FocalSubject(id, name)
        }
        if (_state.value.userId == user) _state.update { it.copy(subjects = FocalSubjects.builtIn + custom) }
    }
}

internal fun focalPayload(entry: FocalStudyEntry): JSONObject {
    fun iso(time: Long) = Instant.ofEpochMilli(time).toString()
    val start = iso(entry.startedAt)
    val end = iso(entry.endedAt.coerceAtLeast(entry.startedAt + 1_000L))
    val intervals = JSONArray()
    val writingIntervals = entry.intervals.ifEmpty {
        if (entry.activeMillis > 0L) listOf(FocalStudyInterval(
            (entry.endedAt - entry.activeMillis).coerceAtLeast(entry.startedAt), entry.endedAt))
        else emptyList()
    }
    writingIntervals.forEachIndexed { index, interval ->
        val open = !entry.completed && !entry.paused && !entry.deleted && index == writingIntervals.lastIndex && interval.endAt == null
        val item = JSONObject().put("start", iso(interval.startAt)).put("source", "manual")
        if (!open) item.put("end", iso(interval.endAt ?: entry.endedAt))
        intervals.put(item)
    }
    val reflection = JSONObject()
    if (entry.notes.isNotBlank()) reflection.put("notes", entry.notes)
    entry.confidence?.let { reflection.put("confidence", it) }
    val notebookContext = entry.notebookTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    val description = when {
        entry.kind != "exam" -> "Study in Folio$notebookContext"
        entry.completed -> "Exam practice in Folio$notebookContext"
        entry.paused -> "Exam practice in Folio$notebookContext · paused"
        entry.examPhase == "reading" -> "Exam practice in Folio$notebookContext · reading"
        else -> "Exam practice in Folio$notebookContext · writing"
    }
    val execution = JSONObject().put("state", if (entry.completed) "completed" else "in-progress")
        .put("intervals", intervals)
    if (entry.completed) execution.put("completedAt", end).put("reportedMinutes", entry.minutes)
    entry.remotePayload?.let { raw ->
        val payload = JSONObject(raw).put("execution", execution).put("updated_at", end)
            .put("last_modified_device_id", "folio-android")
        if (entry.notebookTitle != null) payload.put("description", description)
        val integrations = payload.optJSONObject("integrations")
        val integration = integrations?.optJSONObject("examtrack") ?: integrations?.optJSONObject("folio")
        integration?.let {
            if (entry.examPhase == "paused") {
                if (it.optString("phase") != "paused") it.put("phaseBeforePause", entry.examPhaseBeforePause ?: it.optString("phase", "writing"))
                it.put("phase", "paused")
            } else if (!entry.completed) {
                it.put("phase", entry.examPhase ?: "writing").remove("phaseBeforePause")
            }
        }
        return payload
    }
    val integrations = JSONObject()
    if (entry.kind == "exam") integrations.put("folio", JSONObject().put("type", "folio").put("id", entry.id)
        .put("kind", "exam").put("subject", entry.subjectId ?: "")
        .put("phase", if (entry.paused) "paused" else entry.examPhase ?: "writing")
        .put("phaseBeforePause", if (entry.paused) entry.examPhaseBeforePause ?: entry.examPhase ?: "writing" else JSONObject.NULL))
    return JSONObject().put("schemaVersion", 2).put("id", entry.id)
        .put("subjectIds", JSONArray().also { if (entry.subjectId != null) it.put(entry.subjectId) })
        .put("title", entry.title).put("description", description)
        .put("schedule", JSONObject().put("blocks", JSONArray().put(JSONObject().put("start", start).put("end", end))))
        .put("execution", execution)
        .put("integrations", integrations)
        .put("reflection", reflection).put("createdVia", "manual")
        .put("created_at", if (entry.kind == "exam") start else end)
        .put("updated_at", end).put("deleted_at", JSONObject.NULL)
        .put("last_modified_device_id", "folio-android")
}
