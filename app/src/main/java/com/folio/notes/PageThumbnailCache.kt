package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders and remembers one small preview per page revision.
 *
 * A preview is named after the page's revision, so an edited page gets a new name and its old copy
 * becomes unreachable — there is nothing to invalidate and no stale preview to show. Previews live on
 * disk, which is what keeps the page browser instant on a long notebook: a page whose preview was
 * drawn before is read from cache and its page file is never opened. Only a page that has no preview
 * yet is loaded and drawn, and the result is kept for next time.
 *
 * Rendered from memory when the page is already loaded (it is the copy being edited), otherwise from
 * the page file, so the preview always matches what is on screen or on disk.
 */
class PageThumbnailCache(private val context: Context, private val repository: NoteRepository) {
    private val root = File(context.cacheDir, "thumbnails")
    private val memory = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * The page's preview at roughly [widthPx] wide, drawn now if it has never been drawn before.
     * The width is rounded to one of a few sizes, so the page browser's small preview and a library
     * card's large one never compete for the same cache entry nor redraw each other.
     */
    suspend fun thumbnail(noteId: String, page: NotePage, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val width = bucket(widthPx)
        val name = ThumbnailKeys.name(page.id, page.revision, width)
        val key = "$noteId/$name"
        memory.get(key)?.let { return@withContext it }
        val dir = File(root, noteId).apply { mkdirs() }
        val file = File(dir, name)
        val preview = if (file.exists()) decode(file) else render(noteId, page, width)?.also { store(dir, page, width, it) }
        preview?.also { memory.put(key, it) }
    }

    /** Forgets a notebook's previews and their in-memory copies, used when a notebook is deleted. */
    fun clear(noteId: String) {
        memory.snapshot().keys.filter { it.startsWith("$noteId/") }.forEach { memory.remove(it) }
        File(root, noteId).deleteRecursively()
    }

    private suspend fun render(noteId: String, page: NotePage, widthPx: Int): Bitmap? {
        // A page still only on disk is read here; a failure simply leaves no preview, never a crash.
        val loaded = if (page.loaded) page else try { repository.loadPage(noteId, page) } catch (_: Exception) { return null }
        val content = InkRenderer.exportPage(loaded)
        return try {
            val heightPx = (widthPx * content.height / content.width).toInt().coerceIn(1, 4096)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val background = if (content.pdfIndex != null) repository.pdfBackground(noteId, content, widthPx) else null
            val images = repository.loadImages(noteId, content)
            try {
                val canvas = Canvas(bitmap)
                canvas.scale(widthPx / content.width, heightPx / content.height)
                InkRenderer.page(canvas, content, background, images = images)
            } finally { background?.recycle(); images.values.forEach { it.recycle() } }
            bitmap
        } catch (_: Exception) { null }
    }

    private fun store(dir: File, page: NotePage, width: Int, bitmap: Bitmap) {
        runCatching { File(dir, ThumbnailKeys.name(page.id, page.revision, width)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        // Earlier revisions at this size can no longer be asked for, so they go with it. Other sizes
        // of the page are still in use by whoever asked for them, so they are left alone.
        dir.listFiles()?.filter { ThumbnailKeys.isStale(it.name, page.id, page.revision, width) }?.forEach { it.delete() }
    }

    private fun decode(file: File): Bitmap? = try {
        file.inputStream().use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    private companion object {
        /** Preview sizes the cache keeps: the page browser, then a library card's full face. */
        const val SMALL = 80
        const val MEDIUM = 200
        const val LARGE = 420

        /** A few dozen previews at most; the disk copies are the cache that actually persists. */
        const val MAX_BYTES = 8 * 1024 * 1024

        /** Rounds a requested width to the nearest size the cache keeps, so layouts share previews. */
        fun bucket(widthPx: Int) = when {
            widthPx <= SMALL -> SMALL
            widthPx <= MEDIUM -> MEDIUM
            else -> LARGE
        }
    }
}
