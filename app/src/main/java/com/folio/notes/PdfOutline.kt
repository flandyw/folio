package com.folio.notes

/** One bookmark of an imported PDF: a titled jump to a page, nested by [depth]. */
data class PdfOutlineEntry(val title: String, val pageIndex: Int, val depth: Int)

/**
 * Bookmark lists without any PDF library, so cleanup stays JVM-testable; the repository supplies
 * the raw walked entries.
 */
object PdfOutline {
    /** Nesting deeper than this renders flat; totals are capped against malformed files. */
    const val MAX_DEPTH = 8
    const val MAX_ENTRIES = 2000

    /** Drops blank titles and out-of-range jumps, so a damaged outline cannot break navigation. */
    fun sanitize(entries: List<PdfOutlineEntry>, pageCount: Int): List<PdfOutlineEntry> =
        entries.take(MAX_ENTRIES).map {
            it.copy(title = it.title.trim(), depth = it.depth.coerceIn(0, MAX_DEPTH))
        }.filter { it.title.isNotEmpty() && it.pageIndex in 0 until pageCount }
}
