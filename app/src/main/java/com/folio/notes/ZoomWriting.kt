package com.folio.notes

/** Page-space window rules. The window is a view into a writing area, never its margins. */
object ZoomWriting {
    fun initial(area: WritingLane, visible: WritingLane, direction: WritingDirection,
                spacing: Float, baseline: Float? = null): WritingLane {
        val pitch = spacing.coerceIn(16f, 96f)
        val width = minOf(area.width, visible.width * .5f).coerceAtLeast(48f)
        val y = baseline ?: if (area.unbounded) visible.top + visible.height * .5f else area.top + pitch
        val left = if (direction == WritingDirection.LTR) area.left else area.right - width
        return WritingLane(left, y - pitch * 1.2f, left + width, y + pitch * 1.8f)
    }

    /** Reveal ahead with 30% overlap, stopping at the real answer margin. */
    fun reveal(window: WritingLane, area: WritingLane, direction: WritingDirection): Float {
        if (!window.width.isFinite() || window.width <= 0f || area.width <= window.width) return 0f
        return if (direction == WritingDirection.LTR)
            minOf(window.width * .7f, area.right - window.right).coerceAtLeast(0f)
        else -minOf(window.width * .7f, window.left - area.left).coerceAtLeast(0f)
    }

    /** Move the source frame without changing its scale or the answer area's bounds. */
    fun move(window: WritingLane, dx: Float, dy: Float, width: Float, height: Float, infinite: Boolean): WritingLane {
        val x = if (infinite) window.left + dx else (window.left + dx).coerceIn(0f, maxOf(0f, width - window.width))
        val y = if (infinite) window.top + dy else (window.top + dy).coerceIn(0f, maxOf(0f, height - window.height))
        return WritingLane(x, y, x + window.width, y + window.height)
    }

    /** Uniform resize keeps the pane's aspect ratio and prevents an unusably tiny source frame. */
    fun resize(window: WritingLane, x: Float, y: Float): WritingLane {
        val factor = maxOf((x - window.left) / window.width, (y - window.top) / window.height)
            .coerceIn(maxOf(24f / window.width, 16f / window.height), 8f)
        return window.copy(right = window.left + window.width * factor, bottom = window.top + window.height * factor)
    }
}
