package com.folio.notes.storage

import com.folio.notes.model.NotePage
import com.folio.notes.model.withLoadedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Search over a notebook's own typed text boxes, mirroring the PDF search experience for
 * hand-typed notes: every page containing the words, most matches first, each with a readable
 * excerpt. Pure Kotlin so it stays unit-testable; the editor jumps to the hit page.
 */
object NotebookTextSearch {
    data class Hit(val pageIndex: Int, val matchCount: Int, val snippet: String, val boxId: String)

    private const val SNIPPET_RADIUS = 42
    private val whitespace = Regex("[\\s\\p{Z}]+")

    /** OCR and annotated-page selection need the ink and pictures as well as typed words. */
    internal suspend fun loadPages(pages: List<NotePage>, load: suspend (NotePage) -> NotePage): List<NotePage> =
        pages.map { summary ->
            currentCoroutineContext().ensureActive()
            if (summary.loaded) summary else try {
                summary.withLoadedContent(load(summary))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { summary }
        }

    fun search(pages: List<NotePage>, query: String): List<Hit> {
        val terms = query.trim().split(whitespace).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        pages.forEachIndexed { pageIndex, page ->
            if (!page.loaded && page.texts.isEmpty()) return@forEachIndexed
            if (page.texts.isEmpty()) return@forEachIndexed
            // Every term must appear somewhere on the page (across one or more boxes),
            // like a notebook search that narrows as words are added.
            if (terms.any { term -> page.texts.none { it.text.contains(term, ignoreCase = true) } }) return@forEachIndexed
            var count = 0
            var best: Hit? = null
            var bestScore = 0
            page.texts.forEach { box ->
                if (box.text.isBlank()) return@forEach
                // Single pass: count every term once, tracking the strongest term for the snippet.
                var boxCount = 0
                var bestTerm: String? = null
                var bestTermCount = 0
                var bestAt = -1
                for (term in terms) {
                    val c = countOccurrences(box.text, term)
                    if (c == 0) continue
                    boxCount += c
                    if (c > bestTermCount) {
                        bestTermCount = c
                        bestTerm = term
                        bestAt = box.text.indexOf(term, ignoreCase = true)
                    }
                }
                if (boxCount == 0) return@forEach
                count += boxCount
                if (bestTerm != null && bestAt >= 0 && boxCount > bestScore) {
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
            val at = haystack.indexOf(needle, from, ignoreCase = true)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** A window of surrounding text around a match, with ends trimmed at word edges. */
    internal fun excerpt(text: String, at: Int, length: Int): String {
        val start = (at - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (at + length + SNIPPET_RADIUS).coerceAtMost(text.length)
        var snippet = text.substring(start, end).replace(whitespace, " ").trim()
        if (start > 0) snippet = "…$snippet"
        if (end < text.length) snippet = "$snippet…"
        return snippet.take(160)
    }
}
