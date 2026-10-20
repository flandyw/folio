@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val DAY = 24 * 60f
private const val MIN_SPAN = 30f
private const val SNAP = 5f
// The razor cuts to the minute, one minute in from either end.
private const val CUT_EDGE = 1f
private val TICK_STEPS = listOf(5f, 10f, 15f, 30f, 60f, 120f, 180f, 360f)
private val QUICK_LOGS = listOf(25, 45, 60, 90)
private val ROW = 56.dp
private val BLOCK = 48.dp
// Compact rows still leave a 44dp finger target.
private val COMPACT_ROW = 50.dp
private val COMPACT_BLOCK = 44.dp

private enum class Tone { DONE, LIVE, TODO }
private enum class Grip { START, END, MOVE }
private data class Span(val start: Float, val end: Float)
private data class Block(val key: String, val entry: FocalStudyEntry, val start: Float, val end: Float, val tone: Tone, val count: Int, val index: Int)

private fun clampWindow(span: Span): Span {
    val size = (span.end - span.start).coerceIn(MIN_SPAN, DAY)
    val from = span.start.coerceIn(0f, DAY - size)
    return Span(from, from + size)
}

/** Zooms so the minute under [center] stays where it is on screen. */
private fun zoomWindow(window: Span, factor: Float, center: Float): Span {
    val size = window.end - window.start
    val next = (size * factor).coerceIn(MIN_SPAN, DAY)
    val start = center - (center - window.start) / size * next
    return clampWindow(Span(start, start + next))
}

/** Overlapping blocks go on separate rows, first free row wins, so nothing is drawn on top of anything else. */
private fun pack(blocks: List<Block>): Pair<Map<String, Int>, Int> {
    val ends = mutableListOf<Float>()
    val rows = mutableMapOf<String, Int>()
    blocks.sortedWith(compareBy({ it.start }, { it.end })).forEach { block ->
        var index = ends.indexOfFirst { it <= block.start }
        if (index < 0) { index = ends.size; ends += block.end } else ends[index] = block.end
        rows[block.key] = index
    }
    return rows to maxOf(1, ends.size)
}

private fun snap(minute: Float) = (minute / SNAP).roundToInt() * SNAP
private fun clock(minute: Float): String {
    val total = minute.roundToInt()
    val h = (total / 60) % 24
    val m = total % 60
    return "${if (h % 12 == 0) 12 else h % 12}${if (m != 0) ":%02d".format(Locale.ROOT, m) else ""}${if (h < 12) "am" else "pm"}"
}
private fun duration(minutes: Float): String {
    val rounded = minutes.roundToInt()
    return if (rounded < 60) "${rounded}m" else "${rounded / 60}h${if (rounded % 60 != 0) " ${rounded % 60}m" else ""}"
}
private fun initials(name: String) = name.split(' ', '-').filter { it.firstOrNull()?.isLetter() == true }
    .take(3).joinToString("") { it.take(1).uppercase() }.ifBlank { name.take(3) }

/** The blocks a session occupies: its plan, its finished time, or the time it has run so far. */
private fun spansOf(entry: FocalStudyEntry, focus: FocalFocus?): List<FocalStudyInterval> = when {
    entry.planned -> focalPlannedSlots(entry)
    entry.completed -> focalSessionBlocks(entry)
    focus?.sessionId == entry.id -> focus.intervals
    else -> entry.intervals
}

/**
 * Drag on a block or the draft: its edges resize, its body moves. A vertical drag is left to the
 * scrolling list, and a press that never moves is a tap ([onTap] gets where, in pixels from the left edge).
 * [onDrag] gets the total travel in pixels. Edge grips are 18dp, or a third of the width on a narrow block;
 * a [fixed] grip (a resize handle) always drags that edge.
 */
@Composable
private fun Modifier.timelineDrag(key: Any, draggable: Boolean, onTap: (Float) -> Unit,
                                  onDrag: (Grip, Float) -> Unit, onEnd: () -> Unit, fixed: Grip? = null): Modifier {
    val tap by rememberUpdatedState(onTap)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val canDrag by rememberUpdatedState(draggable)
    val maxEdge = with(LocalDensity.current) { 18.dp.toPx() }
    return pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val edge = minOf(maxEdge, size.width / 3f)
            val grip = fixed ?: when {
                down.position.x <= edge -> Grip.START
                down.position.x >= size.width - edge -> Grip.END
                else -> Grip.MOVE
            }
            var moved = false
            var abandoned = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!moved) {
                    if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) { abandoned = true; break }
                    if (abs(dx) > viewConfiguration.touchSlop && canDrag) moved = true
                }
                if (moved) { drag(grip, dx); change.consume() }
            }
            if (moved) end() else if (!abandoned) tap(down.position.x)
        }
    }
}

/**
 * One day as a zoomable strip of hours, ported from Focal's study timeline: study you did as solid
 * bars, study still to do as dashed ones. Drag across the track to log (past) or plan (future) a
 * block, then drag a block's edges or body to adjust it. Pinch zooms and pans; the overview strip
 * below pans too.
 */
