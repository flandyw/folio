package com.folio.notes

/**
 * Search over a notebook's own typed text boxes, mirroring the PDF search experience for
 * hand-typed notes: every page containing the words, most matches first, each with a readable
 * excerpt. Pure Kotlin so it stays unit-testable; the editor jumps to the hit page.
 */
object NotebookTextSearch {
    data class Hit(val pageIndex: Int, val matchCount: Int, val snippet: String, val boxId: String)

    private const val SNIPPET_RADIUS = 42
    private val whitespaceSplit = Regex("\\s+")
    private val whitespaceCollapse = Regex("\\s+")

    fun search(pages: List<NotePage>, query: String): List<Hit> {
        val terms = query.trim().split(whitespaceSplit).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val loweredTerms = terms.map { it.lowercase() }
        val hits = mutableListOf<Hit>()
        pages.forEachIndexed { pageIndex, page ->
            if (!page.loaded && page.texts.isEmpty()) return@forEachIndexed
            if (page.texts.isEmpty()) return@forEachIndexed
            // Lowercase once and reuse for both the page-level gate and per-box counting.
            val loweredBoxes = page.texts.map { it.text.lowercase() to it }
            // Every term must appear somewhere on the page (across one or more boxes),
            // like a notebook search that narrows as words are added.
            if (loweredTerms.any { term -> loweredBoxes.none { (lowered, _) -> lowered.contains(term) } }) return@forEachIndexed
            var count = 0
            var best: Hit? = null
            var bestScore = 0
            loweredBoxes.forEach { (lowered, box) ->
                if (lowered.isBlank()) return@forEach
                // Single pass: count every term once, tracking the strongest term for the snippet.
                var boxCount = 0
                var bestTerm: String? = null
                var bestTermCount = 0
                var bestAt = -1
                for (term in loweredTerms) {
                    val c = countOccurrences(lowered, term)
                    if (c == 0) continue
                    boxCount += c
                    if (c > bestTermCount) {
                        bestTermCount = c
                        bestTerm = term
                        bestAt = lowered.indexOf(term)
                    }
                }
                if (boxCount == 0) return@forEach
                count += boxCount
                if (best == null && bestTerm != null && bestAt >= 0 && boxCount > bestScore) {
                    bestScore = boxCount
                    best = Hit(pageIndex, 0, excerpt(box.text, bestAt, bestTerm.length), box.id)
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
        var snippet = text.substring(start, end).replace(whitespaceCollapse, " ").trim()
        if (start > 0) snippet = "…$snippet"
        if (end < text.length) snippet = "$snippet…"
        return snippet.take(160)
    }
}
