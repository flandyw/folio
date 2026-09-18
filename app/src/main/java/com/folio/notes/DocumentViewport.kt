package com.folio.notes

import kotlin.math.abs
import kotlin.math.roundToInt

/** Document-space math, shared by pinch zoom and horizontal navigation. */
object DocumentViewport {
    /**
     * Keeps the page reachable without letting it leave the screen. Zoomed in, panning stops
     * when a page edge meets the viewport edge; zoomed out, the page may slide all the way to
     * either edge, so it can be pushed aside instead of being pinned to the centre. That is the
     * behaviour other note apps have: a pan is never swallowed, it just stops at the edge.
     */
    fun clampPan(pan: Float, documentWidth: Float, viewportWidth: Float): Float {
        val limit = abs(documentWidth - viewportWidth) / 2
        return pan.coerceIn(-limit, limit)
    }
    fun zoomPan(pan: Float, focusX: Float, viewportWidth: Float, newWidth: Float, ratio: Float): Float =
        clampPan((pan - (focusX - viewportWidth / 2)) * ratio + focusX - viewportWidth / 2, newWidth, viewportWidth)

    fun zoomScroll(offset: Int, focusY: Float, ratio: Float): Int =
        (offset * ratio + focusY * (ratio - 1)).roundToInt()

    /** How far through the document the viewport sits, as 0..1 across [pageCount] pages. */
    fun scrollProgress(firstIndex: Int, offsetIntoPage: Int, pageHeight: Int, pageCount: Int): Float {
        if (pageCount <= 0) return 0f
        val within = if (pageHeight <= 0) 0f else (offsetIntoPage.toFloat() / pageHeight).coerceIn(0f, 1f)
        return ((firstIndex + within) / pageCount).coerceIn(0f, 1f)
    }

    /** The page a drag to [fraction] of the fast-scroll track should land on. */
    fun pageAt(fraction: Float, pageCount: Int): Int =
        if (pageCount <= 0) 0 else (fraction.coerceIn(0f, 1f) * pageCount).toInt().coerceIn(0, pageCount - 1)

    /**
     * The page the fast-scroll label shows: the first visible page, pinned to the last
     * page once the list cannot scroll forward. Without the pin, a viewport showing
     * the last two pages at once still reports the second-last as first visible,
     * so the end reads "19 / 20" instead of "20 / 20".
     */
    fun displayedPage(firstIndex: Int, canScrollForward: Boolean, pageCount: Int): Int =
        if (pageCount <= 0) 0 else if (!canScrollForward) pageCount - 1 else firstIndex.coerceIn(0, pageCount - 1)

    /** The share of the track the thumb fills when [visible] of [pageCount] pages are on screen. */
    fun thumbFraction(visible: Int, pageCount: Int): Float =
        if (pageCount <= 0) 1f else (visible.toFloat() / pageCount).coerceIn(MIN_THUMB_SHARE, 1f)

    /** A thumb never shrinks past this, so a long document still leaves something to grab. */
    private const val MIN_THUMB_SHARE = 0.08f
}
