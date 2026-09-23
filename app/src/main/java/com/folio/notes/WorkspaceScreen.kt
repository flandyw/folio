@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One editor and an independently navigable companion share the same notebook store. */
@Composable fun WorkspaceScreen(state: FolioState, model: FolioViewModel, finger: Boolean,
    haptics: Boolean, shapeRecognition: Boolean,
    onSettings: () -> Unit, onExport: () -> Unit) {
    var picker by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val boxDensity = LocalDensity.current
        val totalWidthPx = with(boxDensity) { maxWidth.toPx() }
        val totalHeightPx = with(boxDensity) { maxHeight.toPx() }
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().height(48.dp).guardUiTouches(), verticalAlignment = Alignment.CenterVertically) {
                    if (compact) {
                        TextButton({ picker = "tabs" }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                            Text(state.active?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.ExpandMore, "Open documents")
                        }
                    } else Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        state.tabs.forEach { tab ->
                            val note = state.notes.find { it.id == tab.notebookId }
                            if (note != null) Surface(color = if (state.activeId == tab.notebookId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({ model.open(tab.notebookId) }, modifier = Modifier.semantics { selected = state.activeId == tab.notebookId; role = Role.Tab }, shapes = ButtonDefaults.shapes()) {
                                        val tabIsPdf = note.pages.any { it.pdfIndex != null }
                                        Icon(if (tabIsPdf) Icons.Rounded.PictureAsPdf else Icons.AutoMirrored.Rounded.MenuBook, if (tabIsPdf) "PDF notebook" else "Notebook", Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(note.title, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton({ model.closeTab(tab.id) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Close ${note.title}", Modifier.size(18.dp)) }
                                }
                            }
                        }
                    }
                    IconButton({ picker = "open" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Open document") }
                    IconButton({ picker = "split" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.VerticalSplit, "Split view") }
                    IconButton({ picker = "reference" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ChromeReaderMode, "Reference view") }
                }
            }
            val companion = state.companion
            val note = state.notes.find { it.id == companion?.notebookId }
            // Key on the active document only: including navigationRequest discarded the whole
            // editor composition (LazyListState, InkViews, PDF backgrounds) on every page jump.
            // Page jumps scroll via LaunchedEffect in EditorScreen instead.
            val editor: @Composable () -> Unit = {
                key(state.activeId) {
                    EditorScreen(state, model, finger, haptics, shapeRecognition, onSettings, onExport)
                }
            }
            val secondary: @Composable () -> Unit = {
                if (companion != null && note != null) key(companion.notebookId, companion.currentPageId) { CompanionPane(state, model, companion, note) }
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
                    onSwap = model::swapPaneSides,
                    onClose = model::dismissCompanion,
                    mode = state.companionMode,
                    onToggleMode = { model.setCompanionMode(if (state.companionMode == CompanionMode.SPLIT) CompanionMode.REFERENCE else CompanionMode.SPLIT) },
                    linked = state.companionLinked,
                    onToggleLink = { model.setCompanionLinked(!state.companionLinked) }
                )
                Box(Modifier.weight(if (editorFirst) 1f - editorFraction else editorFraction)) { if (state.editorOnRight) editor() else secondary() }
            } else Row(Modifier.weight(1f)) {
                Box(Modifier.weight(if (editorFirst) editorFraction else 1f - editorFraction)) { if (state.editorOnRight) secondary() else editor() }
                SplitDivider(
                    vertical = true,
                    onDrag = { dragFraction(it, totalWidthPx) },
                    onRelease = ::snapFraction,
                    onDoubleTap = ::resetFraction,
                    onSwap = model::swapPaneSides,
                    onClose = model::dismissCompanion,
                    mode = state.companionMode,
                    onToggleMode = { model.setCompanionMode(if (state.companionMode == CompanionMode.SPLIT) CompanionMode.REFERENCE else CompanionMode.SPLIT) },
                    linked = state.companionLinked,
                    onToggleLink = { model.setCompanionLinked(!state.companionLinked) }
                )
                Box(Modifier.weight(if (editorFirst) 1f - editorFraction else editorFraction)) { if (state.editorOnRight) editor() else secondary() }
            }
        }
    }
    picker?.let { kind ->
        FolioPanel(title = when (kind) { "split" -> "Open beside editor"; "reference" -> "Choose a reference"; "tabs" -> "Open documents"; else -> "Open document" }, onDismissRequest = { picker = null }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                val notes = if (kind == "tabs") state.notes.filter { n -> state.tabs.any { it.notebookId == n.id } } else state.notes
                items(notes, key = { it.id }) { note ->
                    ListItem(headlineContent = { Text(note.title) },
                        supportingContent = { Text("${note.pages.size} pages") },
                        trailingContent = { if (kind == "tabs") IconButton({ model.closeTab(note.id) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Close ${note.title}") } },
                        modifier = Modifier.clickable {
                            when (kind) {
                                "split" -> model.showCompanion(note.id, CompanionMode.SPLIT)
                                "reference" -> model.showCompanion(note.id, CompanionMode.REFERENCE)
                                else -> model.open(note.id)
                            }
                            picker = null
                        })
                }
                item { TextButton({ picker = null; model.close() }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("Browse Library") } }
            }
        }
    }
}

@Composable private fun CompanionPane(state: FolioState, model: FolioViewModel, pane: EditorTab, note: Notebook) {
    val index = note.pages.indexOfFirst { it.id == pane.currentPageId }.coerceAtLeast(0)
    val page = note.pages.getOrNull(index) ?: return
    var viewport by remember(pane.notebookId, page.id) { mutableStateOf(pane.viewport) }
    var reset by remember(pane.notebookId, page.id) { mutableIntStateOf(0) }
    // Debounce viewport writes: pan frames must not spam the ViewModel + recompose the tree.
    LaunchedEffect(viewport) {
        kotlinx.coroutines.delay(200)
        model.updateCompanionViewport(viewport)
    }
    val handOptions = remember { ToolOptions.defaults(Tool.HAND) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().guardUiTouches(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(
                    if (state.companionMode == CompanionMode.REFERENCE) {
                        if (state.companionLinked) "Reference · read only · linked pages" else "Reference · read only"
                    } else "Tap Edit to work here",
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
                        onCameraChanged = onCamera, multiTouchUndo = false)
                }
            }
        }
        Row(Modifier.fillMaxWidth().guardUiTouches(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ model.companionPage(index - 1) }, enabled = index > 0, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous reference page") }
            Text("${index + 1} / ${note.pages.size}", style = MaterialTheme.typography.labelLarge)
            IconButton({ model.companionPage(index + 1) }, enabled = index < note.pages.lastIndex, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next reference page") }
            TextButton({ viewport = WorkspaceViewport(); reset++ }, shapes = ButtonDefaults.shapes()) { Text("Fit") }
        }
    }
}

/**
 * The draggable split between editor and companion. Drag resizes (settling on
 * 30/70, 50/50 or 70/30 on release), double-tap returns to 50/50, and
 * long-press offers swap, close, reference/split and linked pages.
 */
@Composable private fun SplitDivider(
    vertical: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    onDoubleTap: () -> Unit,
    onSwap: () -> Unit,
    onClose: () -> Unit,
    mode: CompanionMode,
    onToggleMode: () -> Unit,
    linked: Boolean,
    onToggleLink: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val drag = rememberDraggableState(onDrag)
    Box(
        contentAlignment = Alignment.Center,
        modifier = if (vertical) {
            Modifier.width(28.dp).fillMaxHeight()
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = onDoubleTap,
                    onLongClick = { menu = true },
                    onLongClickLabel = "Split options"
                )
                .semantics { contentDescription = "Split divider. Drag to resize panes. Double-tap for equal split. Long-press for options." }
                .draggable(drag, Orientation.Horizontal, onDragStopped = { onRelease() })
        } else {
            Modifier.height(28.dp).fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = onDoubleTap,
                    onLongClick = { menu = true },
                    onLongClickLabel = "Split options"
                )
                .semantics { contentDescription = "Split divider. Drag to resize panes. Double-tap for equal split. Long-press for options." }
                .draggable(drag, Orientation.Vertical, onDragStopped = { onRelease() })
        }
    ) {
        Box(
            Modifier.then(if (vertical) Modifier.width(4.dp).height(48.dp) else Modifier.height(4.dp).width(48.dp))
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
        )
        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
            DropdownMenuItem({ Text("Swap panes") }, { menu = false; onSwap() }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) })
            DropdownMenuItem({ Text("Close pane") }, { menu = false; onClose() }, leadingIcon = { Icon(Icons.Rounded.Close, null) })
            DropdownMenuItem(
                { Text(if (mode == CompanionMode.SPLIT) "Make reference" else "Make split") },
                { menu = false; onToggleMode() },
                leadingIcon = { Icon(if (mode == CompanionMode.SPLIT) Icons.AutoMirrored.Rounded.ChromeReaderMode else Icons.Rounded.VerticalSplit, null) }
            )
            DropdownMenuItem(
                { Text(if (linked) "Unlink pages" else "Link pages") },
                { menu = false; onToggleLink() },
                leadingIcon = { Icon(if (linked) Icons.Rounded.LinkOff else Icons.Rounded.Link, null) }
            )
        }
    }
}
