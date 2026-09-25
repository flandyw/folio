package com.folio.notes

/** Which file a page-range export produces. PNG with one page is a single image; with several it is a zip. */
enum class PageExportFormat { PDF, PNG }

/**
 * How a PDF export treats an imported source document.
 *
 * PRESERVE keeps the original page content (searchable text, vectors, links where safely
 * possible) and adds Folio annotations as an overlay, so quality and structure survive.
 * RASTERISE flattens each composed page (source + Folio ink/highlighter/shapes/text/images)
 * into a single image, maximising downstream compatibility with weak renderers, parsers
 * and document-analysis tools at the cost of selectable text and larger files.
 */
enum class PdfExportMode {
    PRESERVE, RASTERISE;
    companion object {
        fun safeValueOf(name: String?): PdfExportMode =
            try { valueOf(name ?: "") } catch (_: Exception) { PRESERVE }
    }
}

data class PageExportRequest(
    val note: Notebook,
    val indices: List<Int>,
    val format: PageExportFormat,
    val pdfMode: PdfExportMode = PdfExportMode.PRESERVE
)

/** True when the notebook holds at least one imported-PDF page. */
fun hasPdfPages(note: Notebook): Boolean = note.pages.any { it.pdfIndex != null }

/** True when the PDF-quality choice should be offered for this notebook. */
fun shouldShowPdfQuality(note: Notebook): Boolean = hasPdfPages(note)

/** True when the page carries visible Folio content worth overlaying. */
fun pageHasAnnotations(page: NotePage): Boolean =
    page.strokes.isNotEmpty() || page.images.isNotEmpty() || page.texts.any { it.text.isNotBlank() }

/**
 * True when the export is every notebook page in source order with no native pages,
 * so bookmarks and document structures can be carried over 1:1. Anything selective,
 * reordered or mixed must skip outlines rather than mis-point them.
 */
fun isIdentityPdfExport(note: Notebook, selected: List<Int>, sourcePages: Int): Boolean {
    if (selected.size != note.pages.size) return false
    if (selected.size != sourcePages) return false
    if (selected != note.pages.indices.toList()) return false
    return note.pages.mapIndexed { index, page -> page.pdfIndex == index }.all { it }
}

/**
 * Keeps only valid page indices, de-duplicated and in reading order, so an export can never
 * repeat, reorder or run past the end of the notebook.
 */
fun normalizeExportIndices(raw: Collection<Int>, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    return raw.filter { it in 0 until pageCount }.distinct().sorted()
}

/**
 * Parses a human page range like "1-3, 5" (1-based, as shown in the UI) into 0-based indices.
 * Unknown text is ignored; reversed ranges are read low-to-high; everything is clamped to the
 * notebook so "1-99" on a 3-page notebook simply means all three pages.
 */
fun parsePageRange(input: String, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val found = linkedSetOf<Int>()
    input.split(',', ' ', ';', '\n', '\t').forEach { token ->
        val part = token.trim()
        if (part.isEmpty()) return@forEach
        if ('-' in part || '–' in part || '—' in part) {
            val bounds = part.split('-', '–', '—').map { it.trim() }
            if (bounds.size != 2) return@forEach
            val start = bounds[0].toIntOrNull()?.minus(1) ?: return@forEach
            val end = bounds[1].toIntOrNull()?.minus(1) ?: return@forEach
            val low = minOf(start, end).coerceIn(0, pageCount - 1)
            val high = maxOf(start, end).coerceIn(0, pageCount - 1)
            for (index in low..high) found += index
        } else {
            part.toIntOrNull()?.minus(1)?.let { if (it in 0 until pageCount) found += it }
        }
    }
    return found.sorted()
}

/** Compacts 0-based indices back to a short label like "1–3, 5" for the export dialog. */
fun formatExportSelection(indices: List<Int>): String {
    if (indices.isEmpty()) return "No pages"
    val sorted = indices.distinct().sorted()
    val ranges = mutableListOf<String>()
    var start = sorted[0]
    var end = sorted[0]
    for (index in sorted.drop(1)) {
        if (index == end + 1) end = index
        else {
            ranges += rangeLabel(start, end)
            start = index
            end = index
        }
    }
    ranges += rangeLabel(start, end)
    return ranges.joinToString(", ")
}

private fun rangeLabel(start: Int, end: Int): String =
    if (start == end) "${start + 1}" else "${start + 1}–${end + 1}"

