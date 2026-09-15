@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().height(48.dp).guardUiTouches(), verticalAlignment = Alignment.CenterVertically) {
                    if (compact) {
                        TextButton({ picker = "tabs" }, Modifier.weight(1f)) {
                            Text(state.active?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.ExpandMore, "Open documents")
                        }
                    } else Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        state.tabs.forEach { tab ->
                            val note = state.notes.find { it.id == tab.notebookId }
                            if (note != null) Surface(color = if (state.activeId == tab.notebookId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({ model.open(tab.notebookId) }, Modifier.semantics { selected = state.activeId == tab.notebookId; role = Role.Tab }) {
                                        Icon(if (note.pages.any { it.pdfIndex != null }) Icons.Rounded.PictureAsPdf else Icons.Rounded.MenuBook, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(note.title, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton({ model.closeTab(tab.id) }) { Icon(Icons.Rounded.Close, "Close ${note.title}", Modifier.size(18.dp)) }
                                }
                            }
                        }
                    }
                    IconButton({ picker = "open" }) { Icon(Icons.Rounded.Add, "Open document") }
                    IconButton({ picker = "split" }) { Icon(Icons.Rounded.VerticalSplit, "Split view") }
                    IconButton({ picker = "reference" }) { Icon(Icons.Rounded.ChromeReaderMode, "Reference view") }
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
            val referenceWeight = if (state.companionMode == CompanionMode.REFERENCE) .55f else 1f
            if (companion == null || note == null) Box(Modifier.weight(1f)) { editor() }
            else if (compact) Column(Modifier.weight(1f)) {
                Box(Modifier.weight(if (state.editorOnRight) referenceWeight else 1f)) { if (state.editorOnRight) secondary() else editor() }
                HorizontalDivider()
                Box(Modifier.weight(if (state.editorOnRight) 1f else referenceWeight)) { if (state.editorOnRight) editor() else secondary() }
            } else Row(Modifier.weight(1f)) {
                Box(Modifier.weight(if (state.editorOnRight) referenceWeight else 1f)) { if (state.editorOnRight) secondary() else editor() }
                VerticalDivider()
                Box(Modifier.weight(if (state.editorOnRight) 1f else referenceWeight)) { if (state.editorOnRight) editor() else secondary() }
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
                        trailingContent = { if (kind == "tabs") IconButton({ model.closeTab(note.id) }) { Icon(Icons.Rounded.Close, "Close ${note.title}") } },
                        modifier = Modifier.clickable {
                            when (kind) {
                                "split" -> model.showCompanion(note.id, CompanionMode.SPLIT)
                                "reference" -> model.showCompanion(note.id, CompanionMode.REFERENCE)
                                else -> model.open(note.id)
                            }
                            picker = null
                        })
                }
                item { TextButton({ picker = null; model.close() }, Modifier.fillMaxWidth()) { Text("Browse Library") } }
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
                Text(if (state.companionMode == CompanionMode.REFERENCE) "Reference · read only" else "Tap Edit to work here", style = MaterialTheme.typography.labelSmall)
            }
            if (state.companionMode == CompanionMode.SPLIT) IconButton(model::swapCompanion) { Icon(Icons.Rounded.Edit, "Edit this pane") }
            IconButton(model::dismissCompanion) { Icon(Icons.Rounded.Close, "Close companion") }
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
            IconButton({ model.companionPage(index - 1) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous reference page") }
            Text("${index + 1} / ${note.pages.size}", style = MaterialTheme.typography.labelLarge)
            IconButton({ model.companionPage(index + 1) }, enabled = index < note.pages.lastIndex) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next reference page") }
            TextButton({ viewport = WorkspaceViewport(); reset++ }) { Text("Fit") }
        }
    }
}
