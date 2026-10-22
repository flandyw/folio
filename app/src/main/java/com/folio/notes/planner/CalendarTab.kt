@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.planner

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.input.guardUiTouches
import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.MistakeScheduler
import com.folio.notes.mistakes.isoTime
import com.folio.notes.progress.ChoiceField
import com.folio.notes.progress.LoggedExam
import com.folio.notes.progress.examDate
import com.folio.notes.progress.objects
import com.folio.notes.progress.text
import com.folio.notes.study.StudyTimeline
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val AMBER = Color(0xFFF9A825)

/** One solid mark per kind, as on Focal's calendar: at cell size a pale dot is a smudge, not a legend. */
@Composable private fun kindColor(kind: Calendar.Kind): Color = when (kind) {
    Calendar.Kind.TASK -> MaterialTheme.colorScheme.tertiary
    Calendar.Kind.EVENT -> MaterialTheme.colorScheme.secondary
    Calendar.Kind.SAC -> MaterialTheme.colorScheme.error
    Calendar.Kind.SESSION -> MaterialTheme.colorScheme.primary
    Calendar.Kind.LOGGED -> MaterialTheme.colorScheme.outline
    Calendar.Kind.REVIEW -> AMBER
}

private fun itemDone(item: Calendar.Item) = item.done

internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

private fun dayTitle(date: LocalDate, today: LocalDate): String {
    val label = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
    return when (date) { today -> "Today · $label"; today.plusDays(1) -> "Tomorrow · $label"; today.minusDays(1) -> "Yesterday · $label"; else -> label }
}

