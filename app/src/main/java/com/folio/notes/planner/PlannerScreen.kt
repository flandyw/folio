@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.planner

import com.folio.notes.model.countOf
import androidx.compose.ui.text.input.KeyboardCapitalization
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.input.guardUiTouches
import com.folio.notes.model.Notebook
import com.folio.notes.progress.ChoiceField
import com.folio.notes.progress.MetricBars
import com.folio.notes.progress.ProgressCard
import com.folio.notes.progress.ScoreTrend
import com.folio.notes.progress.countsAsExam
import com.folio.notes.progress.notebookExams
import com.folio.notes.progress.friendlyDate
import com.folio.notes.progress.objects
import com.folio.notes.progress.strings
import com.folio.notes.study.FocalAccountButton
import com.folio.notes.study.FocalAccountPanel
import com.folio.notes.ui.EmptyHint
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioScreenHeading
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val TABS = listOf("Calendar", "SACs", "Stoplight", "Goals", "Score", "Grades")
private val RATING_COLOUR = mapOf("red" to Color(0xFFC62828), "amber" to Color(0xFFF9A825), "green" to Color(0xFF2E7D32))

private fun pct(value: Double?, digits: Int = 1) = value?.let { "%.${digits}f".format(it) + "%" } ?: "—"
private fun mark(value: Double) = value.toString().removeSuffix(".0")

/** Focal's SACs, Stoplight checklist and score goals, kept in the same synced `user_state` rows the Focal apps use. */
@Composable fun PlannerScreen(notes: List<Notebook>, modifier: Modifier = Modifier, onSettings: () -> Unit, onMistakes: () -> Unit, onStudy: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { (context.applicationContext as FolioApplication).focalProgress }
    val state by manager.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(TABS[0]) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.loading && state.readable
    LaunchedEffect(lifecycle, state.userId) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { manager.sync(); delay(30_000) } }
    }
    val sacs = remember(state.cache.rows) { (state.cache.value("sacRecords") as? JSONArray)?.objects().orEmpty() }
    val learning = remember(state.cache.rows) { Planner.learning(state.cache.value("learning")) }
    val events = remember(state.cache.rows) { state.cache.events }
    val mistakes = remember(state.cache.rows) { state.cache.mistakes }
    // The same exam log Progress shows: synced results plus notebook marks not yet logged.
    val exams = remember(state.cache.rows, notes) {
        val notExam = notes.filterNot { it.countsAsExam }.map { it.id }.toSet()
        state.cache.exams.filterNot { it.notebookId in notExam } + notebookExams(notes).filterNot { "attempts:${it.id}" in state.cache.rows }
    }
    val subjects = remember(state.cache.rows, exams) {
        ((state.cache.value("subjects") as? JSONArray)?.strings().orEmpty() + sacs.map { it.optString("subject") } +
            Planner.items(learning).map { it.optString("subject") } + exams.map { it.subject }).filter { it.isNotBlank() }.distinct().sorted()
    }
    suspend fun saveAtar(list: List<JSONObject>) = manager.saveValue("atarEstimates", JSONArray(list), state.userId)
    suspend fun saveSacs(list: List<JSONObject>) = manager.saveValue("sacRecords", JSONArray(list), state.userId)
    suspend fun saveLearning(next: JSONObject) = manager.saveValue("learning", next, state.userId)

    Column(modifier.fillMaxSize()) {
        FolioScreenHeading("Planner", subtitle = {
            Text(when {
                state.loading -> "Loading…"; state.syncing -> "Syncing with Focal…"
                state.userId == null -> "Saved on this device · connect Focal to sync"
                state.cache.outbox.isNotEmpty() -> "${countOf(state.cache.outbox.size, "change")} waiting to sync"
                else -> "Synced${state.cache.lastSynced?.let { " · ${friendlyDate(it)}" } ?: ""}"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }) {
            IconButton(manager::sync, enabled = state.userId != null && !state.syncing && !state.loading, shapes = IconButtonDefaults.shapes()) {
                if (state.syncing) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.Sync, "Sync with Focal")
            }
            FocalAccountButton({ showAccount = true }, trouble = state.userId != null && (state.error != null || state.cache.conflicts.isNotEmpty()))
            IconButton(onSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Settings") }
        }
        PrimaryScrollableTabRow(TABS.indexOf(tab), Modifier.padding(horizontal = FolioDestinationInset), containerColor = Color.Transparent, edgePadding = 0.dp) {
            TABS.forEach { Tab(tab == it, { tab = it }, text = { Text(it) }) }
        }
        state.error?.let { Text(it, Modifier.padding(FolioDestinationInset, FolioSpacing.dp8), color = MaterialTheme.colorScheme.error) }
        if (state.cache.conflicts.keys.any { it == "user_state:sacRecords" || it == "user_state:learning" }) {
            Row(Modifier.padding(horizontal = FolioDestinationInset), verticalAlignment = Alignment.CenterVertically) {
                Text("Changed on another device. Your edit is still saved here.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                for (key in listOf("user_state:sacRecords", "user_state:learning").filter { it in state.cache.conflicts }) {
                    TextButton({ scope.launch { manager.resolve(key, false, state.userId) } }) { Text("Use Focal's") }
                    TextButton({ scope.launch { manager.resolve(key, true, state.userId) } }) { Text("Keep mine") }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                "Calendar" -> CalendarTab(learning, sacs, events, state.cache.timetable, subjects, exams, mistakes, enabled, ::saveLearning,
                    { id, payload -> manager.save("events", id, payload, state.userId) }, { manager.save("timetable_config", Calendar.TIMETABLE_ROW, it, state.userId) },
                    onMistakes, { tab = "SACs" }, onStudy)
                "SACs" -> SacTab(sacs, subjects, enabled, ::saveSacs)
                "Stoplight" -> StoplightTab(learning, subjects, enabled, ::saveLearning)
                "Goals" -> GoalsTab(learning, subjects, exams, state.cache.value("atarEstimates") as? JSONArray, enabled, ::saveLearning)
                "Grades" -> GradesTab(state.catalog.references, exams)
                else -> ScoreTab(exams, sacs, state.catalog.references, (state.cache.value("atarEstimates") as? JSONArray)?.objects().orEmpty(), enabled, ::saveAtar)
            }
        }
    }
    if (showAccount) FocalAccountPanel({ showAccount = false }, onMistakes, onStudy)
}

// ---- Shared form chrome -----------------------------------------------------------------------

/** Title, scrolling fields, an error line and Cancel / Save; [onSave] returns an error message or null once saved. */
@Composable internal fun FormPanel(title: String, onDismiss: () -> Unit, onSave: suspend () -> String?, fields: @Composable ColumnScope.() -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FolioPanel(title, { if (!busy) onDismiss() }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            fields()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
            TextButton(onDismiss, enabled = !busy) { Text("Cancel") }
            Button({ busy = true; scope.launch { error = onSave(); busy = false; if (error == null) onDismiss() } }, enabled = !busy) { Text(if (busy) "Saving…" else "Save") }
        }
    }
}

@Composable internal fun Field(label: String, value: String, modifier: Modifier = Modifier, number: Boolean = false, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text,
            capitalization = if (number) KeyboardCapitalization.None else KeyboardCapitalization.Sentences))

