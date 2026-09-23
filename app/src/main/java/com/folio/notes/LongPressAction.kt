package com.folio.notes

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

private const val HOLD_CLAIM_MS = 800L

/**
 * Tap-and-hold pairing for one control or gesture area.
 *
 * A plain clickable treats hold-then-release as an ordinary tap (its up-event check runs before
 * any consumption check), so a long-press action bolted onto a button double-fires: the hold
 * action, then the button's click on release. This guard closes that gap — the hold claims the
 * gesture, and [click] wraps the control's tap so the release that follows a hold is swallowed.
 * Exactly one of the two actions ever runs.
 *
 * Wire both sides: attach [Modifier.longPressAction] beside the control's clickable and pass the
 * tap through [click]:
 *
 * ```
 * val hold = rememberLongPressGuard()
 * IconButton(
 *     onClick = hold.click { primary() },
 *     modifier = Modifier.longPressAction(hold) { secondary() })
 * ```
 */
@Stable
class LongPressGuard internal constructor() {
    private var heldAt = 0L

    /**
     * Wraps a control's tap action so the release after a hold never also triggers it. Each
     * gesture's claim is spent by the first click that sees it, so the next tap runs normally.
     */
    fun click(action: () -> Unit): () -> Unit = {
        val claimed = System.currentTimeMillis() - heldAt <= HOLD_CLAIM_MS
        heldAt = 0L
        if (!claimed) action()
    }

    /** Marks the gesture as claimed by a hold; the release's tap is swallowed. */
    fun claim() {
        heldAt = System.currentTimeMillis()
    }

    /** Every fresh press starts unclaimed, so a tap right after a hold still clicks. */
    internal fun begin() {
        heldAt = 0L
    }
}

@Composable
fun rememberLongPressGuard(): LongPressGuard = remember { LongPressGuard() }

/**
 * The hold side of a tap-and-hold pair: [onLongClick] fires with the long-press tick and claims
 * [guard]. The control's own tap must run through [LongPressGuard.click], or the release after
 * the hold will fire it too.
 */
@Composable
fun Modifier.longPressAction(guard: LongPressGuard, onLongClick: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(onLongClick)
    return pointerInput(guard) {
        detectTapGestures(
            onPress = { guard.begin() },
            onLongPress = {
                guard.claim()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                current()
            }
        )
    }
}
