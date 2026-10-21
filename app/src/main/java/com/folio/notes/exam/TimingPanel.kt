@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.exam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.app.AppPrefs
import com.folio.notes.model.Notebook
import com.folio.notes.study.StudyStopwatchContent
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.folioEntrance

/** One entry point for the exam countdown and the Focal-backed study stopwatch. */
@Composable
internal fun TimingPanel(
    timer: ExamTimerState,
    note: Notebook?,
    onOpenStudy: () -> Unit,
    onDismiss: () -> Unit,
    onStartTimer: (ExamTimerPreset) -> Unit,
    onStopTimer: (Int?) -> Unit,
    onAdjustTimer: (Int) -> Unit,
    onSkipTimer: () -> Unit,
    onPauseTimer: () -> Unit
) {
    val focalState by (LocalContext.current.applicationContext as FolioApplication).focalStudy.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(if (timer.phase == ExamTimerPhase.IDLE && focalState.visibleFocus != null) 1 else 0) }
    FolioPanel(title = "Timer & stopwatch", onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp4),
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)
        ) {
            // Each tab says when its clock is live, so a running timer is never hidden behind the other tab.
            val examState = when {
                timer.phase == ExamTimerPhase.IDLE || timer.startedAt == null -> null
                timer.phase == ExamTimerPhase.DONE -> "Time's up"
                timer.paused -> "Paused"
                timer.phase == ExamTimerPhase.READING -> "Reading"
                else -> "Writing"
            }
            val focus = focalState.visibleFocus
            val studyState = when {
                focus == null -> null
                focus.resumedAt != null -> "Running"
                else -> "Paused"
            }
            FilterChip(
                selected = selectedTab == 0, onClick = { selectedTab = 0 },
                label = { Text(examState?.let { "Exam timer · $it" } ?: "Exam timer") }, leadingIcon = { Icon(Icons.Rounded.Timer, null) }
            )
            FilterChip(
                selected = selectedTab == 1, onClick = { selectedTab = 1 },
                label = { Text(studyState?.let { "Study stopwatch · $it" } ?: "Study stopwatch") }, leadingIcon = { Icon(Icons.Rounded.HourglassEmpty, null) }
            )
        }
        Box(Modifier.weight(1f, fill = false).folioEntrance(selectedTab)) {
            if (selectedTab == 0) {
                ExamTimerContent(timer, onStartTimer, onStopTimer, onAdjustTimer, onSkipTimer, onPauseTimer)
            } else {
                StudyStopwatchContent(note, timer, onOpenStudy = { onDismiss(); onOpenStudy() })
            }
        }
    }
}

/** The exam-condition timer: a preset, a live countdown with reading and writing phases, and stop. */
@Composable
internal fun ExamTimerContent(timer: ExamTimerState, onStart: (ExamTimerPreset) -> Unit, onStop: (Int?) -> Unit, onAdjust: (Int) -> Unit, onSkip: () -> Unit, onPauseResume: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val timerPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    val defaultCustom = AppPrefs.timerCustomMinutes(timerPrefs.getInt(AppPrefs.TIMER_CUSTOM_MIN, AppPrefs.DEFAULT_TIMER_CUSTOM_MIN).takeIf { timerPrefs.contains(AppPrefs.TIMER_CUSTOM_MIN) })
    val defaultReading = AppPrefs.timerReadingMinutes(timerPrefs.getInt(AppPrefs.TIMER_READING_MIN, AppPrefs.DEFAULT_TIMER_READING_MIN).takeIf { timerPrefs.contains(AppPrefs.TIMER_READING_MIN) })
    val idleMinutes = AppPrefs.timerIdleMinutes(timerPrefs.getInt(AppPrefs.TIMER_IDLE_MIN, AppPrefs.DEFAULT_TIMER_IDLE_MIN).takeIf { timerPrefs.contains(AppPrefs.TIMER_IDLE_MIN) })
    var customMinutes by rememberSaveable { mutableStateOf("$defaultCustom") }
    Box {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)
        ) {
            when (timer.phase) {
                ExamTimerPhase.IDLE -> {
                    Text("Sit the paper under exam conditions: reading time first, then writing time, counted down live.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ExamTimerPreset.PRESETS.filter { it != ExamTimerPreset.CUSTOM }.forEach { preset ->
                        Surface(
                            onClick = { onStart(preset) },
                            shape = FolioShapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(preset.label, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${preset.readingSeconds / 60} min reading + ${preset.writingSeconds / 60} min writing",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Rounded.PlayArrow, "Start ${preset.label}")
                            }
                        }
                    }
                    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                            Text("Custom", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                                OutlinedTextField(
                                    customMinutes, { customMinutes = it.filter(Char::isDigit).take(3) },
                                    Modifier.weight(1f), label = { Text("Writing minutes") }, singleLine = true,
                                    supportingText = { Text("1–${AppPrefs.TIMER_CUSTOM_MAX} min, after $defaultReading min reading") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                        val minutes = AppPrefs.timerCustomMinutes(customMinutes.toIntOrNull())
                                        onStart(ExamTimerPreset("Custom · $minutes min", minutes * 60, defaultReading * 60))
                                    })
                                )
                                Button({
                                    val minutes = AppPrefs.timerCustomMinutes(customMinutes.toIntOrNull())
                                    onStart(ExamTimerPreset("Custom · $minutes min", minutes * 60, defaultReading * 60))
                                }, shapes = ButtonDefaults.shapes()) { Text("Start") }
                            }
                        }
                    }
                }
                ExamTimerPhase.READING, ExamTimerPhase.WRITING -> {
                    val active = timer.phase == ExamTimerPhase.WRITING
                    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp24),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)
                        ) {
                            Text(
                                if (active) "WRITING TIME" else "READING TIME",
                                style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                timer.clockText(),
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                when {
                                    timer.paused && timer.autoParked -> "Stopped on its own — resume, or just keep writing"
                                    timer.paused -> "Paused — resume, or just keep writing"
                                    active -> "The clock is running"
                                    else -> "No pens yet — read the paper"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (active && !timer.paused && idleMinutes > 0) Text(
                                "Stops on its own after $idleMinutes min without writing",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Button(onPauseResume, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                        Icon(if (timer.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text(if (timer.paused) "Resume timer" else "Pause timer")
                    }
                    Text("Adjust ${if (active) "writing" else "reading"} time", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        listOf(-5, -1, 1, 5).forEach { minutes ->
                            OutlinedButton({ onAdjust(minutes * 60) }, modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = FolioSpacing.dp4, vertical = FolioSpacing.dp8),
                                shapes = ButtonDefaults.shapes()) {
                                Text("${if (minutes > 0) "+" else "−"}${kotlin.math.abs(minutes)} min")
                            }
                        }
                    }
                    FilledTonalButton(onSkip, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                        Text(if (active) "Finish writing now" else "Skip reading · start writing")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                        TextButton({ onStop(null) }, shapes = ButtonDefaults.shapes()) { Text("Stop timer") }
                    }
                }
                ExamTimerPhase.DONE -> {
                    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                            Text("PENS DOWN", style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Time is up.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                        TextButton({ onStop(timer.elapsedWriting()) }, shapes = ButtonDefaults.shapes()) { Text("Record the sitting") }
                    }
                }
            }
        }
    }
}
