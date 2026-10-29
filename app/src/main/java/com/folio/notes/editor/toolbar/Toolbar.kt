@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes.editor.toolbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.notes.input.guardUiTouches
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.folioSpring
import com.folio.notes.workspace.LocalEditorBarHeight
import com.folio.notes.workspace.LocalSplitView
import com.folio.notes.workspace.LocalTabDrag
import com.folio.notes.workspace.LocalTabOrder
import com.folio.notes.workspace.TabDrag
import com.folio.notes.workspace.TabSlotBounds
import com.folio.notes.workspace.reorderedTabs
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

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
private val TabStripHeight = 40.dp
private val TabHeight = 34.dp
// Fixed like Chrome's, so switching tabs never resizes one and shoves its neighbours about.
private val TabWidth = 184.dp
private val TabShape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)

/** What a tab's trailing button does: nothing, close the document, or end the split it is part of. */
private enum class TabClose { NONE, TAB, SPLIT }

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
    /** Bulk close for the strip's tab menu; null hides the menu. */
    onCloseTabs: ((List<String>) -> Unit)? = null,
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
            if (tabs != null) TabStrip(tabs, activeId, onHome, onSelectTab, onCloseTab, onNewTab, onCloseTabs)
            content()
            HorizontalDivider(color = BarTones.divider)
        }
    }
}

/** A tab keeps its composition and animation when it moves into or out of a split pair. */
@Stable private class AnimatedEditorTab(tab: EditorTabChip, initiallyVisible: Boolean = true) {
    var chip by mutableStateOf(tab)
    val visibility = MutableTransitionState(initiallyVisible).apply { targetState = true }
    val placement = TabPlacement()
}

/** Layout targets never include animation or pointer offsets, so reorder thresholds stay still. */
@Stable private class TabPlacement {
    var bounds by mutableStateOf<TabSlotBounds?>(null)
    var position by mutableStateOf<Animatable<Offset, AnimationVector2D>?>(null)
    var releasedAt by mutableStateOf<Offset?>(null)

    fun place(left: Float, width: Float) {
        bounds = TabSlotBounds(left, width)
        if (position == null) position = Animatable(Offset(left, 0f), Offset.VectorConverter)
    }

    fun displayed(held: Boolean, pointer: Offset): Offset =
        if (held) pointer else releasedAt ?: position?.value ?: Offset.Zero
}

/**
 * The strip of open documents, drawn like Chrome's: the active tab is cut from the bar's colour and
 * runs into the tool row below, and it is the only one that offers close, so a tap on a background tab always switches rather than
 * risking a close. Two documents in split view share one capsule with a single close that ends the
 * split (both stay open as tabs); after that each tab closes on its own again.
 * Press and drag sideways to reorder; pull a tab down onto the page to carry it, and it opens on the
 * side of the screen where it is dropped.
 */
