package com.folio.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The horizontal inset every sidebar destination shares.
 *
 * A destination's heading and the content under it both start on this edge, so Library, Mistakes,
 * Study, Music, Explorer and Progress read as one shell rather than each picking its own margin.
 * Grids and scrolling columns that carry the destination's content use it as their horizontal
 * `contentPadding`; [FolioScreenHeading] applies it itself by default.
 */
val FolioDestinationInset: Dp = FolioSpacing.dp16

/**
 * Vertical breathing room the shared destination rhythm uses above and below a heading. A pane whose
 * heading is flush — because a grid or bar already insets it — uses it for the same gap itself, so
 * every destination puts the same distance between its title and what follows.
 */
val FolioDestinationVerticalInset: Dp = FolioSpacing.dp8

/**
 * The spacing a [FolioScreenHeading] applies by default: the shared inset, plus a little vertical
 * breath above and below the title.
 */
val FolioHeadingPadding = PaddingValues(horizontal = FolioDestinationInset, vertical = FolioDestinationVerticalInset)

/**
 * Pass as [FolioScreenHeading]'s `contentPadding` from a pane whose container already insets it —
 * a Material `TopAppBar`, or a lazy grid whose `contentPadding` is [FolioDestinationInset]. The
 * heading then adds nothing of its own, so the shared edge is reached exactly once.
 */
val FolioHeadingFlush = PaddingValues(0.dp)

/**
 * The heading every sidebar destination wears — Library, Mistakes, Study, Music, Explorer, Progress.
 *
 * One treatment for all of them: the same [contentPadding], a single-line [title], an optional
 * quieter [subtitle], an optional [leading] control for a nested pane (a back arrow) and a trailing
 * [actions] slot for whatever the pane puts beside its name. [actions] runs in this row's scope, so
 * its children are laid out here and share the row's spacing.
 *
 * When a destination lives in a Material `TopAppBar`, pass this as the bar's `title` with
 * [FolioHeadingFlush]: the bar already owns the inset, the status-bar gap and the
 * navigation/action slots, and the title still reads as one of the shared headings.
 */
@Composable
fun FolioScreenHeading(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * This heading's own outer spacing. Defaults to [FolioHeadingPadding]; a pane that its
     * container already insets passes [FolioHeadingFlush] so the heading never double-pads.
     */
    contentPadding: PaddingValues = FolioHeadingPadding,
    subtitle: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            subtitle?.invoke()
        }
        actions()
    }
}
