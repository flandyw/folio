package com.folio.notes

/** How a read-only reference page should fill its pane. */
enum class PdfFit { PAGE, WIDTH, ACTUAL }

/**
 * Navigation maths for the read-only PDF reference pane, deliberately free of Compose and Android
 * types so zoom, page jumps and hit cycling stay JVM-testable. The pane supplies the view size and
 * the current page; everything else is arithmetic.
 */
object PdfReference {
    /** Zoom bounds shared with the camera, so the buttons can never fight the pinch gesture. */
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 6f
    /** One notch of the zoom in/out buttons. */
    const val ZOOM_STEP = 1.25f
    /** Fraction of the pane a fitted page is kept away from, so the frame is never flush. */
    const val FIT_MARGIN = 0.03f

    private val ordinalWords = mapOf(
        "first" to 0, "start" to 0, "f" to 0, "one" to 1,
        "last" to -1, "end" to -1, "final" to -1, "l" to -1
    )
    private val pageWords = Regex("^(?:p|pg|pp|page)?\\.?\\s*(\\d{1,5})$")
    private val signedSteps = Regex("^([+-])\\s*(\\d{1,4})$")

    /** A finite zoom inside the camera's own range; anything else reads as unzoomed. */
    fun clampZoom(zoom: Float): Float = if (zoom.isFinite()) zoom.coerceIn(MIN_ZOOM, MAX_ZOOM) else 1f

    /** Zoom after [steps] notches — negative zooms out, zero leaves the page alone. */
    fun zoomStep(zoom: Float, steps: Int): Float {
        if (steps == 0) return clampZoom(zoom)
        return clampZoom(zoom * Math.pow(ZOOM_STEP.toDouble(), steps.toDouble()).toFloat())
    }

    /**
     * Scale that frames a [pageWidth] × [pageHeight] page inside a [viewWidth] × [viewHeight] pane:
     * the whole page with a margin, the full page width, or true size (1:1).
     */
    fun fitZoom(pageWidth: Float, pageHeight: Float, viewWidth: Float, viewHeight: Float, fit: PdfFit): Float {
        if (listOf(pageWidth, pageHeight, viewWidth, viewHeight).any { !it.isFinite() } ||
            pageWidth <= 0f || pageHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return 1f
        return clampZoom(
            when (fit) {
                PdfFit.PAGE -> minOf(viewWidth * (1f - 2 * FIT_MARGIN) / pageWidth, viewHeight * (1f - 2 * FIT_MARGIN) / pageHeight)
                PdfFit.WIDTH -> viewWidth * (1f - FIT_MARGIN) / pageWidth
                PdfFit.ACTUAL -> 1f
            }
        )
    }

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
