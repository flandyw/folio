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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun FocalStudyPanel(note: Notebook?, onDismiss: () -> Unit) {
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
    val today = remember(now / 60_000L, state.visibleEntries) {
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        state.visibleEntries.filter { it.endedAt >= start }.sumOf { it.activeMillis } / 60_000L
    }
    FolioPanel("Study sessions", onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.School, null)
                    Column(Modifier.weight(1f)) {
                        Text("$today min recorded in Folio today", style = MaterialTheme.typography.titleMedium)
                        Text("Exam sittings and regular study are logged separately.", style = MaterialTheme.typography.bodySmall)
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
                Button({ manager.startFocus(note, subjectId) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
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
                Text(focus.title, style = MaterialTheme.typography.bodyMedium)
                Text(formatElapsed(focus.elapsed(now)), style = MaterialTheme.typography.headlineLarge)
                Text(if (focus.resumedAt == null) "Paused" else "Recording active time", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ manager.toggleFocus() }, shapes = ButtonDefaults.shapes()) {
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

            if (state.visibleEntries.isNotEmpty()) {
                HorizontalDivider()
                Text("Recent sessions", style = MaterialTheme.typography.titleMedium)
                state.visibleEntries.take(8).forEach { entry ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                                Text("${if (entry.kind == "exam") "Exam" else "Study"} · ${state.subjects.firstOrNull { it.id == entry.subjectId }?.name ?: "No subject"} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.endedAt))}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${entry.minutes} min", style = MaterialTheme.typography.labelLarge)
                        }
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
