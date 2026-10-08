package com.folio.notes

/** Window-wide pen proximity, shared with popup controls. Times are monotonic input event times. */
internal class StylusActivity {
    var graceMs = 500L
    /** Compose recognizers must distinguish cancellation from an ordinary release. */
    var cancellationSerial = 0L; private set
    private var lastSeen: Long? = null
    private var touching = false
    private var hovering = false

    fun record(now: Long) { lastSeen = now }
    fun contact(now: Long, down: Boolean) { record(now); touching = down; if (down) hovering = false }
    fun hover(now: Long, inRange: Boolean) { record(now); hovering = inRange }
    fun clear() { lastSeen = null; touching = false; hovering = false }
    fun canceled() { cancellationSerial++ }
    fun isRecent(now: Long): Boolean = graceMs > 0 &&
        (touching || hovering || lastSeen?.let { now - it in 0 until graceMs } == true)
}

/**
 * Pointer lifetime rules, independent of Android. IDs are Android's 0..31 pointer IDs, not indices.
 * The adapter copies admitted pointers using [dispatchMask], retaining pressure/history.
 * A rejected contact can only return after it lifts and a new DOWN arrives.
 */
internal class PalmRejection(private val stylus: StylusActivity) {
    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    var dispatchMask = 0; private set
    var cancel = false; private set
    private var forwarded = 0
    private var forwardedTouches = 0
    private var rejected = 0

    fun reset() { forwarded = 0; forwardedTouches = 0; rejected = 0; dispatchMask = 0; cancel = false }

    fun route(action: Action, ids: Int, pens: Int, touches: Int, actionId: Int,
              time: Long, canceled: Boolean = false) {
        dispatchMask = 0
        cancel = false
        if (action == Action.CANCEL) {
            cancel = forwarded != 0
            forwarded = 0; forwardedTouches = 0; rejected = 0
            return
        }
        if (action == Action.DOWN) {
            cancel = forwarded != 0
            forwarded = 0; rejected = 0
        }
        val bit = 1 shl actionId
        val down = action == Action.DOWN || action == Action.POINTER_DOWN
        val up = action == Action.UP || action == Action.POINTER_UP
        // Android 13+ cancels only the pointer named by ACTION_POINTER_UP + FLAG_CANCELED.
        // If it owned a touch gesture, abandon the chord/pinch as well, without a release/fling.
        if (up && canceled) {
            rejected = rejected or bit
            if (forwarded and bit != 0) {
                cancel = true
                rejected = rejected or ids
                forwarded = 0
            }
        }
        val pen = pens and rejected.inv()
        if (pen != 0) {
            rejected = rejected or touches
            // A tip always owns its own stream, even when proximity filtering is disabled.
            val owner = Integer.lowestOneBit(pen)
            if (forwarded != 0 && forwarded != owner) {
                cancel = true
                rejected = rejected or (ids and owner.inv())
                forwarded = 0
            }
            if (forwarded == owner || down && bit == owner) dispatchMask = owner
        } else {
            if (stylus.isRecent(time)) rejected = rejected or touches
            if (forwarded and rejected != 0) {
                cancel = true
                rejected = rejected or ids
                forwarded = 0
            }
            val admitted = if (down) bit and rejected.inv() else 0
            dispatchMask = (forwarded or admitted) and ids and rejected.inv()
        }
        forwarded = dispatchMask
        if (up) {
            forwarded = forwarded and bit.inv()
            rejected = rejected and bit.inv()
        }
        if (action == Action.UP) { forwarded = 0; rejected = 0 }
        forwardedTouches = forwarded and touches
    }

    /** Hover can reject a touch gesture before the tip lands. Its held contacts stay quarantined. */
    fun rejectTouches(): Boolean {
        if (forwardedTouches == 0) return false
        rejected = rejected or forwarded
        forwarded = 0
        forwardedTouches = 0
        return true
    }
}

/** Android action values are stable; mapping is pure so pointer-index transitions are traceable. */
internal fun filteredPointerAction(action: Int, count: Int, changedIndex: Int): Int = when (action) {
    5, 6 -> when {
        changedIndex < 0 -> 2 // An unrelated pointer changed: this stream only moved.
        count == 1 -> if (action == 5) 0 else 1 // DOWN / UP for the sole admitted pointer.
        else -> action or (changedIndex shl 8)
    }
    else -> action
}
