package com.folio.notes

import com.folio.notes.mistakes.MistakeTypedResponse
import com.folio.notes.mistakes.TypedEdit
import com.folio.notes.mistakes.TypingBar
import com.folio.notes.mistakes.isPracticePageEmpty
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class MistakeTypedResponseTest {
    @Test fun practicePageReaderLoadsAndUpdatesAnswersWhileShellStaysMetadataOnly() {
        val summary = NotePage(id = "practice", infinite = true, loaded = false)
        val store = DocumentStateStore(FolioState(notes = listOf(
            Notebook(id = "notebook", title = "Practice", pages = listOf(summary)),
        )))
        val reader = store.observePage("notebook", summary.id)
        assertFalse(reader.value!!.page.loaded)

        val blank = summary.copy(loaded = true)
        val answer = MistakeTypedResponse.box(blank, "review").copy(text = "Saved answer")
        val restored = blank.copy(texts = listOf(answer))
        fun publish(page: NotePage) = store.update { state ->
            state.copy(notes = state.notes.replaceNotebook(state.notes.notebook("notebook")!!.withPage(page)))
        }
        publish(restored)
        assertTrue(reader.value!!.page.loaded)
        assertEquals(answer, MistakeTypedResponse.find(reader.value!!.page, "review"))
        assertNull(MistakeTypedResponse.find(reader.value!!.page, "next-review"))

        val edited = answer.copy(text = "Edited answer")
        publish(restored.copy(revision = 1, texts = listOf(edited)))
        assertEquals(edited, MistakeTypedResponse.find(reader.value!!.page, "review"))
        val shellPage = store.shellState.value.notes.notebook("notebook")!!.pages.page(summary.id)!!
        assertFalse(shellPage.loaded)
        assertTrue(shellPage.texts.isEmpty())
    }

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

    @Test fun typingKeysInsertMathsWhereTheCaretIs() {
        val sqrt = TypingBar.snippets.first { it.description == "Square root" }
        val pi = TypingBar.snippets.first { it.description == "Pi" }
        val power = TypingBar.snippets.first { it.description == "Power" }
        // Outside maths a structure is wrapped in $…$ with the caret inside it.
        assertEquals(TypedEdit("Area \$\\sqrt{}\$", 12), TypingBar.insert(TypedEdit("Area ", 5), sqrt))
        // Symbols stay plain text outside maths and become LaTeX inside it.
        assertEquals(TypedEdit("2π", 2), TypingBar.insert(TypedEdit("2", 1), pi))
        assertEquals(TypedEdit("\$2\\pi \$", 6), TypingBar.insert(TypedEdit("\$2\$", 2), pi))
        // x² takes the word before the caret as its base.
        assertEquals(TypedEdit("so \$x^{}\$", 7), TypingBar.insert(TypedEdit("so x", 4), power))
        // A selection is wrapped, and the Maths key steps out of maths from inside.
        assertEquals(TypedEdit("\$a+b\$", 4), TypingBar.insert(TypedEdit("a+b", 0, 3), TypingBar.MATHS))
        assertEquals(TypedEdit("\$a\$ then", 3), TypingBar.insert(TypedEdit("\$a\$ then", 2), TypingBar.MATHS))
        assertTrue(TypingBar.insideMath("\$\$x", 3))
        assertFalse(TypingBar.insideMath("costs \\\$5", 9))
    }

    @Test fun listsContinueAndEndOnEnter() {
        val before = TypedEdit("- one", 5)
        assertEquals(TypedEdit("- one\n- ", 8), TypingBar.continueList(before, TypedEdit("- one\n", 6)))
        assertEquals(TypedEdit("1. a\n2. ", 8), TypingBar.continueList(TypedEdit("1. a", 4), TypedEdit("1. a\n", 5)))
        // Enter on an empty item ends the list instead of adding another.
        assertEquals(TypedEdit("- one\n", 6), TypingBar.continueList(TypedEdit("- one\n- ", 8), TypedEdit("- one\n- \n", 9)))
        assertEquals(TypedEdit("plain\n", 6), TypingBar.continueList(TypedEdit("plain", 5), TypedEdit("plain\n", 6)))
        assertEquals(TypedEdit("- x", 3), TypingBar.toggleList(TypedEdit("x", 1)))
        assertEquals(TypedEdit("x", 1), TypingBar.toggleList(TypedEdit("- x", 3)))
        assertEquals(3, MistakeTypedResponse.words(" one two\nthree "))
    }
}
