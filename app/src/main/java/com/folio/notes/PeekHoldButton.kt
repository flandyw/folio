package com.folio.notes

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Hold to peek at the anchored view, release to come back.
 *
 * The highlight is a circle and the idle state is transparent, matching [WritingFollowControl]
 * beside it: the follow strip's own corners are nearly square, so a rounded square inside it
 * read as a mismatched chip. Only the held state has a visible shape, so every state stays the
 * same size and the row keeps its rhythm. [held] comes from the editor, which decides whether a
 * press may actually open the peek, so the highlight can never disagree with what is on screen.
 */
@Composable internal fun PeekHoldButton(anchor: PeekAnchor, held: Boolean, onHeld: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onHeld)
    val window = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(window.isWindowFocused) { if (!window.isWindowFocused) callback(false) }
    DisposableEffect(Unit) { onDispose { callback(false) } }
    Box(Modifier.size(48.dp).clip(CircleShape)
        .background(if (held) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .semantics { contentDescription = "Hold to peek; release to return" }
        .pointerInput(anchor) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                callback(true)
                try {
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.id == down.id && it.pressed })
                } finally { callback(false) }
            }
        }, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Visibility, null, Modifier.size(20.dp),
            tint = if (held) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current)
    }
}