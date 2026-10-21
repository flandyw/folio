package com.folio.notes.pdf

import com.folio.notes.workspace.WorkspaceViewport

/**
 * Navigation maths for the read-only PDF reference pane, deliberately free of Compose and Android
 * types so page jumps and hit cycling stay JVM-testable. The pane supplies the view size and
 * the current page; everything else is arithmetic.
 */
object PdfReference {
    private val ordinalWords = mapOf(
        "first" to 0, "start" to 0, "f" to 0, "one" to 1,
        "last" to -1, "end" to -1, "final" to -1, "l" to -1
    )
    private val pageWords = Regex("^(?:p|pg|pp|page)?\\.?\\s*(\\d{1,5})$")
    private val signedSteps = Regex("^([+-])\\s*(\\d{1,4})$")

    /**
     * The page a typed jump asks for, counted from [currentIndex] in a document of [pageCount]
     * pages. Accepts a number ("12", "p 12", "page.12"), a relative step ("+3", "-2") and the
     * ends ("first", "last"). Out-of-range numbers clamp to the nearest page; anything else is
     * null, so an unparseable entry simply does not move the pane.
     */
    fun parseJump(raw: String, currentIndex: Int, pageCount: Int): Int? {
        if (pageCount <= 0) return null
        // A pasted number often carries a non-breaking space; treat it like an ordinary one.
        val text = raw.trim().lowercase().replace('\u00A0', ' ')
        if (text.isEmpty()) return null
        ordinalWords[text]?.let { word ->
            return if (word < 0) pageCount - 1 else (word - 1).coerceIn(0, pageCount - 1)
        }
        val step = signedSteps.matchEntire(text)
        if (step != null) {
            val amount = step.groupValues[2].toIntOrNull() ?: return null
            val delta = if (step.groupValues[1] == "-") -amount else amount
            return (currentIndex.coerceIn(0, pageCount - 1) + delta).coerceIn(0, pageCount - 1)
        }
        val number = pageWords.matchEntire(text) ?: return null
        val page = number.groupValues[1].toIntOrNull() ?: return null
        return (page - 1).coerceIn(0, pageCount - 1)
    }

    /** A hint under the jump field, so "last" and "+3" are discoverable without a manual. */
    fun jumpHint(currentIndex: Int, pageCount: Int): String =
        "On ${currentIndex + 1} of $pageCount — try 12, p 12, +3, -2 or last."

    /** The hit pages in reading order, so "next match" walks the document rather than the ranking. */
    fun hitPages(hits: List<PdfSearchHit>): List<Int> = hits.map { it.pageIndex }.distinct().sorted()

    /**
     * The next hit page strictly after (or before) [currentIndex] among [pages], wrapping at
     * either end so walking the document never dead-ends. Null with no hits, so the caller can
     * leave the pane where it is.
     */
    fun nextHit(currentIndex: Int, pages: List<Int>, forward: Boolean): Int? {
        val ordered = pages.distinct().sorted()
        if (ordered.isEmpty()) return null
        return if (forward) ordered.firstOrNull { it > currentIndex } ?: ordered.first()
        else ordered.lastOrNull { it < currentIndex } ?: ordered.last()
    }

    /** The status line under a reference page: its place in the document and in the source PDF. */
    fun pageLabel(pageIndex: Int, pageCount: Int, pdfIndex: Int?): String {
        val place = "${pageIndex + 1} / $pageCount"
        return if (pdfIndex != null) "$place · PDF p.${pdfIndex + 1}" else place
    }

    /**
     * The frame the reference keeps when it turns a page: a deliberate zoom survives (fit width
     * should not snap back on every turn) with the pan re-centred, while a reset pane stays reset.
     */
    fun carriedFrame(current: WorkspaceViewport): WorkspaceViewport =
        if (current.canvasZoom > 1.02f) WorkspaceViewport(canvasZoom = current.canvasZoom) else WorkspaceViewport()
}
