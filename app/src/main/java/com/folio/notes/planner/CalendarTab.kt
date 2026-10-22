@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.planner

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folio.notes.input.guardUiTouches
import com.folio.notes.mistakes.isoTime
import com.folio.notes.progress.ChoiceField
import com.folio.notes.progress.objects
import com.folio.notes.ui.EmptyHint
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** What the day holds: planned study tasks, SACs, calendar events and the school timetable, from the rows Focal already syncs. */
@Composable internal fun CalendarTab(learning: JSONObject, sacs: List<JSONObject>, events: List<JSONObject>, timetable: JSONObject?, subjects: List<String>,
    enabled: Boolean, saveLearning: suspend (JSONObject) -> Boolean, saveEvent: suspend (String, JSONObject?) -> Boolean, saveTimetable: suspend (JSONObject) -> Boolean) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var dayText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val month = YearMonth.parse(monthText); val day = LocalDate.parse(dayText)
    val tasks = remember(learning) { learning.optJSONArray("tasks")?.objects().orEmpty().filter { !it.has("archivedAt") } }
    var editingTask by remember { mutableStateOf<JSONObject?>(null) }
    var addingTask by rememberSaveable { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<JSONObject?>(null) }
    var addingEvent by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    fun count(d: LocalDate) = tasks.count { it.optString("plannedFor") == d.toString() } +
        sacs.count { it.optString("scheduledAt").take(10) == d.toString() } + Calendar.eventsOn(events, d).size
    val dayTasks = tasks.filter { it.optString("plannedFor") == dayText }
    val daySacs = sacs.filter { it.optString("scheduledAt").take(10) == dayText }
    val dayEvents = Calendar.eventsOn(events, day)
    val school = timetable?.takeIf { it.optBoolean("enabled", true) }?.let { Calendar.periodsOn(day, it) }

    LazyColumn(Modifier.fillMaxSize().guardUiTouches(), contentPadding = PaddingValues(FolioDestinationInset, FolioSpacing.dp12, FolioDestinationInset, 120.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ monthText = month.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                Text("${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                IconButton({ monthText = month.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
                TextButton({ monthText = YearMonth.now().toString(); dayText = today.toString() }) { Text("Today") }
            }
        }
        item {
            Column {
                Row(Modifier.fillMaxWidth()) { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                val first = month.atDay(1); val lead = first.dayOfWeek.value - 1
                for (week in 0 until (lead + month.lengthOfMonth() + 6) / 7) Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val n = week * 7 + col - lead + 1
                        if (n < 1 || n > month.lengthOfMonth()) { Spacer(Modifier.weight(1f)) } else {
                            val d = month.atDay(n); val c = count(d); val selected = d == day
                            Surface({ dayText = d.toString() }, Modifier.weight(1f).padding(2.dp).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = if (d == today) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                                Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$n", fontWeight = if (d == today) FontWeight.Bold else null, style = MaterialTheme.typography.bodyMedium)
                                    Text(if (c > 0) "•".repeat(minOf(c, 3)) else " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Button({ addingTask = true }, enabled = enabled) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Task") }
                OutlinedButton({ addingEvent = true }, enabled = enabled) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Event") }
                OutlinedButton({ importing = true }, enabled = enabled) { Icon(Icons.Rounded.Schedule, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Timetable") }
            }
        }
        item { Text(day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")) + (school?.let { " · Day ${it.first}" } ?: ""), style = MaterialTheme.typography.titleMedium) }
        if (dayTasks.isEmpty() && daySacs.isEmpty() && dayEvents.isEmpty() && school == null) item { EmptyHint("Nothing planned. Add a task or event, or plan a week from Stoplight.") }
        items(dayTasks, key = { "t:" + it.optString("id") }) { task ->
            val done = task.optString("status") == "completed"
            ListItem(headlineContent = { Text(task.optString("title")) },
                supportingContent = { Text(listOf(task.optString("subject"), "${task.optInt("durationMinutes")} min", task.optString("status").takeIf { it == "skipped" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")) },
                leadingContent = { Checkbox(done, { on -> scope.launch { saveLearning(Planner.edit(learning, "tasks", task.optString("id")) { it.put("status", if (on) "completed" else "planned") }) } }, enabled = enabled) },
                modifier = Modifier.clickableRow { editingTask = task })
        }
        items(daySacs, key = { "s:" + it.optString("id") }) { sac ->
            ListItem(headlineContent = { Text(sac.optString("title")) }, overlineContent = { Text("SAC") },
                supportingContent = { Text("${sac.optString("subject")} · ${sac.optInt("durationMinutes")} min" + (Planner.percentage(sac)?.let { " · ${"%.1f".format(it)}%" } ?: "")) })
        }
        items(dayEvents, key = { "e:" + it.optString("id") }) { event ->
            ListItem(headlineContent = { Text(event.optString("title")) }, overlineContent = { Text(Calendar.typeLabel(event.optString("eventType"))) },
                supportingContent = { Text(listOf(Calendar.timeLabel(event.optString("startTime")) + event.optString("endTime").takeIf { it.isNotEmpty() }?.let { "–" + Calendar.timeLabel(it) }.orEmpty(),
                    event.optString("location")).filter { it.isNotBlank() }.joinToString(" · ")) },
                leadingContent = { Checkbox(event.optBoolean("isFinished"), { on -> scope.launch {
                    val now = isoTime(); saveEvent(event.optString("id"), JSONObject(event.toString()).put("isFinished", on).put("updated_at", now).apply { if (on) put("finishedAt", now) else remove("finishedAt") }) } }, enabled = enabled) },
                modifier = Modifier.clickableRow { editingEvent = event })
        }
        school?.let { (_, periods) ->
            item { Text("School timetable", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = FolioSpacing.dp8)) }
            items(periods, key = { "p:${it.name}:${it.start}" }) { p ->
                ListItem(headlineContent = { Text(p.subject.ifEmpty { p.name }) }, overlineContent = { Text("${p.start}–${p.end} · ${p.name}") },
                    supportingContent = p.location.takeIf { it.isNotEmpty() }?.let { { Text(it) } })
            }
        }
    }
    if (addingTask || editingTask != null) TaskDialog(editingTask, day, subjects, { addingTask = false; editingTask = null }, onDelete = editingTask?.let { t ->
        { scope.launch { saveLearning(Planner.edit(learning, "tasks", t.optString("id")) { it.put("archivedAt", isoTime()) }) } } }) { title, minutes, subject, date, detail, status ->
        val source = editingTask
        val next = if (source == null) Planner.append(learning, "tasks", listOf(JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "custom").put("title", title)
            .apply { if (subject.isNotBlank()) put("subject", subject) }.put("detail", detail).put("durationMinutes", minutes).put("plannedFor", date).put("status", "planned")
            .put("createdAt", isoTime()).put("updatedAt", isoTime())))
        else Planner.edit(learning, "tasks", source.optString("id")) { it.put("title", title).put("detail", detail).put("durationMinutes", minutes).put("plannedFor", date).put("status", status)
            .apply { if (subject.isBlank()) remove("subject") else put("subject", subject) } }
        if (saveLearning(next)) null else "Could not save the task. Try again."
    }
    if (addingEvent || editingEvent != null) EventDialog(editingEvent, day, { addingEvent = false; editingEvent = null },
        onDelete = editingEvent?.let { e -> { scope.launch { saveEvent(e.optString("id"), null) } } }) { title, type, date, start, end, endDate, location ->
        val source = editingEvent
        val event = if (source == null) Calendar.newEvent(title, type, LocalDate.parse(date), start, end, endDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse), location)
        else JSONObject(source.toString()).put("title", title.trim()).put("eventType", type).put("startTime", Calendar.toIso(LocalDate.parse(date), start))
            .put("endTime", Calendar.toIso(endDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: LocalDate.parse(date), end)).put("updated_at", isoTime())
            .apply { if (location.isBlank()) remove("location") else put("location", location.trim()) }
        if (saveEvent(event.getString("id"), event)) null else "Could not save the event. Try again."
    }
    if (importing) TimetableDialog(timetable, { importing = false }) { parsed -> if (saveTimetable(parsed)) null else "Could not save the timetable. Try again." }
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))

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
