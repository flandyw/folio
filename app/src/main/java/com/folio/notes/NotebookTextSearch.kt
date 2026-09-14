package com.folio.notes

/**
 * Search over a notebook's own typed text boxes, mirroring the PDF search experience for
 * hand-typed notes: every page containing the words, most matches first, each with a readable
 * excerpt. Pure Kotlin so it stays unit-testable; the editor jumps to the hit page.
 */
object NotebookTextSearch {
    data class Hit(val pageIndex: Int, val matchCount: Int, val snippet: String, val boxId: String)

    private const val SNIPPET_RADIUS = 42

    fun search(pages: List<NotePage>, query: String): List<Hit> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val loweredTerms = terms.map { it.lowercase() }
        val hits = mutableListOf<Hit>()
        pages.forEachIndexed { pageIndex, page ->
            if (!page.loaded && page.texts.isEmpty()) return@forEachIndexed
            val loweredBoxes = page.texts.map { it.text.lowercase() to it }
            // Every term must appear somewhere on the page (across one or more boxes),
            // like a notebook search that narrows as words are added.
            if (loweredTerms.any { term -> loweredBoxes.none { (lowered, _) -> lowered.contains(term) } }) return@forEachIndexed
            var count = 0
            var best: Hit? = null
            page.texts.forEach { box ->
                val text = box.text
                if (text.isBlank()) return@forEach
                val lowered = text.lowercase()
                val boxCount = loweredTerms.sumOf { term -> countOccurrences(lowered, term) }
                if (boxCount == 0) return@forEach
                count += boxCount
                val firstTerm = loweredTerms.maxByOrNull { term -> countOccurrences(lowered, term) } ?: return@forEach
                val at = lowered.indexOf(firstTerm)
                if (best == null && at >= 0) {
                    best = Hit(pageIndex, 0, excerpt(text, at, firstTerm.length), box.id)
                }
            }
            if (count > 0) hits += (best?.copy(pageIndex = pageIndex, matchCount = count)
                ?: Hit(pageIndex, count, "", page.texts.firstOrNull()?.id.orEmpty()))
        }
        return hits.sortedByDescending { it.matchCount }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** A window of surrounding text around a match, with ends trimmed at word edges. */
    internal fun excerpt(text: String, at: Int, length: Int): String {
        val start = (at - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (at + length + SNIPPET_RADIUS).coerceAtMost(text.length)
        var snippet = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        if (start > 0) snippet = "…$snippet"
        if (end < text.length) snippet = "$snippet…"
        return snippet.take(160)
    }
}
