@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.pdf

import com.folio.notes.ui.FolioIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.PdfSearchState
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Text search over a PDF's embedded text. Hits jump the pane, and the previous/next buttons
 * walk every matching page in reading order.
 */
@Composable internal fun ReferenceSearchPanel(
    open: Boolean,
    search: PdfSearchState,
    currentIndex: Int,
    onQuery: (String) -> Unit,
    loadPages: suspend () -> List<PdfPageText>,
    onJump: (Int, List<Int>) -> Unit,
    onDismiss: () -> Unit,
    onFindNotes: (() -> Unit)? = null
) {
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    if (!open) return

    var query by rememberSaveable { mutableStateOf(search.query) }
    val currentQuery = search.query == query.trim()
    val filterHits = currentQuery && (matchCase || wholeWord) && search.results.isNotEmpty()
    var filtered by remember(search, matchCase, wholeWord) { mutableStateOf<List<PdfSearchHit>?>(null) }
    if (filterHits) {
        LaunchedEffect(search, matchCase, wholeWord) {
            // Refine the workspace's hits without changing its saved tab query or the default search.
            val candidates = search.results.mapTo(hashSetOf()) { it.pageIndex }
            val pdfPages = loadPages()
            filtered = withContext(Dispatchers.Default) {
                PdfSearch.search(pdfPages.filter { it.pageIndex in candidates }, search.query, matchCase, wholeWord)
            }
        }
    }
    val results = if (filterHits) filtered.orEmpty() else search.results
    val searching = search.searching || (filterHits && filtered == null)
    val pages = remember(results) { PdfReference.hitPages(results) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() { if (query.isNotBlank()) { keyboard?.hide(); onQuery(query.trim()) } }
    fun jump(index: Int) = onJump(index, pages)
    FolioPanel(title = "Search this PDF", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth(),
                label = { Text("Find in this PDF") },
                placeholder = { Text("e.g. quadratic formula") },
                singleLine = true,
                shape = FolioShapes.large,
                leadingIcon = { Icon(FolioIcons.Search, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton({ query = ""; onQuery("") }, shapes = IconButtonDefaults.shapes()) {
                        Icon(FolioIcons.Close, "Clear search")
                    }
                }
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                FilterChip(selected = matchCase, onClick = { matchCase = !matchCase }, label = { Text("Match case") })
                FilterChip(selected = wholeWord, onClick = { wholeWord = !wholeWord }, label = { Text("Whole word") })
            }
            if (onFindNotes != null) TextButton(onFindNotes) { Text("Search typed notes instead") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Button(::submit, enabled = query.isNotBlank() && !searching, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                    Icon(FolioIcons.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Search")
                }
                if (pages.size > 1 && !searching && currentQuery) {
                    OutlinedButton({ PdfReference.nextHit(currentIndex, pages, forward = false)?.let(::jump) }, shapes = ButtonDefaults.shapes()) {
                        Icon(FolioIcons.Back, "Previous match", Modifier.size(18.dp))
                    }
                    OutlinedButton({ PdfReference.nextHit(currentIndex, pages, forward = true)?.let(::jump) }, shapes = ButtonDefaults.shapes()) {
                        Icon(FolioIcons.Forward, "Next match", Modifier.size(18.dp))
                    }
                }
            }
            when {
                searching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    LoadingIndicator(Modifier.size(24.dp).semantics { contentDescription = "Searching PDF" })
                    Text("Reading this PDF's text…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                query.isBlank() -> Text("Type a word or phrase to search this PDF.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                !currentQuery -> Text("Press Search to find “${query.trim().take(80)}”.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                search.searched && search.query.isNotBlank() && results.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp12),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Icon(FolioIcons.SearchOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No matches for “${search.query.trim().take(80)}”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (search.results.isEmpty()) Text("Scanned or locked PDFs have no searchable text.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                results.isNotEmpty() -> {
                    Text(
                        "${results.size} ${if (results.size == 1) "page matches" else "pages match"} — most matches first.",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        items(results, key = { it.pageIndex }) { hit ->
                            Surface(onClick = { jump(hit.pageIndex) }, shape = FolioShapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Page ${hit.pageIndex + 1} · ${hit.matchCount}×", style = MaterialTheme.typography.titleSmall)
                                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(FolioIcons.Forward, "Open page ${hit.pageIndex + 1}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The reference PDF's bookmarks, nested by depth. Null while the first read runs, so the panel can
 * say it is reading rather than claim a document has no contents.
 */
@Composable internal fun ReferenceContentsPanel(
    outline: List<PdfOutlineEntry>?,
    onOpen: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    FolioPanel(title = "Contents", onDismissRequest = onDismiss) {
        var query by rememberSaveable { mutableStateOf("") }
        val filtered = remember(outline, query) { PdfOutline.filter(outline.orEmpty(), query) }
        if (!outline.isNullOrEmpty()) OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp12),
            label = { Text("Search headings") }, singleLine = true, shape = FolioShapes.large,
            leadingIcon = { Icon(FolioIcons.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton({ query = "" }, shapes = IconButtonDefaults.shapes()) {
                    Icon(FolioIcons.Close, "Clear search")
                }
            }
        )
        if (outline == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                LoadingIndicator(Modifier.size(24.dp).semantics { contentDescription = "Loading contents" })
                Text("Reading bookmarks…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (outline.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                Icon(FolioIcons.Bullets, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This PDF has no bookmarks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (filtered.isEmpty()) {
            Text("No headings match your search.",
                modifier = Modifier.padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = FolioSpacing.dp24),
                contentPadding = PaddingValues(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                itemsIndexed(filtered) { _, entry ->
                    Surface(onClick = { onOpen(entry.pageIndex) }, shape = FolioShapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                        Row(Modifier.fillMaxWidth()
                            .padding(start = FolioSpacing.dp16 + FolioSpacing.dp16 * entry.depth.coerceIn(0, 3), end = FolioSpacing.dp16, top = FolioSpacing.dp10, bottom = FolioSpacing.dp10),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                                val title = buildAnnotatedString {
                                    var start = 0
                                    var match = if (query.isEmpty()) -1 else entry.title.indexOf(query, ignoreCase = true)
                                    while (match >= 0) {
                                        append(entry.title.substring(start, match))
                                        withStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)) {
                                            append(entry.title.substring(match, match + query.length))
                                        }
                                        start = match + query.length
                                        match = entry.title.indexOf(query, start, ignoreCase = true)
                                    }
                                    append(entry.title.substring(start))
                                }
                                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Page ${entry.pageIndex + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(FolioIcons.Forward, "Open ${entry.title}")
                        }
                    }
                }
            }
        }
    }
}
