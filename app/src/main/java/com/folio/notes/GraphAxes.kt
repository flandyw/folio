package com.folio.notes

import kotlin.math.min

/**
 * The axes, grid, ticks and labels the graph tool draws. Everything lands as ordinary line paths,
 * so a graph is editable, erasable and exportable exactly like hand-drawn ink.
 *
 * A drag from one corner to the other bounds the diagram; the style decides where the axes cross
 * and how the frame is dressed. The default style is the plain centred axes with open arrowheads
 * the tool has always drawn — no grid, ticks, numbers or letters.
 */
object GraphAxes {
    /** Which side of its anchor a label sits on: over a tick, or off to one side of an axis. */
    private enum class LabelAlign { LEFT, CENTRE, RIGHT }

    /** Digits smaller than this are unreadable, so labels are dropped rather than smudged. */
    const val MIN_GLYPH = 7f
    /** Fractions of the frame's shorter side used for arrowheads and ticks. */
    const val ARROW = .06f
    const val TICK = .022f
    /** Labels sit this far off the axis, as a multiple of their own height. */
    const val LABEL_GAP = .28f

    /**
     * The ink for [draft] laid over the box its two corners bound, in the plain centred style.
     * Kept as its own overload so callers that never dress a graph need no style at all.
     */
    fun strokes(draft: Stroke): List<Stroke> = strokes(draft, GraphStyle.DEFAULT)

    /**
     * The ink for [draft] laid over the box its two corners bound. A drag too small to hold axes
     * draws nothing, and drag direction never changes the result.
     */
    fun strokes(draft: Stroke, style: GraphStyle): List<Stroke> {
        if (draft.points.size < 2) return emptyList()
        val frame = GraphFrame.of(draft.points.first(), draft.points.last(), style) ?: return emptyList()
        val (originX, originY) = frame.origin(style)
        val halfX = frame.halfX(style); val halfY = frame.halfY(style)
        val short = min(frame.width, frame.height)
        val head = (short * ARROW).coerceAtMost(14f)
        val wing = head * .5f
        fun line(vararg points: InkPoint) = draft.copy(tool = Tool.LINE, points = points.toList())
        val out = mutableListOf<Stroke>()

        // Faint grid first, so the axes and their labels sit on top of it.
        if (style.grid && style.divisions > 0) {
            val stepX = halfX / style.divisions; val stepY = halfY / style.divisions
            for (i in 1 until style.divisions) {
                if (style.origin == GraphOrigin.CENTRE) {
                    out += line(InkPoint(originX - i * stepX, frame.top), InkPoint(originX - i * stepX, frame.bottom))
                    out += line(InkPoint(originX + i * stepX, frame.top), InkPoint(originX + i * stepX, frame.bottom))
                    out += line(InkPoint(frame.left, originY - i * stepY), InkPoint(frame.right, originY - i * stepY))
                    out += line(InkPoint(frame.left, originY + i * stepY), InkPoint(frame.right, originY + i * stepY))
                } else {
                    out += line(InkPoint(originX + i * stepX, frame.top), InkPoint(originX + i * stepX, frame.bottom))
                    out += line(InkPoint(frame.left, originY - i * stepY), InkPoint(frame.right, originY - i * stepY))
                }
            }
        }

        // The axes themselves span the frame, whether they cross in the middle or at a corner.
        out += line(InkPoint(frame.left, originY), InkPoint(frame.right, originY))
        out += line(InkPoint(originX, frame.bottom), InkPoint(originX, frame.top))
        if (style.arrows) {
            // A centred origin points all four ways; a corner origin has only two free ends.
            if (style.origin == GraphOrigin.CENTRE) {
                out += line(InkPoint(frame.left + head, originY - wing), InkPoint(frame.left, originY), InkPoint(frame.left + head, originY + wing))
            }
            out += line(InkPoint(frame.right - head, originY - wing), InkPoint(frame.right, originY), InkPoint(frame.right - head, originY + wing))
            out += line(InkPoint(originX - wing, frame.top + head), InkPoint(originX, frame.top), InkPoint(originX + wing, frame.top + head))
            if (style.origin == GraphOrigin.CENTRE) {
                out += line(InkPoint(originX - wing, frame.bottom - head), InkPoint(originX, frame.bottom), InkPoint(originX + wing, frame.bottom - head))
            }
        }

        if (style.divisions <= 0) return out
        val tick = (short * TICK).coerceAtMost(7f)
        val stepX = halfX / style.divisions; val stepY = halfY / style.divisions
        val glyph = (min(stepX, stepY) * .34f).coerceAtMost(short * .08f)
        // Direction pairs for the ticks: a centred origin labels both ways, a corner origin only
        // has room (and only has need) for the positive way.
        val ways = if (style.origin == GraphOrigin.CENTRE) listOf(-1, 1) else listOf(1)
        for (i in 1..style.divisions) {
            for (side in ways) {
                val x = originX + side * i * stepX
                if (style.ticks) out += line(InkPoint(x, originY - tick / 2f), InkPoint(x, originY + tick / 2f))
                if (style.numbers) {
                    // Centred axes label downwards into the free space; a corner origin sits on the
                    // frame's own bottom edge, so its numbers go inside the graph instead.
                    val corner = style.origin == GraphOrigin.CORNER
                    out += graphLabelStrokes(draft, graphLabel(i * style.step * side), x, originY + (if (corner) -tick / 2f else tick / 2f), glyph,
                        below = corner, align = LabelAlign.CENTRE, limit = frame)
                }
                val y = originY - side * i * stepY
                if (style.ticks) out += line(InkPoint(originX - tick / 2f, y), InkPoint(originX + tick / 2f, y))
                if (style.numbers) {
                    // Above the tick, and to the left of the axis unless that would fall outside a
                    // corner-origin frame — there the label hangs to the right, inside the graph.
                    val corner = style.origin == GraphOrigin.CORNER
                    out += graphLabelStrokes(draft, graphLabel(i * style.step * side), originX + (if (corner) tick / 2f else -tick / 2f), y, glyph,
                        below = true, align = if (corner) LabelAlign.RIGHT else LabelAlign.LEFT, limit = frame)
                }
            }
        }
        if (style.letters) {
            // The x sits just past its arrowhead, below the axis; the y to the right of its own,
            // where the numbers hanging left of it never reach.
            out += graphLabelStrokes(draft, "x", frame.right - glyph * .2f, originY + tick, glyph,
                below = false, align = LabelAlign.CENTRE, limit = frame)
            out += graphLabelStrokes(draft, "y", originX + tick / 2f, frame.top + glyph, glyph,
                below = false, align = LabelAlign.RIGHT, limit = frame)
        }
        return out
    }

