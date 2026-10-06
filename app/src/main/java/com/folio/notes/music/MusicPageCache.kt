package com.folio.notes.music

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlin.math.roundToInt

/** A bounded reader cache; render on IO and prefetch the next spread before a page turn. */
internal class MusicPageCache {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Do not recycle evicted images: Compose may still be drawing one.
    }
    /** Shelf covers live apart from reader pages, so closing a score never blanks the shelf. */
    private val covers = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Synchronized fun render(file: File, page: Int, width: Int): Bitmap {
        val key = "${file.name}:$page:$width"
        cache.get(key)?.let { return it }
        return draw(file, page, width).also { cache.put(key, it) }
    }
    /** First page at shelf size. Keyed by file, so a re-extracted part never shows a stale cover. */
    @Synchronized fun cover(file: File, width: Int): Bitmap {
        val key = "${file.name}:${file.lastModified()}:$width"
        covers.get(key)?.let { return it }
        return draw(file, 0, width.coerceAtMost(480)).also { covers.put(key, it) }
    }
    private fun draw(file: File, page: Int, width: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> renderer.openPage(page).use { pdf ->
                val ratio = pdf.height.toFloat() / pdf.width
                val w = minOf(width.coerceIn(1, 2000), (2400 / ratio).roundToInt().coerceAtLeast(1))
                val h = (w * ratio).roundToInt().coerceIn(1, 2400)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    pdf.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (e: Throwable) { bitmap.recycle(); throw e }
            } }
        }
    fun clear() { cache.evictAll() }
}
