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
fun FocalStudyChip(timer: ExamTimerState, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
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
    val sharedActive = entries.firstOrNull { it.active }
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
    val pauseWord = if (focus?.parkReason == FocalFocus.PARK_AWAY || focus?.parkReason == FocalFocus.PARK_CLOSED) "Stopped" else "Paused"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(if (recording) label else "Focal · Study sessions") } },
        state = rememberTooltipState()
    ) {
        if (recording) {
            Surface(
                onClick = hold.click(onClick),
                modifier = Modifier.padding(horizontal = FolioSpacing.dp4).height(36.dp)
                    .then(if (onLongClick != null && !examActive) Modifier.longPressAction(hold, onLongClick) else Modifier)
                    .semantics {
                        contentDescription = "$label. $syncStatus. Open study sessions" +
                            if (onLongClick != null && !examActive) ". Hold to ${if (isPaused) "resume" else "pause"}" else ""
                    },
                shape = FolioShapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Row(Modifier.padding(horizontal = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Icon(if (isPaused) Icons.Rounded.Pause else Icons.Rounded.School, null, Modifier.size(16.dp))
                    Text(when {
                        examActive -> label.removePrefix("Focal · ")
                        isPaused -> "$pauseWord · ${formatChipElapsed(activeElapsed)}"
                        else -> formatChipElapsed(activeElapsed)
                    }, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            }
        } else {
            IconButton(onClick, modifier = Modifier.size(40.dp).semantics {
                contentDescription = "$label. $syncStatus. Open study sessions"
            }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.School, null)
            }
        }
    }
}

@Composable
fun FocalStudyPanel(note: Notebook?, examTimer: ExamTimerState? = null, onDismiss: () -> Unit) {
    FolioPanel("Study sessions", onDismiss) {
        FocalStudyContent(note, examTimer)
    }
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
    var notes by rememberSaveable(state.userId, state.visibleFocus?.sessionId) { mutableStateOf("") }
    var confidence by rememberSaveable(state.userId, state.visibleFocus?.sessionId) { mutableIntStateOf(0) }
    var discardId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyLimit by rememberSaveable { mutableIntStateOf(20) }
    var manualLog by rememberSaveable { mutableStateOf(false) }
    var manualMinutes by rememberSaveable { mutableStateOf("") }
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
        val activeToday = entries.filter { it.active && it.kind == "study" && !focalIsCalendarPlaceholder(it) }.sumOf { entry ->
            val activeMillis = entry.intervals.sumOf { ((it.endAt ?: now) - it.startAt).coerceAtLeast(0L) }
            focalActiveMillisBetween(entry.copy(endedAt = now, activeMillis = activeMillis), start, now)
        }
        (focalStudyMillisBetween(entries, start, now) + activeToday) / 60_000L
    }
    val history = remember(entries) {
        entries.filter { it.completed && !focalIsCalendarPlaceholder(it) }.sortedByDescending { it.endedAt }
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
                    Text("Writing time is being saved to Focal as this exam runs. Reading time and pauses are excluded.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (focus == null) {
            TimerHero(HeroMode.IDLE, formatElapsed(0L), "Ready when you are", null, dedicated) {
                Text("What are you studying?", style = MaterialTheme.typography.labelLarge)
                SubjectPicker(state.subjects, subjectId) { subjectId = it }
                if (dedicated) Box {
                    OutlinedButton({ notebookMenu = true }, modifier = Modifier.fillMaxWidth(), shape = FolioShapes.large) {
                        Icon(Icons.Rounded.Book, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp10))
                        Text(selectedNote?.title ?: "No notebook", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    DropdownMenu(notebookMenu, { notebookMenu = false }) {
                        DropdownMenuItem({ Text("No notebook") }, { notebookId = null; notebookMenu = false })
                        notebooks.sortedBy { it.title.lowercase() }.forEach { item ->
                            DropdownMenuItem({ Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                { notebookId = item.id; notebookMenu = false })
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
                        OutlinedTextField(manualMinutes, { manualMinutes = it.filter(Char::isDigit).take(4) },
                            Modifier.fillMaxWidth(), label = { Text("Minutes studied") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        ReflectionFields(notes, { notes = it }, confidence, { confidence = it })
                        Button({
                            manager.logManual(selectedNote, subjectId, manualMinutes.toInt(), notes, confidence.takeIf { it > 0 })
                            manualMinutes = ""; notes = ""; confidence = 0; manualLog = false
                        }, enabled = manualMinutes.toIntOrNull()?.let { it in 1..1_440 } == true,
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
                focalSessionTitle(focus.subjectId, state.subjects), detail, dedicated,
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

    // ── Context column: today's total + sync, other apps' sessions, history. ──
    val contextItems: LazyListScope.() -> Unit = {
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
                        if ((state.userId != null && (state.pendingCount > 0 || state.error != null || state.syncing)) ||
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
                            Text(focalSessionTitle(entry.subjectId, state.subjects), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        Text(focalSessionTitle(entry.subjectId, state.subjects), style = MaterialTheme.typography.titleSmall)
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
            Row(Modifier.fillMaxWidth().then(if (dedicated) Modifier.fillMaxHeight() else Modifier).imePadding(),
                horizontalArrangement = Arrangement.spacedBy(FolioDestinationInset)) {
                Column(Modifier.weight(5f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(start = FolioDestinationInset, top = top, bottom = FolioSpacing.dp16),
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), content = timerBlock)
                LazyColumn(Modifier.weight(6f).fillMaxHeight(),
                    contentPadding = PaddingValues(end = FolioDestinationInset, top = top, bottom = FolioSpacing.dp16),
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), content = contextItems)
            }
        } else {
            LazyColumn((if (dedicated) Modifier.fillMaxWidth() else Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.TopCenter)).imePadding(),
                contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, top = if (dedicated) 0.dp else FolioSpacing.dp16, bottom = FolioSpacing.dp16),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                item(key = "timer") {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), content = timerBlock)
                }
                contextItems()
            }
        }
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
                FilledTonalButton({ manager.finishFocus("", null) }, enabled = focus.elapsed(now) >= 1_000L,
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
            TextButton(onOpenStudy) { Text("Add notes & history") }
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
private fun SubjectPicker(subjects: List<FocalSubject>, selected: String?, onSelect: (String?) -> Unit) {
    if (subjects.size > 8) {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth(), shape = FolioShapes.large) {
                Icon(Icons.Rounded.School, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp10))
                Text(subjects.firstOrNull { it.id == selected }?.name ?: "No subject", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("No subject") }, { onSelect(null); open = false })
                subjects.forEach { DropdownMenuItem({ Text(it.name) }, { onSelect(it.id); open = false }) }
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
