package com.folio.notes.ink.recognition

import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageLayer
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import org.junit.Assert.*
import org.junit.Test

class ScribbleRecognitionTest {
    private val target = Stroke(Tool.PEN, 0xFF123456.toInt(), 2f, listOf(InkPoint(50f, -3f), InkPoint(50f, 28f)))
    private val scribble = Stroke(Tool.PEN, 0xFF987654.toInt(), 3f,
        (0..5).map { InkPoint(if (it % 2 == 0) 0f else 100f, it * 5f) })

    @Test fun geometryCannotAuthorizeDeletionWithoutTheScribbleLabel() {
        val page = NotePage(id = "page", strokes = listOf(target))
        val candidate = ScribbleRecognition.prepare(mapOf(page.id to page), mapOf(page.id to listOf(scribble)), emptyMap(), ScribbleSensitivity.DEFAULT)!!
        for (label in listOf(null, "writing", "strike", "circle", "RECTANGLE")) assertTrue(candidate.confirmed(label).isEmpty())
        assertTrue(candidate.confirmed("scribble").getValue(page.id).strokes.isEmpty())
        assertEquals(listOf(target), page.strokes)
    }

    @Test fun emptySpaceIncidentalCrossingsAndLockedInkCannotBecomeCandidates() {
        for (page in listOf(NotePage(strokes = emptyList()), NotePage(strokes = listOf(target.copy(points = target.points.map { it.copy(x = 200f) }))),
            NotePage(strokes = listOf(target), layers = listOf(PageLayer.BASE.copy(locked = true))))) {
            assertNull(ScribbleRecognition.prepare(mapOf(page.id to page), mapOf(page.id to listOf(scribble)), emptyMap(), ScribbleSensitivity.DEFAULT))
        }
        val page = NotePage(strokes = listOf(target))
        val strike = scribble.copy(points = listOf(InkPoint(0f, 15f), InkPoint(100f, 15f)))
        assertNull(ScribbleRecognition.prepare(mapOf(page.id to page), mapOf(page.id to listOf(strike)), emptyMap(), ScribbleSensitivity.DEFAULT))
    }

    @Test fun delayedResultsRejectPageSwitchesEditsUndoRedoAndLayerChanges() {
        val original = NotePage(id = "page", strokes = listOf(target, scribble), revision = 7)
        val pending = InkRecognitionSnapshot(original, maximumRevision = 8)
        assertTrue(pending.matches(original))
        assertTrue(pending.matches(original.copy(revision = 8)))
        for (stale in listOf(null, original.copy(id = "other"), original.copy(revision = 6),
            original.copy(revision = 9), original.copy(strokes = listOf(target)),
            original.copy(layers = listOf(PageLayer.BASE.copy(locked = true))))) assertFalse(pending.matches(stale))
        // Grouped document commits accept only the captured acknowledgement, with no revision range.
        val acknowledged = InkRecognitionSnapshot(original.copy(revision = 8))
        assertFalse(acknowledged.matches(original))
        assertTrue(acknowledged.matches(original.copy(revision = 8)))
    }

    @Test fun oneConfirmationCoversPaperAndStickyInkAcrossPages() {
        val note = TextBox(id = "sticky", x = 20f, y = 30f, text = "Keep words", stickyHeight = 120f, stickyInk = listOf(target))
        val a = NotePage(id = "a", strokes = listOf(target))
        val b = NotePage(id = "b", texts = listOf(note))
        val candidate = ScribbleRecognition.prepare(mapOf(a.id to a, b.id to b), mapOf(a.id to listOf(scribble)),
            mapOf(b.id to mapOf(note.id to listOf(scribble))), ScribbleSensitivity.DEFAULT)!!
        val after = candidate.confirmed("scribble")
        assertEquals(setOf("a", "b"), after.keys)
        assertTrue(after.getValue("a").strokes.isEmpty())
        assertEquals(note.copy(stickyInk = emptyList()), after.getValue("b").texts.single())
        assertTrue(candidate.confirmed("writing").isEmpty())
    }
}
