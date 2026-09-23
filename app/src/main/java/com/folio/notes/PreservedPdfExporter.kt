package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.OutputStream

/**
 * Best-quality PDF export for PDF-backed notebooks:
 * ```
 * original source PDF page
 *         +
 * vector/raster Folio annotation overlay
 *         ↓
 * output PDF
 * ```
 * Source text stays searchable/selectable, vectors stay vectors and page annotations
 * (links) travel with [PDDocument.importPage]. Folio content is added as a transparent
 * bitmap overlay aligned to the page, so the editor and the export line up exactly
 * without rasterising the source page itself.
 *
 * Folio-only pages have no source to preserve and are written as normal flattened
 * image pages. Rotated source pages have their overlay pre-rotated so the viewer-side
 * rotation leaves annotations aligned; a page that cannot be preserved safely falls
 * back to a flattened image rather than failing the export.
 */
object PreservedPdfExporter {
    /** Overlay density: sharp handwriting and small exam text without extreme memory. */
    const val OVERLAY_SCALE = 2f
    const val OVERLAY_MAX_DIMENSION = 2800f

    suspend fun write(
        output: OutputStream,
        note: Notebook,
        indices: List<Int>,
        repository: NoteRepository,
        context: Context
    ) {
        val selected = normalizeExportIndices(indices, note.pages.size)
        require(selected.isNotEmpty()) { "Select at least one page to export" }
        repository.ensurePdfBox()
        val sourceFile = repository.sourcePdfFile(note.id)
        // No source to preserve: identical visual result via the compatibility path.
        if (sourceFile == null || !hasPdfPages(note)) {
            RasterPdfExporter.write(output, note, selected, repository)
            return
        }
        val tmpDir = context.cacheDir
        PDDocument.load(sourceFile, MemoryUsageSetting.setupMixed(8L * 1024 * 1024).setTempDir(tmpDir)).use { source ->
            PDDocument().use { out ->
                // Best-effort metadata: keep author/subject/keywords, title follows the notebook.
                try {
                    val srcInfo = source.documentInformation
                    val dstInfo = out.documentInformation
                    dstInfo.title = note.title.ifBlank { srcInfo.title }
                    dstInfo.author = srcInfo.author
                    dstInfo.subject = srcInfo.subject
                    dstInfo.keywords = srcInfo.keywords
                    dstInfo.creator = srcInfo.creator
                } catch (_: Exception) { }
                selected.forEach { pageIndex ->
                    val summary = note.pages.getOrNull(pageIndex) ?: return@forEach
                    val loaded = InkRenderer.exportPage(content(note, repository, summary))
                    try {
                        writeOnePage(out, source, note, repository, loaded)
                    } catch (_: Exception) {
                        // Never fail an export on one awkward page: flatten it instead.
                        writeFlattenedFallback(out, note, repository, loaded)
                    }
                }
                // Bookmarks only when the export is the whole document in source order;
                // anything selective or reordered skips them rather than mis-pointing.
                try {
                    if (isIdentityPdfExport(note, selected, source.numberOfPages)) {
                        copyOutline(source, out)
                    }
                } catch (_: Exception) { }
                out.save(output)
            }
        }
    }

    private suspend fun content(note: Notebook, repository: NoteRepository, page: NotePage): NotePage =
        if (page.loaded) page else repository.loadPage(note.id, page)

