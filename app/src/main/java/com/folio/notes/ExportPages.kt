package com.folio.notes

/** Which file a page-range export produces. PNG with one page is a single image; with several it is a zip. */
enum class PageExportFormat { PDF, PNG }

data class PageExportRequest(val note: Notebook, val indices: List<Int>, val format: PageExportFormat)

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

/** File-name sanitising shared with the single-page export path. */
object NotebookFilename {
    private val unsafe = Regex("[^\\p{L}\\p{N} ._-]")
    fun sanitize(title: String): String = title.replace(unsafe, "_").take(80).ifBlank { "Notebook" }
}