@Composable
internal fun StudyTimeline(
    state: FocalStudyState, now: Long, canStart: Boolean, cardColor: Color,
    onStartPlanned: (String) -> Unit, onStartFocus: (String?) -> Unit, onEditPlan: (String) -> Unit,
    /** Landscape tablet: the timeline spans the page above the timer, so it trades height for width. */
    compact: Boolean = false,
    /** A day (epoch day) another part of the screen asked to show, e.g. a tapped history row. */
    requestedDay: Long? = null, onDayShown: () -> Unit = {},
) {
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    var epochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    // Left open past midnight on "today", the strip moves on to the new today rather than staying on yesterday.
    var lastToday by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    LaunchedEffect(today) {
        if (epochDay == lastToday) epochDay = today.toEpochDay()
        lastToday = today.toEpochDay()
    }
    LaunchedEffect(requestedDay) { requestedDay?.let { epochDay = it; onDayShown() } }
    val date = LocalDate.ofEpochDay(epochDay)
    val isToday = date == today
    // ponytail: minutes are wall-clock offsets from local midnight; a DST changeover day is out by an hour.
    val dayStart = remember(epochDay) { date.atStartOfDay(zone).toInstant().toEpochMilli() }
    fun minuteOf(millis: Long) = (millis - dayStart) / 60_000f
    fun millisAt(minute: Float, day: Long = dayStart) = day + (minute * 60_000f).roundToLong()
    val nowMin = minuteOf(now)

    val focus = state.visibleFocus
    val blocks = remember(state.entries, state.userId, focus, dayStart, now / 15_000L) {
        state.visibleEntries.filter { it.kind == "study" || it.kind == "exam" }.flatMap { entry ->
            val spans = spansOf(entry, focus)
            spans.mapIndexedNotNull { index, span ->
                val start = minuteOf(span.startAt)
                val end = minuteOf(span.endAt ?: now)
                if (end <= 0f || start >= DAY || end <= start) return@mapIndexedNotNull null
                val live = span.endAt == null && entry.active && (focus?.sessionId != entry.id || focus.resumedAt != null)
                Block("${entry.id}:$index", entry, start.coerceAtLeast(0f), end.coerceAtMost(DAY),
                    if (live) Tone.LIVE else if (entry.planned) Tone.TODO else Tone.DONE, spans.size, index)
            }
        }
    }
    val (rows, rowCount) = remember(blocks) { pack(blocks) }
    fun missed(block: Block) = block.tone == Tone.TODO && block.end <= nowMin
    val studied = focalStudyMillisBetween(state.visibleEntries, dayStart, minOf(now, millisAt(DAY))).coerceAtLeast(0L) / 60_000f
    val planned = blocks.filter { it.tone == Tone.TODO && !missed(it) }.sumOf { (it.end - it.start).toDouble() }.toFloat()
    val notDone = blocks.filter(::missed).sumOf { (it.end - it.start).toDouble() }.toFloat()
    val lastEnd = blocks.filter { it.tone == Tone.DONE && it.end <= nowMin }.maxOfOrNull { it.end } ?: -1f

    // Auto-fit hugs everything on the day (and "now"), padded and never tighter than six hours;
    // an empty day shows 7am-11pm so there is room to plan. Zooming overrides it.
    val fit = remember(blocks, isToday, nowMin.roundToInt()) {
        val marks = blocks.flatMap { listOf(it.start, it.end) } + if (isToday) listOf(nowMin) else emptyList()
        if (blocks.isEmpty()) Span(7 * 60f, 23 * 60f) else {
            val start = kotlin.math.floor((marks.min() - 30f) / 60f) * 60f
            val end = kotlin.math.ceil((marks.max() + 30f) / 60f) * 60f
            val grow = maxOf(0f, 6 * 60f - (end - start)) / 2f
            clampWindow(Span(start - grow, end + grow))
        }
    }
    // Unkeyed on purpose: the gesture handlers hold these states for the life of the timeline.
    var view by remember { mutableStateOf<Span?>(null) }
    val window = view ?: fit
    val span = window.end - window.start

    val subjects = state.subjects
    val recentSubject = remember(state.entries, subjects) {
        state.visibleEntries.filter { it.completed && subjects.any { s -> s.id == it.subjectId } }
            .maxByOrNull { it.endedAt }?.subjectId ?: subjects.firstOrNull()?.id
    }
    var draft by remember { mutableStateOf<Span?>(null) }
    var draftOrigin by remember { mutableStateOf<Span?>(null) }
    var drag by remember { mutableStateOf<Span?>(null) }
    var adjust by remember { mutableStateOf<Pair<String, Span>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var doneOverride by remember { mutableStateOf<Boolean?>(null) }
    var subjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var razor by remember { mutableStateOf(false) }
    // The razor's pending cut: the block it is on and the (fractional) minute, until ticked or moved.
    var cut by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val trackWidth = remember { mutableFloatStateOf(1f) }
    fun trackWidthPx() = trackWidth.floatValue
    // Select mode: taps pick whole sessions (by entry id) to merge.
    var picking by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf(emptySet<String>()) }
    var merging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(epochDay) {
        view = null; draft = null; draftOrigin = null; drag = null; adjust = null; selected = null; confirmRemove = false
        message = null; cut = null; razor = false; picking = false; picks = emptySet()
    }
    // Results ("Moved to 4pm–5pm") fade after a few seconds; a tool's instructions stay while it is on.
    LaunchedEffect(message, razor, picking) {
        if (message != null && !razor && !picking) { kotlinx.coroutines.delay(5_000); message = null }
    }
    // "Remove for good?" is a second tap, not a state the block is left in.
    LaunchedEffect(confirmRemove, selected) { if (confirmRemove) { kotlinx.coroutines.delay(4_000); confirmRemove = false } }

    fun reveal(range: Span) {
        if (range.start >= window.start && range.end <= window.end) return
        val size = maxOf(span, (range.end - range.start) * 1.5f)
        val center = (range.start + range.end) / 2f
        view = clampWindow(Span(center - size / 2f, center + size / 2f))
    }
    fun open(range: Span) {
        val next = Span(range.start.coerceIn(0f, DAY - SNAP), range.end.coerceIn(range.start + SNAP, DAY))
        draft = next; reveal(next); selected = null; doneOverride = null; message = null
        if (subjectId == null) subjectId = recentSubject
    }
    fun quick(minutes: Int) = open(Span(maxOf(0f, kotlin.math.floor(nowMin) - minutes), kotlin.math.floor(nowMin)))
    fun zoomBy(factor: Float) {
        val center = draft?.let { (it.start + it.end) / 2f } ?: if (isToday && nowMin in window.start..window.end) nowMin else (window.start + window.end) / 2f
        view = zoomWindow(window, factor, center)
    }
    fun zoomTo(block: Block) {
        val size = maxOf(60f, (block.end - block.start) * 2.5f)
        val center = (block.start + block.end) / 2f
        view = clampWindow(Span(center - size / 2f, center + size / 2f))
    }
    /** Only complete blocks inside this day can be moved; clipped overnight blocks keep their full range. */
    fun movable(block: Block): Boolean {
        val original = focalSessionBlocks(block.entry).getOrNull(block.index) ?: return false
        return focalCanEditBlocks(block.entry) && block.tone != Tone.LIVE && block.entry.id != focus?.sessionId &&
            original.startAt >= dayStart && original.endAt != null && original.endAt <= millisAt(DAY)
    }
    fun splittable(block: Block) = movable(block) && block.count == 1
    fun persist(block: Block, range: Span) {
        val next = focalReplaceSessionBlock(block.entry, block.index, FocalStudyInterval(millisAt(range.start), millisAt(range.end)))
        val ok = manager.editBlocks(block.entry.id, next, block.entry.completed)
        message = if (ok) "Moved to ${clock(range.start)}–${clock(range.end)}" else "That block can't go there."
    }

    /** A block's range while one of its edges or its body is dragged [dx] pixels. */
    fun dragged(live: Block, grip: Grip, dx: Float): Span {
        val delta = dx / trackWidthPx() * span
        val next = when (grip) {
            Grip.START -> Span(minOf(snap(live.start + delta), live.end - SNAP), live.end)
            Grip.END -> Span(live.start, maxOf(snap(live.end + delta), live.start + SNAP))
            Grip.MOVE -> {
                val length = live.end - live.start
                val start = snap(live.start + delta).coerceIn(0f, DAY - length)
                Span(start, start + length)
            }
        }
        return Span(next.start.coerceAtLeast(0f), next.end.coerceAtMost(DAY))
    }

    fun pick(on: Boolean, first: String? = null) {
        picking = on; picks = setOfNotNull(first); razor = false; cut = null; draft = null; selected = null; confirmRemove = false
        message = if (on) "Select: tap the sessions to merge, then Merge." else null
    }
    /** A stopped study session, whatever its shape, can be merged with others. */
    fun mergeable(block: Block) = block.entry.kind == "study" && block.tone != Tone.LIVE && !block.entry.active && block.entry.id != focus?.sessionId
    /** Merge through Focal: the earliest picked session takes every block and the rest are removed in one step. */
    fun merge(ids: Set<String>) {
        val ordered = blocks.filter { it.entry.id in ids }.sortedBy { it.start }.map { it.entry.id }.distinct()
        merging = true
        scope.launch {
            val refusal = try { manager.mergeSessions(ordered) } finally { merging = false }
            if (refusal != null) { message = refusal; return@launch }
            message = "Merged ${ordered.size} sessions"
            picking = false; picks = emptySet()
        }
    }

    /** Where a tap without a pointer position cuts: now if it is inside the block, else the middle. */
    fun splitPoint(block: Block) = snap(if (isToday && nowMin - block.start >= SNAP && block.end - nowMin >= SNAP) nowMin else (block.start + block.end) / 2f)
    /** Razor: the session keeps the first part and a new one takes the rest. The new part is made first so a failure cannot lose study. */
    fun split(block: Block, at: Float) {
        cut = null
        val cut = at.roundToInt().toFloat()
        if (!splittable(block)) { message = "That block can't be split."; return }
        if (cut - block.start < CUT_EDGE || block.end - cut < CUT_EDGE) { message = "Cut at least a minute from either end."; return }
        val entry = block.entry
        val restStart = millisAt(cut)
        val restEnd = millisAt(block.end)
        val made = if (entry.completed) manager.logBlock(entry.subjectId, entry.title, restStart, restEnd)
        else runCatching {
            // The second half is the same plan continued, so it keeps the notes and topics too.
            val (description, topics) = focalPlanDetails(entry)
            manager.savePlan(FocalStudyEntry(id = UUID.randomUUID().toString(), notebookId = null, title = "",
                subjectId = entry.subjectId, kind = "study", startedAt = now, endedAt = now, activeMillis = 0L, completed = false, planned = true),
                entry.title, entry.subjectId, description, topics, listOf(FocalStudyInterval(restStart, restEnd)))
        }.getOrDefault(false)
        if (!made) { message = "Could not split that block."; return }
        // Keep the block's own start to the millisecond rather than a round trip through float minutes.
        val firstStart = focalSessionBlocks(entry).getOrNull(block.index)?.startAt ?: millisAt(block.start)
        message = if (manager.editBlocks(entry.id, listOf(FocalStudyInterval(firstStart, restStart)), entry.completed)) "Split at ${clock(cut)}"
        else "The second part was added, but the first could not be shortened."
        selected = null
    }

    val geometry by rememberUpdatedState(window to (span to blocks))
    fun minuteAt(x: Float, magnetic: Boolean = true): Float {
        val (w, rest) = geometry
        val minute = snap(w.start + (x / trackWidth.floatValue).coerceIn(0f, 1f) * rest.first)
        if (!magnetic) return minute.coerceIn(0f, DAY - 1f)
        // Pulls a minute onto a nearby block edge or "now" (within ~8dp) so blocks can sit flush.
        val reach = with(density) { 8.dp.toPx() } / trackWidth.floatValue * rest.first
        val edges = rest.second.flatMap { listOf(it.start, it.end) } + if (isToday) listOf(nowMin) else emptyList()
        return (edges.filter { abs(it - minute) <= reach }.minByOrNull { abs(it - minute) }?.roundToInt()?.toFloat() ?: minute).coerceIn(0f, DAY - 1f)
    }
    val trackTap by rememberUpdatedState { anchor: Float ->
        // A tap just after you finished studying means "I just did this": end it now.
        if (isToday && anchor <= nowMin && anchor + 30f > nowMin) quick(30)
        // A tap makes a block sized for the zoom: ten minutes zoomed right in, half an hour otherwise.
        else open(Span(anchor, anchor + if (span <= 60f) 10f else if (span <= 120f) 15f else 30f))
    }
    val trackRange by rememberUpdatedState { range: Span -> open(range) }
    val pickMinute by rememberUpdatedState { x: Float -> minuteAt(x) }
    val razorOn by rememberUpdatedState(razor)
    // A tool that turns taps on blocks into commands, so the track neither drafts nor drags.
    val toolOn by rememberUpdatedState(razor || picking)

    val rowHeight = if (compact) COMPACT_ROW else ROW
    val blockHeight = if (compact) COMPACT_BLOCK else BLOCK
    val trackPad = (rowHeight - blockHeight) / 2 + if (compact) 2.dp else 4.dp
    val context = LocalContext.current
    fun pickDate() {
        android.app.DatePickerDialog(context, { _, year, month, day -> epochDay = LocalDate.of(year, month + 1, day).toEpochDay() },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    val dayTitle: @Composable () -> Unit = {
        Column(Modifier.clip(FolioShapes.small).clickable(onClickLabel = "Choose a day") { pickDate() }
            .padding(horizontal = FolioSpacing.dp4, vertical = FolioSpacing.dp2)) {
            if (!compact) Text("Study timeline", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            val relative = when (date) { today -> "Today · "; today.minusDays(1) -> "Yesterday · "; today.plusDays(1) -> "Tomorrow · "; else -> "" }
            Text(relative + date.format(DateTimeFormatter.ofPattern(if (compact) "EEE d MMM" else "EEEE d MMMM", Locale.getDefault())),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val dayNav: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ epochDay-- }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
            IconButton({ epochDay++ }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day") }
            if (!isToday) TextButton({ epochDay = today.toEpochDay() }) { Text("Today") }
        }
    }
    val stats: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            if (date <= today) Surface(shape = FolioShapes.small, color = scheme.primaryContainer) {
                Text("${duration(studied)} studied", Modifier.padding(horizontal = FolioSpacing.dp10, vertical = FolioSpacing.dp6),
                    style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
            }
            if (planned > 0f) Text("${duration(planned)} planned", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            if (notDone > 0f) Text("${duration(notDone)} missed", style = MaterialTheme.typography.labelLarge, color = scheme.error)
        }
    }
    val zoom: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconToggleButton(picking, { pick(it) }) { Icon(Icons.Rounded.SelectAll, "Select blocks to merge") }
            FilledTonalIconToggleButton(razor, {
                razor = it; draft = null; selected = null; cut = null; picking = false; picks = emptySet()
                message = if (it) "Razor: tap a block to place the cut, drag the tag to fine-tune, tick to split." else null
            }) { Icon(Icons.Rounded.ContentCut, "Razor tool: split a study block") }
            OutlinedIconButton({ zoomBy(2f) }, enabled = span < DAY) { Icon(Icons.Rounded.Remove, "Zoom out") }
            OutlinedIconButton({ zoomBy(.5f) }, enabled = span > MIN_SPAN) { Icon(Icons.Rounded.Add, "Zoom in") }
            TextButton({ view = null }, enabled = view != null) { Text("Fit") }
            TextButton({ view = Span(0f, DAY) }, enabled = span < DAY) { Text("24h") }
            if (isToday) TextButton({ view = clampWindow(Span(nowMin - span / 2f, nowMin + span / 2f)) }) { Text("Now") }
        }
    }
    val quickLog: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text(if (compact) "Just did:" else "Just studied?", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (lastEnd > 0f && nowMin - lastEnd in 5f..360f) AssistChip({ open(Span(lastEnd, kotlin.math.floor(nowMin))) }, { Text("Since ${clock(lastEnd)}") })
            QUICK_LOGS.forEach { minutes -> AssistChip({ quick(minutes) }, { Text(duration(minutes.toFloat())) }) }
        }
    }

    Surface(shape = FolioShapes.extraLarge, color = cardColor) {
        Column(Modifier.fillMaxWidth().padding(if (compact) FolioSpacing.dp12 else FolioSpacing.dp16),
            verticalArrangement = Arrangement.spacedBy(if (compact) FolioSpacing.dp8 else FolioSpacing.dp12)) {
            if (compact) {
                // One band: the day, its totals, quick logs and zoom wrap only when the screen runs out.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), itemVerticalAlignment = Alignment.CenterVertically) {
                    dayTitle(); dayNav(); stats()
                    if (isToday) quickLog()
                    Spacer(Modifier.weight(1f))
                    zoom()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { dayTitle() }; dayNav() }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
                    itemVerticalAlignment = Alignment.CenterVertically) { stats(); Spacer(Modifier.weight(1f)); zoom() }
                if (isToday) FlowRow { quickLog() }
            }

            // ── The track: the drag target. ──
            val ticks = run {
                val widthDp = with(density) { trackWidth.floatValue.toDp().value }
                val step = TICK_STEPS.firstOrNull { it / span * widthDp >= 64f } ?: 360f
                generateSequence(kotlin.math.ceil(window.start / step) * step) { it + step }.takeWhile { it <= window.end }.toList()
            }
            fun x(minute: Float) = (minute.coerceIn(window.start, window.end) - window.start) / span * trackWidth.floatValue
            fun widthPx(start: Float, end: Float) = maxOf(with(density) { 4.dp.toPx() }, x(end) - x(start))
            val selection = drag ?: draft
            val draftDrag = { grip: Grip, dx: Float ->
                (draftOrigin ?: draft?.also { draftOrigin = it })?.let { origin ->
                    val delta = dx / trackWidth.floatValue * span
                    draft = when (grip) {
                        Grip.START -> Span(minOf(snap(origin.start + delta).coerceAtLeast(0f), origin.end - SNAP), origin.end)
                        Grip.END -> Span(origin.start, maxOf(snap(origin.end + delta).coerceAtMost(DAY - 1f), origin.start + SNAP))
                        Grip.MOVE -> {
                            val length = origin.end - origin.start
                            val start = snap(origin.start + delta).coerceIn(0f, DAY - length)
                            Span(start, start + length)
                        }
                    }
                }
                Unit
            }
            val trackHeight = rowHeight * rowCount + trackPad * 2 - (rowHeight - blockHeight)
            Box(Modifier.fillMaxWidth().height(trackHeight).clip(FolioShapes.large)
                .background(scheme.surfaceContainerHighest.copy(alpha = .5f))
                .border(1.dp, scheme.outlineVariant, FolioShapes.large)
                .onSizeChangedWidth { trackWidth.floatValue = it }
                .semantics { contentDescription = "Study timeline. Drag across it to log or plan study." }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val anchor = pickMinute(down.position.x)
                        var current = anchor
                        var moved = false
                        var pinching = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                pinching = true; drag = null
                                val (w, rest) = geometry
                                val center = w.start + event.calculateCentroid().x / trackWidth.floatValue * rest.first
                                val zoom = event.calculateZoom().takeIf { it > 0f } ?: 1f
                                val next = zoomWindow(w, 1f / zoom, center)
                                val shift = -event.calculatePan().x / trackWidth.floatValue * (next.end - next.start)
                                view = clampWindow(Span(next.start + shift, next.end + shift))
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (pinching) continue
                            val change = pressed.first()
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!moved) {
                                // A vertical swipe belongs to the scrolling page, not the timeline.
                                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) return@awaitEachGesture
                                if (abs(dx) > viewConfiguration.touchSlop) moved = true
                            }
                            if (moved && !toolOn) {
                                current = pickMinute(change.position.x)
                                drag = Span(minOf(anchor, current), maxOf(anchor, current))
                                change.consume()
                            }
                        }
                        drag = null
                        if (pinching || toolOn) return@awaitEachGesture
                        if (moved && current != anchor) trackRange(Span(minOf(anchor, current), maxOf(anchor, current)))
                        else trackTap(anchor)
                    }
                }
            ) {
                ticks.forEach { minute ->
                    Box(Modifier.offset { IntOffset(x(minute).roundToInt(), 0) }.width(1.dp).fillMaxHeight()
                        .background(scheme.outlineVariant.copy(alpha = if (minute % 60f == 0f) .7f else .35f)))
                }
                if (isToday && nowMin > window.start) Box(Modifier.width(with(density) { x(minOf(nowMin, window.end)).toDp() }).fillMaxHeight()
                    .background(scheme.surface.copy(alpha = .3f)))
                blocks.filter { it.end > window.start && it.start < window.end }.forEach { live ->
                    val range = adjust?.takeIf { it.first == live.key }?.second
                    val block = range?.let { live.copy(start = it.start, end = it.end) } ?: live
                    val room = with(density) { widthPx(block.start, block.end).toDp() }
                    val grab = movable(live) && !razor && !picking
                    val picked = live.entry.id in picks
                    val subject = subjects.firstOrNull { it.id == block.entry.subjectId }
                    val hue = FocalSubjects.color(block.entry.subjectId)?.let { Color(it) } ?: scheme.primary
                    val late = missed(block)
                    val accent = if (late) scheme.error else hue
                    val fill = when {
                        late -> scheme.errorContainer.copy(alpha = .5f)
                        block.tone == Tone.TODO -> lerp(scheme.surface, accent, .08f)
                        block.tone == Tone.LIVE -> lerp(scheme.surface, accent, .32f)
                        else -> lerp(scheme.surface, accent, .22f)
                    }
                    val edge = lerp(scheme.outlineVariant, accent, if (block.tone == Tone.LIVE) .85f else .5f)
                    val text = lerp(scheme.onSurface, accent, .15f)
                    val label = when { block.tone == Tone.LIVE -> "Studying now"; late -> "Missed"; block.tone == Tone.TODO -> "Planned"; else -> "Done" }
                    Box(Modifier
                        .offset { IntOffset(x(block.start).roundToInt(), (trackPad + rowHeight * (rows[live.key] ?: 0)).roundToPx()) }
                        .width(room).height(blockHeight)
                        .clip(FolioShapes.medium).background(fill)
                        .then(when {
                            picked -> Modifier.border(2.dp, scheme.primary, FolioShapes.medium)
                            selected == live.key -> Modifier.border(2.dp, scheme.onSurface, FolioShapes.medium)
                            block.tone == Tone.TODO -> Modifier.drawBehind {
                                drawRoundRect(edge, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                                    cornerRadius = CornerRadius(FolioShapes.mediumRadius.toPx()))
                            }
                            else -> Modifier.border(1.dp, edge, FolioShapes.medium)
                        })
                        .semantics { contentDescription = "$label: ${block.entry.title}${subject?.let { ", ${it.name}" } ?: ""}, ${clock(block.start)} to ${clock(block.end)}" }
                        .timelineDrag(live.key, grab,
                            onTap = { x ->
                                if (picking) {
                                    if (merging) Unit
                                    else if (mergeable(live)) picks = live.entry.id.let { if (it in picks) picks - it else picks + it }
                                    else message = "Only stopped study sessions can be merged."
                                }
                                else if (razorOn) {
                                    if (splittable(live)) cut = live.key to (maxOf(live.start, window.start) + x / trackWidth.floatValue * span).coerceIn(live.start + CUT_EDGE, live.end - CUT_EDGE)
                                    else message = "That block can't be split."
                                }
                                else { selected = if (selected == live.key) null else live.key; confirmRemove = false; draft = null }
                            },
                            onDrag = { grip, dx -> adjust = live.key to dragged(live, grip, dx) },
                            onEnd = { adjust?.let { (_, next) -> if (next != Span(live.start, live.end)) persist(live, next) }; adjust = null })
                        ,
                        contentAlignment = Alignment.CenterStart) {
                        if (room >= 36.dp) Column(Modifier.padding(horizontal = if (grab) 14.dp else FolioSpacing.dp8)) {
                            Text(if (room >= 150.dp) block.entry.title else subject?.name?.let(::initials) ?: block.entry.title.take(3),
                                style = MaterialTheme.typography.labelMedium, fontWeight = if (room >= 150.dp) FontWeight.Medium else null,
                                color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (room >= 150.dp) Text(if (block.tone == Tone.LIVE) label else "${clock(block.start)}–${clock(block.end)}",
                                style = MaterialTheme.typography.labelSmall, color = text.copy(alpha = .75f), maxLines = 1)
                        }
                        // Always-visible grips on a block you can resize.
                        if (grab && selected != live.key) listOf(Alignment.CenterStart, Alignment.CenterEnd).forEach { side ->
                            Box(Modifier.align(side).padding(horizontal = if (room >= 14.dp) 4.dp else 0.dp)
                                .width(3.dp).height(18.dp).background(text.copy(alpha = .5f), CircleShape))
                        }
                        if (picked && room >= 24.dp) Icon(Icons.Rounded.Check, null, Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp)
                            .background(scheme.primary, CircleShape).padding(1.dp), tint = scheme.onPrimary)
                    }
                }
                // Grab handles on the selected block: drag either one to resize it.
                blocks.firstOrNull { it.key == selected && it.end > window.start && it.start < window.end }?.takeIf { movable(it) && !razor && !picking }?.let { live ->
                    val range = adjust?.takeIf { it.first == live.key }?.second ?: Span(live.start, live.end)
                    ResizeHandles(live.key, range, ::x, trackPad + rowHeight * (rows[live.key] ?: 0), blockHeight, scheme.onSurface,
                        onDrag = { grip, dx -> adjust = live.key to dragged(live, grip, dx) },
                        onEnd = { adjust?.let { (_, next) -> if (next != Span(live.start, live.end)) persist(live, next) }; adjust = null })
                }
                selection?.let { range ->
                    val hue = FocalSubjects.color(subjectId)?.let { Color(it) } ?: scheme.primary
                    Box(Modifier.offset { IntOffset(x(range.start).roundToInt(), 4.dp.roundToPx()) }
                        .width(with(density) { widthPx(range.start, range.end).toDp() }).fillMaxHeight().padding(bottom = 8.dp)
                        .clip(FolioShapes.medium).background(hue.copy(alpha = .15f)).border(2.dp, hue, FolioShapes.medium)
                        .then(if (drag == null) Modifier.timelineDrag("draft", true, onTap = {}, onDrag = draftDrag, onEnd = { draftOrigin = null }) else Modifier))
                    if (drag == null) ResizeHandles("draft", range, ::x, 4.dp, trackHeight - 8.dp, hue, onDrag = draftDrag, onEnd = { draftOrigin = null })
                }
                // The pending cut: a line through the block and a small tag with its time. Drag the tag to fine-tune, tick it to split.
                cut?.let { (key, minute) ->
                    val target = blocks.firstOrNull { it.key == key }
                    if (target != null && minute in window.start..window.end) {
                        val top = trackPad + rowHeight * (rows[key] ?: 0)
                        Box(Modifier.offset { IntOffset(x(minute).roundToInt() - 1.dp.roundToPx(), top.roundToPx()) }
                            .width(2.dp).height(blockHeight).background(scheme.onSurface))
                        Box(Modifier.offset { IntOffset(x(minute).roundToInt(), top.roundToPx()) }.width(0.dp).height(blockHeight)) {
                            Row(Modifier.wrapContentSize(Alignment.Center, unbounded = true).height(24.dp)
                                .background(scheme.inverseSurface, CircleShape)
                                .pointerInput(key, span) {
                                    detectHorizontalDragGestures { change, dx ->
                                        change.consume()
                                        cut = cut?.let { (k, m) -> k to (m + dx / trackWidth.floatValue * span).coerceIn(target.start + CUT_EDGE, target.end - CUT_EDGE) }
                                    }
                                }
                                .clickable { split(target, minute) }
                                .padding(start = 8.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(clock(minute.roundToInt().toFloat()), style = MaterialTheme.typography.labelSmall, color = scheme.inverseOnSurface, maxLines = 1)
                                Icon(Icons.Rounded.Check, "Split here", Modifier.size(14.dp), tint = scheme.inverseOnSurface)
                            }
                        }
                    }
                }
                if (isToday && nowMin in window.start..window.end) Box(Modifier.offset { IntOffset(x(nowMin).roundToInt() - 1.dp.roundToPx(), 0) }
                    .width(2.dp).fillMaxHeight().background(scheme.error))
            }
            // Tick labels, and the live range while dragging.
            Box(Modifier.fillMaxWidth().height(18.dp)) {
                val bubble = adjust?.second ?: drag
                if (bubble != null) Text("${clock(bubble.start)}–${clock(bubble.end)} · ${duration(bubble.end - bubble.start)}",
                    Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelMedium)
                else ticks.forEach { minute ->
                    val at = x(minute)
                    Text(clock(minute), Modifier.offset { IntOffset((at - if (at < 24.dp.toPx()) 0f else 16.dp.toPx()).roundToInt(), 0) },
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            // Where this window sits in the day: tap or drag to pan.
            if (span < DAY) {
                val overviewWidth = remember { mutableFloatStateOf(1f) }
                Box(Modifier.fillMaxWidth().height(20.dp).clip(FolioShapes.small).background(scheme.surfaceContainerHighest)
                    .onSizeChangedWidth { overviewWidth.floatValue = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            fun pan(x: Float) {
                                val size = geometry.second.first
                                val center = x / overviewWidth.floatValue * DAY
                                view = clampWindow(Span(center - size / 2f, center + size / 2f))
                            }
                            val down = awaitFirstDown(); pan(down.position.x); down.consume()
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                pan(change.position.x); change.consume()
                            }
                        }
                    }) {
                    fun ox(minute: Float) = (minute / DAY * overviewWidth.floatValue).roundToInt()
                    blocks.forEach { block ->
                        Box(Modifier.offset { IntOffset(ox(block.start), 6.dp.roundToPx()) }
                            .width(with(density) { maxOf(2f, (block.end - block.start) / DAY * overviewWidth.floatValue).toDp() }).height(8.dp)
                            .background((FocalSubjects.color(block.entry.subjectId)?.let { Color(it) } ?: scheme.primary).copy(alpha = .7f), FolioShapes.small))
                    }
                    if (isToday) Box(Modifier.offset { IntOffset(ox(nowMin), 0) }.width(1.dp).fillMaxHeight().background(scheme.error))
                    Box(Modifier.offset { IntOffset(ox(window.start), 0) }.width(with(density) { (span / DAY * overviewWidth.floatValue).toDp() })
                        .fillMaxHeight().border(1.dp, scheme.primary.copy(alpha = .6f), FolioShapes.small))
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }

            // ── Select mode: the picked blocks and Merge. ──
            if (picking) {
                val chosen = state.visibleEntries.filter { it.id in picks }
                val refusal = if (chosen.size < picks.size) "A selected session changed. Select your sessions again."
                    else if (chosen.size >= 2) focalMergeRefusal(chosen) else null
                Surface(shape = FolioShapes.large, color = scheme.surfaceContainerHighest) {
                    Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4, top = FolioSpacing.dp8, bottom = FolioSpacing.dp8),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (picks.isEmpty()) "Tap sessions to select them" else "${picks.size} ${if (picks.size == 1) "session" else "sessions"} selected",
                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(refusal ?: "Keeps the earliest session's title and every break and note. Overlapping time counts once.",
                                style = MaterialTheme.typography.bodySmall, color = if (refusal != null) scheme.error else scheme.onSurfaceVariant)
                        }
                        if (picks.isNotEmpty()) TextButton({ picks = emptySet() }, enabled = !merging) { Text("Clear") }
                        Button({ merge(picks) }, enabled = !merging && picks.size >= 2 && refusal == null) {
                            Icon(Icons.Rounded.Merge, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8))
                            Text(if (merging) "Merging…" else "Merge")
                        }
                        IconButton({ pick(false) }, enabled = !merging) { Icon(Icons.Rounded.Close, "Stop selecting") }
                    }
                }
            }

            // ── The selected block. ──
            blocks.firstOrNull { it.key == selected }?.let { picked ->
                val entry = picked.entry
                val subject = subjects.firstOrNull { it.id == entry.subjectId }
                val timing = entry.active || entry.id == focus?.sessionId
                Surface(shape = FolioShapes.large, color = scheme.surfaceContainerHighest) {
                    Split(compact, Modifier.padding(FolioSpacing.dp12), { modifier ->
                        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(FocalSubjects.color(entry.subjectId)?.let { Color(it) } ?: scheme.primary, CircleShape))
                            Spacer(Modifier.width(FolioSpacing.dp8))
                            Column(Modifier.weight(1f)) {
                                val restriction = when {
                                    entry.active -> "Stop this timer before reshaping or merging it."
                                    entry.pendingBlocks != null && !entry.synced -> "Block changes are waiting for Focal. They remain on this device until acknowledged."
                                    !focalCanEditBlocks(entry) && entry.intervals.isNotEmpty() -> "This timer session must sync its recorded intervals before it can be reshaped."
                                    state.userId != null && !entry.synced -> "Waiting for Focal sync. Merge becomes available after this session is acknowledged."
                                    else -> null
                                }
                                if (restriction != null) Text(restriction, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(subject?.name, "${clock(picked.start)}–${clock(picked.end)}", duration(picked.end - picked.start),
                                    when { picked.tone == Tone.LIVE -> "Studying now"; missed(picked) -> "Missed"; picked.tone == Tone.TODO -> "Planned"
                                        entry.kind == "exam" -> "Exam"; else -> "Done" },
                                    if (picked.count > 1) "Block ${picked.index + 1} of ${picked.count}" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                val about = if (entry.planned) focalPlanDetails(entry).first else entry.notes
                                if (about.isNotBlank()) Text(about.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (!compact) IconButton({ selected = null }) { Icon(Icons.Rounded.Close, "Close details") }
                        }
                    }) { modifier ->
                        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, if (compact) Alignment.End else Alignment.Start),
                            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4), itemVerticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton({ zoomTo(picked) }) { Text("Zoom") }
                            if (splittable(picked)) OutlinedButton({ split(picked, splitPoint(picked)) }) { Text("Split") }
                            if (mergeable(picked)) OutlinedButton({ pick(true, entry.id) }) { Text("Select to merge") }
                            if (entry.planned) OutlinedButton({ onEditPlan(entry.id) }) { Text("Edit") }
                            if (!timing && entry.kind == "study") {
                                val notFinished = !entry.completed && (date > today || (isToday && picked.end > nowMin))
                                OutlinedButton({
                                    val ok = manager.editBlocks(entry.id, spansOf(entry, focus), !entry.completed)
                                    message = if (!ok) "Wait for this session to sync before changing it." else if (entry.completed) "Marked not done" else "Marked done"
                                }, enabled = !notFinished) { Text(if (entry.completed) "Not done" else "Mark done") }
                            }
                            // "Now" only exists on today; on another day it would just push the block to that day's end.
                            if (isToday && missed(picked) && movable(picked) && picked.end - picked.start <= DAY - SNAP - nowMin) OutlinedButton({
                                val length = picked.end - picked.start
                                val start = minOf(DAY - SNAP - length, kotlin.math.ceil(nowMin / SNAP) * SNAP)
                                persist(picked, Span(start, start + length))
                            }) { Text("Move to now") }
                            if (entry.subjectId != null && entry.kind == "study") OutlinedButton({
                                val next = date.plusDays(1)
                                val nextStart = next.atStartOfDay(zone).toInstant().toEpochMilli()
                                val copy = FocalStudyEntry(notebookId = null, title = "", subjectId = entry.subjectId, kind = "study",
                                    startedAt = now, endedAt = now, activeMillis = 0L, completed = false, planned = true)
                                val (description, topics) = if (entry.planned) focalPlanDetails(entry) else entry.notes.trim() to emptyList()
                                val ok = runCatching { manager.savePlan(copy, entry.title, entry.subjectId, description, topics,
                                    listOf(FocalStudyInterval(millisAt(picked.start, nextStart), millisAt(picked.end, nextStart)))) }.getOrDefault(false)
                                message = if (!ok) "Could not plan that again."
                                    else "Planned again for ${clock(picked.start)} ${if (next == today.plusDays(1)) "tomorrow" else next.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}"
                            }) { Text(if (isToday) "Repeat tomorrow" else "Repeat next day") }
                            if (!timing && entry.kind == "study") Button({
                                if (entry.planned) onStartPlanned(entry.id) else onStartFocus(entry.subjectId)
                            }, enabled = canStart) { Text(if (entry.planned) "Start" else "Study again") }
                            if (!timing) {
                                if (confirmRemove) Button({ manager.deleteSession(entry.id); selected = null; confirmRemove = false; message = "Study removed" },
                                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)) { Text("Remove for good?") }
                                else OutlinedButton({ confirmRemove = true }) { Text("Remove") }
                            }
                            if (compact) IconButton({ selected = null }) { Icon(Icons.Rounded.Close, "Close details") }
                        }
                    }
                }
            }

            // ── The draft: log or schedule the selected range. ──
            draft?.let { range ->
                val done = range.end <= nowMin && (doneOverride ?: true) && date <= today
                val finished = date < today || (isToday && range.end <= nowMin)
                Surface(shape = FolioShapes.large, color = scheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${if (done) "Log" else "Schedule"} ${clock(range.start)}–${clock(range.end)} · ${duration(range.end - range.start)}",
                                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton({ draft = null }) { Icon(Icons.Rounded.Close, "Cancel") }
                        }
                        val subjectName = subjects.firstOrNull { it.id == subjectId }?.name
                        Split(compact, Modifier, { modifier -> Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                                FilterChip(range.end - range.start == minutes.toFloat(),
                                    { draft = Span(range.start, minOf(DAY - 1f, range.start + minutes)) }, { Text(duration(minutes.toFloat())) })
                            }
                        }
                        FocalSubjectPicker(subjects, subjectId) { subjectId = it }
                        } }) { modifier -> Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                        if (finished) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(done, { doneOverride = it })
                            Text("Done", style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedTextField(title, { title = it.take(512) }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Title") }, placeholder = { Text("${subjectName ?: "Study"} study") })
                        if (subjectName == null) Text("Choose a subject to ${if (done) "log" else "schedule"} this block.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Button({
                            val name = subjectName ?: return@Button
                            val label = title.trim().ifBlank { "$name study" }
                            val ok = if (done) manager.logBlock(subjectId, label, millisAt(range.start), millisAt(range.end))
                            else runCatching { manager.savePlan(FocalStudyEntry(id = UUID.randomUUID().toString(), notebookId = null, title = "",
                                subjectId = subjectId, kind = "study", startedAt = now, endedAt = now, activeMillis = 0L, completed = false, planned = true),
                                label, subjectId, "", emptyList(), listOf(FocalStudyInterval(millisAt(range.start), millisAt(range.end)))) }.getOrDefault(false)
                            message = if (ok) "${if (done) "Logged" else "Scheduled"} ${duration(range.end - range.start)} of $name" else "Could not save. Try again."
                            if (ok) { draft = null; title = "" }
                        }, Modifier.fillMaxWidth(), enabled = range.end > range.start && subjectName != null) {
                            Text("${if (done) "Log" else "Schedule"} ${duration(range.end - range.start)}")
                        }
                        } }
                    }
                }
            }
        }
    }
}

