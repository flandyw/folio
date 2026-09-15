package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class EditorWorkspaceTests {
    private fun tab(id: String) = EditorTab(id, id, "$id-page", "Notebook $id")

    @Test fun reopeningAndDeepLinksReuseTabAndPreserveOrder() {
        val original = listOf(tab("a"), tab("b"), tab("c"))
        val reopened = original.withTab(original[1].copy(currentPageId = "bookmark-page"))
        assertEquals(listOf("a", "b", "c"), reopened.map { it.id })
        assertEquals("bookmark-page", reopened[1].currentPageId)
        assertEquals(original[0], reopened[0])
        assertEquals(4, reopened.withTab(tab("d")).size)
    }

    @Test fun closingChoosesRightThenLeftAndLastReturnsNoDocument() {
        val tabs = listOf(tab("a"), tab("b"), tab("c"))
        assertEquals("b", tabs.adjacentAfterClosing("a")?.id)
        assertEquals("c", tabs.adjacentAfterClosing("b")?.id)
        assertEquals("b", tabs.adjacentAfterClosing("c")?.id)
        assertNull(listOf(tab("a")).adjacentAfterClosing("a"))
        assertNull(tabs.adjacentAfterClosing("missing"))
    }

    @Test fun sessionsRoundTripIndependentPageToolScrollAndCameras() {
        val a = tab("a").copy(viewport = WorkspaceViewport(2f, -90f, 345, -1200f, 460f, .4f), tool = Tool.HIGHLIGHTER,
            search = PdfSearchState(query = "momentum"))
        val b = tab("b").copy(viewport = WorkspaceViewport(3f, 40f, 22))
        val restored = WorkspaceSessionCodec.decode(WorkspaceSessionCodec.encode(listOf(a, b)))
        assertEquals(listOf(a, b), restored)
        val companion = a.copy(id = "companion", currentPageId = "other-section")
        assertEquals(companion, WorkspaceSessionCodec.decode(WorkspaceSessionCodec.encode(listOf(companion))).single())
        assertEquals("a-page", a.currentPageId)
    }

    @Test fun malformedSavedStateDoesNotPreventOpeningLibrary() {
        assertTrue(WorkspaceSessionCodec.decode("broken").isEmpty())
        assertTrue(WorkspaceSessionCodec.decode(null).isEmpty())
        val valid = WorkspaceSessionCodec.encode(listOf(tab("a")))
        assertEquals(listOf(tab("a")), WorkspaceSessionCodec.decode(valid.dropLast(1) + ",{}]"))
    }

    @Test fun infiniteCameraRestoresWithoutLosingNegativeCoordinates() {
        val camera = InfiniteViewport()
        camera.restore(-1234f, 567f, .4f)
        assertEquals(-1234f, camera.x, 0f)
        assertEquals(567f, camera.y, 0f)
        assertEquals(.4f, camera.zoom, 0f)
        camera.restore(Float.NaN, 0f, 1f)
        assertEquals(-1234f, camera.x, 0f)
    }

    @Test fun notebookPositionsRoundTripPageViewportToolAndQuery() {
        val position = NotebookPosition("page-7",
            WorkspaceViewport(2f, -90f, 345, -1200f, 460f, .4f),
            Tool.HIGHLIGHTER, "momentum")
        val restored = NotebookPositionCodec.decode(NotebookPositionCodec.encode(position))
        assertEquals(position, restored)
    }

    @Test fun notebookPositionsRejectBlankPageAndBrokenPayloads() {
        assertNull(NotebookPositionCodec.decode(null))
        assertNull(NotebookPositionCodec.decode(""))
        assertNull(NotebookPositionCodec.decode("broken"))
        assertNull(NotebookPositionCodec.decode("""{"tool":"PEN"}"""))
    }

    @Test fun notebookPositionsSanitizeNonFiniteNumbersAndUnknownTools() {
        val restored = NotebookPositionCodec.decode(
            """{"page":"p","tool":"NOPE","query":"q","zoom":null,"pan":null,"scroll":-3,"x":null,"y":null,"scale":99}""")
        assertNotNull(restored)
        assertEquals("p", restored!!.pageId)
        assertEquals(Tool.PEN, restored.tool)
        assertEquals(WorkspaceViewport(canvasZoom = 8f), restored.viewport)
        assertEquals("q", restored.query)
    }

    @Test fun notebookPositionsMapToTabsWithoutLosingViewport() {
        val position = NotebookPosition("page-2", WorkspaceViewport(1.5f, 10f, 40, 5f, -6f, 2f), Tool.HAND, "flux")
        val tab = position.toTab("note-1", "Physics")
        assertEquals("note-1", tab.notebookId)
        assertEquals("page-2", tab.currentPageId)
        assertEquals("Physics", tab.title)
        assertEquals(position.viewport, tab.viewport)
        assertEquals(Tool.HAND, tab.tool)
        assertEquals("flux", tab.search.query)
        assertEquals(position, tab.toPosition())
    }
}
