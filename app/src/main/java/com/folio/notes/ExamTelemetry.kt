package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject

/**
 * When the student looked at one page during a timed sitting: wall-clock milliseconds since the
 * epoch, matching [Stroke.createdAt]. [exitedAt] is null while the visit is still open; the
 * sitting's end closes it, so a sitting that is still running always has one open visit.
 */
data class PageVisit(
    val pageId: String,
    val enteredAt: Long,
    val exitedAt: Long? = null
)

/**
 * The timing record of one exam sitting. Stroke timestamps live on the strokes themselves (see
 * [Stroke.createdAt]) so they survive undo, edits and backups; this carries the sitting's window
 * plus the page-visit log that turns "time on each page" from writing-only into dwell.
 */
data class ExamTelemetry(
    /** When the sitting started (the timer's start moment, including reading time). */
    val startedAt: Long,
    /** When the sitting stopped, or null while it is still running. */
    val endedAt: Long? = null,
    val visits: List<PageVisit> = emptyList()
)

/** JSON round-trips for [ExamTelemetry], shared by the attempt codec and the prefs backup. */
object ExamTelemetryCodec {
    fun encode(telemetry: ExamTelemetry): JSONObject = JSONObject().apply {
        put("startedAt", telemetry.startedAt)
        telemetry.endedAt?.let { put("endedAt", it) }
        put("visits", encodeVisits(telemetry.visits))
    }

    fun decode(o: JSONObject?): ExamTelemetry? {
        if (o == null) return null
        val startedAt = if (o.has("startedAt") && !o.isNull("startedAt")) o.optLong("startedAt") else 0L
        if (startedAt <= 0L) return null
        return ExamTelemetry(
            startedAt = startedAt,
            endedAt = if (o.has("endedAt") && !o.isNull("endedAt")) o.optLong("endedAt") else null,
            visits = decodeVisits(o.optJSONArray("visits"))
        )
    }

    /** The visit log on its own, so the running sitting can back it up to preferences. */
    fun encodeVisits(visits: List<PageVisit>): JSONArray = JSONArray().apply {
        visits.forEach { visit -> put(JSONObject().apply {
            put("pageId", visit.pageId)
            put("enteredAt", visit.enteredAt)
            visit.exitedAt?.let { put("exitedAt", it) }
        }) }
    }

    fun decodeVisits(array: JSONArray?): List<PageVisit> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val v = array.optJSONObject(i) ?: return@mapNotNull null
            val pageId = v.optString("pageId", "")
            if (pageId.isEmpty() || !v.has("enteredAt") || v.isNull("enteredAt")) return@mapNotNull null
            PageVisit(
                pageId = pageId,
                enteredAt = v.optLong("enteredAt"),
                exitedAt = if (v.has("exitedAt") && !v.isNull("exitedAt")) v.optLong("exitedAt") else null
            )
        }
    }
}

/**
 * Records arriving on [pageId] at [now] during a timed sitting. A repeat of the page already open
 * changes nothing, so scroll callbacks that re-report the visible page do not split a visit; a
 * different page closes the open visit and opens a new one. Pure so the rule stays unit-tested.
 */
fun recordVisit(visits: List<PageVisit>, pageId: String, now: Long): List<PageVisit> {
    val last = visits.lastOrNull()
    if (last != null && last.exitedAt == null) {
        if (last.pageId == pageId) return visits
        val closed = visits.dropLast(1) + last.copy(exitedAt = now)
        return (closed + PageVisit(pageId, now)).takeLast(MAX_VISITS)
    }
    return (visits + PageVisit(pageId, now)).takeLast(MAX_VISITS)
}

/** Closes any open visit at [now], so a stopped sitting leaves no dangling "still here". */
fun closeVisits(visits: List<PageVisit>, now: Long): List<PageVisit> {
    val last = visits.lastOrNull() ?: return visits
    if (last.exitedAt != null) return visits
    return visits.dropLast(1) + last.copy(exitedAt = now)
}

/** One stroke placed on the replay timeline: when it landed and which page it belongs to. */
data class ReplayEvent(val atMs: Long, val pageIndex: Int, val pageId: String)

