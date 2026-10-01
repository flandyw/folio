@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One editor and an independently navigable companion share the same notebook store. */
@Composable fun WorkspaceScreen(state: FolioState, model: FolioViewModel, finger: Boolean,
    haptics: Boolean, shapeRecognition: Boolean,
    onSettings: () -> Unit, onExport: () -> Unit) {
    var picker by remember { mutableStateOf<PickerPurpose?>(null) }
    var paneOptions by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val boxDensity = LocalDensity.current
        val totalWidthPx = with(boxDensity) { maxWidth.toPx() }
        val totalHeightPx = with(boxDensity) { maxHeight.toPx() }
        Column(Modifier.fillMaxSize()) {
            val companion = state.companion
            val note = state.notes.find { it.id == companion?.notebookId }
            // Key on the active document only: including navigationRequest discarded the whole
            // editor composition (LazyListState, InkViews, PDF backgrounds) on every page jump.
            // Page jumps scroll via LaunchedEffect in EditorScreen instead.
            val editor: @Composable () -> Unit = {
                key(state.activeId) {
                    EditorScreen(state, model, finger, haptics, shapeRecognition, onSettings, onExport,
                        notebookActions = { dismiss ->
                            HorizontalDivider()
                            Text("Workspace", Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            DropdownMenuItem({ Text("Open beside the editor") }, { dismiss(); picker = PickerPurpose.COMPANION },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ChromeReaderMode, null) })
                            DropdownMenuItem({ Text("Open documents · ${state.tabs.size}") }, { dismiss(); picker = PickerPurpose.TABS },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) })
                            DropdownMenuItem({ Text("Open another document") }, { dismiss(); picker = PickerPurpose.OPEN },
                                leadingIcon = { Icon(Icons.Rounded.Add, null) })
                            DropdownMenuItem({ Text("Close this tab") }, { dismiss(); state.activeId?.let(model::closeTab) },
                                leadingIcon = { Icon(Icons.Rounded.Close, null) })
                            DropdownMenuItem({ Text("Close other tabs") }, { dismiss(); state.activeId?.let(model::closeOtherTabs) },
                                enabled = state.tabs.size > 1,
                                leadingIcon = { Icon(Icons.Rounded.ClearAll, null) })
                        })
                }
            }
            val secondary: @Composable () -> Unit = {
                // Keyed on the document alone: the pane keeps its PDF tools (search, contents,
                // previews, link following) across page turns, and its InkView is keyed per page
                // inside, so a turn still resets the camera onto a fresh page.
                if (companion != null && note != null) key(companion.notebookId) {
                    CompanionPane(state, model, companion, note, onPaneOptions = { paneOptions = true })
                }
            }
            val editorFraction = SplitPanes.coerce(state.splitFraction)
            val editorFirst = !state.editorOnRight
            // Dragging right/down grows the first pane; invert when the editor sits second.
            fun dragFraction(deltaPx: Float, totalPx: Float) {
                model.setSplitFraction(SplitPanes.dragged(editorFraction, deltaPx, totalPx, invert = !editorFirst))
            }
            fun snapFraction() = model.setSplitFraction(SplitPanes.snap(state.splitFraction))
            fun resetFraction() = model.setSplitFraction(SplitPanes.EQUAL)
            if (companion == null || note == null) Box(Modifier.weight(1f)) { editor() }
            else if (compact) Column(Modifier.weight(1f)) {
                Box(Modifier.weight(if (editorFirst) editorFraction else 1f - editorFraction)) { if (state.editorOnRight) secondary() else editor() }
                SplitDivider(
                    vertical = false,
                    onDrag = { dragFraction(it, totalHeightPx) },
                    onRelease = ::snapFraction,
                    onDoubleTap = ::resetFraction,
                    onOpenOptions = { paneOptions = true }
                )
                Box(Modifier.weight(if (editorFirst) 1f - editorFraction else editorFraction)) { if (state.editorOnRight) editor() else secondary() }
            } else Row(Modifier.weight(1f)) {
                Box(Modifier.weight(if (editorFirst) editorFraction else 1f - editorFraction)) { if (state.editorOnRight) secondary() else editor() }
                SplitDivider(
                    vertical = true,
                    onDrag = { dragFraction(it, totalWidthPx) },
                    onRelease = ::snapFraction,
                    onDoubleTap = ::resetFraction,
                    onOpenOptions = { paneOptions = true }
                )
                Box(Modifier.weight(if (editorFirst) 1f - editorFraction else editorFraction)) { if (state.editorOnRight) editor() else secondary() }
            }
        }
    }
    // Both workspace dialogs share one host: the picker and the divider's options, never together.
    picker?.let { purpose -> WorkspacePickerPanel(purpose, state, model, onDismiss = { picker = null }) }
    if (paneOptions) SplitOptionsPanel(state, model, onDismiss = { paneOptions = false })

}

