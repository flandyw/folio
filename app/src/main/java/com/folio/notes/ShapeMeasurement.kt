package com.folio.notes

import kotlin.math.*

/** Label content stays in page units; only its anchor follows the canvas camera. */
internal data class ShapeMeasurement(val label: String, val x: Float, val y: Float) {
    companion object {
        fun from(draft: Stroke, graphStyle: GraphStyle, originX: Float, originY: Float, scale: Float): ShapeMeasurement? {
            val a = draft.points.firstOrNull() ?: return null
            val b = draft.points.lastOrNull() ?: return null
            val label = when (draft.tool) {
                Tool.LINE -> {
                    val len = hypot(b.x - a.x, b.y - a.y)
                    val deg = (Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())) + 360) % 360
                    String.format(java.util.Locale.ROOT, "%.0f pt  %.0f°", len, deg)
                }
                Tool.RECTANGLE, Tool.TRIANGLE, Tool.DIAMOND, Tool.PENTAGON, Tool.HEXAGON, Tool.STAR -> {
                    val w = kotlin.math.abs(b.x - a.x); val h = kotlin.math.abs(b.y - a.y)
                    String.format(java.util.Locale.ROOT, "%.0f × %.0f", w, h)
                }
                Tool.GRAPH -> {
                    val w = kotlin.math.abs(b.x - a.x); val h = kotlin.math.abs(b.y - a.y)
                    val step = GraphAxes.stepLabel(graphStyle)?.let { "  $it" }.orEmpty()
                    String.format(java.util.Locale.ROOT, "%.0f × %.0f%s", w, h, step)
                }
                Tool.ELLIPSE -> {
                    val w = kotlin.math.abs(b.x - a.x); val h = kotlin.math.abs(b.y - a.y)
                    val r = (w + h) / 4f
                    String.format(java.util.Locale.ROOT, "⌀ %.0f  r %.0f", kotlin.math.max(w, h), r)
                }
                else -> return null
            }
            return ShapeMeasurement(label, originX + (a.x + b.x) / 2f * scale,
                originY + (a.y + b.y) / 2f * scale)
        }
    }
}