/** Typed dates still work; the calendar button saves remembering the format. */
@Composable internal fun DateField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, trailingIcon = {
        IconButton({
            val start = runCatching { LocalDate.parse(value.trim()) }.getOrDefault(LocalDate.now())
            android.app.DatePickerDialog(context, { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).toString()) },
                start.year, start.monthValue - 1, start.dayOfMonth).show()
        }) { Icon(Icons.Rounded.CalendarMonth, "Choose a date") }
    })
}

@Composable internal fun TimeField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, trailingIcon = {
        IconButton({
            val start = runCatching { java.time.LocalTime.parse(value.trim()) }.getOrDefault(java.time.LocalTime.now())
            android.app.TimePickerDialog(context, { _, hour, minute -> onChange(java.time.LocalTime.of(hour, minute).toString()) },
                start.hour, start.minute, true).show()
        }) { Icon(Icons.Rounded.Schedule, "Choose a time") }
    })
}

/** A figure tile: the number first, its name under it. */
@Composable private fun Stat(label: String, value: String, modifier: Modifier = Modifier) =
    Surface(modifier, shape = com.folio.notes.ui.FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(FolioSpacing.dp16)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

@Composable private fun StatRow(vararg stats: Pair<String, String>) =
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        stats.forEach { (label, value) -> Stat(label, value, Modifier.weight(1f).fillMaxHeight()) }
    }

/** Red / amber / green / unrated as one stacked track, so a checklist reads at a glance. */
@Composable private fun RagBar(items: List<JSONObject>, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 10.dp) {
    val counts = Planner.RATINGS.map { r -> RATING_COLOUR.getValue(r) to items.count { it.optString("rating") == r } } +
        (MaterialTheme.colorScheme.surfaceContainerHighest to items.count { !it.has("rating") })
    Row(modifier.fillMaxWidth().height(height).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        counts.filter { it.second > 0 }.forEach { (colour, n) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(colour)) }
    }
}

@Composable internal fun Row2(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), content = content)

// ---- SACs ---------------------------------------------------------------------------------------

