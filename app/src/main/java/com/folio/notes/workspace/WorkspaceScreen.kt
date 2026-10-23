@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes.workspace

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.app.WorkspaceViewModel
import com.folio.notes.editor.EditorCommand
import com.folio.notes.editor.EditorPageActions
import com.folio.notes.editor.EditorPageMutations
import com.folio.notes.editor.EditorScreen
import com.folio.notes.editor.InkSurfaceConfig
import com.folio.notes.editor.LocalWorkspaceCommands
import com.folio.notes.editor.PagePresentation
import com.folio.notes.editor.layout.DocumentMotion
import com.folio.notes.editor.page.EditorPage
import com.folio.notes.editor.page.PageNavigator
import com.folio.notes.editor.toolbar.ToolOptions
import com.folio.notes.input.LocalStylusActivity
import com.folio.notes.input.StylusActivity
import com.folio.notes.input.guardUiTouches
import com.folio.notes.input.isPen
import com.folio.notes.model.Notebook
import com.folio.notes.model.ShapePickerTools
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
import com.folio.notes.pdf.PdfLink
import com.folio.notes.pdf.PdfLinkTarget
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.PopoverRow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * What the editor's toolbar needs to offer split view: whether it is open, the one button that
 * opens or closes it, and opening a given notebook beside (a long-pressed tab).
 */
internal class SplitViewControl(
    val open: Boolean,
    val toggle: () -> Unit,
    val openBeside: (String) -> Unit,
    /** Opens the notebook in the pane on the side the carried tab was dropped (window pixels). */
    val dropAt: (String, androidx.compose.ui.geometry.Offset) -> Unit = { id, _ -> openBeside(id) },
    /** Opens the notebook in the start (left/top) pane when true, else the end pane. */
    val openOnSide: (String, Boolean) -> Unit = { id, _ -> openBeside(id) },
)

/** Provided by the workspace only; editors elsewhere (mistake review, music) have no split. */
internal val LocalSplitView = compositionLocalOf<SplitViewControl?> { null }

/** Puts the open tabs in the given order (notebook ids); provided by the workspace only. */
internal val LocalTabOrder = compositionLocalOf<((List<String>) -> Unit)?> { null }

/**
 * The editor's live pen settings, published by the editor so the other pane can write with the same
 * tool. There is one toolbar for both documents; it never has to move just to draw.
 */
internal val LocalSharedInk = compositionLocalOf<MutableState<InkSurfaceConfig?>?> { null }

/**
 * The editor's bar, handed up to the workspace so it spans the whole screen above both split panes
 * instead of sitting inside the editor's pane. [locals] are the editor's composition locals at the
 * point it was built, so its menus and popovers work exactly as if drawn inside the editor.
 */
internal class ToolbarPortalContent(
    val owner: Any,
    val locals: CompositionLocalContext,
    val bar: @Composable () -> Unit,
    val overlay: @Composable () -> Unit,
)

internal val LocalToolbarPortal = compositionLocalOf<MutableState<ToolbarPortalContent?>?> { null }

/** A tab carried out of the strip: which notebook, and where the finger is (window pixels). */
internal data class TabDrag(val id: String, val title: String, val window: androidx.compose.ui.geometry.Offset)

/** Set by the tab strip while a tab is carried over the page; the workspace draws the chip and drop zone. */
internal val LocalTabDrag = compositionLocalOf<MutableState<TabDrag?>?> { null }

/** The bar's own height (tabs and tools, without the ink options under it), reported by [EditorBar]. */
internal val LocalEditorBarHeight = compositionLocalOf<MutableIntState?> { null }

/**
 * Tools the other pane takes straight from the shared toolbar. Text, lasso, sticky notes and the
 * like need the editor's own panels, so a pen with one of those moves the editor over instead.
 */
private val PaneInkTools = setOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASER) + ShapePickerTools

/**
 * The editor, and optionally a second document beside it. The toolbar's split button opens the
 * second pane straight onto a document chooser; writing in that pane (or tapping it) makes it the
 * editor, so either document can be worked on without a mode to pick. The divider resizes, and
 * pulled to an edge it closes the pane it squeezes.
 */
