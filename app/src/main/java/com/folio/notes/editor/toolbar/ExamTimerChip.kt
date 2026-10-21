@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.editor.toolbar

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.notes.exam.ExamTimerPhase
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.input.longPressAction
import com.folio.notes.input.rememberLongPressGuard

/**
 * One timing action while idle, with live countdown and elapsed clocks when active.
 */
@Composable internal fun ExamTimerChip(timer: ExamTimerState, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    // The hold claims the gesture so the release after it never also opens the timer panel.
    val hold = rememberLongPressGuard()
    val active = timer.phase == ExamTimerPhase.READING || timer.phase == ExamTimerPhase.WRITING || timer.phase == ExamTimerPhase.DONE
    Crossfade(targetState = active, label = "timingChip") { isActive ->
        if (!isActive) {
            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text("Timer & stopwatch") } }, state = rememberTooltipState()) {
                IconButton(onClick, modifier = Modifier.size(DockButtonWidth, DockButtonHeight), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Timer, "Timer & stopwatch", Modifier.size(20.dp)) }
            }
        } else {
            val done = timer.phase == ExamTimerPhase.DONE
            val reading = timer.phase == ExamTimerPhase.READING
            val caption = when {
                timer.paused -> if (timer.autoParked) "Stopped" else "Paused"
                done -> "Time"
                reading -> "Reading"
                else -> "Writing"
            }
            val phaseSeconds = if (reading) timer.preset.readingSeconds else timer.preset.writingSeconds
            val clock = if (done) "Pens down" else timer.clockText()
            ToolbarSessionPill(
                icon = when {
                    done -> Icons.Rounded.Flag
                    timer.paused -> Icons.Rounded.Pause
                    reading -> Icons.AutoMirrored.Rounded.MenuBook
                    else -> Icons.Rounded.Timer
                },
                value = clock,
                tone = when {
                    done -> SessionTone.ALERT
                    timer.paused -> SessionTone.PAUSED
                    else -> SessionTone.RUNNING
                },
                reserve = if (done) null else if (phaseSeconds >= 3600) "0:00:00" else "00:00",
                description = "Exam timer, $caption, ${if (done) clock else "$clock left"}. Open timer" +
                    if (onLongClick != null && !done) ". Hold to ${if (timer.paused) "resume" else "pause"}" else "",
                onClick = hold.click(onClick),
                modifier = if (onLongClick != null) Modifier.longPressAction(hold, onLongClick) else Modifier
            )
        }
    }
}
