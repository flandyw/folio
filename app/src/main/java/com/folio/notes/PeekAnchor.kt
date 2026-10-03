package com.folio.notes

import org.json.JSONObject

/**
 * The one view a notebook keeps for peeking: a page and a rectangle on it, in page units, so it
 * frames the same content on any screen, orientation or split width.
 */
data class PeekAnchor(val pageId: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun encode(): JSONObject = JSONObject().put("page", pageId).put("left", left).put("top", top)
        .put("right", right).put("bottom", bottom)

    fun page(pages: List<NotePage>): NotePage? = pages.find { it.id == pageId }

    /**
     * Clamped to a fixed page's paper, so a view saved partly off the page still frames its ink;
     * a view that misses the paper entirely falls back to the whole page.
     */
    fun fittedTo(page: NotePage): PeekAnchor {
        if (page.infinite || page.width <= 0f || page.height <= 0f) return this
        val l = left.coerceIn(0f, page.width); val r = right.coerceIn(0f, page.width)
        val t = top.coerceIn(0f, page.height); val b = bottom.coerceIn(0f, page.height)
        return if (r - l >= 1f && b - t >= 1f) copy(left = l, top = t, right = r, bottom = b)
            else PeekAnchor(page.id, 0f, 0f, page.width, page.height)
    }

    companion object {
        const val KEY = "peekAnchor"

        fun decode(o: JSONObject?): PeekAnchor? = runCatching {
            requireNotNull(o)
            PeekAnchor(o.getString("page"), o.getDouble("left").toFloat(), o.getDouble("top").toFloat(),
                o.getDouble("right").toFloat(), o.getDouble("bottom").toFloat()).also {
                require(listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite) && it.right > it.left && it.bottom > it.top)
            }
        }.getOrNull()

        /**
         * The notebook's anchor. Versions before the notebook-level field stored it on one of the
         * pages instead; the first of those that still points at a page in this notebook is used.
         */
        fun decodeNotebook(notebook: JSONObject, pages: List<NotePage>, pageObjects: List<JSONObject>): PeekAnchor? =
            (sequenceOf(notebook.optJSONObject(KEY)) + pageObjects.asSequence().map { it.optJSONObject(KEY) })
                .mapNotNull(::decode).firstOrNull { anchor -> pages.any { it.id == anchor.pageId } }

        /** The whole of [page], for a page that has no better framing. */
        fun wholePage(page: NotePage) = PeekAnchor(page.id, 0f, 0f, page.width, page.height)
    }
}

/** The notebook's peek view, if the page it frames still exists. */
val Notebook.livePeekAnchor: PeekAnchor? get() = peekAnchor?.let { anchor -> anchor.page(pages)?.let(anchor::fittedTo) }

/** Peeking is either a held press that ends on release, or a tap that keeps the view open. */
enum class PeekMode { HELD, LATCHED }