/**
 * Grab handles on the two edges of a block or the draft, centred on each edge with a 28dp finger
 * target that reaches past the block, so even a narrow block can be resized.
 */
@Composable
private fun ResizeHandles(key: Any, range: Span, x: (Float) -> Float, top: Dp, height: Dp, color: Color,
                          onDrag: (Grip, Float) -> Unit, onEnd: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    listOf(Grip.START to range.start, Grip.END to range.end).forEach { (grip, minute) ->
        Box(Modifier.offset { IntOffset(x(minute).roundToInt() - 14.dp.roundToPx(), top.roundToPx()) }.width(28.dp).height(height)
            .semantics { contentDescription = if (grip == Grip.START) "Drag to change the start" else "Drag to change the end" }
            .timelineDrag(key to grip, true, onTap = {}, onDrag = onDrag, onEnd = onEnd, fixed = grip),
            contentAlignment = Alignment.Center) {
            Box(Modifier.width(10.dp).height(minOf(height - 8.dp, 30.dp)).background(scheme.surface, CircleShape).border(2.dp, color, CircleShape),
                contentAlignment = Alignment.Center) {
                Box(Modifier.width(2.dp).height(10.dp).background(color.copy(alpha = .6f), CircleShape))
            }
        }
    }
}

private fun Modifier.onSizeChangedWidth(onWidth: (Float) -> Unit) = onSizeChanged { onWidth(it.width.toFloat().coerceAtLeast(1f)) }

/** Side by side on a wide timeline, stacked otherwise. */
@Composable
private fun Split(wide: Boolean, modifier: Modifier, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (wide) Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), verticalAlignment = Alignment.Top) {
        first(Modifier.weight(1f)); second(Modifier.weight(1f))
    } else Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) { first(Modifier); second(Modifier) }
}