@Composable fun WorkspaceScreen(initialState: FolioState, model: FolioViewModel, finger: Boolean,
    haptics: Boolean, shapeRecognition: Boolean,
    onSettings: () -> Unit, onExport: () -> Unit,
    onBrowseLibrary: (PickerPurpose) -> Unit,
    onShareLongPress: (() -> Unit)? = null,
    onChatGpt: (() -> Unit)? = null,
) {
    val feature: WorkspaceViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by feature.state.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var savedWorkspaces by remember { mutableStateOf(false) }
    var linkOffset by remember { mutableStateOf(false) }
    // The divider's position while a finger holds it; the store only hears about the release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val sharedInk = remember { mutableStateOf<InkSurfaceConfig?>(null) }
    val portal = remember { mutableStateOf<ToolbarPortalContent?>(null) }
    val barHeight = remember { mutableIntStateOf(0) }
    val tabDrag = remember { mutableStateOf<TabDrag?>(null) }
    var rootInWindow by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // The panes' area in the window, so a carried tab knows which half it is over.
    var areaInWindow by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var areaStacked by remember { mutableStateOf(false) }
    val rightToLeft = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val companion = state.companion
    val note = state.notes.find { it.id == companion?.notebookId }
    val paired = companion != null && note != null
    val splitOpen = paired || choosing
    fun toggleSplit() {
        when {
            paired -> { choosing = false; model.dismissCompanion() }
            choosing -> choosing = false
            else -> choosing = true
        }
    }
    fun openBeside(id: String) { choosing = false; model.showCompanion(id) }
    // [start] = the left (or top) pane; the editor takes the other side.
    fun openOnSide(id: String, start: Boolean) { openBeside(id); model.setEditorOnRight(start) }
    fun dropSide(window: androidx.compose.ui.geometry.Offset): Boolean = SplitPanes.dropsAtStart(
        window.x, window.y, areaInWindow.left, areaInWindow.top, areaInWindow.right, areaInWindow.bottom, areaStacked, rightToLeft)
    val control = SplitViewControl(splitOpen, ::toggleSplit, ::openBeside,
        dropAt = { id, window -> openOnSide(id, dropSide(window)) }, openOnSide = ::openOnSide)
    val commands = listOf(
        EditorCommand(if (splitOpen) "Close split view" else "Split view: open a document beside", keys = "Ctrl + \\", run = ::toggleSplit),
        EditorCommand("Work in the other pane", enabled = paired, reason = "Open split view first", run = model::swapCompanion),
        EditorCommand("Swap split view sides", enabled = paired, reason = "Open split view first", run = model::swapPaneSides),
        EditorCommand(if (state.companionLinked) "Unlink split view pages" else "Link split view pages", enabled = paired, reason = "Open split view first",
            run = { model.setCompanionLinked(!state.companionLinked) }),
        EditorCommand("Saved workspaces", run = { savedWorkspaces = true }),
    )
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootInWindow = it.positionInWindow() }.onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Backslash) { toggleSplit(); true } else false
    }) {
        val barTop = with(LocalDensity.current) { barHeight.intValue.toDp() }
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = barTop).onGloballyPositioned { areaInWindow = it.boundsInWindow() }) {
            val stacked = maxWidth < 600.dp
            SideEffect { areaStacked = stacked }
            val divider = 12.dp
            val availablePx = with(LocalDensity.current) { ((if (stacked) maxHeight else maxWidth) - divider).toPx() }
            val editorFirst = !state.editorOnRight
            val fraction = SplitPanes.coerce(dragging ?: state.splitFraction)
            // Past the resize limits the panes stop shrinking and the one about to close says so.
            val closing = dragging?.let { SplitPanes.release(it) }
            SplitLayout(
                split = splitOpen,
                stacked = stacked,
                editorFirst = editorFirst,
                editorFraction = fraction,
                divider = divider,
                editor = {
                    // Key on the active document only: including navigationRequest discarded the whole
                    // editor composition (LazyListState, InkViews, PDF backgrounds) on every page jump.
                    // Page jumps scroll via LaunchedEffect in EditorScreen instead.
                    key(state.activeId, state.workspaceRestoreRequest) {
                        CompositionLocalProvider(LocalSplitView provides control, LocalWorkspaceCommands provides commands,
                            LocalTabOrder provides model::reorderTabs, LocalSharedInk provides sharedInk,
                            LocalToolbarPortal provides portal, LocalEditorBarHeight provides barHeight, LocalTabDrag provides tabDrag) {
                            EditorScreen(state, model, finger, haptics, shapeRecognition, onSettings, onExport,
                                onShareLongPress = onShareLongPress,
                                onChatGpt = onChatGpt,
                                onOpenDocument = { onBrowseLibrary(PickerPurpose.OPEN) },
                                notebookActions = { dismiss ->
                                    PopoverRow(Icons.Rounded.VerticalSplit, if (splitOpen) "Close split view" else "Split view…") { dismiss(); toggleSplit() }
                                    if (paired) {
                                        PopoverRow(Icons.Rounded.SwapHoriz, WorkspacePicker.flipAction(state.editorOnRight)) { dismiss(); model.swapPaneSides() }
                                        PopoverRow(if (state.companionLinked) Icons.Rounded.LinkOff else Icons.Rounded.Link,
                                            if (state.companionLinked) "Unlink pages" else "Link pages: the other pane follows page turns") { dismiss(); model.setCompanionLinked(!state.companionLinked) }
                                        if (state.companionLinked) PopoverRow(Icons.Rounded.Tune, "Adjust page link…") { dismiss(); linkOffset = true }
                                    }
                                    PopoverRow(Icons.Rounded.Bookmarks, "Saved workspaces") { dismiss(); savedWorkspaces = true }
                                    PopoverRow(Icons.Rounded.Add, "Open another document") { dismiss(); onBrowseLibrary(PickerPurpose.OPEN) }
                                })
                        }
                    }
                    // The pane the toolbar is working on, outlined like the active half of a browser split.
                    if (paired) Box(Modifier.matchParentSize().border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)))
                    if (closing == SplitRelease.CloseEditor) ReleaseToClose()
                },
                handle = {
                    SplitDivider(
                        vertical = !stacked,
                        onDrag = { delta -> dragging = SplitPanes.dragged(dragging ?: fraction, delta, availablePx, invert = !editorFirst) },
                        onRelease = {
                            val released = dragging
                            dragging = null
                            when (val outcome = released?.let { SplitPanes.release(it) }) {
                                is SplitRelease.Settle -> model.setSplitFraction(outcome.fraction)
                                SplitRelease.CloseCompanion -> { choosing = false; model.dismissCompanion() }
                                SplitRelease.CloseEditor -> { choosing = false; if (paired) model.closeEditorPane() }
                                null -> Unit
                            }
                        },
                        onDoubleTap = { model.setSplitFraction(SplitPanes.EQUAL) },
                        thickness = divider, touch = 28.dp,
                        contentDescription = "Split view divider. Drag to resize the panes, or to an edge to close one. Double-tap for an equal split.",
                    )
                },
                other = {
                    // Keyed on the document alone; its InkView is keyed per page inside, so a turn
                    // still resets the camera onto a fresh page.
                    if (choosing || !paired) SplitChooserPane(state, model, replacing = paired,
                        onPick = ::openBeside, onCancel = { choosing = false })
                    else key(companion!!.notebookId, state.workspaceRestoreRequest) {
                        CompanionPane(model, companion, note!!, sharedInk.value, onFocus = model::swapCompanion)
                    }
                    if (closing == SplitRelease.CloseCompanion) ReleaseToClose()
                },
            )
            // Where a carried tab will open: the other pane, or the half a new one would take.
            tabDrag.value?.let { drag -> SplitDropZone(stacked, dropSide(drag.window), replacing = paired, rightToLeft = rightToLeft) }
        }
        tabDrag.value?.let { drag -> TabDragChip(drag, state.notes.find { it.id == drag.id }, model, rootInWindow) }
        // One bar for both documents, across the whole screen; the ink options and the text bar
        // drop down over the panes rather than pushing them about.
        portal.value?.let { content ->
            Column(Modifier.align(Alignment.TopCenter).zIndex(11f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CompositionLocalProvider(content.locals) { content.bar(); content.overlay() }
            }
        }
    }
    if (savedWorkspaces) SavedWorkspacesPanel(state, model) { savedWorkspaces = false }
    if (linkOffset && paired) FolioPanel(title = "Page link", onDismissRequest = { linkOffset = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24)) {
            LinkedPageOffset(state, model)
        }
    }
}

