package com.folio.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * Maximum-compatibility PDF export: every composed page becomes pixels.
 *
 * Each output page holds a single flattened bitmap of:
 * ```
 * original PDF page rendered
 *         +
 * Folio ink + highlighter + shapes + typed text + inserted images
 *         ↓
 * final composed bitmap
 *         ↓
 * PDF page containing that bitmap
 * ```
 * Source text is not selectable and links/bookmarks do not survive, but the result never
 * depends on overlay interpretation, so weak renderers, parsers and document-analysis tools
 * see exactly what the editor shows.
 */
object RasterPdfExporter {
    /** Render width for the flattened bitmap, balancing small exam text against memory. */
    const val TARGET_WIDTH = 1680
    const val MAX_DIMENSION = 2800

    suspend fun write(
        output: OutputStream,
        note: Notebook,
        indices: List<Int>,
        repository: NoteRepository
    ) {
        val selected = normalizeExportIndices(indices, note.pages.size)
        require(selected.isNotEmpty()) { "Select at least one page to export" }
        repository.openPdf(note.id).use { source ->
            val document = PdfDocument()
            try {
                selected.forEachIndexed { number, pageIndex ->
                    val page = note.pages.getOrNull(pageIndex) ?: return@forEachIndexed
                    val loaded = InkRenderer.exportPage(content(note, repository, page))
                    val factor = minOf(1f, 14400f / loaded.width, 14400f / loaded.height)
                    val pageWidth = (loaded.width * factor).toInt().coerceAtLeast(1)
                    val pageHeight = (loaded.height * factor).toInt().coerceAtLeast(1)
                    val bitmap = renderFlattened(note.id, loaded, source, repository)
                    try {
                        val pdfPage = document.startPage(
                            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, number + 1).create()
                        )
                        pdfPage.canvas.drawBitmap(
                            bitmap, null,
                            android.graphics.RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()),
                            Paint(Paint.FILTER_BITMAP_FLAG)
                        )
                        document.finishPage(pdfPage)
                    } finally {
                        bitmap.recycle()
                    }
                }
                document.writeTo(output)
            } finally {
                document.close()
            }
        }
    }

    private suspend fun content(note: Notebook, repository: NoteRepository, page: NotePage): NotePage =
        if (page.loaded) page else repository.loadPage(note.id, page)

    /**
     * One flattened bitmap of the full visual page at [TARGET_WIDTH] px wide (capped at
     * [MAX_DIMENSION]), reusing the editor renderer so the export matches the screen.
     */
    private suspend fun renderFlattened(
        noteId: String,
        page: NotePage,
        source: NoteRepository.PdfBackgrounds?,
        repository: NoteRepository
    ): Bitmap {
        val scale = minOf(TARGET_WIDTH / page.width, MAX_DIMENSION / page.width, MAX_DIMENSION / page.height)
            .coerceAtLeast(0.5f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val background = source?.render(page, width)
        val images = repository.loadImages(noteId, page)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            InkRenderer.page(canvas, page, background, images = images)
        } catch (e: Exception) {
            background?.recycle()
            images.values.forEach { it.recycle() }
            bitmap.recycle()
            throw e
        }
        background?.recycle()
        images.values.forEach { it.recycle() }
        return bitmap
    }
}
