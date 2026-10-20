@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.VerticalSplit
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

/**
 * One open document as a tab: the notebook id (what the workspace switches by) and its current title.
 * [split] marks the two tabs showing side by side in split view; they sit next to each other and
 * read as one pair.
 */
internal class EditorTabChip(val id: String, val title: String, val split: Boolean = false)

/** Height of the tool row; the tab strip above it is [TabStripHeight]. */
internal val BarRowHeight = 40.dp
/** Side inset of both rows. */
internal val BarInset = FolioSpacing.dp4
private val TabStripHeight = 36.dp
private val TabHeight = 28.dp

/**
 * The flat editor bar: document tabs over one row for navigation, tools and document actions.
 * [tabs] is null where leaving the editor mid-task is wrong (mistake review), which drops the
 * strip altogether. [content] is the second row and arrives already coloured for the bar.
 */
@Composable internal fun EditorBar(
    tabs: List<EditorTabChip>?,
    activeId: String?,
    onHome: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reportHeight = LocalEditorBarHeight.current
    Surface(
        modifier = Modifier.fillMaxWidth().guardUiTouches()
            .then(if (reportHeight != null) Modifier.onSizeChanged { reportHeight.intValue = it.height } else Modifier),
        color = BarTones.container,
        contentColor = BarTones.content,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (tabs != null) TabStrip(tabs, activeId, onHome, onSelectTab, onCloseTab, onNewTab)
            content()
            HorizontalDivider(color = BarTones.divider)
        }
    }
}

/**
 * The neutral strip behind the tabs; the active tab shares the bar's colour so the two read as one.
 * Tabs behave like a browser's: tap to switch, press and drag sideways to reorder, and drag one down
 * onto the page to open it in split view.
 */
@Composable private fun TabStrip(
    tabs: List<EditorTabChip>,
    activeId: String?,
    onHome: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: (() -> Unit)?,
) {
    val split = LocalSplitView.current
    val reorder = LocalTabOrder.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    // The order shown while a tab is held; the store only hears about it on release.
    var order by remember { mutableStateOf<List<String>?>(null) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val bounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val splitReach = with(density) { 56.dp.toPx() }
    val spacing = with(density) { FolioSpacing.dp4.toPx() }
    val ids = tabs.map { it.id }
    val shown = order?.takeIf { it.toSet() == ids.toSet() }?.let { held -> held.mapNotNull { id -> tabs.find { it.id == id } } } ?: tabs
    LaunchedEffect(activeId, tabs.size) {
        val (start, width) = bounds[activeId] ?: return@LaunchedEffect
        if (start < scroll.value || start + width > scroll.value + scroll.viewportSize) {
            scroll.animateScrollTo((start - spacing * 4).roundToInt().coerceAtLeast(0))
        }
    }
    fun move(id: String, by: Int) {
        val now = ids.toMutableList(); val from = now.indexOf(id); val to = (from + by).coerceIn(0, now.lastIndex)
        if (from < 0 || from == to) return
        now.add(to, now.removeAt(from)); reorder?.invoke(now)
    }
    // Material's 48 dp minimum otherwise wraps the 28 dp tab in extra layout space,
    // lifting it off the bottom of the strip. Compose still expands touch hit targets.
    CompositionLocalProvider(
        LocalContentColor provides BarTones.stripContent,
        LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(TabStripHeight).background(BarTones.strip).padding(horizontal = BarInset),
            // Tabs hang from the bottom of the strip into the bar; home and + sit on the same line.
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2),
        ) {
            Box(Modifier.padding(end = FolioSpacing.dp4)) { TabAction(Icons.Rounded.Home, "Back to notebooks", onHome) }
            Row(
                Modifier.weight(1f, fill = false).fillMaxHeight().horizontalScroll(scroll, enabled = dragged == null),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
                verticalAlignment = Alignment.Bottom,
            ) {
                shown.forEach { tab -> key(tab.id) {
                    val held = dragged == tab.id
                    val splitting = held && split != null && dragY > splitReach
                    Tab(tab, tab.id == activeId, held, splitting, onClose,
                        modifier = Modifier
                            .onGloballyPositioned { bounds[tab.id] = it.positionInParent().x to it.size.width.toFloat() }
                            .zIndex(if (held) 1f else 0f)
                            .graphicsLayer {
                                if (held) { translationX = dragX; translationY = dragY.coerceAtLeast(0f); shadowElevation = 6.dp.toPx(); alpha = if (splitting) .85f else 1f }
                            }
                            .semantics {
                                role = Role.Tab; selected = tab.id == activeId
                                onClick { onSelect(tab.id); true }
                                customActions = listOfNotNull(
                                    split?.let { CustomAccessibilityAction("Open in split view") { it.openBeside(tab.id); true } },
                                    reorder?.let { CustomAccessibilityAction("Move tab left") { move(tab.id, -1); true } },
                                    reorder?.let { CustomAccessibilityAction("Move tab right") { move(tab.id, 1); true } },
                                )
                            }
                            .pointerInput(tab.id) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    // Before the hold a sideways slide scrolls the strip; after it, it carries the tab.
                                    var lifted = false
                                    val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                            // The close button consumes its own tap.
                                            if (!change.pressed) return@withTimeoutOrNull !change.isConsumed
                                            if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                                        }
                                        @Suppress("UNREACHABLE_CODE") false
                                    }
                                    when (released) {
                                        true -> { onSelect(tab.id); return@awaitEachGesture }
                                        false -> return@awaitEachGesture
                                        null -> lifted = true
                                    }
                                    if (!lifted || (reorder == null && split == null)) return@awaitEachGesture
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragged = tab.id; dragX = 0f; dragY = 0f; order = ids
                                    var moved = false
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val delta = change.position - change.previousPosition
                                        change.consume()
                                        dragX += delta.x; dragY += delta.y
                                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                        // Swap with a neighbour once the held tab's middle passes the neighbour's middle.
                                        val current = order ?: break
                                        val at = current.indexOf(tab.id)
                                        val (start, width) = bounds[tab.id] ?: continue
                                        val middle = start + dragX + width / 2
                                        val next = current.getOrNull(at + 1)?.let { id -> bounds[id]?.let { id to it } }
                                        val previous = current.getOrNull(at - 1)?.let { id -> bounds[id]?.let { id to it } }
                                        if (reorder != null && next != null && middle > next.second.first + next.second.second / 2) {
                                            order = current.toMutableList().apply { add(at + 1, removeAt(at)) }
                                            dragX -= next.second.second + spacing
                                        } else if (reorder != null && previous != null && middle < previous.second.first + previous.second.second / 2) {
                                            order = current.toMutableList().apply { add(at - 1, removeAt(at)) }
                                            dragX += previous.second.second + spacing
                                        }
                                    }
                                    val final = order
                                    val split = split?.takeIf { dragY > splitReach || !moved }
                                    dragged = null; dragX = 0f; dragY = 0f
                                    when {
                                        // Pulled down onto the page, or held still: open it beside the editor.
                                        split != null -> { order = null; split.openBeside(tab.id) }
                                        final != null && final != ids -> reorder?.invoke(final)
                                    }
                                    // Keep the dropped order on screen until the store's order catches up.
                                    if (final == null || final == ids) order = null
                                }
                            })
                } }
            }
            if (onNew != null) TabAction(Icons.Rounded.Add, "Open another document", onNew)
        }
    }
    LaunchedEffect(ids) { if (order == ids) order = null }
}

