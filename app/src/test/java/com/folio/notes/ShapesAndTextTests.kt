package com.folio.notes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ShapeRecognitionTests {
    private fun stroke(points: List<InkPoint>) = Stroke(Tool.PEN, 0xFF112233.toInt(), 2.4f, points)

    /** Samples along each edge of the closed polygon, so the drawing includes its corners. */
    private fun polygon(vertices: List<InkPoint>, step: Float = 7f): Stroke {
        val points = mutableListOf<InkPoint>()
        for (i in vertices.indices) {
            val a = vertices[i]; val b = vertices[(i + 1) % vertices.size]
            val length = hypot(b.x - a.x, b.y - a.y)
            val steps = (length / step).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                points += InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        points += vertices.first()
        return stroke(points)
    }

    @Test fun aRoughStraightStrokeBecomesACleanLine() {
        val drawn = stroke((0..40).map { InkPoint(it * 8f, 100f + if (it % 3 == 0) 2f else -1.5f) })
        val tidied = InkGeometry.tidy(drawn)
        assertNotNull(tidied)
        assertEquals(1, tidied!!.size)
        assertEquals(Tool.LINE, tidied.first().tool)
        assertEquals(0f, tidied.first().points.first().x, .001f)
        assertEquals(320f, tidied.first().points.last().x, .001f)
        assertEquals(drawn.color, tidied.first().color)
        assertEquals(drawn.width, tidied.first().width, .001f)
    }

    @Test fun aRoughSquareBecomesARectangle() {
        val box = polygon(listOf(InkPoint(100f, 100f), InkPoint(300f, 104f), InkPoint(297f, 240f), InkPoint(103f, 238f)))
        val tidied = InkGeometry.tidy(box)
        assertNotNull(tidied)
        assertEquals(Tool.RECTANGLE, tidied!!.single().tool)
        // The clean shape spans the drawing's own bounds as two opposite corners.
        val points = tidied.single().points
        assertEquals(100f, points.first().x, 4f)
        assertEquals(100f, points.first().y, 4f)
        assertEquals(300f, points.last().x, 4f)
        assertEquals(240f, points.last().y, 4f)
    }

    @Test fun aRoughCircleBecomesAnEllipseRatherThanABox() {
        val circle = stroke((0..48).map { i ->
            val t = i * 2.0 * Math.PI / 48.0
            InkPoint(200f + 90f * cos(t).toFloat(), 200f + 90f * sin(t).toFloat())
        })
        val tidied = InkGeometry.tidy(circle)
        assertNotNull(tidied)
        assertEquals(Tool.ELLIPSE, tidied!!.single().tool)
    }

    @Test fun aRoughTriangleBecomesThreeEdges() {
        val triangle = polygon(listOf(InkPoint(100f, 100f), InkPoint(300f, 100f), InkPoint(200f, 260f)))
        val tidied = InkGeometry.tidy(triangle)
        assertNotNull(tidied)
        assertEquals(3, tidied!!.size)
        assertTrue(tidied.all { it.tool == Tool.LINE })
    }

    @Test fun drawingsThatAreNotShapesAreLeftAlone() {
        // A tick: not straight, and its ends never meet.
        val tick = stroke(listOf(InkPoint(0f, 0f), InkPoint(40f, 120f), InkPoint(160f, 20f)))
        assertNull(InkGeometry.tidy(tick))
        // A tiny scratch is below the size worth tidying.
        assertNull(InkGeometry.tidy(stroke(listOf(InkPoint(0f, 0f), InkPoint(6f, 4f), InkPoint(10f, 0f), InkPoint(6f, 2f), InkPoint(0f, 0f)))))
        // A highlighter sweep is ink, not a shape, however straight it is.
        val highlight = Stroke(Tool.HIGHLIGHTER, 0, 18f, (0..20).map { InkPoint(it * 10f, 40f) })
        assertNull(InkGeometry.tidy(highlight))
    }
}

class SelectionEditTests {
    private val rectangle = Stroke(Tool.RECTANGLE, 0, 2f, listOf(InkPoint(0f, 0f), InkPoint(100f, 50f)))
    private val dot = Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(20f, 20f)))

    @Test fun boundsAndCentreComeFromEverySampleAndShapeCorner() {
        val bounds = InkGeometry.bounds(listOf(rectangle, dot))!!
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 50f), bounds, .001f)
        assertEquals(50f, InkGeometry.center(listOf(rectangle))!!.x, .001f)
        assertEquals(25f, InkGeometry.center(listOf(rectangle))!!.y, .001f)
        assertNull(InkGeometry.bounds(emptyList()))
    }

    @Test fun rotatingByARightAngleMapsTheCornerRangeOntoItself() {
        val turned = InkGeometry.rotate(listOf(rectangle), InkPoint(50f, 25f), 90f).single()
        assertArrayEquals(floatArrayOf(25f, -25f, 75f, 75f), InkGeometry.bounds(listOf(turned))!!, .01f)
        // Four right angles return the rectangle exactly where it started.
        val round = InkGeometry.rotate(listOf(rectangle), InkPoint(50f, 25f), 360f).single()
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 50f), InkGeometry.bounds(listOf(round))!!, .01f)
    }

    @Test fun resizingScalesTheInkAndItsDrawnWidthAboutTheCentre() {
        val doubled = InkGeometry.scale(listOf(rectangle), InkPoint(50f, 25f), 2f).single()
        assertArrayEquals(floatArrayOf(-50f, -25f, 150f, 75f), InkGeometry.bounds(listOf(doubled))!!, .01f)
        assertEquals(4f, doubled.width, .001f)
    }

    @Test fun restylingRecoloursKeepsGeometryAndLeavesOtherPropertiesAlone() {
        val restyled = InkGeometry.restyle(listOf(rectangle), color = 0xFFC0392B.toInt()).single()
        assertEquals(0xFFC0392B.toInt(), restyled.color)
        assertEquals(rectangle.width, restyled.width, .001f)
        assertEquals(rectangle.opacity, restyled.opacity, .001f)
        assertEquals(rectangle.points, restyled.points)
        assertEquals(rectangle.tool, restyled.tool)
    }

    @Test fun restylingThicknessIsRelativeSoMixedWidthsStayInProportion() {
        val thin = Stroke(Tool.PEN, 0, 1f, listOf(InkPoint(0f, 0f)))
        val thick = Stroke(Tool.PEN, 0, 4f, listOf(InkPoint(9f, 9f)))
        val scaled = InkGeometry.restyle(listOf(thin, thick), widthScale = 2f)
        assertEquals(2f, scaled[0].width, .001f)
        assertEquals(8f, scaled[1].width, .001f)
        // A scale of 1 leaves every stroke's own thickness untouched.
        assertEquals(listOf(thin, thick), InkGeometry.restyle(listOf(thin, thick), widthScale = 1f))
        // Thickness still clamps instead of vanishing or exploding.
        assertEquals(140f, InkGeometry.restyle(listOf(thick), widthScale = 100f).single().width, .001f)
    }

    @Test fun restylingOpacityAppliesToEveryStrokeAndIsClamped() {
        val pen = Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(0f, 0f)))
        val highlight = Stroke(Tool.HIGHLIGHTER, 0, 18f, listOf(InkPoint(1f, 1f)))
        val faded = InkGeometry.restyle(listOf(pen, highlight), opacity = .4f)
        assertTrue(faded.all { it.opacity == .4f })
        assertTrue(InkGeometry.restyle(listOf(pen), opacity = 5f).all { it.opacity == 1f })
    }

    @Test fun aSelectionStyleComesFromItsFirstStroke() {
        val first = Stroke(Tool.PEN, 0xFF112233.toInt(), 2.4f, listOf(InkPoint(0f, 0f)), .8f)
        val second = Stroke(Tool.PEN, 0xFF445566.toInt(), 6f, listOf(InkPoint(1f, 1f)), 1f)
        val style = InkGeometry.styleOf(listOf(first, second))!!
        assertEquals(first.color, style.color)
        assertEquals(first.width, style.width, .001f)
        assertEquals(first.opacity, style.opacity, .001f)
        assertNull(InkGeometry.styleOf(emptyList()))
    }
}

