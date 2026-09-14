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
        opacity: Float = 1f,
        createdAt: Long = 0L
    ): List<Stroke> {
        val half = (size / 2f).coerceIn(40f, 600f)
        fun line(ax: Float, ay: Float, bx: Float, by: Float, style: StrokeStyle = StrokeStyle.SOLID) =
            Stroke(Tool.LINE, color, width, listOf(InkPoint(ax, ay), InkPoint(bx, by)), opacity, createdAt, style)
        return when (kind) {
            Kind.ARROW -> {
                val tail = line(centerX - half, centerY, centerX + half * 0.72f, centerY)
                val tipX = centerX + half
                val tipY = centerY
                val head = half * 0.22f
                listOf(tail,
                    line(tipX, tipY, tipX - head, tipY - head * 0.7f),
                    line(tipX, tipY, tipX - head, tipY + head * 0.7f))
            }
            Kind.DOUBLE_ARROW -> {
                val leftX = centerX - half
                val rightX = centerX + half
                val head = half * 0.22f
                listOf(
                    line(leftX + head, centerY, rightX - head, centerY),
                    line(leftX + head, centerY, leftX + head + head, centerY - head * 0.7f),
                    line(leftX + head, centerY, leftX + head + head, centerY + head * 0.7f),
                    line(rightX - head, centerY, rightX - head - head, centerY - head * 0.7f),
                    line(rightX - head, centerY, rightX - head - head, centerY + head * 0.7f)
                )
            }
            Kind.STAR -> {
                // A five-pointed star as five pen strokes through its outer/inner vertices, so
                // each edge erases and exports like handwriting.
                val outer = half
                val inner = half * 0.45f
                val vertices = (0 until 10).map { i ->
                    val radius = if (i % 2 == 0) outer else inner
                    val angle = -PI / 2 + i * PI / 5
                    InkPoint(centerX + radius * cos(angle).toFloat(), centerY + radius * sin(angle).toFloat())
                }
                vertices.indices.map { i ->
                    Stroke(Tool.PEN, color, width,
                        listOf(vertices[i], vertices[(i + 1) % vertices.size]), opacity, createdAt)
                }
            }
            Kind.CHECKBOX -> {
                val boxHalf = half * 0.45f
                val box = Stroke(Tool.RECTANGLE, color, width,
                    listOf(InkPoint(centerX - boxHalf, centerY - boxHalf), InkPoint(centerX + boxHalf, centerY + boxHalf)),
                    opacity, createdAt)
                // A tick inside, slightly oversized so it reads at a glance.
                val check = listOf(
                    InkPoint(centerX - boxHalf * 0.45f, centerY + boxHalf * 0.05f),
                    InkPoint(centerX - boxHalf * 0.05f, centerY + boxHalf * 0.45f),
                    InkPoint(centerX + boxHalf * 0.55f, centerY - boxHalf * 0.45f)
                )
                listOf(box, Stroke(Tool.PEN, color, width, check, opacity, createdAt))
            }
            Kind.CALLOUT -> {
                val w = half
                val h = half * 0.6f
                val rect = Stroke(Tool.RECTANGLE, color, width,
                    listOf(InkPoint(centerX - w, centerY - h), InkPoint(centerX + w, centerY + h)),
                    opacity, createdAt)
                val tail = line(centerX + w * 0.3f, centerY + h, centerX + w * 0.55f, centerY + h + half * 0.5f)
                listOf(rect, tail)
            }
            Kind.UNDERLINE -> {
                // A slightly wavy emphasis line with tapered-feel short ticks, like a hand-drawn rule.
                val y = centerY
                val main = Stroke(Tool.PEN, color, width,
                    (0..16).map { i ->
                        val t = i / 16f
                        InkPoint(centerX - half + t * half * 2f, y + sin(t * PI).toFloat() * half * 0.06f)
                    }, opacity, createdAt)
                listOf(main)
            }
        }
    }
}