@Composable private fun CompanionPane(state: FolioState, model: FolioViewModel, pane: EditorTab, note: Notebook,
    onPaneOptions: () -> Unit) {
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
    var filmstripOn by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
        Row(Modifier.fillMaxWidth().guardUiTouches(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = FolioSpacing.dp12)) {
                Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        state.companionMode != CompanionMode.REFERENCE -> "Tap Edit to work here"
                        !pdfBacked && state.companionLinked -> "Reference · read only · linked pages"
                        !pdfBacked -> "Reference · read only"
                        state.companionLinked -> "Reference · PDF · linked pages"
                        else -> "Reference · PDF"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton({ model.setCompanionLinked(!state.companionLinked) }, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    if (state.companionLinked) Icons.Rounded.Link else Icons.Rounded.LinkOff,
                    if (state.companionLinked) "Unlink pages — companion stays where it is" else "Link pages — companion follows the editor"
                )
            }
            if (state.companionMode == CompanionMode.SPLIT) IconButton(model::swapCompanion, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Edit, "Edit this pane") }
            IconButton(model::dismissCompanion, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Close companion") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize()) {
                key(note.id, page.id) {
                    val onCamera = remember(pane.notebookId, page.id) {
                        { vp: WorkspaceViewport -> viewport = viewport.copy(canvasX = vp.canvasX, canvasY = vp.canvasY, canvasZoom = vp.canvasZoom) }
                    }
                    val onLoad = remember(note.id, page.id) { { model.loadPage(page.id) } }
                    EditorPage(note.id, page, model, Tool.HAND, handOptions, false,
                        false, false, false, {}, { _, _ -> },
                        {}, {}, {}, {}, onLoad, fullscreen = true, canvasReset = reset,
                        readOnly = true, initialViewport = pane.viewport,
                        onCameraChanged = onCamera, multiTouchUndo = false,
                        onSelectAllView = { view -> paneView = view },
                        pdfLinks = if (linksOn) links else emptyList(),
                        onPdfLink = { link ->
                            when (val target = link.target) {
                                is PdfLinkTarget.Page -> model.companionPage(target.pageIndex)
                                is PdfLinkTarget.Url -> try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target.uri)))
                                } catch (_: Exception) { model.reportError("Couldn't open this link") }
                            }
                        })
                }
            }
        }
        // One tonal bar holds the reference's own controls, so they read as a shelf of their own
        // rather than as loose buttons floating on the page.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column {
                if (filmstripOn && pdfBacked) ReferenceFilmstrip(note.id, note, index, model.thumbnails) { model.companionPage(it) }
            // Scrollable so a cramped split pane keeps every control reachable instead of clipping the
            // zoom buttons off the end; it centres itself whenever there is room for everything.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).guardUiTouches(),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                val hold = rememberLongPressGuard()
                IconButton(hold.click { model.companionPage(index - 1) }, enabled = index > 0, modifier = Modifier.longPressAction(hold) { model.companionPage(0) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous reference page — hold for the first page") }
                Text(PdfReference.pageLabel(index, note.pages.size, page.pdfIndex), style = MaterialTheme.typography.labelLarge)
                IconButton(hold.click { model.companionPage(index + 1) }, enabled = index < note.pages.lastIndex, modifier = Modifier.longPressAction(hold) { model.companionPage(note.pages.lastIndex) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next reference page — hold for the last page") }
                if (pdfBacked) {
                    IconButton({ paneView?.zoomReference(-1) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ZoomOut, "Zoom out") }
                    Text("${(viewport.canvasZoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton({ paneView?.zoomReference(1) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ZoomIn, "Zoom in") }
                    Box {
                        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Pane options") }
                        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
                            DropdownMenuItem({ Text("Pane and split options") }, { menu = false; onPaneOptions() }, leadingIcon = { Icon(Icons.Rounded.Tune, null) })
                            HorizontalDivider()
                            DropdownMenuItem({ Text("Fit whole page") }, { menu = false; paneView?.fitReference(PdfFit.PAGE) }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) })
                            DropdownMenuItem({ Text("Fit page width") }, { menu = false; paneView?.fitReference(PdfFit.WIDTH) }, leadingIcon = { Icon(Icons.Rounded.Fullscreen, null) })
                            DropdownMenuItem({ Text("Actual size") }, { menu = false; paneView?.fitReference(PdfFit.ACTUAL) }, leadingIcon = { Icon(Icons.Rounded.CenterFocusStrong, null) })
                            DropdownMenuItem({ Text("Reset zoom") }, { menu = false; viewport = WorkspaceViewport(); reset++ }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) })
                            HorizontalDivider()
                            DropdownMenuItem({ Text("Contents") }, { menu = false; contentsOpen = true; loadOutline() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null) })
                            DropdownMenuItem({ Text("Search this PDF") }, { menu = false; searchOpen = true }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
                            DropdownMenuItem({ Text("Go to page…") }, { menu = false; jumpOpen = true }, leadingIcon = { Icon(Icons.Rounded.Numbers, null) })
                            HorizontalDivider()
                            DropdownMenuItem(
                                { Text(if (filmstripOn) "Hide page previews" else "Show page previews") },
                                { menu = false; filmstripOn = !filmstripOn },
                                leadingIcon = { Icon(if (filmstripOn) Icons.Rounded.ViewAgenda else Icons.Rounded.PhotoLibrary, null) }
                            )
                            if (links.isNotEmpty()) DropdownMenuItem(
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

/**
 * The draggable split between editor and companion. Drag resizes (settling on
 * 30/70, 50/50 or 70/30 on release), double-tap returns to 50/50, and long-press
 * opens the pane options panel, which now covers mode, linked pages and the pane actions.
 */
@Composable private fun SplitDivider(
    vertical: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    onDoubleTap: () -> Unit,
    onOpenOptions: () -> Unit
) {
    val drag = rememberDraggableState(onDrag)
    Box(
        contentAlignment = Alignment.Center,
        modifier = if (vertical) {
            Modifier.width(28.dp).fillMaxHeight()
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = onDoubleTap,
                    onLongClick = onOpenOptions,
                    onLongClickLabel = "Split options"
                )
                .semantics { contentDescription = "Split divider. Drag to resize panes. Double-tap for equal split. Long-press for options." }
                .draggable(drag, Orientation.Horizontal, onDragStopped = { onRelease() })
        } else {
            Modifier.height(28.dp).fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = onDoubleTap,
                    onLongClick = onOpenOptions,
                    onLongClickLabel = "Split options"
                )
                .semantics { contentDescription = "Split divider. Drag to resize panes. Double-tap for equal split. Long-press for options." }
                .draggable(drag, Orientation.Vertical, onDragStopped = { onRelease() })
        }
    ) {
        Box(
            Modifier.then(if (vertical) Modifier.width(4.dp).height(48.dp) else Modifier.height(4.dp).width(48.dp))
                .background(MaterialTheme.colorScheme.outlineVariant, FolioShapes.hairline)
        )
    }
}
