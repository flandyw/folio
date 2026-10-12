@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

@Composable internal fun EditorSearchPanels(state: FolioState, model: FolioViewModel, overlays: EditorOverlayState,
    query: MutableState<String>, pdfOutline: List<PdfOutlineEntry>?, jumpTo: (Int) -> Unit) {
    val note = state.active ?: return
    var noteQuery by query
    var noteSearchOpen by overlays.noteSearch
    var keyboardShortcuts by overlays.keyboardShortcuts
    var pdfSearchOpen by overlays.pdfSearch
    var pdfContentsOpen by overlays.pdfContents
    if (noteSearchOpen) FolioPanel(title = "Find in notes", onDismissRequest = { noteSearchOpen = false }) {
        val searchFocus = remember { FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        // Search runs off the main thread with a debounce so typing never janks composition.
        var debouncedQuery by remember { mutableStateOf(noteQuery) }
        LaunchedEffect(noteQuery) {
            kotlinx.coroutines.delay(200)
            debouncedQuery = noteQuery
        }
        var hits by remember { mutableStateOf<List<NotebookTextSearch.Hit>>(emptyList()) }
        val searchPages by produceState<List<NotePage>?>(null, note.id, note.pages, noteQuery.isNotBlank()) {
            value = null
            value = if (noteQuery.isBlank()) emptyList() else model.pagesForSearch(note.id)
        }
        var searchingText by remember { mutableStateOf(false) }
        LaunchedEffect(debouncedQuery, searchPages) {
            hits = emptyList()
            val snapshot = searchPages ?: return@LaunchedEffect
            if (debouncedQuery.isBlank()) return@LaunchedEffect
            searchingText = true
            try { hits = withContext(Dispatchers.Default) { NotebookTextSearch.search(snapshot, debouncedQuery) } }
            finally { searchingText = false }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            OutlinedTextField(
                noteQuery, { noteQuery = it },
                Modifier.fillMaxWidth().focusRequester(searchFocus),
                label = { Text("Find typed text") },
                placeholder = { Text("e.g. quadratic formula") },
                singleLine = true,
                shape = FolioShapes.large,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { debouncedQuery = noteQuery; keyboard?.hide() }),
                trailingIcon = {
                    if (noteQuery.isNotEmpty()) IconButton({ noteQuery = "" }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Clear, "Clear search")
                    }
                }
            )
            if (note.pages.any { it.pdfIndex != null }) TextButton({ noteSearchOpen = false; pdfSearchOpen = true }) {
                Text("Search PDF text instead")
            }
            if (searchPages?.any { !it.loaded } == true) Text("Some pages could not be read. Results may be incomplete.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (noteQuery.isBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                    Icon(Icons.Rounded.FindInPage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Searches every typed text box in this notebook. Handwriting is not searched.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (searchPages == null || noteQuery != debouncedQuery || searchingText) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    LoadingIndicator(Modifier.size(24.dp).semanticsLabel("Searching notebook text"))
                    Text("Searching all pages…", style = MaterialTheme.typography.bodySmall)
                }
            } else if (hits.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp12), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Icon(Icons.Rounded.SearchOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No typed text matches “${noteQuery.trim().take(80)}”.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("${hits.size} ${if (hits.size == 1) "page matches" else "pages match"} — most matches first.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    items(hits, key = { it.pageIndex }) { hit ->
                        Surface(onClick = { jumpTo(hit.pageIndex); noteSearchOpen = false }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Page ${hit.pageIndex + 1} · ${hit.matchCount}×", style = MaterialTheme.typography.titleSmall)
                                    if (hit.snippet.isNotBlank()) Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open page ${hit.pageIndex + 1}")
                            }
                        }
                    }
                }
            }
        }
    }
    if (keyboardShortcuts) FolioPanel("Keyboard shortcuts", { keyboardShortcuts = false }) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            listOf("Ctrl + F" to "Find in the current document", "Ctrl + G" to "Go to a page", "Ctrl + 0" to "Reset zoom",
                "Ctrl + Z" to "Undo", "Ctrl + Shift + Z / Ctrl + Y" to "Redo", "Ctrl + Enter" to "Apply a text-box draft").forEach { (keys, action) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    Text(action, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(keys, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (pdfSearchOpen) ReferenceSearchPanel(
        search = state.pdfSearch, currentIndex = state.pageIndex,
        onQuery = model::searchPdf,
        onJump = { jumpTo(it); pdfSearchOpen = false },
        onDismiss = { pdfSearchOpen = false },
        onFindNotes = { pdfSearchOpen = false; noteSearchOpen = true }
    )
    if (pdfContentsOpen) FolioPanel(title = "Contents", onDismissRequest = { pdfContentsOpen = false }) {
        val outline = pdfOutline
        if (outline == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                LoadingIndicator(Modifier.size(24.dp).semanticsLabel("Loading contents"))
                Text("Reading bookmarks…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (outline.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This PDF has no bookmarks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = FolioSpacing.dp16), contentPadding = PaddingValues(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                itemsIndexed(outline) { _, entry ->
                    Surface(onClick = { jumpTo(entry.pageIndex); pdfContentsOpen = false }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                        Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp16 + FolioSpacing.dp16 * entry.depth.coerceIn(0, 3), end = FolioSpacing.dp16, top = FolioSpacing.dp10, bottom = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Page ${entry.pageIndex + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open ${entry.title}")
                        }
                    }
                }
            }
        }
    }
}
