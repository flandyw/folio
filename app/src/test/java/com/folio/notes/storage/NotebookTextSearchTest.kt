package com.folio.notes.storage

import com.folio.notes.model.NotePage
import com.folio.notes.model.TextBox
import org.junit.Assert.*
import org.junit.Test

class NotebookTextSearchTest {
    @Test fun unicodeCaseExpansionCannotShiftSnippetOffsetsOrCrashSearch() {
        val text = "İ".repeat(120) + " Target answer"
        val page = NotePage(texts = listOf(TextBox(x = 0f, y = 0f, text = text)))
        val hit = NotebookTextSearch.search(listOf(page), "target").single()
        assertTrue(hit.snippet.contains("Target answer"))
        assertEquals(1, hit.matchCount)
    }

    @Test fun allTermsCanMatchAcrossBoxesAndCountsIgnoreCase() {
        val page = NotePage(texts = listOf(TextBox(x = 0f, y = 0f, text = "Answer ANSWER"),
            TextBox(x = 0f, y = 20f, text = "Working")))
        assertEquals(3, NotebookTextSearch.search(listOf(page), "answer working").single().matchCount)
        assertTrue(NotebookTextSearch.search(listOf(page), "missing working").isEmpty())
    }
}