/** Suggested file name for a selective export, without a directory. */
fun selectiveExportFilename(note: Notebook, indices: List<Int>, format: PageExportFormat): String {
    val base = NotebookFilename.sanitize(note.title)
    return when (format) {
        PageExportFormat.PDF -> if (indices.size == 1) "$base-p${indices.first() + 1}.pdf" else "$base-pages.pdf"
        PageExportFormat.PNG -> if (indices.size == 1) "$base-p${indices.first() + 1}.png" else "$base-pages.zip"
    }
}

/** Subfolder under Pictures/ where single-page PNGs are saved so they land in the gallery. */
const val GALLERY_PICTURES_SUBFOLDER = "Folio"

/** Display name for a single page saved to the gallery (camera roll). */
fun galleryPngFilename(note: Notebook, pageIndex: Int): String =
    selectiveExportFilename(note, listOf(pageIndex), PageExportFormat.PNG)

/** Relative MediaStore path for gallery saves, e.g. Pictures/Folio. */
fun galleryRelativePath(): String = "Pictures/$GALLERY_PICTURES_SUBFOLDER"

/** MIME type used when natively sharing a selective export. Multi-PNG shares as images, not a zip. */
fun exportShareMimeType(format: PageExportFormat): String = when (format) {
    PageExportFormat.PDF -> "application/pdf"
    PageExportFormat.PNG -> "image/png"
}

/** True when sharing produces several files (one PNG per page) needing ACTION_SEND_MULTIPLE. */
fun exportShareUsesMultipleUris(format: PageExportFormat, pageCount: Int): Boolean =
    format == PageExportFormat.PNG && pageCount > 1

/**
 * File names for a native share, without a directory. A PDF or single PNG shares as one file;
 * several PNGs share as one image per page so receiving apps get pictures, not a zip.
 */
fun selectiveShareFilenames(note: Notebook, indices: List<Int>, format: PageExportFormat): List<String> {
    val normalized = normalizeExportIndices(indices, note.pages.size)
    if (normalized.isEmpty()) return emptyList()
    val base = NotebookFilename.sanitize(note.title)
    return when (format) {
        PageExportFormat.PDF -> listOf(
            if (normalized.size == 1) "$base-p${normalized.first() + 1}.pdf" else "$base-pages.pdf"
        )
        PageExportFormat.PNG -> normalized.map { "$base-p${it + 1}.png" }
    }
}

/** Inserts a timestamp before the extension so repeated shares never collide in the cache dir. */
fun uniqueShareFilename(filename: String, timestamp: Long = System.currentTimeMillis()): String {
    val dot = filename.lastIndexOf('.')
    return if (dot <= 0) "$filename-$timestamp"
    else "${filename.substring(0, dot)}-$timestamp${filename.substring(dot)}"
}

/** Title for the Android sharesheet, describing what is being sent. */
fun shareExportChooserTitle(indices: List<Int>, format: PageExportFormat): String {
    val count = indices.distinct().size
    return when (format) {
        PageExportFormat.PDF -> if (count == 1) "Share page as PDF" else "Share $count pages as PDF"
        PageExportFormat.PNG -> if (count == 1) "Share page as image" else "Share $count images"
    }
}

/** Cache dir holding temporary share files, created on demand. */
fun exportCacheDir(cacheDir: java.io.File): java.io.File =
    java.io.File(cacheDir, "exports").apply { mkdirs() }

/**
 * Deletes cached share files (and share sub-folders) older than [maxAgeMs].
 * Returns how many entries were removed so callers can ignore the result.
 */
fun pruneExportCache(dir: java.io.File, now: Long = System.currentTimeMillis(), maxAgeMs: Long = 86_400_000): Int {
    val entries = dir.listFiles() ?: return 0
    var removed = 0
    entries.forEach { entry ->
        if (now - entry.lastModified() > maxAgeMs) {
            if (entry.isDirectory) {
                entry.listFiles()?.forEach { it.delete() }
                if (entry.delete()) removed++
            } else if (entry.delete()) removed++
        }
    }
    return removed
}

/** File-name sanitising shared with the single-page export path. */
object NotebookFilename {
    private val unsafe = Regex("[^\\p{L}\\p{N} ._-]")
    fun sanitize(title: String): String = title.replace(unsafe, "_").take(80).ifBlank { "Notebook" }
}
