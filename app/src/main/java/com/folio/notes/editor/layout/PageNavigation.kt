package com.folio.notes.editor.layout

import kotlin.math.abs
import kotlin.math.sign

/** An explicit destination owns selection until it lands and the viewport subsequently moves. */
internal class PageNavigation {
    data class Position(val first: Int, val offset: Int)
    data class Viewport(val position: Position, val visible: Set<Int>, val canScrollForward: Boolean,
                        val current: Int?, val layout: Any)
    private var destination: String? = null
    private var landed: Position? = null
    private var previousLayout: Any? = null

    val awaitingLanding: Boolean get() = destination != null && landed == null

    fun request(pageId: String, layout: Any) { destination = pageId; landed = null; previousLayout = layout }

    fun observe(ids: List<String>, viewport: Viewport): Int? {
        val (position, visible, canScrollForward, current, layout) = viewport
        val target = destination?.let(ids::indexOf)?.takeIf { it >= 0 }
        if (target == null) { destination = null; landed = null; previousLayout = null; return current }
        if (layout === previousLayout) return null
        previousLayout = null
        val anchor = landed
        if (anchor != null) {
            if (anchor == position) return target
            destination = null; landed = null
            return current
        }
        if ((position.first == target && position.offset == 0) || (!canScrollForward && target in visible)) {
            landed = position
            return target
        }
        // A layout from before requestScrollToItem must never undo the user's destination.
        return null
    }
}

/** Screen-pixel gesture rules. A drag can only ever turn one page. */
internal object PageSwipe {
    fun offset(distance: Float, width: Float, index: Int, count: Int): Float {
        if (width <= 0f) return 0f
        val edge = (distance > 0f && index == 0) || (distance < 0f && index == count - 1)
        return if (edge) sign(distance) * width * .12f * (1f - 1f / (1f + abs(distance) / (width * .4f)))
        else distance.coerceIn(-width, width)
    }

    /** Resume an edge's resisted motion at exactly its current visual position. */
    fun edgeDistance(offset: Float, width: Float): Float {
        if (width <= 0f) return 0f
        val fraction = (abs(offset) / (width * .12f)).coerceIn(0f, .999f)
        return sign(offset) * width * .4f * fraction / (1f - fraction)
    }

    /**
     * A deliberate swipe: the finger must travel further than a tap's jitter and be clearly more
     * horizontal than vertical, and more so on a zoomed page, where drags are mostly panning.
     */
    fun claims(dx: Float, dy: Float, slop: Float, zoomed: Boolean): Boolean =
        abs(dx) > slop * (if (zoomed) 3f else 2f) && abs(dx) >= abs(dy) * (if (zoomed) 2.5f else 2f)

    /**
     * A zoomed page only turns from a fresh drag that began with the page already flush against the
     * side being dragged past, so panning to an edge and carrying on never flips it by accident.
     */
    fun mayTurn(dx: Float, zoomed: Boolean, atLeft: Boolean, atRight: Boolean): Boolean =
        !zoomed || (dx > 0f && atLeft) || (dx < 0f && atRight)

    fun target(index: Int, count: Int, distance: Float, velocity: Float, width: Float, density: Float,
               zoomed: Boolean = false): Int {
        if (count <= 1 || width <= 0f) return index
        // A reversing flick retracts the turn even after a long drag. Tiny flicks remain taps.
        val flick = abs(velocity) >= (if (zoomed) 1000f else 800f) * density &&
            abs(distance) >= (if (zoomed) 72f else 40f) * density
        if (flick && sign(velocity) != sign(distance)) return index
        val direction = if (flick || abs(distance) >= width * (if (zoomed) .4f else .3f)) sign(distance) else 0f
        return (index - direction.toInt()).coerceIn(0, count - 1)
    }
}
