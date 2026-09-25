package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class PeekAnchorTests {
    private val anchor = PeekAnchor("reference", -234.5f, 100f, 370f, 450.5f)
    @Test fun anchorsSurviveIndexPageAndPortableArchiveRoundTrips() {
        val note = Notebook(title = "Peek", pages = listOf(NotePage(id = "writing", peekAnchor = anchor), NotePage(id = "reference")))
        val meta = NoteMetaCodec.decode(NoteMetaCodec.encode(note))
        assertEquals(anchor, meta.pages.first().peekAnchor)
        val loaded = NotePageCodec.decode(NotePageCodec.encode(note.pages.first()), meta.pages.first())
        assertEquals(anchor, loaded.peekAnchor)
        assertEquals(anchor, NoteCodec.decode(NoteCodec.encode(note)).pages.first().peekAnchor)
    }
    @Test fun missingAndMalformedTargetsFailGracefully() {
        assertNull(anchor.resolve(listOf(NotePage(id = "writing"))))
        assertNull(PeekAnchor.decode(null))
        assertNull(PeekAnchor.decode(anchor.encode().put("right", -500)))
        assertEquals("reference", anchor.resolve(listOf(NotePage(id = "reference")))?.id)
    }
    @Test fun snapshotKeepsExactZoomAndNegativeCoordinates() {
        val camera = InfiniteViewport()
        camera.restore(-1234.125f, 234.75f, 2.5375f)
        val snapshot = ViewportSnapshot("writing", WorkspaceViewport(canvasX = camera.x, canvasY = camera.y, canvasZoom = camera.zoom))
        camera.fit(0f, 0f, 840f, 300f, 1080f, 720f)
        camera.restore(snapshot.viewport.canvasX, snapshot.viewport.canvasY, snapshot.viewport.canvasZoom)
        assertEquals(2.5375f, camera.zoom, 0f)
        assertEquals(-1234.125f, camera.x, 0f)
        assertEquals(234.75f, camera.y, 0f)
    }
    @Test fun oneAnchorIsAvailableFromEveryPageAndSurvivesReload() {
        val note = Notebook(title = "Peek", pages = listOf(NotePage(id = "writing"), NotePage(id = "reference")))
            .withSharedPeekAnchor(anchor)
        assertEquals(anchor, note.sharedPeekAnchor())
        assertEquals(anchor, NoteMetaCodec.decode(NoteMetaCodec.encode(note)).sharedPeekAnchor())
        assertEquals(anchor, NoteCodec.decode(NoteCodec.encode(note)).sharedPeekAnchor())
        assertEquals(anchor, note.withInsertedPage(0).sharedPeekAnchor())
        assertEquals(anchor, note.withMovedPage(1, 0).sharedPeekAnchor())
        assertEquals(anchor, note.withDeletedPage(0).sharedPeekAnchor())
        assertNull(note.withDeletedPage(1).sharedPeekAnchor())
    }
    @Test fun fullPagePeekAnchorCoversCurrentPage() {
        val page = NotePage(id = "current", width = 840f, height = 1188f)
        assertEquals(PeekAnchor("current", 0f, 0f, 840f, 1188f), page.fullPagePeekAnchor())
        val note = Notebook(title = "Peek", pages = listOf(page, NotePage(id = "other")))
            .withSharedPeekAnchor(page.fullPagePeekAnchor())
        assertEquals(PeekAnchor("current", 0f, 0f, 840f, 1188f), note.sharedPeekAnchor())
    }
    @Test fun replacingOrRemovingSharedAnchorClearsLegacyPageAssignments() {
        val legacy = Notebook(title = "Peek", pages = listOf(NotePage(id = "writing", peekAnchor = anchor),
            NotePage(id = "reference", peekAnchor = anchor)))
        assertEquals(anchor, legacy.sharedPeekAnchor())
        val replacement = anchor.copy(pageId = "writing")
        val changed = legacy.withSharedPeekAnchor(replacement)
        assertEquals(replacement, changed.sharedPeekAnchor())
        assertEquals(1, changed.pages.count { it.peekAnchor != null })
        assertNull(changed.withSharedPeekAnchor(null).sharedPeekAnchor())
        assertEquals(changed, changed.withSharedPeekAnchor(anchor.copy(pageId = "missing")))
    }

}