/** Focal's calendar: a month of everything the synced rows hold, a day pane, and the shared study timeline under it. */
@Composable internal fun CalendarTab(learning: JSONObject, sacs: List<JSONObject>, events: List<JSONObject>, timetable: JSONObject?, subjects: List<String>,
    exams: List<LoggedExam>, mistakes: List<ExamTrackMistake>, enabled: Boolean,
    saveLearning: suspend (JSONObject) -> Boolean, saveEvent: suspend (String, JSONObject?) -> Boolean, saveTimetable: suspend (JSONObject) -> Boolean,
    onMistakes: () -> Unit, onSacs: () -> Unit, onStudy: () -> Unit) {
    val study = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val studyState by study.state.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var dayText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var timelineDay by remember { mutableStateOf<Long?>(null) }
    val month = YearMonth.parse(monthText); val day = LocalDate.parse(dayText)
    val today = LocalDate.now()
    val tasks = remember(learning) { learning.optJSONArray("tasks")?.objects().orEmpty() }
    var editingTask by remember { mutableStateOf<JSONObject?>(null) }
    var addingTask by rememberSaveable { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<JSONObject?>(null) }
    var addingEvent by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val names = remember(studyState.subjects) { studyState.subjects.associate { it.id to it.name } }
    val sessions = remember(studyState.entries, studyState.userId, now, names) {
        studyState.visibleEntries.map { e ->
            val blocks = (e.pendingBlocks ?: e.intervals).map { it.startAt to (it.endAt ?: now) }
                .ifEmpty { listOf(e.startedAt to if (e.active) now else maxOf(e.endedAt, e.startedAt)) }
            Calendar.Session(e.id, e.title, e.subjectId?.let { names[it] ?: it }.orEmpty(), blocks, e.completed && !e.planned, e.active)
        }
    }
    val logged = remember(exams) { exams.mapNotNull { x -> x.date?.let { date ->
        val timing = runCatching { x.json.optJSONObject("timing") }.getOrNull()
        Calendar.Logged(x.id, x.title, listOf(x.subject, x.paper, "${x.rawScore.toString().removeSuffix(".0")}/${x.rawMax.toString().removeSuffix(".0")}").filter { it.isNotBlank() }.joinToString(" · "),
            date, timing?.let { it.optDouble("plannedReadingMinutes", 0.0) + it.optDouble("plannedWritingMinutes", 0.0) } ?: 0.0)
    } } }
    // Same rule as Focal: a card lands on its computed due day; suspended and mastered cards never queue.
    val due = remember(mistakes) { mistakes.filter { !it.suspended }.mapNotNull { m ->
        MistakeScheduler.getMistakeSchedule(m).takeIf { !it.resolved }?.dueAt?.let(::examDate) }.groupingBy { it }.eachCount() }
    val source = remember(tasks, sacs, events, sessions, logged, due, names) { Calendar.Sources(tasks, sacs, events, sessions, logged, due, names) }
    val weeks = remember(month, source) { Calendar.month(month, source) }
    val plan = remember(day, source) { Calendar.dayPlan(day, source) }
    val overdue = remember(tasks, day) { if (day >= today) Calendar.overdue(tasks, today) else emptyList() }
    val school = remember(day, timetable) { timetable?.takeIf { it.optBoolean("enabled", true) }?.let { Calendar.periodsOn(day, it) } }
    val inMonth = weeks.flatten().filter { it.inMonth }
    val summary = inMonth.sumOf { it.minutes }.let { m -> if (m > 0) "${formatMinutes(m)} of study across ${inMonth.count { it.minutes > 0 }} day${if (inMonth.count { it.minutes > 0 } == 1) "" else "s"}" else "No study planned this month" } +
        inMonth.sumOf { d -> d.plan.items.count { it.fixed } }.let { if (it > 0) " · $it assessment${if (it == 1) "" else "s"}" else "" }

    fun selectDay(d: LocalDate) { dayText = d.toString(); monthText = YearMonth.from(d).toString(); timelineDay = d.toEpochDay() }
    fun setTask(id: String, edit: (JSONObject) -> Unit) { scope.launch { saveLearning(Planner.edit(learning, "tasks", id) { edit(it); it.put("updatedAt", isoTime()) }) } }
    val actions = RowActions(
        onTask = { id, status -> setTask(id) { it.put("status", status) } },
        onEditTask = { id -> editingTask = tasks.find { it.text("id") == id } },
        onArchive = { id -> setTask(id) { it.put("archivedAt", isoTime()) } },
        onEvent = { id, finished -> events.find { it.text("id") == id }?.let { event -> scope.launch {
            val stamp = isoTime(); saveEvent(id, JSONObject(event.toString()).put("isFinished", finished).put("updated_at", stamp).apply { if (finished) put("finishedAt", stamp) else remove("finishedAt") }) } } },
        onEditEvent = { id -> editingEvent = events.find { it.text("id") == id } },
        onMistakes = onMistakes, onSacs = onSacs, onStudy = onStudy, enabled = enabled)

    val header: @Composable () -> Unit = {
        CalendarHeader(month, summary, enabled, { monthText = month.plusMonths(it.toLong()).toString() }, { selectDay(today) },
            { addingEvent = true }, { addingTask = true }, { importing = true })
    }
    val grid: @Composable (Boolean) -> Unit = { wide -> MonthGrid(weeks, day, today, wide, ::selectDay) }
    val pane: @Composable () -> Unit = {
        DayPane(day, today, plan, school, timetable != null, overdue, actions, { selectDay(day.plusDays(it.toLong())) }, { importing = true },
            { id -> setTask(id) { it.put("plannedFor", day.toString()) } })
    }
    val timeline: @Composable () -> Unit = {
        StudyTimeline(studyState, now, canStart = studyState.focus == null, cardColor = MaterialTheme.colorScheme.surfaceContainerLow,
            onStartPlanned = { if (study.startPlannedFocus(it, null)) onStudy() }, onStartFocus = { if (study.startFocus(null, it)) onStudy() },
            onEditPlan = { onStudy() }, requestedDay = timelineDay, onDayShown = { timelineDay = null })
    }
    BoxWithConstraints(Modifier.fillMaxSize().guardUiTouches()) {
        val wide = maxWidth >= 840.dp
        val gap = Arrangement.spacedBy(FolioSpacing.dp16)
        val pad = Modifier.padding(start = FolioDestinationInset, end = FolioDestinationInset, top = FolioSpacing.dp12, bottom = 120.dp)
        if (wide) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(pad), verticalArrangement = gap) {
            header()
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                Column(Modifier.weight(1.7f)) { grid(true) }
                Column(Modifier.weight(1f)) { pane() }
            }
            timeline()
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(pad), verticalArrangement = gap) {
            header(); grid(false); pane(); timeline()
        }
    }

    if (addingTask || editingTask != null) TaskDialog(editingTask, day, subjects, { addingTask = false; editingTask = null }, onDelete = editingTask?.let { t ->
        { scope.launch { saveLearning(Planner.edit(learning, "tasks", t.optString("id")) { it.put("archivedAt", isoTime()) }) } } }) { title, minutes, subject, date, detail, status ->
        val source = editingTask
        val next = if (source == null) Planner.append(learning, "tasks", listOf(JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "custom").put("title", title)
            .apply { if (subject.isNotBlank()) put("subject", subject) }.put("detail", detail).put("durationMinutes", minutes).put("plannedFor", date).put("status", "planned")
            .put("createdAt", isoTime()).put("updatedAt", isoTime())))
        else Planner.edit(learning, "tasks", source.optString("id")) { it.put("title", title).put("detail", detail).put("durationMinutes", minutes).put("plannedFor", date).put("status", status)
            .put("updatedAt", isoTime()).apply { if (subject.isBlank()) remove("subject") else put("subject", subject) } }
        if (saveLearning(next)) { selectDay(LocalDate.parse(date)); null } else "Could not save the task. Try again."
    }
    if (addingEvent || editingEvent != null) EventDialog(editingEvent, day, { addingEvent = false; editingEvent = null },
        onDelete = editingEvent?.let { e -> { scope.launch { saveEvent(e.optString("id"), null) } } }) { title, type, date, start, end, endDate, location ->
        val source = editingEvent
        val event = if (source == null) Calendar.newEvent(title, type, LocalDate.parse(date), start, end, endDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse), location)
            .put("isFinished", false)
        else JSONObject(source.toString()).put("title", title.trim()).put("eventType", type).put("startTime", Calendar.toIso(LocalDate.parse(date), start))
            .put("endTime", Calendar.toIso(endDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: LocalDate.parse(date), end)).put("updated_at", isoTime())
            .apply { if (location.isBlank()) remove("location") else put("location", location.trim()) }
        if (saveEvent(event.getString("id"), event)) { selectDay(LocalDate.parse(date)); null } else "Could not save the event. Try again."
    }
    if (importing) TimetableDialog(timetable, { importing = false }) { parsed -> if (saveTimetable(parsed)) null else "Could not save the timetable. Try again." }
}

