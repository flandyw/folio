package com.folio.notes.pdf

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

    /** Keeps matching titles and their ancestors in the original reading order. */
    fun filter(entries: List<PdfOutlineEntry>, query: String): List<PdfOutlineEntry> {
        if (query.isEmpty()) return entries
        val visible = BooleanArray(entries.size)
        val ancestors = mutableListOf<Int>()
        entries.forEachIndexed { index, entry ->
            while (ancestors.isNotEmpty() && entries[ancestors.last()].depth >= entry.depth) {
                ancestors.removeAt(ancestors.lastIndex)
            }
            if (entry.title.contains(query, ignoreCase = true)) {
                visible[index] = true
                ancestors.forEach { visible[it] = true }
            }
            ancestors.add(index)
        }
        return entries.filterIndexed { index, _ -> visible[index] }
    }

    /** Drops blank titles and out-of-range jumps, so a damaged outline cannot break navigation. */
    fun sanitize(entries: List<PdfOutlineEntry>, pageCount: Int): List<PdfOutlineEntry> =
        entries.take(MAX_ENTRIES).map {
            it.copy(title = it.title.trim(), depth = it.depth.coerceIn(0, MAX_DEPTH))
        }.filter { it.title.isNotEmpty() && it.pageIndex in 0 until pageCount }
}
