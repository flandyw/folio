@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.key.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * What the editor's toolbar needs to offer split view: whether it is open, the one button that
 * opens or closes it, and opening a given notebook beside (a long-pressed tab).
 */
internal class SplitViewControl(val open: Boolean, val toggle: () -> Unit, val openBeside: (String) -> Unit)

/** Provided by the workspace only; editors elsewhere (mistake review, music) have no split. */
internal val LocalSplitView = compositionLocalOf<SplitViewControl?> { null }

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
    val control = SplitViewControl(splitOpen, ::toggleSplit, ::openBeside)
    val commands = listOf(
        EditorCommand(if (splitOpen) "Close split view" else "Split view: open a document beside", keys = "Ctrl + \\", run = ::toggleSplit),
        EditorCommand("Work in the other pane", enabled = paired, reason = "Open split view first", run = model::swapCompanion),
        EditorCommand("Swap split view sides", enabled = paired, reason = "Open split view first", run = model::swapPaneSides),
        EditorCommand("Saved workspaces", run = { savedWorkspaces = true }),
    )
    BoxWithConstraints(Modifier.fillMaxSize().onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Backslash) { toggleSplit(); true } else false
    }) {
        val stacked = maxWidth < 600.dp
        val divider = FolioTouch.target
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
                    CompositionLocalProvider(LocalSplitView provides control, LocalWorkspaceCommands provides commands) {
                        EditorScreen(state, model, finger, haptics, shapeRecognition, onSettings, onExport,
                            onShareLongPress = onShareLongPress,
                            onChatGpt = onChatGpt,
                            onOpenDocument = { onBrowseLibrary(PickerPurpose.OPEN) },
                            notebookActions = { dismiss ->
                                PopoverRow(Icons.Rounded.VerticalSplit, if (splitOpen) "Close split view" else "Split view…") { dismiss(); toggleSplit() }
                                if (paired) PopoverRow(Icons.Rounded.SwapHoriz, WorkspacePicker.flipAction(state.editorOnRight)) { dismiss(); model.swapPaneSides() }
                                PopoverRow(Icons.AutoMirrored.Rounded.MenuBook, "Open documents · ${state.tabs.size}") { dismiss(); onBrowseLibrary(PickerPurpose.TABS) }
                                PopoverRow(Icons.Rounded.Bookmarks, "Saved workspaces") { dismiss(); savedWorkspaces = true }
                                PopoverRow(Icons.Rounded.Add, "Open another document") { dismiss(); onBrowseLibrary(PickerPurpose.OPEN) }
                                PopoverRow(Icons.Rounded.Close, "Close this tab") { dismiss(); state.activeId?.let(model::closeTab) }
                                PopoverRow(Icons.Rounded.ClearAll, "Close other tabs", enabled = state.tabs.size > 1) { dismiss(); state.activeId?.let(model::closeOtherTabs) }
                            })
                    }
                }
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
                    contentDescription = "Split view divider. Drag to resize the panes, or to an edge to close one. Double-tap for an equal split.",
                )
            },
            other = {
                // Keyed on the document alone: the pane keeps its PDF tools (search, contents,
                // previews, link following) across page turns, and its InkView is keyed per page
                // inside, so a turn still resets the camera onto a fresh page.
                if (choosing || !paired) SplitChooserPane(state, model, replacing = paired,
                    onPick = ::openBeside, onCancel = { choosing = false })
                else key(companion!!.notebookId, state.workspaceRestoreRequest) {
                    CompanionPane(state, model, companion, note!!, finger,
                        onFocus = model::swapCompanion,
                        onChangeDocument = { choosing = true },
                        onSavedWorkspaces = { savedWorkspaces = true },
                        onLinkOffset = { linkOffset = true })
                }
                if (closing == SplitRelease.CloseCompanion) ReleaseToClose()
            },
        )
    }
    if (savedWorkspaces) SavedWorkspacesPanel(state, model) { savedWorkspaces = false }
    if (linkOffset && paired) FolioPanel(title = "Page link", onDismissRequest = { linkOffset = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24)) {
            LinkedPageOffset(state, model)
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
 * Makes the pane the editor as soon as a pen touches it, and on a deliberate finger tap. The pen's
 * contact is consumed (the read-only page underneath gets a cancel), so the stroke is never drawn
 * into the wrong document; a finger still scrolls, zooms and follows links in the pane, and a palm
 * near a pen that was just writing is never taken for a tap.
 */
private fun Modifier.focusOnTouch(stylus: StylusActivity, linkFollowedAt: () -> Long, onFocus: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    val focus by rememberUpdatedState(onFocus)
    pointerInput(stylus) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
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

@Composable private fun CompanionPane(state: FolioState, model: FolioViewModel, pane: EditorTab, note: Notebook,
    finger: Boolean,
    onFocus: () -> Unit,
    onChangeDocument: () -> Unit,
    onSavedWorkspaces: () -> Unit,
    onLinkOffset: () -> Unit) {
    val index = note.pages.indexOfFirst { it.id == pane.currentPageId }.coerceAtLeast(0)
    val page = note.pages.getOrNull(index) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewport by remember(pane.notebookId, page.id) { mutableStateOf(pane.viewport) }
    var reset by remember(pane.notebookId, page.id) { mutableIntStateOf(0) }
    // The pane's own surface, for the zoom buttons and the fit menu; the editor's view is untouched.
    var paneView by remember { mutableStateOf<InkView?>(null) }
    // PDF tools only appear for a notebook that actually has an imported document behind it.
    val pdfBacked = remember(note.id) { note.pages.any { it.pdfIndex != null } }
    var links by remember(note.id) { mutableStateOf(emptyList<PdfLink>()) }
    var linksOn by remember(note.id) { mutableStateOf(true) }
    var outline by remember(note.id) { mutableStateOf<List<PdfOutlineEntry>?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var contentsOpen by remember { mutableStateOf(false) }
    var jumpOpen by remember { mutableStateOf(false) }
    var fastScrollOn by remember(pane.notebookId) { mutableStateOf(false) }
    var scrubPage by remember(pane.notebookId, index) { mutableFloatStateOf(index.toFloat()) }
    var filmstripOn by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var paneMenu by remember { mutableStateOf(false) }
    var linkFollowedAt by remember { mutableLongStateOf(0L) }
    val stylus = LocalStylusActivity.current
    // Link rectangles arrive once per notebook; a native notebook simply has none.
    LaunchedEffect(note.id, pdfBacked) {
        links = if (pdfBacked) try { model.repository.pdfPageLinks(note.id, note.pages) } catch (_: Exception) { emptyList() } else emptyList()
    }
    fun loadOutline() {
        if (outline != null) return
        scope.launch {
            outline = try { model.repository.pdfOutline(note.id) } catch (_: Exception) { emptyList() }
        }
    }
    // Debounce viewport writes: pan frames must not spam the ViewModel + recompose the tree.
    LaunchedEffect(viewport) {
        kotlinx.coroutines.delay(200)
        model.updateCompanionViewport(viewport)
    }
    val handOptions = remember { ToolOptions.defaults(Tool.HAND) }
    Column(Modifier.fillMaxSize()) {
        // The pane's own slim bar: its title switches the document, Edit (or simply writing on the
        // page) makes it the editor, and the rest of the split's controls live in its menu.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = FolioTouch.target).guardUiTouches().padding(end = FolioSpacing.dp4),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).padding(start = FolioSpacing.dp4).clip(FolioShapes.medium)
                    .clickable(onClickLabel = "Show another document here", onClick = onChangeDocument)
                    .padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(note.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        Text(
                            buildString {
                                append("Page ${index + 1} of ${note.pages.size}")
                                if (state.companionLinked) append(" · linked")
                                append(" · write here to edit")
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onFocus, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp6))
                    Text("Edit")
                }
                IconButton({ model.setCompanionLinked(!state.companionLinked) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        if (state.companionLinked) Icons.Rounded.Link else Icons.Rounded.LinkOff,
                        if (state.companionLinked) "Unlink pages — this pane stays where it is" else "Link pages — this pane follows the editor's page turns",
                        tint = if (state.companionLinked) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
                Box {
                    IconButton({ paneMenu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Split view options") }
                    FolioMenuPopover(paneMenu, { paneMenu = false }, modifier = Modifier.guardUiTouches(), title = "Split view") {
                        FolioMenuItem({ Text("Show another document here") }, { paneMenu = false; onChangeDocument() }, leadingIcon = { Icon(Icons.Rounded.SwapVert, null) })
                        FolioMenuItem({ Text(WorkspacePicker.flipAction(state.editorOnRight)) }, { paneMenu = false; model.swapPaneSides() }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) })
                        FolioMenuItem({ Text("Equal split") }, { paneMenu = false; model.setSplitFraction(SplitPanes.EQUAL) }, leadingIcon = { Icon(Icons.Rounded.Balance, null) })
                        if (state.companionLinked) FolioMenuItem({ Text("Adjust page link…") }, { paneMenu = false; onLinkOffset() }, leadingIcon = { Icon(Icons.Rounded.Link, null) })
                        FolioMenuItem({ Text("Saved workspaces…") }, { paneMenu = false; onSavedWorkspaces() }, leadingIcon = { Icon(Icons.Rounded.Bookmarks, null) })
                        HorizontalDivider()
                        FolioMenuItem({ Text("Close split view") }, { paneMenu = false; model.dismissCompanion() }, leadingIcon = { Icon(Icons.Rounded.Close, null) })
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().focusOnTouch(stylus, { linkFollowedAt }, onFocus)) {
                key(note.id, page.id) {
                    val onCamera = remember(pane.notebookId, page.id) {
                        { vp: WorkspaceViewport -> viewport = viewport.copy(canvasX = vp.canvasX, canvasY = vp.canvasY, canvasZoom = vp.canvasZoom) }
                    }
                    val onLoad = remember(note.id, page.id) { { model.loadPage(page.id) } }
                    EditorPage(note.id, page, model,
                        surface = InkSurfaceConfig(tool = Tool.HAND,
                            options = handOptions,
                            finger = false,
                            snapEnabled = false,
                            shapeRecognition = false,
                            multiTouchUndo = false),
                        presentation = PagePresentation(active = false,
                            fullscreen = true,
                            canvasReset = reset,
                            readOnly = true,
                            initialViewport = pane.viewport,
                            pdfLinks = if (linksOn) links else emptyList()),
                        actions = EditorPageActions(mutations = remember(page.id) { model.pageMutations(page.id) }, onActive = {},
                            onPan = { _, _ -> },
                            onPanEnd = {},
                            onSelection = {},
                            onTextEdit = {},
                            onTextCreate = {},
                            onLoad = onLoad,
                            onCameraChanged = onCamera,
                            onSelectAllView = { view -> paneView = view },
                            onPdfLink = { link ->
                            linkFollowedAt = android.os.SystemClock.uptimeMillis()
                            when (val target = link.target) {
                                is PdfLinkTarget.Page -> model.companionPage(target.pageIndex)
                                is PdfLinkTarget.Url -> try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target.uri)))
                                } catch (_: Exception) { model.reportError("Couldn't open this link") }
                            }
                        }))
                }
            }
        }
        // One tonal bar holds the reference's own controls, so they read as a shelf of their own
        // rather than as loose buttons floating on the page.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column {
                if (fastScrollOn && note.pages.size > 1) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16).guardUiTouches()) {
                        Text("Page ${scrubPage.roundToInt() + 1} of ${note.pages.size}", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = scrubPage,
                            onValueChange = { scrubPage = it },
                            onValueChangeFinished = { model.companionPage(scrubPage.roundToInt()) },
                            valueRange = 0f..note.pages.lastIndex.toFloat(),
                            modifier = Modifier.semantics { contentDescription = "Fast scroll reference pages" }
                        )
                    }
                }
                if (filmstripOn && pdfBacked) ReferenceFilmstrip(note.id, note, index, model.thumbnails) { model.companionPage(it) }
            // Scrollable so a cramped split pane keeps every control reachable instead of clipping the
            // zoom buttons off the end; it centres itself whenever there is room for everything.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).guardUiTouches(),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                val hold = rememberLongPressGuard()
                IconButton(hold.click { model.companionPage(index - 1) }, enabled = index > 0, modifier = Modifier.longPressAction(hold) { model.companionPage(0) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous reference page — hold for the first page") }
                TextButton({ jumpOpen = true }, shapes = ButtonDefaults.shapes()) {
                    Text(PdfReference.pageLabel(index, note.pages.size, page.pdfIndex), maxLines = 1)
                }
                IconButton(hold.click { model.companionPage(index + 1) }, enabled = index < note.pages.lastIndex, modifier = Modifier.longPressAction(hold) { model.companionPage(note.pages.lastIndex) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next reference page — hold for the last page") }
                if (note.pages.size > 1) {
                    IconToggleButton(fastScrollOn, { fastScrollOn = it }, shapes = IconButtonDefaults.toggleableShapes()) {
                        Icon(Icons.Rounded.SwapVert, "Fast scroll")
                    }
                }
                if (pdfBacked) {
                    IconButton({ paneView?.zoomReference(-1) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ZoomOut, "Zoom out") }
                    Text("${(viewport.canvasZoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton({ paneView?.zoomReference(1) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ZoomIn, "Zoom in") }
                    Box {
                        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Zoom, contents and search") }
                        FolioMenuPopover(menu, { menu = false }, modifier = Modifier.guardUiTouches(), title = "View") {
                            FolioMenuItem({ Text("Fit whole page") }, { menu = false; paneView?.fitReference(PdfFit.PAGE) }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) })
                            FolioMenuItem({ Text("Fit page width") }, { menu = false; paneView?.fitReference(PdfFit.WIDTH) }, leadingIcon = { Icon(Icons.Rounded.Fullscreen, null) })
                            FolioMenuItem({ Text("Actual size") }, { menu = false; paneView?.fitReference(PdfFit.ACTUAL) }, leadingIcon = { Icon(Icons.Rounded.CenterFocusStrong, null) })
                            FolioMenuItem({ Text("Reset zoom") }, { menu = false; viewport = WorkspaceViewport(); reset++ }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) })
                            HorizontalDivider()
                            FolioMenuItem({ Text("Contents") }, { menu = false; contentsOpen = true; loadOutline() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null) })
                            FolioMenuItem({ Text("Search this PDF") }, { menu = false; searchOpen = true }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
                            FolioMenuItem({ Text("Go to page…") }, { menu = false; jumpOpen = true }, leadingIcon = { Icon(Icons.Rounded.Numbers, null) })
                            HorizontalDivider()
                            FolioMenuItem(
                                { Text(if (filmstripOn) "Hide page previews" else "Show page previews") },
                                { menu = false; filmstripOn = !filmstripOn },
                                leadingIcon = { Icon(if (filmstripOn) Icons.Rounded.ViewAgenda else Icons.Rounded.PhotoLibrary, null) }
                            )
                            if (links.isNotEmpty()) FolioMenuItem(
                                { Text(if (linksOn) "Ignore PDF links" else "Follow PDF links") },
                                { menu = false; linksOn = !linksOn },
                                leadingIcon = { Icon(if (linksOn) Icons.Rounded.LinkOff else Icons.Rounded.Link, null) }
                            )
                        }
                    }
                    } else {
                        TextButton({ viewport = WorkspaceViewport(); reset++ }, shapes = ButtonDefaults.shapes()) { Text("Fit") }
                    }
                    }
                }
            }
    }
    if (searchOpen) ReferenceSearchPanel(
        search = state.companionPdfSearch,
        currentIndex = index,
        onQuery = model::searchCompanionPdf,
        onJump = { model.companionPage(it); searchOpen = false },
        onDismiss = { searchOpen = false; model.clearCompanionPdfSearch() }
    )
    if (contentsOpen) ReferenceContentsPanel(
        outline = outline,
        onOpen = { model.companionPage(it); contentsOpen = false },
        onDismiss = { contentsOpen = false }
    )
    if (jumpOpen) ReferenceJumpDialog(
        currentIndex = index,
        pageCount = note.pages.size,
        onJump = { model.companionPage(it); jumpOpen = false },
        onDismiss = { jumpOpen = false }
    )
}
