@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
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
 * Tabs behave like a browser's: tap to switch, press and drag sideways to reorder, and pull one down
 * out of the strip to carry it onto the page as a chip, which opens it in split view where it drops.
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
    val tabDrag = LocalTabDrag.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    // The order shown while a tab is held; the store only hears about it on release.
    var order by remember { mutableStateOf<List<String>?>(null) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val bounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    var content by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
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
                Modifier.weight(1f, fill = false).fillMaxHeight().horizontalScroll(scroll, enabled = dragged == null)
                    .onGloballyPositioned { content = it },
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
                verticalAlignment = Alignment.Bottom,
            ) {
                val slot: @Composable (EditorTabChip) -> Unit = { tab -> key(tab.id) {
                    val held = dragged == tab.id
                    // Pulled down out of the strip, the tab leaves it and travels as a chip (drawn by the
                    // workspace over the panes); its slot stays behind as a faint placeholder.
                    val detached = held && split != null && dragY > splitReach
                    var slotInWindow by remember { mutableStateOf(Offset.Zero) }
                    Tab(tab, tab.id == activeId, held, onClose,
                        modifier = Modifier
                            .onGloballyPositioned {
                                // In the scrolled row's frame, so a tab inside the split group measures the same way.
                                bounds[tab.id] = (content?.takeIf { c -> c.isAttached }?.localPositionOf(it, Offset.Zero)?.x ?: it.positionInParent().x) to it.size.width.toFloat()
                                slotInWindow = it.positionInWindow()
                            }
                            .zIndex(if (held) 1f else 0f)
                            .semantics {
                                role = Role.Tab; selected = tab.id == activeId
                                onClick { onSelect(tab.id); true }
                                customActions = listOfNotNull(
                                    split?.let { CustomAccessibilityAction("Open in split view") { it.openBeside(tab.id); true } },
                                    reorder?.let { CustomAccessibilityAction("Move tab left") { move(tab.id, -1); true } },
                                    reorder?.let { CustomAccessibilityAction("Move tab right") { move(tab.id, 1); true } },
                                )
                            }
                            // Outside the layer below, so positions are in the slot's own (unmoved) frame:
                            // the held tab's offset is simply where the finger is now minus where it took hold.
                            .pointerInput(tab.id) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    // Before the hold a sideways slide scrolls the strip; after it, it carries the tab.
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
                                        null -> Unit
                                    }
                                    if (reorder == null && split == null) return@awaitEachGesture
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragged = tab.id; dragX = 0f; dragY = 0f; order = ids
                                    var wasDetached = false
                                    var cancelled = false
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        if (change == null) { cancelled = true; break }
                                        if (!change.pressed) break
                                        change.consume()
                                        dragX = change.position.x - down.position.x
                                        dragY = change.position.y - down.position.y
                                        val off = split != null && dragY > splitReach
                                        if (off != wasDetached) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); wasDetached = off }
                                        tabDrag?.value = if (off) TabDrag(tab.id, tab.title, slotInWindow + change.position) else null
                                        if (off) continue
                                        // Swap with a neighbour once the held tab's middle passes the neighbour's middle.
                                        val current = order ?: break
                                        val at = current.indexOf(tab.id)
                                        val (start, width) = bounds[tab.id] ?: continue
                                        val middle = start + dragX + width / 2
                                        val next = current.getOrNull(at + 1)?.let { id -> bounds[id] }
                                        val previous = current.getOrNull(at - 1)?.let { id -> bounds[id] }
                                        if (reorder != null && next != null && middle > next.first + next.second / 2) {
                                            order = current.toMutableList().apply { add(at + 1, removeAt(at)) }
                                        } else if (reorder != null && previous != null && middle < previous.first + previous.second / 2) {
                                            order = current.toMutableList().apply { add(at - 1, removeAt(at)) }
                                        }
                                    }
                                    val final = order
                                    dragged = null; dragX = 0f; dragY = 0f
                                    tabDrag?.value = null
                                    when {
                                        cancelled -> order = null
                                        // Dropped onto the page: open it beside the editor.
                                        wasDetached && split != null -> { order = null; split.openBeside(tab.id) }
                                        final != null && final != ids -> reorder?.invoke(final)
                                        else -> order = null
                                    }
                                }
                            }
                            .graphicsLayer {
                                if (held && !detached) {
                                    translationX = dragX; translationY = dragY.coerceIn(0f, splitReach)
                                    shadowElevation = 6.dp.toPx()
                                }
                                if (detached) alpha = .35f
                            })
                } }
                // The two documents in split view are wrapped together in one outlined group with the
                // split mark, so the strip says plainly which tabs share the screen.
                var at = 0
                while (at < shown.size) {
                    val tab = shown[at]
                    val partner = shown.getOrNull(at + 1)
                    if (tab.split && partner?.split == true && dragged != tab.id && dragged != partner.id) {
                        SplitTabGroup(tab, partner) { slot(tab); slot(partner) }
                        at += 2
                    } else { slot(tab); at++ }
                }
            }
            if (onNew != null) TabAction(Icons.Rounded.Add, "Open another document", onNew)
        }
    }
    LaunchedEffect(ids) { if (order == ids) order = null }
}

/** An outlined, tinted frame around the two split tabs, led by the split mark. */
@Composable private fun SplitTabGroup(first: EditorTabChip, second: EditorTabChip, tabs: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = FolioShapes.mediumRadius + 2.dp, topEnd = FolioShapes.mediumRadius + 2.dp)
    Row(
        Modifier.height(TabHeight + 4.dp).clip(shape)
            .background(colors.primary.copy(alpha = .14f))
            .border(1.5.dp, colors.primary.copy(alpha = .7f), shape)
            .padding(start = FolioSpacing.dp6, end = 2.dp, top = 2.dp)
            .semantics(mergeDescendants = false) {
                contentDescription = "Split view: ${first.title.ifBlank { "Untitled" }} and ${second.title.ifBlank { "Untitled" }}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.Rounded.VerticalSplit, null, Modifier.size(16.dp), tint = colors.primary)
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), content = tabs)
    }
}

@Composable private fun Tab(tab: EditorTabChip, active: Boolean, held: Boolean, onClose: (String) -> Unit, modifier: Modifier) {
    val title = tab.title.ifBlank { "Untitled" }
    val accent = MaterialTheme.colorScheme.primary
    val shape = if (held) RoundedCornerShape(FolioShapes.mediumRadius) else RoundedCornerShape(topStart = FolioShapes.mediumRadius, topEnd = FolioShapes.mediumRadius)
    Surface(
        shape = shape,
        // Both halves of a split wear the bar's colour, so the pair reads as one joined tab; the
        // half the toolbar is working on carries the accent line.
        color = when {
            active || held || tab.split -> BarTones.container
            else -> Color.Transparent
        },
        contentColor = when {
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

            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
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