@Composable private fun Tab(tab: EditorTabChip, active: Boolean, held: Boolean, splitting: Boolean, onClose: (String) -> Unit, modifier: Modifier) {
    val title = tab.title.ifBlank { "Untitled" }
    val accent = MaterialTheme.colorScheme.primary
    val shape = if (held) RoundedCornerShape(FolioShapes.mediumRadius) else RoundedCornerShape(topStart = FolioShapes.mediumRadius, topEnd = FolioShapes.mediumRadius)
    Surface(
        shape = shape,
        // Both halves of a split wear the bar's colour, so the pair reads as one joined tab; the
        // half the toolbar is working on carries the accent line.
        color = when {
            splitting -> MaterialTheme.colorScheme.primaryContainer
            active || held || tab.split -> BarTones.container
            else -> Color.Transparent
        },
        contentColor = when {
            splitting -> MaterialTheme.colorScheme.onPrimaryContainer
            active || held -> BarTones.content
            tab.split -> BarTones.content.copy(alpha = .75f)
            else -> BarTones.stripContent
        },
        modifier = modifier.height(TabHeight).widthIn(min = 88.dp, max = 200.dp).clip(shape)
            .then(if (tab.split && active) Modifier.drawWithContent {
                drawContent()
                drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
            } else Modifier),
    ) {
        Row(Modifier.fillMaxHeight().padding(start = FolioSpacing.dp8, end = FolioSpacing.dp2), verticalAlignment = Alignment.CenterVertically) {
            if (splitting || tab.split) {
                Icon(Icons.Rounded.VerticalSplit, if (tab.split) "In split view" else null, Modifier.size(14.dp))
                Spacer(Modifier.width(FolioSpacing.dp4))
            }
            Text(if (splitting) "Open beside" else title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(FolioSpacing.dp2))
            IconButton({ onClose(tab.id) }, modifier = Modifier.size(24.dp), shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Close, "Close $title", Modifier.size(14.dp),
                    tint = LocalContentColor.current.copy(alpha = if (active) 1f else .7f))
            }
        }
    }
}

@Composable private fun TabAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, modifier = Modifier.width(36.dp).height(TabHeight), shapes = IconButtonDefaults.shapes()) { Icon(icon, label, Modifier.size(18.dp)) }
    }
}
