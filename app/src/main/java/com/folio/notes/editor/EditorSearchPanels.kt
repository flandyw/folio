@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes.editor

import com.folio.notes.model.countOf
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.toolbar.toolLabel
import com.folio.notes.library.semanticsLabel
import com.folio.notes.model.NotePage
import com.folio.notes.model.displayTitle
import com.folio.notes.pdf.PdfOutlineEntry
import com.folio.notes.pdf.PdfPageText
import com.folio.notes.pdf.PdfSearch
import com.folio.notes.pdf.PdfSearchHit
import com.folio.notes.pdf.ReferenceSearchPanel
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.settings.write
import com.folio.notes.storage.NotebookSearchIndex
import com.folio.notes.storage.NotebookTextSearch
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
                val embedded = model.repository.pdfs.pdfPageTexts(note.id).associate { it.pageIndex to it.text }
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
            Text(when { pages == null || searching -> "Searching…"; noteQuery.isBlank() -> "Enter a word or phrase. Results identify their source."; hits.isEmpty() -> "No matches."; else -> countOf(hits.size, "matching page source") }, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f, false), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(hits) { index, (source, hit) ->
                    Surface(onClick = {
                        overlays.searchMatches.value = hits.map { it.second.pageIndex }.distinct()
                        overlays.searchMatch.intValue = overlays.searchMatches.value.indexOf(hit.pageIndex).coerceAtLeast(0)
                        overlays.searchCaption.value = noteQuery
                        overlays.searchFromPdf.value = false
                        jumpTo(hit.pageIndex); noteSearchOpen = false
                    }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("${note.pages[hit.pageIndex].displayTitle(hit.pageIndex)} · $source · ${countOf(hit.matchCount, "match", "matches")}", style = MaterialTheme.typography.titleSmall)
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
        onJump = { index -> overlays.searchMatches.value = state.pdfSearch.results.map { it.pageIndex }.distinct(); overlays.searchMatch.intValue = overlays.searchMatches.value.indexOf(index).coerceAtLeast(0); overlays.searchCaption.value = state.pdfSearch.query; overlays.searchFromPdf.value = true; jumpTo(index); pdfSearchOpen = false },
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

/** Steps through search results above the pages: tap the caption to refine, arrows to move, × to finish. */
@Composable internal fun SearchMatchesBar(overlays: EditorOverlayState, onJump: (Int) -> Unit) {
    if (overlays.searchMatches.value.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        val matches = overlays.searchMatches.value
        val match = overlays.searchMatch.intValue.coerceIn(0, matches.lastIndex)
        // One match has nowhere else to go, so the arrows rest instead of re-jumping to the same page.
        val cycles = matches.size > 1
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = FolioSpacing.dp4),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
            Icon(Icons.Rounded.Search, null, Modifier.padding(horizontal = FolioSpacing.dp8).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                TextButton({ if (overlays.searchFromPdf.value) overlays.pdfSearch.value = true else overlays.noteSearch.value = true }) {
                    Text("${overlays.searchCaption.value} · ${match + 1} of ${matches.size}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton({ val next = (match + matches.size - 1) % matches.size; overlays.searchMatch.intValue = next; onJump(matches[next]) }, enabled = cycles) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Previous matching page")
            }
            IconButton({ val next = (match + 1) % matches.size; overlays.searchMatch.intValue = next; onJump(matches[next]) }, enabled = cycles) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Next matching page")
            }
            IconButton({ overlays.searchMatches.value = emptyList() }) { Icon(Icons.Rounded.Close, "Close search") }
        }
    }
}
