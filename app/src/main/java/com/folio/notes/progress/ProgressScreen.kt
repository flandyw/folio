@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import com.folio.notes.FolioSpacing
import com.folio.notes.EmptyHint
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.folio.notes.*
import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.MistakeScheduler
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal const val ALL_SUBJECTS = "All subjects"

/** Full-width grid item: headers, banners and anything that should not sit beside a card. */
internal fun LazyGridScope.wide(key: Any? = null, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key, span = { GridItemSpan(maxLineSpan) }, content = content)

@Composable fun ProgressScreen(notes: List<Notebook>, model: FolioViewModel, modifier: Modifier = Modifier,
    onSettings: () -> Unit, onMistakes: () -> Unit, onStudy: () -> Unit, onOpenNotebook: (String) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { (context.applicationContext as FolioApplication).focalProgress }
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var destination by rememberSaveable { mutableStateOf("Overview") }
    var subject by rememberSaveable { mutableStateOf(ALL_SUBJECTS) }
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
    var showResults by rememberSaveable { mutableStateOf(false) }
    var showDifficulty by rememberSaveable { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val local = remember(notes) { notebookExams(notes) }
    // A result logged earlier from a notebook later relabelled SAC (or topic test, notes) is no longer an exam.
    val notExamNotebooks = remember(notes) { notes.filterNot { it.countsAsExam }.map { it.id }.toSet() }
    val exams = remember(state.cache.rows, local, notExamNotebooks) {
        (state.cache.exams.filterNot { it.notebookId in notExamNotebooks } + local.filterNot { "attempts:${it.id}" in state.cache.rows })
            .sortedByDescending { it.completedAt }
    }
    val subjects = remember(exams, notes, state.catalog, state.cache.rows) { (exams.map { it.subject } + state.catalog.exams.map { it.subject } +
        state.catalog.references.map { it.subject } + notes.filter { it.exam.isTagged }.map(::focalNotebookSubject) +
        (state.cache.value("subjects") as? JSONArray)?.strings().orEmpty()).distinct().sorted() }
    val filtered = remember(exams, subject, query, provider, paperFilter, yearFilter, sort) {
        val matching = exams.filter { (subject == ALL_SUBJECTS || it.subject == subject) &&
            (provider == "All providers" || it.provider == provider) && (paperFilter == "All papers" || it.paper == paperFilter) &&
            (yearFilter == "All years" || it.examYear.toString() == yearFilter) &&
            "${it.title} ${it.subject} ${it.provider} ${it.paper} ${it.examYear} ${it.json.optString("comment")}".contains(query.trim(), true) }
        when (sort) { "Oldest first" -> matching.sortedBy { it.completedAt }; "Highest mark" -> matching.sortedByDescending { it.percentage }
            "Lowest mark" -> matching.sortedBy { it.percentage }; else -> matching }
    }
    val official = remember(state.catalog, subject, query, completion, exams, state.cache.completedIds) {
        state.catalog.exams.filter { exam ->
            val done = exam.logged(exams) != null || exam.resource.url in state.cache.completedIds
            (subject == ALL_SUBJECTS || exam.subject == subject) &&
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
    /** Keep the notebook mark consistent when its Progress log entry is saved or corrected. */
    fun mirrorToNotebook(id: String) {
        manager.state.value.cache.exams.find { it.id == id }?.let { saved ->
            val note = notes.find { it.id == saved.notebookId }
            val original = note?.attempts?.find { it.id == id }
            if (original != null && saved.rawScore % 1 == 0.0 && saved.rawMax % 1 == 0.0 && saved.rawMax <= Int.MAX_VALUE) {
                val date = runCatching { java.time.Instant.parse(saved.completedAt).toEpochMilli() }.getOrNull()
                    ?: saved.date?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: original.date
                model.updateAttempt(note.id, original.copy(score = saved.rawScore.toInt(), total = saved.rawMax.toInt(), date = date))
            }
        }
    }
    fun copyDeviceLogs() {
        val owner = state.userId ?: return; busy = true
        scope.launch {
            try { if (manager.copyDeviceLogs(owner)) actionError = "Device exams and mistake cards copied to this account." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { actionError = "Could not copy the device logs. Retry." }
            finally { busy = false }
        }
    }
    fun startTimed(exam: OfficialExam) {
        val preset = exam.draft()
        val note = notes.firstOrNull { it.exam.year == exam.year && comparisonName(focalNotebookSubject(it)) == comparisonName(exam.subject) &&
            comparisonName(it.exam.type?.label ?: "Exam") == comparisonName(exam.paper) && comparisonName(it.exam.company) == comparisonName(exam.provider) }
        if (note != null) onOpenNotebook(note.id)
        else {
            val study = VceSubject.match(exam.subject)
            model.create("${exam.provider} ${exam.year} ${exam.subject} · ${exam.paper}", 0, study?.paper ?: Paper.RULED,
                ExamTags(subjectText = exam.subject, year = exam.year, company = exam.provider,
                    type = when (exam.paper) { "Exam 1" -> ExamType.EXAM_1; "Exam 2" -> ExamType.EXAM_2; else -> ExamType.EXAM }, marksTotal = preset.optInt("rawMax")), infinite = true)
        }
        val number = Regex("\\d+").find(exam.paper)?.value?.toIntOrNull()
        val writing = when (comparisonName(exam.subject)) { "mathematical methods", "specialist mathematics" -> if (number == 1) 60 else 120
            "general mathematics" -> 90; "english", "english as an additional language" -> 180
            "biology", "chemistry", "physics", "psychology" -> 150; else -> 120 }
        model.startTimer(ExamTimerPreset("${exam.subject} · ${exam.paper}", writing * 60, 15 * 60))
    }
    fun toggleCompleted(exam: OfficialExam) {
        val owner = state.userId; val ids = state.cache.completedIds.toMutableSet()
        scope.launch {
            if (!ids.add(exam.resource.url)) ids.remove(exam.resource.url)
            manager.saveValue("completedExamIds", JSONArray(ids.toList()), owner)
        }
    }

    Column(modifier.fillMaxSize()) {
        ProgressHeader(state, enabled = enabled && exams.isNotEmpty(), onSync = manager::sync, onShare = ::shareReport,
            onAccount = { showAccount = true }, onSettings = onSettings)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val gridState = rememberLazyGridState()
            LaunchedEffect(destination, listSection) { gridState.scrollToItem(0) }
            val focusManager = LocalFocusManager.current
            LaunchedEffect(gridState) { snapshotFlow { gridState.isScrollInProgress }.collect { if (it) focusManager.clearFocus() } }
            LazyVerticalGrid(GridCells.Adaptive(340.dp), Modifier.fillMaxSize().guardUiTouches(), state = gridState,
                contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, top = 0.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                (state.error ?: actionError ?: state.catalogError)?.let { message -> wide("banner:error") {
                    ProgressBanner(Icons.Rounded.ErrorOutline, message, error = state.error != null || !state.readable) {
                        if (!state.readable) TextButton(manager::retryLoad, enabled = !state.loading) { Text("Retry loading") }
                        else if (actionError != null) TextButton({ actionError = null }) { Text("Dismiss") }
                    }
                } }
                state.cache.conflicts.forEach { (key, remote) -> wide("conflict:$key") {
                    ProgressBanner(Icons.Rounded.SyncProblem, "Changed on another device · ${remote.entity} · " +
                        "${remote.payload?.optString("title")?.ifBlank { remote.rowId } ?: remote.rowId}. Your local edit is still saved.") {
                        TextButton({ scope.launch { manager.resolve(key, false, state.userId) } }) { Text("Use Focal version") }
                        Button({ scope.launch { manager.resolve(key, true, state.userId) } }, enabled = remote.operation != "delete",
                            shapes = ButtonDefaults.shapes()) { Text("Keep local edit") }
                    }
                } }
                when (destination) {
                    "Overview" -> overviewItems(exams, mistakes, local, notes, state, enabled,
                        onLog = { draft = "{}" }, onDetail = { detailId = it }, onLogMistake = { mistakeId = it }, onMistakes = onMistakes,
                        onDestination = { destination = it }, onSubject = { subject = it; destination = "Insights" },
                        onOpenNotebook = onOpenNotebook, onLogNotebooks = { showResults = true }, onCopyLogs = ::copyDeviceLogs)
                    "Exams" -> {
                        wide("exam-filters") {
                            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                SearchField(query, { query = it }, "Search titles, papers and comments")
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    MenuChip("Subject", subject, listOf(ALL_SUBJECTS) + subjects, { subject = it }, Icons.Rounded.School)
                                    MenuChip("Provider", provider, listOf("All providers") + exams.map { it.provider }.distinct().sorted(), { provider = it })
                                    MenuChip("Paper", paperFilter, listOf("All papers") + exams.map { it.paper }.distinct().sorted(), { paperFilter = it })
                                    MenuChip("Year", yearFilter, listOf("All years") + exams.map { it.examYear.toString() }.distinct().sortedDescending(), { yearFilter = it })
                                    MenuChip("Sort", sort, listOf("Newest first", "Oldest first", "Highest mark", "Lowest mark"), { sort = it }, Icons.AutoMirrored.Rounded.Sort)
                                    if (subject != ALL_SUBJECTS || provider != "All providers" || paperFilter != "All papers" || yearFilter != "All years" || query.isNotEmpty())
                                        AssistChip({ subject = ALL_SUBJECTS; provider = "All providers"; paperFilter = "All papers"; yearFilter = "All years"; query = "" },
                                            { Text("Clear") }, leadingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(AssistChipDefaults.IconSize)) })
                                }
                                Text(if (filtered.isEmpty()) "No results" else "${filtered.size} ${if (filtered.size == 1) "result" else "results"} · " +
                                    "${filtered.map { it.percentage }.average().display()}% raw average",
                                    Modifier.padding(start = FolioSpacing.dp4), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (filtered.isEmpty()) wide("exams-empty") {
                            EmptyState(Icons.AutoMirrored.Rounded.Assignment, if (exams.isEmpty()) "No results yet" else "No matching exams",
                                if (exams.isEmpty()) "Log a completed practice paper. Notebook marks with a known total appear here too." else "Change the filters or search.") {
                                if (exams.isEmpty()) Button({ draft = "{}" }, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text("Log exam") }
                            }
                        }
                        val byMonth = sort == "Newest first" || sort == "Oldest first"
                        (if (byMonth) filtered.groupBy { it.completedAt.take(7) } else mapOf("" to filtered)).forEach { (month, group) ->
                            if (byMonth) wide("month:$month") { SectionHeader(monthLabel(month), "${group.size} ${if (group.size == 1) "paper" else "papers"}") }
                            items(group, key = { "exam:${it.id}" }) { exam ->
                                ExamRow(exam, state.catalog.references, { detailId = exam.id }, note = exam.json.optString("comment")) {
                                    ExamActions(exam.title, enabled, editable = "attempts:${exam.id}" !in state.cache.conflicts,
                                        onEdit = { draft = exam.originalJson; detailId = null }, onMistake = { mistakeId = exam.id }, onDelete = { deleteId = exam.id })
                                }
                            }
                        }
                    }
                    "Lists" -> {
                        wide("list-controls") {
                            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                FolioButtonGroup(Modifier.fillMaxWidth()) {
                                    listOf("Progression" to Icons.Rounded.Route, "VCAA library" to Icons.Rounded.LocalLibrary, "Notebooks" to Icons.AutoMirrored.Rounded.MenuBook).forEach { (label, icon) ->
                                        toggleableItem(listSection == label, label, { listSection = label }, icon = { Icon(icon, null, Modifier.size(18.dp)) })
                                    }
                                }
                                SearchField(query, { query = it }, when (listSection) { "Progression" -> "Search planned papers"; "VCAA library" -> "Search official papers"; else -> "Search exam notebooks" })
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    MenuChip("Subject", subject, listOf(ALL_SUBJECTS) + subjects, { subject = it }, Icons.Rounded.School)
                                    if (listSection == "VCAA library") MenuChip("Status", completion, listOf("All papers", "To do", "Completed"), { completion = it }, Icons.Rounded.TaskAlt)
                                }
                            }
                        }
                        when (listSection) {
                            "Progression" -> progressionItems(state.cache.progression, exams, subject, query, enabled,
                                canEdit = enabled && "user_state:examProgression" !in state.cache.conflicts, onEdit = { showPlan = true },
                                onResult = { detailId = it }, onLog = { draft = it })
                            "VCAA library" -> {
                                wide("library-note") {
                                    Text("${official.size} papers · offline catalogue ${state.catalog.generatedAt.take(10)}. Check the current study design before using older papers.",
                                        Modifier.padding(start = FolioSpacing.dp4), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                items(official, key = { it.resource.url }) { exam -> OfficialExamCard(exam, exams, state.cache.completedIds,
                                    onLog = { draft = exam.draft().toString() }, onResult = { detailId = it }, onToggle = { toggleCompleted(exam) },
                                    onTimed = { startTimed(exam) }, enabled = enabled) }
                                if (official.isEmpty()) wide("library-empty") {
                                    EmptyState(Icons.Rounded.LocalLibrary, "No matching papers", "Choose another subject or status.")
                                }
                            }
                            else -> {
                                val notebookList = notes.filter { it.exam.isTagged && it.countsAsExam && (subject == ALL_SUBJECTS || comparisonName(focalNotebookSubject(it)) == comparisonName(subject)) &&
                                    "${it.title} ${it.exam.summaryLine()}".contains(query, true) }.sortedByDescending { it.exam.year ?: 0 }
                                items(notebookList, key = { "note:${it.id}" }) { note -> NotebookRow(note) { onOpenNotebook(note.id) } }
                                if (notebookList.isEmpty()) wide("notebooks-empty") {
                                    EmptyState(Icons.AutoMirrored.Rounded.MenuBook, "No exam notebooks", "Tag a notebook with its subject and exam details to include it here.")
                                }
                            }
                        }
                    }
                    else -> {
                        wide("insight-filters") {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                MenuChip("Subject", subject, listOf(ALL_SUBJECTS) + subjects, { subject = it }, Icons.Rounded.School)
                                AssistChip({ showDifficulty = true }, { Text("Provider difficulty") }, leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(AssistChipDefaults.IconSize)) })
                            }
                        }
                        insightsItems(exams.filter { subject == ALL_SUBJECTS || it.subject == subject }, mistakes, state.catalog.references,
                            state.cache.difficulty, onMistakes = onMistakes)
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12, Alignment.CenterHorizontally)) {
                ProgressToolbar(destination, { destination = it }, Modifier.weight(1f, fill = false).widthIn(max = 400.dp))
                FloatingActionButton({ if (enabled) draft = "{}" }, shape = FolioShapes.large,
                    containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Icon(Icons.Rounded.Add, "Log exam")
                }
            }
        }
    }
    if (showAccount) FocalAccountPanel({ showAccount = false }, onMistakes, onStudy)
    if (showPlan) ProgressionDialog(state.cache.progression?.toString(), state.catalog, exams, manager, state.userId, { showPlan = false })
    if (showResults) {
        val pending = remember(local, state.cache.rows) { local.filterNot { "attempts:${it.id}" in state.cache.rows } }
        NotebookResultsDialog(pending, state.cache.exams, subjects, manager, state.userId, { showResults = false }) { ids -> ids.forEach(::mirrorToNotebook) }
    }
    if (showDifficulty) DifficultyDialog(state.cache.difficulty, manager, state.userId, { showDifficulty = false })
    draft?.let { raw -> LogExamDialog(raw, state.catalog, state.userId, manager, { draft = null }) { id, addMistake ->
        mirrorToNotebook(id)
        draft = null; if (addMistake) mistakeId = id else detailId = id
    } }
    exams.find { it.id == detailId }?.let { exam -> ExamDetailDialog(exam, state.catalog, mistakes.filter { it.attemptId == exam.id },
        { detailId = null }, { detailId = null; draft = exam.originalJson }, { mistakeId = exam.id }, onMistakes,
        onOpenNotebook = { exam.notebookId?.let(onOpenNotebook) }, hasNotebook = notes.any { it.id == exam.notebookId }) }
    exams.find { it.id == mistakeId }?.let { exam -> LogMistakeDialog(exam, state.userId, manager, { mistakeId = null }) }
    exams.find { it.id == deleteId }?.let { exam -> AlertDialog(onDismissRequest = { if (!busy) deleteId = null },
        icon = { Icon(Icons.Rounded.DeleteOutline, null) },
        title = { Text("Delete exam result?") }, text = { Text("${exam.title} · ${exam.paper}. The linked mistake cards and notebook will be kept.") },
        confirmButton = { TextButton({ val owner = state.userId; busy = true; scope.launch {
            try { if (manager.save("attempts", exam.id, null, owner)) { exam.notebookId?.let { model.deleteAttempt(it, exam.id) }; deleteId = null } }
            finally { busy = false }
        } }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
        dismissButton = { TextButton({ deleteId = null }, enabled = !busy) { Text("Cancel") } }) }
}

// ---- Header and navigation ---------------------------------------------------------------------

@Composable private fun ProgressHeader(state: ProgressState, enabled: Boolean, onSync: () -> Unit, onShare: () -> Unit,
    onAccount: () -> Unit, onSettings: () -> Unit) {
    val (statusIcon, status) = when {
        state.loading -> Icons.Rounded.HourglassTop to "Loading saved exams…"
        state.syncing -> Icons.Rounded.Sync to "Syncing with Focal…"
        state.userId == null -> Icons.Rounded.PhoneAndroid to "Saved on this device · connect Focal to sync"
        state.cache.outbox.isNotEmpty() -> Icons.Rounded.CloudUpload to "${state.cache.outbox.size} changes waiting to sync"
        else -> Icons.Rounded.CloudDone to "Synced${state.cache.lastSynced?.let { " · ${friendlyDate(it)}" } ?: ""}"
    }
    FolioScreenHeading(
        title = "Progress",
        subtitle = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                Icon(statusIcon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    ) {
        IconButton(onSync, enabled = state.userId != null && !state.syncing && !state.loading, shapes = IconButtonDefaults.shapes()) {
            if (state.syncing) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.Sync, "Sync exam results")
        }
        IconButton(onShare, enabled = enabled, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.IosShare, "Export progress report") }
        FocalAccountButton(onAccount, trouble = state.userId != null && (state.error != null || state.cache.conflicts.isNotEmpty()))
        IconButton(onSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Settings") }
    }
}

@Composable private fun ProgressToolbar(selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    val standard = FloatingToolbarDefaults.standardFloatingToolbarColors()
    val vibrant = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
    Surface(modifier, shape = CircleShape, color = standard.toolbarContainerColor,
        contentColor = standard.toolbarContentColor, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(FolioSpacing.dp6).selectableGroup()) {
            listOf("Overview" to Icons.Rounded.SpaceDashboard, "Exams" to Icons.AutoMirrored.Rounded.Assignment,
                "Lists" to Icons.AutoMirrored.Rounded.ListAlt, "Insights" to Icons.Rounded.Insights).forEach { (label, icon) ->
                val active = selected == label
                Surface(Modifier.weight(1f).clip(CircleShape).selectable(active, onClick = { onSelect(label) }, role = Role.Tab),
                    shape = CircleShape, color = if (active) vibrant.toolbarContainerColor else Color.Transparent,
                    contentColor = if (active) vibrant.toolbarContentColor else standard.toolbarContentColor) {
                    Column(Modifier.heightIn(min = 56.dp).padding(horizontal = FolioSpacing.dp4, vertical = FolioSpacing.dp6),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2, Alignment.CenterVertically)) {
                        Icon(icon, null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, unfocusedBorderColor = Color.Transparent))
}

private fun monthLabel(key: String): String = runCatching { YearMonth.parse(key) }.getOrNull()?.let { month ->
    val name = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    if (month.year == LocalDate.now().year) name else "$name ${month.year}"
} ?: "Undated"

// ---- Overview ----------------------------------------------------------------------------------

private fun LazyGridScope.overviewItems(exams: List<LoggedExam>, mistakes: List<ExamTrackMistake>, local: List<LoggedExam>,
    notes: List<Notebook>, state: ProgressState, enabled: Boolean, onLog: () -> Unit, onDetail: (String) -> Unit,
    onLogMistake: (String) -> Unit, onMistakes: () -> Unit, onDestination: (String) -> Unit, onSubject: (String) -> Unit,
    onOpenNotebook: (String) -> Unit, onLogNotebooks: () -> Unit, onCopyLogs: () -> Unit) {
    val settings = state.cache.difficulty
    val due = MistakeScheduler.getDueMistakes(mistakes)
    wide("hero") { OverviewHero(exams, settings, due.size, mistakes, enabled, onLog, onLogMistake, onMistakes, onDestination) }
    wide("tiles") {
        val mature = mistakes.count { MistakeScheduler.getMistakeSchedule(it).resolved }
        StatTiles(listOf(
            StatTileData(Icons.AutoMirrored.Rounded.Assignment, exams.size.toString(), "Practice papers") { onDestination("Exams") },
            StatTileData(Icons.Rounded.EmojiEvents, exams.maxOfOrNull { it.percentage }?.let { "${it.display()}%" } ?: "—", "Best mark"),
            StatTileData(Icons.Rounded.Replay, "${due.size}", "Mistakes due · $mature of ${mistakes.size} mastered", onMistakes),
            StatTileData(Icons.Rounded.School, exams.map { it.subject }.distinct().size.toString(), "Subjects practised"),
        ))
    }
    item("subjects") { SubjectsCard(exams, settings, onSubject) }
    item("recent") {
        ProgressCard("Recent results", icon = Icons.Rounded.History, action = {
            if (exams.isNotEmpty()) TextButton({ onDestination("Exams") }) { Text("All ${exams.size}") }
        }) {
            if (exams.isEmpty()) EmptyHint("No results yet. Notebook marks with a known total appear here too.")
            exams.take(5).forEach { exam -> ExamRow(exam, state.catalog.references, { onDetail(exam.id) }) }
        }
    }
    item("activity") { ActivityCard(exams) }
    val today = LocalDate.now()
    val upcoming = notes.filter { it.countsAsExam && (it.exam.examDate ?: 0) >= System.currentTimeMillis() }.sortedBy { it.exam.examDate }
    if (upcoming.isNotEmpty()) item("upcoming") {
        ProgressCard("Upcoming exams", icon = Icons.Rounded.Event) {
            upcoming.take(6).forEach { note ->
                val date = examDate(com.folio.notes.mistakes.isoTime(note.exam.examDate!!))
                val days = date?.let { ChronoUnit.DAYS.between(today, it) }
                Surface({ onOpenNotebook(note.id) }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                            Column(Modifier.widthIn(min = 52.dp).padding(FolioSpacing.dp6), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (days == 0L) "Today" else "${days ?: "—"}", style = if (days == 0L) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium)
                                if (days != 0L) Text(if (days == 1L) "day" else "days", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(note.exam.subjectLabel.ifBlank { note.title }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(date?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" } ?: "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open notebook", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    val pending = local.filter { "attempts:${it.id}" !in state.cache.rows }
    val ready = pending.size
    val likelyLogged = pending.count { LoggedMatcher.best(ResultFacts.of(it), state.cache.exams)?.level == MatchLevel.LIKELY }
    if (ready > 0 || state.userId != null) item("notebook-results") {
        ProgressCard("Notebook results", "Notebook marks show here straight away. Add them to the exam log to sync them with Focal.", Icons.Rounded.CloudSync) {
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                StatusPill("${local.size} recorded"); StatusPill("$ready ready to add", strong = ready > 0)
                if (likelyLogged > 0) StatusPill("$likelyLogged likely logged", Icons.Rounded.ContentCopy)
            }
            FilledTonalButton(onLogNotebooks, enabled = enabled && ready > 0, shapes = ButtonDefaults.shapes()) { Text("Review and add to log") }
            if (state.userId != null) TextButton(onCopyLogs, enabled = enabled) { Text("Copy device logs to this account") }
        }
    }
}

@Composable private fun OverviewHero(exams: List<LoggedExam>, settings: JSONObject?, due: Int, mistakes: List<ExamTrackMistake>, enabled: Boolean,
    onLog: () -> Unit, onLogMistake: (String) -> Unit, onMistakes: () -> Unit, onDestination: (String) -> Unit) {
    ProgressHero {
        if (exams.isEmpty()) {
            Text("Your progress starts here", style = MaterialTheme.typography.headlineSmall)
            Text("Log a completed practice paper to see your average, trends and what to work on next.", style = MaterialTheme.typography.bodyLarge)
            Button(onLog, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Log first exam")
            }
            return@ProgressHero
        }
        val average = performanceAverage(exams, settings)
        val recent = performanceAverage(exams.take(5), settings)
        val trend = if (exams.size > 5) recent - average else null
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 520.dp
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp24)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Text("Aligned average", style = MaterialTheme.typography.labelLarge)
                    Text("${average.display()}%", style = MaterialTheme.typography.displayMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        StatusPill(if (trend == null) "Last ${exams.size.coerceAtMost(5)}: ${recent.display()}%" else "Last 5 ${signed(trend)} pts", trendIcon(trend))
                    }
                }
                Sparkline(exams.take(12).reversed().map { performance(it, settings).aligned },
                    Modifier.width(if (wide) 240.dp else 112.dp).height(if (wide) 96.dp else 72.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
        val latest = exams.first()
        // Review needs no local write, so it stays available while the log is busy; the others edit the log.
        val needsMistake = due == 0 && mistakes.none { it.attemptId == latest.id }
        Text("Next step", style = MaterialTheme.typography.labelLarge)
        Text(when {
            due > 0 -> "$due mistake ${if (due == 1) "card is" else "cards are"} due for review."
            needsMistake -> "Capture what went wrong in ${latest.subject} · ${latest.paper}."
            else -> "You're up to date. Pick the next paper in your progression."
        }, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            when {
                due > 0 -> Button(onMistakes, shapes = ButtonDefaults.shapes()) { Text("Review mistakes") }
                needsMistake -> Button({ onLogMistake(latest.id) }, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text("Log a mistake") }
                else -> Button({ onDestination("Lists") }, shapes = ButtonDefaults.shapes()) { Text("Open lists") }
            }
            if (due > 0 || needsMistake) OutlinedButton({ onDestination("Lists") }, shapes = ButtonDefaults.shapes()) { Text("Plan papers") }
        }
    }
}

@Composable private fun SubjectsCard(exams: List<LoggedExam>, settings: JSONObject?, onSubject: (String) -> Unit) {
    val outlooks = remember(exams, settings) { subjectOutlooks(exams, settings).associateBy { it.subject } }
    ProgressCard("Subjects", "VCAA-aligned, relevance-weighted averages. Planning estimates.", Icons.Rounded.School) {
        if (exams.isEmpty()) Text("Log papers to see each subject's average and coverage.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        exams.groupBy { it.subject }.entries.sortedByDescending { it.value.size }.forEach { (subject, attempts) ->
            val average = performanceAverage(attempts, settings)
            val distinct = attempts.distinctBy { it.paperKey() }.size
            val momentum = outlooks[subject]?.momentum?.takeIf { attempts.size > 1 }
            Surface({ onSubject(subject) }, shape = FolioShapes.medium, color = Color.Transparent) {
                Row(Modifier.padding(vertical = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Box(Modifier.weight(1f)) {
                        MeterRow(subject, "${average.display()}%", (average / 100).toFloat(),
                            supporting = "$distinct ${if (distinct == 1) "paper" else "papers"} · ${attempts.map { it.provider }.distinct().size} providers · " +
                                "${attempts.map { it.examYear }.distinct().size} years${(attempts.size - distinct).takeIf { it > 0 }?.let { " · $it repeats" } ?: ""}")
                    }
                    Icon(trendIcon(momentum), momentum?.let { "Momentum ${signed(it)} points" }, Modifier.size(20.dp),
                        tint = when { momentum == null || kotlin.math.abs(momentum) < 1 -> MaterialTheme.colorScheme.onSurfaceVariant
                            momentum > 0 -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.error })
                }
            }
        }
    }
}

@Composable private fun ActivityCard(exams: List<LoggedExam>) {
    val today = LocalDate.now(); val start = today.minusWeeks(11).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val weeks = (0..11).map { i -> val from = start.plusWeeks(i.toLong()); val until = from.plusWeeks(1)
        "${from.dayOfMonth}/${from.monthValue}" to exams.count { e -> e.date?.let { !it.isBefore(from) && it.isBefore(until) } == true }.toDouble()
    }
    val total = weeks.sumOf { it.second }.toInt(); val active = weeks.count { it.second > 0 }
    var streak = 0; for (week in weeks.reversed()) { if (week.second > 0) streak++ else if (streak > 0 || week !== weeks.last()) break }
    ProgressCard("Weekly activity", "Papers completed over the past 12 weeks", Icons.Rounded.CalendarMonth) {
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp24)) {
            listOf("$total" to "papers", "$active" to "active weeks", "$streak" to "week streak").forEach { (value, label) ->
                Column { Text(value, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        ColumnChart(weeks, "Papers per week: ${weeks.joinToString { "week of ${it.first}: ${it.second.toInt()}" }}", labelEvery = 3)
    }
}

// ---- Exams -------------------------------------------------------------------------------------

@Composable private fun ExamActions(title: String, enabled: Boolean, editable: Boolean, onEdit: () -> Unit, onMistake: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, enabled = enabled) { Icon(Icons.Rounded.MoreVert, "Actions for $title") }
        DropdownMenu(open, { open = false }, Modifier.guardUiTouches()) {
            DropdownMenuItem({ Text("Edit result") }, { open = false; onEdit() }, enabled = editable, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem({ Text("Add mistake") }, { open = false; onMistake() }, leadingIcon = { Icon(Icons.Rounded.AddTask, null) })
            HorizontalDivider()
            DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { open = false; onDelete() },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

// ---- Lists -------------------------------------------------------------------------------------

private fun LazyGridScope.progressionItems(progression: JSONObject?, exams: List<LoggedExam>, subject: String, query: String, enabled: Boolean,
    canEdit: Boolean, onEdit: () -> Unit, onResult: (String) -> Unit, onLog: (String) -> Unit) {
    val plan = progression?.optJSONArray("exams")?.objects().orEmpty()
    val done = exams.map { it.paperKey() }.toSet()
    val complete = plan.count { paperKey(it) in done }
    wide("plan-header") {
        Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                ScoreRing(if (plan.isEmpty()) 0.0 else complete * 100.0 / plan.size, 72.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                    Text(progression?.optString("name")?.ifBlank { null } ?: "Exam progression", style = MaterialTheme.typography.titleLarge)
                    Text(if (plan.isEmpty()) "Plan practice across your subjects. Completion follows your logged results."
                        else "$complete of ${plan.size} papers complete across ${plan.map { it.optString("subject") }.distinct().size} subjects",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onEdit, enabled = canEdit, shapes = ButtonDefaults.shapes()) {
                    Icon(if (progression == null) Icons.Rounded.Add else Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (progression == null) "Create" else "Edit")
                }
            }
        }
    }
    plan.groupBy { it.optString("subject") }.filterKeys { subject == ALL_SUBJECTS || subject == it }.forEach { (s, papers) -> item("plan:$s") {
        val count = papers.count { paperKey(it) in done }
        ProgressCard(s, "$count of ${papers.size} complete", action = { if (count == papers.size) StatusPill("Done", Icons.Rounded.Check, strong = true) }) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth(count.toFloat() / papers.size).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
            var phase: String? = null
            papers.filter { "${it.optString("provider")} ${it.optInt("examYear")} ${it.optString("paper")} ${it.optString("phase")}".contains(query, true) }.forEach { paper ->
                val finished = paperKey(paper) in done
                val label = paper.optString("phase")
                if (label.isNotBlank() && label != phase) Text(label.uppercase(), Modifier.padding(top = FolioSpacing.dp4), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                phase = label
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    Icon(if (finished) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (finished) "Completed" else "To do", Modifier.size(22.dp),
                        tint = if (finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Text("${paper.optString("provider")} ${paper.optInt("examYear")} · ${paper.optString("paper")}", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (finished) TextButton({ exams.firstOrNull { it.paperKey() == paperKey(paper) }?.id?.let(onResult) }) { Text("Result") }
                    else FilledTonalButton({ onLog(JSONObject(paper.toString()).put("rawMax", paper.optDouble("marks", 100.0)).toString()) }, enabled = enabled,
                        contentPadding = PaddingValues(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp6), shapes = ButtonDefaults.shapes()) { Text("Log") }
                }
            }
        }
    } }
    if (plan.isEmpty()) wide("plan-empty") {
        EmptyState(Icons.Rounded.Route, "No papers planned yet", "Build a list by hand or import a Focal progression. Logged papers tick off automatically.")
    }
}

@Composable private fun OfficialExamCard(exam: OfficialExam, attempts: List<LoggedExam>, completed: Set<String>,
    onLog: () -> Unit, onResult: (String) -> Unit, onToggle: () -> Unit, onTimed: () -> Unit, enabled: Boolean) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    fun open(url: String) { try { uri.openUri(url) } catch (_: Exception) { linkError = true } }
    val logged = exam.logged(attempts); val done = logged != null || exam.resource.url in completed
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Surface(shape = FolioShapes.large, color = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (done) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Text("${exam.year}", Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp10), style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text(exam.subject, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${exam.provider} · ${exam.paper}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    logged != null -> Surface({ onResult(logged.id) }, shape = CircleShape, color = Color.Transparent) { ScoreRing(logged.percentage, 44.dp) }
                    done -> StatusPill("Done", Icons.Rounded.Check, strong = true)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                FilledTonalButton(onLog, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text(if (logged != null) "Log again" else "Log result") }
                OutlinedButton({ open(exam.resource.url) }, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(FolioSpacing.dp6)); Text("Paper")
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${exam.subject} ${exam.year} ${exam.paper}") }
                    DropdownMenu(menu, { menu = false }, Modifier.guardUiTouches()) {
                        DropdownMenuItem({ Text("Start timed sitting") }, { menu = false; onTimed() }, enabled = enabled, leadingIcon = { Icon(Icons.Rounded.Timer, null) })
                        if (logged != null) DropdownMenuItem({ Text("Compare result") }, { menu = false; onResult(logged.id) }, leadingIcon = { Icon(Icons.Rounded.Insights, null) })
                        else DropdownMenuItem({ Text(if (done) "Mark not done" else "Mark done") }, { menu = false; onToggle() }, enabled = enabled,
                            leadingIcon = { Icon(if (done) Icons.Rounded.RemoveDone else Icons.Rounded.TaskAlt, null) })
                        if (exam.companions.isNotEmpty()) HorizontalDivider()
                        exam.companions.forEach { resource ->
                            DropdownMenuItem({ Text(when (resource.kind) { "report" -> "Examiner report"; "specification" -> "Specifications"; else -> "Sample paper" }) },
                                { menu = false; open(resource.url) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) })
                        }
                    }
                }
            }
            if (linkError) Text("Could not open this link. Check that a browser is installed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun NotebookRow(note: Notebook, onOpen: () -> Unit) {
    Surface(onOpen, shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.padding(FolioSpacing.dp10).size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(note.exam.summaryLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val redo = note.pages.count { it.redoFlag }
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    StatusPill(note.exam.status.label)
                    if (note.attempts.isNotEmpty()) StatusPill("${note.attempts.size} ${if (note.attempts.size == 1) "attempt" else "attempts"}")
                    if (redo > 0) StatusPill("$redo to redo", Icons.Rounded.Flag, strong = true)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open notebook", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
