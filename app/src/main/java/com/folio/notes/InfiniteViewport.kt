package com.folio.notes

/** Screen-space translation, with zoom anchored to the fingers rather than the canvas origin. */
class InfiniteViewport {
    var x = 0f; private set
    var y = 0f; private set
    var zoom = 1f; private set

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