    /**
     * [text] as ink with its cap height [size] in page units, set beside its anchor by [align].
     * [below] puts the cap under the anchor (an axis) rather than above it (a tick). Labels that
     * would fall outside [limit] or would be too small to read are simply not drawn — a cramped
     * graph keeps its axes rather than smudging unreadable numbers over them.
     */
    private fun graphLabelStrokes(
        draft: Stroke, text: String, atX: Float, atY: Float, size: Float,
        below: Boolean, align: LabelAlign, limit: GraphFrame
    ): List<Stroke> {
        if (!GraphGlyphs.supports(text) || size < MIN_GLYPH) return emptyList()
        val textWidth = GraphGlyphs.width(text) * size
        val left = when (align) {
            LabelAlign.CENTRE -> atX - textWidth / 2f
            LabelAlign.LEFT -> atX - textWidth - size * LABEL_GAP
            LabelAlign.RIGHT -> atX + size * LABEL_GAP
        }
        // Cap height runs up from the baseline; a tick's label sits over it, an axis's under it.
        val top = if (below) atY - size else atY + size * LABEL_GAP
        if (left < limit.left || left + textWidth > limit.right || top < limit.top || top + size > limit.bottom) return emptyList()
        return GraphGlyphs.polylines(text).map { points ->
            draft.copy(tool = Tool.LINE, points = points.map { p -> InkPoint(left + p.x * size, top + p.y * size) })
        }
    }

    /**
     * The unit a graph's numbers count in, for the live measurement readout: "1 div = 5". Null
     * while the graph is bare, so the readout stays as it was.
     */
    fun stepLabel(style: GraphStyle): String? =
        if (style.divisions > 0 && style.numbers) "1 div = ${style.step}" else null
}
