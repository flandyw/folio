@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.folio.notes.*
import com.folio.notes.mistakes.MistakeScheduler
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

@Composable fun ProgressScreen(notes: List<Notebook>, model: FolioViewModel, modifier: Modifier = Modifier,
    onSettings: () -> Unit, onMistakes: () -> Unit, onStudy: () -> Unit, onOpenNotebook: (String) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { (context.applicationContext as FolioApplication).focalProgress }
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var destination by rememberSaveable { mutableStateOf("Overview") }
    var subject by rememberSaveable { mutableStateOf("All subjects") }
    var query by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("All providers") }
    var paperFilter by rememberSaveable { mutableStateOf("All papers") }
    var yearFilter by rememberSaveable { mutableStateOf("All years") }
    var sort by rememberSaveable { mutableStateOf("Newest first") }
    var completion by rememberSaveable { mutableStateOf("All papers") }
    var listSection by rememberSaveable { mutableStateOf("Progression") }
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var mistakeId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showDifficulty by rememberSaveable { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val local = remember(notes) { notebookExams(notes) }
    val exams = remember(state.cache.rows, local) {
        (state.cache.exams + local.filterNot { "attempts:${it.id}" in state.cache.rows }).sortedByDescending { it.completedAt }
    }
    val subjects = remember(exams, notes, state.catalog, state.cache.rows) { (exams.map { it.subject } + state.catalog.exams.map { it.subject } +
        state.catalog.references.map { it.subject } + notes.filter { it.exam.isTagged }.map(::focalNotebookSubject) +
        (state.cache.value("subjects") as? JSONArray)?.strings().orEmpty()).distinct().sorted() }
    val filtered = remember(exams, subject, query, provider, paperFilter, yearFilter, sort) {
        val matching = exams.filter { (subject == "All subjects" || it.subject == subject) &&
            (provider == "All providers" || it.provider == provider) && (paperFilter == "All papers" || it.paper == paperFilter) &&
            (yearFilter == "All years" || it.examYear.toString() == yearFilter) &&
            "${it.title} ${it.subject} ${it.provider} ${it.paper} ${it.examYear} ${it.json.optString("comment")}".contains(query.trim(), true) }
        when (sort) { "Oldest first" -> matching.sortedBy { it.completedAt }; "Highest mark" -> matching.sortedByDescending { it.percentage }
            "Lowest mark" -> matching.sortedBy { it.percentage }; else -> matching }
    }
    val official = remember(state.catalog, subject, query, completion, exams, state.cache.completedIds) {
        state.catalog.exams.filter { exam ->
            val done = exam.logged(exams) != null || exam.resource.url in state.cache.completedIds
            (subject == "All subjects" || exam.subject == subject) &&
                "${exam.subject} ${exam.year} ${exam.paper} ${exam.provider}".contains(query.trim(), true) &&
                (completion == "All papers" || if (completion == "Completed") done else !done)
        }.sortedWith(compareByDescending<OfficialExam> { it.year }.thenBy { it.subject }.thenBy { it.paper })
    }
    val mistakes = remember(state.cache.rows) { state.cache.mistakes }
    val enabled = !state.loading && state.readable && !busy
    LaunchedEffect(lifecycle, state.userId) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { manager.sync(); delay(30_000) } }
    }
    var previousUser by remember { mutableStateOf(state.userId) }
    LaunchedEffect(state.userId) {
        if (previousUser != state.userId) { draft = null; detailId = null; mistakeId = null; deleteId = null; showPlan = false; showDifficulty = false; actionError = null }
        previousUser = state.userId
    }
    fun shareReport() {
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { File(context.cacheDir, "exports/progress-${System.currentTimeMillis()}.html").apply {
                    check(parentFile!!.isDirectory || parentFile!!.mkdirs()); writeText(progressReport(exams, state.catalog.references, state.cache.difficulty))
                } }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/html"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("Progress report", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share progress report"))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { actionError = "Could not share the progress report. Try again." }
            finally { busy = false }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Progress", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton({ showAccount = true }) { Icon(Icons.Rounded.ManageAccounts, "Focal account") }
            IconButton({ manager.sync() }, enabled = state.userId != null && !state.syncing && !state.loading) { Icon(Icons.Rounded.Sync, "Sync exam results") }
            IconButton(::shareReport, enabled = enabled && exams.isNotEmpty()) { Icon(Icons.Rounded.IosShare, "Export progress report") }
            IconButton(onSettings) { Icon(Icons.Rounded.Tune, "Settings") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val gridState = rememberLazyGridState()
            LaunchedEffect(destination, listSection) { gridState.scrollToItem(0) }
            LazyVerticalGrid(GridCells.Adaptive(360.dp), Modifier.fillMaxSize().guardUiTouches(), state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(when { state.loading -> "Loading saved exams…"; state.syncing -> "Syncing with Focal…"
                            state.userId == null -> "Saved on this device · connect Focal to sync"
                            state.cache.outbox.isNotEmpty() -> "${state.cache.outbox.size} saved changes waiting to sync"
                            else -> "Focal connected · ${state.cache.lastSynced?.take(10) ?: "ready to sync"}" },
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button({ draft = "{}" }, enabled = enabled) { Icon(Icons.Rounded.Add, null); Text("Log exam") }
                    }
                }
                (state.error ?: actionError ?: state.catalogError)?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.padding(16.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (!state.readable) TextButton(manager::retryLoad, enabled = !state.loading) { Text("Retry loading saved data") }
                        }
                    }
                } }
                state.cache.conflicts.forEach { (key, remote) -> item(span = { GridItemSpan(maxLineSpan) }) {
                    ProgressCard("Changed on another device", "${remote.entity} · ${remote.payload?.optString("title")?.ifBlank { remote.rowId } ?: remote.rowId}. Your local edit is still saved.") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ scope.launch { manager.resolve(key, false, state.userId) } }) { Text("Use Focal version") }
                            Button({ scope.launch { manager.resolve(key, true, state.userId) } }, enabled = remote.operation != "delete") { Text("Keep local edit") }
                        }
                    }
                } }
                if (destination != "Overview") item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceField("Subject", subject, listOf("All subjects") + subjects, { subject = it })
                        if (destination != "Insights") OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search exams") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Search, null) })
                    }
                }
                when (destination) {
                    "Overview" -> {
                        item(span = { GridItemSpan(maxLineSpan) }) { OverviewMetrics(exams, mistakes, state.cache.difficulty) }
                        item { ProgressCard("Next step") {
                            val due = MistakeScheduler.getDueMistakes(mistakes)
                            Text(when { exams.isEmpty() -> "Log your first completed practice paper."
                                due.isNotEmpty() -> "${due.size} mistake cards are due for review."
                                mistakes.none { it.attemptId == exams.first().id } -> "Capture the mistakes from your latest paper."
                                else -> "Choose the next paper in your progression." })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton({ if (exams.isEmpty()) draft = "{}" else if (due.isNotEmpty()) onMistakes() else { mistakeId = exams.first().id } }, enabled = enabled) { Text(if (due.isNotEmpty()) "Review mistakes" else if (exams.isEmpty()) "Log first exam" else "Log mistake") }
                                TextButton({ destination = "Lists" }) { Text("View lists") }
                            }
                        } }
                        item { MetricBars("Subjects", exams.groupBy { it.subject }.map { (s, attempts) -> "$s (${attempts.size})" to performanceAverage(attempts, state.cache.difficulty) },
                            subtitle = "VCAA-aligned, relevance-weighted averages. Planning estimates.", maximum = 100.0) }
                        item(span = { GridItemSpan(maxLineSpan) }) { ProgressCard("Recent exams", "Your latest five results") {
                            if (exams.isEmpty()) Text("No results yet. Notebook marks with a known total appear here too.")
                            exams.take(5).forEach { exam -> ExamSummary(exam, state.catalog.references) { detailId = exam.id } }
                            TextButton({ destination = "Exams" }) { Text("View all ${exams.size} exams") }
                        } }
                        item { ActivityCard(exams) }
                        item { CoverageCard(exams) }
                        item { ProgressCard("Notebook results", "Notebook marks appear in Progress immediately. Add them to the exam log to sync them with Focal.") {
                            Text("${local.size} recorded notebook marks · ${local.count { "attempts:${it.id}" !in state.cache.rows }} ready to add")
                            OutlinedButton({ val owner = state.userId; val batch = local.filterNot { "attempts:${it.id}" in state.cache.rows }; busy = true; scope.launch {
                                try { for (exam in batch) if (!manager.save("attempts", exam.id, exam.json, owner)) break }
                                finally { busy = false }
                            } }, enabled = enabled && local.any { "attempts:${it.id}" !in state.cache.rows }) { Text("Add notebook results to log") }
                            if (state.userId != null) TextButton({ val owner = state.userId!!; busy = true; scope.launch {
                                try { if (manager.copyDeviceLogs(owner)) actionError = "Device exams and mistake cards copied to this account." }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { actionError = "Could not copy the device logs. Retry." }
                                finally { busy = false }
                            } }, enabled = enabled) { Text("Copy device logs to this account") }
                        } }
                        val upcoming = notes.filter { (it.exam.examDate ?: 0) >= System.currentTimeMillis() }.sortedBy { it.exam.examDate }
                        if (upcoming.isNotEmpty()) item { ProgressCard("Upcoming exams") { upcoming.take(8).forEach { note ->
                            TextButton({ onOpenNotebook(note.id) }) { Text("${note.exam.subjectLabel.ifBlank { note.title }} · ${examDate(com.folio.notes.mistakes.isoTime(note.exam.examDate!!))}") }
                        } } }
                    }
                    "Exams" -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChoiceField("Provider", provider, listOf("All providers") + exams.map { it.provider }.distinct().sorted(), { provider = it })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) { ChoiceField("Paper", paperFilter, listOf("All papers") + exams.map { it.paper }.distinct().sorted(), { paperFilter = it }) }
                                    Box(Modifier.weight(1f)) { ChoiceField("Year", yearFilter, listOf("All years") + exams.map { it.examYear.toString() }.distinct().sortedDescending(), { yearFilter = it }) }
                                }
                                ChoiceField("Sort", sort, listOf("Newest first", "Oldest first", "Highest mark", "Lowest mark"), { sort = it })
                                Text("${filtered.size} results", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { ProgressCard("No matching exams") { Text("Log a paper or change the filters.") } }
                        items(filtered, key = { "exam:${it.id}" }) { exam -> ProgressCard(exam.title, "${exam.paper} · ${exam.completedAt.take(10)}") {
                            ExamSummary(exam, state.catalog.references) { detailId = exam.id }
                            Text(exam.json.optString("comment"), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton({ draft = exam.originalJson; detailId = null }, enabled = enabled && "attempts:${exam.id}" !in state.cache.conflicts) { Text("Edit") }
                                TextButton({ mistakeId = exam.id }, enabled = enabled) { Text("Add mistake") }
                                IconButton({ deleteId = exam.id }, enabled = enabled) { Icon(Icons.Rounded.DeleteOutline, "Delete ${exam.title}") }
                            }
                        } }
                    }
                    "Lists" -> {
                        item(span = { GridItemSpan(maxLineSpan) }) { ChoiceField("List", listSection, listOf("Progression", "VCAA library", "Notebooks"), { listSection = it }) }
                        when (listSection) {
                            "Progression" -> {
                                item(span = { GridItemSpan(maxLineSpan) }) { ProgressCard(state.cache.progression?.optString("name") ?: "Exam progression", "Plan practice across all your subjects. Completion follows your logged results.") {
                                    Button({ showPlan = true }, enabled = enabled && "user_state:examProgression" !in state.cache.conflicts) { Text(if (state.cache.progression == null) "Create or import list" else "Edit list") }
                                } }
                                val plan = state.cache.progression?.optJSONArray("exams")?.objects().orEmpty()
                                val done = exams.map { it.paperKey() }.toSet()
                                plan.groupBy { it.optString("subject") }.filterKeys { subject == "All subjects" || subject == it }.forEach { (s, papers) -> item {
                                    ProgressCard(s, "${papers.count { paperKey(it) in done }} / ${papers.size} complete") {
                                        papers.filter { "${it.optString("provider")} ${it.optInt("examYear")} ${it.optString("paper")} ${it.optString("phase")}".contains(query, true) }.forEach { paper ->
                                            val complete = paperKey(paper) in done
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(if (complete) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (complete) "Completed" else "To do", Modifier.size(20.dp))
                                                Column(Modifier.weight(1f).padding(8.dp)) {
                                                    Text("${paper.optString("provider")} ${paper.optInt("examYear")} · ${paper.optString("paper")}", style = MaterialTheme.typography.bodyMedium)
                                                    Text(paper.optString("phase"), style = MaterialTheme.typography.labelSmall)
                                                }
                                                TextButton({ if (complete) detailId = exams.firstOrNull { it.paperKey() == paperKey(paper) }?.id
                                                    else draft = JSONObject(paper.toString()).put("rawMax", paper.optDouble("marks", 100.0)).toString() }, enabled = enabled) { Text(if (complete) "Result" else "Log") }
                                            }
                                        }
                                    }
                                } }
                            }
                            "VCAA library" -> {
                                item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Offline catalogue · ${state.catalog.generatedAt.take(10)}. Check the current study design before using older papers.", style = MaterialTheme.typography.bodySmall)
                                    ChoiceField("Completion", completion, listOf("All papers", "To do", "Completed"), { completion = it })
                                    Text("${official.size} papers", style = MaterialTheme.typography.labelMedium)
                                } }
                                items(official, key = { it.resource.url }) { exam -> OfficialExamCard(exam, exams, state.cache.completedIds,
                                    onLog = { draft = exam.draft().toString() }, onResult = { detailId = it },
                                    onToggle = { val owner = state.userId; val ids = state.cache.completedIds.toMutableSet(); scope.launch {
                                        if (!ids.add(exam.resource.url)) ids.remove(exam.resource.url)
                                        manager.saveValue("completedExamIds", JSONArray(ids.toList()), owner)
                                    } }, onTimed = {
                                        val preset = exam.draft()
                                        val note = notes.firstOrNull { it.exam.year == exam.year && comparisonName(focalNotebookSubject(it)) == comparisonName(exam.subject) &&
                                            comparisonName(it.exam.type?.label ?: "Exam") == comparisonName(exam.paper) && comparisonName(it.exam.company) == comparisonName(exam.provider) }
                                        if (note != null) onOpenNotebook(note.id)
                                        else {
                                            val study = VceSubject.match(exam.subject)
                                            model.create("${exam.provider} ${exam.year} ${exam.subject} · ${exam.paper}", 0, study?.paper ?: Paper.RULED,
                                                ExamTags(subjectText = exam.subject, year = exam.year, company = exam.provider,
                                                    type = when (exam.paper) { "Exam 1" -> ExamType.EXAM_1; "Exam 2" -> ExamType.EXAM_2; else -> null }, marksTotal = preset.optInt("rawMax")), infinite = true)
                                        }
                                        val number = Regex("\\d+").find(exam.paper)?.value?.toIntOrNull()
                                        val writing = when (comparisonName(exam.subject)) { "mathematical methods", "specialist mathematics" -> if (number == 1) 60 else 120
                                            "general mathematics" -> 90; "english", "english as an additional language" -> 180
                                            "biology", "chemistry", "physics", "psychology" -> 150; else -> 120 }
                                        model.startTimer(ExamTimerPreset("${exam.subject} · ${exam.paper}", writing * 60, 15 * 60))
                                    }, enabled = enabled) }
                                if (official.isEmpty()) item { ProgressCard("No matching papers") { Text("Choose another subject or completion filter.") } }
                            }
                            else -> {
                                val notebookList = notes.filter { it.exam.isTagged && (subject == "All subjects" || comparisonName(focalNotebookSubject(it)) == comparisonName(subject)) &&
                                    "${it.title} ${it.exam.summaryLine()}".contains(query, true) }.sortedByDescending { it.exam.year ?: 0 }
                                items(notebookList, key = { "note:${it.id}" }) { note -> ProgressCard(note.title, note.exam.summaryLine()) {
                                    Text("${note.exam.status.label} · ${note.attempts.size} attempts · ${note.pages.count { it.redoFlag }} pages to redo")
                                    TextButton({ onOpenNotebook(note.id) }) { Text("Open notebook") }
                                } }
                                if (notebookList.isEmpty()) item { ProgressCard("No exam notebooks") { Text("Tag a notebook with its subject and exam details to include it here.") } }
                            }
                        }
                    }
                    else -> insightsItems(exams.filter { subject == "All subjects" || it.subject == subject }, mistakes, state.catalog.references,
                        state.cache.difficulty, onDifficulty = { showDifficulty = true }, onMistakes = onMistakes)
                }
            }
            ProgressToolbar(destination, { destination = it }, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
    if (showAccount) FocalAccountPanel({ showAccount = false }, onMistakes, onStudy)
    if (showPlan) ProgressionDialog(state.cache.progression?.toString(), state.catalog, manager, state.userId, { showPlan = false })
    if (showDifficulty) DifficultyDialog(state.cache.difficulty, manager, state.userId, { showDifficulty = false })
    draft?.let { raw -> LogExamDialog(raw, state.catalog, state.userId, manager, { draft = null }) { id, addMistake ->
        manager.state.value.cache.exams.find { it.id == id }?.let { saved ->
            val note = notes.find { it.id == saved.notebookId }
            val original = note?.attempts?.find { it.id == id }
            if (original != null && saved.rawScore % 1 == 0.0 && saved.rawMax % 1 == 0.0 && saved.rawMax <= Int.MAX_VALUE) {
                val date = runCatching { java.time.Instant.parse(saved.completedAt).toEpochMilli() }.getOrNull()
                    ?: saved.date?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: original.date
                model.updateAttempt(note.id, original.copy(score = saved.rawScore.toInt(), total = saved.rawMax.toInt(), date = date))
            }
        }
        draft = null; if (addMistake) mistakeId = id else detailId = id
    } }
    exams.find { it.id == detailId }?.let { exam -> ExamDetailDialog(exam, state.catalog, mistakes.filter { it.attemptId == exam.id },
        { detailId = null }, { detailId = null; draft = exam.originalJson }, { mistakeId = exam.id }, onMistakes,
        onOpenNotebook = { exam.notebookId?.let(onOpenNotebook) }, hasNotebook = notes.any { it.id == exam.notebookId }) }
    exams.find { it.id == mistakeId }?.let { exam -> LogMistakeDialog(exam, state.userId, manager, { mistakeId = null }) }
    exams.find { it.id == deleteId }?.let { exam -> AlertDialog(onDismissRequest = { if (!busy) deleteId = null },
        title = { Text("Delete exam result?") }, text = { Text("${exam.title} · ${exam.paper}. The linked mistake cards and notebook will be kept.") },
        confirmButton = { TextButton({ val owner = state.userId; busy = true; scope.launch {
            try { if (manager.save("attempts", exam.id, null, owner)) { exam.notebookId?.let { model.deleteAttempt(it, exam.id) }; deleteId = null } }
            finally { busy = false }
        } }, enabled = !busy) { Text("Delete") } }, dismissButton = { TextButton({ deleteId = null }, enabled = !busy) { Text("Cancel") } }) }
}