    private suspend fun writeOnePage(
        out: PDDocument,
        source: PDDocument,
        note: Notebook,
        repository: NoteRepository,
        page: NotePage
    ) {
        val pdfIndex = page.pdfIndex
        if (pdfIndex == null || pdfIndex !in 0 until source.numberOfPages) {
            writeFlattenedFallback(out, note, repository, page)
            return
        }
        val imported = out.importPage(source.getPage(pdfIndex))
        if (!pageHasAnnotations(page)) return
        val overlay = renderOverlay(note.id, page, repository)
        try {
            val rotated = applySourceRotation(overlay, imported.rotation)
            try {
                val image = LosslessFactory.createFromImage(out, rotated)
                val box = imported.cropBox ?: imported.mediaBox ?: PDRectangle.A4
                PDPageContentStream(out, imported, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.drawImage(image, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
                }
            } finally {
                if (rotated !== overlay) rotated.recycle()
            }
        } finally {
            overlay.recycle()
        }
    }

    private suspend fun writeFlattenedFallback(
        out: PDDocument,
        note: Notebook,
        repository: NoteRepository,
        page: NotePage
    ) {
        val bitmap = renderFull(note.id, page, repository)
        try {
            val width = page.width.coerceIn(1f, 14400f)
            val height = page.height.coerceIn(1f, 14400f)
            val pdPage = PDPage(PDRectangle(width, height))
            out.addPage(pdPage)
            val image = LosslessFactory.createFromImage(out, bitmap)
            PDPageContentStream(out, pdPage, PDPageContentStream.AppendMode.OVERWRITE, false).use { stream ->
                stream.drawImage(image, 0f, 0f, width, height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Transparent bitmap of Folio content only, in displayed orientation. */
    private suspend fun renderOverlay(noteId: String, page: NotePage, repository: NoteRepository): Bitmap {
        val scale = minOf(OVERLAY_SCALE, OVERLAY_MAX_DIMENSION / page.width, OVERLAY_MAX_DIMENSION / page.height)
            .coerceAtLeast(0.5f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val images = repository.loadImages(noteId, page)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            InkRenderer.overlay(canvas, page, images = images)
        } finally {
            images.values.forEach { it.recycle() }
        }
        return bitmap
    }

    /** Opaque full-page bitmap for Folio-only pages and fallbacks. */
    private suspend fun renderFull(noteId: String, page: NotePage, repository: NoteRepository): Bitmap {
        // Native pages have no PDF background; paper + content render via the shared renderer.
        val scale = minOf(OVERLAY_SCALE, OVERLAY_MAX_DIMENSION / page.width, OVERLAY_MAX_DIMENSION / page.height)
            .coerceAtLeast(0.5f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val images = repository.loadImages(noteId, page)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            InkRenderer.page(canvas, page, background = null, images = images)
        } finally {
            images.values.forEach { it.recycle() }
        }
        return bitmap
    }

    /**
     * Pre-rotates a displayed-orientation overlay into unrotated content space so the
     * viewer's page rotation leaves it aligned. Returns the input when no rotation applies.
     */
    internal fun applySourceRotation(overlay: Bitmap, rotation: Int): Bitmap {
        val rot = ((rotation % 360) + 360) % 360
        if (rot == 0) return overlay
        val degrees = when (rot) {
            90 -> 270f
            180 -> 180f
            270 -> 90f
            else -> return overlay
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(overlay, 0, 0, overlay.width, overlay.height, matrix, true)
    }

    /**
     * Best-effort bookmark copy for identity exports. Pages line up 1:1 so explicit
     * page destinations can be remapped by number; anything unmappable is skipped.
     */
    internal fun copyOutline(source: PDDocument, out: PDDocument) {
        val srcOutline = source.documentCatalog.documentOutline ?: return
        val first = try { srcOutline.firstChild } catch (_: Exception) { return } ?: return
        val dstOutline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
        var child: PDOutlineItem? = first
        while (child != null) {
            copyOutlineItem(child, dstOutline, source, out)?.let { dstOutline.addLast(it) }
            child = try { child.nextSibling } catch (_: Exception) { null }
        }
        // Only keep the outline when at least one bookmark survived mapping.
        val kept = try { dstOutline.firstChild } catch (_: Exception) { null }
        if (kept != null) out.documentCatalog.documentOutline = dstOutline
    }

    private fun copyOutlineItem(
        src: PDOutlineItem,
        parent: PDOutlineNode,
        source: PDDocument,
        out: PDDocument
    ): PDOutlineItem? {
        return try {
            val dst = PDOutlineItem()
            dst.title = try { src.title } catch (_: Exception) { "" }.orEmpty()
            val destPage = outlineTargetPage(src, source)
            if (destPage != null) {
                if (destPage !in 0 until out.numberOfPages) return null
                val dest = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
                dest.page = out.getPage(destPage)
                dst.destination = dest
            } else {
                // Non-page bookmarks (e.g. URI) are skipped rather than mis-pointed;
                // children may still be mappable.
            }
            var child: PDOutlineItem? = try { src.firstChild } catch (_: Exception) { null }
            while (child != null) {
                copyOutlineItem(child, dst, source, out)?.let { dst.addLast(it) }
                child = try { child.nextSibling } catch (_: Exception) { null }
            }
            // Keep items with a destination or with kept children; drop empty shells.
            val hasChild = try { dst.firstChild != null } catch (_: Exception) { false }
            if (destPage != null || hasChild) dst else null
        } catch (_: Exception) { null }
    }

    private fun outlineTargetPage(item: PDOutlineItem, source: PDDocument): Int? {
        return try {
            val dest = try { item.destination } catch (_: Exception) { null }
                ?: (try { item.action } catch (_: Exception) { null } as? PDActionGoTo)?.let { action ->
                    try { action.destination } catch (_: Exception) { null }
                }
            val page = (dest as? PDPageDestination)?.retrievePageNumber() ?: -1
            if (page in 0 until source.numberOfPages) page else null
        } catch (_: Exception) { null }
    }
}
