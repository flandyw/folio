package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

class NoteExporter(private val repository: NoteRepository) {
    suspend fun write(context: Context, uri: Uri, note: Notebook, pageIndex: Int, png: Boolean, pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE): Unit = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { write(it, note, pageIndex, png, pngScale) } ?: error("Couldn't open the export destination")
    }
    suspend fun write(output: OutputStream, note: Notebook, pageIndex: Int, png: Boolean, pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE): Unit = withContext(Dispatchers.IO) {
        if (png) writeSinglePng(output, note, pageIndex, pngScale)
        else writePdf(output, note, note.pages.indices.toList())
    }

    /** Writes exactly the requested pages, as a PDF or as one PNG (single page) or a zip of PNGs. */
    suspend fun write(context: Context, uri: Uri, request: PageExportRequest, pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE): Unit = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { write(it, request, pngScale) } ?: error("Couldn't open the export destination")
    }

    suspend fun write(output: OutputStream, request: PageExportRequest, pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE): Unit = withContext(Dispatchers.IO) {
        val indices = normalizeExportIndices(request.indices, request.note.pages.size)
        require(indices.isNotEmpty()) { "Select at least one page to export" }
        val scoped = request.copy(indices = indices)
        when (scoped.format) {
            PageExportFormat.PDF -> writePdf(output, scoped.note, scoped.indices)
            PageExportFormat.PNG -> if (scoped.indices.size == 1) writeSinglePng(output, scoped.note, scoped.indices.first(), pngScale)
                else writePngZip(output, scoped.note, scoped.indices, pngScale)
        }
    }

    /** A PDF holding exactly [indices] in notebook order, reading one page at a time. */
    suspend fun writePdf(output: OutputStream, note: Notebook, indices: List<Int>): Unit = withContext(Dispatchers.IO) {
        val selected = normalizeExportIndices(indices, note.pages.size)
        require(selected.isNotEmpty()) { "Select at least one page to export" }
        // The imported PDF is parsed once here and reused, so a long notebook isn't reparsed per page.
        repository.openPdf(note.id).use { source ->
            val document = PdfDocument()
            try {
                selected.forEachIndexed { number, pageIndex ->
                    // Each page is read from disk just before it is drawn, so exporting a long notebook
                    // never holds more than one page's ink in memory at a time.
                    val page = note.pages.getOrNull(pageIndex) ?: return@forEachIndexed
                    val loaded = InkRenderer.exportPage(content(note, page))
                    val factor = minOf(1f, 14400f / loaded.width, 14400f / loaded.height)
                    val pdfPage = document.startPage(PdfDocument.PageInfo.Builder((loaded.width * factor).toInt().coerceAtLeast(1), (loaded.height * factor).toInt().coerceAtLeast(1), number + 1).create())
                    pdfPage.canvas.scale(factor, factor)
                    val background = source?.render(loaded, 1680)
                    val images = repository.loadImages(note.id, loaded)
                    try { InkRenderer.page(pdfPage.canvas, loaded, background, images = images) } finally { background?.recycle(); images.values.forEach { it.recycle() } }
                    document.finishPage(pdfPage)
                }
                document.writeTo(output)
            } finally { document.close() }
        }
    }

    /** One PNG per selected page, zipped so a single document-picker destination is enough. */
    suspend fun writePngZip(output: OutputStream, note: Notebook, indices: List<Int>, pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE): Unit = withContext(Dispatchers.IO) {
        val selected = normalizeExportIndices(indices, note.pages.size)
        require(selected.isNotEmpty()) { "Select at least one page to export" }
        repository.openPdf(note.id).use { source ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                selected.forEach { pageIndex ->
                    val page = note.pages.getOrNull(pageIndex) ?: return@forEach
                    val bitmap = renderPng(note, page, source, pngScale)
                    try {
                        val name = "${NotebookFilename.sanitize(note.title)}-p${pageIndex + 1}.png"
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) { "Image export failed" }
                        zip.closeEntry()
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    /** One PNG file per selected page, so a native share can send images instead of a zip. */
    suspend fun writeSeparatePngs(dir: File, note: Notebook, indices: List<Int>, pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE): List<File> = withContext(Dispatchers.IO) {
        val selected = normalizeExportIndices(indices, note.pages.size)
        require(selected.isNotEmpty()) { "Select at least one page to export" }
        dir.mkdirs()
        repository.openPdf(note.id).use { source ->
            selected.mapNotNull { pageIndex ->
                val page = note.pages.getOrNull(pageIndex) ?: return@mapNotNull null
                val bitmap = renderPng(note, page, source, pngScale)
                try {
                    val file = File(dir, "${NotebookFilename.sanitize(note.title)}-p${pageIndex + 1}.png")
                    file.outputStream().use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Image export failed" }
                    }
                    file
                } finally { bitmap.recycle() }
            }
        }
    }

    private suspend fun writeSinglePng(output: OutputStream, note: Notebook, pageIndex: Int, pngScale: Float): Unit = withContext(Dispatchers.IO) {
        repository.openPdf(note.id).use { source ->
            val page = note.pages.getOrNull(pageIndex) ?: note.pages.first()
            val bitmap = renderPng(note, page, source, pngScale)
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Image export failed" }
            } finally { bitmap.recycle() }
        }
    }

    private suspend fun renderPng(note: Notebook, page: NotePage, source: NoteRepository.PdfBackgrounds?, pngScale: Float): Bitmap {
        val prepared = InkRenderer.exportPage(content(note, page))
        val scale = AppPrefs.pngScale(pngScale)
        val factor = minOf(scale, 2800f / prepared.height, 2800f / prepared.width)
        val bitmap = Bitmap.createBitmap((prepared.width * factor).toInt().coerceAtLeast(1), (prepared.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val background = source?.render(prepared, bitmap.width)
        val images = repository.loadImages(note.id, prepared)
        try {
            val canvas = Canvas(bitmap); canvas.scale(factor, factor); InkRenderer.page(canvas, prepared, background, images = images)
        } finally { background?.recycle(); images.values.forEach { it.recycle() } }
        return bitmap
    }

    /** A page's ink, reading it from disk when the notebook is stored lazily and it is not loaded yet. */
    private suspend fun content(note: Notebook, page: NotePage): NotePage =
        if (page.loaded) page else repository.loadPage(note.id, page)

    fun filename(note: Notebook): String = NotebookFilename.sanitize(note.title)
}
