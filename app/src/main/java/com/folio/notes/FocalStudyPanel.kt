@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun FocalStudyChip(timer: ExamTimerState, onClick: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val examActive = timer.active && timer.startedAt != null
    val focus = state.focus
    val sharedActive = state.visibleEntries.firstOrNull { !it.completed && !it.deleted }
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
    val syncStatus = when {
        state.error != null -> "Sync needs attention"
        state.userId == null || !state.configured -> "Saved on this device"
        state.syncing -> "Syncing with Focal"
        state.pendingCount > 0 -> "Waiting to sync"
        else -> "Synced with Focal"
    }
    val syncIcon = when {
        state.error != null -> Icons.Rounded.ErrorOutline
        state.userId == null || !state.configured || state.pendingCount > 0 -> Icons.Rounded.CloudOff
        state.syncing -> Icons.Rounded.Sync
        else -> Icons.Rounded.CloudDone
    }
    val recording = examActive || focus != null || sharedActive != null
    val isPaused = when {
        examActive -> timer.paused
        focus != null -> focus.resumedAt == null
        else -> sharedActive?.paused == true
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (recording) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.semantics { contentDescription = "$label. $syncStatus. Open study sessions" }) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(if (recording && isPaused) Icons.Rounded.Pause
                else if (recording) Icons.Rounded.PlayArrow else Icons.Rounded.School,
                null, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(syncIcon, null, Modifier.size(15.dp),
                tint = if (state.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current)
        }
    }
}

