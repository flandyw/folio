package com.folio.notes.pdf

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.LinkedHashMap

/**
 * Keeps recent documents open and shares immutable page images within a byte budget. Android-free
 * so renderer reuse, cancellation and ownership can be checked without a device.
 *
 * Only document access takes [renderLock]. A cached page can appear immediately while another page
 * is rendering. Callbacks run on the caller's dispatcher; the repository supplies its IO context.
 */
internal class PdfRenderCache<Document : Closeable, Image : Any>(
    private val maxBytes: Int,
    private val open: (String) -> Document?,
    private val draw: suspend (Document, Int, Int) -> Image,
    private val sizeOf: (Image) -> Int,
    private val maxDocuments: Int = 3,
) {
    init { require(maxBytes > 0 && maxDocuments > 0) }

    private data class Key(val noteId: String, val index: Int)
    private data class Entry<Image>(val image: Image, val width: Int, val bytes: Int)
    private val renderLock = Mutex()
    private val documents = LinkedHashMap<String, Document>(4, .75f, true)
    private val imageLock = Any()
    private val images = LinkedHashMap<Key, Entry<Image>>(16, .75f, true)
    private var imageBytes = 0L

    /** Shared image: callers must never recycle it, including after eviction or [close]. */
    suspend fun get(noteId: String, index: Int, width: Int): Image? {
        require(index >= 0 && width > 0)
        currentCoroutineContext().ensureActive()
        cached(noteId, index, width)?.let { return it }
        return renderLock.withLock {
            currentCoroutineContext().ensureActive()
            // Other surfaces may have requested the same page while this request was queued.
            cached(noteId, index, width)?.let { return@withLock it }
            val document = document(noteId) ?: return@withLock null
            draw(document, index, width).also { remember(Key(noteId, index), width, it) }
        }
    }

    /** Fresh, caller-owned image for exports or OCR that recycle their working bitmaps. */
    suspend fun render(noteId: String, index: Int, width: Int): Image? {
        require(index >= 0 && width > 0)
        return renderLock.withLock {
            currentCoroutineContext().ensureActive()
            document(noteId)?.let { draw(it, index, width) }
        }
    }

    /** Keep one resolution per page, upgrading as needed; never upscale a thumbnail. */
    private fun cached(noteId: String, index: Int, width: Int): Image? = synchronized(imageLock) {
        images[Key(noteId, index)]?.takeIf { it.width >= width }?.image
    }

    private fun remember(key: Key, width: Int, image: Image) = synchronized(imageLock) {
        val bytes = sizeOf(image)
        // An oversized export/review must not evict every useful editor page.
        if (bytes > maxBytes) return@synchronized
        images.remove(key)?.let { imageBytes -= it.bytes }
        images[key] = Entry(image, width, bytes)
        imageBytes += bytes
        val oldest = images.entries.iterator()
        while (imageBytes > maxBytes && oldest.hasNext()) {
            imageBytes -= oldest.next().value.bytes
            oldest.remove()
        }
    }

    /** Called only under renderLock: a document cannot close while one of its pages is open. */
    private fun document(noteId: String): Document? {
        documents[noteId]?.let { return it }
        val document = open(noteId) ?: return null
        documents[noteId] = document
        if (documents.size > maxDocuments) {
            val oldest = documents.entries.iterator()
            val evicted = oldest.next().value
            oldest.remove()
            evicted.close()
        }
        return document
    }

    /** Invalidates images too, so a removed/replaced document cannot leave stale backgrounds. */
    suspend fun close(noteId: String? = null): Unit = renderLock.withLock {
        synchronized(imageLock) {
            val iterator = images.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (noteId == null || entry.key.noteId == noteId) {
                    imageBytes -= entry.value.bytes
                    iterator.remove()
                }
            }
        }
        if (noteId != null) documents.remove(noteId)?.close()
        else {
            val closing = documents.values.toList()
            documents.clear()
            closing.forEach { it.close() }
        }
    }
}
