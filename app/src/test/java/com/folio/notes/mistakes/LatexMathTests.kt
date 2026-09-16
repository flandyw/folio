package com.folio.notes.mistakes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grammar and box-model tests for the local LaTeX engine.
 *
 * Layout assertions run against a deterministic fake measurer (every glyph is half an em wide,
 * with a 0.7em ascent and 0.2em depth), so the expected geometry is exact rather than
 * font-dependent.
 */
class LatexMathTests {
    private class FixedMetrics : LatexMetrics {
        override fun measure(text: String, size: Float, italic: Boolean, bold: Boolean, font: LatexFont) =
            LatexGlyphMetrics(text.length * size * 0.5f, size * 0.7f, size * 0.2f)

        override fun ruleThickness(size: Float) = size * 0.05f
    }

    private fun firstRoot(latex: String): LatexNode = (LatexParser.parse(latex) as LatexNode.Row).children.single()

    private fun box(latex: String, style: MathStyle = MathStyle.Display, size: Float = BASE) =
        LatexLayoutEngine(FixedMetrics(), size).layout(LatexParser.parse(latex), style)

    private fun glyphs(latex: String, style: MathStyle = MathStyle.Display) =
        box(latex, style).draws.filterIsInstance<MathDraw.Glyph>()

    private fun glyph(latex: String, text: String, style: MathStyle = MathStyle.Display) =
        glyphs(latex, style).first { it.text == text }

    // ---- Grammar --------------------------------------------------------------------------------

    @Test fun fractionsParse() {
        val fraction = firstRoot("\\frac{a+1}{b-2}") as LatexNode.Fraction
        assertEquals("a+1", LatexParser.toUnicode(fraction.num))
        assertEquals("b\u22122", LatexParser.toUnicode(fraction.den))
        assertTrue(fraction.bar)
    }

    @Test fun radicalIndexParses() {
        val radical = firstRoot("\\sqrt[3]{x+1}") as LatexNode.Radical
        assertEquals("x+1", LatexParser.toUnicode(radical.body))
        assertEquals("3", LatexParser.toUnicode(radical.index!!))
    }

    @Test fun scriptsMergeOnOneBase() {
        val script = firstRoot("x_a^b") as LatexNode.Script
        assertEquals("b", LatexParser.toUnicode(script.sup!!))
        assertEquals("a", LatexParser.toUnicode(script.sub!!))
    }

    @Test fun primesBecomeSuperscripts() {
        val script = firstRoot("x''") as LatexNode.Script
        val sup = script.sup!!.filterIsInstance<LatexNode.Symbol>()
        assertEquals(2, sup.size)
        assertEquals("\u2032", sup.first().text)
    }

    @Test fun prIsAnUprightOperatorWithLimits() {
        val operator = firstRoot("\\Pr") as LatexNode.OperatorName
        assertEquals("Pr", operator.text)
        assertEquals(LimitsMode.Auto, operator.limits)
    }

    @Test fun otherNamedOperatorsKeepTheirLimitRules() {
        assertEquals(LimitsMode.Auto, (firstRoot("\\lim") as LatexNode.OperatorName).limits)
        assertEquals(LimitsMode.Never, (firstRoot("\\sin") as LatexNode.OperatorName).limits)
        assertEquals(LimitsMode.Never, (firstRoot("\\log") as LatexNode.OperatorName).limits)
        assertEquals(LimitsMode.Auto, (firstRoot("\\operatorname*{argmax}") as LatexNode.OperatorName).limits)
        assertEquals(LimitsMode.Never, (firstRoot("\\operatorname{rank}") as LatexNode.OperatorName).limits)
    }

    @Test fun explicitLimitsOverrideTheDefault() {
        assertEquals(LimitsMode.Force, (firstRoot("\\int\\limits_0^1") as LatexNode.Script).let { specifiedLimits(it.base!!) })
        assertEquals(LimitsMode.Never, (firstRoot("\\sum\\nolimits_{i}") as LatexNode.Script).let { specifiedLimits(it.base!!) })
    }

    private fun specifiedLimits(node: LatexNode): LimitsMode = (node as LatexNode.BigOp).limits

