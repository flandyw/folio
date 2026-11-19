package com.folio.notes.pdf

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
     * Pages containing [query], most matches first and page order breaking ties. Matching treats
     * a run of whitespace as one space, including line breaks. Whole-word searches require a
     * boundary on both sides of the match; punctuation such as a hyphen separates words.
     */
    fun search(
        pages: List<PdfPageText>,
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): List<PdfSearchHit> {
        val needle = collapse(query)
        if (needle.isEmpty()) return emptyList()
        return pages.mapNotNull { page ->
            val haystack = collapse(page.text)
            if (haystack.isEmpty()) return@mapNotNull null
            var count = 0
            var from = 0
            var first = -1
            while (true) {
                val at = haystack.indexOf(needle, from, ignoreCase = !matchCase)
                if (at < 0) break
                val end = at + needle.length
                if (wholeWord && ((at > 0 && isWordCharacter(haystack.codePointBefore(at))) ||
                        (end < haystack.length && isWordCharacter(haystack.codePointAt(end))))) {
                    from = at + 1
                    continue
                }
                if (first < 0) first = at
                count++
                from = end
            }
            if (count == 0) null else PdfSearchHit(page.pageIndex, count, snippet(haystack, first, needle.length))
        }.sortedWith(compareByDescending<PdfSearchHit> { it.matchCount }.thenBy { it.pageIndex })
    }

    private fun isWordCharacter(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) || when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(), Character.CONNECTOR_PUNCTUATION.toInt() -> true
            else -> false
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
