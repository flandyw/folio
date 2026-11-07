package com.folio.notes.storage

import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageImage
import com.folio.notes.model.PageLayer
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import com.folio.notes.model.asSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NotebookSearchLoadingTest {
    @Test fun unopenedPagesKeepTheirInkPicturesLayersAndCurrentMetadata() = runBlocking {
        val stroke = Stroke(Tool.PEN, 1, 2f, listOf(InkPoint(10f, 20f)))
        val stored = NotePage(id = "stored", revision = 8, title = "Old title",
            strokes = listOf(stroke), texts = listOf(TextBox(x = 1f, y = 2f, text = "Answer")),
            images = listOf(PageImage(id = "photo", x = 1f, y = 2f, width = 30f, height = 40f)),
            layers = listOf(PageLayer(id = 0, name = "Ink")))
        val summary = stored.asSummary().copy(title = "Renamed page", bookmarked = true, revision = 7)
        val live = NotePage(id = "live", strokes = listOf(stroke))
        val reads = mutableListOf<String>()
        val pages = NotebookTextSearch.loadPages(listOf(live, summary)) {
            reads += it.id
            stored
        }
        assertEquals(listOf("stored"), reads)
        assertSame(live, pages[0])
        val loaded = pages[1]
        assertTrue(loaded.loaded)
        assertEquals(stored.strokes, loaded.strokes)
        assertEquals(stored.images, loaded.images)
        assertEquals(stored.texts, loaded.texts)
        assertEquals(stored.layers, loaded.layers)
        assertEquals(8, loaded.revision)
        assertEquals("Renamed page", loaded.title)
        assertTrue(loaded.bookmarked)
    }

    @Test fun failedPageRemainsUnavailableWhileOtherPagesAreSearchable() = runBlocking {
        val unavailable = NotePage(id = "unavailable", loaded = false)
        val available = NotePage(id = "available", loaded = false)
        val loaded = available.copy(loaded = true, texts = listOf(TextBox(x = 0f, y = 0f, text = "Answer")))
        val pages = NotebookTextSearch.loadPages(listOf(unavailable, available)) {
            if (it.id == unavailable.id) throw IOException("Unreadable page")
            loaded
        }
        assertSame(unavailable, pages[0])
        assertEquals(listOf(1), NotebookTextSearch.search(pages, "Answer").map { it.pageIndex })
    }

    @Test fun cancellationStopsLoadingInsteadOfReturningAnEmptySearch() = runBlocking {
        val reads = mutableListOf<String>()
        try {
            NotebookTextSearch.loadPages(listOf(NotePage(id = "a", loaded = false), NotePage(id = "b", loaded = false))) {
                reads += it.id
                throw CancellationException("Search closed")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("a"), reads)
        }
    }
}
