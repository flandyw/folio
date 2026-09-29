package com.folio.notes

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.folio.notes.sync.rpcObject
import com.folio.notes.sync.SupabaseSyncRemote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

internal data class FocalServerClockAnchor(val serverMillis: Long, val elapsedMillis: Long)

internal fun focalEstimatedServerNow(anchor: FocalServerClockAnchor?, elapsedNow: Long, wallNow: Long): Long =
    anchor?.let { it.serverMillis + (elapsedNow - it.elapsedMillis).coerceAtLeast(0L) } ?: wallNow

internal fun focalApplyCustomSubject(
    current: MutableMap<String, FocalSubject>, rowId: String, operation: String, payload: JSONObject?
) {
    when (operation) {
        "delete" -> current.remove(rowId)
        "put" -> payload?.let { row ->
        val id = row.optString("id").ifBlank { rowId }
        val name = row.optString("name")
            if (id.isNotBlank() && name.isNotBlank()) current[id] = FocalSubject(id, name)
        }
    }
}

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
    val planned: Boolean = false,
    val deleted: Boolean = false,
    val paused: Boolean = false,
    val examPhase: String? = null,
    val examPhaseBeforePause: String? = null,
    val intervals: List<FocalStudyInterval> = emptyList(),
    val remotePayload: String? = null,
    val notebookTitle: String? = null,
    /** Only unacknowledged exam lifecycle boundaries. Focus uses its durable intervals. */
    val examBoundaries: List<FocalExamBoundary> = emptyList(),
    val examLastBoundaryAt: Long? = null
) {
    val active: Boolean get() = !completed && !planned && !deleted
    val minutes: Int get() = (activeMillis / 60_000L).toInt().coerceAtLeast(if (completed) 1 else 0)
}

data class FocalStudyInterval(val startAt: Long, val endAt: Long?)

/** A single exam boundary, retained only until its canonical mutation is acknowledged. */
data class FocalExamBoundary(
    val id: String = UUID.randomUUID().toString(),
    val action: String,
    val phase: String,
    val at: Long,
    val elapsedMs: Long,
    /** Monotonic instant of the boundary when it was recorded, for an unskewed offline start. */
    val monotonicAt: Long? = null,
    val bootCount: Int = -1,
    /** Frozen before the RPC, so a lost response can be retried with the same receipt. */
    val request: String? = null
)

internal fun focalAppendExamBoundaries(entry: FocalStudyEntry, events: List<Triple<String, String, Long>>,
                                       now: Long? = null, monotonicNow: Long? = null, bootCount: Int = -1): FocalStudyEntry {
    var lastAt = entry.examLastBoundaryAt ?: entry.remotePayload?.let { raw -> runCatching {
        val canonical = JSONObject(raw)
        val stamp = canonical.optString("timing_at").takeIf { it.isNotBlank() && it != "null" }
            ?: canonical.optString("segment_started_at").takeIf { it.isNotBlank() && it != "null" }
            ?: canonical.optString("paused_at").takeIf { it.isNotBlank() && it != "null" }
        stamp?.let { Instant.parse(it).toEpochMilli() }
    }.getOrNull() }
    val added = events.map { (action, phase, at) ->
        // A negative gap is not a zero-length command. Keep it invalid so the publisher
        // reports a timing conflict rather than inventing a boundary on the server.
        val elapsed = if (lastAt == null) 0L else at - lastAt!!
        lastAt = at
        val age = now?.minus(at)
        FocalExamBoundary(action = action, phase = phase, at = at, elapsedMs = elapsed,
            monotonicAt = if (monotonicNow != null && age != null && age in 0L..monotonicNow)
                monotonicNow - age else null, bootCount = bootCount)
    }
    return entry.copy(examBoundaries = entry.examBoundaries + added, examLastBoundaryAt = lastAt,
        synced = if (added.isEmpty()) entry.synced else false)
}

/**
 * A newly-created active row is uploaded so other Focal clients can show it immediately.
 * Timer checkpoints stay local, but pause/resume and terminal boundaries are published.
 */
internal fun focalShouldUpload(entry: FocalStudyEntry): Boolean = !entry.synced

/** Pending local boundaries beat an older echo; remote termination beats an active checkpoint. */
internal fun focalMergeSession(local: FocalStudyEntry?, remote: FocalStudyEntry): FocalStudyEntry {
    if (local == null) return remote
    if (remote.revision < local.revision) return local
    // A pending exam boundary is a durable intent. A feed echo (even a terminal one)
    // cannot acknowledge it: only retrying the same mutation receipt can do that.
    if (local.examBoundaries.isNotEmpty()) return local.copy(revision = remote.revision,
        remotePayload = remote.remotePayload)
    // The first mutation in an offline replay can have this entry's changeId even though
    // later intervals have not landed yet. Matching an echo is safe only when the entire
    // local timeline is already present; otherwise the remaining boundaries stay pending.
    if (!local.synced && local.changeId == remote.changeId && local.intervals == remote.intervals &&
        local.paused == remote.paused && local.completed == remote.completed && local.deleted == remote.deleted)
        return remote.copy(notebookId = local.notebookId ?: remote.notebookId,
            notebookTitle = local.notebookTitle ?: remote.notebookTitle)
    if (!local.synced &&
        !(remote.deleted || (remote.completed && !local.deleted && !local.completed))) {
        return if (remote.revision > local.revision) local.copy(revision = remote.revision, remotePayload = remote.remotePayload) else local
    }
    return remote.copy(kind = if (local.notebookId != null) local.kind else remote.kind,
        startedAt = if (local.notebookId != null) local.startedAt else remote.startedAt,
        notebookId = local.notebookId ?: remote.notebookId,
        notebookTitle = local.notebookTitle ?: remote.notebookTitle)
}

internal fun focalRecoverFocus(entry: FocalStudyEntry, focus: FocalFocus): FocalStudyEntry = entry.copy(
    changeId = UUID.randomUUID().toString(), synced = false, paused = true,
    // A persisted open interval proves only its start, not how long it ran before process death.
    intervals = focus.intervals.mapIndexed { index, interval ->
        if (index == focus.intervals.lastIndex && interval.endAt == null) interval.copy(endAt = interval.startAt)
        else interval
    },
    activeMillis = focus.accumulatedMillis
)

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

