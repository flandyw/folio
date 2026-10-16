@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun FocalStudyChip(timer: ExamTimerState, notebookId: String?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    // The hold claims the gesture so the release after it never also opens the panel.
    val hold = rememberLongPressGuard()
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    val entries = remember(state.entries, state.userId) { state.visibleEntries }
    var now by remember { mutableLongStateOf(manager.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, manager) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = manager.now()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }
    val examActive = timer.active && timer.startedAt != null
    val focus = state.visibleFocus
    // A sitting recorded from a notebook's timer belongs to that notebook; the sessions dialog lists it everywhere.
    val sharedActive = entries.firstOrNull { it.active && (it.kind != "exam" || it.notebookId == notebookId) }
    val activeElapsed = when {
        focus != null -> focus.elapsed(now)
        sharedActive != null -> sharedActive.intervals.sumOf { interval ->
            ((interval.endAt ?: now) - interval.startAt).coerceAtLeast(0L)
        }.coerceAtLeast(sharedActive.activeMillis)
        else -> 0L
    }
    val label = when {
        examActive && timer.paused -> "Focal · Paused"
        examActive && timer.phase == ExamTimerPhase.READING -> "Focal · Reading"
        examActive -> "Focal · Writing"
        focus?.resumedAt != null -> "Studying · ${formatChipElapsed(activeElapsed)}"
        focus != null -> "Focal · ${focalFocusStatus(focus)}"
        sharedActive != null && sharedActive.paused -> "Focal · Paused"
        sharedActive != null -> "Studying · ${formatChipElapsed(activeElapsed)}"
        else -> "Focal"
    }
    val syncStatus = state.syncStatus
    val recording = examActive || focus != null || sharedActive != null
    val isPaused = when {
        examActive -> timer.paused
        focus != null -> focus.resumedAt == null
        else -> sharedActive?.paused == true
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(if (recording) label else "Focal · Study sessions") } },
        state = rememberTooltipState()
    ) {
        val holdHint = if (onLongClick != null && !examActive) ". Hold to ${if (isPaused) "resume" else "pause"}" else ""
        val description = "$label. $syncStatus. Open study sessions$holdHint"
        // An exam sitting has one pill, the exam timer's; Focal's entry point stays a plain icon beside it.
        if (recording && !examActive) {
            ToolbarSessionPill(
                icon = if (isPaused) Icons.Rounded.Pause else Icons.Rounded.School,
                value = formatChipElapsed(activeElapsed),
                tone = if (isPaused) SessionTone.PAUSED else SessionTone.RUNNING,
                description = description,
                onClick = hold.click(onClick),
                modifier = Modifier.padding(horizontal = FolioSpacing.dp4)
                    .then(if (onLongClick != null) Modifier.longPressAction(hold, onLongClick) else Modifier),
                reserve = if (activeElapsed >= 3_600_000L) "0:00:00" else "00:00"
            )
        } else {
            IconButton(onClick, modifier = Modifier.size(DockButtonWidth, DockButtonHeight).semantics {
                contentDescription = "$label. $syncStatus. Open study sessions"
            }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.School, null, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun FocalStudyPanel(note: Notebook?, examTimer: ExamTimerState? = null, onDismiss: () -> Unit) {
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    FolioPanel("Study sessions", onDismiss) {
        FocalStudyContent(note, examTimer, onAccount = { accountOpen = true })
    }
    if (accountOpen) FocalAccountPanel({ accountOpen = false }, onMistakes = null, onStudy = null)
}

private enum class HeroMode { IDLE, RUNNING, PAUSED }

/**
 * One set of controls for the dedicated destination and the editor's quick panel.
 *
 * Wide surfaces (landscape tablet, the landscape panel) split into a timer column and a
 * context column that scroll independently, so Pause/Save never move while history grows.
 * Narrow ones (portrait tablet, phone) stack the same blocks, timer first.
 */
@Composable
internal fun FocalStudyContent(
    note: Notebook?, examTimer: ExamTimerState? = null, dedicated: Boolean = false,
    notebooks: List<Notebook> = emptyList(), onAccount: () -> Unit = {},
) {
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    val entries = remember(state.entries, state.userId) { state.visibleEntries }
    var notebookId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedNote = if (dedicated) notebooks.firstOrNull { it.id == notebookId } else note
    var notebookMenu by remember { mutableStateOf(false) }
    var subjectId by rememberSaveable(state.userId, selectedNote?.id) { mutableStateOf(selectedNote?.let { FocalSubjects.suggest(it, state.subjects) }) }
    var notes by rememberSaveable(state.userId, state.visibleFocus?.sessionId) { mutableStateOf(entries.firstOrNull { it.id == state.visibleFocus?.sessionId }?.notes.orEmpty()) }
    var confidence by rememberSaveable(state.userId, state.visibleFocus?.sessionId) { mutableIntStateOf(entries.firstOrNull { it.id == state.visibleFocus?.sessionId }?.confidence ?: 0) }
    var discardId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyLimit by rememberSaveable { mutableIntStateOf(20) }
    var manualLog by rememberSaveable { mutableStateOf(false) }
    var manualMinutes by rememberSaveable { mutableStateOf("") }
    var planEditorId by rememberSaveable(state.userId) { mutableStateOf<String?>(null) }
    var planSearch by rememberSaveable(state.userId) { mutableStateOf("") }
    var planScope by rememberSaveable(state.userId) { mutableStateOf("All") }
    var planSubject by rememberSaveable(state.userId) { mutableStateOf<String?>(null) }
    var plannedLimit by rememberSaveable(state.userId) { mutableIntStateOf(5) }
    var showAllShared by rememberSaveable { mutableStateOf(false) }
    var bulkAction by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(manager.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, manager) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            manager.retry()
            while (true) {
                now = manager.now()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }
    val focus = state.visibleFocus
    val examRecording = examTimer?.takeIf { it.active && it.startedAt != null }
    val today = remember(now / 60_000L, entries) {
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        (focalStudyMillisBetween(entries, start, now) + 30_000L) / 60_000L
    }
    val history = remember(entries) {
        entries.filter { it.completed && !focalIsCalendarPlaceholder(it) }.sortedByDescending { it.endedAt }
    }
    val planned = remember(entries) {
        entries.filter { it.planned && !it.completed && it.kind == "study" }
            .sortedBy { focalPlannedSlots(it).firstOrNull()?.startAt ?: Long.MAX_VALUE }
    }
    val dayStart = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val dayEnd = (dayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    val visiblePlans = remember(planned, state.subjects, planSearch, planScope, planSubject, now / 60_000L) {
        planned.filter { entry ->
            val slots = focalPlannedSlots(entry)
            val details = focalPlanDetails(entry)
            (planSubject == null || entry.subjectId == planSubject) &&
                listOf(entry.title, state.subjects.firstOrNull { it.id == entry.subjectId }?.name.orEmpty(), details.first, details.second.joinToString(" "))
                    .joinToString(" ").contains(planSearch.trim(), ignoreCase = true) && when (planScope) {
                    "Today" -> slots.any { it.startAt < dayEnd && requireNotNull(it.endAt) > dayStart.timeInMillis }
                    "Overdue" -> slots.lastOrNull()?.endAt?.let { it < now } == true
                    "Upcoming" -> slots.any { requireNotNull(it.endAt) >= now }
                    else -> true
                }
        }
    }
    val recent = history.take(if (dedicated) historyLimit else 5)
    val cardColor = if (dedicated) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHighest
    val anotherRunning = entries.any { it.active && !it.paused }

    // ── Timer column: exam notice, the clock with its controls, and the wrap-up form. ──
    val timerBlock: @Composable ColumnScope.() -> Unit = {
        if (examRecording != null) {
            val phase = when {
                examRecording.paused -> "Paused"
                examRecording.phase == ExamTimerPhase.READING -> "Reading"
                else -> "Writing"
            }
            Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Text("Exam recording · $phase", style = MaterialTheme.typography.titleSmall)
                    Text("Reading and writing time are being saved to Focal as this exam runs. Pauses are excluded.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (focus == null) {
            TimerHero(HeroMode.IDLE, formatElapsed(0L), "Ready when you are", null, dedicated) {
                Text("What are you studying?", style = MaterialTheme.typography.labelLarge)
                FocalSubjectPicker(state.subjects, subjectId) { subjectId = it }
                if (dedicated) Box {
                    OutlinedButton({ notebookMenu = true }, modifier = Modifier.fillMaxWidth(), shape = FolioShapes.large) {
                        Icon(Icons.Rounded.Book, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp10))
                        Text(selectedNote?.title ?: "No notebook", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    FolioMenuPopover(notebookMenu, { notebookMenu = false }, title = "Session notebook") {
                        FolioMenuItem({ Text("No notebook") }, { notebookId = null; notebookMenu = false }, selected = notebookId == null)
                        notebooks.sortedBy { it.title.lowercase() }.forEach { item ->
                            FolioMenuItem({ Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                { notebookId = item.id; notebookMenu = false }, selected = notebookId == item.id)
                        }
                    }
                }
                Button({ manager.startFocus(selectedNote, subjectId) },
                    enabled = examRecording == null && state.canStartFocus && !anotherRunning,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Start study")
                }
                if (anotherRunning) Text("Pause or finish your running session before starting another.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton({ manualLog = !manualLog }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (manualLog) "Cancel manual entry" else "Log past study without the timer")
                }
            }
            FolioExpand(manualLog) {
                Surface(shape = FolioShapes.extraLarge, color = cardColor) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Text("Log past study", style = MaterialTheme.typography.titleMedium)
                        fun savePastStudy() {
                            if (manualMinutes.toIntOrNull()?.let { it in 1..1_440 } != true) return
                            manager.logManual(selectedNote, subjectId, manualMinutes.toInt(), notes, confidence.takeIf { it > 0 })
                            manualMinutes = ""; notes = ""; confidence = 0; manualLog = false
                        }
                        OutlinedTextField(manualMinutes, { manualMinutes = it.filter(Char::isDigit).take(4) },
                            Modifier.fillMaxWidth(), label = { Text("Minutes studied") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { savePastStudy() }))
                        ReflectionFields(notes, { notes = it }, confidence, { confidence = it })
                        Button(::savePastStudy, enabled = manualMinutes.toIntOrNull()?.let { it in 1..1_440 } == true,
                            shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Save past study") }
                    }
                }
            }
        } else {
            val running = focus.resumedAt != null
            val detail = listOfNotNull(
                state.subjects.firstOrNull { it.id == focus.subjectId }?.name, focus.notebookTitle,
            ).joinToString(" · ").ifBlank { null }
            TimerHero(
                if (running) HeroMode.RUNNING else HeroMode.PAUSED, formatElapsed(focus.elapsed(now)),
                focus.title, detail, dedicated,
                statusOverride = focalFocusStatus(focus),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        { manager.toggleFocus() },
                        enabled = running || (examRecording == null && entries.none { it.active && !it.paused && it.id != focus.sessionId }),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text(if (running) "Pause" else "Resume")
                    }
                    OutlinedButton(
                        { discardId = focus.sessionId },
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text("Discard")
                    }
                }
                if (examRecording != null && !running) {
                    Text("Resume regular study after the exam timer stops.", style = MaterialTheme.typography.bodySmall)
                } else focalFocusStatusDetail(focus)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val plannedMillis = entries.firstOrNull { it.id == focus.sessionId }?.let { entry ->
                    focalPlannedSlots(entry).sumOf { requireNotNull(it.endAt) - it.startAt }
                } ?: 0L
                if (plannedMillis > 0L) {
                    Text("${formatStudied(focus.elapsed(now) / 60_000L)} of ${formatStudied(plannedMillis / 60_000L)} planned" +
                        if (focus.elapsed(now) >= plannedMillis) " · Goal reached" else "", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { (focus.elapsed(now).toFloat() / plannedMillis).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                Text(state.syncStatus, style = MaterialTheme.typography.labelSmall)
            }
            Surface(shape = FolioShapes.extraLarge, color = cardColor) {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    Text("Wrap up", style = MaterialTheme.typography.titleMedium)
                    ReflectionFields(notes, { notes = it }, confidence, { confidence = it })
                    Button({ manager.finishFocus(notes, confidence.takeIf { it > 0 }); notes = ""; confidence = 0 },
                        enabled = focus.elapsed(now) >= 1_000L,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Save session")
                    }
                }
            }
        }
    }

    val timeline: @Composable (Boolean) -> Unit = { compact ->
        StudyTimeline(state, now, canStart = examRecording == null && state.canStartFocus && !anotherRunning, cardColor = cardColor,
            onStartPlanned = { manager.startPlannedFocus(it, selectedNote); manualLog = false },
            onStartFocus = { manager.startFocus(selectedNote, it) },
            onEditPlan = { planEditorId = it }, compact = compact)
    }
    // ── Context column: the day's timeline (when it isn't spanning the page), today's total + sync, other apps' sessions, history. ──
    val contextItems: LazyListScope.(Boolean) -> Unit = { withTimeline ->
        if (dedicated && withTimeline) item(key = "timeline") { timeline(false) }
        item(key = "today") {
            Surface(shape = FolioShapes.extraLarge, color = cardColor) {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Icon(Icons.Rounded.School, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Studied today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatStudied(today), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    Text("Active study only — pauses, exams and calendar blocks are excluded.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Icon(when {
                            state.syncing -> Icons.Rounded.Sync
                            state.error != null -> Icons.Rounded.ErrorOutline
                            state.userId == null || !state.configured || state.pendingCount > 0 -> Icons.Rounded.CloudOff
                            else -> Icons.Rounded.CloudDone
                        }, null, Modifier.size(20.dp))
                        Text(state.syncStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (state.needsSignIn) {
                            TextButton(onAccount) { Text("Sign in again") }
                        } else if ((state.userId != null && (state.pendingCount > 0 || state.error != null || state.syncing)) ||
                            state.localSaveFailed) {
                            TextButton({ manager.retry() }, enabled = !state.syncing) {
                                Text(if (state.syncing) "Retrying…" else "Retry")
                            }
                        } else if (state.userId == null) {
                            TextButton(onAccount) { Text("Connect Focal") }
                        }
                    }
                    if (state.error != null && !state.syncing) Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val sharedActive = entries.filter { entry ->
            entry.active && entry.id != focus?.sessionId &&
                !(entry.kind == "exam" && examRecording?.startedAt == entry.startedAt)
        }
        if (sharedActive.isNotEmpty()) item(key = "shared") {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Active across your apps", style = MaterialTheme.typography.titleMedium)
                val pausedCount = sharedActive.count { it.paused }
                if (pausedCount > 1) {
                    Text("$pausedCount paused sessions can be resolved together.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        TextButton({ bulkAction = "finish" }) { Text("Finish paused") }
                        TextButton({ bulkAction = "discard" }) { Text("Discard paused") }
                    }
                }
                sharedActive.take(if (showAllShared) Int.MAX_VALUE else 6).forEach { entry ->
                    val elapsed = entry.intervals.sumOf { interval ->
                        ((interval.endAt ?: now) - interval.startAt).coerceAtLeast(0L)
                    }.coerceAtLeast(entry.activeMillis)
                    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${if (entry.kind == "exam") "Timed exam" else "Study"} · ${if (entry.paused) "Paused" else "In progress"} · ${formatElapsed(elapsed)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                OutlinedButton({ manager.controlEntry(entry.id, if (entry.paused) "resume" else "pause") },
                                    enabled = !entry.paused || entries.none { it.active && !it.paused && it.id != entry.id },
                                    shapes = ButtonDefaults.shapes()) {
                                    Icon(if (entry.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                                    Spacer(Modifier.width(FolioSpacing.dp6)); Text(if (entry.paused) "Resume" else "Pause")
                                }
                                TextButton({ manager.controlEntry(entry.id, "finish") }) { Text("Finish") }
                                TextButton({ discardId = entry.id }) { Text("Discard") }
                            }
                        }
                    }
                }
                if (sharedActive.size > 6) {
                    TextButton({ showAllShared = !showAllShared }) {
                        Text(if (showAllShared) "Show fewer" else "Show all ${sharedActive.size} sessions")
                    }
                }
            }
        }
        item(key = "planned-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Planned study${if (planned.isNotEmpty()) " · ${planned.size}" else ""}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (state.userId != null && state.configured) TextButton({ manager.retry() }, enabled = !state.syncing) {
                        Text(if (state.syncing) "Refreshing…" else "Refresh")
                    }
                }
                Text(when {
                    state.userId == null -> "Connect Focal to start sessions planned on the website."
                    planned.isEmpty() -> "Sessions planned on Focal will appear here."
                    focus != null -> "Finish or discard your current session before starting a plan."
                    examRecording != null -> "Stop the exam timer before starting a plan."
                    anotherRunning -> "Pause or finish your running session before starting a plan."
                    else -> "Start whenever you’re ready. Only time spent studying is recorded."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ planEditorId = "new:${java.util.UUID.randomUUID()}" }, modifier = Modifier.fillMaxWidth()) { Text("Plan study") }
                if (planned.isNotEmpty()) {
                    OutlinedTextField(planSearch, { planSearch = it; plannedLimit = 5 }, Modifier.fillMaxWidth(),
                        label = { Text("Search title, subject or topics") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        listOf("All", "Today", "Overdue", "Upcoming").forEach { scope ->
                            FilterChip(planScope == scope, { planScope = scope; plannedLimit = 5 }, label = { Text(scope) })
                        }
                    }
                    FocalSubjectPicker(state.subjects, planSubject, emptyLabel = "All subjects") { planSubject = it; plannedLimit = 5 }
                    if (visiblePlans.isEmpty()) Text("No plans match these filters.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(visiblePlans.take(plannedLimit), key = { "planned:${it.id}" }) { entry ->
            PlannedStudyCard(entry, state.subjects, cardColor,
                enabled = examRecording == null && state.canStartFocus && !anotherRunning && !(entry.synced && entry.planDraft != null),
                onStart = { manager.startPlannedFocus(entry.id, selectedNote); manualLog = false },
                onEdit = { planEditorId = entry.id })
        }

        if (visiblePlans.size > plannedLimit) item(key = "more-planned") {
            TextButton({ plannedLimit += 10 }) { Text("Show more planned sessions") }
        }
        item(key = "history-heading") {
            Text(if (dedicated) "Session history" else "Recent sessions", style = MaterialTheme.typography.titleMedium)
        }
        if (recent.isEmpty()) item(key = "empty-history") {
            EmptyHint("No completed sessions yet. Your saved study will appear here.")
        }
        items(recent, key = { it.id }) { entry ->
            val minutes = (focalActiveMillisBetween(entry, entry.startedAt, entry.endedAt) / 60_000L).toInt().coerceAtLeast(1)
            Surface(shape = FolioShapes.large, color = cardColor) {
                Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        val subject = state.subjects.firstOrNull { it.id == entry.subjectId }?.name ?: "No subject"
                        val details = listOf(if (entry.kind == "exam") "Exam" else "Study", subject,
                            entry.notebookTitle, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.endedAt)))
                            .filterNot { it.isNullOrBlank() }.joinToString(" · ")
                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (dedicated) {
                            if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall)
                            entry.confidence?.let { Text("Confidence $it/5", style = MaterialTheme.typography.labelSmall) }
                            Text(if (entry.synced) "Synced with Focal" else if (entry.userId == null) "Saved on this device" else "Saved locally · awaiting sync",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(formatStudied(minutes.toLong()), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (dedicated && history.size > historyLimit) item(key = "more-history") {
            TextButton({ historyLimit += 20 }) { Text("Show more sessions") }
        }
    }

    // The dedicated destination sits under a FolioScreenHeading, which already leaves the shared
    // gap below itself; the editor's quick panel has no heading, so it keeps its own.
    val top = if (dedicated) 0.dp else FolioSpacing.dp8
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 840.dp) {
            // Landscape tablet: a time strip wants width, so the timeline spans both columns. It is
            // capped at under half the height (scrolling inside) so the clock and history keep theirs.
            val spanning = dedicated && constraints.hasBoundedHeight
            val timelineMax = maxHeight * 0.45f
            val columnsTop = if (spanning) FolioSpacing.dp12 else top
            Column(Modifier.fillMaxWidth().then(if (dedicated) Modifier.fillMaxHeight() else Modifier).imePadding()) {
                if (spanning) Box(Modifier.fillMaxWidth().heightIn(max = timelineMax).verticalScroll(rememberScrollState())
                    .padding(horizontal = FolioDestinationInset)) { timeline(true) }
                Row(Modifier.fillMaxWidth().then(if (dedicated) Modifier.weight(1f) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(FolioDestinationInset)) {
                    Column(Modifier.weight(5f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = FolioDestinationInset, top = columnsTop, bottom = FolioSpacing.dp16),
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), content = timerBlock)
                    LazyColumn(Modifier.weight(6f).fillMaxHeight(),
                        contentPadding = PaddingValues(end = FolioDestinationInset, top = columnsTop, bottom = FolioSpacing.dp16),
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) { contextItems(!spanning) }
                }
            }
        } else {
            LazyColumn((if (dedicated) Modifier.fillMaxWidth() else Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.TopCenter)).imePadding(),
                contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, top = top, bottom = FolioSpacing.dp16),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                item(key = "timer") {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), content = timerBlock)
                }
                contextItems(true)
            }
        }
    }
    planEditorId?.let { id ->
        val existing = entries.firstOrNull { it.id == id }
        val copySource = if (id.startsWith("copy:")) entries.firstOrNull { it.id == id.split(":")[1] } else null
        val original = existing ?: copySource?.copy(id = id.substringAfterLast(":"), title = copySource.title + " (copy)",
            revision = 0L, remotePayload = null, planDraft = copySource.planDraft ?: copySource.remotePayload,
            synced = false, changeId = java.util.UUID.randomUUID().toString()) ?: if (id.startsWith("new:")) FocalStudyEntry(id = id.removePrefix("new:"),
            notebookId = selectedNote?.id, notebookTitle = selectedNote?.title, title = "", subjectId = subjectId,
            kind = "study", startedAt = now, endedAt = now, activeMillis = 0L, completed = false, planned = true) else null
        if (original != null) FocalPlanEditor(original, state.subjects, now,
            onDismiss = { planEditorId = null },
            onSave = { title, subject, description, topics, slots -> manager.savePlan(original, title, subject, description, topics, slots) },
            onDelete = existing?.let { { manager.deletePlan(it.id) } },
            onDuplicate = existing?.let { { planEditorId = "copy:${it.id}:${java.util.UUID.randomUUID()}" } })
    }
    discardId?.let { id ->
        AlertDialog(onDismissRequest = { discardId = null }, modifier = Modifier.guardUiTouches(),
            title = { Text("Discard this session?") },
            text = { Text("Its recorded study time will be removed from Folio and Focal. This cannot be undone.") },
            confirmButton = { TextButton({ manager.controlEntry(id, "discard"); discardId = null }) { Text("Discard") } },
            dismissButton = { TextButton({ discardId = null }) { Text("Keep session") } })
    }
    bulkAction?.let { action ->
        val ids = entries.filter { it.active && it.paused && it.id != focus?.sessionId &&
            !(it.kind == "exam" && examRecording?.startedAt == it.startedAt) }.map { it.id }
        AlertDialog(onDismissRequest = { bulkAction = null }, modifier = Modifier.guardUiTouches(),
            title = { Text(if (action == "discard") "Discard ${ids.size} paused sessions?" else "Finish ${ids.size} paused sessions?") },
            text = { Text(if (action == "discard") "These sessions will be removed from Folio and Focal."
                else "These sessions will be saved as completed study sessions.") },
            confirmButton = { TextButton({ manager.controlPausedEntries(ids, action); bulkAction = null }) {
                Text(if (action == "discard") "Discard sessions" else "Finish sessions")
            } },
            dismissButton = { TextButton({ bulkAction = null }) { Text("Cancel") } })
    }
}

@Composable
private fun PlannedStudyCard(entry: FocalStudyEntry, subjects: List<FocalSubject>, color: Color,
                             enabled: Boolean, onStart: () -> Unit, onEdit: (() -> Unit)? = null) {
    val slots = remember(entry.remotePayload, entry.planDraft) { focalPlannedSlots(entry) }
    val details = remember(entry.remotePayload, entry.planDraft) { focalPlanDetails(entry) }
    Surface(shape = FolioShapes.large, color = color) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            if (entry.synced && entry.planDraft != null) Text("Draft needs review · edit and save to resolve changes from Focal.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            subjects.firstOrNull { it.id == entry.subjectId }?.let {
                Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            slots.take(3).forEach { slot ->
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(slot.startAt)) +
                    " – " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(requireNotNull(slot.endAt))),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (slots.isNotEmpty()) Text(
                formatStudied((slots.sumOf { requireNotNull(it.endAt) - it.startAt } + 30_000L) / 60_000L) + " planned" +
                    if (slots.size > 3) " · ${slots.size} blocks" else "",
                style = MaterialTheme.typography.labelMedium)
                        if (details.first.isNotBlank()) Text(details.first, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (details.second.isNotEmpty()) Text("Topics: ${details.second.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
            onEdit?.let { TextButton(it) { Text("Edit plan") } }
            Button(onStart, enabled = enabled, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text("Start session")
            }
        }
    }
}

/**
 * The editor's stopwatch is the Focal study focus, not a second clock: starting it records a
 * study session for the open notebook, and the session shows up in history and on other Focal
 * apps. It counts only while the pages are on screen, so leaving the editor or backgrounding
 * the app parks it with a visible reason, and a kill recovers it paused at the last checkpoint.
 */
@Composable
internal fun StudyStopwatchContent(note: Notebook?, examTimer: ExamTimerState, onOpenStudy: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    val entries = remember(state.entries, state.userId) { state.visibleEntries }
    var now by remember { mutableLongStateOf(manager.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, manager) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            manager.retry()
            while (true) {
                now = manager.now()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    val focus = state.visibleFocus
    val examRunning = examTimer.active && examTimer.startedAt != null
    val otherRunning = entries.any { it.active && !it.paused && it.id != focus?.sessionId }
    val blocked = when {
        examRunning -> "The exam timer is recording this sitting. Stop it to start regular study."
        otherRunning -> "Another study session is running, possibly on another Focal app. Pause or finish it first."
        else -> null
    }
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)
    ) {
        if (focus == null) {
            val subject = note?.let { FocalSubjects.suggest(it, state.subjects) }
                ?.let { id -> state.subjects.firstOrNull { it.id == id }?.name }
            Text(
                "Time your study here. It is saved as a Focal study session for this notebook" +
                    (subject?.let { ", under $it" } ?: "") + ", and appears in your history and on your other Focal apps.",
                color = scheme.onSurfaceVariant
            )
            Button({ manager.startFocus(note, note?.let { FocalSubjects.suggest(it, state.subjects) }, attended = true) },
                enabled = blocked == null && state.canStartFocus,
                modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text("Start studying")
            }
            blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            val planned = remember(entries) {
                entries.filter { it.planned && !it.completed && it.kind == "study" }
                    .sortedBy { focalPlannedSlots(it).firstOrNull()?.startAt ?: Long.MAX_VALUE }
            }
            if (planned.isNotEmpty()) {
                Text("Planned study", style = MaterialTheme.typography.titleMedium)
                planned.take(3).forEach { entry ->
                    PlannedStudyCard(entry, state.subjects, scheme.surfaceContainerHighest,
                        enabled = blocked == null && state.canStartFocus && !(entry.synced && entry.planDraft != null),
                        onStart = { manager.startPlannedFocus(entry.id, note, attended = true) })
                }
                if (planned.size > 3) TextButton(onOpenStudy) { Text("All ${planned.size} planned sessions") }
            } else if (state.userId == null) {
                Text("Connect Focal in Study sessions to start plans from the website.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val running = focus.resumedAt != null
            Surface(shape = FolioShapes.extraLarge,
                color = if (running) scheme.primaryContainer else scheme.tertiaryContainer,
                contentColor = if (running) scheme.onPrimaryContainer else scheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp24, horizontal = FolioSpacing.dp16),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Text((focalFocusStatus(focus) ?: "Recording").uppercase(),
                        style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
                    Text(formatElapsed(focus.elapsed(now)),
                        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text(focus.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(state.subjects.firstOrNull { it.id == focus.subjectId }?.name, focus.notebookTitle)
                        .joinToString(" · ").takeIf { it.isNotBlank() }
                        ?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    (if (examRunning && !running) "Resume regular study after the exam timer stops." else focalFocusStatusDetail(focus))
                        ?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                }
            }
            Button({ manager.toggleFocus() },
                enabled = running || (!examRunning && !otherRunning),
                modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(if (running) "Pause" else "Resume")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                FilledTonalButton({
                    val entry = entries.firstOrNull { it.id == focus.sessionId }
                    manager.finishFocus(entry?.notes.orEmpty(), entry?.confidence)
                }, enabled = focus.elapsed(now) >= 1_000L,
                    modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp6))
                    Text("Finish & save")
                }
                OutlinedButton({ confirmDiscard = true }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error)) {
                    Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp6))
                    Text("Discard")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(when {
                state.syncing -> Icons.Rounded.Sync
                state.error != null -> Icons.Rounded.ErrorOutline
                state.userId == null || !state.configured || state.pendingCount > 0 -> Icons.Rounded.CloudOff
                else -> Icons.Rounded.CloudDone
            }, null, Modifier.size(18.dp))
            Text(state.syncStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onOpenStudy) { Text(if (focus == null) "Study sessions" else "Add notes & history") }
        }
        if (state.error != null && !state.syncing) Text(state.error!!, color = scheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, modifier = Modifier.guardUiTouches(),
        title = { Text("Discard this session?") },
        text = { Text("Its recorded study time will be removed from Folio and Focal. This cannot be undone.") },
        confirmButton = { TextButton({ manager.discardFocus(); confirmDiscard = false }) { Text("Discard") } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep session") } })
}

/** The clock. Colour carries state (running / paused / idle) so it reads from across a desk. */
@Composable
private fun TimerHero(
    mode: HeroMode, time: String, title: String, detail: String?, large: Boolean,
    statusOverride: String? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(when (mode) {
        HeroMode.RUNNING -> scheme.primaryContainer
        HeroMode.PAUSED -> scheme.tertiaryContainer
        HeroMode.IDLE -> scheme.surfaceContainerHighest
    }, label = "heroContainer")
    val content = when (mode) {
        HeroMode.RUNNING -> scheme.onPrimaryContainer
        HeroMode.PAUSED -> scheme.onTertiaryContainer
        HeroMode.IDLE -> scheme.onSurface
    }
    val status = statusOverride ?: when (mode) { HeroMode.RUNNING -> "Recording"; HeroMode.PAUSED -> "Paused"; HeroMode.IDLE -> "Ready" }
    Surface(shape = FolioShapes.panel, color = container, contentColor = content) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Box(Modifier.size(10.dp).background(if (mode == HeroMode.IDLE) content.copy(alpha = .4f) else content, CircleShape))
                Text(status, style = MaterialTheme.typography.labelLarge)
            }
            Text(time,
                style = (if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium)
                    .copy(fontFeatureSettings = "tnum"),
                maxLines = 1, softWrap = false,
                modifier = Modifier.alpha(if (mode == HeroMode.IDLE) .45f else 1f).semantics {
                    contentDescription = "Active study time, $time"
                })
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(FolioSpacing.dp4))
            actions()
        }
    }
}

/** Chips while there are few subjects (one tap on a tablet), a menu once they'd sprawl. */
@Composable
internal fun FocalSubjectPicker(subjects: List<FocalSubject>, selected: String?, emptyLabel: String = "No subject", onSelect: (String?) -> Unit) {
    if (subjects.size > 8) {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth(), shape = FolioShapes.large) {
                Icon(Icons.Rounded.School, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp10))
                Text(subjects.firstOrNull { it.id == selected }?.name ?: emptyLabel, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, null)
            }
            FolioMenuPopover(open, { open = false }, title = "Subject") {
                FolioMenuItem({ Text(emptyLabel) }, { onSelect(null); open = false }, selected = selected == null)
                subjects.forEach { FolioMenuItem({ Text(it.name) }, { onSelect(it.id); open = false }, selected = selected == it.id) }
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            subjects.forEach { subject ->
                FilterChip(selected = selected == subject.id, onClick = { onSelect(if (selected == subject.id) null else subject.id) },
                    label = { Text(subject.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
            if (subjects.isEmpty()) EmptyHint("No subjects yet — sessions are saved without one.")
        }
    }
}

@Composable
private fun ReflectionFields(notes: String, onNotes: (String) -> Unit, confidence: Int, onConfidence: (Int) -> Unit) {
    OutlinedTextField(notes, { onNotes(it.take(500)) }, Modifier.fillMaxWidth(),
        label = { Text("What did you work on? (optional)") }, minLines = 2)
    Text("How confident do you feel? (optional)", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        (1..5).forEach { score ->
            FilterChip(selected = confidence == score, onClick = { onConfidence(if (confidence == score) 0 else score) },
                label = { Text("$score", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f))
        }
    }
}

private fun formatStudied(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h == 0L) "$m min" else if (m == 0L) "$h h" else "$h h $m min"
}

private fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
}

private fun formatChipElapsed(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return if (seconds >= 3600L) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
    } else {
        String.format(java.util.Locale.ROOT, "%02d:%02d", (seconds / 60) % 60, seconds % 60)
    }
}