/** One quiet stretch with no writing inside the sitting window. */
data class IdleGap(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** Writing and dwell totals for one page of a sitting, in page order. */
data class PageTiming(
    val pageId: String,
    val pageIndex: Int,
    /** Wall-clock time the page was open, from the visit log clipped to the window. */
    val dwellMs: Long,
    /** Writing time attributed to the page from inter-stroke gaps (see [analyzeSitting]). */
    val activeMs: Long,
    val strokes: Int,
    /** Strokes that landed inside the final rush window. */
    val rushStrokes: Int
)

/** Everything the timing report and the replay need, computed once per attempt. */
data class SittingAnalysis(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val totalStrokes: Int,
    /** Gap-attributed writing time: each stroke owns the time since the previous one, capped. */
    val activeMs: Long,
    val idleGaps: List<IdleGap>,
    val pages: List<PageTiming>,
    /** Strokes in time order, the frames the replay steps through. */
    val timeline: List<ReplayEvent>,
    /** Stroke counts per minute of the window, backing the activity chart. */
    val perMinute: List<Int>
) {
    val durationMs: Long get() = (windowEndMs - windowStartMs).coerceAtLeast(0L)
    val idleMs: Long get() = idleGaps.sumOf { it.durationMs }
    val rushStrokes: Int get() = pages.sumOf { it.rushStrokes }
    /** Share of strokes in the final rush window, or null when nothing was written. */
    val rushShare: Float? get() = if (totalStrokes > 0) rushStrokes.toFloat() / totalStrokes else null
}

/**
 * Analyses one sitting of [note] as recorded on [attempt].
 *
 * The window is the telemetry's start-to-end, falling back to the attempt's own mark
 * (date minus recorded seconds) for timed sittings from before telemetry existed. Returns null
 * when there is no usable window — an untimed attempt with no telemetry has nothing to analyse.
 *
 * Writing time is attributed from the gaps between consecutive strokes: a stroke owns the time
 * since the previous one, capped at [activeGapCapMs], so a five-minute stare counts as idle
 * rather than writing. Gaps over [idleThresholdMs] — including a slow start and an early finish —
 * are reported as idle periods. Strokes inside the last [rushWindowMs] count towards the rush.
 */
fun analyzeSitting(
    note: Notebook,
    attempt: ExamAttempt,
    idleThresholdMs: Long = IDLE_THRESHOLD_MS,
    activeGapCapMs: Long = ACTIVE_GAP_CAP_MS,
    rushWindowMs: Long = RUSH_WINDOW_MS
): SittingAnalysis? {
    val telemetry = attempt.telemetry
    val start = telemetry?.startedAt
        ?: if (attempt.timed && (attempt.secondsTaken ?: 0) > 0) attempt.date - attempt.secondsTaken!! * 1000L else null
    val end = telemetry?.endedAt ?: attempt.date
    if (start == null || start <= 0L || end <= start) return null
    val indexOf = note.pages.mapIndexed { index, page -> page.id to index }.toMap()

    data class TimedStroke(val atMs: Long, val pageIndex: Int, val pageId: String)
    val strokes = note.pages.flatMapIndexed { index, page ->
        page.strokes
            .filter { it.createdAt in start..end }
            .map { TimedStroke(it.createdAt, index, page.id) }
    }.sortedBy { it.atMs }

    // Writing time: each stroke owns the gap since the previous stroke, capped.
    val gaps = strokes.zipWithNext { previous, next -> Triple(next, next.atMs - previous.atMs, previous) }
    val activeByPage = mutableMapOf<Int, Long>()
    var activeMs = 0L
    gaps.forEach { (stroke, gap, _) ->
        val owned = gap.coerceAtMost(activeGapCapMs).coerceAtLeast(0L)
        activeMs += owned
        activeByPage[stroke.pageIndex] = (activeByPage[stroke.pageIndex] ?: 0L) + owned
    }

    // Idle periods: gaps over the threshold, plus a slow start and an early finish.
    val idleGaps = mutableListOf<IdleGap>()
    if (strokes.isEmpty()) {
        if (end - start > idleThresholdMs) idleGaps += IdleGap(start, end)
    } else {
        if (strokes.first().atMs - start > idleThresholdMs) idleGaps += IdleGap(start, strokes.first().atMs)
        gaps.forEach { (_, gap, previous) ->
            if (gap > idleThresholdMs) idleGaps += IdleGap(previous.atMs, previous.atMs + gap)
        }
        if (end - strokes.last().atMs > idleThresholdMs) idleGaps += IdleGap(strokes.last().atMs, end)
    }

    // Dwell per page from the visit log, clipped to the window.
    val dwellByPage = mutableMapOf<String, Long>()
    telemetry?.visits?.forEach { visit ->
        if (visit.pageId !in indexOf) return@forEach
        val from = visit.enteredAt.coerceAtLeast(start)
        val to = (visit.exitedAt ?: end).coerceAtMost(end)
        if (to > from) dwellByPage[visit.pageId] = (dwellByPage[visit.pageId] ?: 0L) + (to - from)
    }

    val rushFrom = end - rushWindowMs
    val strokesByPage = strokes.groupBy { it.pageIndex }
    val pages = (strokesByPage.keys + dwellByPage.keys.mapNotNull { indexOf[it] })
        .sorted()
        .map { pageIndex ->
            val pageId = note.pages[pageIndex].id
            val onPage = strokesByPage[pageIndex].orEmpty()
            PageTiming(
                pageId = pageId,
                pageIndex = pageIndex,
                dwellMs = dwellByPage[pageId] ?: 0L,
                activeMs = activeByPage[pageIndex] ?: 0L,
                strokes = onPage.size,
                rushStrokes = onPage.count { it.atMs >= rushFrom }
            )
        }

    val minutes = ((end - start + 59_999L) / 60_000L).toInt().coerceIn(1, MAX_ANALYSIS_MINUTES)
    val perMinute = IntArray(minutes)
    strokes.forEach { stroke ->
        val bucket = ((stroke.atMs - start) / 60_000L).toInt().coerceIn(0, minutes - 1)
        perMinute[bucket]++
    }

    return SittingAnalysis(
        windowStartMs = start,
        windowEndMs = end,
        totalStrokes = strokes.size,
        activeMs = activeMs,
        idleGaps = idleGaps,
        pages = pages,
        timeline = strokes.map { ReplayEvent(it.atMs, it.pageIndex, it.pageId) },
        perMinute = perMinute.toList()
    )
}

private const val MAX_VISITS = 2000

/** Upper bound for the per-minute activity chart; guards against clock-skewed windows. */
private const val MAX_ANALYSIS_MINUTES = 1440

/** A gap longer than this with no writing counts as an idle period. */
const val IDLE_THRESHOLD_MS = 60_000L

/** The most writing time one stroke can own — anything longer was thinking, not writing. */
const val ACTIVE_GAP_CAP_MS = 60_000L

/** The final stretch reported as the rush. */
const val RUSH_WINDOW_MS = 10L * 60 * 1000
