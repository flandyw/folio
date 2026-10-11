@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The bar's colour roles: the same neutral surface as Folio's glass pills, so tools, chips and
 * dividers keep the colours they have in the Folio style and only the arrangement differs.
 */
internal object BarTones {
    val container @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val content @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    /** Behind the tabs, a step deeper; the active tab wears [container] so it runs straight into the bar. */
    val strip @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val stripContent @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val divider @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
}

/** One open document as a tab: the notebook id (what the workspace switches by) and its current title. */
internal class EditorTabChip(val id: String, val title: String)

/** Height of the tool row; the tab strip above it is [TabStripHeight]. */
internal val GoodnotesRowHeight = 52.dp
/** Side inset of both rows, matching where the Folio pills' first button sits from the edge. */
internal val GoodnotesBarInset = FolioSpacing.dp8
private val TabStripHeight = 40.dp
private val TabHeight = 34.dp

/**
 * The flat editor bar: document tabs over one row for navigation, tools and document actions.
 * [tabs] is null where leaving the editor mid-task is wrong (mistake review), which drops the
 * strip altogether. [content] is the second row and arrives already coloured for the bar.
 */
@Composable internal fun GoodnotesBar(
    tabs: List<EditorTabChip>?,
    activeId: String?,
    onHome: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().guardUiTouches(),
        color = BarTones.container,
        contentColor = BarTones.content,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (tabs != null) TabStrip(tabs, activeId, onHome, onSelectTab, onCloseTab, onNewTab)
            content()
            HorizontalDivider(color = BarTones.divider)
        }
    }
}

/** The neutral strip behind the tabs; the active tab shares the bar's colour so the two read as one. */
@Composable private fun TabStrip(
    tabs: List<EditorTabChip>,
    activeId: String?,
    onHome: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(activeId, tabs.size) {
        val index = tabs.indexOfFirst { it.id == activeId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    CompositionLocalProvider(LocalContentColor provides BarTones.stripContent) {
        Row(
            Modifier.fillMaxWidth().height(TabStripHeight).background(BarTones.strip).padding(horizontal = GoodnotesBarInset - FolioSpacing.dp2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
        ) {
            TabAction(Icons.Rounded.Home, "Back to notebooks", onHome)
            LazyRow(
                Modifier.weight(1f, fill = false).fillMaxHeight(),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
                verticalAlignment = Alignment.Bottom,
            ) {
                items(tabs, key = { it.id }) { tab -> Tab(tab, tab.id == activeId, onSelect, onClose) }
            }
            if (onNew != null) TabAction(Icons.Rounded.Add, "Open another document", onNew)
        }
    }
}

@Composable private fun Tab(tab: EditorTabChip, active: Boolean, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    val title = tab.title.ifBlank { "Untitled" }
    Surface(
        onClick = { onSelect(tab.id) },
        shape = RoundedCornerShape(topStart = FolioShapes.mediumRadius, topEnd = FolioShapes.mediumRadius),
        color = if (active) BarTones.container else Color.Transparent,
        contentColor = if (active) BarTones.content else BarTones.stripContent,
        modifier = Modifier.height(TabHeight).widthIn(min = 112.dp, max = 220.dp)
            .semantics { role = Role.Tab; selected = active },
    ) {
        Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(FolioSpacing.dp4))
            IconButton({ onClose(tab.id) }, modifier = Modifier.size(28.dp), shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Close, "Close $title", Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = if (active) 1f else .7f))
            }
        }
    }
}

@Composable private fun TabAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, modifier = Modifier.size(36.dp), shapes = IconButtonDefaults.shapes()) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}
