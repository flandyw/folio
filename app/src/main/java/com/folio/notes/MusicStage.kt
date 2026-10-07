package com.folio.notes

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the real editor lays itself out as a music stand. A score is an ordinary PDF-backed notebook,
 * so the pens, eraser, lasso, text, undo and the journal behind them are the editor's own; the stage
 * only changes the layout (a window of one or two pages that fits the view and zooms, instead of a
 * scrolling column) and adds the reader's chrome around it.
 */
internal class MusicStage(
    /** First page of the window on view. */
    val start: Int,
    /** Pages shown side by side, 1 or 2. */
    val step: Int,
    /** Only the page remains: no strip, no drawing, no zoom. */
    val performance: Boolean,
    /** Whether the editor's tool strip floats over the desk. */
    val showToolbar: Boolean,
    /** A page turn from a tap or a key; true is forward. */
    val onTurn: (forward: Boolean) -> Unit,
    /** Page-turn keys and pedals; true when the key was a turn. */
    val onKey: (android.view.KeyEvent) -> Boolean,
    /** Rails, popovers, the dimming scrim and performance chrome, laid over the desk. */
    val chrome: @Composable BoxScope.(MusicStageInsets) -> Unit,
)

/** The gutters the pages leave around themselves so no chrome ever covers the music. */
internal data class MusicStageInsets(val top: Dp, val bottom: Dp, val side: Dp, val gap: Dp)

internal object MusicStageMetrics {
    /** Room for a floating rail either side of the sheets. */
    val RAIL_GUTTER = 58.dp
    val PERFORMANCE_GUTTER = 10.dp
    val BOTTOM = 8.dp
    val GAP = 8.dp
    val PERFORMANCE_TOP = 8.dp
}