@Composable private fun TabStrip(
    tabs: List<EditorTabChip>,
    activeId: String?,
    onHome: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: (() -> Unit)?,
    onCloseTabs: ((List<String>) -> Unit)?,
) {
    val split = LocalSplitView.current
    val reorder = LocalTabOrder.current
    val tabDrag = LocalTabDrag.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scroll = rememberScrollState()
    val latestTabs by rememberUpdatedState(tabs)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestSplit by rememberUpdatedState(split)
    val latestReorder by rememberUpdatedState(reorder)
    var entries by remember { mutableStateOf(tabs.map { AnimatedEditorTab(it) }) }
    // Closed tabs live just long enough to fade and collapse. Only tab UI is retained, never editors.
    LaunchedEffect(tabs) {
        val previous = entries
        val next = tabs.map { tab ->
            (previous.find { it.chip.id == tab.id } ?: AnimatedEditorTab(tab, initiallyVisible = false)).also {
                it.chip = tab
                it.visibility.targetState = true
            }
        }.toMutableList()
        previous.forEachIndexed { index, entry ->
            if (tabs.none { it.id == entry.chip.id }) {
                entry.visibility.targetState = false
                next.add(index.coerceAtMost(next.size), entry)
            }
        }
        entries = next
    }
    var order by remember { mutableStateOf<List<String>?>(null) }
    var dragged by remember { mutableStateOf<String?>(null) }
    // The held tab's absolute position in the scrollable row, independent of its changing slot.
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val splitReach = with(density) { 56.dp.toPx() }
    val spacing = with(density) { FolioSpacing.dp4.toPx() }
    val ids = tabs.map { it.id }
    val liveOrder = order?.takeIf { it.toSet() == ids.toSet() } ?: ids
    val shown = liveOrder.mapNotNull { id -> entries.find { it.chip.id == id } }.toMutableList()
    entries.forEachIndexed { index, entry ->
        if (entry.chip.id !in ids) shown.add(index.coerceAtMost(shown.size), entry)
    }
    // The frame is decoration behind flat, keyed slots; changing split state cannot restart a drag.
    val pair = tabs.zipWithNext().firstOrNull { (first, second) -> first.split && second.split }
    val firstSplit = pair?.first?.id
    val secondSplit = pair?.second?.id
    val pairTogether = pair != null && liveOrder.indexOf(secondSplit) == liveOrder.indexOf(firstSplit) + 1
    LaunchedEffect(activeId, ids) {
        if (activeId == null || activeId !in ids || dragged != null) return@LaunchedEffect
        val bounds = snapshotFlow {
            entries.find { it.chip.id == activeId }?.let { entry ->
                entry.placement.bounds?.takeIf { entry.visibility.isIdle && entry.visibility.currentState }
            }
        }.filterNotNull().first()
        val start = if (rightToLeft) scroll.maxValue + scroll.viewportSize - bounds.left - bounds.width else bounds.left
        if (start < scroll.value || start + bounds.width > scroll.value + scroll.viewportSize) {
            scroll.animateScrollTo((start - spacing * 4).roundToInt().coerceAtLeast(0))
        }
    }
    fun move(id: String, by: Int) {
        val now = ids.toMutableList()
        val from = now.indexOf(id)
        if (from < 0) return
        val to = (from + by).coerceIn(0, now.lastIndex)
        if (from == to) return
        now.add(to, now.removeAt(from))
        reorder?.invoke(now)
    }
    CompositionLocalProvider(
        LocalContentColor provides BarTones.stripContent,
        LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(TabStripHeight).background(BarTones.strip).padding(horizontal = BarInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2),
        ) {
            Box(Modifier.padding(end = FolioSpacing.dp4)) { TabAction(Icons.Rounded.Home, "Back to notebooks", onHome) }
            val stripColor = BarTones.strip
            Box(Modifier.weight(1f, fill = false).fillMaxHeight().drawWithContent {
                drawContent()
                // Fade tabs that run past either end, so a long strip shows there is more to scroll to.
                val fade = 20.dp.toPx().coerceAtMost(size.width / 4f)
                val startHidden = if (rightToLeft) scroll.canScrollForward else scroll.canScrollBackward
                val endHidden = if (rightToLeft) scroll.canScrollBackward else scroll.canScrollForward
                if (startHidden) drawRect(Brush.horizontalGradient(listOf(stripColor, stripColor.copy(alpha = 0f)), 0f, fade),
                    size = androidx.compose.ui.geometry.Size(fade, size.height))
                if (endHidden) drawRect(Brush.horizontalGradient(listOf(stripColor.copy(alpha = 0f), stripColor), size.width - fade, size.width),
                    topLeft = Offset(size.width - fade, 0f), size = androidx.compose.ui.geometry.Size(fade, size.height))
            }.horizontalScroll(scroll, enabled = dragged == null)) {
                val first = entries.find { it.chip.id == firstSplit }
                val second = entries.find { it.chip.id == secondSplit }
                if (first != null && second != null) SplitTabGroup(
                    first, second, visible = pairTogether && dragged != firstSplit && dragged != secondSplit,
                    modifier = Modifier.matchParentSize(), rightToLeft = rightToLeft,
                )
                Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.Bottom) {
                    shown.forEachIndexed { index, entry -> key(entry.chip.id) {
                        val tab = entry.chip
                        val live = tab.id in ids
                        val held = dragged == tab.id
                        val detached = held && split != null && pointer.y > splitReach
                        val placement = entry.placement
                        val target = placement.bounds?.let { Offset(it.left, 0f) }
                        LaunchedEffect(target, held) {
                            val animation = placement.position ?: return@LaunchedEffect
                            if (!held && target != null) {
                                placement.releasedAt?.let {
                                    animation.snapTo(it)
                                    placement.releasedAt = null
                                }
                                animation.animateTo(target, folioSpring())
                            }
                        }
                        LaunchedEffect(entry.visibility.isIdle, entry.visibility.currentState) {
                            if (entry.visibility.isIdle && !entry.visibility.currentState && !entry.visibility.targetState && latestTabs.none { it.id == entry.chip.id }) {
                                entries = entries.filterNot { it === entry }
                            }
                        }
                        val lift = animateFloatAsState(if (held && !detached) 1f else 0f, folioSpring(), label = "tabLift")
                        val opacity = animateFloatAsState(if (detached) .25f else 1f, tween(120), label = "tabDragOpacity")
                        var inputCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        // The gesture below is hand-rolled, so it shows its own press instead of a ripple.
                        var pressed by remember { mutableStateOf(false) }
                        // The pair's capsule leads with the split glyph and ends with its one close.
                        val leading by animateDpAsState(if (tab.id == firstSplit && pairTogether) 26.dp else 0.dp, folioSpring(), label = "splitTabInset")
                        val inPair = pairTogether && (tab.id == firstSplit || tab.id == secondSplit)
                        val raised = tab.id == activeId || inPair
                        // Chrome's separators: hidden beside a raised tab, where its edge already shows.
                        val following = shown.getOrNull(index + 1)?.chip?.id
                        val divider = !raised && following != null && following != activeId &&
                            !(pairTogether && (following == firstSplit || following == secondSplit))
                        val close = when {
                            !live -> TabClose.NONE
                            inPair && split != null -> if (tab.id == secondSplit) TabClose.SPLIT else TabClose.NONE
                            tab.id == activeId || held -> TabClose.TAB
                            else -> TabClose.NONE
                        }
                        Box(
                            Modifier.onPlaced {
                                placement.place(it.positionInParent().x, it.size.width.toFloat())
                            }
                                .zIndex(if (held || placement.releasedAt != null || lift.value > .01f) 1f else 0f)
                                .graphicsLayer {
                                    val shownAt = placement.displayed(held, Offset(pointer.x, pointer.y.coerceIn(0f, splitReach)))
                                    translationX = shownAt.x - (placement.bounds?.left ?: shownAt.x)
                                    translationY = shownAt.y
                                    scaleX = 1f + .035f * lift.value
                                    scaleY = scaleX
                                    alpha = opacity.value
                                }
                                .onGloballyPositioned { inputCoordinates = it }
                                .pointerInput(tab.id, live, split != null, reorder != null) {
                                    if (!live) return@pointerInput
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        val downWindow = inputCoordinates?.takeIf { it.isAttached }?.localToWindow(down.position) ?: return@awaitEachGesture
                                        pressed = true
                                        val released = try {
                                            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                                while (true) {
                                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                                    if (!change.pressed) return@withTimeoutOrNull !change.isConsumed
                                                    val window = inputCoordinates?.takeIf { it.isAttached }?.localToWindow(change.position) ?: return@withTimeoutOrNull false
                                                    if (change.isConsumed || (window - downWindow).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                                                }
                                                @Suppress("UNREACHABLE_CODE") false
                                            }
                                        } finally { pressed = false }
                                        when (released) {
                                            true -> { latestSelect(tab.id); return@awaitEachGesture }
                                            false -> return@awaitEachGesture
                                            null -> Unit
                                        }
                                        if (dragged != null || (latestReorder == null && latestSplit == null)) return@awaitEachGesture
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val origin = placement.displayed(false, Offset.Zero).x
                                        pointer = Offset(origin, 0f)
                                        dragged = tab.id
                                        order = latestTabs.map { it.id }
                                        var completed = false
                                        var wasDetached = false
                                        var lastWindow = downWindow
                                        try {
                                            while (true) {
                                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) { completed = true; break }
                                                change.consume()
                                                // Map through the animated layer too, keeping pointer motion independent of slot motion and lift scale.
                                                val window = inputCoordinates?.takeIf { it.isAttached }?.localToWindow(change.position) ?: break
                                                lastWindow = window
                                                val delta = window - downWindow
                                                pointer = Offset(origin + delta.x, delta.y)
                                                val off = latestSplit != null && pointer.y > splitReach
                                                if (off != wasDetached) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                wasDetached = off
                                                tabDrag?.value = if (off) TabDrag(tab.id, tab.title, window) else null
                                                if (off || latestReorder == null) continue
                                                val currentIds = latestTabs.map { it.id }
                                                val current = order?.takeIf { it.toSet() == currentIds.toSet() } ?: currentIds
                                                val bounds = entries.mapNotNull { item -> item.placement.bounds?.let { item.chip.id to it } }.toMap()
                                                val next = reorderedTabs(current, tab.id, pointer.x + (placement.bounds?.width ?: 0f) / 2f, bounds, rightToLeft)
                                                if (next != current) {
                                                    order = next
                                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                }
                                            }
                                        } finally {
                                            // Seed the settling spring at the exact release point, including canceled gestures.
                                            placement.releasedAt = Offset(pointer.x, pointer.y.coerceIn(0f, splitReach))
                                            val final = order
                                            dragged = null
                                            tabDrag?.value = null
                                            when {
                                                !completed -> order = null
                                                wasDetached && latestSplit != null -> { order = null; latestSplit?.dropAt(tab.id, lastWindow) }
                                                final != null && final != latestTabs.map { it.id } -> latestReorder?.invoke(final)
                                                else -> order = null
                                            }
                                        }
                                    }
                                },
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visibleState = entry.visibility,
                                enter = fadeIn(tween(140)) + expandHorizontally(folioSpring(), expandFrom = Alignment.Start, clip = false),
                                exit = fadeOut(tween(110)) + shrinkHorizontally(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f), shrinkTowards = Alignment.Start, clip = false),
                            ) {
                                Row(
                                    Modifier.height(TabHeight).padding(start = leading.coerceAtLeast(0.dp)),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Both halves of an open split look alike: neither document is the background one.
                                    Tab(tab, raised, held, pressed, divider, close, onClose, { split?.toggle?.invoke() },
                                        modifier = Modifier.semantics {
                                            role = Role.Tab
                                            selected = tab.id == activeId
                                            if (live) {
                                                onClick { onSelect(tab.id); true }
                                                customActions = listOfNotNull(
                                                    split?.let { CustomAccessibilityAction(if (rightToLeft) "Open on the right" else "Open on the left") { it.openOnSide(tab.id, true); true } },
                                                    split?.let { CustomAccessibilityAction(if (rightToLeft) "Open on the left" else "Open on the right") { it.openOnSide(tab.id, false); true } },
                                                    if (tab.id != activeId && !inPair) CustomAccessibilityAction("Close") { onClose(tab.id); true } else null,
                                                    reorder?.let { CustomAccessibilityAction("Move tab left") { move(tab.id, if (rightToLeft) 1 else -1); true } },
                                                    reorder?.let { CustomAccessibilityAction("Move tab right") { move(tab.id, if (rightToLeft) -1 else 1); true } },
                                                )
                                            } else hideFromAccessibility()
                                        },
                                        enabled = live,
                                    )
                                }
                            }
                        }
                    } }
                }
            }
            if (onNew != null) TabAction(Icons.Rounded.Add, "Open another document", onNew)
            if (onCloseTabs != null) Box {
                var menu by remember { mutableStateOf(false) }
                TabAction(Icons.Rounded.KeyboardArrowDown, "Tab actions") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    val at = ids.indexOf(activeId)
                    @Composable fun item(label: String, targets: List<String>) =
                        DropdownMenuItem({ Text(label) }, { menu = false; onCloseTabs(targets) }, enabled = targets.isNotEmpty())
                    item("Close tab", listOfNotNull(activeId?.takeIf { at >= 0 }))
                    item("Close other tabs", if (at < 0) emptyList() else ids - ids[at])
                    // "Right" is what the reader sees, so it runs the other way in a right-to-left layout.
                    item("Close tabs to the right", when { at < 0 -> emptyList(); rightToLeft -> ids.take(at); else -> ids.drop(at + 1) })
                    item("Close all tabs", ids)
                }
            }
        }
    }
    // The workspace may normalize split partners back together when it acknowledges a reorder.
    LaunchedEffect(ids) { if (dragged == null || order?.toSet() != ids.toSet()) order = null }
    DisposableEffect(tabDrag) { onDispose { tabDrag?.value = null } }
}

