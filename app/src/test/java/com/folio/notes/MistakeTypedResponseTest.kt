package com.folio.notes

import com.folio.notes.mistakes.MistakeTypedResponse
import com.folio.notes.mistakes.isPracticePageEmpty
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class MistakeTypedResponseTest {
    @Test fun mixedWorkingSurvivesSnapshotAndResponseUndo() {
        val ink = Stroke(Tool.PEN, 0xff123456.toInt(), 2f, listOf(InkPoint(50f, 10f), InkPoint(300f, 90f)))
        val sticky = TextBox(id = "sticky", x = 40f, y = 30f, text = "Keep this", stickyHeight = 160f)
        val before = NotePage(id = "practice", infinite = true, strokes = listOf(ink), texts = listOf(sticky))
        val response = MistakeTypedResponse.box(before, "review").copy(text = "First line\n\nReasoning: $ x^2 $ = 4.\nα → β")
        assertTrue(response.x > sticky.x + sticky.width)
        assertTrue(response.x > 300f)
        val changed = DocumentReducer.page(before, before.copy(texts = before.texts + response))
        val bytes = ByteArrayOutputStream().also {
            PageSnapshotBinary.write(it, changed.page, 1, PageJournal.History(listOf(changed.inverse!!), emptyList()))
        }.toByteArray()
        val saved = PageSnapshotBinary.read(bytes)
        val reopened = before.copy(strokes = saved.strokes, texts = saved.texts, images = saved.images, layers = saved.layers)
        assertEquals(response, MistakeTypedResponse.find(reopened, "review"))
        assertNull(MistakeTypedResponse.find(reopened, "other-review"))
        assertEquals(before.content(), PageJournal.apply(reopened.content(), saved.history.undo.single()))
        // Canvas edits to placement, styling and wording remain authoritative when typing resumes.
        val moved = response.copy(x = -100f, y = 200f, width = 480f, bold = true, text = "Edited on canvas")
        assertEquals(moved, MistakeTypedResponse.box(reopened.copy(texts = listOf(sticky, moved)), "review"))
    }

    @Test fun typedOnlyWorkingIsKeptAndNewResponsesUseAnEditableLayer() {
        val blank = NotePage(infinite = true, layers = listOf(PageLayer.BASE.copy(locked = true), PageLayer(1, "Answers")))
        val response = MistakeTypedResponse.box(blank, "review").copy(text = "An answer without any ink")
        assertEquals(1, response.layer)
        assertTrue(isPracticePageEmpty(blank))
        assertFalse(isPracticePageEmpty(blank.copy(texts = listOf(response))))
        assertTrue(isPracticePageEmpty(blank.copy(texts = listOf(response.copy(text = " \n ")))))
        assertEquals("write", AppPrefs.mistakeResponseMode("unknown"))
        assertEquals("write", AppPrefs.mistakeResponseMode(null))
        assertEquals("type", AppPrefs.mistakeResponseMode("type"))
    }
}
