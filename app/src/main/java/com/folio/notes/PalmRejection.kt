package com.folio.notes

/** Window-wide pen proximity, shared with popup controls. Times are monotonic input event times. */
internal class StylusActivity {
    var graceMs = 500L
    /** Compose recognizers must distinguish cancellation from an ordinary release. */
    var cancellationSerial = 0L; private set
    private var lastSeen: Long? = null
    private var touching = false
    private var hovering = false
    private var navigationSurfaces = 0
    private var navigationDevice = -1
    private var navigationStart = -1L
    val isTouching get() = touching
    val canPinchNavigate get() = navigationSurfaces > 0

    fun attachNavigationSurface() { navigationSurfaces++ }
    fun detachNavigationSurface() { navigationSurfaces = (navigationSurfaces - 1).coerceAtLeast(0) }
    fun beginNavigation(device: Int, start: Long) { navigationDevice = device; navigationStart = start }
    fun endNavigation() { navigationDevice = -1; navigationStart = -1L }
    fun isNavigation(device: Int, start: Long) = device == navigationDevice && start == navigationStart

    fun record(now: Long) { lastSeen = now }
    fun contact(now: Long, down: Boolean) { record(now); touching = down; if (down) hovering = false }
    fun hover(now: Long, inRange: Boolean) { record(now); hovering = inRange }
    fun clear() { lastSeen = null; touching = false; hovering = false; endNavigation() }
    fun canceled(window: Boolean = true) { if (window) cancellationSerial++ }
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
    private var pending = 0
    private var navigating = false

    fun reset() { forwarded = 0; forwardedTouches = 0; rejected = 0; pending = 0; navigating = false; dispatchMask = 0; cancel = false }

    fun route(action: Action, ids: Int, pens: Int, touches: Int, actionId: Int,
              time: Long, canceled: Boolean = false, navigation: Boolean = false) {
        dispatchMask = 0
        cancel = false
        if (action == Action.CANCEL) {
            cancel = forwarded != 0
            forwarded = 0; forwardedTouches = 0; rejected = 0; pending = 0; navigating = false
            return
        }
        if (action == Action.DOWN) {
            cancel = forwarded != 0
            forwarded = 0; forwardedTouches = 0; rejected = 0; pending = 0; navigating = navigation
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
                navigating = false
            }
        }
        val pen = pens and rejected.inv()
        if (pen != 0) {
            rejected = rejected or touches
            pending = pending and touches.inv()
            navigating = false
            // A tip always owns its own stream, even when proximity filtering is disabled.
            val owner = Integer.lowestOneBit(pen)
            if (forwarded != 0 && forwarded != owner) {
                cancel = true
                rejected = rejected or (ids and owner.inv())
                forwarded = 0
            }
            if (forwarded == owner || down && bit == owner) dispatchMask = owner
        } else {
            if (stylus.isTouching) rejected = rejected or touches
            else if (stylus.isRecent(time) && !navigating && !navigation) {
                // Fresh proximity contacts wait for pinch intent. They never reach tools as taps.
                pending = pending or (touches and rejected.inv())
            }
            if (forwarded and (rejected or pending) != 0) {
                cancel = true
                if (forwarded and rejected != 0) rejected = rejected or ids
                else pending = pending or touches
                forwarded = 0
                navigating = false
            }
            val admitted = if (down) bit and rejected.inv() else 0
            dispatchMask = (forwarded or admitted) and ids and rejected.inv() and pending.inv()
            if (navigation || Integer.bitCount(dispatchMask and touches) >= 2) navigating = true
        }
        forwarded = dispatchMask
        if (up) {
            forwarded = forwarded and bit.inv()
            rejected = rejected and bit.inv()
            pending = pending and bit.inv()
        }
        if (action == Action.UP) { forwarded = 0; rejected = 0; pending = 0; navigating = false }
        forwardedTouches = forwarded and touches
    }

    /** Hover can reject a touch gesture before the tip lands. Its held contacts stay quarantined. */
    fun rejectTouches(): Boolean {
        if (forwardedTouches == 0 || navigating && !stylus.isTouching) return false
        if (stylus.isTouching) rejected = rejected or forwarded
        else pending = pending or forwardedTouches
        forwarded = 0
        forwardedTouches = 0
        return true
    }

    /** Only undecided fresh contacts can become navigation; rejected palms never return. */
    fun startNavigation(mask: Int): Boolean {
        if (Integer.bitCount(mask) != 2 || mask and rejected != 0 || pending and mask != mask || stylus.isTouching) return false
        pending = pending and mask.inv()
        forwarded = mask; forwardedTouches = mask; navigating = true
        return true
    }
}

/** A fresh pair must change its span beyond touch slop before bypassing proximity protection. */
internal class PinchNavigation(private val slop: Float) {
    private var first = -1
    private var second = -1
    private var startedAt = 0L
    private var x1 = 0f; private var y1 = 0f
    private var x2 = 0f; private var y2 = 0f
    private var span = 0f

    fun reset() { first = -1; second = -1 }
    fun down(id: Int, x: Float, y: Float, time: Long) {
        if (first == -1) {
            first = id; x1 = x; y1 = y; startedAt = time
        } else if (second == -1 && time - startedAt in 0L..280L) {
            second = id; x2 = x; y2 = y
            // Start measuring the pinch when both fingers are on the display.
            span = kotlin.math.hypot(x1 - x2, y1 - y2)
        } else reset()
    }
    fun move(id: Int, x: Float, y: Float) {
        if (id == first) { x1 = x; y1 = y }
        if (id == second) { x2 = x; y2 = y }
    }
    fun intent(): Int {
        if (first == -1 || second == -1) return 0
        if (kotlin.math.abs(kotlin.math.hypot(x1 - x2, y1 - y2) - span) <= slop * 2f) return 0
        return (1 shl first) or (1 shl second)
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
