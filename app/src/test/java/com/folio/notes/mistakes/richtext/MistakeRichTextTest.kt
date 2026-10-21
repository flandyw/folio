package com.folio.notes.mistakes.richtext

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MistakeRichTextTest {
    @Test fun diceTableKeepsMathAndSurroundingProse() {
        val source = """
            Need ${'$'}B^2-4C\ge0${'$'}.
            | ${'$'}B${'$'} | 1 | 2 | 3 | 4 | 5 | 6 |
            |---|---|---|---|---|---|---|
            | ${'$'}C\le B^2/4${'$'} | none | 1 | 1,2 | 1-4 | 1-6 | 1-6 |
            | count | 0 | 1 | 2 | 4 | 6 | 6 |

            Total ${'$'}=19${'$'}. **Answer A.**
        """.trimIndent()
        val blocks = RichTextParser.parse(source)
        assertEquals(3, blocks.size)
        val table = blocks[1] as RichBlock.Table
        assertEquals(7, table.header.size)
        assertEquals(2, table.rows.size)
        assertEquals(RichInline.Math("C\\le B^2/4"), table.rows[0][0].single())
        val encoded = JSONArray(RichTextDocument.encode(blocks)).getJSONObject(1)
        assertEquals("table", encoded.getString("type"))
        assertEquals(7, encoded.getJSONArray("rows").getJSONArray(0).length())
        assertTrue(RichTextParser.plainText(source, 1000).contains("count | 0 | 1 | 2 | 4 | 6 | 6"))
        assertFalse(RichTextParser.plainText(source, 1000).contains("---"))
    }

    @Test fun alignmentEscapesAndOpaquePipes() {
        val source = """
            Left | Centre | Right
            :--- | :---: | ---:
            a\|b | `x|y` | ${'$'}|x|${'$'}
            short | row
        """.trimIndent()
        val table = RichTextParser.parse(source).single() as RichBlock.Table
        assertEquals(listOf("left", "center", "right"), table.alignments)
        assertEquals(listOf(RichInline.Run("a|b")), table.rows[0][0])
        assertEquals(listOf(RichInline.Run("x|y", code = true)), table.rows[0][1])
        assertEquals(listOf(RichInline.Math("|x|")), table.rows[0][2])
        assertTrue(table.rows[1][2].isEmpty())
        assertTrue(RichTextParser.containsMath("A | B\n--- | ---\nx | y"))
        assertTrue(RichTextParser.previewInlines(source).contains(RichInline.Math("|x|")))
    }

    @Test fun ordinaryPipesAndFencedTablesStayLiteral() {
        assertTrue(RichTextParser.parse("a | b\nc | d").single() is RichBlock.Para)
        assertTrue(RichTextParser.parse("a | b\n--- | nope").single() is RichBlock.Para)
        assertTrue(RichTextParser.parse("```\nA | B\n--- | ---\n```").single() is RichBlock.Code)
        assertTrue(RichTextParser.parse("a | b\n--- | --- | ---").single() is RichBlock.Para)
    }
}
