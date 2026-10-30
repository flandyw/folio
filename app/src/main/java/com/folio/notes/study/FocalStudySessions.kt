package com.folio.notes.study

import com.folio.notes.FocalBuildConfig
import com.folio.notes.exam.ExamTimerPhase
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.model.Notebook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

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
    val examLastBoundaryAt: Long? = null,
    /** Pending plan details kept separate from the last acknowledged server row. */
    val planDraft: String? = null,
    /** A timeline move, resize or done/not-done waiting to replace the server's blocks. */
    val pendingBlocks: List<FocalStudyInterval>? = null,
    /** Local integration details, sent with each lifecycle boundary. */
    val metadata: String? = null
) {
    val active: Boolean get() = !completed && !planned && !deleted
    val minutes: Int get() = (activeMillis / 60_000L).toInt().coerceAtLeast(if (completed) 1 else 0)
}

data class FocalStudyInterval(val startAt: Long, val endAt: Long?)

internal fun focalEncodeIntervals(intervals: List<FocalStudyInterval>): JSONArray = JSONArray().also { array ->
    intervals.forEach { array.put(JSONObject().put("startAt", it.startAt).put("endAt", it.endAt)) }
}

internal fun focalDecodeIntervals(saved: JSONArray?): List<FocalStudyInterval> =
    (0 until (saved?.length() ?: 0)).mapNotNull { position -> runCatching {
        val interval = saved!!.getJSONObject(position)
        FocalStudyInterval(interval.getLong("startAt"), interval.optLong("endAt").takeIf { it > 0 })
    }.getOrNull() }

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

/** Pending local boundaries beat an older echo; remote termination beats an active checkpoint. */
internal fun focalMergeSession(local: FocalStudyEntry?, remote: FocalStudyEntry): FocalStudyEntry {
    if (local == null) return remote
    if (local.planDraft != null && local.synced && remote.planned) return local.copy(
        revision = maxOf(local.revision, remote.revision), remotePayload = remote.remotePayload)
    if (remote.revision < local.revision) return local
    // Continued finished study: the server still shows the completion this device reopened.
    if (!local.synced && local.active && remote.completed && remote.revision == local.revision) return local
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
        startedAt = if (local.notebookId != null && !remote.completed) local.startedAt else remote.startedAt,
        notebookId = local.notebookId ?: remote.notebookId,
        notebookTitle = local.notebookTitle ?: remote.notebookTitle)
}

internal fun focalRecoverFocus(entry: FocalStudyEntry, focus: FocalFocus): FocalStudyEntry = entry.copy(
    changeId = UUID.randomUUID().toString(), synced = false, paused = true,
    endedAt = maxOf(entry.startedAt, focus.intervals.lastOrNull()?.endAt ?: entry.endedAt),
    intervals = focus.intervals, activeMillis = focus.accumulatedMillis
)

/** A stable, readable title for a regular study session. */
internal fun focalSessionTitle(subjectId: String?, subjects: List<FocalSubject>): String =
    (subjects.firstOrNull { it.id == subjectId }?.name ?: "Study") + " Focus"

/**
 * Count only the part of a session that overlaps the requested day. This matters for a session
 * that begins before midnight: the old UI added its whole duration whenever it ended today.
 * Finished, paused and running study all count; only the active intervals overlap the day.
 */
internal const val MAX_REPORTED_SESSION_MILLIS = 24 * 60 * 60 * 1_000L

internal fun focalStudyMillisBetween(entries: Iterable<FocalStudyEntry>, from: Long, until: Long): Long =
    focalCountedStudy(entries).sumOf { focalMeasuredMillisBetween(it, from, until) }

/**
 * The sessions that count as study time: measured study, never plans, tombstones, exams or
 * imported calendar blocks. Filter once when summing many ranges (a streak, a week of days),
 * because recognising a calendar block parses the session's payload.
 */
internal fun focalCountedStudy(entries: Iterable<FocalStudyEntry>): List<FocalStudyEntry> =
    entries.filter { it.kind == "study" && !it.planned && !it.deleted && !focalIsCalendarPlaceholder(it) }

/** One counted session's study inside [from, until); a running one counts its open interval up to [until]. */
internal fun focalMeasuredMillisBetween(entry: FocalStudyEntry, from: Long, until: Long): Long {
    val measured = if (entry.active) entry.copy(
        endedAt = if (entry.paused) entry.endedAt else until,
        activeMillis = maxOf(entry.activeMillis, entry.intervals.sumOf {
            ((it.endAt ?: until) - it.startAt).coerceAtLeast(0L)
        })
    ) else entry
    return focalActiveMillisBetween(measured, from, until)
}

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

