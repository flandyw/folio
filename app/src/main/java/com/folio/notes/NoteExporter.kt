package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

class NoteExporter(private val repository: NoteRepository) {
    suspend fun write(context: Context, uri: Uri, note: Notebook, pageIndex: Int, png: Boolean): Unit = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { write(it, note, pageIndex, png) } ?: error("Couldn't open the export destination")
    }
    suspend fun write(output: OutputStream, note: Notebook, pageIndex: Int, png: Boolean): Unit = withContext(Dispatchers.IO) {
        // The imported PDF is parsed once here and reused, so a long notebook isn't reparsed per page.
        repository.openPdf(note.id).use { source ->
            if (png) {
                val page = content(note, note.pages.getOrNull(pageIndex) ?: note.pages.first())
                val factor = minOf(2f, 2800f / page.height, 2800f / page.width)
                val bitmap = Bitmap.createBitmap((page.width * factor).toInt().coerceAtLeast(1), (page.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val background = source?.render(page, bitmap.width)
                try {
                    val canvas = Canvas(bitmap); canvas.scale(factor, factor); InkRenderer.page(canvas, page, background)
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Image export failed" }
                } finally { bitmap.recycle(); background?.recycle() }
            } else {
                val document = PdfDocument()
                try {
                note.pages.forEachIndexed { index, page ->
                    // Each page is read from disk just before it is drawn, so exporting a long notebook
                    // never holds more than one page's ink in memory at a time.
                    val loaded = content(note, page)
                    val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(loaded.width.toInt(), loaded.height.toInt(), index + 1).create())
                    val background = source?.render(loaded, 1680)
                    try { InkRenderer.page(pdfPage.canvas, loaded, background) } finally { background?.recycle() }
                    document.finishPage(pdfPage)
                }
                document.writeTo(output)
                } finally { document.close() }
            }
        }
    }

    /** A page's ink, reading it from disk when the notebook is stored lazily and it is not loaded yet. */
    private suspend fun content(note: Notebook, page: NotePage): NotePage =
        if (page.loaded) page else repository.loadPage(note.id, page)

    fun filename(note: Notebook): String = note.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "Notebook" }
}
