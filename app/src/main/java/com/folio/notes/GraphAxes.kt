package com.folio.notes

import kotlin.math.abs
import kotlin.math.min

/** Unlabelled Cartesian axes for Methods sketches: no grid, ticks, numbers or axis labels. */
object GraphAxes {
    /**
     * Drag corners bound the complete diagram. The origin is centred, with open arrowheads
     * on both ends of both axes. Ordinary line paths keep every part editable and erasable.
     */
    fun strokes(draft: Stroke): List<Stroke> {
        if (draft.points.size < 2) return emptyList()
        val a = draft.points.first(); val b = draft.points.last()
        val width = abs(b.x - a.x); val height = abs(b.y - a.y)
        if (width == 0f || height == 0f) return emptyList()
        val left = min(a.x, b.x); val top = min(a.y, b.y)
        val right = left + width; val bottom = top + height
        val cx = left + width / 2f; val cy = top + height / 2f
        val head = (min(width, height) * .06f).coerceAtMost(14f)
        val wing = head * .5f
        fun line(vararg points: InkPoint) = draft.copy(tool = Tool.LINE, points = points.toList())
        return listOf(
            line(InkPoint(left, cy), InkPoint(right, cy)),
            line(InkPoint(cx, bottom), InkPoint(cx, top)),
            line(InkPoint(left + head, cy - wing), InkPoint(left, cy), InkPoint(left + head, cy + wing)),
            line(InkPoint(right - head, cy - wing), InkPoint(right, cy), InkPoint(right - head, cy + wing)),
            line(InkPoint(cx - wing, top + head), InkPoint(cx, top), InkPoint(cx + wing, top + head)),
            line(InkPoint(cx - wing, bottom - head), InkPoint(cx, bottom), InkPoint(cx + wing, bottom - head))
        )
    }
}
