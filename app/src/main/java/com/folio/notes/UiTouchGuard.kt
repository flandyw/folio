package com.folio.notes

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