/** A capsule behind the split pair; it follows the animated slots without reparenting either tab. */
@Composable private fun SplitTabGroup(
    first: AnimatedEditorTab,
    second: AnimatedEditorTab,
    visible: Boolean,
    modifier: Modifier,
    rightToLeft: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    var frameWidth by remember { mutableIntStateOf(0) }
    val opacity = animateFloatAsState(if (visible) 1f else 0f, tween(140), label = "splitTabFrame")
    Box(modifier.onSizeChanged { frameWidth = it.width }.semantics {
        if (visible) contentDescription = "Split view: ${first.chip.title.ifBlank { "Untitled" }} and ${second.chip.title.ifBlank { "Untitled" }}"
    }.drawBehind {
        val firstX = first.placement.displayed(false, Offset.Zero).x
        val secondX = second.placement.displayed(false, Offset.Zero).x
        val left = minOf(firstX, secondX) - 2.dp.toPx()
        val right = maxOf(firstX + (first.placement.bounds?.width ?: 0f), secondX + (second.placement.bounds?.width ?: 0f)) + 2.dp.toPx()
        val top = size.height - TabHeight.toPx() - 2.dp.toPx()
        val radius = CornerRadius(12.dp.toPx())
        val fill = Path().apply { addRoundRect(RoundRect(left, top, right.coerceAtLeast(left), size.height,
            topLeftCornerRadius = radius, topRightCornerRadius = radius)) }
        drawPath(fill, colors.primary.copy(alpha = .12f * opacity.value))
        // Outline the top and sides only; the pair runs into the tool row like any raised tab.
        val r = radius.x
        val edge = Path().apply {
            moveTo(left, size.height); lineTo(left, top + r); quadraticTo(left, top, left + r, top)
            lineTo(right - r, top); quadraticTo(right, top, right, top + r); lineTo(right, size.height)
        }
        drawPath(edge, colors.primary.copy(alpha = .55f * opacity.value), style = Stroke(1.dp.toPx()))
    }) {
        Icon(Icons.Rounded.VerticalSplit, null,
            Modifier.align(Alignment.BottomStart).offset {
                val x = first.placement.displayed(false, Offset.Zero).x
                // offset is relative in RTL; the placement coordinates above are physical.
                IntOffset((if (rightToLeft) frameWidth - x - (first.placement.bounds?.width ?: 0f) else x).roundToInt(), 0)
            }.padding(start = FolioSpacing.dp6, bottom = (TabHeight - 16.dp) / 2).size(16.dp).graphicsLayer { alpha = opacity.value },
            tint = colors.primary,
        )
    }
}

