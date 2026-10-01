package com.folio.notes

/** Screen dp converted to page units, including the live resize preview's extra scale. */
internal object SelectionChrome {
    const val HANDLE_RADIUS_DP = 8f
    const val TOUCH_RADIUS_DP = 24f
    const val ROTATE_LIFT_DP = 32f
    const val FRAME_MARGIN_DP = 4f

    fun pageUnit(density: Float, canvasScale: Float, previewScale: Float = 1f): Float =
        density / (canvasScale * previewScale).coerceAtLeast(0.0001f)
}
