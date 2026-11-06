package com.folio.notes.storage

import com.folio.notes.model.NotePage
import com.folio.notes.model.TextBox
import org.junit.Assert.*
import org.junit.Test

class NotebookTextSearchTest {
    private fun box(text: String, id: String = "box") = TextBox(id = id, x = 0f, y = 0f, text = text)

    @Test fun snippetOffsetsReferToOriginalTextWhenLowercaseChangesItsLength() {
        val text = "İ".repeat(100) + " target"
        val hit = NotebookTextSearch.search(listOf(NotePage(texts = listOf(box(text)))), "target").single()
        assertEquals(1, hit.matchCount)
        assertTrue(hit.snippet.endsWith(" target"))
        assertTrue(hit.snippet.startsWith("…"))
    }

    @Test fun caseInsensitiveSearchHandlesDottedIWithoutExpandingOffsets() {
        val page = NotePage(texts = listOf(box("İstanbul and İSTANBUL")))
        assertEquals(2, NotebookTextSearch.search(listOf(page), "istanbul").single().matchCount)
    }

    @Test fun allWordsCanMatchAcrossBoxesAndTheStrongestBoxSuppliesTheSnippet() {
        val first = NotePage(texts = listOf(box("Algebra", "a"), box("Solve SOLVE", "b")))
        val second = NotePage(texts = listOf(box("algebra solve")))
        val missing = NotePage(texts = listOf(box("algebra")))
        val hits = NotebookTextSearch.search(listOf(first, second, missing), "Algebra solve")
        assertEquals(listOf(0, 1), hits.map { it.pageIndex })
        assertEquals(listOf(3, 2), hits.map { it.matchCount })
        assertEquals("b", hits.first().boxId)
        assertEquals("Solve SOLVE", hits.first().snippet)
    }

    @Test fun unicodeSpacesSeparatePastedSearchWordsAndCollapseInSnippets() {
        val page = NotePage(texts = listOf(box("Algebra\u00a0\u2003revision")))
        val hit = NotebookTextSearch.search(listOf(page), "algebra\u00a0revision").single()
        assertEquals(2, hit.matchCount)
        assertEquals("Algebra revision", hit.snippet)
    }

    @Test fun emptyQueriesAndUnloadedSummariesHaveNoHits() {
        val page = NotePage(texts = listOf(box("Algebra")))
        assertTrue(NotebookTextSearch.search(listOf(page), " \u00a0 ").isEmpty())
        assertTrue(NotebookTextSearch.search(listOf(page.copy(loaded = false, texts = emptyList())), "Algebra").isEmpty())
    }
}
