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
    if (noteSearchOpen) FolioPanel(title = "Find in document", onDismissRequest = { noteSearchOpen = false }) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val prefs = rememberPrefs()
        val handwriting by rememberPref(prefs, AppPrefs.UX_SEARCH_HANDWRITING) { it.getBoolean(AppPrefs.UX_SEARCH_HANDWRITING, AppPrefs.DEFAULT_UX_SEARCH_HANDWRITING) }
        val searchFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        var pages by remember(note.id) { mutableStateOf<List<NotePage>?>(null) }
        var pdf by remember(note.id) { mutableStateOf<List<PdfPageText>>(emptyList()) }
        var ocr by remember(note.id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
        var indexProgress by remember { mutableStateOf<String?>(null) }
        var searchError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(note.id, note.pages.map { it.id to it.revision }, handwriting) {
            pages = null; ocr = emptyMap(); searchError = null
            try {
                val snapshot = model.pagesForSearch(note.id)
                pages = snapshot
                val embedded = model.repository.pdfPageTexts(note.id).associate { it.pageIndex to it.text }
                pdf = snapshot.mapIndexedNotNull { index, page -> page.pdfIndex?.let { PdfPageText(index, embedded[it].orEmpty()) } }
                if (handwriting) snapshot.forEachIndexed { index, page ->
                    if (!page.loaded) return@forEachIndexed
                    indexProgress = "Indexing handwriting ${index + 1} of ${snapshot.size}…"
                    try { ocr = ocr + (index to NotebookSearchIndex.handwriting(context, note.id, page)) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { searchError = "Some handwriting could not be read. Typed and PDF results remain available." }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { searchError = "Some document text could not be read. Try searching again." }
            finally { indexProgress = null }
        }
        var hits by remember { mutableStateOf<List<Pair<String, PdfSearchHit>>>(emptyList()) }
        var searching by remember { mutableStateOf(false) }
        LaunchedEffect(noteQuery, pages, pdf, ocr) {
            searching = true
            try {
                delay(180)
                val snapshot = pages.orEmpty()
                hits = withContext(Dispatchers.Default) {
                    val typed = NotebookTextSearch.search(snapshot, noteQuery).map { "Typed note" to PdfSearchHit(it.pageIndex, it.matchCount, it.snippet) }
                    val embedded = PdfSearch.search(pdf, noteQuery).map { "PDF" to it }
                    val written = PdfSearch.search(ocr.map { PdfPageText(it.key, it.value) }, noteQuery).map { "Handwriting" to it }
                    (typed + embedded + written).sortedWith(compareBy<Pair<String, PdfSearchHit>> { it.second.pageIndex }.thenBy { it.first })
                }
            } finally { searching = false }
        }
        Column(Modifier.fillMaxWidth().weight(1f, false).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(noteQuery, { noteQuery = it }, Modifier.fillMaxWidth().focusRequester(searchFocus), label = { Text("PDF, typed notes and indexed handwriting") }, singleLine = true,
                trailingIcon = { if (noteQuery.isNotEmpty()) TextButton({ noteQuery = "" }) { Text("Clear") } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(handwriting, { prefs.write { putBoolean(AppPrefs.UX_SEARCH_HANDWRITING, it) } })
                Text("Search handwriting on this device", style = MaterialTheme.typography.bodyMedium)
            }
            if (indexProgress != null) Text(indexProgress!!, style = MaterialTheme.typography.bodySmall)
            if (searchError != null) Text(searchError!!, color = MaterialTheme.colorScheme.error)
            if (pages?.any { !it.loaded } == true) Text("Some pages are unavailable; results are incomplete.", color = MaterialTheme.colorScheme.error)
            Text(when { pages == null || searching -> "Searching…"; noteQuery.isBlank() -> "Enter a word or phrase. Results identify their source."; hits.isEmpty() -> "No matches."; else -> "${hits.size} matching page sources" }, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f, false), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(hits) { index, (source, hit) ->
                    Surface(onClick = {
                        overlays.searchMatches.value = hits.map { it.second.pageIndex }.distinct()
                        overlays.searchMatch.intValue = overlays.searchMatches.value.indexOf(hit.pageIndex).coerceAtLeast(0)
                        overlays.searchCaption.value = noteQuery
                        jumpTo(hit.pageIndex); noteSearchOpen = false
                    }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("${note.pages[hit.pageIndex].displayTitle(hit.pageIndex)} · $source · ${hit.matchCount} matches", style = MaterialTheme.typography.titleSmall)
                            Text(hit.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                    }
                }
            }
        }
    }
    if (keyboardShortcuts) FolioPanel("Keyboard shortcuts", { keyboardShortcuts = false }) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            val prefs = rememberPrefs()
            val bindings by rememberPref(prefs, AppPrefs.UX_TOOL_BINDINGS) { EditorKeyBindings.decode(it.getString(AppPrefs.UX_TOOL_BINDINGS, null)) }
            Text("Your tool keys: " + bindings.entries.joinToString(" · ") { "${it.key} ${toolLabel(it.value)}" }, style = MaterialTheme.typography.bodySmall)
            editorShortcutHelp.forEach { (keys, action) ->
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
        onJump = { index -> overlays.searchMatches.value = state.pdfSearch.results.map { it.pageIndex }; overlays.searchMatch.intValue = overlays.searchMatches.value.indexOf(index).coerceAtLeast(0); overlays.searchCaption.value = state.pdfSearch.query; jumpTo(index); pdfSearchOpen = false },
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
