@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

/** One entry point for the independent countdown and count-up clocks. */
@Composable
internal fun TimingPanel(
    timer: ExamTimerState,
    stopwatch: StopwatchState,
    onDismiss: () -> Unit,
    onStartTimer: (ExamTimerPreset) -> Unit,
    onStopTimer: (Int?) -> Unit,
    onAdjustTimer: (Int) -> Unit,
    onSkipTimer: () -> Unit,
    onPauseTimer: () -> Unit,
    onStartStopwatch: () -> Unit,
    onPauseStopwatch: () -> Unit,
    onResetStopwatch: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(if (timer.phase == ExamTimerPhase.IDLE && stopwatch.active) 1 else 0) }
    FolioPanel(title = "Timer & stopwatch", onDismissRequest = onDismiss) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Exam timer") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Stopwatch") })
        }
        Box(Modifier.weight(1f, fill = false)) {
            if (selectedTab == 0) {
                ExamTimerContent(timer, onStartTimer, onStopTimer, onAdjustTimer, onSkipTimer, onPauseTimer)
            } else {
                StopwatchContent(stopwatch, onStartStopwatch, onPauseStopwatch, onResetStopwatch)
            }
        }
    }
}
