package com.folio.notes.mistakes

import kotlin.math.max

/** Width/ascent/depth of a measured glyph run, in pixels, relative to its baseline. */
data class LatexGlyphMetrics(val width: Float, val ascent: Float, val depth: Float)

/**
 * Glyph measurement, supplied by the renderer. Keeping this an interface means the whole box
 * model in [LatexLayoutEngine] stays Android-free and unit-testable with a fake measurer.
 */
interface LatexMetrics {
    fun measure(text: String, size: Float, italic: Boolean, bold: Boolean, font: LatexFont): LatexGlyphMetrics

    /** Thickness of fraction bars, radicals, over/underlines and rules at [size]. */
    fun ruleThickness(size: Float): Float = max(1f, size * 0.045f)
}

/** A point in formula coordinates: x to the right, y downwards from the baseline. */
data class MathPoint(val x: Float, val y: Float)

/** One primitive in a laid-out formula. Coordinates are relative to the formula's baseline. */
sealed interface MathDraw {
    data class Glyph(
        val text: String,
        val x: Float,
        val baseline: Float,
        val size: Float,
        val italic: Boolean,
        val bold: Boolean,
        val font: LatexFont,
        val color: Long,
    ) : MathDraw

    data class Rule(val x: Float, val y: Float, val width: Float, val height: Float, val color: Long) : MathDraw

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val thickness: Float,
        val color: Long,
    ) : MathDraw

    data class Border(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val thickness: Float,
        val color: Long,
    ) : MathDraw

    /** A stroked polyline, used for the curves and heads of stretchy decorations. */
    data class Polyline(val points: List<MathPoint>, val thickness: Float, val color: Long) : MathDraw
}

/** A laid-out formula: its extent plus the primitives needed to paint it. */
class LatexBox(val width: Float, val ascent: Float, val depth: Float, val draws: List<MathDraw>) {
    val height: Float get() = ascent + depth

    companion object {
        val EMPTY = LatexBox(0f, 0f, 0f, emptyList())
    }
}

private fun MathDraw.shift(dx: Float, dy: Float): MathDraw = when (this) {
    is MathDraw.Glyph -> copy(x = x + dx, baseline = baseline + dy)
    is MathDraw.Rule -> copy(x = x + dx, y = y + dy)
    is MathDraw.Line -> copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)
    is MathDraw.Border -> copy(x = x + dx, y = y + dy)
    is MathDraw.Polyline -> copy(points = points.map { MathPoint(it.x + dx, it.y + dy) })
}

/**
 * TeX-style math layout: every node becomes a box with a width, a height above the baseline and
 * a depth below it, and the boxes are combined with the standard fraction/radical/script rules.
 * Sizes come from [MathStyle] rather than compounding ratios, which is what keeps deeply nested
 * scripts from collapsing to nothing.
 */