@Composable
fun FocalStudyPanel(note: Notebook?, examTimer: ExamTimerState? = null, onDismiss: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    var subjectId by remember(note?.id) { mutableStateOf(note?.let { FocalSubjects.suggest(it) }) }
    var subjectMenu by remember { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var confidence by rememberSaveable { mutableIntStateOf(0) }
    var manualLog by rememberSaveable { mutableStateOf(false) }
    var manualMinutes by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val emailValid = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        manager.retry()
        while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() }
    }
    val focus = state.focus
    val examRecording = examTimer?.takeIf { it.active && it.startedAt != null }
    val activeElsewhere = state.visibleEntries.any { !it.completed && !it.deleted && it.id != focus?.sessionId &&
        !(it.kind == "exam" && examRecording?.startedAt == it.startedAt) }
    val today = remember(now / 60_000L, state.visibleEntries) {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        focalStudyMillisBetween(state.visibleEntries, start, now) / 60_000L
    }
    FolioPanel("Study sessions", onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.School, null)
                    Column(Modifier.weight(1f)) {
                        Text("$today min studied today", style = MaterialTheme.typography.titleMedium)
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
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Exam recording · $phase", style = MaterialTheme.typography.titleSmall)
                        Text("Writing time is being saved to Focal as this exam runs. Reading time and pauses are excluded.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text("Regular study", style = MaterialTheme.typography.titleMedium)
            if (focus == null && note == null) {
                Text("Open a notebook to start recording study. You can connect Focal and review recent sessions here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (focus == null && note != null) {
                Text("Record reading, revision, homework or practice outside exam conditions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton({ subjectMenu = true }, shapes = ButtonDefaults.shapes()) {
                        Text(state.subjects.firstOrNull { it.id == subjectId }?.name ?: "Choose a subject")
                    }
                    DropdownMenu(subjectMenu, onDismissRequest = { subjectMenu = false }) {
                        DropdownMenuItem(text = { Text("No subject") }, onClick = { subjectId = null; subjectMenu = false })
                        state.subjects.forEach { subject ->
                            DropdownMenuItem(text = { Text(subject.name) }, onClick = { subjectId = subject.id; subjectMenu = false })
                        }
                    }
                }
                val suggested = FocalSubjects.suggest(note, state.subjects)
                if (suggested != null && suggested == subjectId) {
                    Text("Suggested from this notebook", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button({ manager.startFocus(note, subjectId) }, enabled = examRecording == null && !activeElsewhere,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start study")
                }
                TextButton({ manualLog = !manualLog }) { Text(if (manualLog) "Cancel manual entry" else "Log study without a timer") }
                if (manualLog) {
                    OutlinedTextField(manualMinutes, { manualMinutes = it.filter(Char::isDigit).take(4) },
                        Modifier.fillMaxWidth(), label = { Text("Minutes studied") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(notes, { notes = it.take(500) }, Modifier.fillMaxWidth(),
                        label = { Text("What did you work on? (optional)") }, minLines = 2)
                    Text("Confidence (optional)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..5).forEach { score ->
                            FilterChip(selected = confidence == score, onClick = { confidence = if (confidence == score) 0 else score },
                                label = { Text("$score") })
                        }
                    }
                    Button({
                        manager.logManual(note, subjectId, manualMinutes.toInt(), notes, confidence.takeIf { it > 0 })
                        manualMinutes = ""; notes = ""; confidence = 0; manualLog = false
                    }, enabled = manualMinutes.toIntOrNull()?.let { it in 1..1_440 } == true,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Save past study") }
                }
            } else if (focus != null) {
                Text(focalSessionTitle(focus.subjectId, state.subjects), style = MaterialTheme.typography.bodyMedium)
                Text(formatElapsed(focus.elapsed(now)), style = MaterialTheme.typography.headlineLarge)
                Text(if (focus.resumedAt == null) "Paused" else "Recording active time", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (examRecording != null && focus.resumedAt == null) {
                    Text("Resume regular study after the exam timer stops.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ manager.toggleFocus() }, enabled = examRecording == null || focus.resumedAt != null,
                        shapes = ButtonDefaults.shapes()) {
                        Icon(if (focus.resumedAt == null) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                        Spacer(Modifier.width(6.dp)); Text(if (focus.resumedAt == null) "Resume" else "Pause")
                    }
                    TextButton({ manager.discardFocus() }, shapes = ButtonDefaults.shapes()) { Text("Discard") }
                }
                OutlinedTextField(notes, { notes = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("What did you work on? (optional)") }, minLines = 2)
                Text("How confident do you feel? (optional)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { score ->
                        FilterChip(selected = confidence == score, onClick = { confidence = if (confidence == score) 0 else score },
                            label = { Text("$score") })
                    }
                }
                Button({ manager.finishFocus(notes, confidence.takeIf { it > 0 }); notes = ""; confidence = 0 },
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Save session") }
            }

            val sharedActive = state.visibleEntries.filter { entry ->
                !entry.completed && !entry.deleted && entry.id != focus?.sessionId &&
                    !(entry.kind == "exam" && examRecording?.startedAt == entry.startedAt)
            }
            if (sharedActive.isNotEmpty()) {
                HorizontalDivider()
                Text("Active across your apps", style = MaterialTheme.typography.titleMedium)
                sharedActive.take(6).forEach { entry ->
                    val elapsed = entry.intervals.sumOf { interval ->
                        ((interval.endAt ?: now) - interval.startAt).coerceAtLeast(0L)
                    }.coerceAtLeast(entry.activeMillis)
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val title = focalSessionTitle(entry.subjectId, state.subjects)
                            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${if (entry.kind == "exam") "Timed exam" else "Study"} · ${if (entry.paused) "Paused" else "In progress"} · ${formatElapsed(elapsed)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ manager.controlEntry(entry.id, if (entry.paused) "resume" else "pause") },
                                    shapes = ButtonDefaults.shapes()) {
                                    Icon(if (entry.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                                    Spacer(Modifier.width(6.dp)); Text(if (entry.paused) "Resume" else "Pause")
                                }
                                TextButton({ manager.controlEntry(entry.id, "finish") }) { Text("Finish") }
                                TextButton({ manager.controlEntry(entry.id, "discard") }) { Text("Discard") }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (state.pendingCount == 0) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff, null)
                Text(if (state.userId == null) "Saved on this device" else if (state.error != null) "Focal sync needs attention" else if (state.pendingCount == 0) "Synced with Focal" else "${state.pendingCount} waiting to sync",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (state.userId != null && (state.pendingCount > 0 || state.error != null)) TextButton({ manager.retry() }) { Text("Retry") }
            }
            if (state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (state.authMessage != null) Text(state.authMessage!!,
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            if (state.userId == null) {
                Text("Connect Focal", style = MaterialTheme.typography.titleMedium)
                if (!state.configured) {
                    Text("Focal sync is not configured in this build. Sessions stay available on this device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Use your Focal account to see these sessions on your other devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
                        isError = email.isNotBlank() && !emailValid, enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        trailingIcon = { TextButton({ showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } })
                    Button({ val secret = password; password = ""; manager.signIn(email, secret) },
                        enabled = emailValid && password.isNotEmpty() && !state.busy,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.busy) "Signing in…" else "Sign in to Focal")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ val secret = password; password = ""; manager.signUp(email, secret) },
                            enabled = emailValid && password.length >= 6 && !state.busy) { Text("Create account") }
                        TextButton({ manager.resetPassword(email) }, enabled = emailValid && !state.busy) { Text("Forgot password?") }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.email ?: "Focal connected", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ manager.signOut() }) { Text("Sign out") }
                }
            }

            val recent = state.visibleEntries.filter { it.completed }
                .sortedByDescending { it.endedAt }
                .take(5)
            if (recent.isNotEmpty()) {
                HorizontalDivider()
                Text("Recent sessions", style = MaterialTheme.typography.titleMedium)
                recent.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider()
                    val title = focalSessionTitle(entry.subjectId, state.subjects)
                    val minutes = (focalActiveMillisBetween(entry, entry.startedAt, entry.endedAt) / 60_000L)
                        .toInt().coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            val subject = state.subjects.firstOrNull { it.id == entry.subjectId }?.name ?: "No subject"
                            val details = listOf(if (entry.kind == "exam") "Exam" else "Study", subject,
                                entry.notebookTitle, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.endedAt)))
                                .filterNot { it.isNullOrBlank() }.joinToString(" · ")
                            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("$minutes min", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
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
