package com.folio.notes

import org.json.JSONObject

/** Page coordinates, independent of device size. The target can be any page in this notebook. */
data class PeekAnchor(val pageId: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun encode() = JSONObject().put("page", pageId).put("left", left).put("top", top).put("right", right).put("bottom", bottom)
    fun resolve(pages: List<NotePage>): NotePage? = pages.find { it.id == pageId }
    companion object {
        fun decode(o: JSONObject?): PeekAnchor? = runCatching {
            requireNotNull(o)
            PeekAnchor(o.getString("page"), o.getDouble("left").toFloat(), o.getDouble("top").toFloat(),
                o.getDouble("right").toFloat(), o.getDouble("bottom").toFloat()).also {
                require(listOf(it.left, it.top, it.right, it.bottom).all { n -> n.isFinite() } && it.right > it.left && it.bottom > it.top)
            }
        }.getOrNull()
    }
}

data class ViewportSnapshot(val pageId: String, val viewport: WorkspaceViewport)
