package com.folio.notes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reusable diagram elements, like GoodNotes' Elements tool: one tap inserts a clean, editable
 * shape as ordinary ink strokes centred on the page, so it moves, restyles, erases and exports
 * like anything drawn by hand. Pure geometry so it stays unit-testable.
 */
object InkStamps {
    enum class Kind { ARROW, DOUBLE_ARROW, STAR, CHECKBOX, CALLOUT, UNDERLINE }

    val kinds: List<Kind> = Kind.entries.toList()

    fun label(kind: Kind): String = when (kind) {
        Kind.ARROW -> "Arrow"
        Kind.DOUBLE_ARROW -> "Double arrow"
        Kind.STAR -> "Star"
        Kind.CHECKBOX -> "Checkbox"
        Kind.CALLOUT -> "Callout box"
        Kind.UNDERLINE -> "Emphasis underline"
    }

    /**
     * Strokes for [kind] centred on ([centerX], [centerY]), drawn with [color]/[width]/[opacity].
     * Sizes are in page units; [size] is roughly the element's longest side.
     */
    fun make(
        kind: Kind,
        centerX: Float,
        centerY: Float,
        size: Float = 220f,
        color: Int = 0xFF303431.toInt(),
        width: Float = 2.2f,
        opacity: Float = 1f
    ): List<Stroke> {
        val half = (size / 2f).coerceIn(8f, 600f)
        fun point(x: Float, y: Float) = InkPoint(centerX + x * half, centerY + y * half)
        // LINE supports explicit polylines: uniform width, exact corners and no pen taper.
        fun outline(points: List<InkPoint>) = Stroke(Tool.LINE, color, width, points, opacity)
        fun path(vararg xy: Pair<Float, Float>) = outline(xy.map { point(it.first, it.second) })
        fun arc(cx: Float, cy: Float, radius: Float, start: Double): List<InkPoint> =
            (0..8).map { i ->
                val angle = start + i * PI / 16
                point(cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat())
            }
        fun roundedBox(left: Float, top: Float, right: Float, bottom: Float, radius: Float,
            tail: Boolean = false): Stroke {
            val points = buildList {
                addAll(arc(right - radius, top + radius, radius, -PI / 2))
                addAll(arc(right - radius, bottom - radius, radius, 0.0))
                if (tail) {
                    // A real speech-bubble tail replaces part of the bottom edge.
                    add(point(.42f, bottom)); add(point(.16f, .78f)); add(point(.08f, bottom))
                }
                addAll(arc(left + radius, bottom - radius, radius, PI / 2))
                addAll(arc(left + radius, top + radius, radius, PI))
                add(first())
            }
            return outline(points)
        }
        return when (kind) {
            Kind.ARROW -> listOf(
                path(-1f to 0f, 1f to 0f),
                path(.68f to -.25f, 1f to 0f, .68f to .25f)
            )
            Kind.DOUBLE_ARROW -> listOf(
                path(-1f to 0f, 1f to 0f),
                path(-.68f to -.25f, -1f to 0f, -.68f to .25f),
                path(.68f to -.25f, 1f to 0f, .68f to .25f)
            )
            Kind.STAR -> {
                // Regular five-point outline, with a golden-ratio inner radius.
                val vertices = (0 until 10).map { i ->
                    val radius = if (i % 2 == 0) 1f else .381966f
                    val angle = -PI / 2 + i * PI / 5
                    point(radius * cos(angle).toFloat(), radius * sin(angle).toFloat() + .095492f)
                }
                listOf(outline(vertices + vertices.first()))
            }
            Kind.CHECKBOX -> listOf(
                roundedBox(-.7f, -.7f, .7f, .7f, .12f),
                path(-.39f to .01f, -.1f to .30f, .40f to -.32f)
            )
            Kind.CALLOUT -> listOf(roundedBox(-1f, -.78f, 1f, .42f, .18f, tail = true))
            Kind.UNDERLINE -> listOf(outline((0..48).map { i ->
                val t = i / 48f
                // Subtle, symmetric bow with level ends; clean at any scale.
                point(-1f + 2f * t, .10f * sin(t * PI).toFloat())
            }))
        }
    }
}