class TextBoxTests {
    @Test fun typedTextSurvivesSaveAndReload() {
        val note = Notebook(title = "Labelled", pages = listOf(NotePage(
            strokes = listOf(Stroke(Tool.PEN, -1, 2f, listOf(InkPoint(5f, 5f)))),
            texts = listOf(TextBox(x = 40f, y = 60f, width = 300f, text = "Ohm's law • V = IR", size = 32f, color = -16777216, bold = true))
        )))
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
    }

    @Test fun aNotebookSavedBeforeTextExistedStillOpens() {
        val note = Notebook(title = "Legacy", pages = listOf(NotePage(
            strokes = listOf(Stroke(Tool.PEN, -1, 2f, listOf(InkPoint(5f, 5f)))))))
        val json = JSONObject(NoteCodec.encode(note))
        json.getJSONArray("pages").getJSONObject(0).remove("texts")
        val restored = NoteCodec.decode(json.toString())
        assertEquals(note.pages, restored.pages)
        assertTrue(restored.pages.first().texts.isEmpty())
    }

    @Test fun aMovedTextBoxKeepsItsWordingAndSize() {
        val box = TextBox(x = 10f, y = 20f, text = "Note", size = 40f, bold = true, italic = true)
        assertEquals(TextBox(box.id, 25f, 12f, box.width, "Note", 40f, box.color, true, true), box.moved(15f, -8f))
    }
}

class NotebookArchiveTests {
    private val note = Notebook(title = "Archive • 你好", pages = listOf(NotePage(
        strokes = listOf(Stroke(Tool.PEN, -1, 2f, listOf(InkPoint(1f, 2f)))),
        texts = listOf(TextBox(x = 3f, y = 4f, text = "hello"))
    )))

    @Test fun anArchiveRoundTripsTheNotebookAndItsPdf() {
        val bytes = ByteArrayOutputStream().also { NotebookArchive.write(note, byteArrayOf(1, 2, 3, 4), it) }.toByteArray()
        val restored = NotebookArchive.read(ByteArrayInputStream(bytes))
        assertEquals(note, restored.note)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), restored.pdf!!)
    }

    @Test fun anArchiveWithoutAPdfComesBackWithoutOne() {
        val bytes = ByteArrayOutputStream().also { NotebookArchive.write(note, null, it) }.toByteArray()
        val restored = NotebookArchive.read(ByteArrayInputStream(bytes))
        assertEquals(note, restored.note)
        assertNull(restored.pdf)
    }

    @Test fun unknownEntriesAreIgnoredAndAMissingNotebookIsAnError() {
        val extra = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("notes.txt")); zip.write("ignore me".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry(NotebookArchive.ENTRY_NOTE))
                zip.write(NoteCodec.encode(note).toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        assertEquals(note, NotebookArchive.read(ByteArrayInputStream(extra)).note)

        assertThrows(Exception::class.java) { NotebookArchive.read(ByteArrayInputStream("not a zip".toByteArray())) }
        val empty = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { } }.toByteArray()
        assertThrows(Exception::class.java) { NotebookArchive.read(ByteArrayInputStream(empty)) }
    }
}
