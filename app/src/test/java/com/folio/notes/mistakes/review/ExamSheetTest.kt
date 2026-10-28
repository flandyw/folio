package com.folio.notes.mistakes.review

import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
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
}
