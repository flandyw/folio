package com.folio.notes

/** One imported-PDF page's extracted text, in notebook page order. */
data class PdfPageText(val pageIndex: Int, val text: String)

/** One page containing the query: how often it appears, plus a readable excerpt. */
data class PdfSearchHit(val pageIndex: Int, val matchCount: Int, val snippet: String)

/**
 * Text search over an imported PDF's embedded text. Deliberately free of any PDF library so
 * matching stays JVM-testable; the repository supplies the per-page strings.
 */
object PdfSearch {
    /** Characters of context kept around the first match, so a hit reads like a sentence. */
    const val SNIPPET_BEFORE = 42
    const val SNIPPET_AFTER = 72

    private val whitespace = Regex("\\s+")

    /**
     * Pages containing [query], most matches first and page order breaking ties. Matching ignores
     * case and treats every run of whitespace — including a line break inside the PDF — as one
     * space, so "exam 1" also finds "exam" at the end of one line and "1" starting the next.
     */
    fun search(pages: List<PdfPageText>, query: String): List<PdfSearchHit> {
        val needle = collapse(query)
        if (needle.isEmpty()) return emptyList()
        return pages.mapNotNull { page ->
            val haystack = collapse(page.text)
            if (haystack.isEmpty()) return@mapNotNull null
            var count = 0
            var from = 0
            var first = -1
            while (true) {
                val at = haystack.indexOf(needle, from, ignoreCase = true)
                if (at < 0) break
                if (first < 0) first = at
                count++
                from = at + needle.length
            }
            if (count == 0) null else PdfSearchHit(page.pageIndex, count, snippet(haystack, first, needle.length))
        }.sortedWith(compareByDescending<PdfSearchHit> { it.matchCount }.thenBy { it.pageIndex })
    }

    /** One excerpt around the match at [at], with ellipses where the page text continues. */
    fun snippet(haystack: String, at: Int, length: Int): String {
        if (haystack.isEmpty() || at !in haystack.indices) return ""
        val start = (at - SNIPPET_BEFORE).coerceAtLeast(0)
        val end = (at + length + SNIPPET_AFTER).coerceAtMost(haystack.length)
        return (if (start > 0) "… " else "") + haystack.substring(start, end).trim() +
            (if (end < haystack.length) " …" else "")
    }

    /** Line breaks, tabs and doubled spaces become one space, so matches span layout freely. */
    fun collapse(text: String): String = whitespace.replace(text, " ").trim()
}
