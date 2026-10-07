@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
            FilterChip(
                selected = selectedTab == 0, onClick = { selectedTab = 0 },
                label = { Text("Exam timer") }, leadingIcon = { Icon(Icons.Rounded.Timer, null) }
            )
            FilterChip(
                selected = selectedTab == 1, onClick = { selectedTab = 1 },
                label = { Text("Study stopwatch") }, leadingIcon = { Icon(Icons.Rounded.HourglassEmpty, null) }
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
