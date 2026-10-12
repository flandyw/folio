@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*

/**
 * The pages of a score on the music stand: a window of one or two sheets, each fitted to the room
 * the chrome leaves and each an ordinary [EditorPage]. Tapping the left or right half turns the page
 * where a tap would not otherwise draw; the tap is only watched, never consumed.
 */
@Composable internal fun MusicCanvas(note: Notebook, stage: MusicStage, insets: MusicStageInsets, zoomed: Boolean, onFit: () -> Unit, tapTurns: Boolean,
    sheet: @Composable (NotePage, Int) -> Unit) {
    val inputStylusActivity = LocalStylusActivity.current
    val end = minOf(stage.start + stage.step, note.pages.size)
    val turn by rememberUpdatedState(stage.onTurn)
    Row(Modifier.fillMaxSize().padding(start = insets.side, end = insets.side, top = insets.top, bottom = insets.bottom)
        .pointerInput(inputStylusActivity, tapTurns, zoomed) {
            if (!tapTurns && !zoomed) return@pointerInput
            var lastTapAt = 0L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val cancellationSerial = inputStylusActivity.cancellationSerial
                if (down.type != PointerType.Touch) return@awaitEachGesture
                var crowded = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (inputStylusActivity.cancellationSerial != cancellationSerial) { lastTapAt = 0L; break }
                    if (event.changes.size > 1) crowded = true
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.uptimeMillis - down.uptimeMillis > 300L || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    if (!change.pressed) {
                        if (crowded) break
                        // Zoomed in, a tap never turns the page; a quick second tap fits the sheet again.
                        if (zoomed && !stage.performance) {
                            if (change.uptimeMillis - lastTapAt < 320L) { lastTapAt = 0L; onFit() } else lastTapAt = change.uptimeMillis
                        } else turn(down.position.x >= size.width / 2f)
                        break
                    }
                }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(insets.gap)) {
        for (index in stage.start until end) key(note.pages[index].id) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(FolioShapes.medium)) { sheet(note.pages[index], index) }
        }
    }
}

/**
 * Bottom-corner page controls: previous/next, the position, and the single-page view switch.
 * Holding an arrow runs to the first or last page; releasing it never fires a second turn.
 */