@Composable private fun Tab(
    tab: EditorTabChip,
    active: Boolean,
    held: Boolean,
    pressed: Boolean,
    divider: Boolean,
    close: TabClose,
    onClose: (String) -> Unit,
    onCloseSplit: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    val title = tab.title.ifBlank { "Untitled" }
    val accent = MaterialTheme.colorScheme.primary
    val raised = BarTones.container
    // No colour animation: a switch changes both tabs in the same frame, as in Chrome, instead of
    // cross-fading two half-lit tabs.
    val containerColor = when {
        active || held -> raised
        pressed -> raised.copy(alpha = .55f)
        tab.split -> raised.copy(alpha = .35f)
        else -> raised.copy(alpha = 0f)
    }
    val contentColor = when {
        active || held -> BarTones.content
        tab.split -> BarTones.content.copy(alpha = .8f)
        else -> BarTones.stripContent
    }
    val separator = BarTones.stripContent.copy(alpha = .3f)
    Surface(
        shape = TabShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.fillMaxHeight().width(TabWidth)
            .drawBehind {
                val h = size.height
                val w = size.width
                if (active || held) {
                    // Chrome's flared feet, curving the raised tab into the tool row.
                    val r = 8.dp.toPx()
                    val feet = Path().apply {
                        moveTo(-r, h); lineTo(0f, h); lineTo(0f, h - r); quadraticTo(0f, h, -r, h); close()
                        moveTo(w + r, h); lineTo(w, h); lineTo(w, h - r); quadraticTo(w, h, w + r, h); close()
                    }
                    drawPath(feet, raised)
                } else if (divider) {
                    drawLine(separator, Offset(w, h * .28f), Offset(w, h * .78f), 1.dp.toPx())
                }
            },
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The close slot is always reserved, so the title never shifts when the button appears.
            Box(Modifier.padding(start = FolioSpacing.dp2).size(TabHeight - 10.dp), contentAlignment = Alignment.Center) {
                if (close != TabClose.NONE) {
                    val split = close == TabClose.SPLIT
                    val label = if (split) "Close split view" else "Close $title"
                    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                        tooltip = { PlainTooltip { Text(if (split) "Close split view (both stay open)" else "Close") } }, state = rememberTooltipState()) {
                        IconButton({ if (split) onCloseSplit() else onClose(tab.id) }, enabled = enabled,
                            modifier = Modifier.fillMaxSize(), shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Close, label, Modifier.size(15.dp),
                                tint = if (split) accent else LocalContentColor.current)
                        }
                    }
                }
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
