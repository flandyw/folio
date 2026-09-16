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

    @Test fun plainTextFallback() {
        val plain = RichTextParser.plainText("Solve \$\\frac{1}{2}\$ **bold**", 80)
        assertTrue(plain.contains("\\frac{1}{2}"))
        assertTrue(plain.contains("bold"))
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("$"))
    }

    @Test fun plainTextPreservesOpaqueMath() {
        val plain = RichTextParser.plainText("So \$\\Pr_{x}(A)\$ holds", 120)
        assertTrue(plain.contains("Pr"))
        assertTrue(plain.contains("x"))
        assertTrue(plain.contains("A"))
    }

    @Test fun malformedLatexDoesNotThrow() {
        assertNotNull(RichTextParser.parse("## \n\$\$unclosed\n- "))
        assertNotNull(RichTextParser.plainText("\$\\sqrt{\$"))
        assertNotNull(RichTextParser.plainText("\$\\begin{pmatrix} a\$"))
        assertNotNull(RichTextParser.plainText("\$\\left( \\frac{1}{\$"))
    }

    @Test fun snakeCaseNotItalic() {
        val inlines = RichTextParser.parseInlines("practice_page_id stays plain")
        val joined = inlines.filterIsInstance<RichInline.Run>().joinToString("") { it.text }
        assertTrue(joined.contains("practice_page_id"))
        assertTrue(inlines.none { it is RichInline.Run && it.italic })
    }

    @Test fun containsMathDetectsLatex() {
        assertTrue(RichTextParser.containsMath("Solve \$x\$"))
        assertTrue(RichTextParser.containsMath("Value: \\[\\Pr(A)\\]"))
        assertFalse(RichTextParser.containsMath("Plain prose, no maths here."))
        assertFalse(RichTextParser.containsMath("Price is \$5."))
    }

    @Test fun requestedDelimitersPreserveExactSource() {
        val cases = listOf(
            "$" + "x^2" + "$" to "x^2",
            "The value is $" + "x^2+1" + "$." to "x^2+1",
            "\\(\\frac{1}{2}\\)" to "\\frac{1}{2}",
            "\\[\\sum_{i=1}^n i\\]" to "\\sum_{i=1}^n i",
            "\\(  x \\)" to "  x "
        )
        cases.forEach { (source, expected) ->
            assertEquals(expected, RichTextParser.parseInlines(source).filterIsInstance<RichInline.Math>().single().latex)
        }
        val math = "\n\\Pr(X \\le 3)\n"
        assertEquals(listOf(RichBlock.DisplayMath(math)), RichTextParser.parse("$$" + math + "$$"))
        assertEquals(listOf(RichBlock.DisplayMath(math)), RichTextParser.parse("\\[" + math + "\\]"))
    }

    @Test fun escapedDollarsAndClosingDelimiters() {
        val source = "Pay \\$5 or \\$10, then $" + "x+\\$" + "$" + "."
        val parts = RichTextParser.parseInlines(source)
        assertEquals("Pay $" + "5 or $" + "10, then ", (parts.first() as RichInline.Run).text)
        assertEquals("x+\\$", parts.filterIsInstance<RichInline.Math>().single().latex)
        assertFalse(RichTextParser.containsMath("\\$\\$" + "x\\$\\$"))
        // Two backslashes escape each other, so the following dollar is a real delimiter.
        assertTrue(RichTextParser.containsMath("\\\\$" + "x$"))
    }

    @Test fun multipleFragmentsAndEnclosingEmphasis() {
        val parts = RichTextParser.parseInlines("**Use $" + "x_i$ and \\(y^2\\) now**.")
        assertEquals(listOf("x_i", "y^2"), parts.filterIsInstance<RichInline.Math>().map { it.latex })
        assertTrue(parts.filterIsInstance<RichInline.Run>().filter { it.text != "." }.all { it.bold })
        val adjacent = RichTextParser.parseInlines("**value**: $" + "x$ and *next*: $" + "y$")
        assertEquals(2, adjacent.filterIsInstance<RichInline.Math>().size)
    }

    @Test fun codeIsNeverMath() {
        val code = "$" + "x$ and \\(y\\)"
        assertEquals(listOf(RichInline.Run(code, code = true)), RichTextParser.parseInlines("`$code`"))
        assertEquals(listOf(RichBlock.Code(code)), RichTextParser.parse("```\n$code\n```"))
    }

    @Test fun malformedDelimitersStayReadable() {
        listOf("$$\nx_1", "\\[\nx_1", "\\(x_1", "$" + "5", "$$$$", "\\[x").forEach { source ->
            val plain = RichTextParser.plainText(source)
            assertTrue("Lost source: $source -> $plain", plain.contains(source.replace('\n', ' ')))
        }
        assertEquals("\\frac{", RichTextParser.parseInlines("$" + "\\frac{$").filterIsInstance<RichInline.Math>().single().latex)
    }
}
