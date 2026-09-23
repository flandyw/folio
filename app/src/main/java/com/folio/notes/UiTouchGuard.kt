package com.folio.notes

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/** Shared across the editor and its separate dialog/popup windows; uses monotonic event times. */
internal class StylusActivity {
    private var lastSeen: Long? = null
    fun record(now: Long) { lastSeen = now }
    fun isRecent(now: Long): Boolean = lastSeen?.let { now - it in 0 until 500L } ?: false
}

/** Once rejected, a contact stays rejected until it lifts, even when the pen moves away. */
internal class UiTouchGesture(private val stylus: StylusActivity) {
    private val rejected = mutableSetOf<Long>()
    fun reject(id: Long, touch: Boolean, pressed: Boolean, now: Long): Boolean {
        if (touch && stylus.isRecent(now)) rejected += id
        val reject = touch && id in rejected
        if (!pressed) rejected -= id
        return reject
    }
}

internal val LocalStylusActivity = staticCompositionLocalOf { StylusActivity() }

/** A pen tip: the stylus and the stylus eraser, the contacts that leave ink rather than navigate. */
internal fun isPen(type: PointerType): Boolean =
    type == PointerType.Stylus || type == PointerType.Eraser

/** True for a pen contact nothing has claimed, so the workspace must not read it as a drag. */
internal fun holdsPenFromScrolling(type: PointerType, consumed: Boolean): Boolean =
    isPen(type) && !consumed

/**
 * Keeps a pen that no page has taken from dragging the page column. Without it a pen that lands on
 * a page before that page's ink surface is mounted — its ink still being read, or a PDF page still
 * rendering — scrolls the document instead of writing, so the stroke is lost and the page slides
 * under the hand. It also stops a pen drag in the gaps between pages or the margin beside them from
 * scrolling, since a pen is never how this workspace is navigated.
 *
 * The claim is read at the Final pass of the touch-down: by then every page, button, slider and
 * scroller has had its turn, so anything still unclaimed truly belongs to nobody. A pen a page did
 * take is left completely alone — the whole gesture is ignored — so ink is never at risk, and
 * finger scrolling over the column keeps working because only pen contacts are held.
 */
internal fun Modifier.holdPenFromScrolling(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!isPen(down.type)) return@awaitEachGesture
        val claimed = awaitPointerEvent(PointerEventPass.Final)
            .changes.firstOrNull { it.id == down.id }?.isConsumed == true
        if (claimed) return@awaitEachGesture
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { if (holdsPenFromScrolling(it.type, it.isConsumed)) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** Consume palm contacts before buttons, text fields and sliders see them; pen/mouse still work. */
internal fun Modifier.guardUiTouches(): Modifier = composed {
    val stylus = LocalStylusActivity.current
    pointerInput(stylus) {
        val gesture = UiTouchGesture(stylus)
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.filter { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
                    .forEach { stylus.record(it.uptimeMillis) }
                event.changes.forEach { change ->
                    if (gesture.reject(change.id.value, change.type == PointerType.Touch,
                            change.pressed, change.uptimeMillis)) change.consume()
                }
            }
        }
    }
}
