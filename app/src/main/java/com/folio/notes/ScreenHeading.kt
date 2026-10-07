package com.folio.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The horizontal inset every destination shares. A destination's heading and the content under it
 * both start on this edge; grids and scrolling columns use it as their horizontal `contentPadding`.
 */
val FolioDestinationInset: Dp = FolioSpacing.dp16

/** The gap a heading keeps above itself (below the status bar) and below itself. */
val FolioDestinationVerticalInset: Dp = FolioSpacing.dp8

/**
 * The title row's fixed height. Fixed rather than wrapped, so a pane with a 48 dp icon button, a
 * 40 dp split button or no actions at all still puts its title on exactly the same line.
 */
val FolioHeadingHeight: Dp = 56.dp

/**
 * Extra space a heading keeps above itself when the status bar is hidden or shorter than 16 dp.
 * Provided once by the shell (and by Settings), so no screen threads its own top gap.
 */
val LocalDestinationTopGap = compositionLocalOf { 0.dp }

/**
 * The one heading every destination wears: Library, Explorer, Mistakes, Study, Progress, Music and
 * Settings. Always the first thing in the pane, outside any scrolling content, and never padded by
 * its caller: it owns the shared inset, the top gap and its height, so every title sits in the same
 * place on every screen.
 *
 * [leading] is only for a nested pane that has somewhere to go back to; [actions] are laid out in
 * this row's scope. [subtitle] sits under the title row, so it never moves the title.
 */
@Composable
fun FolioScreenHeading(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(
            start = FolioDestinationInset, end = FolioDestinationInset,
            top = LocalDestinationTopGap.current + FolioDestinationVerticalInset, bottom = FolioDestinationVerticalInset,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().height(FolioHeadingHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
        ) {
            leading?.invoke()
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            actions()
        }
        subtitle?.invoke()
    }
}

/** A titled group inside a destination's content, such as "Your notebooks" above the shelf. */
@Composable
fun FolioSectionHeading(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}
