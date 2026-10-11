package com.folio.notes

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

    fun target(index: Int, count: Int, distance: Float, velocity: Float, width: Float, density: Float): Int {
        if (count <= 1 || width <= 0f) return index
        // A reversing flick retracts the turn even after a long drag. Tiny flicks remain taps.
        val flick = abs(velocity) >= 700f * density && abs(distance) >= 24f * density
        if (flick && sign(velocity) != sign(distance)) return index
        val direction = if (flick || abs(distance) >= width * .24f) sign(distance) else 0f
        return (index - direction.toInt()).coerceIn(0, count - 1)
    }
}