/** Imported calendar blocks are scheduled time, not measured study, regardless of duration. */
internal fun focalIsCalendarPlaceholder(entry: FocalStudyEntry): Boolean {
    val raw = entry.remotePayload ?: return false
    return runCatching {
        val payload = JSONObject(raw)
        val metadata = payload.optJSONObject("metadata")
        val legacy = metadata?.optJSONObject("legacy_metadata") ?: payload
        val integrations = legacy.optJSONObject("integrations") ?: metadata?.optJSONObject("integrations")
        if (integrations?.optJSONObject("notion")?.optString("kind") == "event") return@runCatching true
        val execution = legacy.optJSONObject("execution") ?: payload.optJSONObject("execution")
        val intervals = execution?.optJSONArray("intervals") ?: return@runCatching false
        // ponytail: imported calendar intervals are scheduled time, including after canonical metadata wrapping.
        intervals.length() > 0 && (0 until intervals.length()).all { index ->
            val source = intervals.getJSONObject(index).optString("source")
            source == "imported" || (source.isBlank() && legacy.optString("createdVia") == "notion")
        }
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

/** Keep exam lifecycle boundaries apart from writing-only analytics intervals. Every boundary is
 *  written with the entry before any RPC, including automatic phase changes and auto-parks. */
internal fun focalExamBoundariesFor(
    previous: FocalStudyEntry?, timer: ExamTimerState, now: Long, terminal: String? = null,
    phaseAt: Long? = null
): List<Triple<String, String, Long>> {
    val started = timer.startedAt ?: return emptyList()
    // Pre-journal entries keep their legacy sync path. Guessing boundaries they did not
    // record would manufacture offline history rather than recover it.
    if (previous != null && previous.examLastBoundaryAt == null && previous.examBoundaries.isEmpty() &&
        previous.remotePayload == null) return emptyList()
    val initialPhase = if (timer.preset.readingSeconds > 0) "reading" else "writing"
    val currentPhase = when (timer.phase) {
        ExamTimerPhase.WRITING -> "writing"
        ExamTimerPhase.READING -> "reading"
        else -> previous?.examPhaseBeforePause?.takeIf { it in setOf("reading", "writing") }
            ?: previous?.examPhase?.takeIf { it in setOf("reading", "writing") } ?: initialPhase
    }
    val oldPhase = previous?.examPhaseBeforePause?.takeIf { it in setOf("reading", "writing") }
        ?: previous?.examPhase?.takeIf { it in setOf("reading", "writing") } ?: initialPhase
    val events = mutableListOf<Triple<String, String, Long>>()
    if (previous == null) events += Triple("start", initialPhase, started)
    val phaseChanged = oldPhase == "reading" && (currentPhase == "writing" ||
        (timer.phase == ExamTimerPhase.DONE && timer.elapsedWriting(now) > 0))
    if (phaseChanged) {
        // The countdown cannot cross phases while paused. A skip in a parked exam (or a
        // resume right on the threshold) changes phase at the button press, before resume.
        val calculated = if (previous?.paused == true) now else
            phaseAt ?: started + timer.pausedMillis + timer.preset.readingSeconds * 1_000L
        events += Triple("phase_change", "writing", calculated.coerceIn(started, now))
    }
    if (previous?.paused == true && !timer.paused && terminal == null)
        events += Triple("resume", if (phaseChanged) "writing" else oldPhase, now)
    if (timer.paused && previous?.paused != true && terminal == null)
        events += Triple("pause", if (events.any { it.first == "phase_change" }) "writing" else currentPhase,
            timer.pausedAt ?: now)
    if (terminal != null) events += Triple(terminal,
        if (events.any { it.first == "phase_change" }) "writing" else currentPhase, now)
    return events
}

/** Preserve one Focal row through reading, writing, pauses and completion. */
internal fun examStudyEntry(note: Notebook, timer: ExamTimerState, now: Long,
                            existing: FocalStudyEntry?, completed: Boolean = false,
                            forceUpload: Boolean = false,
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
        title = focalSessionTitle(subject, subjects), subjectId = subject, kind = "exam",
        endedAt = now.coerceAtLeast(started + 1_000L), activeMillis = writing, notebookTitle = note.title,
        completed = completed, deleted = completed && writing == 0L,
        paused = timer.paused, examPhase = timer.phase.name.lowercase(),
        examPhaseBeforePause = if (timer.paused) timer.phase.name.lowercase() else null,
        intervals = intervals,
        // Publish the first checkpoint so another Focal client can see the active sitting, but
        // keep later timer checkpoints local. The stable row_id lets the terminal update replace
        // that in-progress record in clients that understand the change log.
        synced = if (!completed && !forceUpload) existing?.synced ?: false else false
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

internal fun focalControlledEntry(current: FocalStudyEntry, action: String, now: Long): FocalStudyEntry? {
    val intervals = current.intervals.toMutableList()
    val last = intervals.lastOrNull()
    when (action) {
        "pause" -> if (last != null && last.endAt == null) intervals[intervals.lastIndex] = last.copy(endAt = now)
        "resume" -> if ((current.kind != "exam" || current.examPhaseBeforePause != "reading") &&
            (last == null || last.endAt != null)) intervals.add(FocalStudyInterval(now, null))
        "finish", "discard" -> if (last != null && last.endAt == null) intervals[intervals.lastIndex] = last.copy(endAt = now)
        else -> return null
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
    return current.copy(changeId = UUID.randomUUID().toString(), endedAt = now.coerceAtLeast(current.startedAt + 1_000L),
        activeMillis = total, intervals = intervals, paused = action != "resume", completed = action == "finish",
        deleted = action == "discard", examPhase = when (action) {
            "pause" -> "paused"
            "resume" -> resumedPhase
            else -> current.examPhase
        }, examPhaseBeforePause = when (action) {
            "pause" -> oldPhase
            "resume" -> null
            else -> current.examPhaseBeforePause
        }, remotePayload = remotePayload, synced = false)
}

data class FocalStudyState(
    val entries: List<FocalStudyEntry> = emptyList(),
    val focus: FocalFocus? = null,
    val remoteRevision: Long = 0L,
    val remoteRevisionUser: String? = null,
    val remoteLamport: Long = 0L,
    val remoteSubjectsRevision: Long = 0L,
    val remoteSubjectsUser: String? = null,
    val subjects: List<FocalSubject> = FocalSubjects.builtIn,
    val userId: String? = null,
    val email: String? = null,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val syncDetail: String? = null,
    val localSaveFailed: Boolean = false,
    val error: String? = null,
    val authMessage: String? = null,
    /** Transient in-app notice for a change received after the first remote load. */
    val remoteNotice: String? = null,
    val remoteNoticeId: Long = 0L,
    val configured: Boolean = !BuildConfig.FOCAL_SUPABASE_URL.contains("example.supabase.co") &&
        !BuildConfig.FOCAL_SUPABASE_PUBLISHABLE_KEY.contains("example_placeholder")
) {
    val visibleEntries get() = entries.filter { !it.deleted && (it.userId == null || it.userId == userId) }
    val pendingCount get() = entries.count { !it.synced && (it.userId == null || it.userId == userId) }
    val hasActiveSession get() = focus != null || visibleEntries.any { it.active }
    val canStartFocus get() = focus == null
    val syncStatus get() = when {
        syncing -> syncDetail ?: "Connecting to Focal…"
        error != null -> "Focal sync needs attention"
        userId == null || !configured -> "Saved on this device"
        pendingCount > 0 -> "$pendingCount waiting to sync"
        else -> "Synced with Focal"
    }
}

internal data class FocalSessionCommandTiming(val occurredAt: String?, val elapsedMs: Long)

internal fun focalElapsedSince(
    previousElapsed: Long?, previousBootCount: Int, currentElapsed: Long, currentBootCount: Int,
    fallbackElapsed: Long = 0L
): Long = if (previousElapsed != null && previousBootCount >= 0 && previousBootCount == currentBootCount)
    (currentElapsed - previousElapsed).coerceIn(0L, 604_800_000L)
else fallbackElapsed.coerceIn(0L, 604_800_000L)

internal fun focalRecoveryElapsed(activeMillis: Long, canonicalAccumulatedMillis: Long): Long =
    (activeMillis - canonicalAccumulatedMillis).coerceIn(0L, 604_800_000L)

internal fun focalElapsedFromServerBoundary(boundary: String?, serverNow: Long?): Long? {
    if (boundary == null || serverNow == null) return null
    val boundaryMillis = runCatching { Instant.parse(boundary).toEpochMilli() }.getOrNull() ?: return null
    val elapsed = serverNow - boundaryMillis
    return elapsed.takeIf { it in 0L..604_800_000L }
}

private fun focalJsonCanonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
        "${JSONObject.quote(key)}:${focalJsonCanonical(value.opt(key))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index -> focalJsonCanonical(value.opt(index)) }
    JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

private fun focalProgressChanged(
    entry: FocalStudyEntry,
    pending: JSONObject?,
    canonical: JSONObject?,
    expectedRevision: Long,
    deviceId: String
): Boolean {
    val candidate = focalStudyCommand(entry, "save_progress", expectedRevision, deviceId, entry.changeId)
    val previousMetadata = pending?.optJSONObject("metadata") ?: canonical?.optJSONObject("metadata")
        ?: canonical?.let { JSONObject().put("legacy_metadata", focalLegacyMetadata(it)) }
        ?: return true
    val candidateMetadata = candidate.optJSONObject("metadata") ?: JSONObject()
    if (focalJsonCanonical(candidateMetadata) != focalJsonCanonical(previousMetadata)) return true
    for (field in listOf("kind", "phase", "title")) {
        if (candidate.optString(field) != (pending ?: canonical)?.optString(field)) return true
    }
    val candidateSubject = candidate.opt("subject_id")
    val previousSubject = (pending ?: canonical)?.opt("subject_id")
    return candidateSubject != previousSubject
}

/**
 * The commands implied by the difference between the server's view of this session and the
 * local entry. The durable active intervals carry offline timer boundaries; while online the
 * diff is just one lifecycle command per button press. No separate command queue is needed.
 */
internal fun focalCommandsFor(
    entry: FocalStudyEntry,
    deviceId: String,
    timingForAction: (String, Long?, Long?) -> FocalSessionCommandTiming? = { _, _, _ -> null }
): List<JSONObject> {
    if (entry.synced) return emptyList()
    val desired = focalDesiredState(entry)
    val canonical = entry.remotePayload?.let { runCatching { JSONObject(it) }.getOrNull() }
    var currentState = focalCanonicalState(entry.remotePayload)
    var currentPhase = canonical?.optString("phase")?.takeIf { it in setOf("focus", "reading", "writing") }
    val commands = mutableListOf<JSONObject>()
    var expected = entry.revision
    var first = true
    fun append(action: String, at: Long? = null, previousAt: Long? = null) {
        val id = if (first) entry.changeId else UUID.randomUUID().toString()
        first = false
        val command = focalStudyCommand(entry, action, expected++, deviceId, id, timingForAction(action, at, previousAt))
        commands += command
        when (action) {
            "start", "resume" -> currentState = "running"
            "pause" -> currentState = "paused"
            "complete" -> currentState = "completed"
            "cancel" -> currentState = "cancelled"
            "create" -> currentState = "planned"
            "phase_change" -> currentPhase = command.optString("phase")
        }
    }
    // Folio already persists each active interval. Reconstruct only the boundaries missing
    // from the canonical segments, even when the final state happens to equal the server's.
    // A server row without segments may be a legacy/imported row: use the normal diff there.
    val segments = canonical?.optJSONArray("segments")
    val intervals = entry.intervals
    if (entry.notebookId != null && entry.kind == "study" && intervals.isNotEmpty() &&
        (canonical == null || segments != null) && currentState !in setOf("completed", "cancelled")) {
        val count = segments?.length() ?: 0
        // Another client may have added segments that Folio has never observed locally.
        // A final-state diff cannot reconcile two incompatible timelines safely.
        if (count > intervals.size || (currentState in setOf("running", "paused") && count == 0))
            error("This session has server timer boundaries Folio cannot match to its local intervals")
        if (intervals.zipWithNext().all { (a, b) ->
                a.endAt != null && a.endAt >= a.startAt && b.startAt >= a.endAt
            } && intervals.all { it.endAt == null || it.endAt >= it.startAt }) {
            var index = if (currentState == null || currentState == "planned") 0 else count - 1
            var boundary = when (currentState) {
                null, "planned" -> null
                "running" -> intervals.getOrNull(index)?.startAt
                "paused" -> intervals.getOrNull(index)?.endAt
                else -> null
            }
            if (currentState in setOf("running", "paused") && boundary == null)
                error("This session's last server boundary cannot be matched to a local interval")
            if (currentState == null || currentState == "planned" || count > 0) {
                if (currentState == null || currentState == "planned") {
                    append("start", intervals[0].startAt, null)
                    boundary = intervals[0].startAt
                }
                while (index < intervals.size) {
                    val interval = intervals[index]
                    if (currentState == "running") {
                        val end = interval.endAt ?: break
                        val terminal = index == intervals.lastIndex && desired in setOf("completed", "cancelled") &&
                            entry.endedAt == end
                        append(if (terminal) if (desired == "cancelled") "cancel" else "complete" else "pause", end, boundary)
                        boundary = end
                        if (terminal) return commands
                    }
                    if (index == intervals.lastIndex) break
                    index++
                    val start = intervals[index].startAt
                    append("resume", start, boundary)
                    boundary = start
                }
                if (desired in setOf("completed", "cancelled") && currentState !in setOf("completed", "cancelled"))
                    append(if (desired == "cancelled") "cancel" else "complete", entry.endedAt, boundary)
                if (commands.isNotEmpty()) return commands
            }
        }
    }
    if (currentState == null) {
        when (desired) {
            "planned" -> append("create")
            "running" -> append("start")
            "paused" -> { append("start"); append("pause") }
            "completed" -> {
                if (entry.activeMillis > 0L) append("start") else append("create")
                append("complete")
            }
            "cancelled" -> {
                if (entry.activeMillis > 0L) append("start") else append("create")
                append("cancel")
            }
        }
    } else if (currentState !in setOf("completed", "cancelled") || desired == currentState) {
        when {
            desired == "cancelled" && currentState != "cancelled" -> append("cancel")
            desired == "completed" && currentState != "completed" -> append("complete")
            desired == "running" && currentState == "planned" -> append("start")
            desired == "running" && currentState == "paused" -> append("resume")
            desired == "paused" && currentState == "planned" -> { append("start"); append("pause") }
            desired == "paused" && currentState == "running" -> append("pause")
            currentState == desired && currentPhase != focalEntryPhase(entry) -> append("phase_change")
            currentState == desired && focalProgressChanged(entry, null, canonical, expected - 1, deviceId) -> append("save_progress")
            currentState == desired -> Unit
        }
    }
    return commands
}

private fun focalEntryPhase(entry: FocalStudyEntry): String =
    entry.examPhaseBeforePause?.takeIf { entry.paused && it in setOf("reading", "writing") }
        ?: entry.examPhase?.takeIf { it in setOf("reading", "writing") }
        ?: if (entry.kind == "exam") "writing" else "focus"

/** Durable local sessions. The server is reached directly; nothing waits in a local queue. */
class FocalStudyManager(context: Context) {
    @Volatile private var serverClockAnchor: FocalServerClockAnchor? = null
    private val localClockAnchor = FocalServerClockAnchor(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    private val timingPreferences = context.getSharedPreferences("focal-study-timing", Context.MODE_PRIVATE)
    private val timingProcessId = UUID.randomUUID().toString()
    private val timingBootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun now(): Long = focalEstimatedServerNow(
        serverClockAnchor ?: localClockAnchor, SystemClock.elapsedRealtime(), System.currentTimeMillis()
    )

    private fun observeServerNow(value: String?) {
        val serverMillis = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        serverClockAnchor = FocalServerClockAnchor(serverMillis, SystemClock.elapsedRealtime())
    }
    private val file = AtomicFile(File(context.filesDir, "focal-study.json"))
    private val gate = Mutex()
    // Study sessions and mistake review share one Supabase client and one account.
    private val connection = FocalSupabaseConnection.of(context)
    private val sessions = connection.sessions
    private val client = connection.client
    private val lifecycle = connection.lifecycle
    private val syncRemote = SupabaseSyncRemote(client)
    private val deviceId = focalSyncDeviceId(context)
    private val _state = MutableStateFlow(load())
    val state = _state.asStateFlow()
    private val foreground = MutableStateFlow(false)
    private var remoteNoticeReadyUser: String? = null
    // Change ids this device authored, so their echo in the feed is never read as another device.
    private val ownChangeIds = LinkedHashSet<String>()
    // Session id to the change id already reported, so a loop that re-reads the same row cannot
    // raise the same notice over and over.
    private val noticedExternalChanges = HashMap<String, String>()

    private fun rememberOwnChange(changeId: String) {
        ownChangeIds += changeId
        // Insertion order, so the oldest id is the one to drop once the window is full.
        while (ownChangeIds.size > MAX_OWN_CHANGE_IDS) {
            val oldest = ownChangeIds.firstOrNull() ?: return
            ownChangeIds.remove(oldest)
        }
    }

    private fun clearSyncMemory() {
        ownChangeIds.clear()
        noticedExternalChanges.clear()
    }

    fun setForeground(value: Boolean) { foreground.value = value }

    init {
        scope.launch {
            lifecycle.awaitRestoration()
            lifecycle.restoredUser?.let { user -> _state.update { it.copy(userId = user.id, email = user.email) } }
            combine(client.auth.sessionStatus, foreground) { status, visible -> status to visible }
                .collectLatest { (status, visible) ->
                    when (status) {
                        is SessionStatus.Authenticated -> {
                            status.session.user?.let { user ->
                                if (_state.value.userId != user.id) { remoteNoticeReadyUser = null; clearSyncMemory() }
                                _state.update { it.copy(userId = user.id, email = user.email, error = null,
                                    subjects = if (it.remoteSubjectsUser == user.id) it.subjects else FocalSubjects.builtIn) }
                                sync()
                                if (visible) watchRemoteSessions(user.id)
                            }
                        }
                        is SessionStatus.NotAuthenticated -> {
                            remoteNoticeReadyUser = null
                            clearSyncMemory()
                            _state.update { it.copy(userId = null, email = null) }
                        }
                        else -> Unit
                    }
                }
        }
        scope.launch {
            while (true) {
                // The first active checkpoint is published so another Focal client can show the
                // sitting immediately. Later checkpoints stay local; this avoids turning a long
                // exam into dozens of immutable changes. The terminal update keeps the same
                // row_id and replaces the in-progress record in a correct Focal consumer.
                delay(30_000)
                if (_state.value.focus?.resumedAt != null) persist(parkRunningAt = now())
                if (_state.value.userId != null) sync()
            }
        }
    }

    private suspend fun watchRemoteSessions(user: String) {
        while (foreground.value && _state.value.userId == user) {
            val channel = client.realtime.channel("folio-study-$user")
            try {
                coroutineScope {
                    for (entity in listOf("study_sessions", "custom_subjects")) {
                        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                            table = "sync_log"
                            filter("entity", FilterOperator.EQ, entity)
                        }.onEach { sync() }.launchIn(this)
                    }
                    // A reconnect can miss messages while the socket is down. Joining also
                    // closes the gap between the initial pull and this subscription.
                    channel.status.onEach { status ->
                        if (status == RealtimeChannel.Status.SUBSCRIBED) sync()
                    }.launchIn(this)
                    channel.subscribe(blockUntilSubscribed = true)
                    awaitCancellation()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { delay(7_000) }
            finally { withContext(NonCancellable) { runCatching { client.realtime.removeChannel(channel) } } }
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
                completed = row.optBoolean("completed", true), planned = row.optBoolean("planned") ||
                    row.optString("remotePayload").let { raw -> runCatching {
                        JSONObject(raw).optJSONObject("execution")?.optString("state") == "planned"
                    }.getOrDefault(false) }, deleted = row.optBoolean("deleted"),
                paused = row.optBoolean("paused"), examPhase = row.optString("examPhase").lowercase().ifBlank { null },
                examPhaseBeforePause = row.optString("examPhaseBeforePause").lowercase().ifBlank { null },
                remotePayload = row.optString("remotePayload").ifBlank { null },
                notebookTitle = row.optString("notebookTitle").ifBlank { null },
                examLastBoundaryAt = row.optLong("examLastBoundaryAt").takeIf { it > 0L },
                examBoundaries = (row.optJSONArray("examBoundaries") ?: JSONArray()).let { saved ->
                    (0 until saved.length()).mapNotNull { position -> runCatching {
                        val event = saved.getJSONObject(position)
                        FocalExamBoundary(id = event.getString("id"), action = event.getString("action"),
                            phase = event.getString("phase"), at = event.getLong("at"),
                            elapsedMs = event.getLong("elapsedMs"),
                            monotonicAt = event.optLong("monotonicAt").takeIf { it > 0L },
                            bootCount = event.optInt("bootCount", -1),
                            request = event.optString("request").takeIf { it.isNotBlank() && it != "null" })
                    }.getOrNull() }
                },
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
        // Recovery closes at the last durable checkpoint. A new process cannot reuse the
        // prior process's monotonic delta, and a reboot cannot reuse elapsedRealtime at all.
        val recoveredFocus = focus?.copy(resumedAt = null)?.takeUnless { current ->
            entries.any { it.id == current.sessionId && (it.deleted || it.completed) }
        }
        val builtInIds = FocalSubjects.builtIn.mapTo(mutableSetOf()) { it.id }
        val savedSubjects = (data.optJSONArray("subjects") ?: JSONArray()).let { array ->
            (0 until array.length()).mapNotNull { index -> runCatching {
                val subject = array.getJSONObject(index)
                FocalSubject(subject.getString("id"), subject.getString("name"))
            }.getOrNull() }.filterNot { it.id in builtInIds }
        }
        FocalStudyState(entries = entries.map { entry ->
            if (recoveredFocus?.sessionId == entry.id && !entry.paused)
                focalRecoverFocus(entry, recoveredFocus) else entry
        }, focus = recoveredFocus, subjects = FocalSubjects.builtIn + savedSubjects,
            remoteRevision = data.optLong("remoteRevision"), remoteRevisionUser = data.optString("remoteRevisionUser").ifBlank { null },
            remoteLamport = data.optLong("remoteLamport"),
            remoteSubjectsRevision = data.optLong("remoteSubjectsRevision").coerceAtLeast(0L),
            remoteSubjectsUser = data.optString("remoteSubjectsUser").ifBlank { null },
            )
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
            .put("completed", entry.completed).put("planned", entry.planned).put("deleted", entry.deleted)
            .put("paused", entry.paused).put("examPhase", entry.examPhase)
            .put("examPhaseBeforePause", entry.examPhaseBeforePause)
            .put("remotePayload", entry.remotePayload).put("notebookTitle", entry.notebookTitle)
            .put("examLastBoundaryAt", entry.examLastBoundaryAt)
            .put("examBoundaries", JSONArray().also { array -> entry.examBoundaries.forEach { event ->
                array.put(JSONObject().put("id", event.id).put("action", event.action)
                    .put("phase", event.phase).put("at", event.at).put("elapsedMs", event.elapsedMs)
                    .put("monotonicAt", event.monotonicAt).put("bootCount", event.bootCount)
                    .put("request", event.request))
            } })
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
            .put("remoteLamport", snapshot.remoteLamport)
            .put("subjects", JSONArray(snapshot.subjects.filterNot { subject ->
                FocalSubjects.builtIn.any { it.id == subject.id }
            }.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("remoteSubjectsRevision", snapshot.remoteSubjectsRevision).put("remoteSubjectsUser", snapshot.remoteSubjectsUser)
            .toString().toByteArray()
        return try {
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
            _state.update { it.copy(localSaveFailed = false,
                error = if (it.localSaveFailed) null else it.error) }
            true
        }
        catch (_: Exception) {
            _state.update { it.copy(localSaveFailed = true,
                error = "Could not save study sessions on this device. Check free storage, then retry while Folio is still open.") }
            false
        }
    }

    fun startFocus(note: Notebook, subjectId: String?, now: Long = this.now()) {
        if (!_state.value.canStartFocus) return
        val focus = FocalFocus(notebookId = note.id,
            title = focalSessionTitle(subjectId, _state.value.subjects), subjectId = subjectId,
            startedAt = now, resumedAt = now, intervals = listOf(FocalStudyInterval(now, null)), notebookTitle = note.title)
        _state.update { it.copy(focus = focus, error = null) }
        saveFocusEntry(focus, now)
    }
    fun toggleFocus(now: Long = this.now()) {
        val focus = _state.value.focus ?: return
        val next = if (focus.resumedAt == null) focus.resume(now) else focus.pause(now)
        _state.update { it.copy(focus = next, error = null) }
        // Publish pause/resume boundaries. While running, the macOS client can derive elapsed
        // time from the open interval's start and does not need a checkpoint every few seconds.
        saveFocusEntry(next, now, forceUpload = true)
    }
    fun discardFocus(now: Long = this.now()) {
        val focus = _state.value.focus ?: return
        _state.update { it.copy(focus = null) }
        saveFocusEntry(focus.pause(now), now, deleted = true)
    }
    fun finishFocus(notes: String, confidence: Int?, now: Long = this.now()) {
        val focus = _state.value.focus ?: return
        val active = focus.elapsed(now)
        if (active < 1_000L) { discardFocus(); return }
        _state.update { it.copy(focus = null, error = null) }
        saveFocusEntry(focus.pause(now), now, completed = true, notes = notes.trim(), confidence = confidence)
    }

    private fun saveFocusEntry(focus: FocalFocus, now: Long, completed: Boolean = false, deleted: Boolean = false,
                               notes: String = "", confidence: Int? = null, forceUpload: Boolean = false) {
        val previous = _state.value.entries.firstOrNull { it.id == focus.sessionId }
        if (previous?.deleted == true || previous?.completed == true) return
        val entry = FocalStudyEntry(id = focus.sessionId, notebookId = focus.notebookId, title = focus.title,
            revision = previous?.revision ?: 0L, remotePayload = previous?.remotePayload,
            subjectId = focus.subjectId, kind = "study", startedAt = focus.startedAt,
            endedAt = now.coerceAtLeast(focus.startedAt + 1_000L), activeMillis = focus.elapsed(now),
            notes = notes, confidence = confidence, userId = _state.value.userId,
            completed = completed, deleted = deleted, paused = focus.resumedAt == null || completed || deleted,
            intervals = focus.intervals, changeId = UUID.randomUUID().toString(), notebookTitle = focus.notebookTitle,
            synced = if (!completed && !deleted && !forceUpload) _state.value.entries.firstOrNull { it.id == focus.sessionId }?.synced ?: false else false)
        saveEntry(entry)
    }

    private fun saveEntry(entry: FocalStudyEntry) {
        _state.update { state ->
            state.copy(entries = listOf(entry) + state.entries.filterNot { it.id == entry.id }, error = null)
        }
        // Local first, then straight at the server. An offline write stays unsynced;
        // the durable intervals reconstruct every missing boundary on the next sync pass.
        if (persist()) scope.launch { publishEntry(entry) }
    }

    private fun commandsFor(entry: FocalStudyEntry): List<JSONObject> {
        val desired = focalDesiredState(entry)
        val monoNow = SystemClock.elapsedRealtime()
        var previousMono = timingPreferences.getLong("elapsed:${entry.id}", -1L).takeIf { it > 0L }
        var previousBoot = timingPreferences.getInt("boot:${entry.id}", -1)
        val previousProcess = timingPreferences.getString("process:${entry.id}", null)
        val serverNow = serverClockAnchor?.let { estimate ->
            estimate.serverMillis + (monoNow - estimate.elapsedMillis).coerceAtLeast(0L)
        }
        val canonical = entry.remotePayload?.let { runCatching { JSONObject(it) }.getOrNull() }
        val remoteBoundary = canonical?.optString("timing_at")?.takeIf { it.isNotBlank() }
            ?: when (canonical?.optString("state")) {
                "running" -> canonical.optString("segment_started_at").takeIf { it.isNotBlank() }
                "paused" -> canonical.optString("paused_at").takeIf { it.isNotBlank() }
                else -> null
            }
        val recoveredRunningFocus = _state.value.focus?.let { it.sessionId == entry.id && it.resumedAt == null } == true &&
            entry.paused && canonical?.optString("state") == "running"
        val serverElapsed = if (recoveredRunningFocus) null
            else focalElapsedFromServerBoundary(remoteBoundary, serverNow)
        val monotonicContinuous = previousMono != null && previousBoot >= 0 && previousBoot == timingBootCount &&
            previousProcess == timingProcessId
        var synthesizedEnd = false
        return focalCommandsFor(entry, deviceId) { action, boundaryAt, previousAt ->
            if (boundaryAt != null) {
                // An offline boundary is durable in the intervals, not inferred from the
                // final running/paused state. The server advances from its last boundary
                // by exactly this gap; a fresh start can be placed using the server clock.
                if (previousAt != null) {
                    val gap = boundaryAt - previousAt
                    if (gap !in 0L..604_800_000L)
                        error("This offline timer boundary is outside the server's seven-day timing window")
                    return@focalCommandsFor FocalSessionCommandTiming(null, gap)
                }
                val anchored = serverClockAnchor
                val age = serverNow?.minus(boundaryAt)
                if (anchored != null && age != null && age !in -5_000L..(30L * 24 * 60 * 60 * 1_000))
                    error("This offline timer is outside the server's 30-day timing window; it cannot be replayed accurately")
                return@focalCommandsFor FocalSessionCommandTiming(
                    if (anchored != null && age != null && age >= -5_000L) Instant.ofEpochMilli(boundaryAt).toString() else null, 0L)
            }
            if (action !in setOf("start", "pause", "resume", "phase_change", "complete", "cancel")) return@focalCommandsFor null
            val fallbackElapsed = when {
                recoveredRunningFocus -> focalRecoveryElapsed(entry.activeMillis, canonical?.optLong("accumulated_active_ms") ?: 0L)
                serverElapsed != null -> serverElapsed
                action in setOf("pause", "complete") && entry.remotePayload == null -> entry.activeMillis
                else -> 0L
            }
            val elapsed = if (synthesizedEnd && entry.activeMillis > 0L)
                entry.activeMillis.coerceAtMost(604_800_000L)
            else focalElapsedSince(
                previousMono.takeIf { monotonicContinuous },
                if (monotonicContinuous) previousBoot else -1,
                monoNow, timingBootCount, fallbackElapsed
            )
            val synthesized = action == "start" && desired != "running"
            if (synthesized) synthesizedEnd = true
            val occurrenceIsSafe = !synthesized && serverNow != null &&
                (monotonicContinuous || serverElapsed != null ||
                    (previousMono == null && entry.remotePayload == null))
            val occurredAt = if (occurrenceIsSafe) Instant.ofEpochMilli(serverNow!!).toString() else null
            // Only the in-process chain advances here; the durable anchor is committed by
            // commitTimingAnchor once the server has accepted the command. A command that never
            // left the device must not move it, or a retry would report the gap since the retry.
            if (previousMono != null) { previousMono = monoNow; previousBoot = timingBootCount }
            FocalSessionCommandTiming(occurredAt, elapsed)
        }
    }

    /**
     * Move the durable monotonic anchor for a session. Called only after study_session_mutate
     * accepted the command, so the next boundary measures from a boundary the server agreed to.
     */
    private fun commitTimingAnchor(entry: FocalStudyEntry, commands: List<JSONObject>) {
        if (commands.isEmpty()) return
        val terminal = commands.last().optString("action") in setOf("complete", "cancel")
        if (terminal) {
            timingPreferences.edit().remove("elapsed:${entry.id}").remove("boot:${entry.id}")
                .remove("process:${entry.id}").commit()
        } else {
            timingPreferences.edit().putLong("elapsed:${entry.id}", SystemClock.elapsedRealtime())
                .putInt("boot:${entry.id}", timingBootCount)
                .putString("process:${entry.id}", timingProcessId).commit()
        }
    }

    fun controlEntry(id: String, action: String, now: Long = this.now()) {
        val current = _state.value.visibleEntries.firstOrNull { it.id == id && it.active } ?: return
        val focus = _state.value.focus
        if (focus?.sessionId == id) {
            when (action) {
                "pause", "resume" -> { toggleFocus(now); return }
                "finish" -> { finishFocus(current.notes, current.confidence, now); return }
                "discard" -> { discardFocus(now); return }
            }
        }
        val updated = focalControlledEntry(current, action, now) ?: return
        saveEntry(updated)
    }

    /** Resolve multiple old paused rows in one durable write and one sync pass. */
    fun controlPausedEntries(ids: Collection<String>, action: String, now: Long = this.now()) {
        if (action != "finish" && action != "discard") return
        val focusId = _state.value.focus?.sessionId
        val targets = _state.value.visibleEntries.filter { it.id in ids && it.active && it.paused && it.id != focusId }
        if (targets.isEmpty()) return
        val updated = targets.mapNotNull { focalControlledEntry(it, action, now) }.associateBy { it.id }
        _state.update { state -> state.copy(entries = state.entries.map { updated[it.id] ?: it }, error = null) }
        if (persist()) scope.launch { updated.values.forEach { publishEntry(it) } }
    }

    fun recordExamProgress(note: Notebook, timer: ExamTimerState, now: Long = this.now(), force: Boolean = false,
                           phaseAt: Long? = null) {
        val started = timer.startedAt ?: return
        if (!timer.active) return
        val existing = _state.value.entries.firstOrNull { it.notebookId == note.id && it.startedAt == started }
        if (existing?.completed == true || existing?.deleted == true) return
        if (!force && existing != null && existing.examPhase == timer.phase.name.lowercase() && existing.paused == timer.paused &&
            now - existing.endedAt < EXAM_SYNC_INTERVAL_MS) return
        val phaseChanged = existing != null && (existing.examPhase != timer.phase.name.lowercase() || existing.paused != timer.paused)
        val entry = examStudyEntry(note, timer, now, existing, forceUpload = phaseChanged, subjects = _state.value.subjects)
        updateExam(focalAppendExamBoundaries(entry, focalExamBoundariesFor(existing, timer, now, phaseAt = phaseAt),
            now, SystemClock.elapsedRealtime(), timingBootCount))
    }

    fun finishExam(note: Notebook, timer: ExamTimerState, now: Long = this.now(), phaseAt: Long? = null) {
        val started = timer.startedAt ?: return
        val existing = _state.value.entries.firstOrNull { it.notebookId == note.id && it.startedAt == started }
        if (existing?.completed == true || existing?.deleted == true || (existing == null && timer.elapsedWriting(now) <= 0)) return
        val entry = examStudyEntry(note, timer, now, existing, completed = true, subjects = _state.value.subjects)
        updateExam(focalAppendExamBoundaries(entry, focalExamBoundariesFor(existing, timer, now,
            terminal = if (entry.deleted) "cancel" else "complete", phaseAt = phaseAt),
            now, SystemClock.elapsedRealtime(), timingBootCount))
    }

    private fun updateExam(entry: FocalStudyEntry) {
        _state.update { state ->
            val present = state.entries.any { it.id == entry.id }
            state.copy(entries = if (present) state.entries.map { if (it.id == entry.id) entry else it }
                else listOf(entry) + state.entries, error = null)
        }
        if (persist()) scope.launch { publishEntry(entry) }
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
        _state.update { state -> state.copy(entries = listOf(entry) + state.entries, error = null) }
        if (persist()) scope.launch { publishEntry(entry) }
    }
    fun retry() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true, syncDetail = if (it.localSaveFailed)
            "Saving sessions on this device…" else "Waiting to connect to Focal…") }
        scope.launch {
            if (_state.value.localSaveFailed) {
                if (!persist()) {
                    _state.update { it.copy(syncing = false, syncDetail = null) }
                    return@launch
                }
                _state.update { it.copy(error = null) }
            }
            sync()
        }
    }

    companion object {
        private const val EXAM_SYNC_INTERVAL_MS = 30_000L
        private const val MAX_OWN_CHANGE_IDS = 200
    }

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
                remoteNoticeReadyUser = null
                clearSyncMemory()
                _state.update { it.copy(userId = null, email = null, subjects = FocalSubjects.builtIn,
                    remoteSubjectsRevision = 0L, remoteSubjectsUser = null) }
                persist()
            }
        }
    }

    private suspend fun sync() = gate.withLock {
        val user = _state.value.userId ?: run {
            _state.update { it.copy(syncing = false, syncDetail = null) }
            return@withLock
        }
        if (!_state.value.configured) {
            _state.update { it.copy(syncing = false, syncDetail = null,
                error = "Focal sync is not configured in this build. Sessions remain on this device.") }
            return@withLock
        }
        if (client.auth.currentUserOrNull()?.id != user) {
            _state.update { it.copy(userId = null, email = null, syncing = false, syncDetail = null,
                error = "Your Focal session expired. Sign in again to sync your saved sessions.") }
            return@withLock
        }
        _state.update { state -> state.copy(entries = state.entries.map { if (it.userId == null) it.copy(userId = user) else it }) }
        // Keep the last error visible while retrying. Clearing it at the start makes the chip
        // flash "synced" between every failed attempt, which is misleading and distracting.
        _state.update { it.copy(syncing = true, syncDetail = "Checking Focal for session changes…") }
        try {
            if (!persist()) error("Could not save the local account ownership")
            loadRemoteSessions(user)
            publishUnsyncedEntries(user)
            _state.update { it.copy(syncDetail = "Checking that Focal received the changes…") }
            loadRemoteSessions(user)
            _state.update { it.copy(syncDetail = "Loading Focal subjects…") }
            loadCustomSubjects(user)
            _state.update { it.copy(error = if (it.localSaveFailed) it.error else null) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportPublishFailure(_state.value.syncDetail?.removeSuffix("…") ?: "Syncing with Focal", e) }
        finally { _state.update { it.copy(syncing = false, syncDetail = null) } }
    }

    /** Send only the outstanding exam boundaries. Freezing each request before its RPC means
     * a timeout after server acceptance is retried against the exact same mutation receipt.
     * The mutex belongs to the caller, so button presses cannot overtake each other. */
    private suspend fun publishExamBoundaries(user: String, sessionId: String) {
        while (_state.value.userId == user) {
            val entry = _state.value.entries.firstOrNull {
                it.id == sessionId && (it.userId == null || it.userId == user)
            } ?: return
            val boundary = entry.examBoundaries.firstOrNull() ?: return
            val command = boundary.request?.let(::JSONObject) ?: run {
                val serverNow = serverClockAnchor?.let { focalEstimatedServerNow(it,
                    SystemClock.elapsedRealtime(), System.currentTimeMillis()) }
                val created = focalStudyCommand(entry, boundary.action, entry.revision, deviceId,
                    boundary.id, FocalSessionCommandTiming(
                        if (boundary.action == "start" && serverNow != null) {
                            val monoNow = SystemClock.elapsedRealtime()
                            val eventAt = if (boundary.bootCount >= 0 && boundary.bootCount == timingBootCount &&
                                boundary.monotonicAt != null && boundary.monotonicAt <= monoNow)
                                serverNow - (monoNow - boundary.monotonicAt)
                            else boundary.at
                            val age = serverNow - eventAt
                            if (age !in -5_000L..(30L * 24 * 60 * 60 * 1_000))
                                error("This offline timer is outside the server's 30-day timing window; it cannot be replayed accurately")
                            Instant.ofEpochMilli(eventAt).toString()
                        } else null, boundary.elapsedMs.also { elapsed ->
                            if (elapsed !in 0L..604_800_000L)
                                error("This offline timer boundary is outside the server's seven-day timing window")
                        }))
                    .put("phase", boundary.phase)
                _state.update { state -> state.copy(entries = state.entries.map { row ->
                    if (row.id != sessionId || row.examBoundaries.firstOrNull()?.id != boundary.id) row
                    else row.copy(examBoundaries = listOf(boundary.copy(request = created.toString())) +
                        row.examBoundaries.drop(1))
                }) }
                if (!persist()) error("Could not save the exam mutation before sending it")
                created
            }
            val result = syncRemote.mutateStudySession(user, command)
            observeServerNow(result.optString("server_now"))
            val canonical = result.optJSONObject("session") ?: error("Focal sent no canonical exam session")
            if (!result.optBoolean("applied"))
                error("This exam session changed on another device (${result.optString("reason")}); its local boundaries were kept")
            rememberOwnChange(boundary.id)
            _state.update { state -> state.copy(entries = state.entries.map { row ->
                if (row.id != sessionId || row.examBoundaries.firstOrNull()?.id != boundary.id) row
                else {
                    val remaining = row.examBoundaries.drop(1)
                    row.copy(examBoundaries = remaining, revision = canonical.optLong("revision"),
                        remotePayload = canonical.toString(), synced = remaining.isEmpty(),
                        changeId = if (remaining.isEmpty()) boundary.id else row.changeId)
                }
            }) }
            if (!persist()) error("Could not save the exam mutation receipt")
        }
    }

    /**
     * Publish the latest entry: derive the missing boundaries from its durable intervals, send
     * them, and retain the canonical answer. A failed request leaves the entry unsynced.
     */
    private suspend fun publishEntry(entry: FocalStudyEntry) {
        val user = _state.value.userId ?: return
        if (!_state.value.configured) return
        gate.withLock {
            if (_state.value.userId != user) return@withLock
            try {
                if (_state.value.entries.any { it.id == entry.id && it.examBoundaries.isNotEmpty() }) {
                    publishExamBoundaries(user, entry.id)
                    return@withLock
                }
                // Derive under the gate from the latest local entry. A start, pause and resume
                // can each schedule a publish before the first RPC returns; an earlier snapshot
                // must not overtake the later one or reuse its stale revision.
                val latest = _state.value.entries.firstOrNull { it.id == entry.id && !it.synced &&
                    (it.userId == null || it.userId == user) } ?: return@withLock
                val commands = commandsFor(latest)
                if (commands.isEmpty()) return@withLock
                val ack = sendCommands(user, commands)
                if (ack.sent) { commitTimingAnchor(latest, commands); acknowledge(latest.id, ack) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportPublishFailure("Sending this session to Focal", e) }
        }
    }

    /** Every entry the server has not acknowledged. This is also how an offline session recovers. */
    private suspend fun publishUnsyncedEntries(user: String) {
        val pending = _state.value.entries.filter { !it.synced && (it.userId == null || it.userId == user) }
        for ((index, entry) in pending.withIndex()) {
            if (_state.value.userId != user) return
            _state.update { it.copy(syncDetail = "Sending session ${index + 1} of ${pending.size} to Focal\u2026") }
            try {
                val latest = _state.value.entries.firstOrNull { it.id == entry.id && !it.synced } ?: continue
                if (latest.examBoundaries.isNotEmpty()) {
                    publishExamBoundaries(user, latest.id)
                    continue
                }
                val commands = commandsFor(latest)
                if (commands.isEmpty()) { markSynced(latest.id); continue }
                val ack = sendCommands(user, commands)
                if (ack.sent) { commitTimingAnchor(latest, commands); acknowledge(latest.id, ack) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw e }
        }
    }

    private fun markSynced(sessionId: String) {
        _state.update { state -> state.copy(entries = state.entries.map {
            if (it.id == sessionId) it.copy(synced = true) else it
        }) }
        if (!persist()) error("Could not save the session receipt")
    }

    /**
     * Take the server's own word for a published session: its canonical payload, its revision and
     * the change id the feed will echo. Without this the entry keeps the revision and change id it
     * had before the publish, so the very next pull reads the device's own write back as somebody
     * else's change and the "changed on another device" notice repeats.
     */
    private fun acknowledge(sessionId: String, ack: FocalPublishAck) {
        if (!ack.sent) return
        val mutationId = ack.mutationIds.lastOrNull()
        val revision = ack.canonical?.optLong("revision")?.takeIf { it >= 0L }
        val payload = ack.canonical?.toString()
        ack.mutationIds.forEach { rememberOwnChange(it) }
        _state.update { state -> state.copy(entries = state.entries.map { entry ->
            when {
                entry.id != sessionId -> entry
                // A newer button press replaced the snapshot while this RPC was in flight.
                // Keep its intervals pending, but retain the accepted server revision so its
                // next publish starts at the right canonical boundary, not revision zero.
                entry.changeId !in ack.mutationIds -> entry.copy(
                    revision = revision ?: entry.revision, remotePayload = payload ?: entry.remotePayload)
                else -> entry.copy(synced = true, changeId = mutationId ?: entry.changeId,
                    revision = revision ?: entry.revision, remotePayload = payload ?: entry.remotePayload)
            }
        }) }
        if (!persist()) error("Could not save the session receipt")
    }

    /** Returns false when the server refused the change, true once the session is acknowledged. */
    private suspend fun sendCommands(user: String, commands: List<JSONObject>): FocalPublishAck {
        var command = commands.first()
        var rest = commands.drop(1)
        var rebases = 0
        // Every mutation id this device puts on the wire, including the ones a stale rebase
        // mints. The feed echoes them back as change ids, so they are how a later pull knows
        // that a row moved because of this device and not another one.
        val mutationIds = mutableListOf<String>()
        var canonical: JSONObject? = null
        while (true) {
            val result = syncRemote.mutateStudySession(user, command)
            command.optString("mutation_id").takeIf { it.isNotBlank() }?.let { mutationIds += it }
            observeServerNow(result.optString("server_now").takeIf { it.isNotBlank() })
            val reason = result.optString("reason").takeIf { it.isNotBlank() }
            canonical = result.optJSONObject("session") ?: canonical
            if (reason == "stale_revision" && canonical != null) {
                val state = canonical.optString("state")
                val phase = canonical.optString("phase").takeIf { it.isNotBlank() }
                val action = command.optString("action")
                val satisfied = focalMutationSatisfied(action, state, phase, command.optString("phase").takeIf { it.isNotBlank() })
                val terminal = state in setOf("completed", "cancelled")
                if (satisfied) {
                    if (rest.isEmpty()) return FocalPublishAck(true, canonical, mutationIds)
                    command = JSONObject(rest.first().toString()).put("expected_revision", canonical.optLong("revision"))
                    rest = rest.drop(1)
                    continue
                }
                if (terminal || !focalMutationCanRebase(action, state)) {
                    return FocalPublishAck(false, canonical, mutationIds)
                }
                if (rebases++ >= 2) error("Study session changed repeatedly; retry sync to rebase the command")
                command = JSONObject(command.toString())
                    .put("mutation_id", UUID.randomUUID().toString())
                    .put("expected_revision", canonical.optLong("revision"))
                continue
            }
            if (reason == "session_terminal") return FocalPublishAck(false, canonical, mutationIds)
            if (reason == "not_found" || reason == "invalid_transition") {
                return FocalPublishAck(false, canonical, mutationIds)
            }
            val satisfied = canonical?.let { row -> focalMutationSatisfied(command.optString("action"),
                row.optString("state"), row.optString("phase"), command.optString("phase")) } == true
            if (!result.optBoolean("applied") && !satisfied) {
                error("study_session_mutate failed: ${reason ?: "server_unavailable"}")
            }
            if (rest.isEmpty()) return FocalPublishAck(true, canonical, mutationIds)
            command = JSONObject(rest.first().toString()).put("expected_revision", canonical?.optLong("revision"))
            rest = rest.drop(1)
        }
    }

    private fun reportPublishFailure(step: String, e: Exception) {
        val causes = generateSequence(e as Throwable?) { it.cause }.toList()
        val reason = when {
            e.message?.startsWith("This offline timer") == true ||
                e.message?.startsWith("This session") == true ||
                e.message?.startsWith("This exam session") == true -> e.message!!
            causes.any { it is java.net.UnknownHostException } -> "Focal could not be reached. Check your internet connection."
            causes.any { it is java.net.SocketTimeoutException } -> "Focal did not respond in time. Check your connection and try again."
            causes.any { it is java.io.IOException } -> "The connection to Focal was interrupted. Check your connection and try again."
            else -> "Focal could not complete the request. A server or account issue may be involved. Try again, or sign out and reconnect your account."
        }
        val advice = if (reason == e.message) "This session remains saved on this device; review it before retrying."
            else "Sessions remain saved on this device and will be sent when you are back online."
        _state.update { it.copy(error = "$step failed. $reason $advice") }
    }

    private suspend fun loadRemoteSessions(user: String) {
        val prior = _state.value
        var cursor = prior.remoteRevision.takeIf { prior.remoteRevisionUser == user } ?: 0L
        val startCursor = cursor
        val latest = linkedMapOf<String, Pair<JSONObject, String>>()
        val observedSessionIds = mutableSetOf<String>()
        var latestLamport = if (prior.remoteRevisionUser == user) prior.remoteLamport else 0L
        var pages = 0
        while (pages++ < 100) {
            val page = syncRemote.read(user, cursor, 500)
            observeServerNow(page.serverNow)
            if (page.mode == "snapshot") {
                latest.clear()
                page.rows.filter { it.entity == "study_sessions" && it.payload != null }.forEach { row ->
                    latest[row.rowId] = row.payload!! to "snapshot:${row.rowId}:${row.lamport}"
                    latestLamport = maxOf(latestLamport, row.lamport)
                }
                cursor = page.head
                break
            }
            page.changes.forEach { change ->
                cursor = maxOf(cursor, change.seq)
                if (change.entity == "study_sessions" && change.payload != null) {
                    latest[change.rowId] = change.payload to change.changeId
                    observedSessionIds += change.rowId
                    latestLamport = maxOf(latestLamport, change.lamport)
                }
            }
            if (page.changes.size < 500 || cursor >= page.head) break
        }
        if (_state.value.userId != user) return
        val previous = _state.value.entries.associateBy { it.id }
        val remote = latest.mapNotNull { (id, value) -> focalEntryFromCanonical(id, value.first, value.second, user) }
        val externalChanges = focalExternalChanges(
            previous.values.toList(), remote, ownChangeIds, noticedExternalChanges)
        val notice = if (remoteNoticeReadyUser == user && externalChanges.isNotEmpty()) {
            if (externalChanges.any { incoming -> incoming.completed && previous[incoming.id]?.active == true })
                "A Focal session finished on another device"
            else if (externalChanges.size == 1) "A Focal session changed on another device"
            else "${externalChanges.size} Focal sessions changed on another device"
        } else null
        externalChanges.forEach { noticedExternalChanges[it.id] = it.changeId }
        _state.update { state ->
            // A session the server echoed back is acknowledged; anything it did not echo is
            // still local truth and the next publish pass sends the difference.
            val merged = state.entries.filter { it.userId == null || it.userId == user }
                .associateBy { it.id }.toMutableMap()
            remote.forEach { incoming -> merged[incoming.id] = focalMergeSession(merged[incoming.id], incoming) }
            val otherAccounts = state.entries.filter { it.userId != null && it.userId != user }
            val focus = state.focus?.let { current ->
                val session = merged[current.sessionId] ?: return@let current
                if (!session.active) return@let null
                if (remote.none { it.id == current.sessionId } || !session.synced) return@let current
                val accumulated = session.intervals.filter { it.endAt != null }
                    .sumOf { ((it.endAt ?: it.startAt) - it.startAt).coerceAtLeast(0L) }
                val runningStart = session.intervals.lastOrNull()?.takeIf { it.endAt == null }?.startAt
                current.copy(intervals = session.intervals, accumulatedMillis = accumulated,
                    resumedAt = if (!session.paused) runningStart ?: now() else null)
            }
            state.copy(entries = merged.values.toList() + otherAccounts, focus = focus,
                remoteRevision = maxOf(startCursor, cursor), remoteRevisionUser = user,
                remoteLamport = latestLamport,
                remoteNotice = notice ?: state.remoteNotice,
                remoteNoticeId = if (notice != null) state.remoteNoticeId + 1 else state.remoteNoticeId)
        }
        persist()
        remoteNoticeReadyUser = user
    }

    private suspend fun loadCustomSubjects(user: String) {
        val prior = _state.value
        val sameUser = prior.remoteSubjectsUser == user
        var cursor = if (sameUser) prior.remoteSubjectsRevision else 0L
        val builtInIds = FocalSubjects.builtIn.mapTo(mutableSetOf()) { it.id }
        val custom = (if (sameUser) prior.subjects else emptyList())
            .filterNot { it.id in builtInIds }.associateBy { it.id }.toMutableMap()
        var pages = 0
        while (pages++ < 100) {
            val page = syncRemote.read(user, cursor, 500)
            observeServerNow(page.serverNow)
            if (page.mode == "snapshot") {
                custom.clear()
                page.rows.filter { it.entity == "custom_subjects" }.forEach { row ->
                    focalApplyCustomSubject(custom, row.rowId, row.operation, row.payload)
                }
                cursor = page.head
                break
            }
            page.changes.sortedBy { it.seq }.forEach { change ->
                if (change.entity == "custom_subjects")
                    focalApplyCustomSubject(custom, change.rowId, change.operation, change.payload)
                cursor = maxOf(cursor, change.seq)
            }
            if (cursor >= page.head || page.changes.size < 500) break
        }
        if (_state.value.userId == user) {
            _state.update { it.copy(subjects = FocalSubjects.builtIn + custom.values,
                remoteSubjectsRevision = if (sameUser) maxOf(it.remoteSubjectsRevision, cursor) else cursor,
                remoteSubjectsUser = user) }
            if (!persist()) error("Could not save the Folio subject sync cursor")
        }
    }
}

private fun focalLegacyMetadata(raw: JSONObject?): JSONObject {
    if (raw == null) return JSONObject()
    val result = JSONObject()
    val keys = listOf(
        "integrations", "reflection", "createdVia", "description", "topics", "examtrack", "folio",
        "provider", "examYear", "paper", "marks", "readingMinutes", "writingMinutes", "workspaceItems",
        "unit", "scheduledAt", "durationMinutes", "maxScore"
    )
    val nested = raw.optJSONObject("metadata")
    keys.forEach { key ->
        when {
            raw.has(key) -> result.put(key, raw.get(key))
            nested?.has(key) == true -> result.put(key, nested.get(key))
        }
    }
    return result
}

internal fun focalStudyCommand(
    entry: FocalStudyEntry,
    action: String,
    expectedRevision: Long,
    deviceId: String,
    mutationId: String = entry.changeId,
    timing: FocalSessionCommandTiming? = null
): JSONObject {
    val raw = entry.remotePayload?.let { runCatching { JSONObject(it) }.getOrNull() }
    val canonicalMetadata = raw?.optJSONObject("metadata")
    val metadata = if (canonicalMetadata != null) JSONObject(canonicalMetadata.toString())
        else JSONObject().put("legacy_metadata", focalLegacyMetadata(raw))
    val folio = metadata.optJSONObject("folio") ?: JSONObject().also { metadata.put("folio", it) }
    folio.put("kind", if (entry.kind == "exam") "exam" else "focus")
        .put("notebook_id", entry.notebookId ?: JSONObject.NULL)
        .put("notebook_title", entry.notebookTitle ?: JSONObject.NULL)
    val phase = entry.examPhaseBeforePause?.takeIf { it in setOf("reading", "writing") }
        ?: entry.examPhase?.takeIf { it in setOf("reading", "writing") }
        ?: if (entry.kind == "exam") "writing" else "focus"
    folio.put("phase_before_pause", if (entry.paused) phase else JSONObject.NULL)
    if (entry.completed && entry.intervals.isEmpty() && entry.activeMillis > 0L)
        folio.put("reported_active_ms", entry.activeMillis)
    val reflection = metadata.optJSONObject("reflection") ?: JSONObject().also { metadata.put("reflection", it) }
    if (entry.notes.isBlank()) reflection.remove("notes") else reflection.put("notes", entry.notes)
    if (entry.confidence == null) reflection.remove("confidence") else reflection.put("confidence", entry.confidence)
    return JSONObject().put("mutation_id", mutationId).put("session_id", entry.id)
        .put("expected_revision", expectedRevision).put("action", action).put("device_id", deviceId)
        .put("app", "folio").put("kind", if (entry.kind == "exam") "exam" else "focus")
        .put("phase", phase).put("title", entry.title)
        .put("subject_id", entry.subjectId ?: JSONObject.NULL).put("metadata", metadata)
        .put("occurred_at", timing?.occurredAt ?: JSONObject.NULL)
        .put("elapsed_since_previous_ms", timing?.elapsedMs ?: 0L)
}

internal fun focalCanonicalState(payload: String?): String? = payload?.let { raw -> runCatching {
    val value = JSONObject(raw)
    value.optString("state").takeIf { it.isNotBlank() }
        ?: when (value.optJSONObject("execution")?.optString("state") ?: value.optString("status")) {
            "in-progress", "running" -> if (value.optJSONObject("integrations")?.optJSONObject("folio")?.optString("phase") == "paused") "paused" else "running"
            "completed" -> "completed"
            "planned" -> "planned"
            else -> null
        }
}.getOrNull() }

internal fun focalDesiredState(entry: FocalStudyEntry): String = when {
    entry.deleted -> "cancelled"
    entry.completed -> "completed"
    entry.planned -> "planned"
    entry.paused -> "paused"
    else -> "running"
}

internal fun focalMutationCanRebase(action: String, state: String?): Boolean = when (action) {
    "create" -> false
    "start" -> state == "planned"
    "pause" -> state == "running"
    "resume" -> state == "paused"
    "phase_change", "save_progress" -> state in setOf("running", "paused")
    "complete", "cancel" -> state in setOf("planned", "running", "paused")
    else -> false
}

internal fun focalMutationSatisfied(action: String, state: String?, phase: String?, requestedPhase: String? = null): Boolean = when (action) {
    "create" -> state != null
    "start", "resume" -> state == "running"
    "pause" -> state == "paused"
    "complete" -> state == "completed"
    "cancel" -> state == "cancelled"
    "phase_change" -> phase == requestedPhase
    else -> false
}

/** What one publish pass learned from the server about the session it just wrote. */
internal data class FocalPublishAck(
    val sent: Boolean,
    val canonical: JSONObject?,
    val mutationIds: List<String>
)

/**
 * The remote sessions that somebody else moved. A row is external when the feed carries a change
 * id the local entry has not seen, the server's revision moved past the local one, this device did
 * not author that change id, and the same change was not already reported. Re-reading one change
 * is not a new event, so a retried sync cannot turn one remote edit into a stream of notices.
 */
internal fun focalExternalChanges(
    local: List<FocalStudyEntry>,
    remote: List<FocalStudyEntry>,
    ownChangeIds: Set<String> = emptySet(),
    alreadyNoticed: Map<String, String> = emptyMap()
): List<FocalStudyEntry> {
    val previous = local.associateBy { it.id }
    return remote.filter { incoming ->
        val old = previous[incoming.id]
        incoming.changeId !in ownChangeIds &&
            alreadyNoticed[incoming.id] != incoming.changeId &&
            old?.changeId != incoming.changeId &&
            (old == null || incoming.revision > old.revision)
    }
}

private fun focalEntryFromCanonical(id: String, payload: JSONObject, changeId: String, user: String): FocalStudyEntry? = runCatching {
    val state = payload.optString("state")
    val kind = payload.optString("kind")
    val metadata = payload.optJSONObject("metadata") ?: JSONObject()
    val legacy = metadata.optJSONObject("legacy_metadata")
    val folio = metadata.optJSONObject("folio") ?: legacy?.optJSONObject("integrations")?.optJSONObject("folio")
    fun epoch(key: String): Long? = payload.optString(key).takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val segments = payload.optJSONArray("segments") ?: JSONArray()
    val intervals = (0 until segments.length()).mapNotNull { index -> runCatching {
        val segment = segments.getJSONObject(index)
        val started = Instant.parse(segment.getString("started_at")).toEpochMilli()
        FocalStudyInterval(started, segment.optString("ended_at").takeIf { it.isNotBlank() }
            ?.let { Instant.parse(it).toEpochMilli() })
    }.getOrNull() }
    val reflection = metadata.optJSONObject("reflection") ?: legacy?.optJSONObject("reflection")
    val phase = payload.optString("phase").takeIf { it in setOf("reading", "writing") }
    val started = epoch("started_at") ?: epoch("created_at") ?: 0L
    val ended = epoch("completed_at") ?: epoch("cancelled_at") ?: epoch("paused_at") ?: epoch("updated_at") ?: started
    val examtrack = legacy?.optJSONObject("integrations")?.optJSONObject("examtrack")
    FocalStudyEntry(
        id = id, changeId = changeId, notebookId = folio?.optString("notebook_id")?.takeIf { it.isNotBlank() },
        title = payload.optString("title", "Study session"), subjectId = payload.optString("subject_id").takeIf { it.isNotBlank() },
        kind = if (kind in setOf("exam", "sac")) "exam" else "study", startedAt = started, endedAt = ended,
        activeMillis = payload.optLong("accumulated_active_ms").takeIf { it > 0L }
            ?: folio?.optLong("reported_active_ms", 0L)?.coerceAtLeast(0L) ?: 0L,
        notes = reflection?.optString("notes").orEmpty(), confidence = reflection?.optInt("confidence")?.takeIf { it in 1..5 },
        userId = user, synced = true, revision = payload.optLong("revision"), completed = state == "completed",
        planned = state == "planned", deleted = state == "cancelled", paused = state == "paused",
        examPhase = phase, examPhaseBeforePause = metadata.optJSONObject("folio")?.optString("phase_before_pause")
            ?.takeIf { it in setOf("reading", "writing") }
            ?: metadata.optJSONObject("examtrack")?.optString("phaseBeforePause")?.takeIf { it in setOf("reading", "writing") }
            ?: examtrack?.optString("phaseBeforePause")?.takeIf { it in setOf("reading", "writing") },
        intervals = intervals, remotePayload = payload.toString(),
        notebookTitle = folio?.optString("notebook_title")?.takeIf { it.isNotBlank() }
    )
}.getOrNull()
