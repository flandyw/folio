package com.folio.notes.mistakes

import org.junit.Assert.*
import org.junit.Test

class RichTextParserTests {
    @Test fun inlineMathSplit() {
        val inlines = RichTextParser.parseInlines("Solve \$x^2 + 1\$ for x")
        assertEquals(3, inlines.size)
        val math = inlines[1] as RichInline.Math
        assertEquals("x^2 + 1", math.latex)
        assertFalse(math.display)
    }

    @Test fun displayMathDelimiters() {
        val dbl = RichTextParser.parseInlines("See \$\$\\frac{1}{2}\$\$ done")
        assertTrue(dbl.any { it is RichInline.Math && it.display })
        val paren = RichTextParser.parseInlines("See \\(x\\) and \\[y\\]")
        assertEquals(4, paren.size)
        assertTrue((paren[1] as RichInline.Math).latex == "x")
        assertTrue((paren[3] as RichInline.Math).display)
    }

    @Test fun unclosedDollarStaysText() {
        val single = RichTextParser.parseInlines("price is \$5 and x")
        assertTrue(single.none { it is RichInline.Math })
        val currencyPair = RichTextParser.parseInlines("price is \$5 and \$x")
        // The second $ is preceded by a space so it cannot close the first: no math.
        assertTrue(currencyPair.none { it is RichInline.Math })
    }

    @Test fun markdownSpans() {
        val inlines = RichTextParser.parseInlines("**bold** and *it* and `code` and ~~gone~~")
        val runs = inlines.filterIsInstance<RichInline.Run>()
        assertTrue(runs.any { it.bold && it.text == "bold" })
        assertTrue(runs.any { it.italic && it.text == "it" })
        assertTrue(runs.any { it.code && it.text == "code" })
        assertTrue(runs.any { it.strike && it.text == "gone" })
    }

    @Test fun blocks() {
        val blocks = RichTextParser.parse("## Title\n\n- a\n- b\n\n\$\$x\$\$")
        assertTrue(blocks[0] is RichBlock.Heading)
        assertTrue(blocks[1] is RichBlock.Bullets)
        assertTrue(blocks[2] is RichBlock.DisplayMath)
    }

    @Test fun fracParses() {
        val nodes = MathParser.parse("\\frac{a+1}{b-2}")
        val frac = nodes.single() as MathNode.Frac
        assertEquals("a+1", MathParser.toUnicode(frac.num))
        assertEquals("b-2", MathParser.toUnicode(frac.den))
        assertTrue(MathParser.isComplex(nodes))
    }

    @Test fun sqrtAndSupSub() {
        val sqrt = MathParser.parse("\\sqrt{x+1}") .single() as MathNode.Sqrt
        assertEquals("x+1", MathParser.toUnicode(sqrt.body))
        val sup = MathParser.parse("x^{2}") .single() as MathNode.SupSub
        assertEquals("2", MathParser.toUnicode(sup.sup!!))
        val both = MathParser.parse("x_a^b") .single() as MathNode.SupSub
        assertNotNull(both.sup); assertNotNull(both.sub)
    }

    @Test fun greekAndFunctions() {
        assertEquals("α", MathParser.toUnicode(MathParser.parse("\\alpha")))
        assertEquals("×", MathParser.toUnicode(MathParser.parse("\\times")))
        assertEquals("≤", MathParser.toUnicode(MathParser.parse("\\le")))
        val func = MathParser.parse("\\sin x").first() as MathNode.Func
        assertEquals("sin", func.name)
        assertFalse(MathParser.isComplex(MathParser.parse("\\alpha + \\beta")))
    }

    @Test fun unknownCommandNeverDropsContent() {
        assertEquals("weirdcommand", MathParser.toUnicode(MathParser.parse("\\weirdcommand")))
        assertEquals("text", MathParser.toUnicode(MathParser.parse("\\text{text}")))
    }

    @Test fun plainTextFallback() {
        val plain = RichTextParser.plainText("Solve \$\\frac{1}{2}\$ **bold**", 80)
        assertTrue(plain.contains("(1)/(2)"))
        assertTrue(plain.contains("bold"))
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("$"))
    }

    @Test fun malformedLatexDoesNotThrow() {
        assertNotNull(MathParser.parse("\\frac{a}{"))
        assertNotNull(RichTextParser.parse("## \n\$\$unclosed\n- "))
        assertNotNull(RichTextParser.plainText("\$\\sqrt{\$"))
    }

    @Test fun snakeCaseNotItalic() {
        val inlines = RichTextParser.parseInlines("practice_page_id stays plain")
        val joined = inlines.filterIsInstance<RichInline.Run>().joinToString("") { it.text }
        assertTrue(joined.contains("practice_page_id"))
        assertTrue(inlines.none { it is RichInline.Run && it.italic })
    }
}
