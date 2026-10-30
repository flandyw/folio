package com.folio.notes.study

import com.folio.notes.model.Notebook
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

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
