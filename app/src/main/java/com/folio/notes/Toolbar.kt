@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

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
private val TabHeight = 30.dp
private val TabSlotHeight = TabHeight + 6.dp
private val TabShape = RoundedCornerShape(10.dp)

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
 * The strip of open documents. Tabs are floating pills: the active one is raised in the bar's colour
 * and is the only one that offers close, so a tap on a background tab always switches rather than
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
                Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    shown.forEach { entry -> key(entry.chip.id) {
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
                                    shadowElevation = 6.dp.toPx() * lift.value.coerceAtLeast(0f)
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
                                    Modifier.height(TabSlotHeight).padding(start = leading.coerceAtLeast(0.dp), end = if (tab.id == firstSplit && inPair) 0.dp else FolioSpacing.dp4),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Tab(tab, tab.id == activeId, held, pressed, close, onClose, { split?.toggle?.invoke() },
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
        val right = maxOf(firstX + (first.placement.bounds?.width ?: 0f), secondX + (second.placement.bounds?.width ?: 0f)) -
            FolioSpacing.dp4.toPx() + 2.dp.toPx()
        val height = TabHeight.toPx() + 6.dp.toPx()
        val top = (size.height - height) / 2f
        val radius = CornerRadius(12.dp.toPx())
        val frame = Path().apply { addRoundRect(RoundRect(left, top, right.coerceAtLeast(left), top + height, radius)) }
        drawPath(frame, colors.primary.copy(alpha = .12f * opacity.value))
        drawPath(frame, colors.primary.copy(alpha = .55f * opacity.value), style = Stroke(1.dp.toPx()))
    }) {
        Icon(Icons.Rounded.VerticalSplit, null,
            Modifier.align(Alignment.CenterStart).offset {
                val x = first.placement.displayed(false, Offset.Zero).x
                // offset is relative in RTL; the placement coordinates above are physical.
                IntOffset((if (rightToLeft) frameWidth - x - (first.placement.bounds?.width ?: 0f) else x).roundToInt(), 0)
            }.padding(start = FolioSpacing.dp6).size(16.dp).graphicsLayer { alpha = opacity.value },
            tint = colors.primary,
        )
    }
}

@Composable private fun Tab(
    tab: EditorTabChip,
    active: Boolean,
    held: Boolean,
    pressed: Boolean,
    close: TabClose,
    onClose: (String) -> Unit,
    onCloseSplit: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    val title = tab.title.ifBlank { "Untitled" }
    val accent = MaterialTheme.colorScheme.primary
    val containerColor by animateColorAsState(
        when {
            active || held -> BarTones.container
            pressed -> BarTones.container.copy(alpha = .55f)
            tab.split -> BarTones.container.copy(alpha = .35f)
            else -> BarTones.container.copy(alpha = 0f)
        },
        tween(if (pressed) 60 else 160), label = "tabContainer",
    )
    val contentColor by animateColorAsState(
        when {
            active || held -> BarTones.content
            tab.split -> BarTones.content.copy(alpha = .8f)
            else -> BarTones.stripContent
        },
        tween(160), label = "tabContent",
    )
    val elevation by animateDpAsState(if (active || held) 1.dp else 0.dp, tween(160), label = "tabElevation")
    val indicator = animateFloatAsState(if (active) 1f else 0f, tween(180), label = "tabIndicator")
    Surface(
        shape = TabShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = elevation,
        modifier = modifier.height(TabHeight).widthIn(min = 72.dp, max = 220.dp)
            .drawWithContent {
                drawContent()
                // A short accent dash under the title marks the document the tools act on.
                if (indicator.value > 0f) {
                    val w = 16.dp.toPx() * indicator.value
                    val h = 2.5.dp.toPx()
                    drawRoundRect(accent.copy(alpha = indicator.value), topLeft = Offset((size.width - w) / 2f, size.height - h - 2.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = CornerRadius(h / 2f))
                }
            },
    ) {
        Row(
            Modifier.fillMaxHeight().padding(start = FolioSpacing.dp10, end = if (close == TabClose.NONE) FolioSpacing.dp10 else FolioSpacing.dp2)
                .animateContentSize(folioSpring()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (close != TabClose.NONE) {
                val split = close == TabClose.SPLIT
                val label = if (split) "Close split view" else "Close $title"
                TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                    tooltip = { PlainTooltip { Text(if (split) "Close split view (both stay open)" else "Close") } }, state = rememberTooltipState()) {
                    IconButton({ if (split) onCloseSplit() else onClose(tab.id) }, enabled = enabled,
                        modifier = Modifier.padding(start = FolioSpacing.dp2).size(TabHeight - 4.dp), shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, label, Modifier.size(15.dp),
                            tint = if (split) accent else LocalContentColor.current)
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

/**
 * The tab strip's documents. In split view both documents get a tab, side by side in the order the
 * panes sit on screen, so the strip shows which two are open together.
 */
internal fun splitTabs(state: FolioState, activeId: String): List<EditorTabChip> {
    val other = state.companion?.notebookId?.takeIf { id -> id != activeId && state.notes.any { it.id == id } }
    var ids = state.tabs.map { it.notebookId }.distinct()
    if (other != null) {
        ids = ids - other
        val at = ids.indexOf(activeId)
        ids = if (at < 0) ids + other else ids.toMutableList().apply { add(if (state.editorOnRight) at else at + 1, other) }
    }
    return ids.mapNotNull { id ->
        val notebook = state.notes.find { it.id == id }
        if (notebook == null || notebook.mistakePractice) null
        else EditorTabChip(id, notebook.title.ifBlank { state.tabs.find { it.notebookId == id }?.title.orEmpty() },
            split = other != null && (id == other || id == activeId))
    }
}
