package com.folio.notes.pdf

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException

class PdfRenderCacheTest {
    private class Document(val id: String) : Closeable {
        var closes = 0
        override fun close() { closes++ }
    }
    private class Image(val bytes: Int)
    private class Fixture(maxBytes: Int = 100, maxDocuments: Int = 3) {
        val documents = mutableListOf<Document>()
        var draws = 0
        var beforeDraw: suspend () -> Unit = {}
        var missing = false
        val cache = PdfRenderCache(
            maxBytes = maxBytes,
            maxDocuments = maxDocuments,
            open = { id: String -> if (missing) null else Document(id).also { documents += it } },
            draw = { _: Document, _: Int, width: Int ->
                draws++
                beforeDraw()
                Image(width)
            },
            sizeOf = { image: Image -> image.bytes },
        )
    }

    @Test fun alternatingSplitDocumentsAreParsedOnlyOnce() = runBlocking {
        val f = Fixture(maxBytes = 1000)
        repeat(8) { page ->
            f.cache.get("editor", page, 10)
            f.cache.get("reference", page, 10)
        }
        assertEquals(16, f.draws)
        assertEquals(listOf("editor", "reference"), f.documents.map { it.id })
        assertTrue(f.documents.all { it.closes == 0 })
        f.cache.close()
        assertTrue(f.documents.all { it.closes == 1 })
    }

    @Test fun thumbnailsAndEditedPreviewsReuseSharperBackgrounds() = runBlocking {
        val f = Fixture(maxBytes = 2000)
        val editor = f.cache.get("a", 0, 1400)
        repeat(10) {
            assertSame(editor, f.cache.get("a", 0, 80))
            assertSame(editor, f.cache.get("a", 0, 200))
            assertSame(editor, f.cache.get("a", 0, 420))
        }
        assertEquals(1, f.draws)
    }

    @Test fun thumbnailNeverSatisfiesLargerRequestAndSharperImageReplacesIt() = runBlocking {
        val f = Fixture()
        val thumbnail = f.cache.get("a", 0, 20)
        val editor = f.cache.get("a", 0, 70)
        assertNotSame(thumbnail, editor)
        assertSame(editor, f.cache.get("a", 0, 20))
        f.cache.get("a", 1, 30)
        assertSame(editor, f.cache.get("a", 0, 70))
        assertEquals(3, f.draws) // The superseded 20-byte thumbnail no longer consumes the budget.
    }

    @Test fun cachedPageDoesNotWaitForAnotherNativeRender() = runBlocking {
        val f = Fixture()
        val cached = f.cache.get("a", 0, 20)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeDraw = { started.complete(Unit); release.await() }
        val busy = async { f.cache.get("a", 1, 20) }
        try {
            withTimeout(5000) { started.await() }
            assertSame(cached, withTimeout(1000) { f.cache.get("a", 0, 20) })
        } finally { release.complete(Unit) }
        busy.await()
        assertEquals(2, f.draws)
    }

    @Test fun simultaneousRequestsShareOneRender() = runBlocking {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        f.beforeDraw = { release.await() }
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.cache.get("a", 0, 50) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { f.cache.get("a", 0, 20) }
        release.complete(Unit)
        assertSame(first.await(), second.await())
        assertEquals(1, f.draws)
    }

    @Test fun cancelledQueuedPageDoesNotOpenOrRenderItsDocument() = runBlocking {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        f.beforeDraw = { release.await() }
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.cache.get("a", 0, 20) }
        val stale = async(start = CoroutineStart.UNDISPATCHED) { f.cache.get("b", 0, 20) }
        stale.cancelAndJoin()
        release.complete(Unit)
        first.await()
        assertEquals(1, f.draws)
        assertEquals(listOf("a"), f.documents.map { it.id })
    }

    @Test fun differentDocumentsStillSerializeNativePageAccess() = runBlocking {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        f.beforeDraw = { release.await() }
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.cache.render("a", 0, 20) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { f.cache.render("b", 0, 20) }
        assertEquals(1, f.draws)
        assertEquals(listOf("a"), f.documents.map { it.id })
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(2, f.draws)
        assertEquals(listOf("a", "b"), f.documents.map { it.id })
    }

    @Test fun documentEvictionClosesLeastRecentlyRenderedDocument() = runBlocking {
        val f = Fixture(maxDocuments = 2)
        f.cache.render("a", 0, 20)
        f.cache.render("b", 0, 20)
        f.cache.render("a", 1, 20)
        f.cache.render("c", 0, 20)
        assertEquals(listOf(0, 1, 0), f.documents.map { it.closes })
        f.cache.close("a")
        assertEquals(listOf(1, 1, 0), f.documents.map { it.closes })
        f.cache.close()
        assertTrue(f.documents.all { it.closes == 1 })
    }

    @Test fun byteBudgetEvictsLeastRecentlyUsedImage() = runBlocking {
        val f = Fixture(maxBytes = 40)
        val a = f.cache.get("a", 0, 20)
        f.cache.get("a", 1, 20)
        assertSame(a, f.cache.get("a", 0, 20))
        f.cache.get("a", 2, 20)
        assertSame(a, f.cache.get("a", 0, 20))
        f.cache.get("a", 1, 20)
        assertEquals(4, f.draws)
        assertEquals(20, a!!.bytes) // An evicted shared image remains usable by its view.
    }

    @Test fun oversizedImageDoesNotFlushUsefulPages() = runBlocking {
        val f = Fixture(maxBytes = 40)
        val cached = f.cache.get("a", 0, 20)
        f.cache.get("a", 1, 50)
        assertSame(cached, f.cache.get("a", 0, 20))
        f.cache.get("a", 1, 50)
        assertEquals(3, f.draws)
    }

    @Test fun ownedRendersCannotExposeOrReplaceSharedImage() = runBlocking {
        val f = Fixture()
        val shared = f.cache.get("a", 0, 20)
        val owned = f.cache.render("a", 0, 20)
        assertNotSame(shared, owned)
        assertNotSame(owned, f.cache.render("a", 0, 20))
        assertSame(shared, f.cache.get("a", 0, 20))
        assertEquals(1, f.documents.size)
        assertEquals(3, f.draws)
    }

    @Test fun closeWaitsForActiveRenderAndInvalidatesOnlyNamedDocument() = runBlocking {
        val f = Fixture()
        val a = f.cache.get("a", 0, 20)
        val b = f.cache.get("b", 0, 20)
        val release = CompletableDeferred<Unit>()
        f.beforeDraw = { release.await() }
        val busy = async(start = CoroutineStart.UNDISPATCHED) { f.cache.get("a", 1, 20) }
        val closing = async(start = CoroutineStart.UNDISPATCHED) { f.cache.close("a") }
        assertEquals(0, f.documents[0].closes)
        release.complete(Unit)
        busy.await(); closing.await()
        assertEquals(1, f.documents[0].closes)
        assertSame(b, f.cache.get("b", 0, 20))
        assertNotSame(a, f.cache.get("a", 0, 20))
        assertEquals(listOf("a", "b", "a"), f.documents.map { it.id })
    }

    @Test fun failedRenderCanRetryAndMissingSourceIsNotRemembered() = runBlocking {
        val f = Fixture()
        f.missing = true
        assertNull(f.cache.get("a", 0, 20))
        f.missing = false
        f.beforeDraw = { throw IOException("Bad page") }
        assertTrue(runCatching { f.cache.get("a", 0, 20) }.exceptionOrNull() is IOException)
        f.beforeDraw = {}
        assertNotNull(f.cache.get("a", 0, 20))
        assertEquals(1, f.documents.size)
        assertEquals(2, f.draws)
    }
}
