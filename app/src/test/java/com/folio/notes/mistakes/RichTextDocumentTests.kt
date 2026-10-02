package com.folio.notes.mistakes

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RichTextDocumentTests {
    @Test fun mathDetectionReusesParsedBlocksWithoutChangingDelimiterAndCodeRules() {
        val samples = mapOf(
            "Ordinary prose" to false,
            "`\\(literal\\)`" to false,
            "```\n\$literal\$\n```" to false,
            "\\\$literal" to false,
            "\\(unclosed" to false,
            "# Heading \\(x\\)" to true,
            "> Quoted \\(x\\)" to true,
            "- Bullet \\(x\\)" to true,
            "1. Numbered \\(x\\)" to true,
            "\$\$\nx^2\n\$\$" to true,
            "Prose \\(x\\)" to true
        )
        samples.forEach { (source, expected) ->
            assertEquals(source, expected, RichTextParser.containsMath(source))
            assertEquals(source, expected, RichTextParser.containsMath(RichTextParser.parse(source)))
        }
        assertFalse(RichTextParser.containsMath(emptyList()))
    }

    @Test fun questionKeepsDisplayMathAndFinalInstructionBeyondFormerPreviewBudget() {
        val source = "Let \\(f : \\mathbb{R} \\to \\mathbb{R}\\) be defined by\n\n" +
            "\$\$f(x)=x^2e^{kx},\$\$\n\nwhere \\(k\\) is a positive real constant.\n\n" +
            "\$\$f'(x)=xe^{kx}(kx+2).\$\$\n\n" +
            "Context about the functions. ".repeat(30) + "Find the value of k. (2 marks)"
        val document = JSONArray(RichTextDocument.encode(RichTextParser.parse(source)))
        assertEquals(5, document.length())
        assertEquals("math", document.getJSONObject(1).getString("type"))
        assertEquals("f(x)=x^2e^{kx},", document.getJSONObject(1).getString("latex"))
        assertEquals("f'(x)=xe^{kx}(kx+2).", document.getJSONObject(3).getString("latex"))
        assertTrue(document.getJSONObject(4).getJSONArray("inlines").getJSONObject(0)
            .getString("text").endsWith("Find the value of k. (2 marks)"))
    }

    @Test fun sourceHtmlAndCodeStayLiteralAndMathKeepsItsDisplayMode() {
        val source = "<script>alert('x')</script> & **bold** `\\frac{1}{2}` \\(k\\) \\[x^2\\]"
        val inlines = JSONArray(RichTextDocument.encode(RichTextParser.parse(source)))
            .getJSONObject(0).getJSONArray("inlines")
        assertEquals("<script>alert('x')</script> & ", inlines.getJSONObject(0).getString("text"))
        assertTrue(inlines.getJSONObject(1).getBoolean("bold"))
        assertTrue(inlines.getJSONObject(3).getBoolean("code"))
        assertEquals("\\frac{1}{2}", inlines.getJSONObject(3).getString("text"))
        val math = (0 until inlines.length()).map { inlines.getJSONObject(it) }.filter { it.getString("type") == "math" }
        assertEquals(listOf("k", "x^2"), math.map { it.getString("latex") })
        assertEquals(listOf(false, true), math.map { it.getBoolean("display") })
    }
}