/**
 * Two halves over the panes while a tab is carried: the one under the finger lights up and says
 * exactly where the document will open; the other stays a faint outline naming its own side.
 */
@Composable private fun SplitDropZone(stacked: Boolean, start: Boolean, replacing: Boolean, rightToLeft: Boolean) {
    val colors = MaterialTheme.colorScheme
    val half: @Composable (Modifier, Boolean) -> Unit = { modifier, isStart ->
        val target = isStart == start
        val tint by androidx.compose.animation.animateColorAsState(colors.primary.copy(alpha = if (target) .14f else .03f), label = "dropTint")
        val edge by androidx.compose.animation.animateColorAsState(if (target) colors.primary else colors.outline.copy(alpha = .5f), label = "dropEdge")
        val side = when {
            stacked -> if (isStart) "top" else "bottom"
            else -> if (isStart != rightToLeft) "left" else "right"
        }
        val icon = when {
            stacked -> if (isStart) Icons.Rounded.VerticalAlignTop else Icons.Rounded.VerticalAlignBottom
            else -> if (isStart) Icons.AutoMirrored.Rounded.ArrowBack else Icons.AutoMirrored.Rounded.ArrowForward
        }
        Box(modifier.fillMaxSize().padding(FolioSpacing.dp12)
            .background(tint, FolioShapes.large)
            .drawBehind {
                drawRoundRect(edge, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = (if (target) 2.5f else 1.5f).dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))))
            }, contentAlignment = Alignment.Center) {
            Surface(shape = FolioShapes.large,
                color = if (target) colors.primaryContainer else colors.surfaceContainerHigh.copy(alpha = .9f),
                contentColor = if (target) colors.onPrimaryContainer else colors.onSurfaceVariant,
                shadowElevation = if (target) 4.dp else 0.dp) {
                Row(Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Column {
                        Text(if (target) "Release to open on the $side" else "Open on the $side",
                            style = if (target) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium)
                        if (target) Text(if (replacing) "Replaces the document beside" else "This document moves to the other side",
                            style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = .8f))
                    }
                }
            }
        }
    }
    if (stacked) Column(Modifier.fillMaxSize()) { half(Modifier.weight(1f), true); half(Modifier.weight(1f), false) }
    else Row(Modifier.fillMaxSize()) { half(Modifier.weight(1f), true); half(Modifier.weight(1f), false) }
}