    @Test fun unknownCommandIsKept() {
        assertEquals("weirdcommand", LatexParser.plainText("\\weirdcommand"))
    }

    @Test fun greekAndSymbolTables() {
        assertEquals("α", LatexParser.plainText("\\alpha"))
        assertEquals("Γ", LatexParser.plainText("\\Gamma"))
        assertEquals("×", LatexParser.plainText("\\times"))
        assertEquals("≤", LatexParser.plainText("\\le"))
        assertEquals("∑", LatexParser.plainText("\\sum"))
        assertEquals("∈", LatexParser.plainText("\\in"))
        assertEquals("∉", LatexParser.plainText("\\notin"))
    }

    @Test fun textCommandPreservesSpaces() {
        assertEquals("hello world", (firstRoot("\\text{hello world}") as LatexNode.Text).text)
        // Runs of whitespace collapse the way TeX collapses them, but single spaces survive.
        assertEquals(" spaced ", (firstRoot("\\text{  spaced  }") as LatexNode.Text).text)
    }

    @Test fun escapedCharactersAreLiteral() {
        assertEquals("%", (firstRoot("\\%") as LatexNode.Text).text)
        assertEquals("{", (firstRoot("\\{") as LatexNode.Symbol).text)
        assertEquals("‖", (firstRoot("\\|") as LatexNode.Symbol).text)
    }

    @Test fun leftRightDelimitersParse() {
        val delimited = firstRoot("\\left( \\frac{a}{b} \\right)") as LatexNode.Delimited
        assertEquals("(", delimited.left)
        assertEquals(")", delimited.right)
        val oneSided = firstRoot("\\left. x \\right|") as LatexNode.Delimited
        assertEquals(null, oneSided.left)
        assertEquals("|", oneSided.right)
    }

    @Test fun environmentsBuildMatrices() {
        val matrix = firstRoot("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}") as LatexNode.Matrix
        assertEquals(2, matrix.rows.size)
        assertEquals(2, matrix.rows[0].size)
        assertEquals("a", LatexParser.toUnicode(matrix.rows[0][0]))
        assertEquals("d", LatexParser.toUnicode(matrix.rows[1][1]))
        assertEquals("(", matrix.left)
        assertEquals(")", matrix.right)
    }

    @Test fun casesIsOneSided() {
        val cases = firstRoot("\\begin{cases} x & x > 0 \\\\ 0 & x = 0 \\end{cases}") as LatexNode.Matrix
        assertEquals("{", cases.left)
        assertEquals(null, cases.right)
        assertEquals(2, cases.rows.size)
    }

    @Test fun arrayColumnSpecIsKept() {
        val matrix = firstRoot("\\begin{array}{lc} a & b \\\\ c & d \\end{array}") as LatexNode.Matrix
        assertEquals(listOf('l', 'c'), matrix.aligns)
    }

    @Test fun coloursParse() {
        assertEquals(0xFFE53935L, (firstRoot("\\textcolor{red}{x}") as LatexNode.Colored).argb)
        assertEquals(0xFF00FF00L, (firstRoot("\\textcolor{#00ff00}{x}") as LatexNode.Colored).argb)
    }

    @Test fun textFontCommandsChangePosture() {
        val row = LatexParser.parse("\\mathrm{d}x") as LatexNode.Row
        val group = row.children.first() as LatexNode.Group
        val d = group.children.first() as LatexNode.Symbol
        assertFalse(d.italic)
    }

    @Test fun doubleBarCommandDiffersFromTheSingleBar() {
        val delimited = firstRoot("\\left\\| x \\right\\|") as LatexNode.Delimited
        assertEquals("\u2016", delimited.left)
        assertEquals("\u2016", delimited.right)
        assertEquals("|", (firstRoot("\\left| x \\right|") as LatexNode.Delimited).left)
    }

    @Test fun deeplyNestedInputDoesNotExhaustTheStack() {
        val deep = "{".repeat(5000) + "x" + "}".repeat(5000)
        assertNotNull(LatexParser.parse(deep))
        assertNotNull(box(deep))
    }

