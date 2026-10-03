@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun FocalStudyChip(timer: ExamTimerState, onClick: () -> Unit) {
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
        focus != null -> "Focal · Paused"
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
        if (recording) {
            Surface(
                onClick = onClick,
                modifier = Modifier.padding(horizontal = FolioSpacing.dp4).height(36.dp).semantics {
                    contentDescription = "$label. $syncStatus. Open study sessions"
                },
                shape = FolioShapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Row(Modifier.padding(horizontal = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Icon(if (isPaused) Icons.Rounded.Pause else Icons.Rounded.School, null, Modifier.size(15.dp))
                    Text(when {
                        examActive -> label.removePrefix("Focal · ")
                        isPaused -> "Paused · ${formatChipElapsed(activeElapsed)}"
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

/** One set of controls for the dedicated destination and the editor's quick panel. */
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
    var subjectMenu by remember { mutableStateOf(false) }
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
    LazyColumn(Modifier.fillMaxWidth().imePadding(),
        contentPadding = PaddingValues(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp16),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
        item(key = "controls") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    Icon(Icons.Rounded.School, null)
                    Column(Modifier.weight(1f)) {
                        val minutes = today % 60
                        val hours = today / 60
                        val duration = if (hours == 0L) "$today ${if (today == 1L) "minute" else "minutes"}"
                            else "$hours ${if (hours == 1L) "hour" else "hours"} $minutes ${if (minutes == 1L) "minute" else "minutes"}"
                        Text("$duration studied today", style = MaterialTheme.typography.titleMedium)
                        Text("Active time from study sessions only — pauses are excluded.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

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
                if (dedicated) {
                    Text("Focus on your work", style = MaterialTheme.typography.headlineSmall)
                    Text("Count active study time, with or without a notebook. Pauses are never counted.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        OutlinedButton({ notebookMenu = true }, modifier = Modifier.fillMaxWidth()) {
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
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Text("Subject", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        OutlinedButton(
                            onClick = { subjectMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp12),
                            shape = FolioShapes.large
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.School, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(FolioSpacing.dp10))
                                Text(
                                    state.subjects.firstOrNull { it.id == subjectId }?.name ?: "No subject",
                                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
                            }
                        }
                        DropdownMenu(subjectMenu, onDismissRequest = { subjectMenu = false }) {
                            DropdownMenuItem(text = { Text("No subject") }, onClick = { subjectId = null; subjectMenu = false })
                            state.subjects.forEach { subject ->
                                DropdownMenuItem(text = { Text(subject.name) }, onClick = { subjectId = subject.id; subjectMenu = false })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                    Button({ manager.startFocus(selectedNote, subjectId) }, enabled = examRecording == null && state.canStartFocus && entries.none { it.active && !it.paused },
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Start study")
                    }
                    TextButton({ manualLog = !manualLog }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                        Text(if (manualLog) "Cancel manual entry" else "Log without timer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (entries.any { it.active && !it.paused }) Text("Pause or finish your running session before starting another.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FolioExpand(manualLog) {
                    OutlinedTextField(manualMinutes, { manualMinutes = it.filter(Char::isDigit).take(4) },
                        Modifier.fillMaxWidth(), label = { Text("Minutes studied") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(notes, { notes = it.take(500) }, Modifier.fillMaxWidth(),
                        label = { Text("What did you work on? (optional)") }, minLines = 2)
                    Text("Confidence (optional)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        (1..5).forEach { score ->
                            FilterChip(selected = confidence == score, onClick = { confidence = if (confidence == score) 0 else score },
                                label = { Text("$score") })
                        }
                    }
                    Button({
                        manager.logManual(selectedNote, subjectId, manualMinutes.toInt(), notes, confidence.takeIf { it > 0 })
                        manualMinutes = ""; notes = ""; confidence = 0; manualLog = false
                    }, enabled = manualMinutes.toIntOrNull()?.let { it in 1..1_440 } == true,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Save past study") }
                }
            } else if (focus != null) {
                Text(focalSessionTitle(focus.subjectId, state.subjects), style = MaterialTheme.typography.bodyMedium)
                Text(formatElapsed(focus.elapsed(now)), style = if (dedicated) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { contentDescription = "Active study time, ${formatElapsed(focus.elapsed(now))}" })
                focus.notebookTitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(if (focus.resumedAt == null) "Paused" else "Recording active time", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dedicated) Text("The timer continues when you leave this page. If Folio restarts, it recovers paused at the last saved checkpoint (up to 30 seconds earlier).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (examRecording != null && focus.resumedAt == null) {
                    Text("Resume regular study after the exam timer stops.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        { manager.toggleFocus() },
                        enabled = focus.resumedAt != null || (examRecording == null && entries.none { it.active && !it.paused && it.id != focus.sessionId }),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (focus.resumedAt == null) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text(if (focus.resumedAt == null) "Resume study" else "Pause study")
                    }
                    OutlinedButton(
                        { discardId = focus.sessionId },
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text("Discard")
                    }
                }
                OutlinedTextField(notes, { notes = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("What did you work on? (optional)") }, minLines = 2)
                Text("How confident do you feel? (optional)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    (1..5).forEach { score ->
                        FilterChip(selected = confidence == score, onClick = { confidence = if (confidence == score) 0 else score },
                            label = { Text("$score") })
                    }
                }
                Button({ manager.finishFocus(notes, confidence.takeIf { it > 0 }); notes = ""; confidence = 0 },
                    enabled = focus.elapsed(now) >= 1_000L,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Save session") }
            }

            val sharedActive = remember(entries, focus?.sessionId, examRecording?.startedAt) {
                entries.filter { entry ->
                    entry.active && entry.id != focus?.sessionId &&
                        !(entry.kind == "exam" && examRecording?.startedAt == entry.startedAt)
                }
            }
            if (sharedActive.isNotEmpty()) {
                HorizontalDivider()
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
                            val title = focalSessionTitle(entry.subjectId, state.subjects)
                            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${if (entry.kind == "exam") "Timed exam" else "Study"} · ${if (entry.paused) "Paused" else "In progress"} · ${formatElapsed(elapsed)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Icon(when {
                    state.syncing -> Icons.Rounded.Sync
                    state.error != null -> Icons.Rounded.ErrorOutline
                    state.userId == null || !state.configured || state.pendingCount > 0 -> Icons.Rounded.CloudOff
                    else -> Icons.Rounded.CloudDone
                }, null)
                Text(state.syncStatus,
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if ((state.userId != null && (state.pendingCount > 0 || state.error != null || state.syncing)) ||
                    state.localSaveFailed) {
                    TextButton({ manager.retry() }, enabled = !state.syncing) {
                        Text(if (state.syncing) "Retrying…" else "Retry")
                    }
                }
            }
            if (state.error != null && !state.syncing) Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (dedicated) OutlinedButton(onAccount, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.userId == null) "Connect Focal" else "Focal account")
            } else com.folio.notes.mistakes.FocalAccountContent()

            }
        }
        if (recent.isNotEmpty()) {
            item(key = "history-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    HorizontalDivider()
                    Text(if (dedicated) "Session history" else "Recent sessions", style = MaterialTheme.typography.titleMedium)
                }
            }
            itemsIndexed(recent, key = { _, entry -> entry.id }) { index, entry ->
                Column {
                    if (index > 0) HorizontalDivider()
                    val title = focalSessionTitle(entry.subjectId, state.subjects)
                    val minutes = (focalActiveMillisBetween(entry, entry.startedAt, entry.endedAt) / 60_000L)
                        .toInt().coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp6), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            val subject = state.subjects.firstOrNull { it.id == entry.subjectId }?.name ?: "No subject"
                            val details = listOf(if (entry.kind == "exam") "Exam" else "Study", subject,
                                entry.notebookTitle, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.endedAt)))
                                .filterNot { it.isNullOrBlank() }.joinToString(" · ")
                            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (dedicated) {
                                if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall)
                                entry.confidence?.let { Text("Confidence $it/5", style = MaterialTheme.typography.labelSmall) }
                                Text(if (entry.synced) "Synced with Focal" else if (entry.userId == null) "Saved on this device" else "Saved locally · awaiting sync", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("$minutes min", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else if (dedicated) item(key = "empty-history") {
            Text("No completed sessions yet. Your saved study will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dedicated && history.size > historyLimit) item(key = "more-history") {
            TextButton({ historyLimit += 20 }) { Text("Show more sessions") }
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
