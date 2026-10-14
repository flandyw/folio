package android.view

import android.content.Context
import android.graphics.Canvas

class Metrics { var density = 1f }
class Resources { val displayMetrics = Metrics() }
class Parent {
    var disallowed = false
    fun requestDisallowInterceptTouchEvent(value: Boolean) { disallowed = value }
}

open class View(val context: Context) {
    val resources = Resources()
    var parent: Parent? = Parent()
    var width = 600
    var height = 800
    var isAttachedToWindow = false
    var isLaidOut = true
    var isShown = true
    val location = intArrayOf(0, 0)
    var invalidations = 0
    val viewTreeObserver = ViewTreeObserver()
    open fun invalidate() { invalidations++ }
    fun getLocationInWindow(at: IntArray) { at[0] = location[0]; at[1] = location[1] }
    open fun onDraw(canvas: Canvas) {}
    open fun onTouchEvent(event: MotionEvent) = false
    open fun onAttachedToWindow() {}
    open fun onDetachedFromWindow() {}
    fun attach() { isAttachedToWindow = true; onAttachedToWindow() }
    fun detach() { onDetachedFromWindow(); isAttachedToWindow = false }
}

class ViewTreeObserver {
    fun interface OnPreDrawListener { fun onPreDraw(): Boolean }
    val listeners = mutableListOf<OnPreDrawListener>()
    fun addOnPreDrawListener(listener: OnPreDrawListener) { listeners += listener }
    fun removeOnPreDrawListener(listener: OnPreDrawListener) { listeners -= listener }
    fun draw() { listeners.toList().forEach { check(it.onPreDraw()) } }
}

class ViewConfiguration {
    val scaledTouchSlop = 8
    companion object { fun get(context: Context) = ViewConfiguration() }
}

class MotionEvent(val actionMasked: Int, var x: Float, var y: Float,
                  val toolType: Int = TOOL_TYPE_FINGER, var history: List<Pair<Float, Float>> = emptyList()) {
    val historySize get() = history.size
    fun getHistoricalX(pointer: Int, index: Int) = history[index].first
    fun getHistoricalY(pointer: Int, index: Int) = history[index].second
    fun getToolType(pointer: Int) = toolType
    fun offsetLocation(dx: Float, dy: Float) {
        x += dx; y += dy; history = history.map { it.first + dx to it.second + dy }
    }
    fun recycle() {}
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val TOOL_TYPE_FINGER = 1
        const val TOOL_TYPE_STYLUS = 2
        fun obtain(event: MotionEvent) = MotionEvent(event.actionMasked, event.x, event.y, event.toolType, event.history)
    }
}
