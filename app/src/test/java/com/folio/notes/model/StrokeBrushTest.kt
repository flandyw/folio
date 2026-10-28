package com.folio.notes.model

import com.folio.notes.editor.toolbar.WidthPresets
import com.folio.notes.ink.InkGeometry
import com.folio.notes.storage.BinReader
import com.folio.notes.storage.BinWriter
import com.folio.notes.storage.InkBinary
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class StrokeBrushTest {
    private val line = listOf(InkPoint(0f, 0f, .8f), InkPoint(20f, 4f, 1.2f))

    @Test fun brushesRideTheStoredStyleName() {
        StrokeStyle.brushes(Tool.PEN).forEach { brush ->
            val stroke = Stroke(Tool.PEN, -1, 3f, line, style = brush)
            val bytes = ByteArrayOutputStream().also { InkBinary.writeStroke(BinWriter(it), stroke) }.toByteArray()
            assertEquals(brush, InkBinary.readStroke(BinReader(bytes)).style)
        }
        // An older build meets a name it does not know and draws its plain pen.
        assertEquals(StrokeStyle.SOLID, StrokeStyle.safeValueOf("SOME_FUTURE_BRUSH"))
    }

    @Test fun toolsOnlyAcceptTheirOwnBrushes() {
        assertTrue(StrokeStyle.PENCIL.accepts(Tool.PEN))
        assertFalse(StrokeStyle.PENCIL.accepts(Tool.HIGHLIGHTER))
        assertTrue(StrokeStyle.CALLIGRAPHY.accepts(Tool.HIGHLIGHTER))
        assertFalse(StrokeStyle.DASHED.accepts(Tool.PEN))
        assertTrue(StrokeStyle.FOUNTAIN.accepts(Tool.ELLIPSE))
        assertTrue(StrokeStyle.brushes(Tool.ERASER).isEmpty())
        // Cycling line patterns never lands on a brush.
        var style = StrokeStyle.SOLID
        repeat(StrokeStyle.PATTERNS.size * 2) { style = style.next(); assertFalse(style.isBrush) }
        assertEquals("Chisel", StrokeStyle.CALLIGRAPHY.brushLabel(Tool.HIGHLIGHTER))
        assertEquals("Ballpoint", StrokeStyle.SOLID.brushLabel(Tool.PEN))
    }

    @Test fun restyleKeepsPatternsOnShapesAndBrushesOnInk() {
        val pen = Stroke(Tool.PEN, -1, 3f, line, style = StrokeStyle.PENCIL)
        val highlight = Stroke(Tool.HIGHLIGHTER, -1, 12f, line)
        val box = Stroke(Tool.RECTANGLE, -1, 2f, line, style = StrokeStyle.DOTTED)
        val (p, h, b) = InkGeometry.restyle(listOf(pen, highlight, box), style = StrokeStyle.DASHED, brush = StrokeStyle.MARKER)
        assertEquals(StrokeStyle.MARKER, p.style)
        assertEquals(StrokeStyle.SOLID, h.style)
        assertEquals(StrokeStyle.DASHED, b.style)
        // A solid line pattern must not turn the pencil back into a ballpoint.
        assertEquals(StrokeStyle.PENCIL, InkGeometry.restyle(listOf(pen), style = StrokeStyle.SOLID).single().style)
        assertEquals(StrokeStyle.CALLIGRAPHY, InkGeometry.restyle(listOf(highlight), brush = StrokeStyle.CALLIGRAPHY).single().style)
    }

    @Test fun eachPenBrushKeepsItsOwnWidths() {
        assertEquals(WidthPresets.PEN, WidthPresets.group(Tool.PEN))
        assertEquals("PEN.MARKER", WidthPresets.group(Tool.PEN, StrokeStyle.MARKER))
        assertEquals(WidthPresets.HIGHLIGHTER, WidthPresets.group(Tool.HIGHLIGHTER, StrokeStyle.CALLIGRAPHY))
        assertTrue(WidthPresets.groups.containsAll(StrokeStyle.brushes(Tool.PEN).map { WidthPresets.group(Tool.PEN, it) }))
        WidthPresets.groups.forEach { group ->
            val range = WidthPresets.range(group)
            assertTrue(group, WidthPresets.defaults(group).all { it in range })
        }
    }
}
