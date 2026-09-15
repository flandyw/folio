package com.folio.notes

/** Screen-space translation, with zoom anchored to the fingers rather than the canvas origin. */
class InfiniteViewport {
    var x = 0f; private set
    var y = 0f; private set
    var zoom = 1f; private set

    fun centerOn(worldX: Float, worldY: Float, width: Float, height: Float) {
        if (!worldX.isFinite() || !worldY.isFinite() || width <= 0f || height <= 0f) return
        x = width / 2f - worldX * zoom
        y = height / 2f - worldY * zoom
    }

    fun fit(left: Float, top: Float, right: Float, bottom: Float, width: Float, height: Float) {
        if (listOf(left, top, right, bottom, width, height).any { !it.isFinite() } ||
            right <= left || bottom <= top || width <= 0f || height <= 0f) return
        zoom = minOf(width * .8f / (right - left), height * .8f / (bottom - top)).coerceIn(.1f, 8f)
        centerOn((left + right) / 2f, (top + bottom) / 2f, width, height)
    }

    fun reset() { x = 0f; y = 0f; zoom = 1f }
    fun pan(dx: Float, dy: Float) {
        if (dx.isFinite() && dy.isFinite()) { x += dx; y += dy }
    }
    fun scaleBy(factor: Float, focusX: Float, focusY: Float) {
        if (!factor.isFinite() || factor <= 0f || !focusX.isFinite() || !focusY.isFinite()) return
        val next = (zoom * factor).coerceIn(.1f, 8f)
        val ratio = next / zoom
        x = focusX - (focusX - x) * ratio
        y = focusY - (focusY - y) * ratio
        zoom = next
    }
}
