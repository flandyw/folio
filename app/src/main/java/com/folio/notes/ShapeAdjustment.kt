package com.folio.notes

import kotlin.math.*

/** A recognised shape stays latched until lift. Every update derives from the original fit. */
internal class ShapeAdjustment(private val shapes: List<Stroke>, private val contact: InkPoint) {
    var tip: InkPoint = contact; private set
    private val line = shapes.singleOrNull()?.takeIf { it.tool == Tool.LINE || it.tool == Tool.HIGHLIGHTER }
    private val arrow = shapes.size == 3 && shapes.all { it.tool == Tool.LINE } &&
        shapes.last().points.last() != shapes.first().points.first()
    private val points = shapes.flatMap { it.points }
    private val pivot = when {
        line != null || arrow -> shapes.first().points.first()
        else -> InkPoint((points.minOf { it.x } + points.maxOf { it.x }) / 2f,
            (points.minOf { it.y } + points.maxOf { it.y }) / 2f)
    }
    private val handle = if (line != null || arrow) shapes.first().points.last() else contact

    /** A continuous dead zone absorbs tremor without a jump when intentional adjustment begins. */
    fun update(at: InkPoint, slop: Float): List<Stroke> {
        if (!at.x.isFinite() || !at.y.isFinite()) return shapes
        tip = at
        var dx = at.x - contact.x; var dy = at.y - contact.y
        val moved = hypot(dx, dy)
        if (moved <= slop) return shapes
        val share = (moved - slop) / moved
        dx *= share; dy *= share
        if (line != null) return listOf(line.copy(points = listOf(pivot, InkPoint(handle.x + dx, handle.y + dy))))
        val hx = handle.x - pivot.x; val hy = handle.y - pivot.y
        val radius = hypot(hx, hy)
        if (radius < 1f) return shapes
        val tx = hx + dx; val ty = hy + dy
        val scale = (hypot(tx, ty) / radius).coerceIn(.1f, 10f)
        val angle = atan2(ty, tx) - atan2(hy, hx)
        val c = cos(angle); val s = sin(angle)
        fun transform(p: InkPoint): InkPoint {
            val x = p.x - pivot.x; val y = p.y - pivot.y
            return p.copy(x = pivot.x + (x * c - y * s) * scale,
                y = pivot.y + (x * s + y * c) * scale)
        }
        return shapes.flatMap { stroke ->
            when (stroke.tool) {
                Tool.RECTANGLE -> {
                    val a = stroke.points.first(); val b = stroke.points.last()
                    val corners = listOf(a, InkPoint(b.x, a.y), b, InkPoint(a.x, b.y)).map(::transform)
                    if (abs(s) < .001f) listOf(stroke.copy(points = listOf(
                        InkPoint(corners.minOf { it.x }, corners.minOf { it.y }), InkPoint(corners.maxOf { it.x }, corners.maxOf { it.y }))))
                    else corners.indices.map { i -> stroke.copy(tool = Tool.LINE, points = listOf(corners[i], corners[(i + 1) % 4])) }
                }
                Tool.ELLIPSE -> {
                    val a = stroke.points.first(); val b = stroke.points.last()
                    val cx = (a.x + b.x) / 2f; val cy = (a.y + b.y) / 2f
                    val rx = abs(b.x - a.x) / 2f; val ry = abs(b.y - a.y) / 2f
                    if (abs(s) < .001f || abs(rx - ry) < .01f) {
                        val centre = transform(InkPoint(cx, cy))
                        listOf(stroke.copy(points = listOf(InkPoint(centre.x - rx * scale, centre.y - ry * scale),
                            InkPoint(centre.x + rx * scale, centre.y + ry * scale))))
                    } else listOf(stroke.copy(tool = Tool.PEN, points = (0..128).map { i ->
                        val theta = i * 2 * PI / 128
                        transform(InkPoint(cx + rx * cos(theta).toFloat(), cy + ry * sin(theta).toFloat()))
                    }))
                }
                else -> listOf(stroke.copy(points = stroke.points.map(::transform)))
            }
        }
    }
}
