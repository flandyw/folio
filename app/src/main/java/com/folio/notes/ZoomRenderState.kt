package com.folio.notes

/** Detects both native camera zoom and page resizing by the Compose document container. */
internal class ZoomRenderState(private val settleMillis: Long = 90L) {
    private var scale: Float? = null
    private var lastMotion: Long? = null

    fun usePreview(currentScale: Float, navigating: Boolean, now: Long): Boolean {
        if (navigating || (scale != null && scale != currentScale)) lastMotion = now
        scale = currentScale
        return lastMotion?.let { now - it < settleMillis } ?: false
    }

    fun reset() { scale = null; lastMotion = null }
}
