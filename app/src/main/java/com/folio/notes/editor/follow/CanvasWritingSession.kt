package com.folio.notes.editor.follow


/**
 * Explicit, transient permission to move an infinite canvas while composing prose. It starts
 * [placed] = false: the column's trailing margin comes from the starting viewport, and the first
 * pen stroke sets its leading margin and top ([placedAt]), so returns go back to where the
 * response actually began rather than to the screen edge.
 */
data class CanvasWritingSession(
    val column: WritingLane,
    val direction: WritingDirection,
    val automaticReturn: Boolean = false,
    val placed: Boolean = true,
) {
    val startX get() = if (direction == WritingDirection.LTR) column.left else column.right
    fun preferences(base: FollowPreferences) = base.copy(
        mode = FollowMode.TEXT, direction = direction, automaticReturn = automaticReturn,
        autoSwitchAreas = false,
    )

    /**
     * The column begun by the first stroke [box]: its leading edge, and a top half a line above
     * it so taller letters on the first line still count as inside. A start near the trailing
     * margin still leaves at least [MIN_WIDTH] of the original width to write in, extending past
     * the old margin; sideways following then reveals it.
     */
    fun placedAt(box: WritingLane, spacing: Float): CanvasWritingSession {
        if (placed || !box.left.isFinite() || !box.right.isFinite() || !box.top.isFinite()) return this
        val top = box.top - spacing.coerceIn(16f, 96f) * .5f
        val room = maxOf(column.width * MIN_WIDTH, FollowNavigation.MIN_LANE_UNITS)
        val placedColumn = if (direction == WritingDirection.LTR)
            WritingLane(box.left, top, maxOf(column.right, box.left + room), Float.MAX_VALUE)
            else WritingLane(minOf(column.left, box.right - room), top, box.right, Float.MAX_VALUE)
        return copy(column = placedColumn, placed = true)
    }

    /** Placing the column continues the same response, so it keeps the line already being written. */
    fun continues(previous: CanvasWritingSession?) = previous != null && !previous.placed && placed &&
        direction == previous.direction && automaticReturn == previous.automaticReturn

    /**
     * The visible left edge, in canvas units, that keeps the response readable. A column that
     * fits on screen stays wholly in view with the least movement, so writing across it and
     * returning never slide the view sideways. A column wider than the view (after zooming in)
     * takes [wantedLeft], but never shows more than a margin beyond either column edge.
     */
    fun framedLeft(viewLeft: Float, viewWidth: Float, wantedLeft: Float = viewLeft): Float {
        if (!viewLeft.isFinite() || !viewWidth.isFinite() || viewWidth <= 0f) return viewLeft
        val pad = column.width * FRAME_PAD
        val showsTrailing = column.right + pad - viewWidth
        val showsLeading = column.left - pad
        if (showsTrailing <= showsLeading) return viewLeft.coerceIn(showsTrailing, showsLeading)
        return (if (wantedLeft.isFinite()) wantedLeft else viewLeft).coerceIn(showsLeading, showsTrailing)
    }

    /** Survives Activity recreation (rotation, resize, split screen) as plain text. */
    fun encode(): String = listOf(column.left, column.top, column.right, column.bottom,
        direction.name, automaticReturn, placed).joinToString(",")

    companion object {
        /** Breathing room kept beside the column edges when framing it, as a fraction of its width. */
        const val FRAME_PAD = .04f
        /** The least of the starting width a column keeps, however close to the margin it begins. */
        const val MIN_WIDTH = .5f

        fun decode(raw: String?): CanvasWritingSession? = runCatching {
            val parts = raw!!.split(",")
            val (l, t, r, b) = parts.take(4).map { it.toFloat() }
            val column = WritingLane(l, t, r, b)
            // Older saved state has no placement flag; its column was already final.
            if (!l.isFinite() || !r.isFinite() || column.width <= 0f || parts.size !in 6..7) null
            else CanvasWritingSession(column, WritingDirection.valueOf(parts[4]), parts[5].toBooleanStrict(),
                parts.getOrNull(6)?.toBooleanStrict() ?: true)
        }.getOrNull()

        /** A new, unplaced response; it takes the writer's own automatic-return choice. */
        fun start(viewport: WritingLane, preferences: FollowPreferences): CanvasWritingSession? {
            if (!viewport.left.isFinite() || !viewport.right.isFinite() ||
                !viewport.width.isFinite() || viewport.width <= 0f) return null
            return CanvasWritingSession(FollowNavigation.infiniteRegion(viewport, preferences.direction,
                endMargin = preferences.endMargin), preferences.direction, preferences.automaticReturn, placed = false)
        }
    }
}
