@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Text search over the reference pane's PDF. The same matching as the editor's own PDF search, so
 * "quadratic" finds the same pages beside a paper as it does under it; hits jump the pane, and the
 * previous/next buttons walk every matching page in reading order.
 */
@Composable internal fun ReferenceSearchPanel(
    search: PdfSearchState,
    currentIndex: Int,
    onQuery: (String) -> Unit,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf(search.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() { if (query.isNotBlank()) { keyboard?.hide(); onQuery(query.trim()) } }
    FolioPanel(title = "Search this PDF", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth(),
                label = { Text("Find in this PDF") },
                placeholder = { Text("e.g. quadratic formula") },
                singleLine = true,
                shape = FolioShapes.large,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton({ query = ""; onQuery("") }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Clear, "Clear search")
                    }
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Button(::submit, enabled = query.isNotBlank() && !search.searching, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Search")
                }
                val pages = remember(search.results) { PdfReference.hitPages(search.results) }
                if (pages.size > 1) {
                    OutlinedButton({ PdfReference.nextHit(currentIndex, pages, forward = false)?.let(onJump) }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous match", Modifier.size(18.dp))
                    }
                    OutlinedButton({ PdfReference.nextHit(currentIndex, pages, forward = true)?.let(onJump) }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next match", Modifier.size(18.dp))
                    }
                }
            }
            when {
                search.searching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    LoadingIndicator(Modifier.size(24.dp).semantics { contentDescription = "Searching PDF" })
                    Text("Reading this PDF's text…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                search.query.trim() != query.trim() -> Text("Press Search to find “${query.trim().take(80)}”.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                search.searched && search.query.isNotBlank() && search.results.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp12),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Icon(Icons.Rounded.SearchOff, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No matches for “${search.query.trim().take(80)}”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Scanned or locked PDFs have no searchable text.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                search.results.isNotEmpty() -> {
                    Text(
                        "${search.results.size} ${if (search.results.size == 1) "page matches" else "pages match"} — most matches first.",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 368.dp), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        items(search.results, key = { it.pageIndex }) { hit ->
                            Surface(onClick = { onJump(hit.pageIndex) }, shape = FolioShapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Page ${hit.pageIndex + 1} · ${hit.matchCount}×", style = MaterialTheme.typography.titleSmall)
                                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open page ${hit.pageIndex + 1}")
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
        if (outline == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                LoadingIndicator(Modifier.size(24.dp).semantics { contentDescription = "Loading contents" })
                Text("Reading bookmarks…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (outline.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This PDF has no bookmarks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 428.dp).padding(bottom = FolioSpacing.dp16),
                contentPadding = PaddingValues(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                itemsIndexed(outline) { _, entry ->
                    Surface(onClick = { onOpen(entry.pageIndex) }, shape = FolioShapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                        Row(Modifier.fillMaxWidth()
                            .padding(start = FolioSpacing.dp16 + FolioSpacing.dp16 * entry.depth, end = FolioSpacing.dp16, top = FolioSpacing.dp10, bottom = FolioSpacing.dp10),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
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

/**
 * Jump to any page of the reference, by number or relative to the open one — the fastest way
 * through a 60-page paper when you know it is "around 40" or "eight pages back".
 */
@Composable internal fun ReferenceJumpDialog(
    currentIndex: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var entry by remember { mutableStateOf("") }
    val target = remember(entry, currentIndex, pageCount) { PdfReference.parseJump(entry, currentIndex, pageCount) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                OutlinedTextField(
                    entry, { entry = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Page") },
                    placeholder = { Text("12, p 12, +3 or last") },
                    singleLine = true,
                    shape = FolioShapes.large,
                    isError = entry.isNotBlank() && target == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { target?.let(onJump) })
                )
                Text(PdfReference.jumpHint(currentIndex, pageCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button({ target?.let(onJump) }, enabled = target != null) { Text(if (target != null) "Open page ${target + 1}" else "Open") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

/**
 * A filmstrip of the reference's pages, so a long paper can be found by eye rather than by number.
 * Previews come from the shared thumbnail cache, so the strip costs nothing on pages already seen.
 */
@Composable internal fun ReferenceFilmstrip(
    noteId: String,
    note: Notebook,
    currentIndex: Int,
    thumbnails: PageThumbnailCache,
    onPick: (Int) -> Unit
) {
    val widthPx = with(LocalDensity.current) { 52.dp.roundToPx() }
    val strip = rememberLazyListState()
    // Follow the pane, but only while the current page is off the strip — a manual scroll wins.
    LaunchedEffect(currentIndex, note.id) {
        val visible = strip.layoutInfo.visibleItemsInfo.map { it.index }
        if (currentIndex !in visible) strip.scrollToItem(currentIndex)
    }
    LazyRow(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp4).guardUiTouches(),
        state = strip, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        itemsIndexed(note.pages, key = { _, page -> page.id }) { index, page ->
            val current = index == currentIndex
            Surface(
                onClick = { onPick(index) },
                modifier = Modifier
                    .width(52.dp)
                    .heightIn(max = 76.dp)
                    .semantics { contentDescription = "Reference page ${index + 1} of ${note.pages.size}" },
                shape = FolioShapes.small,
                color = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.White,
                border = BorderStroke(if (current) 2.dp else 1.dp,
                    if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val preview by produceState<Bitmap?>(null, noteId, page.id, page.revision, page.loaded, widthPx) {
                        value = try { thumbnails.thumbnail(noteId, page, widthPx) } catch (_: Exception) { null }
                    }
                    val bitmap = preview
                    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    else if (!page.loaded) LoadingIndicator(Modifier.size(16.dp))
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall,
                        color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.7f), FolioShapes.small).padding(horizontal = 3.dp))
                }
            }
        }
    }
}
