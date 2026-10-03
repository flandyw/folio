package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** A press shorter than this is a tap, which keeps the peek open instead of closing on release. */
private const val PEEK_TAP_MS = 250L

/**
 * The peek eye. Press and hold to look at the pinned view and release to come back; a quick tap
 * keeps it open until the next tap, which is also what TalkBack's double-tap does.
 *
 * [onMode] returns whether the editor accepted the change (it refuses to open mid-stroke), and
 * [mode] is what is actually on screen, so the highlight can never disagree with the page. The
 * highlight is a circle and the idle state transparent, matching the follow controls beside it.
 */
@Composable internal fun PeekControl(mode: PeekMode?, onMode: (PeekMode?) -> Boolean) {
    val current by rememberUpdatedState(mode)
    val callback by rememberUpdatedState(onMode)
    val window = LocalWindowInfo.current
    // Losing the window mid-hold (shade, app switch) has no release event: close the held peek.
    LaunchedEffect(window.isWindowFocused) { if (!window.isWindowFocused && current == PeekMode.HELD) callback(null) }
    DisposableEffect(Unit) { onDispose { if (current == PeekMode.HELD) callback(null) } }
    val open = mode != null
    val label = if (mode == PeekMode.LATCHED) "Close peek" else "Peek at pinned view. Hold to look, tap to keep open"
    Box(Modifier.size(48.dp).clip(CircleShape)
        .background(if (open) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .semantics {
            contentDescription = label
            role = Role.Button
            stateDescription = if (open) "Open" else "Closed"
            onClick(label) { callback(if (current == null) PeekMode.LATCHED else null) }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val wasOpen = current != null
                val opened = !wasOpen && callback(PeekMode.HELD)
                var tap = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            tap = change != null && change.uptimeMillis - down.uptimeMillis < PEEK_TAP_MS
                            break
                        }
                    }
                } finally {
                    // A tap on an open peek closes it; a held press closes on release; a tap keeps it open.
                    when {
                        wasOpen -> callback(null)
                        opened && tap -> callback(PeekMode.LATCHED)
                        opened -> callback(null)
                    }
                }
            }
        }, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Visibility, null, Modifier.size(20.dp),
            tint = if (open) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current)
    }
}
