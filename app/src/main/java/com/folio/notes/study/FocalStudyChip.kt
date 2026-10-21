@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.study

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.editor.toolbar.DockButtonHeight
import com.folio.notes.editor.toolbar.DockButtonWidth
import com.folio.notes.editor.toolbar.SessionTone
import com.folio.notes.editor.toolbar.ToolbarSessionPill
import com.folio.notes.exam.ExamTimerPhase
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.input.longPressAction
import com.folio.notes.input.rememberLongPressGuard
import com.folio.notes.ui.FolioSpacing

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
        sharedActive != null -> focalEntryElapsed(sharedActive, now)
        else -> 0L
    }
    val label = when {
        examActive && timer.paused -> "Focal · Paused"
        examActive && timer.phase == ExamTimerPhase.READING -> "Focal · Reading"
        examActive -> "Focal · Writing"
        focus?.resumedAt != null -> "Studying · ${formatStudyChipClock(activeElapsed)}"
        focus != null -> "Focal · ${focalFocusStatus(focus)}"
        sharedActive != null && sharedActive.paused -> "Focal · Paused"
        sharedActive != null -> "Studying · ${formatStudyChipClock(activeElapsed)}"
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
                value = formatStudyChipClock(activeElapsed),
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
