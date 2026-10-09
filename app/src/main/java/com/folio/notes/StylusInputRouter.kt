package com.folio.notes

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** Routes before gesture recognition, so every tool and Compose navigation sees the same policy. */
internal class StylusInputRouter(val stylus: StylusActivity = StylusActivity(), pinchSlop: Float = 0f,
                                private val notifyWindowCancellation: Boolean = false) {
    private val rejection = PalmRejection(stylus)
    private val pinch = PinchNavigation(pinchSlop)
    private val pinchEnabled = pinchSlop > 0f
    private var navigationOwner = false
    private var lastDispatch: MotionEvent? = null
    private var penStream = false
    private var downTime = 0L
    // Reused scratch arrays; obtain/addBatch copy them into the platform event.
    private val properties = Array(32) { MotionEvent.PointerProperties() }
    private val coords = Array(32) { MotionEvent.PointerCoords() }
    private val indices = IntArray(32)

    fun touch(event: MotionEvent, dispatch: (MotionEvent) -> Boolean): Boolean {
        var ids = 0; var pens = 0; var touches = 0
        for (i in 0 until event.pointerCount) {
            val bit = 1 shl event.getPointerId(i)
            ids = ids or bit
            when (event.getToolType(i)) {
                MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> pens = pens or bit
                MotionEvent.TOOL_TYPE_FINGER -> if (!event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                    !event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) touches = touches or bit
                MotionEvent.TOOL_TYPE_UNKNOWN -> if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) touches = touches or bit
            }
        }
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PalmRejection.Action.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> PalmRejection.Action.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> PalmRejection.Action.MOVE
            MotionEvent.ACTION_POINTER_UP -> PalmRejection.Action.POINTER_UP
            MotionEvent.ACTION_UP -> PalmRejection.Action.UP
            MotionEvent.ACTION_CANCEL -> PalmRejection.Action.CANCEL
            else -> return dispatch(event)
        }
        val id = event.getPointerId(event.actionIndex)
        val up = action == PalmRejection.Action.UP || action == PalmRejection.Action.POINTER_UP
        if (navigationOwner && (action == PalmRejection.Action.DOWN || action == PalmRejection.Action.CANCEL || pens != 0)) endNavigation()
        if (pens != 0) stylus.contact(event.eventTime,
            action != PalmRejection.Action.CANCEL && (pens and (if (up) (1 shl id).inv() else -1)) != 0)
        else if (action == PalmRejection.Action.CANCEL && penStream) stylus.contact(event.eventTime, false)
        val canceled = Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0
        if (action == PalmRejection.Action.DOWN) pinch.reset()
        if (pens != 0 || stylus.isTouching || up || action == PalmRejection.Action.CANCEL || !stylus.canPinchNavigate) pinch.reset()
        else if (pinchEnabled && !navigationOwner) {
            for (i in 0 until event.pointerCount) pinch.move(event.getPointerId(i), event.getX(i), event.getY(i))
            if ((action == PalmRejection.Action.DOWN || action == PalmRejection.Action.POINTER_DOWN) && touches and (1 shl id) != 0) {
                pinch.down(id, event.getX(event.actionIndex), event.getY(event.actionIndex), event.eventTime)
            }
            val intent = if (action == PalmRejection.Action.MOVE) pinch.intent() else 0
            if (intent != 0 && rejection.startNavigation(intent)) {
                navigationOwner = true
                downTime = event.eventTime
                stylus.beginNavigation(event.deviceId, downTime)
                dispatchNavigationStart(event, intent, dispatch)
                pinch.reset()
            }
        }
        val navigation = navigationOwner || stylus.isNavigation(event.deviceId, event.downTime)
        rejection.route(action, ids, pens, touches, id, event.eventTime, canceled, navigation)
        if (rejection.cancel) cancelDispatch(dispatch)
        val mask = rejection.dispatchMask
        if (mask == 0) { if (navigationOwner) endNavigation(); return true }
        // Filtering also rewrites POINTER_DOWN/UP to DOWN/UP when the other pointers were rejected.
        // Use public obtain/addBatch APIs, retaining each pointer's full coordinates and history.
        if (action == PalmRejection.Action.DOWN || action == PalmRejection.Action.POINTER_DOWN && mask == 1 shl id) {
            downTime = if (action == PalmRejection.Action.DOWN) event.downTime else event.eventTime
        }
        val copied = mask != ids || event.downTime != downTime
        val routed = if (copied) filteredEvent(event, mask, downTime) else event
        try {
            penStream = mask and pens != 0
            if (routed.actionMasked != MotionEvent.ACTION_MOVE) {
                lastDispatch?.recycle()
                lastDispatch = if (up && mask and (1 shl id).inv() != 0) {
                    filteredEvent(routed, mask and (1 shl id).inv(), downTime, history = false).apply {
                        this.action = MotionEvent.ACTION_MOVE
                    }
                } else MotionEvent.obtainNoHistory(routed)
            }
            dispatch(routed)
            if (routed.actionMasked == MotionEvent.ACTION_UP) { clearDispatch(); endNavigation() }
        } finally { if (copied) routed.recycle() }
        // Rejected DOWNs must still claim the physical stream so a later pen can take it over.
        return true
    }

    fun hover(event: MotionEvent, dispatch: (MotionEvent) -> Boolean) {
        if ((0 until event.pointerCount).none { isStylusPointer(event, it) }) return
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                stylus.hover(event.eventTime, true)
                if (!penStream && stylus.isRecent(event.eventTime) && rejection.rejectTouches()) cancelDispatch(dispatch)
            }
            // EXIT is also sent immediately before DOWN; preserve the grace period across it.
            MotionEvent.ACTION_HOVER_EXIT -> stylus.hover(event.eventTime, false)
        }
    }

    fun reset(dispatch: (MotionEvent) -> Boolean) {
        cancelDispatch(dispatch)
        rejection.reset()
        pinch.reset()
        endNavigation()
    }

    private fun cancelDispatch(dispatch: (MotionEvent) -> Boolean) {
        lastDispatch?.let { last ->
            // Compose cancels the child AndroidView when its parent takes a pinch. Only the
            // window's physical-stream cancellations should abort that parent recognizer.
            stylus.canceled(notifyWindowCancellation)
            var mask = 0
            for (i in 0 until last.pointerCount) mask = mask or (1 shl last.getPointerId(i))
            // The topology snapshot can be older than the last MOVE. A cancellation is sent now,
            // so its time must not jump backwards in Compose's physical input stream.
            val cancel = filteredEvent(last, mask, last.downTime, history = false, time = SystemClock.uptimeMillis())
            try { cancel.action = MotionEvent.ACTION_CANCEL; dispatch(cancel) }
            finally { cancel.recycle() }
        }
        clearDispatch()
    }

    private fun clearDispatch() { lastDispatch?.recycle(); lastDispatch = null; penStream = false }

    private fun endNavigation() {
        if (navigationOwner) stylus.endNavigation()
        navigationOwner = false
    }

    private fun dispatchNavigationStart(event: MotionEvent, mask: Int, dispatch: (MotionEvent) -> Boolean) {
        var first = 0
        for (i in 0 until event.pointerCount) {
            val bit = 1 shl event.getPointerId(i)
            if (mask and bit != 0) { first = bit; break }
        }
        val down = filteredEvent(event, first, downTime, history = false)
        try {
            down.action = MotionEvent.ACTION_DOWN
            lastDispatch?.recycle(); lastDispatch = MotionEvent.obtainNoHistory(down)
            dispatch(down)
        } finally { down.recycle() }
        val joined = filteredEvent(event, mask, downTime, history = false)
        try {
            joined.action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            lastDispatch?.recycle(); lastDispatch = MotionEvent.obtainNoHistory(joined)
            dispatch(joined)
        }
        finally { joined.recycle() }
    }

    private fun filteredEvent(event: MotionEvent, mask: Int, start: Long, history: Boolean = true,
                              time: Long = event.eventTime): MotionEvent {
        var count = 0
        var changedIndex = -1
        for (i in 0 until event.pointerCount) {
            if (mask and (1 shl event.getPointerId(i)) == 0) continue
            indices[count] = i
            event.getPointerProperties(i, properties[count])
            if (i == event.actionIndex) changedIndex = count
            count++
        }
        val action = filteredPointerAction(event.actionMasked, count, changedIndex)
        val histories = if (history) event.historySize else 0
        var firstHistory = 0
        // A delayed pinch DOWN starts now. Do not feed its recognizers older withheld samples.
        while (firstHistory < histories && event.getHistoricalEventTime(firstHistory) < start) firstHistory++
        // Re-create the original raw/local translation. This also keeps hand-tool panning stable
        // when a pen at index > 0 takes over from a palm, including on API 26..28.
        val first = indices[0]
        val offsetX = if (Build.VERSION.SDK_INT >= 29) event.getRawX(first) - event.getX(first) else event.rawX - event.x
        val offsetY = if (Build.VERSION.SDK_INT >= 29) event.getRawY(first) - event.getY(first) else event.rawY - event.y
        fun coordinates(h: Int) {
            for (i in 0 until count) {
                if (h < histories) event.getHistoricalPointerCoords(indices[i], h, coords[i])
                else event.getPointerCoords(indices[i], coords[i])
                coords[i].x += offsetX; coords[i].y += offsetY
            }
        }
        coordinates(firstHistory)
        // addBatch accepts MOVE events; set the final action only after copying the history.
        val result = MotionEvent.obtain(start,
            if (firstHistory < histories) event.getHistoricalEventTime(firstHistory) else time,
            MotionEvent.ACTION_MOVE, count, properties, coords, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source,
            event.flags and MotionEvent.FLAG_CANCELED.inv())
        for (h in (firstHistory + 1)..histories) {
            coordinates(h)
            result.addBatch(if (h < histories) event.getHistoricalEventTime(h) else time, coords, event.metaState)
        }
        result.offsetLocation(-offsetX, -offsetY)
        result.action = action
        return result
    }
}

internal fun isStylusPointer(event: MotionEvent, index: Int): Boolean =
    event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
