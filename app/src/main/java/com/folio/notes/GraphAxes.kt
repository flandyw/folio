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
    const val ARROW = .04f
    const val TICK = .022f
    /** Labels sit this far off the axis, as a multiple of their own height. */
    const val LABEL_GAP = .28f
    /** The grid is drawn this fraction as heavy and as dark as the axes. */
    const val GRID_WIDTH = .55f
    const val GRID_OPACITY = .4f

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
        val xBoth = style.origin.xBoth; val yBoth = style.origin.yBoth
        val short = min(frame.width, frame.height)
        val head = (short * ARROW).coerceAtMost(10f)
        val wing = head * .5f
        fun line(vararg points: InkPoint) = draft.copy(tool = Tool.LINE, points = points.toList())
        // The grid is a backdrop: thinner and fainter than the axes it sits behind.
        fun gridLine(vararg points: InkPoint) = draft.copy(
            tool = Tool.LINE, points = points.toList(),
            width = (draft.width * GRID_WIDTH).coerceAtLeast(.5f), opacity = draft.opacity * GRID_OPACITY
        )
        val out = mutableListOf<Stroke>()

        // Divisions along each half axis. With square cells the count fits the shorter half axis and
        // the longer one gets as many whole cells as it can hold, so a wide drag never stretches the grid.
        val divisions = style.divisions
        val cellX: Float; val cellY: Float; val countX: Int; val countY: Int
        if (divisions <= 0) {
            cellX = halfX; cellY = halfY; countX = 0; countY = 0
        } else if (style.squareCells) {
            val cell = min(halfX, halfY) / divisions
            cellX = cell; cellY = cell
            countX = (halfX / cell + .001f).toInt(); countY = (halfY / cell + .001f).toInt()
        } else {
            cellX = halfX / divisions; cellY = halfY / divisions
            countX = divisions; countY = divisions
        }

        // Faint grid first, so the axes and their labels sit on top of it. The line on the frame's
        // own edge is left out: the axis end (or nothing) already marks it.
        if (style.grid && divisions > 0) {
            for (i in 1..countX) {
                val dx = i * cellX
                if (dx >= halfX - 1f) continue
                out += gridLine(InkPoint(originX + dx, frame.top), InkPoint(originX + dx, frame.bottom))
                if (xBoth) out += gridLine(InkPoint(originX - dx, frame.top), InkPoint(originX - dx, frame.bottom))
            }
            for (i in 1..countY) {
                val dy = i * cellY
                if (dy >= halfY - 1f) continue
                out += gridLine(InkPoint(frame.left, originY - dy), InkPoint(frame.right, originY - dy))
                if (yBoth) out += gridLine(InkPoint(frame.left, originY + dy), InkPoint(frame.right, originY + dy))
            }
        }

        // The axes themselves span the frame, whether they cross in the middle, at an edge or a corner.
        out += line(InkPoint(frame.left, originY), InkPoint(frame.right, originY))
        out += line(InkPoint(originX, frame.bottom), InkPoint(originX, frame.top))
        if (style.arrows) {
            // Only the ends that run past the origin are free: a one-sided axis has just one arrowhead.
            if (xBoth) out += line(InkPoint(frame.left + head, originY - wing), InkPoint(frame.left, originY), InkPoint(frame.left + head, originY + wing))
            out += line(InkPoint(frame.right - head, originY - wing), InkPoint(frame.right, originY), InkPoint(frame.right - head, originY + wing))
            out += line(InkPoint(originX - wing, frame.top + head), InkPoint(originX, frame.top), InkPoint(originX + wing, frame.top + head))
            if (yBoth) out += line(InkPoint(originX - wing, frame.bottom - head), InkPoint(originX, frame.bottom), InkPoint(originX + wing, frame.bottom - head))
        }

        if (divisions <= 0) return out
        val tick = (short * TICK).coerceAtMost(7f)
        var glyph = (min(cellX, cellY) * .34f).coerceAtMost(short * .08f)
        // Numbers on a bottom-edge axis hang inside the graph (the edge has no room below), and
        // numbers on a left-edge axis sit to its right, for the same reason.
        val xBelow = yBoth; val yLeft = xBoth
        // Leave the arrowhead and a little breathing room free of ticks and their numbers.
        val endGap = if (style.arrows) head + tick / 2f else 0f
        fun xText(i: Int) = if (style.piDen > 0) graphPiLabel(i, style.piDen) else graphNumber(i * style.step)
        fun yText(i: Int) = graphNumber(i * style.yUnit)

        // Wide labels (0.25, 3π/2) would run into their neighbours: shrink them a little if that
        // keeps them readable, otherwise label every other (or third…) division.
        var skip = 1
        if (style.numbers) {
            val widest = (1..countX).maxOfOrNull { GraphGlyphs.width(xText(it)) } ?: 0f
            val room = cellX * .9f
            if (widest * glyph > room) {
                val fit = room / widest
                if (fit >= MIN_GLYPH * 1.3f) glyph = fit
                else skip = kotlin.math.ceil(widest * glyph / room).toInt().coerceAtLeast(1)
            }
        }

        val ways = if (xBoth) listOf(-1, 1) else listOf(1)
        for (i in 1..countX) {
            val clear = !style.arrows || halfX - i * cellX > endGap
            if (!clear) continue
            for (side in ways) {
                val x = originX + side * i * cellX
                if (style.ticks) out += line(InkPoint(x, originY - tick / 2f), InkPoint(x, originY + tick / 2f))
                if (style.numbers && i % skip == 0) {
                    val text = xText(i * side)
                    out += graphLabelStrokes(draft, text, x, originY + (if (xBelow) tick / 2f else -tick / 2f), glyph,
                        above = !xBelow, align = LabelAlign.CENTRE, limit = frame)
                }
            }
        }
        for (i in 1..countY) {
            val clear = !style.arrows || halfY - i * cellY > endGap
            if (!clear) continue
            for (side in if (yBoth) listOf(-1, 1) else listOf(1)) {
                val y = originY - side * i * cellY
                if (style.ticks) out += line(InkPoint(originX - tick / 2f, y), InkPoint(originX + tick / 2f, y))
                if (style.numbers) {
                    out += graphLabelStrokes(draft, yText(i * side), originX + (if (yLeft) -tick / 2f else tick / 2f), y, glyph,
                        above = true, align = if (yLeft) LabelAlign.LEFT else LabelAlign.RIGHT, limit = frame)
                }
            }
        }
        if (style.originLabel) {
            // The conventional O in the free corner beside the crossing, clear of both axes' numbers.
            out += graphLabelStrokes(draft, "O", originX + (if (yLeft) -tick / 2f else tick / 2f), originY + (if (xBelow) tick / 2f else -tick / 2f), glyph,
                above = !xBelow, align = if (yLeft) LabelAlign.LEFT else LabelAlign.RIGHT, limit = frame)
        }
        if (style.letters) {
            // The x sits at the end of its axis on the free side of it (below, or above when the
            // axis lies on the bottom edge); the y to the right of its own, where the numbers
            // hanging left of it never reach.
            out += graphLabelStrokes(draft, "x", frame.right - glyph * (if (style.arrows) .2f else .8f),
                originY + (if (xBelow) tick else -tick), glyph, above = !xBelow, align = LabelAlign.CENTRE, limit = frame)
            out += graphLabelStrokes(draft, "y", originX + tick / 2f, frame.top + glyph, glyph,
                above = false, align = LabelAlign.RIGHT, limit = frame)
        }
        return out
    }

    /**
     * [text] as ink with its cap height [size] in page units, set beside its anchor by [align].
     * [above] sets the cap over the anchor (a label standing on its tick) rather than under it. Labels that
     * would fall outside [limit] or would be too small to read are simply not drawn — a cramped
     * graph keeps its axes rather than smudging unreadable numbers over them.
     */
    private fun graphLabelStrokes(
        draft: Stroke, text: String, atX: Float, atY: Float, size: Float,
        above: Boolean, align: LabelAlign, limit: GraphFrame
    ): List<Stroke> {
        if (!GraphGlyphs.supports(text) || size < MIN_GLYPH) return emptyList()
        val textWidth = GraphGlyphs.width(text) * size
        val left = when (align) {
            LabelAlign.CENTRE -> atX - textWidth / 2f
            LabelAlign.LEFT -> atX - textWidth - size * LABEL_GAP
            LabelAlign.RIGHT -> atX + size * LABEL_GAP
        }
        // Cap height runs up from the baseline; a tick's label sits over it, an axis's under it.
        val top = if (above) atY - size else atY + size * LABEL_GAP
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
        if (style.divisions > 0 && style.numbers) {
            val x = if (style.piDen > 0) graphPiLabel(1, style.piDen) else graphNumber(style.step)
            if (style.piDen == 0 && style.yUnit != style.step) "1 div = $x, ${graphNumber(style.yUnit)}" else "1 div = $x"
        } else null
}