    @Test fun malformedInputNeverThrows() {
        assertNotNull(LatexParser.parse("\\frac{a}{"))
        assertNotNull(LatexParser.parse("\\begin{pmatrix} a"))
        assertNotNull(LatexParser.parse("\\left( x"))
        assertNotNull(LatexParser.parse("x^"))
        assertNotNull(box("\\sqrt{\\frac{1}{"))
        assertNotNull(box("{{{{"))
    }

    // ---- Layout ---------------------------------------------------------------------------------

    @Test fun fractionStacksNumeratorOverDenominator() {
        val fraction = box("\\frac{1}{2}")
        val one = fraction.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "1" }
        val two = fraction.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "2" }
        assertTrue("numerator sits above the baseline", one.baseline < 0f)
        assertTrue("denominator sits below the baseline", two.baseline > 0f)
        assertTrue("the bar is drawn", fraction.draws.any { it is MathDraw.Rule })
        assertTrue(fraction.ascent > box("1").ascent)
    }

    @Test fun binomialsHaveNoBar() {
        assertFalse(box("\\binom{n}{k}").draws.any { it is MathDraw.Rule })
    }

    @Test fun radicalsDrawTheSignAndTheOverline() {
        val radical = box("\\sqrt{2}")
        assertTrue(radical.draws.any { it is MathDraw.Glyph && it.text == "\u221A" })
        assertTrue(radical.draws.any { it is MathDraw.Rule })
        val indexed = glyphs("\\sqrt[3]{2}")
        val index = indexed.first { it.text == "3" }
        assertEquals(BASE * 0.5f, index.size, 0.001f)
    }

    @Test fun scriptSizeShrinksWithNesting() {
        val outer = glyph("x^{y}", "y")
        assertEquals(BASE * 0.7f, outer.size, 0.001f)
        val nested = glyph("x^{y^{z}}", "z")
        assertEquals(BASE * 0.5f, nested.size, 0.001f)
    }

    @Test fun ordinaryScriptsSitBesideTheBase() {
        val base = glyph("x^{2}", "x")
        val sup = glyph("x^{2}", "2")
        assertTrue("superscript is raised", sup.baseline < 0f)
        assertTrue("superscript is to the right", sup.x >= base.x + base.size * 0.5f - 0.01f)
        val sub = glyph("x_{i}", "i")
        assertTrue("subscript drops below", sub.baseline > 0f)
    }

    @Test fun prPutsItsLimitsBelowInDisplayStyleOnly() {
        val displayBase = glyph("\\Pr_{X}", "Pr", MathStyle.Display)
        val displaySub = glyph("\\Pr_{X}", "X", MathStyle.Display)
        assertTrue("limits are centred under the operator", displaySub.x < displayBase.size * 0.5f)
        assertTrue("the subscript drops below the operator", displaySub.baseline > 0f)

        val textBase = glyph("\\Pr_{X}", "Pr", MathStyle.Text)
        val textSub = glyph("\\Pr_{X}", "X", MathStyle.Text)
        assertTrue("side subscripts start after the operator", textSub.x >= textBase.x + textBase.size * 0.5f - 0.01f)
    }

    @Test fun sumTakesLimitsInDisplayAndIntegralNeverDoes() {
        val sumBase = glyph("\\sum_{i=1}^{n}", "∑", MathStyle.Display)
        val sumSub = glyph("\\sum_{i=1}^{n}", "i", MathStyle.Display)
        assertTrue(sumSub.x < sumBase.size * 0.5f)

        val sumTextSub = glyph("\\sum_{i=1}^{n}", "i", MathStyle.Text)
        val sumTextBase = glyph("\\sum_{i=1}^{n}", "∑", MathStyle.Text)
        assertTrue(sumTextSub.x >= sumTextBase.x + sumTextBase.size * 0.5f - 0.01f)

        val integralBase = glyph("\\int_{0}^{1}", "∫", MathStyle.Display)
        val integralSub = glyph("\\int_{0}^{1}", "0", MathStyle.Display)
        assertTrue(integralSub.x >= integralBase.size * 0.5f - 0.01f)
    }

    @Test fun atomClassesDriveSpacing() {
        val tight = box("ab", MathStyle.Text).width
        val binary = box("a+b", MathStyle.Text).width
        val relation = box("a=b", MathStyle.Text).width
        assertTrue("binary operators get medium space", binary > tight + 0.5f)
        assertTrue("relations get more space than binaries", relation > binary)

        // A leading sign is not a binary operator, so no space is inserted before it.
        assertEquals(box("+b", MathStyle.Text).width, box("-b", MathStyle.Text).width, 0.001f)
        assertTrue(box("-b", MathStyle.Text).width < box("a-b", MathStyle.Text).width)
    }

    @Test fun explicitSpacingCommandsAddRoom() {
        val plain = box("ab", MathStyle.Text).width
        val spaced = box("a\\,b", MathStyle.Text).width
        assertTrue(spaced > plain)
        assertTrue(box("a\\quad b", MathStyle.Text).width > spaced)
    }

    @Test fun delimitersGrowWithTheirContents() {
        val tall = glyphs("\\left( \\frac{a}{b} \\right)").first { it.text == "(" }
        val short = glyphs("(a)").first { it.text == "(" }
        assertTrue("a tall body stretches the delimiter", tall.size > short.size)
    }

    @Test fun bigDelimiterCommandsScaleExplicitly() {
        val plain = glyphs("(x)").first { it.text == "(" }
        val big = glyphs("\\big( x \\big)").first { it.text == "(" }
        val bigger = glyphs("\\Bigg( x \\Bigg)").first { it.text == "(" }
        assertTrue(big.size > plain.size)
        assertTrue(bigger.size > big.size)
    }

    @Test fun boxedAndCancelledDrawTheirDecoration() {
        assertTrue(box("\\boxed{x}").draws.any { it is MathDraw.Border })
        assertTrue(box("\\cancel{x}").draws.any { it is MathDraw.Line })
        assertTrue(box("\\not=").draws.any { it is MathDraw.Line })
    }

    @Test fun accentsAreDrawnAboveTheBase() {
        val hat = box("\\hat{x}")
        val mark = hat.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "^" }
        assertTrue(mark.baseline < 0f)
        assertTrue(hat.ascent > box("x").ascent)
        assertTrue(box("\\vec{v}").draws.any { it is MathDraw.Glyph && it.text == "→" })
    }

    @Test fun lineBreaksStackRows() {
        val stacked = box("a \\\\ b", MathStyle.Text)
        val a = stacked.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "a" }
        val b = stacked.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "b" }
        assertTrue(b.baseline > a.baseline)
    }

    @Test fun emptyAndDegenerateInputLaysOutToNothing() {
        assertEquals(0f, box("").width, 0.001f)
        assertEquals(0f, box("   ").width, 0.001f)
    }

    @Test fun matrixAlignsColumns() {
        val matrix = box("\\begin{pmatrix} a & bb \\\\ ccc & d \\end{pmatrix}")
        val a = matrix.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "a" }
        val d = matrix.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "d" }
        assertTrue("second column is right of the first", d.x > a.x)
        assertTrue(matrix.ascent > 0f && matrix.depth > 0f)
    }

    // ---- Stretchy decorations --------------------------------------------------------------------

    @Test fun overbraceSpansItsArgument() {
        val short = box("\\overbrace{x}")
        val long = box("\\overbrace{x+y+z}")
        val shortBrace = braceSpan(short)
        val longBrace = braceSpan(long)
        assertTrue("the brace grows with its argument", longBrace > shortBrace)
        assertEquals("the brace spans the whole box", long.width, longBrace, 0.001f)
        assertTrue("drawn above the baseline", bracePoints(long).all { it.y < 0f })
        val plain = box("x+y+z")
        assertTrue("it raises the box", long.ascent > plain.ascent)
        assertEquals("the base keeps its depth", plain.depth, long.depth, 0.001f)
    }

    @Test fun underbraceHangsBelowItsArgument() {
        val under = box("\\underbrace{a+b}")
        assertTrue("drawn below the baseline", bracePoints(under).all { it.y > 0f })
        assertTrue(under.depth > box("a+b").depth)
        assertEquals(box("a+b").ascent, under.ascent, 0.001f)
    }

    @Test fun braceLabelsGoOverTheBrace() {
        val labelled = box("\\overbrace{a+b}^{n}")
        val n = labelled.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "n" }
        val top = bracePoints(labelled).minOf { it.y }
        assertTrue("the label sits above the brace", n.baseline < top)
    }

    @Test fun stretchyArrowsGrowWithTheirArgument() {
        val short = box("\\overrightarrow{v}")
        val long = box("\\overrightarrow{v+w}")
        val shortArrow = short.draws.filterIsInstance<MathDraw.Rule>().single()
        val longArrow = long.draws.filterIsInstance<MathDraw.Rule>().single()
        assertTrue("a longer argument gives a longer arrow", longArrow.width > shortArrow.width)
        assertEquals("the arrow spans the argument", long.width, longArrow.width, 0.001f)
        assertEquals("one head", 1, short.draws.filterIsInstance<MathDraw.Polyline>().size)
        assertEquals("two heads", 2, box("\\overleftrightarrow{v}").draws.filterIsInstance<MathDraw.Polyline>().size)
        assertTrue(short.ascent > box("v").ascent)
    }

    @Test fun xrightarrowSpansItsLabelAndSitsOnTheAxis() {
        val short = box("a \\xrightarrow{f} b", MathStyle.Text)
        val long = box("a \\xrightarrow{f+g+h} b", MathStyle.Text)
        val f = short.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "f" }
        val arrow = short.draws.filterIsInstance<MathDraw.Rule>().single()
        val longArrow = long.draws.filterIsInstance<MathDraw.Rule>().single()
        assertTrue("the arrow grows with its label", longArrow.width > arrow.width)
        assertEquals("the arrow is exactly as wide as its label", f.size * 0.5f, arrow.width, 0.001f)
        assertTrue("the label sits above the arrow", f.baseline < arrow.y)
        assertTrue("the arrow is on the maths axis", arrow.y + arrow.height / 2f < 0f)

        val both = box("a \\xrightarrow[g]{f} b", MathStyle.Text)
        val g = both.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "g" }
        val bothArrow = both.draws.filterIsInstance<MathDraw.Rule>().single()
        assertTrue("the optional label sits below the arrow", g.baseline > bothArrow.y + bothArrow.height)
        assertTrue("it takes relation spacing", box("a \\xrightarrow{} b", MathStyle.Text).width > box("ab", MathStyle.Text).width)
    }

    // ---- Column alignment ------------------------------------------------------------------------

    @Test fun alignedColumnsMeetOnTheirAmpersand() {
        val aligned = box("\\begin{aligned} x &= 1 \\\\ yyy &= 2 \\end{aligned}")
        val equals = aligned.draws.filterIsInstance<MathDraw.Glyph>().filter { it.text == "=" }
        assertEquals(2, equals.size)
        assertEquals("both relations share a column", equals[0].x, equals[1].x, 0.001f)
        val x = aligned.draws.filterIsInstance<MathDraw.Glyph>().first { it.text == "x" }
        // The right-aligned column ends where the relation's space begins.
        assertTrue("the relation keeps its spacing", equals[0].x - (x.x + x.size * 0.5f) > 0f)
    }

    @Test fun arrayColumnsFollowTheAlignmentSpec() {
        val grid = box("\\begin{array}{lr} a & bbb \\\\ ccc & d \\end{array}")
        val glyphs = grid.draws.filterIsInstance<MathDraw.Glyph>()
        val a = glyphs.first { it.text == "a" }
        val firstC = glyphs.first { it.text == "c" }
        assertEquals("the left column starts flush", a.x, firstC.x, 0.001f)
        // Every glyph here is one character wide, so the right edges are directly comparable.
        val lastB = glyphs.filter { it.text == "b" }.maxOf { it.x }
        val d = glyphs.first { it.text == "d" }
        assertEquals("the right column ends flush", lastB, d.x, 0.001f)
    }

    private fun bracePoints(box: LatexBox) = box.draws.filterIsInstance<MathDraw.Polyline>().flatMap { it.points }

    private fun braceSpan(box: LatexBox) = box.draws.filterIsInstance<MathDraw.Polyline>().maxOf { it.points.maxOf { p -> p.x } }

    private companion object {
        const val BASE = 16f
    }
}