@Composable private fun ProgressToolbar(selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    val standard = FloatingToolbarDefaults.standardFloatingToolbarColors()
    val vibrant = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
    Surface(modifier.widthIn(max = 440.dp).fillMaxWidth(), shape = CircleShape, color = standard.toolbarContainerColor,
        contentColor = standard.toolbarContentColor, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(8.dp).selectableGroup()) {
            listOf("Overview" to Icons.Rounded.Insights, "Exams" to Icons.AutoMirrored.Rounded.Assignment, "Lists" to Icons.AutoMirrored.Rounded.ListAlt, "Insights" to Icons.Rounded.BarChart).forEach { (label, icon) ->
                val active = selected == label
                Surface(Modifier.weight(1f).clip(CircleShape).selectable(active, onClick = { onSelect(label) }, role = Role.Tab),
                    shape = CircleShape, color = if (active) vibrant.toolbarContainerColor else Color.Transparent,
                    contentColor = if (active) vibrant.toolbarContentColor else standard.toolbarContentColor) {
                    Column(Modifier.heightIn(min = 64.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                        Icon(icon, null); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable internal fun ExamSummary(exam: LoggedExam, references: List<ExamReference>, onClick: () -> Unit) {
    val ref = remember(exam, references) { referenceFor(exam, references) }
    val analysis = ref?.let { analyseExam(exam, it) }
    Surface(onClick, shape = FolioShapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${exam.subject} · ${exam.provider} ${exam.examYear} · ${exam.paper}", style = MaterialTheme.typography.bodyMedium)
            Text("${exam.rawScore.display()}/${exam.rawMax.display()} · ${exam.percentage.display()}% · ${exam.completedAt.take(10)}", style = MaterialTheme.typography.labelLarge)
            if (analysis != null) Text("Est. ${analysis.grade ?: "—"} · ${analysis.percentile?.display() ?: "—"} percentile · ${ref!!.year} VCAA", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun OfficialExamCard(exam: OfficialExam, attempts: List<LoggedExam>, completed: Set<String>,
    onLog: () -> Unit, onResult: (String) -> Unit, onToggle: () -> Unit, onTimed: () -> Unit, enabled: Boolean) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    fun open(url: String) { try { uri.openUri(url) } catch (_: Exception) { linkError = true } }
    val logged = exam.logged(attempts); val done = logged != null || exam.resource.url in completed
    ProgressCard(exam.subject, "${exam.provider} ${exam.year} · ${exam.paper} · ${if (done) "Completed" else "To do"}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ open(exam.resource.url) }) { Text("Exam paper") }
            Button(onLog, enabled = enabled) { Text(if (logged != null) "Log again" else "Log result") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onTimed, enabled = enabled) { Text("Start timed") }
            if (logged != null) TextButton({ onResult(logged.id) }) { Text("${logged.percentage.display()}% · Compare") }
            else TextButton(onToggle, enabled = enabled) { Text(if (done) "Mark not done" else "Mark done") }
        }
        exam.companions.forEach { resource -> TextButton({ open(resource.url) }) { Text(when (resource.kind) { "report" -> "Examiner report"; "specification" -> "Specifications"; else -> "Sample paper" }) } }
        if (linkError) Text("Could not open this link. Check that a browser is installed.", color = MaterialTheme.colorScheme.error)
    }
}