/** The carried tab: a little card with the document's current page and its name, under the finger. */
@Composable private fun TabDragChip(drag: TabDrag, note: Notebook?, model: FolioViewModel, rootInWindow: androidx.compose.ui.geometry.Offset) {
    val density = LocalDensity.current
    val widthPx = with(density) { 44.dp.roundToPx() }
    val page = note?.let { n -> n.pages.find { it.id == model.state.value.tabs.find { t -> t.notebookId == n.id }?.currentPageId } ?: n.pages.firstOrNull() }
    val preview by produceState<android.graphics.Bitmap?>(null, note?.id, page?.id, page?.revision) {
        value = if (note == null || page == null) null else try { model.thumbnails.thumbnail(note.id, page, widthPx) } catch (_: Exception) { null }
    }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Surface(
        modifier = Modifier.zIndex(20f)
            .onSizeChanged { size = it }
            .offset { IntOffset((drag.window.x - rootInWindow.x - size.width / 2).roundToInt(), (drag.window.y - rootInWindow.y - size.height / 2).roundToInt()) }
            .widthIn(max = 260.dp),
        shape = FolioShapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f)),
    ) {
        Row(Modifier.padding(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
            val ratio = page?.let { (it.width / it.height).coerceIn(.4f, 2f) } ?: .7f
            Box(Modifier.width(44.dp).aspectRatio(ratio).clip(FolioShapes.small).background(androidx.compose.ui.graphics.Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FolioShapes.small)) {
                preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds) }
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(drag.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                note?.let { Text("${it.pages.size} page${if (it.pages.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/**
 * Lays out the editor, divider and second pane. The editor is always the first child, wherever it
 * sits on screen, so opening, closing or flipping the split never remounts it: its pages, camera and
 * any half-made selection survive.
 */
@Composable private fun SplitLayout(
    split: Boolean,
    stacked: Boolean,
    editorFirst: Boolean,
    editorFraction: Float,
    divider: androidx.compose.ui.unit.Dp,
    editor: @Composable BoxScope.() -> Unit,
    handle: @Composable () -> Unit,
    other: @Composable BoxScope.() -> Unit,
) {
    Layout(content = {
        Box(Modifier.layoutId(SplitSlot.EDITOR).clipToBounds(), content = editor)
        if (split) {
            Box(Modifier.layoutId(SplitSlot.HANDLE).zIndex(1f), contentAlignment = Alignment.Center) { handle() }
            Box(Modifier.layoutId(SplitSlot.OTHER).clipToBounds(), content = other)
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val editorMeasurable = measurables.first { it.layoutId == SplitSlot.EDITOR }
        if (!split) {
            val placeable = editorMeasurable.measure(Constraints.fixed(width, height))
            return@Layout layout(width, height) { placeable.place(0, 0) }
        }
        val gap = divider.roundToPx()
        val total = (if (stacked) height else width) - gap
        val editorSize = (total * editorFraction).roundToInt().coerceIn(0, total.coerceAtLeast(0))
        val otherSize = (total - editorSize).coerceAtLeast(0)
        fun fixed(size: Int) = if (stacked) Constraints.fixed(width, size) else Constraints.fixed(size, height)
        val editorPlaceable = editorMeasurable.measure(fixed(editorSize))
        val handlePlaceable = measurables.first { it.layoutId == SplitSlot.HANDLE }.measure(fixed(gap))
        val otherPlaceable = measurables.first { it.layoutId == SplitSlot.OTHER }.measure(fixed(otherSize))
        layout(width, height) {
            fun Placeable.at(offset: Int) = if (stacked) place(0, offset) else place(offset, 0)
            if (editorFirst) {
                editorPlaceable.at(0); handlePlaceable.at(editorSize); otherPlaceable.at(editorSize + gap)
            } else {
                otherPlaceable.at(0); handlePlaceable.at(otherSize); editorPlaceable.at(otherSize + gap)
            }
        }
    }
}

private enum class SplitSlot { EDITOR, HANDLE, OTHER }

/** Covers a pane the divider has been pulled over, so letting go is not a surprise. */
@Composable private fun BoxScope.ReleaseToClose() {
    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
        Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.inverseSurface) {
            Row(Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                Text("Release to close", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Makes the pane the editor on a deliberate finger tap, and when a pen touches it holding a tool the
 * pane can't use on its own (with an ink tool the pen just writes there). The pen's
 * contact is consumed (the read-only page underneath gets a cancel), so the stroke is never drawn
 * into the wrong document; a finger still scrolls, zooms and follows links in the pane, and a palm
 * near a pen that was just writing is never taken for a tap.
 */
private fun Modifier.focusOnTouch(stylus: StylusActivity, penWrites: Boolean, linkFollowedAt: () -> Long, onFocus: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    val focus by rememberUpdatedState(onFocus)
    val writes by rememberUpdatedState(penWrites)
    pointerInput(stylus) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // With an ink tool the pen simply writes here; the toolbar stays where it is.
            if (isPen(down.type) && writes) return@awaitEachGesture
            if (isPen(down.type)) {
                down.consume()
                focus()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }
            if (down.type != PointerType.Touch || stylus.isRecent(down.uptimeMillis) || stylus.isTouching) return@awaitEachGesture
            var tapped = false
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    if (!change.pressed) { tapped = true; break }
                }
            }
            if (!tapped) return@awaitEachGesture
            val at = down.uptimeMillis
            // The page sees this tap after us; let it follow a PDF link first, which keeps the
            // pane a reference rather than swapping documents under the link.
            scope.launch {
                kotlinx.coroutines.delay(120)
                if (linkFollowedAt() < at) focus()
            }
        }
    }
}

/**
 * The other document, laid out like the editor: a scrolling column of its pages (an infinite canvas
 * stays a single canvas), with no bar of its own. The shared toolbar's pen writes here directly; a
 * finger scrolls, flings and follows links, and a tap (or a pen holding a tool only the editor can
 * use) makes this pane the editor.
 */
@Composable private fun CompanionPane(model: FolioViewModel, pane: EditorTab, note: Notebook,
    sharedInk: InkSurfaceConfig?,
    onFocus: () -> Unit) {
    val index = note.pages.indexOfFirst { it.id == pane.currentPageId }.coerceAtLeast(0)
    val page = note.pages.getOrNull(index) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfBacked = remember(note.id) { note.pages.any { it.pdfIndex != null } }
    var links by remember(note.id) { mutableStateOf(emptyList<PdfLink>()) }
    var linkFollowedAt by remember { mutableLongStateOf(0L) }
    val stylus = LocalStylusActivity.current
    // Link rectangles arrive once per notebook; a native notebook simply has none.
    LaunchedEffect(note.id, pdfBacked) {
        links = if (pdfBacked) try { model.repository.pdfs.pdfPageLinks(note.id, note.pages) } catch (_: Exception) { emptyList() } else emptyList()
    }
    val handOptions = remember { ToolOptions.defaults(Tool.HAND) }
    // Both documents are editable at once: the pane borrows the editor's pen, eraser or shape.
    val writable = sharedInk != null && sharedInk.tool in PaneInkTools
    val surface = if (writable) sharedInk!!.copy(finger = false, multiTouchUndo = false, followEnabled = false, rulerEnabled = false, ruler = null)
        else InkSurfaceConfig(tool = Tool.HAND, options = handOptions, finger = false, snapEnabled = false, shapeRecognition = false, multiTouchUndo = false)
    fun openLink(link: PdfLink) {
        linkFollowedAt = android.os.SystemClock.uptimeMillis()
        when (val target = link.target) {
            is PdfLinkTarget.Page -> model.companionPage(target.pageIndex)
            is PdfLinkTarget.Url -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target.uri)))
            } catch (_: Exception) { model.reportError("Couldn't open this link") }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        // Pages fill the pane's width with a margin, like the editor's column at its fitted zoom.
        val paperWidth = (maxWidth - FolioSpacing.dp16 * 2).coerceIn(120.dp, 1100.dp)
        val paperWidthPx = with(LocalDensity.current) { paperWidth.toPx() }
        // Start where this document was last seen (as the editor or as this pane), page-relative so a
        // different zoom or pane width still lands on the same line. A stale anchor is ignored.
        val start = remember(note.id) {
            pane.viewport.documentPageId?.let { id -> note.pages.indexOfFirst { it.id == id } }?.takeIf { it == index || it == index - 1 }
                ?.let { at -> at to ((pane.viewport.documentPageY ?: 0f) * paperWidthPx / note.pages[at].width).roundToInt().coerceAtLeast(0) }
                ?: (index to 0)
        }
        val pages = rememberLazyListState(initialFirstVisibleItemIndex = start.first, initialFirstVisibleItemScrollOffset = start.second)
        val motionDensity = LocalDensity.current.density
        val motion = remember(pages, motionDensity) { DocumentMotion(pages::dispatchRawDelta, scope, motionDensity) }
        // The page mostly on screen is this pane's page: page links, undo and the saved position follow it.
        val shownIndex by remember(pages) { derivedStateOf {
            val info = pages.layoutInfo
            info.visibleItemsInfo.maxByOrNull { item ->
                minOf(item.offset + item.size, info.viewportEndOffset) - maxOf(item.offset, info.viewportStartOffset)
            }?.index ?: pages.firstVisibleItemIndex
        } }
        LaunchedEffect(shownIndex) { if (shownIndex != index && !pages.isScrollInProgress) model.companionPage(shownIndex) }
        LaunchedEffect(pages.isScrollInProgress) { if (!pages.isScrollInProgress && shownIndex != index) model.companionPage(shownIndex) }
        // A linked page turn (or a PDF link) moves the column to the new page.
        LaunchedEffect(index) { if (index != shownIndex) pages.animateScrollToItem(index) }
        // The exact spot on screen, in the editor's own terms, so trading places with the editor (or a
        // restart) keeps both documents where they were instead of jumping to the top of a page.
        fun anchor(): WorkspaceViewport? {
            if (page.infinite) return null
            val item = pages.layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 } ?: return null
            val sheet = note.pages.getOrNull(item.index) ?: return null
            return WorkspaceViewport(scrollOffset = -item.offset, documentPageId = sheet.id,
                documentPageY = if (item.size > 0) -item.offset.toFloat() / item.size * sheet.height else 0f)
        }
        LaunchedEffect(pages, page.infinite, note.pages) {
            if (!page.infinite) snapshotFlow { anchor() }.distinctUntilChanged().debounce(250).collect { it?.let(model::updateCompanionViewport) }
        }
        val focus = { anchor()?.let(model::updateCompanionViewport); onFocus() }
        Box(Modifier.fillMaxSize().focusOnTouch(stylus, writable, { linkFollowedAt }, focus)) {
            if (page.infinite) key(note.id, page.id) {
                var viewport by remember(pane.notebookId, page.id) { mutableStateOf(pane.viewport) }
                LaunchedEffect(viewport) { kotlinx.coroutines.delay(200); model.updateCompanionViewport(viewport) }
                val onCamera = remember(pane.notebookId, page.id) {
                    { vp: WorkspaceViewport -> viewport = viewport.copy(canvasX = vp.canvasX, canvasY = vp.canvasY, canvasZoom = vp.canvasZoom) }
                }
                EditorPage(note.id, page, model, surface = surface,
                    presentation = PagePresentation(active = false, fullscreen = true, initialViewport = pane.viewport, pdfLinks = links),
                    actions = EditorPageActions(mutations = remember(page.id) { model.pageMutations(page.id) }, onActive = {},
                        onPan = { _, _ -> }, onPanEnd = {}, onSelection = {}, onTextEdit = {}, onTextCreate = {},
                        onLoad = { model.loadPage(page.id) }, onCameraChanged = onCamera, onPdfLink = ::openLink))
            } else Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().graphicsLayer { translationY = motion.stretch },
                    state = pages,
                    contentPadding = PaddingValues(vertical = FolioSpacing.dp12),
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    itemsIndexed(note.pages, key = { _, item -> item.id }) { itemIndex, item ->
                        val mutations = remember(item.id) { model.pageMutations(item.id) }
                        // Writing on a page makes it this pane's page, so Undo knows where to look.
                        val paneMutations = remember(mutations, itemIndex) { object : EditorPageMutations by mutations {
                            override fun appendStroke(before: List<Stroke>, stroke: Stroke, after: List<Stroke>) {
                                if (model.state.value.companion?.currentPageId != item.id) model.companionPage(itemIndex)
                                mutations.appendStroke(before, stroke, after)
                            }
                        } }
                        Box(Modifier.width(paperWidth)) {
                            EditorPage(note.id, item, model, surface = surface,
                                presentation = PagePresentation(active = false,
                                    pageLabel = "Page ${itemIndex + 1} of ${note.pages.size}",
                                    paperWidth = paperWidth,
                                    pdfLinks = links),
                                actions = EditorPageActions(mutations = paneMutations, onActive = {},
                                    onPan = { _, dy -> motion.drag(dy) },
                                    onPanEnd = motion::release,
                                    onSelection = {}, onTextEdit = {}, onTextCreate = {},
                                    onLoad = { model.loadPage(item.id) },
                                    onPdfLink = ::openLink))
                        }
                    }
                }
            }
        }
        if (note.pages.size > 1) PageNavigator(
            index = index, count = note.pages.size, singlePage = page.infinite,
            onTurn = { forward -> model.companionPage(index + if (forward) 1 else -1) },
            onJumpToEnd = { last -> model.companionPage(if (last) note.pages.lastIndex else 0) },
            onToggleView = focus, onBrowse = focus,
            modifier = Modifier.align(Alignment.BottomEnd).padding(FolioSpacing.dp16).guardUiTouches())
    }
}
