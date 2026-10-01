package com.folio.notes

import kotlin.math.roundToInt

/** Window coordinates, independent of Android and Compose so placement can be regression-tested. */
internal data class SelectionMenuRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun intersect(other: SelectionMenuRect): SelectionMenuRect? {
        val result = SelectionMenuRect(maxOf(left, other.left), maxOf(top, other.top),
            minOf(right, other.right), minOf(bottom, other.bottom))
        return result.takeIf { it.width > 0 && it.height > 0 }
    }
}

internal data class SelectionMenuPosition(val x: Int, val y: Int)

internal object SelectionMenuGeometry {
    /** Above the rotate handle when possible, below the resize handle next, then a viewport shelf. */
    fun place(selection: SelectionMenuRect, viewport: SelectionMenuRect, menuWidth: Int, menuHeight: Int,
        margin: Float, aboveGap: Float, belowGap: Float): SelectionMenuPosition? {
        val visible = selection.intersect(viewport) ?: return null
        val left = viewport.left + margin
        val right = viewport.right - margin - menuWidth
        val top = viewport.top + margin
        val bottom = viewport.bottom - margin - menuHeight
        if (right < left || bottom < top) return null
        val x = ((visible.left + visible.right - menuWidth) / 2f).coerceIn(left, right)
        val above = selection.top - aboveGap - menuHeight
        val below = selection.bottom + belowGap
        val y = when {
            above >= top && above <= bottom -> above
            below >= top && below <= bottom -> below
            else -> top
        }
        return SelectionMenuPosition(x.roundToInt(), y.roundToInt())
    }
}
