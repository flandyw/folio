package com.folio.notes

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Pointer cancellation, disposal and focus loss all release the temporary reference. */
@Composable internal fun PeekHoldButton(anchor: PeekAnchor, onHeld: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onHeld)
    val window = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(window.isWindowFocused) { if (!window.isWindowFocused) callback(false) }
    DisposableEffect(Unit) { onDispose { callback(false) } }
    Box(Modifier.size(48.dp).semantics { contentDescription = "Hold to peek; release to return" }
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
        Icon(Icons.Rounded.Visibility, null, tint = MaterialTheme.colorScheme.primary)
    }
}
