package com.folio.notes.pdf

import com.folio.notes.workspace.WorkspaceViewport

/**
 * Navigation maths for the read-only PDF reference pane, deliberately free of Compose and Android
 * types so page jumps and hit cycling stay JVM-testable. The pane supplies the view size and
 * the current page; everything else is arithmetic.
 */
object PdfReference {
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
