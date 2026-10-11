package com.folio.notes.chatgpt

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Rect
import com.folio.notes.InkGeometry
import com.folio.notes.InkRenderer
import com.folio.notes.NotePage
import com.folio.notes.NoteRepository
import com.folio.notes.Notebook
import com.folio.notes.PageLayers
import com.folio.notes.StickyNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

internal data class ChatGptPageImage(val bitmap: Bitmap, val bounds: Rect) {
    companion object {
        /** One renderer for the local preview and the image sent for review. */
        suspend fun render(note: Notebook, original: NotePage, repository: NoteRepository, longestSide: Int = 1600): ChatGptPageImage = withContext(Dispatchers.IO) {
            val page = PageLayers.view(original.copy(texts = original.texts.filter { StickyNotes.exports(it, original) }))
            val bounds = if (page.infinite) InkGeometry.contentBounds(page.strokes, page.texts, page.images, InkRenderer::textHeight, page.width, page.height)
                .let { Rect(it.left - 24f, it.top - 24f, it.right + 24f, it.bottom + 24f) }
                else Rect(0f, 0f, page.width, page.height)
            val width = bounds.width; val height = bounds.height
            if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0)
                throw ChatGptFailure("This page could not be prepared for review.")
            val scale = min(longestSide.toFloat() / width, longestSide.toFloat() / height).coerceAtMost(2f)
            val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            var background: Bitmap? = null
            var images: Map<String, Bitmap> = emptyMap()
            try {
                background = repository.pdfBackground(note.id, page, bitmap.width)
                images = repository.loadImages(note.id, page)
                if (page.pdfIndex != null && background == null) throw ChatGptFailure("The PDF background could not be loaded. Reopen the page and try again.")
                if (page.images.any { !images.containsKey(it.id) }) throw ChatGptFailure("A picture on this page could not be loaded. Reopen the page and try again.")
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale); canvas.translate(-bounds.left, -bounds.top)
                InkRenderer.page(canvas, page, background, images = images)
                ChatGptPageImage(bitmap, bounds)
            } catch (e: Exception) { bitmap.recycle(); throw e }
            finally { background?.recycle(); images.values.forEach { it.recycle() } }
        }
    }
}
