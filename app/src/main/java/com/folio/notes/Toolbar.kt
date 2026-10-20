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
            verticalAlignment = Alignment.Bottom,
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
                        val leading by animateDpAsState(if (tab.id == firstSplit) 24.dp else 0.dp, folioSpring(), label = "splitTabInset")
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
                                        try {
                                            while (true) {
                                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) { completed = true; break }
                                                change.consume()
                                                // Map through the animated layer too, keeping pointer motion independent of slot motion and lift scale.
                                                val window = inputCoordinates?.takeIf { it.isAttached }?.localToWindow(change.position) ?: break
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
                                                wasDetached && latestSplit != null -> { order = null; latestSplit?.openBeside(tab.id) }
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
                                    Modifier.height(TabHeight + 4.dp).padding(start = leading.coerceAtLeast(0.dp), end = FolioSpacing.dp4),
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    Tab(tab, tab.id == activeId, held, pressed, onClose,
                                        modifier = Modifier.semantics {
                                            role = Role.Tab
                                            selected = tab.id == activeId
                                            if (live) {
                                                onClick { onSelect(tab.id); true }
                                                customActions = listOfNotNull(
                                                    split?.let { CustomAccessibilityAction("Open in split view") { it.openBeside(tab.id); true } },
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

/** A decorative frame follows the animated slots without reparenting either tab. */
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
    val radius = with(LocalDensity.current) { (FolioShapes.mediumRadius + 2.dp).toPx() }
    Box(modifier.onSizeChanged { frameWidth = it.width }.semantics {
        if (visible) contentDescription = "Split view: ${first.chip.title.ifBlank { "Untitled" }} and ${second.chip.title.ifBlank { "Untitled" }}"
    }.drawBehind {
        val firstX = first.placement.displayed(false, Offset.Zero).x
        val secondX = second.placement.displayed(false, Offset.Zero).x
        val left = minOf(firstX, secondX) + if (rightToLeft) FolioSpacing.dp4.toPx() else 0f
        val right = maxOf(firstX + (first.placement.bounds?.width ?: 0f), secondX + (second.placement.bounds?.width ?: 0f)) - if (rightToLeft) 0f else FolioSpacing.dp4.toPx()
        val top = size.height - (TabHeight + 4.dp).toPx()
        val frame = Path().apply {
            addRoundRect(RoundRect(
                left = left, top = top, right = right.coerceAtLeast(left), bottom = size.height,
                topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
            ))
        }
        drawPath(frame, colors.primary.copy(alpha = .14f * opacity.value))
        drawPath(frame, colors.primary.copy(alpha = .7f * opacity.value), style = Stroke(1.5.dp.toPx()))
    }) {
        Icon(Icons.Rounded.VerticalSplit, null,
            Modifier.align(Alignment.BottomStart).offset {
                val x = first.placement.displayed(false, Offset.Zero).x
                // offset is relative in RTL; the placement coordinates above are physical.
                IntOffset((if (rightToLeft) frameWidth - x - (first.placement.bounds?.width ?: 0f) else x).roundToInt(), 0)
            }.padding(start = FolioSpacing.dp6, bottom = 8.dp).size(16.dp).graphicsLayer { alpha = opacity.value },
            tint = colors.primary,
        )
    }
}

@Composable private fun Tab(tab: EditorTabChip, active: Boolean, held: Boolean, pressed: Boolean, onClose: (String) -> Unit, modifier: Modifier, enabled: Boolean) {
    val title = tab.title.ifBlank { "Untitled" }
    val accent = MaterialTheme.colorScheme.primary
    val bottomRadius by animateDpAsState(if (held) FolioShapes.mediumRadius else 0.dp, folioSpring(), label = "tabCorners")
    val shape = RoundedCornerShape(
        topStart = FolioShapes.mediumRadius, topEnd = FolioShapes.mediumRadius,
        bottomStart = bottomRadius.coerceAtLeast(0.dp), bottomEnd = bottomRadius.coerceAtLeast(0.dp),
    )
    // Both halves of a split wear the bar's colour, so the pair reads as one joined tab; the
    // half the toolbar is working on carries the accent line.
    val containerColor by animateColorAsState(
        when {
            active || held || tab.split -> BarTones.container
            pressed -> BarTones.container.copy(alpha = .6f)
            else -> BarTones.container.copy(alpha = 0f)
        },
        tween(if (pressed) 60 else 160), label = "tabContainer",
    )
    val contentColor by animateColorAsState(
        when {
            active || held -> BarTones.content
            tab.split -> BarTones.content.copy(alpha = .75f)
            else -> BarTones.stripContent
        },
        tween(160), label = "tabContent",
    )
    val indicator = animateFloatAsState(if (tab.split && active) 1f else 0f, tween(160), label = "tabIndicator")
    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.height(TabHeight).widthIn(min = 88.dp, max = 200.dp).clip(shape)
            .drawWithContent {
                drawContent()
                if (indicator.value > 0f) drawRect(accent.copy(alpha = indicator.value), size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
            },
    ) {
        Row(Modifier.fillMaxHeight().padding(start = FolioSpacing.dp8, end = FolioSpacing.dp2), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The whole height of the tab is the close target; the glyph stays small.
            IconButton({ onClose(tab.id) }, enabled = enabled, modifier = Modifier.size(TabHeight), shapes = IconButtonDefaults.shapes()) {
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
