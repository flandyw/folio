package com.folio.notes.mistakes

/**
 * Uses the actual available window so split-screen and rotation follow the same rules.
 */
internal data class MistakeLayout(val columns: Int, val splitLibrary: Boolean, val splitReview: Boolean)

internal fun mistakeLayout(width: Int, height: Int): MistakeLayout {
    val landscape = width > height
    return MistakeLayout(
        columns = when { landscape && width >= 1200 -> 3; width >= 600 -> 2; else -> 1 },
        splitLibrary = landscape && width >= 1000,
        splitReview = landscape && width >= 840
    )
}

/**
 * How much room the question gets beside (or above) the editor, free of Compose types so the
 * dragging and snapping stay JVM-testable. The question never gets more than [MAX] — past
 * half the answer area is too cramped to write in — and never less than [MIN].
 */
internal object MistakeSplit {
    const val MIN = .2f
    const val MAX = .6f

    /** Question share side by side (landscape) and stacked (portrait). */
    const val DEFAULT_LANDSCAPE = .36f
    const val DEFAULT_PORTRAIT = .30f

    /** Comfortable places a released drag can settle, in question share. */
    val SNAPS = listOf(.25f, DEFAULT_LANDSCAPE, .5f)
    const val SNAP_THRESHOLD = .06f

    const val KEY_LANDSCAPE = "mistake.splitLandscape"
    const val KEY_PORTRAIT = "mistake.splitPortrait"

    fun coerce(value: Float?): Float =
        if (value != null && value.isFinite()) value.coerceIn(MIN, MAX) else DEFAULT_LANDSCAPE

    /** Where a released drag settles: the nearest snap within threshold, else the value. */
    fun snap(value: Float): Float {
        val share = coerce(value)
        val nearest = SNAPS.minByOrNull { kotlin.math.abs(it - share) } ?: return share
        return if (kotlin.math.abs(nearest - share) <= SNAP_THRESHOLD) nearest else share
    }

    /**
     * Applies a drag of [deltaPx] over the split's total [totalPx] to the question share.
     * The question pane comes first in both layouts, so dragging towards the end of the axis
     * always grows it; [invert] is there for a caller that puts the editor first.
     */
    fun dragged(share: Float, deltaPx: Float, totalPx: Float, invert: Boolean = false): Float {
        if (!totalPx.isFinite() || totalPx <= 0f || !deltaPx.isFinite()) return coerce(share)
        val delta = if (invert) -deltaPx else deltaPx
        return coerce(share + delta / totalPx)
    }
}