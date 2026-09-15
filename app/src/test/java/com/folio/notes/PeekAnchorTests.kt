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
}
