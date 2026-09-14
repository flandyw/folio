package com.folio.notes

/** Where a tappable PDF link goes: a web address or another page of the same document. */
sealed interface PdfLinkTarget {
    data class Url(val uri: String) : PdfLinkTarget
    data class Page(val pageIndex: Int) : PdfLinkTarget
}

/** One tappable link on an imported-PDF page, in Folio page coordinates. */
data class PdfLink(
    val pageIndex: Int,
    val x: Float, val y: Float, val width: Float, val height: Float,
    val target: PdfLinkTarget
) {
    /** True with a finger-sized margin, so small footnote links stay tappable. */
    fun contains(px: Float, py: Float, slop: Float = TAP_SLOP): Boolean =
        px >= x - slop && px <= x + width + slop && py >= y - slop && py <= y + height + slop

    companion object {
        const val TAP_SLOP = 6f
    }
}

/**
 * Link geometry without any PDF library, so mapping stays JVM-testable; the repository supplies
 * the raw annotation rectangles.
 */
object PdfLinks {
    /** Link schemes opened in a browser or mail app; anything else (notably javascript:) is dropped. */
    private val allowedSchemes = setOf("http", "https", "mailto")

    /** The trimmed URL when [raw] uses an openable scheme, or null when the link must be ignored. */
    fun urlTarget(raw: String?): String? {
        val uri = raw?.trim().orEmpty()
        if (uri.isEmpty()) return null
        val scheme = uri.substringBefore(':').lowercase()
        return if (scheme in allowedSchemes && uri.length > scheme.length + 1) uri else null
    }

    /**
     * Maps a link rectangle from PDF user space (origin bottom-left, y-up, measured from the
     * page's crop origin) onto Folio page coordinates (origin top-left, y-down). Returns null for
     * degenerate or fully off-page rectangles, or when any dimension is not positive.
     */
    fun mapLink(
        pageIndex: Int, x0: Float, y0: Float, x1: Float, y1: Float,
        cropX: Float, cropY: Float, cropW: Float, cropH: Float,
        pageW: Float, pageH: Float, target: PdfLinkTarget
    ): PdfLink? {
        if (cropW <= 0f || cropH <= 0f || pageW <= 0f || pageH <= 0f) return null
        val left = minOf(x0, x1) - cropX
        val right = maxOf(x0, x1) - cropX
        val bottom = minOf(y0, y1) - cropY
        val top = maxOf(y0, y1) - cropY
        // A rectangle fully outside the visible crop is not tappable.
        if (right <= 0f || left >= cropW || top <= 0f || bottom >= cropH) return null
        val cl = left.coerceIn(0f, cropW)
        val cr = right.coerceIn(0f, cropW)
        val cb = bottom.coerceIn(0f, cropH)
        val ct = top.coerceIn(0f, cropH)
        if (cr - cl <= 0.5f || ct - cb <= 0.5f) return null
        return PdfLink(
            pageIndex = pageIndex,
            x = cl / cropW * pageW,
            y = (cropH - ct) / cropH * pageH,
            width = (cr - cl) / cropW * pageW,
            height = (ct - cb) / cropH * pageH,
            target = target
        )
    }
}
