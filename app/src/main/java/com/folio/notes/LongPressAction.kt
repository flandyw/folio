package com.folio.notes

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Adds a long-press secondary action to any control without disturbing its own taps.
 *
 * The detector sits beside the control's own clickable in the modifier chain, so a tap still
 * runs the button while a hold (with the standard long-press tick) runs [onLongClick] — a
 * context menu, a quick toggle, or a shortcut for the button's rarer sibling action.
 */
@Composable
fun Modifier.longPressAction(onLongClick: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(onLongClick)
    return pointerInput(Unit) {
        detectTapGestures(onLongPress = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            current()
        })
    }
}
