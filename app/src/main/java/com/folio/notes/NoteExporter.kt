package com.folio.notes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

class NoteExporter(private val repository: NoteRepository) {
    suspend fun write(
        context: Context,
        uri: Uri,
        note: Notebook,
        pageIndex: Int,
        png: Boolean,
        pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE,
        pdfMode: PdfExportMode = PdfExportMode.PRESERVE
    ): Unit = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            write(it, note, pageIndex, png, pngScale, pdfMode, context.applicationContext)
        } ?: error("Couldn't open the export destination")
    }
    suspend fun write(
        output: OutputStream,
        note: Notebook,
        pageIndex: Int,
        png: Boolean,
        pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE,
        pdfMode: PdfExportMode = PdfExportMode.PRESERVE,
        context: Context? = null
    ): Unit = withContext(Dispatchers.IO) {
        if (png) writeSinglePng(output, note, pageIndex, pngScale)
        else writePdf(output, note, note.pages.indices.toList(), pdfMode, context)
    }

    /** Writes exactly the requested pages, as a PDF or as one PNG (single page) or a zip of PNGs. */
    suspend fun write(context: Context, uri: Uri, request: PageExportRequest, pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE): Unit = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { write(it, request, pngScale, context.applicationContext) } ?: error("Couldn't open the export destination")
    }

    suspend fun write(
        output: OutputStream,
        request: PageExportRequest,
        pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE,
        context: Context? = null
    ): Unit = withContext(Dispatchers.IO) {
        val indices = normalizeExportIndices(request.indices, request.note.pages.size)
        require(indices.isNotEmpty()) { "Select at least one page to export" }
        val scoped = request.copy(indices = indices)
        when (scoped.format) {
            PageExportFormat.PDF -> writePdf(output, scoped.note, scoped.indices, scoped.pdfMode, context)
            PageExportFormat.PNG -> if (scoped.indices.size == 1) writeSinglePng(output, scoped.note, scoped.indices.first(), pngScale)
                else writePngZip(output, scoped.note, scoped.indices, pngScale)
        }
    }

    /**
     * A PDF holding exactly [indices] in notebook order, reading one page at a time.
     *
     * PRESERVE keeps source text/vectors and overlays Folio content; RASTERISE flattens
     * each composed page into an image for maximum compatibility. A [context] is required
     * for PRESERVE (PDFBox resources + temp dir); without one PRESERVE falls back to RASTERISE
     * rather than failing.
     */
    suspend fun writePdf(
        output: OutputStream,
        note: Notebook,
        indices: List<Int>,
        mode: PdfExportMode = PdfExportMode.PRESERVE,
        context: Context? = null
    ): Unit = withContext(Dispatchers.IO) {
        val selected = normalizeExportIndices(indices, note.pages.size)
        require(selected.isNotEmpty()) { "Select at least one page to export" }
        when (mode) {
            PdfExportMode.RASTERISE -> RasterPdfExporter.write(output, note, selected, repository)
            PdfExportMode.PRESERVE -> {
                val ctx = context
                if (ctx != null) PreservedPdfExporter.write(output, note, selected, repository, ctx)
                else RasterPdfExporter.write(output, note, selected, repository)
            }
        }
    }

    /** Context-aware overload so callers with a Context always get true PRESERVE behaviour. */
    suspend fun writePdfWithContext(
        context: Context,
        output: OutputStream,
        note: Notebook,
        indices: List<Int>,
        mode: PdfExportMode = PdfExportMode.PRESERVE
    ): Unit = writePdf(output, note, indices, mode, context)

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

    /**
     * Renders one page as PNG and inserts it into the shared photo gallery
     * (MediaStore Images, Pictures/Folio) so it appears in the camera roll /
     * gallery app. Returns the MediaStore Uri of the new image.
     *
     * On Android 10+ no permission is needed; on older releases the caller must
     * hold WRITE_EXTERNAL_STORAGE before invoking.
     */
    suspend fun saveSinglePngToGallery(
        context: Context,
        note: Notebook,
        pageIndex: Int,
        pngScale: Float = AppPrefs.DEFAULT_PNG_SCALE
    ): Uri = withContext(Dispatchers.IO) {
        val selected = normalizeExportIndices(listOf(pageIndex), note.pages.size)
        require(selected.isNotEmpty()) { "Select at least one page to export" }
        val index = selected.first()
        val bitmap = repository.openPdf(note.id).use { source ->
            val page = note.pages.getOrNull(index) ?: note.pages.first()
            renderPng(note, page, source, pngScale)
        }
        try {
            val filename = galleryPngFilename(note, index)
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, galleryRelativePath())
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't save to gallery")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Image export failed" }
                } ?: error("Couldn't save to gallery")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } finally {
            bitmap.recycle()
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
