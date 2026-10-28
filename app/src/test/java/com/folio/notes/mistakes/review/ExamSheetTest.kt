package com.folio.notes.mistakes.review

import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
import com.folio.notes.workspace.WorkspaceViewport
import org.junit.Assert.*
import org.junit.Test

class ExamSheetTest {
    @Test fun linesFollowMarksAndStudentSpace() {
        assertEquals(9, ExamSheet.lines(3.0, 0))
        assertEquals(8, ExamSheet.lines(null, 0))
        assertEquals(4, ExamSheet.lines(1.0, 0))
        assertEquals(30, ExamSheet.lines(20.0, 0))
        assertEquals(13, ExamSheet.lines(3.0, 4))
        assertEquals(2, ExamSheet.lines(1.0, -40))
        assertEquals(8, ExamSheet.lines(Double.NaN, 0))
    }

    @Test fun labelsAndPace() {
        assertEquals("(3 marks)", ExamSheet.marksLabel(3.0))
        assertEquals("(1 mark)", ExamSheet.marksLabel(1.0))
        assertEquals("(1.5 marks)", ExamSheet.marksLabel(1.5))
        assertNull(ExamSheet.marksLabel(0.0))
        assertEquals(270L, ExamSheet.paceSeconds(3.0))
        assertEquals("4:30", ExamSheet.clock(270))
        assertEquals(.2f, ExamSheet.imageWidth(.01f))
        assertEquals(1f, ExamSheet.imageWidth(Float.NaN))
    }

    private fun line(y: Float, x: Float = 100f) = Stroke(Tool.HIGHLIGHTER, 0, 10f, listOf(InkPoint(x, y), InkPoint(x + 50f, y)))

    @Test fun resizingAPictureCarriesQuestionInk() {
        // Picture from page y -300 to -100, centred at x 320; it grows by half.
        val above = line(-400f); val on = line(-200f, 220f); val below = line(-50f); val answer = line(30f)
        val out = ExamSheet.reflow(listOf(above, on, below, answer), -300f, -100f, 320f, 1.5f)
        assertEquals(-500f, out[0].points[0].y)            // text above moved up by the 100 units added
        assertEquals(-250f, out[1].points[0].y)            // on the picture: scaled about its bottom
        assertEquals(170f, out[1].points[0].x)
        assertSame(below, out[2]); assertSame(answer, out[3])
        val same = listOf(answer)
        assertSame(same, ExamSheet.reflow(same, -300f, -100f, 320f, 1.5f))
        val untouched = listOf(above)
        assertSame(untouched, ExamSheet.reflow(untouched, -300f, -100f, 320f, 1f))
    }

    private fun night(r: Int, g: Int, b: Int): List<Int> {
        val m = ExamSheet.nightMatrix()
        return (0..2).map { row -> (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]).toInt().coerceIn(0, 255) }
    }

    @Test fun darkPaperInvertsLightnessKeepingHue() {
        night(255, 255, 255).forEach { assertTrue(it in 32..40) }           // paper → dark grey
        night(27, 27, 27).forEach { assertTrue(it in 215..230) }             // pen ink → near white
        val red = night(198, 40, 40); assertTrue(red[0] > red[1] && red[0] > red[2])   // red stays red
        val blue = night(31, 78, 180); assertTrue(blue[2] > blue[0] && blue[2] > blue[1])
    }

    @Test fun headerMarksAreNotRepeatedInTheQuestion() {
        val text = "Find the rule and domain for \\(f^{-1}\\). (2 marks)"
        assertEquals("Find the rule and domain for \\(f^{-1}\\).", ExamSheet.questionBody(text, 2.0))
        assertEquals(text, ExamSheet.questionBody(text, null))
        assertEquals("Explain (in words) why.", ExamSheet.questionBody("Explain (in words) why. ( 1 Mark )", 1.0))
        assertNull(ExamSheet.questionBody("(3 marks)", 3.0))
    }

    @Test fun sheetIsKeptOnScreen() {
        // A 1000 x 1600 view, zoom 1: the 640-unit sheet is centred; question 200, sheet 1200 units.
        fun clamp(x: Float, y: Float, z: Float = 1f) = ExamSheet.clamp(WorkspaceViewport(canvasX = x, canvasY = y, canvasZoom = z), 1000f, 1600f, 100f, 16f, 200f, 1200f)
        val fit = WorkspaceViewport(canvasX = 180f, canvasY = 300f, canvasZoom = 1f)
        assertEquals(fit, clamp(180f, 300f))                         // the fitted sheet is left alone
        assertEquals(180f, clamp(-500f, 300f).canvasX)               // narrower than the view: centred
        assertEquals(300f, clamp(180f, 900f).canvasY)                // pulled below the bar: back to the top
        assertEquals(800f - 1200f + 200f, clamp(180f, -5000f).canvasY)        // end no higher than mid-view
        val zoomed = clamp(400f, 300f, 3f)                           // 1920 px wide: edges within the gutter
        assertEquals(16f, zoomed.canvasX)
        assertEquals(1000f - 1920f - 16f, clamp(-5000f, 300f, 3f).canvasX)
    }
}