private class RowActions(val onTask: (String, String) -> Unit, val onEditTask: (String) -> Unit, val onArchive: (String) -> Unit,
    val onEvent: (String, Boolean) -> Unit, val onEditEvent: (String) -> Unit, val onMistakes: () -> Unit, val onSacs: () -> Unit,
    val onStudy: () -> Unit, val enabled: Boolean)

/** The month is the title: where you are, how to move and what you can add, so the grid starts above the fold. */
@Composable private fun CalendarHeader(month: YearMonth, summary: String, enabled: Boolean, onShift: (Int) -> Unit, onToday: () -> Unit,
    onAddEvent: () -> Unit, onAddTask: () -> Unit, onTimetable: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).widthIn(min = 240.dp)) {
                Text("CALENDAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}", style = MaterialTheme.typography.headlineMedium)
                    IconButton({ onShift(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                    IconButton({ onShift(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
                }
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onToday) { Icon(Icons.Rounded.Replay, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Today") }
            OutlinedButton(onTimetable, enabled = enabled) { Icon(Icons.Rounded.Schedule, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Timetable") }
            OutlinedButton(onAddTask, enabled = enabled) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Task") }
            Button(onAddEvent, enabled = enabled) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add event") }
        }
        HorizontalDivider()
    }
}

/** Seven days plus the week's total where the eye lands at the end of the row; a narrow pane drops the total and shows dots. */
@Composable private fun MonthGrid(weeks: List<List<Calendar.Day>>, selected: LocalDate, today: LocalDate, wide: Boolean, onSelect: (LocalDate) -> Unit) {
    val lines = MaterialTheme.colorScheme.outlineVariant
    val peak = maxOf(1, weeks.maxOfOrNull { w -> w.sumOf { it.minutes } } ?: 1)
    Surface(shape = FolioShapes.large, border = BorderStroke(1.dp, lines), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                    Text(it.uppercase(), Modifier.weight(1f).padding(FolioSpacing.dp8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (wide) Text("WEEK", Modifier.width(72.dp).padding(FolioSpacing.dp8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
            weeks.forEach { week ->
                HorizontalDivider(color = lines)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    week.forEachIndexed { i, d ->
                        if (i > 0) VerticalDivider(color = lines)
                        DayCell(d, d.date == selected, d.date == today, i >= 5, wide, Modifier.weight(1f).fillMaxHeight()) { onSelect(d.date) }
                    }
                    if (wide) {
                        VerticalDivider(color = lines)
                        val minutes = week.sumOf { it.minutes }
                        Column(Modifier.width(72.dp).padding(FolioSpacing.dp8), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                            Text(if (minutes > 0) formatMinutes(minutes) else "–", style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (minutes > 0) FontWeight.Medium else null, color = if (minutes > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (minutes > 0) Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                Box(Modifier.fillMaxWidth(minutes.toFloat() / peak).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .6f)))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = lines)
            FlowRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp6),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Calendar.Kind.entries.forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                        Dot(kindColor(kind)); Text(kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 6.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

private const val CELL_LINES = 3

/** Open work leads the cell, assessments first; what is finished folds into one count. */
@Composable private fun DayCell(day: Calendar.Day, active: Boolean, isToday: Boolean, weekend: Boolean, wide: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val open = day.plan.items.filterNot(::itemDone).sortedBy { if (it.fixed) 0 else if (it.kind == Calendar.Kind.EVENT) 1 else 2 }
    val done = day.plan.items.size - open.size
    val background = when { active -> scheme.primary.copy(alpha = .08f); !day.inMonth -> scheme.surfaceContainer; weekend -> scheme.surfaceContainerLowest; else -> Color.Transparent }
    Column(modifier.background(background).then(if (active) Modifier.border(BorderStroke(2.dp, scheme.primary)) else Modifier)
        .clickable(onClick = onClick).heightIn(min = if (wide) 112.dp else 64.dp).padding(FolioSpacing.dp4)
        .semantics { contentDescription = "${day.date}, ${open.size} open, $done done, ${formatMinutes(day.minutes)} of study" },
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                Text("${day.date.dayOfMonth}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                    color = if (isToday) scheme.onPrimary else if (day.inMonth) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = .6f))
            }
            Spacer(Modifier.weight(1f))
            if (wide && day.minutes > 0) Text(formatMinutes(day.minutes), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        if (!wide) {
            if (open.isNotEmpty()) Row(Modifier.padding(horizontal = FolioSpacing.dp4), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                open.take(4).forEach { Dot(kindColor(it.kind)) }
            }
        } else {
            open.take(CELL_LINES).forEach { item ->
                Row(Modifier.fillMaxWidth().clip(FolioShapes.bar).background(when { item.fixed -> scheme.error.copy(alpha = .1f); item.kind == Calendar.Kind.EVENT -> scheme.secondary.copy(alpha = .1f); else -> Color.Transparent })
                    .padding(horizontal = FolioSpacing.dp4, vertical = 1.dp).alpha(if (day.inMonth) 1f else .7f),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Dot(kindColor(item.kind))
                    Text(item.title, style = MaterialTheme.typography.labelSmall, fontWeight = if (item.fixed) FontWeight.Medium else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val more = open.size - minOf(open.size, CELL_LINES)
            if (more > 0 || done > 0) Text(listOfNotNull(if (more > 0) "+$more more" else null, if (done > 0) "✓ $done done" else null).joinToString("  "),
                Modifier.padding(horizontal = FolioSpacing.dp4), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable private fun DayPane(day: LocalDate, today: LocalDate, plan: Calendar.Plan, school: Pair<Int, List<Calendar.Period>>?, hasTimetable: Boolean,
    overdue: List<JSONObject>, actions: RowActions, onShift: (Int) -> Unit, onTimetable: () -> Unit, onMoveHere: (String) -> Unit) {
    val open = plan.items.filterNot(::itemDone); val banked = plan.items.filter(::itemDone)
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(dayTitle(day, today), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton({ onShift(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
                IconButton({ onShift(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day") }
            }
            Text(listOfNotNull(if (plan.totalCount > 0) "${plan.completedCount} of ${plan.totalCount} done" else "Nothing planned",
                plan.plannedMinutes.takeIf { it > 0 }?.let { "${formatMinutes(it)} to go" }, plan.completedMinutes.takeIf { it > 0 }?.let { "${formatMinutes(it)} banked" },
                school?.let { "Day ${it.first}" }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (plan.totalCount > 0) LinearProgressIndicator({ plan.completedCount.toFloat() / plan.totalCount }, Modifier.fillMaxWidth())
            if (plan.dueMistakes > 0) TextButton(actions.onMistakes, contentPadding = PaddingValues(0.dp)) {
                Dot(AMBER); Spacer(Modifier.width(FolioSpacing.dp8)); Text("${plan.dueMistakes} mistake${if (plan.dueMistakes == 1) "" else "s"} due for review")
            }
            if (!school?.second.isNullOrEmpty()) DaySection("Classes", school!!.second.size) {
                school.second.forEach { p ->
                    ItemLine(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), p.subject.ifEmpty { p.name },
                        "${p.name} · ${p.start}–${p.end}" + (if (p.location.isNotEmpty()) " · ${p.location}" else ""))
                }
            } else if (!hasTimetable) TextButton(onTimetable, contentPadding = PaddingValues(0.dp)) { Text("Set up your class timetable to see classes here") }
            if (open.isNotEmpty()) DaySection("To do", open.size) { open.forEach { DayItemRow(it, actions) } }
            else Column(Modifier.fillMaxWidth().clip(FolioShapes.large).background(MaterialTheme.colorScheme.surfaceContainer).padding(FolioSpacing.dp24),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Icon(Icons.Rounded.CalendarMonth, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (banked.isNotEmpty()) "All done" else "Nothing planned", style = MaterialTheme.typography.titleSmall)
                Text(if (banked.isNotEmpty()) "Everything on this day is finished." else "Add an event or task, or plan study in the timeline below.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (overdue.isNotEmpty()) DaySection("Carried over", overdue.size) {
                overdue.forEach { task ->
                    ItemLine(kindColor(Calendar.Kind.TASK), task.text("title"), "${task.text("plannedFor")} · ${task.optInt("durationMinutes")} min") {
                        OutlinedButton({ onMoveHere(task.text("id")) }, enabled = actions.enabled) { Text("Move here") }
                    }
                }
            }
            if (banked.isNotEmpty()) DaySection("Done", banked.size, muted = true) { banked.forEach { DayItemRow(it, actions) } }
        }
    }
}

@Composable private fun DaySection(title: String, count: Int, muted: Boolean = false, rows: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.alpha(if (muted) .85f else 1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("$count ${if (count == 1) "item" else "items"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // One list with hairlines, not a card per item.
        Surface(shape = FolioShapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
            Column { rows() }
        }
    }
}

@Composable private fun ItemLine(dot: Color, title: String, meta: String, faded: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp6),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Dot(dot)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable private fun Badge(label: String, filled: Boolean = false) = Surface(shape = FolioShapes.small,
    color = if (filled) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
    border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Text(label, Modifier.padding(horizontal = FolioSpacing.dp6, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
}

/** One row: the two things you do while scanning (open, finish) stay on it; the rare ones hide behind "more". */
@Composable private fun DayItemRow(item: Calendar.Item, a: RowActions) {
    val meta = listOfNotNull(item.detail.ifEmpty { null }, item.minutes.takeIf { it >= 1 }?.let { "${Math.round(it)} min" }, item.time.ifEmpty { null }).joinToString(" · ")
    val open = when (item.kind) {
        Calendar.Kind.TASK -> { { a.onEditTask(item.id) } }
        Calendar.Kind.EVENT -> { { a.onEditEvent(item.id) } }
        Calendar.Kind.SAC -> a.onSacs
        Calendar.Kind.SESSION -> a.onStudy
        Calendar.Kind.REVIEW -> a.onMistakes
        Calendar.Kind.LOGGED -> null
    }
    var menu by remember { mutableStateOf(false) }
    ItemLine(kindColor(item.kind), item.title, meta, faded = item.done, onClick = open) {
        when (item.kind) {
            Calendar.Kind.TASK -> {
                if (item.skipped) Badge("Skipped")
                if (item.done || item.skipped) IconButton({ a.onTask(item.id, "planned") }, enabled = a.enabled) { Icon(Icons.Rounded.Replay, "Reopen ${item.title}") }
                else IconButton({ a.onTask(item.id, "completed") }, enabled = a.enabled) { Icon(Icons.Rounded.Check, "Mark ${item.title} done") }
                Box {
                    IconButton({ menu = true }, enabled = a.enabled) { Icon(Icons.Rounded.MoreHoriz, "More actions for ${item.title}") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Edit or move") }, { menu = false; a.onEditTask(item.id) })
                        if (!item.done && !item.skipped) DropdownMenuItem({ Text("Skip") }, { menu = false; a.onTask(item.id, "skipped") })
                        DropdownMenuItem({ Text("Archive") }, { menu = false; a.onArchive(item.id) })
                    }
                }
            }
            Calendar.Kind.EVENT -> {
                Badge(item.detail.substringBefore(" · ").ifEmpty { "Event" })
                if (item.done) IconButton({ a.onEvent(item.id, false) }, enabled = a.enabled) { Icon(Icons.Rounded.Replay, "Mark ${item.title} not done") }
                else IconButton({ a.onEvent(item.id, true) }, enabled = a.enabled) { Icon(Icons.Rounded.Check, "Mark ${item.title} done") }
            }
            Calendar.Kind.SAC -> { Badge("SAC"); TextButton(a.onSacs) { Text("Open") } }
            Calendar.Kind.SESSION -> if (item.live) Badge("Studying", filled = true) else if (item.done) Badge("Done", filled = true)
                else OutlinedButton(a.onStudy) { Icon(Icons.Rounded.Timer, null); Spacer(Modifier.width(FolioSpacing.dp4)); Text("Start") }
            Calendar.Kind.REVIEW -> TextButton(a.onMistakes) { Text("Review") }
            Calendar.Kind.LOGGED -> Badge("Logged exam")
        }
    }
}

@Composable internal fun TaskDialog(source: JSONObject?, day: LocalDate, subjects: List<String>, onDismiss: () -> Unit, onDelete: (() -> Unit)?,
    onSave: suspend (String, Int, String, String, String, String) -> String?) {
    var title by rememberSaveable { mutableStateOf(source?.optString("title").orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(source?.optInt("durationMinutes")?.toString() ?: "30") }
    var subject by rememberSaveable { mutableStateOf(source?.optString("subject").orEmpty()) }
    var date by rememberSaveable { mutableStateOf(source?.optString("plannedFor") ?: day.toString()) }
    var detail by rememberSaveable { mutableStateOf(source?.optString("detail").orEmpty()) }
    var status by rememberSaveable { mutableStateOf(source?.optString("status") ?: "planned") }
    FormPanel(if (source == null) "Add task" else "Edit task", onDismiss, onSave = {
        val m = minutes.toIntOrNull()
        when {
            title.isBlank() || title.length > 500 -> "Add a title (up to 500 characters)."
            m == null || m !in 1..1440 -> "Duration must be 1 to 1440 minutes."
            runCatching { LocalDate.parse(date.trim()) }.isFailure -> "Enter the date as YYYY-MM-DD."
            else -> onSave(title.trim(), m, subject.trim(), date.trim(), detail.trim(), status)
        }
    }) {
        Field("Task", title) { title = it }
        ChoiceField("Subject", subject, subjects, { subject = it }, true)
        Row2 {
            Field("Minutes", minutes, Modifier.weight(1f), true) { minutes = it }
            Field("Date · YYYY-MM-DD", date, Modifier.weight(1f)) { date = it }
        }
        Field("Notes", detail) { detail = it }
        if (source != null) ChoiceField("Status", status, listOf("planned", "completed", "skipped"), { status = it })
        onDelete?.let { TextButton(it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete task") } }
    }
}

@Composable internal fun EventDialog(source: JSONObject?, day: LocalDate, onDismiss: () -> Unit, onDelete: (() -> Unit)?,
    onSave: suspend (String, String, String, String, String, String, String) -> String?) {
    val days = source?.let(Calendar::eventDays)
    var title by rememberSaveable { mutableStateOf(source?.optString("title").orEmpty()) }
    var type by rememberSaveable { mutableStateOf(source?.optString("eventType") ?: "event") }
    var date by rememberSaveable { mutableStateOf(days?.first?.toString() ?: day.toString()) }
    var start by rememberSaveable { mutableStateOf(source?.optString("startTime")?.let(Calendar::timeLabel) ?: "09:00") }
    var end by rememberSaveable { mutableStateOf(source?.optString("endTime")?.takeIf { it.isNotEmpty() }?.let(Calendar::timeLabel) ?: "10:00") }
    var endDate by rememberSaveable { mutableStateOf(days?.takeIf { it.first != it.second }?.second?.toString().orEmpty()) }
    var location by rememberSaveable { mutableStateOf(source?.optString("location").orEmpty()) }
    FormPanel(if (source == null) "Add event" else "Edit event", onDismiss, onSave = {
        Calendar.validateEvent(title, date.trim(), start.trim(), end.trim(), endDate.trim()) ?: onSave(title, type, date.trim(), start.trim(), end.trim(), endDate.trim(), location)
    }) {
        Field("Title", title) { title = it }
        ChoiceField("Type", Calendar.typeLabel(type), Calendar.EVENT_TYPES.map { it.second }, { label -> type = Calendar.EVENT_TYPES.first { it.second == label }.first })
        Field("Date · YYYY-MM-DD", date) { date = it }
        Row2 {
            Field("Start · HH:mm", start, Modifier.weight(1f)) { start = it }
            Field("End · HH:mm", end, Modifier.weight(1f)) { end = it }
        }
        Field("End date (multi-day, optional)", endDate) { endDate = it }
        Field("Location", location) { location = it }
        onDelete?.let { TextButton(it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete event") } }
    }
}

@Composable private fun TimetableDialog(current: JSONObject?, onDismiss: () -> Unit, onSave: suspend (JSONObject) -> String?) {
    var day1 by rememberSaveable { mutableStateOf(current?.optString("day1Starts")?.takeIf { it.isNotEmpty() } ?: LocalDate.now().toString()) }
    var reply by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    FormPanel("School timetable", onDismiss, onSave = {
        if (runCatching { LocalDate.parse(day1.trim()) }.isFailure) "Enter the Day 1 date as YYYY-MM-DD."
        else if (reply.isBlank()) "Paste the timetable JSON, or cancel to keep the current one."
        else runCatching { Calendar.parseTimetable(reply, current, day1.trim()) }.fold({ onSave(it) }, { it.message ?: "Could not import the timetable." })
    }) {
        Text(if (current == null) "No timetable yet." else "Replace the timetable (${current.optInt("cycleLength", 10)}-day cycle). Holidays set in Focal are kept.", style = MaterialTheme.typography.bodyMedium)
        Text("Attach a screenshot of your timetable to any chatbot with this prompt, then paste its JSON reply.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Timetable prompt", Calendar.SCREENSHOT_PROMPT))); copied = true } }) {
            Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (copied) "Copied" else "Copy prompt") }
        Field("Day 1 starts · YYYY-MM-DD", day1) { day1 = it }
        OutlinedTextField(reply, { reply = it }, Modifier.fillMaxWidth(), label = { Text("Timetable JSON") }, minLines = 4, maxLines = 10)
    }
}