/** Keep exam lifecycle boundaries apart from active-time analytics intervals. Every boundary is
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
    val activeMillis = timer.elapsedActiveMillis(now)
    val activeEnd = started + timer.pausedMillis + activeMillis
    val intervals = (existing?.intervals ?: emptyList()).toMutableList()
    if (intervals.isEmpty()) {
        intervals.add(FocalStudyInterval((activeEnd - activeMillis).coerceAtLeast(started),
            if (timer.paused || completed) activeEnd else null))
    } else if (intervals.isNotEmpty() && (timer.paused || completed) && intervals.last().endAt == null) {
        intervals[intervals.lastIndex] = intervals.last().copy(endAt = activeEnd)
    } else if (intervals.last().endAt != null && (timer.running || completed)) {
        val recordedMillis = intervals.sumOf { ((it.endAt ?: it.startAt) - it.startAt).coerceAtLeast(0L) }
        val newMillis = (activeMillis - recordedMillis).coerceAtLeast(0L)
        // Finishing an already-paused exam adds no work and must not append an empty interval.
        if (!completed || newMillis > 0L)
            intervals.add(FocalStudyInterval((activeEnd - newMillis).coerceAtLeast(started),
                if (completed) activeEnd else null))
    }
    return (existing ?: FocalStudyEntry(notebookId = note.id, title = focalSessionTitle(subject, subjects),
        subjectId = subject, kind = "exam", startedAt = started,
        endedAt = now, activeMillis = activeMillis, notebookTitle = note.title)).copy(
        changeId = UUID.randomUUID().toString(),
        title = focalSessionTitle(subject, subjects), subjectId = subject, kind = "exam",
        endedAt = now.coerceAtLeast(started + 1_000L), activeMillis = activeMillis, notebookTitle = note.title,
        completed = completed, deleted = completed && activeMillis == 0L,
        paused = timer.paused, examPhase = timer.phase.name.lowercase(),
        examPhaseBeforePause = if (timer.paused) timer.phase.name.lowercase() else null,
        intervals = intervals,
        // Publish the first checkpoint so another Focal client can see the active sitting, but
        // keep later timer checkpoints local. The stable row_id lets the terminal update replace
        // that in-progress record in clients that understand the change log.
        synced = if (!completed && !forceUpload) existing?.synced ?: false else false
    )
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
    val needsSignIn: Boolean = false,
    val reconnecting: Boolean = false,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val syncDetail: String? = null,
    val localSaveFailed: Boolean = false,
    val error: String? = null,
    val authMessage: String? = null,
    /** Transient in-app notice for a change received after the first remote load. */
    val remoteNotice: String? = null,
    val remoteNoticeId: Long = 0L,
    val configured: Boolean = !FocalBuildConfig.FOCAL_SUPABASE_URL.contains("example.supabase.co") &&
        !FocalBuildConfig.FOCAL_SUPABASE_PUBLISHABLE_KEY.contains("example_placeholder")
) {
    val visibleEntries get() = entries.filter { !it.deleted && (it.userId == null || it.userId == userId) }
    val visibleFocus get() = focus?.takeIf { current ->
        val owner = entries.firstOrNull { it.id == current.sessionId }?.userId
        owner == null || owner == userId
    }
    val pendingCount get() = entries.count { !it.synced && (it.userId == null || it.userId == userId) }
    val canStartFocus get() = focus == null
    val syncStatus get() = when {
        needsSignIn -> "Sign in to Focal again · saved work is kept on this device"
        reconnecting -> "Reconnecting to Focal · saved work will sync automatically"
        syncing -> syncDetail ?: "Connecting to Focal…"
        error != null -> "Focal sync needs attention"
        userId == null || !configured -> "Saved on this device"
        pendingCount > 0 -> "$pendingCount waiting to sync"
        else -> "Synced with Focal"
    }
}

/** A crash-recoverable copy; the app-owned running focus is never changed by a disk write. */
internal fun focalStudyCheckpoint(state: FocalStudyState, now: Long): FocalStudyState {
    // A practice visit ends with the process; never count time after its last heartbeat.
    val checkpoint = state.copy(entries = state.entries.map { entry ->
        if (!entry.active || entry.id == state.focus?.sessionId ||
            entry.metadata?.let { JSONObject(it).optBoolean("mistakePractice") } != true) entry
        else {
            val intervals = entry.intervals.map { interval ->
                if (interval.endAt == null) interval.copy(endAt = now.coerceAtLeast(interval.startAt)) else interval
            }
            val metadata = JSONObject(entry.metadata)
            metadata.optJSONObject("examtrack")?.optJSONArray("questions")?.let { questions ->
                intervals.forEachIndexed { index, interval -> questions.optJSONObject(index)?.put("endAt", interval.endAt) }
            }
            entry.copy(intervals = intervals, completed = true, paused = true, synced = false,
                changeId = UUID.randomUUID().toString(), metadata = metadata.toString(),
                endedAt = intervals.lastOrNull()?.endAt ?: now,
                activeMillis = intervals.sumOf { it.endAt!! - it.startAt })
        }
    })
    val focus = checkpoint.focus ?: return checkpoint
    if (focus.resumedAt == null) return checkpoint
    val parked = focus.pause(now, FocalFocus.PARK_CLOSED)
    return checkpoint.copy(focus = parked, entries = checkpoint.entries.map { entry ->
        if (entry.id == focus.sessionId && entry.active) focalRecoverFocus(entry, parked) else entry
    })
}