@Composable private fun SacTab(sacs: List<JSONObject>, subjects: List<String>, enabled: Boolean, save: suspend (List<JSONObject>) -> Boolean) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    val stats = remember(sacs) { Planner.stats(sacs) }
    val bySubject = remember(sacs) { Planner.bySubject(sacs) }
    val results = remember(sacs) { sacs.filter(Planner::isCompleted).sortedBy { it.optString("scheduledAt") }
        .mapNotNull { s -> Planner.percentage(s)?.let { "${s.optString("scheduledAt").take(10)} · ${s.optString("title")}" to it } } }
    val next = remember(sacs) { Planner.upcoming(sacs).take(6) }
    LazyColumn(Modifier.fillMaxSize().guardUiTouches(), contentPadding = PaddingValues(FolioDestinationInset, FolioSpacing.dp12, FolioDestinationInset, 120.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        item { Button({ adding = true }, enabled = enabled) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Plan or log SAC") } }
        if (sacs.isEmpty()) item { EmptyHint("Plan an upcoming assessment or log a completed result to start building subject statistics.") }
        else {
            item {
                StatRow("Completed" to "${stats.completed}",
                    "Weighted avg" to pct(stats.average) + (stats.trend?.takeIf { kotlin.math.abs(it) >= 0.5 }?.let { " ${if (it > 0) "▲" else "▼"}${"%.1f".format(kotlin.math.abs(it))}" } ?: ""),
                    "Best" to pct(stats.best), "Upcoming" to "${stats.upcoming}")
            }
            if (results.isNotEmpty()) item(key = "trend") { ScoreTrend("Results over time", results, "Every completed SAC, as a percentage of its marks", icon = Icons.Rounded.ShowChart) }
            bySubject.filter { it.average != null }.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "averages") { MetricBars("Average by subject", list.map { it.subject to it.average!! }.sortedByDescending { it.second },
                    subtitle = "Weighted by each SAC's weighting", maximum = 100.0, icon = Icons.Rounded.BarChart) }
            }
            if (next.isNotEmpty()) {
                item { Text("Upcoming", style = MaterialTheme.typography.titleMedium) }
                items(next, key = { "up:" + it.optString("id") }) { sac ->
                    val days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(sac.optString("scheduledAt").take(10)))
                    SacRow(sac, if (days == 0L) "Today" else if (days == 1L) "Tomorrow" else "$days days") { editing = sac }
                }
            }
            item { Text("Subjects", style = MaterialTheme.typography.titleMedium) }
            items(bySubject, key = { "sub:" + it.subject }) { s ->
                ListItem(headlineContent = { Text(s.subject) }, supportingContent = { Text("${s.completed} completed · ${s.upcoming} upcoming") },
                    trailingContent = { Text(pct(s.average), style = MaterialTheme.typography.titleMedium) })
            }
            item { Text("All SACs", style = MaterialTheme.typography.titleMedium) }
            items(sacs.sortedByDescending { it.optString("scheduledAt") }, key = { it.optString("id") }) { sac ->
                SacRow(sac, Planner.percentage(sac)?.let { pct(it) } ?: if (sac.optString("scheduledAt") >= LocalDate.now().toString()) "Upcoming" else "No mark yet") { editing = sac }
            }
        }
    }
    if (adding || editing != null) {
        val source = editing
        SacDialog(source, subjects, onDismiss = { adding = false; editing = null },
            onDelete = source?.let { { deleting = it; editing = null; adding = false } }) { saved ->
            val id = saved.optString("id")
            if (save(if (sacs.any { it.optString("id") == id }) sacs.map { if (it.optString("id") == id) saved else it } else sacs + saved)) null
            else "Could not save the SAC. Try again."
        }
    }
    deleting?.let { sac ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete SAC?") }, text = { Text(sac.optString("title")) },
            confirmButton = { TextButton({ scope.launch { save(sacs.filter { it.optString("id") != sac.optString("id") }); deleting = null } },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Keep") } })
    }
}

@Composable private fun SacRow(sac: JSONObject, trailing: String, onClick: () -> Unit) {
    val score = if (Planner.isCompleted(sac)) "${mark(sac.getDouble("score"))}/${mark(sac.getDouble("maxScore"))}" else "${sac.optInt("durationMinutes")} min"
    Surface(onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(sac.optString("title"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text("${sac.optString("subject")} · ${sac.optString("provider")} · Unit ${sac.optInt("unit")} · ${sac.optString("scheduledAt").take(10)} · $score") },
            trailingContent = { Text(trailing, style = MaterialTheme.typography.titleSmall) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

@Composable private fun SacDialog(source: JSONObject?, subjects: List<String>, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: suspend (JSONObject) -> String?) {
    fun str(key: String) = source?.let { if (it.has(key)) it.optString(key).removeSuffix(".0") else null }.orEmpty()
    var subject by rememberSaveable { mutableStateOf(str("subject")) }
    var provider by rememberSaveable { mutableStateOf(str("provider").ifEmpty { "School" }) }
    var title by rememberSaveable { mutableStateOf(str("title")) }
    var unit by rememberSaveable { mutableStateOf(str("unit").ifEmpty { "3" }) }
    var date by rememberSaveable { mutableStateOf(str("scheduledAt").take(10).ifEmpty { LocalDate.now().toString() }) }
    var minutes by rememberSaveable { mutableStateOf(str("durationMinutes").ifEmpty { "60" }) }
    var score by rememberSaveable { mutableStateOf(str("score")) }
    var max by rememberSaveable { mutableStateOf(str("maxScore")) }
    var weighting by rememberSaveable { mutableStateOf(str("weighting")) }
    FormPanel(if (source == null) "Plan or log SAC" else "Edit SAC", onDismiss, onSave = {
        val now = com.folio.notes.mistakes.isoTime()
        val sac = (source?.let { JSONObject(it.toString()) } ?: Planner.newSac("", "", "", 1, date, 1.0, null, null, null, now))
            .put("subject", subject.trim()).put("provider", provider.trim()).put("title", title.trim()).put("unit", unit.toIntOrNull() ?: 0)
            .put("scheduledAt", date.trim()).put("durationMinutes", minutes.toDoubleOrNull() ?: 0.0).put("updatedAt", now)
        if (score.isBlank() && max.isBlank()) { sac.remove("score"); sac.remove("maxScore"); sac.remove("completedAt") }
        else { sac.put("score", score.toDoubleOrNull() ?: Double.NaN); sac.put("maxScore", max.toDoubleOrNull() ?: Double.NaN)
            if (!sac.has("completedAt")) sac.put("completedAt", date.trim()) }
        if (weighting.isBlank()) sac.remove("weighting") else sac.put("weighting", weighting.toDoubleOrNull() ?: Double.NaN)
        // NaN is not valid JSON: surface it as the field error instead of writing it.
        Planner.validateSac(sac)?.also { return@FormPanel it }
        if (listOf("score", "maxScore", "weighting").any { sac.has(it) && sac.optDouble(it).isNaN() }) "Marks and weighting must be numbers."
        else onSave(sac)
    }) {
        ChoiceField("Subject", subject, subjects, { subject = it }, true)
        Field("School or provider", provider) { provider = it }
        Field("SAC title", title) { title = it }
        Row2 {
            Box(Modifier.weight(1f)) { ChoiceField("Unit", unit, listOf("1", "2", "3", "4"), { unit = it }) }
            DateField("Date", date, Modifier.weight(1f)) { date = it }
        }
        Row2 {
            Field("Duration (min)", minutes, Modifier.weight(1f), true) { minutes = it }
            Field("Weighting %", weighting, Modifier.weight(1f), true) { weighting = it }
        }
        Row2 {
            Field("Mark", score, Modifier.weight(1f), true) { score = it }
            Field("Out of", max, Modifier.weight(1f), true) { max = it }
        }
        Text("Leave the mark blank for an upcoming SAC.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onDelete?.let { TextButton(it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete SAC") } }
    }
}

// ---- Stoplight ----------------------------------------------------------------------------------

@Composable private fun StoplightTab(learning: JSONObject, subjects: List<String>, enabled: Boolean, save: suspend (JSONObject) -> Boolean) {
    val items = remember(learning) { Planner.items(learning) }
    val itemSubjects = remember(items) { items.map { it.optString("subject") }.distinct().sorted() }
    var chosen by rememberSaveable { mutableStateOf("") }
    val subject = if (chosen in itemSubjects) chosen else itemSubjects.firstOrNull().orEmpty()
    var filter by rememberSaveable { mutableStateOf("All") }
    var importing by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mine = remember(items, subject) { items.filter { it.optString("subject") == subject } }
    val visible = remember(mine, filter) { mine.filter { when (filter) {
        "Red", "Amber", "Green" -> it.optString("rating") == filter.lowercase(); "Unrated" -> !it.has("rating")
        "Re-check" -> Planner.recheckDue(it); else -> true } }.groupBy(Planner::group) }
    val greens = mine.count { it.optString("rating") == "green" }
    val bySubject = remember(items) { items.groupBy { it.optString("subject") } }
    LazyColumn(Modifier.fillMaxSize().guardUiTouches(), contentPadding = PaddingValues(FolioDestinationInset, FolioSpacing.dp12, FolioDestinationInset, 120.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Button({ importing = true }, enabled = enabled) { Icon(Icons.Rounded.DataObject, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Import checklist") }
                if (subject.isNotEmpty()) OutlinedButton({
                    scope.launch {
                        val (next, count) = Planner.planWeek(learning, subject)
                        notice = if (count == 0) "Nothing to plan: every red, amber or re-check item already has a task."
                        else if (save(next)) "Added ${countOf(count, "revision task")} (shown in Focal's calendar)." else "Could not save the tasks. Try again."
                    }
                }, enabled = enabled) { Icon(Icons.Rounded.EventAvailable, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Plan this week") }
            }
        }
        notice?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        if (subject.isEmpty()) item { EmptyHint("Import a checklist from your study design with any chatbot. Each key knowledge and skill becomes an item you can rate red, amber or green.") }
        else {
            item {
                ProgressCard("Checklist health", "Tap a subject to open its checklist", Icons.Rounded.Traffic) {
                    itemSubjects.forEach { s ->
                        val list = bySubject[s].orEmpty()
                        Column(Modifier.fillMaxWidth().clip(com.folio.notes.ui.FolioShapes.medium).clickable { chosen = s }
                            .background(if (s == subject) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f) else Color.Transparent).padding(FolioSpacing.dp8),
                            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            Row { Text(s, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (s == subject) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${list.count { it.optString("rating") == "green" }}/${list.size} green", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            RagBar(list)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                        Planner.RATINGS.forEach { com.folio.notes.progress.ChartKey(RATING_COLOUR.getValue(it), it.replaceFirstChar(Char::uppercase)) }
                        com.folio.notes.progress.ChartKey(MaterialTheme.colorScheme.surfaceContainerHighest, "Unrated")
                    }
                }
            }
            item { Text("$greens of ${mine.size} green · ${mine.count { it.optString("rating") == "red" }} red · ${mine.count(Planner::recheckDue)} to re-check", style = MaterialTheme.typography.titleSmall) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    listOf("All", "Red", "Amber", "Green", "Re-check", "Unrated").forEach { FilterChip(filter == it, { filter = it }, { Text(it) }) }
                }
            }
            visible.forEach { (group, list) ->
                item(key = "g:$group") {
                    Column(Modifier.padding(top = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        Text("$group · ${list.count { it.optString("rating") == "green" }}/${list.size}", style = MaterialTheme.typography.titleMedium)
                        RagBar(list, height = 6.dp)
                    }
                }
                items(list, key = { it.optString("id") }) { item ->
                    ListItem(headlineContent = { Text(item.optString("name")) },
                        supportingContent = { Text(listOfNotNull(item.optString("type").ifEmpty { null }, if (Planner.recheckDue(item)) "re-check" else null,
                            item.optString("check").ifEmpty { null }).joinToString(" · ")) },
                        trailingContent = { Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            Planner.RATINGS.forEach { r ->
                                val on = item.optString("rating") == r
                                // Tapping the rating in place takes it off again, back to Unrated.
                                FilterChip(on, { scope.launch { save(if (on) Planner.clearRating(learning, item.optString("id")) else Planner.setRating(learning, item.optString("id"), r)) } }, { Text(r.replaceFirstChar(Char::uppercase)) },
                                    enabled = enabled, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = RATING_COLOUR.getValue(r), selectedLabelColor = Color.White))
                            }
                        } })
                }
            }
        }
    }
    if (importing) ImportDialog(subject.ifEmpty { subjects.firstOrNull().orEmpty() }, subjects, { importing = false }) { sub, imported ->
        val (next, added) = Planner.merge(learning, sub, imported)
        if (save(next)) { chosen = sub; notice = "Checklist updated for $sub · $added new item${if (added == 1) "" else "s"}."; null } else "Could not save the checklist. Try again."
    }
}

@Composable private fun ImportDialog(defaultSubject: String, subjects: List<String>, onDismiss: () -> Unit, onImport: suspend (String, List<Planner.Imported>) -> String?) {
    var subject by rememberSaveable { mutableStateOf(defaultSubject) }
    var reply by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    FormPanel("Import checklist", onDismiss, onSave = {
        if (subject.isBlank()) "Choose a subject."
        else runCatching { Planner.parseImport(reply) }.fold({ it.takeIf { l -> l.isNotEmpty() }?.let { l -> onImport(subject.trim(), l) } ?: "No items found in that reply." },
            { it.message ?: "That reply is not valid JSON." })
    }) {
        Text("Give any chatbot your study design with this prompt, then paste its JSON reply. Re-importing keeps your ratings.", style = MaterialTheme.typography.bodyMedium)
        ChoiceField("Subject", subject, subjects, { subject = it }, true)
        OutlinedButton({ scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText("Stoplight prompt", Planner.importPrompt(subject.trim())))); copied = true } },
            enabled = subject.isNotBlank()) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (copied) "Copied" else "Copy prompt") }
        OutlinedTextField(reply, { reply = it }, Modifier.fillMaxWidth(), label = { Text("Chatbot reply (JSON)") }, minLines = 4, maxLines = 10)
    }
}

// ---- Goals --------------------------------------------------------------------------------------

@Composable private fun GoalsTab(learning: JSONObject, subjects: List<String>, exams: List<com.folio.notes.progress.LoggedExam>, atar: JSONArray?,
    enabled: Boolean, save: suspend (JSONObject) -> Boolean) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val active = remember(learning) { Planner.goals(learning, false) }
    val archived = remember(learning) { Planner.goals(learning, true) }
    val today = LocalDate.now().toString()
    val progressOf = remember(active, exams, atar) { active.associate { it.optString("id") to Planner.goalProgress(it, exams, atar) } }
    LazyColumn(Modifier.fillMaxSize().guardUiTouches(), contentPadding = PaddingValues(FolioDestinationInset, FolioSpacing.dp12, FolioDestinationInset, 120.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        item { Button({ adding = true }, enabled = enabled) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Set a goal") } }
        if (active.isNotEmpty()) item {
            val stats = active.map { progressOf.getValue(it.optString("id")) }
            StatRow("Active" to "${active.size}", "Reached" to "${active.zip(stats).count { (g, p) -> p.current != null && p.current >= g.getDouble("target") }}",
                "Average progress" to "${stats.map { it.progress }.average().toInt()}%")
        }
        if (active.isEmpty()) item { EmptyHint("Add a target to see your current baseline and the gap to close. Goals show direction, not guaranteed outcomes.") }
        items(active, key = { it.optString("id") }) { goal ->
            val progress = progressOf[goal.optString("id")] ?: Planner.goalProgress(goal, exams, atar)
            val reached = progress.current != null && progress.current >= goal.getDouble("target")
            val expired = !reached && goal.optString("deadline") < today
            OutlinedCard {
                Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Text("${goal.optString("subject").let { if (it.isEmpty()) "" else "$it · " }}${Planner.kindLabel(goal.optString("kind"))}", style = MaterialTheme.typography.titleMedium)
                    Text("Target ${mark(goal.getDouble("target"))} by ${goal.optString("deadline")} · " +
                        if (expired) "Expired" else if (reached) "Reached" else progress.gap?.let { "${"%.1f".format(it)} to go" } ?: "Needs baseline",
                        style = MaterialTheme.typography.bodySmall, color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator({ (progress.progress / 100).toFloat().coerceIn(0f, 1f) }, Modifier.size(72.dp), strokeWidth = 8.dp,
                                color = if (reached) RATING_COLOUR.getValue("green") else MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                            Text("${progress.progress.toInt()}%", style = MaterialTheme.typography.labelLarge)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            Text("${progress.label}: ${progress.current?.let { "%.1f".format(it) } ?: "—"} / ${mark(goal.getDouble("target"))}", style = MaterialTheme.typography.bodyMedium)
                            Text(progress.evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ editing = goal }, enabled = enabled) { Text("Edit") }
                        TextButton({ scope.launch { save(Planner.edit(learning, "goals", goal.optString("id")) { it.put("archivedAt", com.folio.notes.mistakes.isoTime()) }) } }, enabled = enabled) { Text("Archive") }
                    }
                }
            }
        }
        if (archived.isNotEmpty()) {
            item { Text("Archived", style = MaterialTheme.typography.titleMedium) }
            items(archived, key = { "a:" + it.optString("id") }) { goal ->
                ListItem(headlineContent = { Text("${goal.optString("subject").let { if (it.isEmpty()) "" else "$it · " }}${Planner.kindLabel(goal.optString("kind"))}") },
                    supportingContent = { Text("Target ${mark(goal.getDouble("target"))}") },
                    trailingContent = { TextButton({ scope.launch { save(Planner.edit(learning, "goals", goal.optString("id")) { it.remove("archivedAt") }) } }, enabled = enabled) { Text("Restore") } })
            }
        }
    }
    if (adding || editing != null) GoalDialog(editing, subjects, { adding = false; editing = null }) { kind, subject, target, deadline ->
        val source = editing
        val next = if (source == null) Planner.append(learning, "goals", listOf(Planner.newGoal(kind, subject, target, deadline)))
        else Planner.edit(learning, "goals", source.optString("id")) {
            it.put("kind", kind).put("target", target).put("deadline", deadline)
            if (kind == "atar") it.remove("subject") else it.put("subject", subject.trim())
        }
        if (save(next)) null else "Could not save the goal. Try again."
    }
}

@Composable private fun GoalDialog(source: JSONObject?, subjects: List<String>, onDismiss: () -> Unit, onSave: suspend (String, String, Double, String) -> String?) {
    var kind by rememberSaveable { mutableStateOf(source?.optString("kind") ?: "exam-percentage") }
    var subject by rememberSaveable { mutableStateOf(source?.optString("subject") ?: subjects.firstOrNull().orEmpty()) }
    var target by rememberSaveable { mutableStateOf(source?.optDouble("target")?.let(::mark) ?: "80") }
    var deadline by rememberSaveable { mutableStateOf(source?.optString("deadline") ?: LocalDate.now().plusMonths(3).toString()) }
    FormPanel(if (source == null) "Set a goal" else "Edit goal", onDismiss, onSave = {
        Planner.validateGoal(kind, subject, target.toDoubleOrNull(), deadline.trim()) ?: onSave(kind, subject, target.toDouble(), deadline.trim())
    }) {
        ChoiceField("Measure", Planner.kindLabel(kind), Planner.GOAL_KINDS.map { it.second }, { label ->
            kind = Planner.GOAL_KINDS.first { it.second == label }.first
            target = when (kind) { "study-score" -> "40"; "atar" -> "90"; else -> "80" }
        })
        if (kind != "atar") ChoiceField("Subject", subject, subjects, { subject = it }, true)
        Row2 {
            Field("Target", target, Modifier.weight(1f), true) { target = it }
            DateField("By", deadline, Modifier.weight(1f)) { deadline = it }
        }
    }
}

// ---- Study score and ATAR -----------------------------------------------------------------------

/** The bundled 275 KB VTAC table, decoded once per process instead of on every visit to the Score tab. */
@Volatile private var vtacCache: StudyScore.VtacData? = null

@Composable private fun ScoreTab(exams: List<com.folio.notes.progress.LoggedExam>, sacs: List<JSONObject>, references: List<com.folio.notes.progress.ExamReference>,
    saved: List<JSONObject>, enabled: Boolean, save: suspend (List<JSONObject>) -> Boolean) {
    val context = LocalContext.current
    val vtac by produceState(vtacCache) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { StudyScore.VtacData.decode(context.assets.open("planner/vtac-scaling.json").bufferedReader().use { it.readText() }) }.getOrNull() }
            ?.also { vtacCache = it }
    }
    val subjects = remember(exams) { exams.map { it.subject }.distinct().sorted() }
    var picked by rememberSaveable { mutableStateOf("") }
    val subject = if (picked in subjects) picked else subjects.firstOrNull().orEmpty()
    var sacText by rememberSaveable(subject) { mutableStateOf("") }
    var weightText by rememberSaveable(subject) { mutableStateOf(StudyScore.defaultExamWeight(subject).toInt().toString()) }
    val prediction = remember(subject, exams, references, sacText, weightText) {
        if (subject.isEmpty()) null else StudyScore.predict(subject, exams, references, sacText.toDoubleOrNull(),
            weightText.toDoubleOrNull() ?: StudyScore.defaultExamWeight(subject))
    }
    val scaled = remember(prediction, vtac) { prediction?.let { p -> vtac?.let { StudyScore.scaled(p.studyScore.toDouble(), subject, it) } } }
    LazyColumn(Modifier.fillMaxSize().guardUiTouches(), contentPadding = PaddingValues(FolioDestinationInset, FolioSpacing.dp12, FolioDestinationInset, 120.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        if (subjects.isEmpty()) item { EmptyHint("Log an exam in Progress to estimate a study score. Predictions use the VCAA grade distribution for each paper.") }
        else {
            item { Text("Study score", style = MaterialTheme.typography.titleLarge) }
            item { ChoiceField("Subject", subject, subjects, { picked = it }) }
            item { Row2 {
                Field("SAC percentile (optional)", sacText, Modifier.weight(1f), true) { sacText = it }
                Field("Exam weight %", weightText, Modifier.weight(1f), true) { weightText = it }
            } }
            if (sacs.any { it.optString("subject").equals(subject, true) && Planner.isCompleted(it) }) item {
                Text("Your SAC marks are percentages of the marks, not statewide percentiles; enter a percentile only if you know where you sit in your cohort.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (prediction == null) item { EmptyHint("None of your $subject papers match a VCAA grade distribution yet.") }
            else item {
                ElevatedCard { Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Text("${prediction.studyScore}", style = MaterialTheme.typography.displaySmall)
                        Text("raw study score · likely ${prediction.low}–${prediction.high} · ${prediction.confidence} confidence", style = MaterialTheme.typography.bodyMedium)
                    }
                    scaled?.let { Text("Scaled ≈ ${"%.1f".format(it.scaled)} (${"%.1f".format(it.min)}–${"%.1f".format(it.max)} across ${it.years.size} year${if (it.years.size == 1) "" else "s"} of VTAC reports)") }
                    Text("Combined percentile ${"%.1f".format(prediction.combinedPercentile)} · exams ${"%.1f".format(prediction.examPercentile)} (${prediction.examWeightPercent.toInt()}%) · school work ${"%.1f".format(prediction.sacPercentile)}",
                        style = MaterialTheme.typography.bodySmall)
                    prediction.components.forEach {
                        com.folio.notes.progress.MeterRow(it.label, "${"%.1f".format(it.percentile)}", (it.percentile / 100).toFloat(),
                            if (it.projected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            "${it.weightPercent.toInt()}% weight · ${if (it.projected) "projected from your other papers" else "${it.evidenceCount} paper${if (it.evidenceCount == 1) "" else "s"}"}")
                    }
                    if (prediction.approximated > 0) Text("${prediction.approximated} result${if (prediction.approximated == 1) "" else "s"} compared with a different year's distribution.", style = MaterialTheme.typography.bodySmall)
                    if (prediction.excluded > 0) Text("${prediction.excluded} paper${if (prediction.excluded == 1) "" else "s"} could not be matched and ${if (prediction.excluded == 1) "is" else "are"} ignored.", style = MaterialTheme.typography.bodySmall)
                    Text("An estimate from past grade distributions, not a guaranteed result.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
        vtac?.let { data -> atarItems(data, exams, references, saved, enabled, save) }
    }
}

private fun LazyListScope.atarItems(data: StudyScore.VtacData, exams: List<com.folio.notes.progress.LoggedExam>, references: List<com.folio.notes.progress.ExamReference>,
    saved: List<JSONObject>, enabled: Boolean, save: suspend (List<JSONObject>) -> Boolean) {
    item(key = "atar") { AtarEstimator(data, exams, references, saved, enabled, save) }
}

@Composable private fun AtarEstimator(data: StudyScore.VtacData, exams: List<com.folio.notes.progress.LoggedExam>, references: List<com.folio.notes.progress.ExamReference>,
    saved: List<JSONObject>, enabled: Boolean, save: suspend (List<JSONObject>) -> Boolean) {
    val years = remember(data) { data.atar.map { it.year }.filter { y -> data.scaling.any { it.year == y } }.sortedDescending() }
    var year by rememberSaveable { mutableIntStateOf(years.firstOrNull() ?: 0) }
    var rowsRaw by rememberSaveable { mutableStateOf("[]") }
    val rows = remember(rowsRaw) { JSONArray(rowsRaw).objects() }
    val studies = remember(data, year) { data.scaling.filter { it.year == year }.sortedBy { it.studyName } }
    val predictions = remember(exams, references) { exams.map { it.subject }.distinct().mapNotNull { s -> StudyScore.predict(s, exams, references)?.let { StudyScore.studyKey(s) to it.studyScore.toDouble() } }.toMap() }
    val scope = rememberCoroutineScope()
    fun update(next: List<JSONObject>) { rowsRaw = JSONArray(next).toString() }
    val results = remember(rows, studies, predictions) {
        rows.mapNotNull { row ->
            val ref = studies.find { it.code == row.optString("code") } ?: return@mapNotNull null
            val predicted = predictions[StudyScore.studyKey(ref.studyName)]
            val raw = if (row.optBoolean("usePrediction") && predicted != null) predicted else row.optString("raw").toDoubleOrNull() ?: return@mapNotNull null
            StudyScore.interpolate(raw, ref.points)?.let { StudyScore.Result(row.optString("id"), ref.code, ref.studyName, raw, it) }
        }
    }
    val estimate = remember(results, year) { StudyScore.estimateAtar(results, year, data) }
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        Text("ATAR estimate", style = MaterialTheme.typography.titleLarge)
        Text("Add at least four Unit 3/4 studies including an English-group study. Predicted raw scores are used when you have them; otherwise enter one.", style = MaterialTheme.typography.bodySmall)
        ChoiceField("Scaling year", year.toString(), years.map { it.toString() }, { year = it.toInt() })
        rows.forEachIndexed { index, row ->
            val ref = studies.find { it.code == row.optString("code") }
            val predicted = ref?.let { predictions[StudyScore.studyKey(it.studyName)] }
            OutlinedCard { Column(Modifier.padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                ChoiceField("Study", ref?.studyName.orEmpty(), studies.map { it.studyName }, { name ->
                    update(rows.mapIndexed { i, r -> if (i == index) JSONObject(r.toString()).put("code", studies.first { it.studyName == name }.code) else r }) })
                if (predicted != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(row.optBoolean("usePrediction"), { on -> update(rows.mapIndexed { i, r -> if (i == index) JSONObject(r.toString()).put("usePrediction", on) else r }) })
                    Spacer(Modifier.width(FolioSpacing.dp8)); Text("Use predicted score ($predicted)")
                }
                if (predicted == null || !row.optBoolean("usePrediction")) Field("Raw study score", row.optString("raw"), number = true) { v ->
                    update(rows.mapIndexed { i, r -> if (i == index) JSONObject(r.toString()).put("raw", v) else r }) }
                TextButton({ update(rows.filterIndexed { i, _ -> i != index }) }) { Text("Remove") }
            } }
        }
        OutlinedButton({ update(rows + JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("code", studies.firstOrNull()?.code.orEmpty())
            .put("raw", "").put("usePrediction", true)) }, enabled = studies.isNotEmpty()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add study") }
        estimate?.let { e ->
            ElevatedCard { Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Text(e.label, style = MaterialTheme.typography.displaySmall)
                Text("Aggregate ${"%.2f".format(e.aggregate)}", style = MaterialTheme.typography.bodyMedium)
                val top = maxOf(1.0, e.primary.maxOfOrNull { it.contribution } ?: 1.0)
                e.primary.forEach { com.folio.notes.progress.MeterRow(it.result.studyName, "%.1f".format(it.contribution), (it.contribution / top).toFloat(),
                    supporting = "${it.role} · raw ${"%.0f".format(it.result.raw)}") }
                e.increments.forEach { com.folio.notes.progress.MeterRow(it.result.studyName, "+%.2f".format(it.contribution), (it.contribution / top).toFloat(),
                    MaterialTheme.colorScheme.tertiary, "Increment (10%)") }
                Button({ scope.launch { save(listOf(JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("year", year).put("rows", JSONArray(rows))
                    .put("atarLabel", e.label).put("aggregate", e.aggregate).put("savedAt", com.folio.notes.mistakes.isoTime())) + saved) } }, enabled = enabled) { Text("Save estimate") }
            } }
        } ?: if (rows.isNotEmpty()) Text("Not enough valid studies yet: need four, including English.", style = MaterialTheme.typography.bodySmall) else Unit
        if (saved.isNotEmpty()) {
            val history = saved.reversed().mapNotNull { s -> s.optString("atarLabel").toDoubleOrNull()?.let { "${s.optString("savedAt").take(10)} · ${s.optInt("year")} scaling" to it } }
            if (history.size >= 2) ScoreTrend("ATAR over time", history, "Your saved estimates, oldest first", icon = Icons.Rounded.ShowChart)
            Text("Saved estimates", style = MaterialTheme.typography.titleMedium)
            saved.forEach { s -> ListItem(headlineContent = { Text("ATAR ${s.optString("atarLabel")} · ${s.optInt("year")}") },
                supportingContent = { Text(s.optString("savedAt").take(10)) },
                trailingContent = { TextButton({ scope.launch { save(saved.filter { it.optString("id") != s.optString("id") }) } }, enabled = enabled) { Text("Delete") } }) }
        }
    }
}