class LatexLayoutEngine(
    private val metrics: LatexMetrics,
    private val baseSize: Float,
    private val defaultColor: Long = DEFAULT_MATH_COLOR,
) {
    fun layout(node: LatexNode, style: MathStyle = MathStyle.Display): LatexBox =
        layoutNode(node, style, defaultColor)

    /** True when the font cannot draw a reasonable box for this node (never: we always can). */
    private fun empty() = LatexBox.EMPTY

    private fun sizeOf(style: MathStyle): Float = baseSize * when (style) {
        MathStyle.Display, MathStyle.Text -> 1f
        MathStyle.Script -> 0.7f
        MathStyle.ScriptScript -> 0.5f
    }

    /** TeX drops a superscript/subscript straight to script size, not through text size. */
    private fun scriptStyle(style: MathStyle): MathStyle = when (style) {
        MathStyle.Display, MathStyle.Text -> MathStyle.Script
        MathStyle.Script, MathStyle.ScriptScript -> MathStyle.ScriptScript
    }

    /** A fraction's numerator keeps one step more of the current size than its scripts do. */
    private fun fractionChildStyle(style: MathStyle): MathStyle = when (style) {
        MathStyle.Display -> MathStyle.Text
        MathStyle.Text -> MathStyle.Script
        MathStyle.Script, MathStyle.ScriptScript -> MathStyle.ScriptScript
    }

    private fun box(width: Float, ascent: Float, depth: Float, draws: List<MathDraw>) =
        LatexBox(max(0f, width), max(0f, ascent), max(0f, depth), draws)

    private fun glyph(text: String, size: Float, italic: Boolean, bold: Boolean, font: LatexFont, color: Long): LatexBox {
        if (text.isEmpty()) return empty()
        val m = metrics.measure(text, size, italic, bold, font)
        val draw = MathDraw.Glyph(text, 0f, 0f, size, italic, bold, font, color)
        return box(m.width, m.ascent, m.depth, listOf(draw))
    }

    /** Horizontal concatenation with the baselines aligned. */
    private fun hcat(boxes: List<Pair<LatexBox, Float>>): LatexBox {
        if (boxes.isEmpty()) return empty()
        var x = 0f
        var ascent = 0f
        var depth = 0f
        val draws = ArrayList<MathDraw>()
        boxes.forEach { (b, gap) ->
            x += gap
            b.draws.forEach { draws += it.shift(x, 0f) }
            x += b.width
            ascent = max(ascent, b.ascent)
            depth = max(depth, b.depth)
        }
        return box(x, ascent, depth, draws)
    }

    private fun layoutNode(node: LatexNode, style: MathStyle, color: Long): LatexBox = when (node) {
        is LatexNode.Symbol -> glyph(node.text, sizeOf(style), node.italic, node.bold, node.font, color)
        is LatexNode.Text -> glyph(node.text, sizeOf(style), node.italic, node.bold, node.font, color)
        is LatexNode.Group -> layoutSequence(node.children, style, color)
        is LatexNode.Row -> layoutSequence(node.children, style, color)
        is LatexNode.Fraction -> layoutFraction(node, style, color)
        is LatexNode.Radical -> layoutRadical(node, style, color)
        is LatexNode.Script -> layoutScript(node, style, color)
        is LatexNode.BigOp -> glyph(
            node.text,
            sizeOf(style) * if (style == MathStyle.Display) 1.25f else 1f,
            italic = false, bold = false, font = LatexFont.Serif, color = color,
        )
        is LatexNode.OperatorName -> glyph(node.text, sizeOf(style), false, false, LatexFont.Serif, color)
        is LatexNode.Accent -> layoutAccent(node, style, color)
        is LatexNode.Stretchy -> layoutStretchy(node, style, color)
        is LatexNode.Overline -> layoutOverline(node.base, style, color, above = true)
        is LatexNode.Underline -> layoutOverline(node.base, style, color, above = false)
        is LatexNode.Delimited -> layoutDelimited(node.body, node.left, node.right, style, color)
        is LatexNode.SizedDelimiter -> fixedDelimiter(node.text, sizeOf(style) * node.scale, color)
        is LatexNode.Matrix -> layoutMatrix(node, style, color)
        is LatexNode.Styled -> layoutSequence(node.children, node.style, color)
        is LatexNode.Colored -> layoutSequence(node.children, style, node.argb)
        is LatexNode.Space -> box(node.em * sizeOf(style), 0f, 0f, emptyList())
        is LatexNode.Phantomed -> layoutSequence(node.children, style, color).let { box(it.width, it.ascent, it.depth, emptyList()) }
        is LatexNode.Boxed -> layoutBoxed(node, style, color)
        is LatexNode.Cancelled -> layoutCancelled(node, style, color)
        is LatexNode.Negated -> layoutNegated(node, style, color)
        is LatexNode.Classed -> layoutNode(node.base ?: LatexNode.Group(emptyList()), style, color)
        LatexNode.LineBreak -> box(sizeOf(style) * 0.5f, 0f, 0f, emptyList())
    }

    // ---- Sequences ------------------------------------------------------------------------------

    private fun layoutSequence(nodes: List<LatexNode>, style: MathStyle, color: Long): LatexBox {
        if (nodes.any { it is LatexNode.LineBreak }) return layoutLines(nodes, style, color)
        val boxes = nodes.map { layoutNode(it, style, color) to kindOf(it) }.toMutableList()
        normaliseBinaryOperators(boxes)
        val size = sizeOf(style)
        val positioned = ArrayList<Pair<LatexBox, Float>>(boxes.size)
        boxes.forEachIndexed { index, (b, kind) ->
            val gap = if (index == 0) 0f
            else spacingEm(ATOM_SPACING[boxes[index - 1].second.ordinal][kind.ordinal]) * size
            positioned += b to gap
        }
        return hcat(positioned)
    }

    /** A `+`/`−` heads an expression as a sign, not an operator, when nothing suitable precedes it. */
    private fun normaliseBinaryOperators(boxes: MutableList<Pair<LatexBox, AtomKind>>) {
        boxes.forEachIndexed { index, (b, kind) ->
            if (kind != AtomKind.Bin) return@forEachIndexed
            val previous = if (index == 0) null else boxes[index - 1].second
            if (previous == null || previous in NON_BINARY_PRECEDERS) boxes[index] = b to AtomKind.Ord
        }
    }

    private fun layoutLines(nodes: List<LatexNode>, style: MathStyle, color: Long): LatexBox {
        val lines = mutableListOf<MutableList<LatexNode>>(mutableListOf())
        nodes.forEach { if (it is LatexNode.LineBreak) lines.add(mutableListOf()) else lines.last().add(it) }
        val laid = lines.filter { it.isNotEmpty() }.map { layoutSequence(it, style, color) }
        if (laid.isEmpty()) return empty()
        val gap = sizeOf(style) * 0.35f
        val first = laid.first()
        var offset = 0f
        var depth = first.depth
        val draws = ArrayList<MathDraw>()
        laid.forEachIndexed { index, line ->
            if (index > 0) {
                // Drop each line down by its own height plus the gap, keeping the first baseline.
                offset += laid[index - 1].height + gap
                line.draws.forEach { draws += it.shift(0f, offset) }
                depth = max(depth, offset + line.depth)
            } else {
                line.draws.forEach { draws += it }
            }
        }
        val width = laid.maxOf { it.width }
        return box(width, first.ascent, depth, draws)
    }

    private fun kindOf(node: LatexNode): AtomKind = when (node) {
        is LatexNode.Symbol -> node.kind
        is LatexNode.BigOp -> AtomKind.Op
        is LatexNode.OperatorName -> AtomKind.Op
        is LatexNode.Fraction -> AtomKind.Inner
        is LatexNode.Delimited -> AtomKind.Inner
        is LatexNode.Row -> AtomKind.Inner
        is LatexNode.Classed -> node.kind
        is LatexNode.Script -> node.base?.let { kindOf(it) } ?: AtomKind.Ord
        is LatexNode.Accent -> node.base?.let { kindOf(it) } ?: AtomKind.Ord
        is LatexNode.Cancelled -> node.base?.let { kindOf(it) } ?: AtomKind.Ord
        is LatexNode.Phantomed -> node.children.singleOrNull()?.let { kindOf(it) } ?: AtomKind.Ord
        is LatexNode.Styled -> node.children.singleOrNull()?.let { kindOf(it) } ?: AtomKind.Ord
        is LatexNode.Colored -> node.children.singleOrNull()?.let { kindOf(it) } ?: AtomKind.Ord
        is LatexNode.Matrix -> if (node.left != null || node.right != null) AtomKind.Inner else AtomKind.Ord
        is LatexNode.Text -> AtomKind.Ord
        is LatexNode.Space -> AtomKind.Ord
        LatexNode.LineBreak -> AtomKind.Ord
        else -> AtomKind.Ord
    }

    // ---- Structures -----------------------------------------------------------------------------

    private fun layoutFraction(node: LatexNode.Fraction, style: MathStyle, color: Long): LatexBox {
        val size = sizeOf(style)
        val childStyle = fractionChildStyle(style)
        val num = layoutSequence(node.num, childStyle, color)
        val den = layoutSequence(node.den, childStyle, color)
        val width = max(num.width, den.width)
        val centre = width / 2f
        val half = size * 0.08f
        val thickness = metrics.ruleThickness(size)
        val drags = ArrayList<MathDraw>()

        val numBaseline: Float
        val denBaseline: Float
        if (node.bar) {
            val axis = size * 0.25f
            numBaseline = -(axis + thickness / 2f + half) - num.depth
            denBaseline = -(axis - thickness / 2f - half) + den.ascent
            drags += MathDraw.Rule(0f, -(axis + thickness / 2f), width, thickness, color)
        } else {
            numBaseline = -half - num.depth
            denBaseline = half + den.ascent
        }

        num.draws.forEach { drags += it.shift(centre - num.width / 2f, numBaseline) }
        den.draws.forEach { drags += it.shift(centre - den.width / 2f, denBaseline) }

        val barTop = if (node.bar) -(size * 0.25f + thickness / 2f) else 0f
        val barBottom = if (node.bar) -size * 0.25f + thickness / 2f else 0f
        val top = minOf(numBaseline - num.ascent, barTop)
        val bottom = maxOf(denBaseline + den.depth, barBottom)
        return box(width, -top, bottom, drags)
    }

    private fun layoutRadical(node: LatexNode.Radical, style: MathStyle, color: Long): LatexBox {
        val size = sizeOf(style)
        val body = layoutSequence(node.body, style, color)
        val thickness = metrics.ruleThickness(size)
        val gap = size * 0.1f
        val target = body.ascent + body.depth + thickness + gap
        val probe = metrics.measure("\u221A", size, false, false, LatexFont.Serif)
        val probeHeight = (probe.ascent + probe.depth).coerceAtLeast(0.01f)
        val scale = (target / probeHeight).coerceIn(1f, 4f)
        val sign = if (scale > 1.02f) metrics.measure("\u221A", size * scale, false, false, LatexFont.Serif) else probe
        val signSize = size * scale

        val top = -(body.ascent + thickness + gap)
        val signBaseline = top + sign.ascent
        val draws = ArrayList<MathDraw>()
        draws += MathDraw.Glyph("\u221A", 0f, signBaseline, signSize, false, false, LatexFont.Serif, color)
        draws += MathDraw.Rule(sign.width * 0.82f, top, sign.width + body.width, thickness, color)
        body.draws.forEach { draws += it.shift(sign.width, 0f) }

        var ascent = max(body.ascent + thickness + gap, 0f)
        var depth = max(body.depth, signBaseline + sign.depth)
        val index = node.index
        if (index != null && index.isNotEmpty()) {
            val indexBox = layoutSequence(index, MathStyle.ScriptScript, color)
            val indexBaseline = top - size * 0.06f
            val indexX = max(-indexBox.width * 0.5f, sign.width * 0.5f - indexBox.width)
            indexBox.draws.forEach { draws += it.shift(indexX, indexBaseline) }
            ascent = max(ascent, -(indexBaseline - indexBox.ascent))
            depth = max(depth, indexBaseline + indexBox.depth)
        }
        return box(sign.width + body.width, ascent, depth, draws)
    }

    private fun layoutScript(node: LatexNode.Script, style: MathStyle, color: Long): LatexBox {
        val size = sizeOf(style)
        val base = node.base?.let { layoutNode(it, style, color) } ?: empty()
        val childStyle = scriptStyle(style)
        val sup = node.sup?.takeIf { it.isNotEmpty() }?.let { layoutSequence(it, childStyle, color) }
        val sub = node.sub?.takeIf { it.isNotEmpty() }?.let { layoutSequence(it, childStyle, color) }

        val operator = coreOperator(node.base)
        val limits = limitsFor(operator)
        val shown = when (limits) {
            LimitsMode.Force -> true
            LimitsMode.Never -> false
            LimitsMode.Auto -> effectiveStyle(node.base, style) == MathStyle.Display
        }

        if (shown && (sup != null || sub != null)) {
            val width = maxOf(base.width, sup?.width ?: 0f, sub?.width ?: 0f)
            val gapAbove = size * 0.12f
            val gapBelow = size * 0.16f
            val draws = ArrayList<MathDraw>()
            base.draws.forEach { draws += it.shift((width - base.width) / 2f, 0f) }
            var ascent = base.ascent
            var depth = base.depth
            sup?.let {
                val baseline = -(base.ascent + gapAbove + it.depth)
                it.draws.forEach { draw -> draws += draw.shift((width - it.width) / 2f, baseline) }
                ascent = max(ascent, -(baseline - it.ascent))
            }
            sub?.let {
                val baseline = base.depth + gapBelow + it.ascent
                it.draws.forEach { draw -> draws += draw.shift((width - it.width) / 2f, baseline) }
                depth = max(depth, baseline + it.depth)
            }
            return box(width, ascent, depth, draws)
        }

        val supRise = max(size * 0.42f, base.ascent - size * 0.26f)
        val subDrop = max(size * 0.16f, base.depth + size * 0.05f)
        val scriptWidth = max(sup?.width ?: 0f, sub?.width ?: 0f)
        val draws = ArrayList<MathDraw>()
        base.draws.forEach { draws += it }
        var ascent = base.ascent
        var depth = base.depth
        sup?.let {
            it.draws.forEach { draw -> draws += draw.shift(base.width, -supRise) }
            ascent = max(ascent, supRise + it.ascent)
        }
        sub?.let {
            it.draws.forEach { draw -> draws += draw.shift(base.width, subDrop) }
            depth = max(depth, subDrop + it.depth)
        }
        return box(base.width + scriptWidth, ascent, depth, draws)
    }

    /** Peels off style/colour wrappers so `\displaystyle\sum` still counts as a large operator. */
    private fun coreOperator(node: LatexNode?): LatexNode? = when (node) {
        null -> null
        is LatexNode.Styled -> node.children.singleOrNull()?.let { coreOperator(it) }
        is LatexNode.Colored -> node.children.singleOrNull()?.let { coreOperator(it) }
        is LatexNode.Classed -> coreOperator(node.base)
        is LatexNode.Group -> node.children.singleOrNull()?.let { coreOperator(it) } ?: node
        else -> node
    }

    private fun limitsFor(node: LatexNode?): LimitsMode = when (node) {
        is LatexNode.BigOp -> node.limits
        is LatexNode.OperatorName -> node.limits
        // `\overbrace{…}^{label}` and `\underbrace{…}_{label}` are math operators with forced
        // limits in LaTeX, so the label goes over/under the brace rather than beside it.
        is LatexNode.Stretchy -> if (node.kind == StretchyKind.Brace || node.kind == StretchyKind.Bracket) {
            LimitsMode.Force
        } else LimitsMode.Never
        else -> LimitsMode.Never
    }

    private fun effectiveStyle(node: LatexNode?, fallback: MathStyle): MathStyle {
        var current = node
        while (current != null) {
            current = when (current) {
                is LatexNode.Styled -> { if (current.style == MathStyle.Display) return MathStyle.Display; current.children.singleOrNull() }
                is LatexNode.Colored -> current.children.singleOrNull()
                is LatexNode.Group -> current.children.singleOrNull()
                is LatexNode.Classed -> current.base
                else -> null
            }
        }
        return fallback
    }

    private fun layoutAccent(node: LatexNode.Accent, style: MathStyle, color: Long): LatexBox {
        val size = sizeOf(style)
        val base = node.base?.let { layoutNode(it, style, color) } ?: empty()
        val mark = metrics.measure(node.mark, size * 0.85f, false, false, LatexFont.Serif)
        val gap = size * 0.05f
        val baseline = -(base.ascent + gap + mark.depth)
        val width = max(base.width, mark.width)
        val draws = ArrayList<MathDraw>()
        base.draws.forEach { draws += it.shift((width - base.width) / 2f, 0f) }
        draws += MathDraw.Glyph(node.mark, (width - mark.width) / 2f, baseline, size * 0.85f, false, false, LatexFont.Serif, color)
        return box(width, max(base.ascent, -(baseline - mark.ascent)), base.depth, draws)
    }

    /**
     * `\overbrace`, `\underbrace`, `\overrightarrow`, … — the decoration is built from geometry and
     * spans the full width of its base, so it grows with whatever it covers. Stretching a single
     * brace or arrow glyph would fatten its stroke instead, which is why these are drawn by hand.
     */
    private fun layoutStretchy(node: LatexNode.Stretchy, style: MathStyle, color: Long): LatexBox {
        val size = sizeOf(style)
        val base = node.base?.let { layoutNode(it, style, color) } ?: empty()
        val label = node.label?.let { layoutSequence(it, scriptStyle(style), color) } ?: empty()
        val stroke = max(1f, size * 0.045f)
        val gap = size * 0.12f
        val band = size * 0.42f
        // The decoration spans what it decorates, but never less than its label.
        val pad = if (node.kind == StretchyKind.Brace || node.kind == StretchyKind.Bracket) size * 0.1f else 0f
        val width = max(base.width + pad * 2f, label.width)
        val draws = ArrayList<MathDraw>()
        base.draws.forEach { draws += it.shift((width - base.width) / 2f, 0f) }

        // `dir` points away from the base, so each kind below is written once for both sides.
        val dir = if (node.above) -1f else 1f
        val near = dir * (if (node.above) base.ascent + gap else base.depth + gap)
        var extent: Float
        var shaftY = 0f
        when (node.kind) {
            StretchyKind.Brace -> {
                val far = near + dir * band
                // `bracePoints` puts the cusp at y = 0 and the two ends at y = band.
                draws += MathDraw.Polyline(
                    bracePoints(width, band).map { MathPoint(it.x, near + dir * (band - it.y)) },
                    stroke,
                    color,
                )
                extent = far
            }
            StretchyKind.Bracket -> {
                val far = near + dir * band
                val tick = far - dir * band * 0.55f
                draws += MathDraw.Polyline(
                    listOf(MathPoint(0f, tick), MathPoint(0f, far), MathPoint(width, far), MathPoint(width, tick)),
                    stroke,
                    color,
                )
                extent = far
            }
            else -> {
                val headLength = size * 0.45f
                val head = size * 0.18f
                shaftY = near + dir * head
                draws += MathDraw.Rule(0f, shaftY - stroke / 2f, width, stroke, color)
                if (node.kind != StretchyKind.ArrowLeft) {
                    draws += MathDraw.Polyline(
                        listOf(
                            MathPoint(width - headLength, shaftY - head),
                            MathPoint(width, shaftY),
                            MathPoint(width - headLength, shaftY + head),
                        ),
                        stroke,
                        color,
                    )
                }
                if (node.kind != StretchyKind.ArrowRight) {
                    draws += MathDraw.Polyline(
                        listOf(
                            MathPoint(headLength, shaftY - head),
                            MathPoint(0f, shaftY),
                            MathPoint(headLength, shaftY + head),
                        ),
                        stroke,
                        color,
                    )
                }
                extent = shaftY + dir * head
            }
        }
        if (label.width > 0f || label.height > 0f) {
            // The label goes past the decoration, on the same side as it.
            val baseline = extent + dir * (gap + if (node.above) label.depth else label.ascent)
            label.draws.forEach { draws += it.shift((width - label.width) / 2f, baseline) }
            extent = if (node.above) baseline - label.ascent else baseline + label.depth
        }
        val ascent = if (node.above) max(base.ascent, -extent) else base.ascent
        val depth = if (node.above) base.depth else max(base.depth, extent)
        // An arrow with a label is a relation, so the arrow itself lands on the maths axis; an accent
        // (`\overrightarrow`) instead keeps the base on the baseline and decorates it.
        val onAxis = !node.above && node.kind != StretchyKind.Brace && node.kind != StretchyKind.Bracket
        if (onAxis) {
            val dy = -size * 0.25f - shaftY
            return box(width, ascent - dy, depth + dy, draws.map { it.shift(0f, dy) })
        }
        return box(width, ascent, depth, draws)
    }

    /**
     * Samples a curly brace spanning [width] and [height] tall, with y = 0 at its cusp and
     * y = height at its two ends. Four quadratic curves joined by two straight limbs.
     */
    private fun bracePoints(width: Float, height: Float): List<MathPoint> {
        val radius = minOf(height / 2f, width / 4f)
        val shoulder = height - radius
        val half = width / 2f
        val points = ArrayList<MathPoint>()
        fun quad(p0: MathPoint, control: MathPoint, p1: MathPoint) {
            val steps = 12
            for (i in 0..steps) {
                // Later curves continue from the point the previous one ended on.
                if (i == 0 && points.isNotEmpty()) continue
                val t = i / steps.toFloat()
                val u = 1f - t
                points += MathPoint(
                    u * u * p0.x + 2f * u * t * control.x + t * t * p1.x,
                    u * u * p0.y + 2f * u * t * control.y + t * t * p1.y,
                )
            }
        }
        quad(MathPoint(0f, height), MathPoint(0f, shoulder), MathPoint(radius, shoulder))
        points += MathPoint(half - radius, shoulder)
        quad(MathPoint(half - radius, shoulder), MathPoint(half, shoulder), MathPoint(half, 0f))
        quad(MathPoint(half, 0f), MathPoint(half, shoulder), MathPoint(half + radius, shoulder))
        points += MathPoint(width - radius, shoulder)
        quad(MathPoint(width - radius, shoulder), MathPoint(width, shoulder), MathPoint(width, height))
        return points
    }

    private fun layoutOverline(base: LatexNode?, style: MathStyle, color: Long, above: Boolean): LatexBox {
        val size = sizeOf(style)
        val inner = base?.let { layoutNode(it, style, color) } ?: empty()
        val thickness = metrics.ruleThickness(size)
        val gap = size * 0.1f
        val draws = ArrayList<MathDraw>()
        inner.draws.forEach { draws += it }
        return if (above) {
            draws += MathDraw.Rule(0f, -(inner.ascent + gap + thickness), inner.width, thickness, color)
            box(inner.width, inner.ascent + gap + thickness, inner.depth, draws)
        } else {
            draws += MathDraw.Rule(0f, inner.depth + gap, inner.width, thickness, color)
            box(inner.width, inner.ascent, inner.depth + gap + thickness, draws)
        }
    }

    private fun layoutDelimited(
        body: List<LatexNode>,
        left: String?,
        right: String?,
        style: MathStyle,
        color: Long,
    ): LatexBox {
        val inner = layoutSequence(body, style, color)
        return surround(inner, left, right, style, color)
    }

    private fun surround(inner: LatexBox, left: String?, right: String?, style: MathStyle, color: Long): LatexBox {
        val size = sizeOf(style)
        val gap = size * 0.12f
        val target = inner.ascent + inner.depth + gap * 2f
        val parts = ArrayList<Pair<LatexBox, Float>>(3)
        val leftBox = left?.let { scaledDelimiter(it, target, style, color) }
        val rightBox = right?.let { scaledDelimiter(it, target, style, color) }
        leftBox?.let { parts += it to 0f }
        parts += inner to 0f
        rightBox?.let { parts += it to 0f }
        val combined = hcat(parts)
        // The delimiters are centred on the body's own vertical centre.
        val bodyCentre = (inner.depth - inner.ascent) / 2f
        val offsetLeft = leftBox?.let { bodyCentre + (it.ascent - it.depth) / 2f } ?: 0f
        val offsetRight = rightBox?.let { bodyCentre + (it.ascent - it.depth) / 2f } ?: 0f
        val leftWidth = leftBox?.width ?: 0f
        val rightWidth = rightBox?.width ?: 0f
        val draws = ArrayList<MathDraw>()
        leftBox?.draws?.forEach { draws += it.shift(0f, offsetLeft) }
        inner.draws.forEach { draws += it.shift(leftWidth, 0f) }
        rightBox?.draws?.forEach { draws += it.shift(leftWidth + inner.width, offsetRight) }
        var ascent = inner.ascent
        var depth = inner.depth
        leftBox?.let { ascent = max(ascent, it.ascent - offsetLeft); depth = max(depth, offsetLeft + it.depth) }
        rightBox?.let { ascent = max(ascent, it.ascent - offsetRight); depth = max(depth, offsetRight + it.depth) }
        return box(combined.width, ascent, depth, draws)
    }

    /** A `\big(`-style delimiter at an explicit font size. */
    private fun fixedDelimiter(text: String, size: Float, color: Long): LatexBox {
        if (text.isEmpty()) return empty()
        val m = metrics.measure(text, size, false, false, LatexFont.Serif)
        return box(m.width, m.ascent, m.depth, listOf(MathDraw.Glyph(text, 0f, 0f, size, false, false, LatexFont.Serif, color)))
    }

    /** A `\left…\right` delimiter, grown just enough to cover its contents. */
    private fun scaledDelimiter(text: String, targetHeight: Float, style: MathStyle, color: Long): LatexBox {
        if (text.isEmpty()) return empty()
        val size = sizeOf(style)
        val probe = metrics.measure(text, size, false, false, LatexFont.Serif)
        val height = (probe.ascent + probe.depth).coerceAtLeast(0.01f)
        val scale = (targetHeight / height).coerceIn(1f, 4f)
        return fixedDelimiter(text, size * scale, color)
    }

    private fun layoutMatrix(node: LatexNode.Matrix, style: MathStyle, color: Long): LatexBox {
        val size = sizeOf(style)
        val columns = node.rows.maxOfOrNull { it.size } ?: 0
        if (columns == 0) return empty()
        // amsmath sets `aligned`/`align` cells in display style; a plain `matrix` stays in text style.
        val cellStyle = if (node.amsmathTemplate && style == MathStyle.Display) MathStyle.Display else MathStyle.Text
        val aligns = node.aligns
        val rows = node.rows.map { row ->
            row.mapIndexed { c, cell ->
                val padRelation = node.amsmathTemplate && aligns?.getOrNull(c) == 'l' && startsWithRelation(cell)
                layoutSequence(if (padRelation) listOf(LEADING_ORD) + cell else cell, cellStyle, color)
            }
        }

        val widths = FloatArray(columns) { 1f }
        rows.forEach { row -> row.forEachIndexed { c, cell -> if (c < columns) widths[c] = max(widths[c], cell.width) } }
        // Inside an `rl` pair the columns abut: the space before the relation is supplied by the
        // leading `{}` the amsmath template inserts. Between pairs the usual gap applies.
        val gaps = FloatArray(columns) { c ->
            val withinPair = aligns != null && c < columns - 1 && aligns[c] == 'r' && aligns[c + 1] == 'l'
            if (c == columns - 1 || withinPair) 0f else size * 0.8f
        }
        val rowGap = size * 0.3f

        val rowAscent = FloatArray(rows.size)
        val rowDepth = FloatArray(rows.size)
        rows.forEachIndexed { r, row ->
            rowAscent[r] = row.maxOfOrNull { it.ascent } ?: 0f
            rowDepth[r] = row.maxOfOrNull { it.depth } ?: 0f
        }
        val totalHeight = rowAscent.indices.sumOf { (rowAscent[it] + rowDepth[it]).toDouble() }.toFloat() +
            rowGap * (rows.size - 1).coerceAtLeast(0)
        val centre = totalHeight / 2f

        val totalWidth = widths.indices.sumOf { (widths[it] + gaps[it]).toDouble() }.toFloat()
        val draws = ArrayList<MathDraw>()
        var rowTop = 0f
        rows.forEachIndexed { r, row ->
            val baseline = rowTop + rowAscent[r] - centre
            var columnX = 0f
            row.forEachIndexed { c, cell ->
                if (c < columns) {
                    val x = when (aligns?.getOrNull(c) ?: 'c') {
                        'l' -> columnX
                        'r' -> columnX + widths[c] - cell.width
                        else -> columnX + (widths[c] - cell.width) / 2f
                    }
                    cell.draws.forEach { draws += it.shift(x, baseline) }
                    columnX += widths[c] + gaps[c]
                }
            }
            rowTop += rowAscent[r] + rowDepth[r] + rowGap
        }
        val inner = box(totalWidth, centre, centre, draws)
        return surround(inner, node.left, node.right, style, color)
    }

    /** A leading binary operator or relation needs an atom before it to take its spacing from. */
    private fun startsWithRelation(cell: List<LatexNode>): Boolean {
        val kind = cell.firstOrNull()?.let { kindOf(it) } ?: return false
        return kind == AtomKind.Rel || kind == AtomKind.Bin
    }

    private fun layoutBoxed(node: LatexNode.Boxed, style: MathStyle, color: Long): LatexBox {
        val size = sizeOf(style)
        val inner = node.base?.let { layoutNode(it, style, color) } ?: empty()
        val thickness = metrics.ruleThickness(size)
        val pad = size * 0.25f
        val draws = ArrayList<MathDraw>()
        inner.draws.forEach { draws += it }
        draws += MathDraw.Border(-pad, -(inner.ascent + pad), inner.width + pad * 2f, inner.height + pad * 2f, thickness, color)
        return box(inner.width + pad * 2f, inner.ascent + pad + thickness, inner.depth + pad + thickness, draws)
    }

    private fun layoutCancelled(node: LatexNode.Cancelled, style: MathStyle, color: Long): LatexBox {
        val inner = node.base?.let { layoutNode(it, style, color) } ?: empty()
        val thickness = metrics.ruleThickness(sizeOf(style))
        val draws = ArrayList<MathDraw>()
        inner.draws.forEach { draws += it }
        if (node.forward) draws += MathDraw.Line(0f, -inner.ascent, inner.width, inner.depth, thickness, color)
        if (node.backward) draws += MathDraw.Line(0f, inner.depth, inner.width, -inner.ascent, thickness, color)
        return box(inner.width, inner.ascent, inner.depth, draws)
    }

    private fun layoutNegated(node: LatexNode.Negated, style: MathStyle, color: Long): LatexBox {
        val inner = node.base?.let { layoutNode(it, style, color) } ?: empty()
        val thickness = metrics.ruleThickness(sizeOf(style))
        val draws = ArrayList<MathDraw>()
        inner.draws.forEach { draws += it }
        draws += MathDraw.Line(
            inner.width * 0.2f, -inner.ascent - thickness,
            inner.width * 0.8f, inner.depth + thickness,
            thickness, color,
        )
        return box(inner.width, max(inner.ascent, inner.ascent + thickness), inner.depth + thickness, draws)
    }

    private companion object {
        val NON_BINARY_PRECEDERS = setOf(
            AtomKind.Bin, AtomKind.Op, AtomKind.Rel, AtomKind.Open, AtomKind.Punct, AtomKind.Inner,
        )

        /** A zero-width ordinary atom — the `{}` amsmath places before an alignment point. */
        val LEADING_ORD = LatexNode.Group(emptyList())
    }
}
