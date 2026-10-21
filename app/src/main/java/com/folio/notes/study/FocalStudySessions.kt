package com.folio.notes.study

import com.folio.notes.FocalBuildConfig
import com.folio.notes.exam.ExamTimerPhase
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.exam.VceSubject
import com.folio.notes.model.Notebook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
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

    /** The subject's Folio cover band, so a study block wears the same colour as its notebooks. */
    fun color(id: String?): Long? = folioIds.entries.firstOrNull { it.value == id }?.key?.color

    fun suggest(note: Notebook, subjects: List<FocalSubject> = builtIn): String? {
        folioIds[note.exam.subject]?.let { return it }
        return find(note.exam.subjectText.ifBlank { note.title }, subjects)
    }

    fun find(text: String, subjects: List<FocalSubject> = builtIn): String? {
        val source = text.trim()
        if (source.isBlank()) return null
        val normalized = source.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val exact = subjects.firstOrNull { it.id.equals(source, true) || it.name.equals(source, true) }
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

data class FocalFocus(
    val notebookId: String?,
    val title: String,
    val subjectId: String?,
    val startedAt: Long,
    val resumedAt: Long?,
    val accumulatedMillis: Long = 0L,
    val sessionId: String = UUID.randomUUID().toString(),
    val intervals: List<FocalStudyInterval> = emptyList(),
    val notebookTitle: String? = null,
    /**
     * Started from the editor's stopwatch, so it counts only while the pages are on screen:
     * leaving the editor or backgrounding the app parks it. A session started from the Study
     * page keeps running when the app is in the background.
     */
    val attended: Boolean = false,
    /** Why a paused session is paused, when the app rather than a button press parked it. */
    val parkReason: String? = null
) {
    fun elapsed(now: Long) = accumulatedMillis + (resumedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L)
    fun pause(now: Long, reason: String? = null): FocalFocus {
        val start = resumedAt ?: return this
        val end = now.coerceAtLeast(start)
        return copy(resumedAt = null, parkReason = reason,
            accumulatedMillis = elapsed(end), intervals = intervals.map { interval ->
                if (interval.endAt == null) interval.copy(endAt = end.coerceAtLeast(interval.startAt)) else interval
            })
    }
    fun resume(now: Long) = if (resumedAt != null) this else copy(resumedAt = now, parkReason = null,
        intervals = intervals + FocalStudyInterval(now, null))

    companion object {
        /** The app stopped looking at the pages (left the editor, backgrounded, screen off). */
        const val PARK_AWAY = "away"
        /** Folio was closed or killed while running; recovered at the last durable checkpoint. */
        const val PARK_CLOSED = "closed"
        /** An exam timer started and took over. */
        const val PARK_EXAM = "exam"
        /** The user installed an app update, which restarts Folio. */
        const val PARK_UPDATE = "update"
        const val PARK_PRACTICE = "practice"
    }
}

internal fun focalPlannedFocus(entry: FocalStudyEntry, note: Notebook?, now: Long, attended: Boolean = false): FocalFocus? {
    if (!entry.planned || entry.completed || entry.deleted || entry.kind != "study" ||
        (entry.planDraft != null && entry.synced)) return null
    return FocalFocus(notebookId = note?.id ?: entry.notebookId, title = entry.title.ifBlank { "Study session" },
        subjectId = entry.subjectId, startedAt = now, resumedAt = now, sessionId = entry.id,
        intervals = listOf(FocalStudyInterval(now, null)), notebookTitle = note?.title ?: entry.notebookTitle, attended = attended)
}

/**
 * Finished, timed study from today can be continued: Focal's `resume` reopens the same session with
 * its earlier time kept (Focal's `continueableToday`). Only what the server has as completed, so the
 * reopened row's segments are exactly this entry's intervals.
 */
internal fun focalCanContinue(entry: FocalStudyEntry, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (entry.kind != "study" || !entry.completed || entry.deleted || !entry.synced || entry.pendingBlocks != null ||
        entry.intervals.isEmpty() || entry.intervals.any { it.endAt == null }) return false
    val row = entry.remotePayload?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
    fun stamp(key: String) = row.optString(key).takeIf { it.isNotBlank() && it != "null" }
    val completedAt = stamp("completed_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    return focalStateOf(row) == "completed" && row.optString("kind", "focus") == "focus" && stamp("started_at") != null &&
        completedAt.atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}

/** Planned slots are display context, never evidence of time worked. */
internal fun focalPlannedSlots(entry: FocalStudyEntry): List<FocalStudyInterval> = runCatching {
    if (entry.planned) entry.pendingBlocks?.let { return it }
    val payload = JSONObject(entry.planDraft ?: entry.remotePayload ?: return emptyList())
    val segments = payload.optJSONArray("segments") ?: JSONArray()
    val metadata = payload.optJSONObject("metadata")
    val blocks = metadata?.optJSONObject("schedule")?.optJSONArray("blocks")
        ?: metadata?.optJSONObject("legacy_metadata")?.optJSONObject("schedule")?.optJSONArray("blocks")
    val rows = if (entry.planned && segments.length() > 0) segments else blocks ?: JSONArray()
    (0 until rows.length()).mapNotNull { index -> runCatching {
        val row = rows.getJSONObject(index)
        val start = Instant.parse(row.optString("started_at", row.optString("start"))).toEpochMilli()
        val end = Instant.parse(row.optString("ended_at", row.optString("end"))).toEpochMilli()
        FocalStudyInterval(start, end).takeIf { end > start && end - start <= MAX_REPORTED_SESSION_MILLIS }
    }.getOrNull() }.sortedBy { it.startAt }
}.getOrDefault(emptyList())

/** The time a stopped session occupies: its plan, or the study it recorded. */
internal fun focalSessionBlocks(entry: FocalStudyEntry): List<FocalStudyInterval> = when {
    entry.planned -> focalPlannedSlots(entry)
    else -> entry.pendingBlocks ?: entry.intervals.filter { it.endAt != null }.ifEmpty {
        listOf(FocalStudyInterval(entry.endedAt - entry.activeMillis, entry.endedAt))
    }
}

/** Unsent timer boundaries must sync before they can be replaced by timeline edits. */
internal fun focalCanEditBlocks(entry: FocalStudyEntry): Boolean = entry.kind == "study" &&
    !entry.deleted && !entry.active && (entry.remotePayload != null || entry.intervals.isEmpty()) &&
    !(entry.planned && entry.planDraft != null && entry.synced)

/** Replaces one displayed block, keeping the other blocks and their break gaps. */
internal fun focalReplaceSessionBlock(entry: FocalStudyEntry, index: Int, block: FocalStudyInterval): List<FocalStudyInterval> =
    focalSessionBlocks(entry).mapIndexed { i, original -> if (i == index) block else original }

internal fun focalBlockEdit(current: FocalStudyEntry, blocks: List<FocalStudyInterval>,
                            completed: Boolean, now: Long): FocalStudyEntry? {
    val sorted = blocks.sortedBy { it.startAt }
    if (!focalCanEditBlocks(current) || sorted.isEmpty() || sorted.size > 100 ||
        sorted.any { val end = it.endAt; end == null || end <= it.startAt || end - it.startAt > MAX_REPORTED_SESSION_MILLIS } ||
        sorted.zipWithNext().any { (a, b) -> requireNotNull(a.endAt) > b.startAt } ||
        (completed && requireNotNull(sorted.last().endAt) > now)) return null
    val local = current.remotePayload == null
    if (local && completed && sorted.size > 1) return null
    val end = requireNotNull(sorted.last().endAt)
    // Keep a plan's metadata in step with its blocks, including plans not yet created remotely.
    val edited = if (current.planned && !completed) {
        val (description, topics) = focalPlanDetails(current)
        runCatching { focalPlanEdit(current, current.title, current.subjectId, description, topics, sorted) }.getOrNull()
            ?: return null
    } else current
    return edited.copy(changeId = UUID.randomUUID().toString(), synced = false,
        completed = completed, planned = !completed, startedAt = sorted.first().startAt, endedAt = end,
        activeMillis = if (completed) sorted.sumOf { requireNotNull(it.endAt) - it.startAt } else 0L,
        intervals = if (completed && !local) sorted else emptyList(),
        pendingBlocks = if (local && completed) null else sorted)
}

/** Every block of [sessions] in order, with overlapping or touching blocks joined so shared time counts once. */
internal fun focalMergedBlocks(sessions: List<FocalStudyEntry>): List<FocalStudyInterval> {
    val merged = mutableListOf<FocalStudyInterval>()
    sessions.flatMap(::focalSessionBlocks).filter { it.endAt != null && it.endAt > it.startAt }.sortedBy { it.startAt }.forEach { block ->
        val last = merged.lastOrNull()
        if (last != null && block.startAt <= requireNotNull(last.endAt))
            merged[merged.lastIndex] = last.copy(endAt = maxOf(requireNotNull(last.endAt), requireNotNull(block.endAt)))
        else merged += block
    }
    return merged
}

/** Why [sessions] cannot merge, or null. Focal's `study_session_merge` checks the same rules again. */
internal fun focalMergeRefusal(sessions: List<FocalStudyEntry>): String? {
    val first = sessions.firstOrNull() ?: return "Select at least two sessions to merge."
    return when {
        sessions.size !in 2..100 || sessions.distinctBy { it.id }.size != sessions.size -> "Select 2–100 different sessions to merge."
        sessions.any { it.kind != "study" || it.active || it.deleted || it.paused } -> "Only stopped study sessions can be merged."
        sessions.any { it.subjectId != first.subjectId || it.completed != first.completed } ->
            "Select sessions with the same subject and done/planned status."
        else -> null
    }
}

/**
 * The `study_session_merge` request, as Focal builds it: the first session keeps its title and
 * takes every block plus the others' descriptions, topics and notes; the others are listed with
 * the revisions they were selected at, so a session that changed meanwhile refuses the merge.
 */
internal fun focalMergeRequest(sessions: List<FocalStudyEntry>, deviceId: String, mutationId: String): Pair<JSONObject, JSONArray> {
    val first = sessions.first()
    val blocks = focalMergedBlocks(sessions)
    val details = sessions.map { entry ->
        val metadata = (entry.planDraft ?: entry.remotePayload)?.let { runCatching { JSONObject(it).optJSONObject("metadata") }.getOrNull() } ?: JSONObject()
        val legacy = metadata.optJSONObject("legacy_metadata") ?: JSONObject()
        JSONObject(legacy.toString()).also { joined -> metadata.keys().forEach { joined.put(it, metadata.get(it)) } }
    }
    fun texts(pick: (JSONObject) -> String?) = details.mapNotNull { pick(it)?.trim()?.takeIf { it.isNotBlank() && it != "null" } }.distinct()
    val command = focalStudyCommand(first.copy(notes = (listOf(first.notes) + sessions.drop(1).map { it.notes })
        .map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n\n")), "save_progress", first.revision, deviceId, mutationId)
        .put("blocks", focalBlocksJson(blocks))
    val metadata = command.getJSONObject("metadata")
    metadata.put("description", texts { it.optString("description") }.joinToString("\n\n"))
        .put("topics", JSONArray(details.flatMap { d -> d.optJSONArray("topics")?.let { t -> (0 until t.length()).mapNotNull { t.optString(it).takeIf(String::isNotBlank) } }.orEmpty() }.distinct()))
        .put("merge_sources", JSONArray().also { array -> sessions.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("title", entry.title)
                .put("metadata", entry.remotePayload?.let { runCatching { JSONObject(it).optJSONObject("metadata") }.getOrNull() } ?: JSONObject()))
        } })
    if (first.planned) metadata.put("schedule", JSONObject().put("blocks", focalBlocksJson(blocks)))
    val sources = JSONArray().also { array -> sessions.forEach { array.put(JSONObject().put("id", it.id).put("revision", it.revision)) } }
    return command to sources
}

internal fun focalPlanDetails(entry: FocalStudyEntry): Pair<String, List<String>> = runCatching {
    val metadata = JSONObject(entry.planDraft ?: entry.remotePayload ?: return "" to emptyList()).optJSONObject("metadata")
    val legacy = metadata?.optJSONObject("legacy_metadata")
    val description = (metadata?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }
        ?: legacy?.optString("description")).orEmpty().takeUnless { it == "null" }.orEmpty()
    val topics = metadata?.optJSONArray("topics") ?: legacy?.optJSONArray("topics") ?: JSONArray()
    description to (0 until topics.length()).mapNotNull { topics.optString(it).trim().takeIf { it.isNotBlank() && it != "null" } }.distinct()
}.getOrDefault("" to emptyList())

/** Older website plans may have scheduled segments without a metadata schedule. */
internal fun focalPlanStartPayload(entry: FocalStudyEntry): String? = runCatching {
    val raw = JSONObject(entry.remotePayload ?: "{}")
    val metadata = raw.optJSONObject("metadata") ?: JSONObject().also { raw.put("metadata", it) }
    if (metadata.optJSONObject("schedule")?.optJSONArray("blocks") != null ||
        metadata.optJSONObject("legacy_metadata")?.optJSONObject("schedule")?.optJSONArray("blocks") != null) return null
    val slots = focalPlannedSlots(entry)
    if (slots.isEmpty()) return null
    metadata.put("schedule", JSONObject().put("blocks", JSONArray().also { blocks -> slots.forEach { slot ->
        blocks.put(JSONObject().put("start", Instant.ofEpochMilli(slot.startAt).toString())
            .put("end", Instant.ofEpochMilli(requireNotNull(slot.endAt)).toString()))
    } }))
    raw.toString()
}.getOrNull()

internal fun focalPlanEdit(original: FocalStudyEntry, title: String, subjectId: String?,
                           description: String, topics: List<String>, slots: List<FocalStudyInterval>): FocalStudyEntry {
    require(original.planned && !original.completed && !original.deleted && original.kind == "study") { "Only unstarted study plans can be edited." }
    require(title.trim().length in 1..512) { "Enter a title of 1–512 characters." }
    require(slots.size in 1..100) { "Enter 1–100 time blocks." }
    val sorted = slots.sortedBy { it.startAt }
    require(sorted.all { it.endAt != null && it.endAt > it.startAt && it.endAt - it.startAt <= MAX_REPORTED_SESSION_MILLIS } &&
        sorted.zipWithNext().all { (a, b) -> requireNotNull(a.endAt) <= b.startAt }) { "Time blocks must end after they start, last up to 24 hours and never overlap." }
    val raw = JSONObject(original.planDraft ?: original.remotePayload ?: "{}")
    val metadata = raw.optJSONObject("metadata") ?: JSONObject().also { raw.put("metadata", it) }
    val blocks = JSONArray().also { array -> sorted.forEach { slot -> array.put(JSONObject()
        .put("start", Instant.ofEpochMilli(slot.startAt).toString()).put("end", Instant.ofEpochMilli(requireNotNull(slot.endAt)).toString())) } }
    metadata.put("description", description.trim()).put("topics", JSONArray(topics.map(String::trim).filter(String::isNotBlank).distinct()))
        .put("subjectIds", JSONArray(listOfNotNull(subjectId))).put("schedule", JSONObject().put("blocks", blocks))
    // Keep legacy consumers on the same plan; canonical metadata remains the authoritative copy.
    metadata.optJSONObject("legacy_metadata")?.let { legacy ->
        listOf("description", "topics", "subjectIds", "schedule").forEach { legacy.put(it, metadata.get(it)) }
    }
    raw.put("title", title.trim()).put("subject_id", subjectId ?: JSONObject.NULL)
    raw.remove("segments")
    require(metadata.toString().toByteArray(Charsets.UTF_8).size <= 60_000) { "Plan details are too long. Shorten the notes or topics." }
    return original.copy(title = title.trim(), subjectId = subjectId, synced = false,
        changeId = UUID.randomUUID().toString(), planDraft = raw.toString(), pendingBlocks = null)
}

/** Old saves may contain an open interval without a heartbeat. Its start is all we can prove. */
internal fun focalRecoveredFocus(focus: FocalFocus): FocalFocus = focus.copy(
    resumedAt = null,
    parkReason = if (focus.resumedAt != null) FocalFocus.PARK_CLOSED else focus.parkReason,
    intervals = focus.intervals.map { interval ->
        if (interval.endAt == null) interval.copy(endAt = interval.startAt) else interval
    }
)

/** What a paused session should say about itself; null while it is running. */
internal fun focalFocusStatus(focus: FocalFocus): String? = when {
    focus.resumedAt != null -> null
    focus.parkReason == FocalFocus.PARK_AWAY -> "Stopped when you left the pages"
    focus.parkReason == FocalFocus.PARK_CLOSED -> "Recovered after Folio closed"
    focus.parkReason == FocalFocus.PARK_EXAM -> "Paused for the exam timer"
    focus.parkReason == FocalFocus.PARK_UPDATE -> "Paused for the app update"
    focus.parkReason == FocalFocus.PARK_PRACTICE -> "Paused for mistake practice"
    else -> "Paused"
}

/** Longer explanation under [focalFocusStatus], so the user knows what was and was not counted. */
internal fun focalFocusStatusDetail(focus: FocalFocus): String? = when {
    focus.resumedAt != null -> if (focus.attended) "Pauses by itself when you leave the pages or close Folio." else
        "Keeps counting when you leave this page. If Folio closes, it recovers paused at the last checkpoint."
    focus.parkReason == FocalFocus.PARK_AWAY -> "Time away was not counted. Resume to carry on."
    focus.parkReason == FocalFocus.PARK_CLOSED ->
        "Recovered at the last saved time. Time since that save was not counted. Resume to carry on."
    focus.parkReason == FocalFocus.PARK_EXAM -> "Resume regular study after the exam timer stops."
    focus.parkReason == FocalFocus.PARK_UPDATE -> "Folio paused your session to install an update. Resume to carry on."
    focus.parkReason == FocalFocus.PARK_PRACTICE -> "Practice is recorded separately. Resume regular study when you finish."
    else -> null
}

internal fun focalReconcileFocus(current: FocalFocus, session: FocalStudyEntry): FocalFocus? {
    if (session.id != current.sessionId) return current
    if (!session.active) return null
    if (!session.synced) return current
    val runningStart = session.intervals.lastOrNull()?.takeIf { it.endAt == null }?.startAt
    // An incomplete server timeline cannot establish a new start; keep the last known timer.
    if (!session.paused && runningStart == null) return current
    val accumulated = session.intervals.sumOf { ((it.endAt ?: it.startAt) - it.startAt).coerceAtLeast(0L) }
    val resumedAt = if (session.paused) null else runningStart
    return current.copy(intervals = session.intervals,
        // activeMillis on a running row can include a checkpoint of its open interval.
        // elapsed() adds that interval itself; only closed intervals belong in this base.
        accumulatedMillis = if (session.paused) maxOf(session.activeMillis, accumulated) else accumulated,
        resumedAt = resumedAt,
        parkReason = current.parkReason.takeIf { resumedAt == null && current.resumedAt == null })
}

internal fun focalControlledEntry(current: FocalStudyEntry, action: String, now: Long): FocalStudyEntry? {
    val intervals = current.intervals.toMutableList()
    val last = intervals.lastOrNull()
    when (action) {
        "pause" -> if (last != null && last.endAt == null) intervals[intervals.lastIndex] = last.copy(endAt = now)
        "resume" -> if (last == null || last.endAt != null) intervals.add(FocalStudyInterval(now, null))
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
