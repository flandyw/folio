@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes.editor.page

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.EditorBrowserState
import com.folio.notes.editor.EditorOverlayState
import com.folio.notes.editor.dialogs.BatchPagesPanel
import com.folio.notes.editor.dialogs.EditorPageDialogs
import com.folio.notes.model.PageFilter
import com.folio.notes.model.displayTitle
import com.folio.notes.model.organizePages
import com.folio.notes.model.resolvePageLabel
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable internal fun EditorPageBrowser(state: FolioState, model: FolioViewModel, overlays: EditorOverlayState,
    browser: EditorBrowserState, jumpTo: (Int) -> Unit, addPage: () -> Unit, revealNewPage: (Int) -> Unit,
    openPaperMenu: (Boolean) -> Unit, onExport: () -> Unit) {
    val note = state.active ?: return
    val page = state.page ?: return
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var pageQuery by browser.query
    var pageFilter by browser.filter
    var pageNumber by browser.number
    var pageBrowserGrid by browser.grid
    var pageJumpExpanded by browser.jumpExpanded
    var pageBrowser by overlays.pageBrowser
    var namedPage by overlays.namedPage
    var pageTitle by overlays.pageTitle
    var movingPage by overlays.movingPage
    var destinationPage by overlays.destinationPage
    var deletingPage by overlays.deletingPage
    var batching by remember { mutableStateOf(false) }
    val pdfLabels by produceState<Map<Int, String>>(emptyMap(), note.id) { value = runCatching { model.repository.pdfs.pdfPageLabels(note.id) }.getOrDefault(emptyMap()) }
    if (batching) BatchPagesPanel(note, model, onExport) { batching = false }
    if (pageBrowser) FolioPanel(title = "Notebook pages", onDismissRequest = { pageBrowser = false }) {
        val visiblePages = remember(note.pages, pageQuery, pageFilter, pdfLabels) {
            val labelled = resolvePageLabel(note.pages, pdfLabels, pageQuery)
            organizePages(note.pages, if (labelled != null && pageQuery.toIntOrNull() == null) (labelled + 1).toString() else pageQuery, pageFilter)
        }
        val canDrag = !pageBrowserGrid && pageQuery.isBlank() && pageFilter == PageFilter.ALL
        val browserPages = rememberLazyListState()
        val browserGrid = rememberLazyGridState()
        LaunchedEffect(pageQuery, pageFilter, pageBrowserGrid) {
            val current = visiblePages.indexOfFirst { it.value.id == page.id }
            if (pageBrowserGrid) browserGrid.scrollToItem(current.coerceAtLeast(0))
            else browserPages.scrollToItem(current.coerceAtLeast(0))
        }
        fun goToPage() {
            val target = resolvePageLabel(note.pages, pdfLabels, pageNumber) ?: return
            jumpTo(target); pageBrowser = false; pageNumber = ""
        }
        OutlinedTextField(pageQuery, { pageQuery = it }, Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24),
            label = { Text("Find a page by name or number") }, placeholder = { Text("e.g. Quadratics or 12") }, singleLine = true,
            shape = FolioShapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (pageQuery.isNotEmpty()) IconButton({ pageQuery = "" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear page search") } })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            PageFilter.entries.forEach { option ->
                FilterChip(pageFilter == option, { pageFilter = option }, { Text(option.label) })
            }
        }
        TextButton({ pageJumpExpanded = !pageJumpExpanded }, modifier = Modifier.padding(horizontal = FolioSpacing.dp24)) {
            Icon(Icons.Rounded.Numbers, null); Spacer(Modifier.width(FolioSpacing.dp8))
            Text(if (pageJumpExpanded) "Hide page jump" else "Go to a page number")
        }
        if (pageJumpExpanded) Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            val jumpFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { jumpFocus.requestFocus() }
            OutlinedTextField(pageNumber, { pageNumber = it.take(100) },
                label = { Text("Page number, name or PDF label") }, supportingText = { Text("Use label:3 for printed PDF page 3, or name:12 for a page named 12.") }, singleLine = true,
                shape = FolioShapes.large,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { goToPage() }),
                modifier = Modifier.weight(1f).focusRequester(jumpFocus))
            FilledTonalButton(::goToPage,
                enabled = resolvePageLabel(note.pages, pdfLabels, pageNumber) != null,
                shapes = ButtonDefaults.shapes()) { Text("Go") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24), verticalAlignment = Alignment.CenterVertically) {
            Text(if (canDrag) "Long-press a page and drag to reorder it." else "${visiblePages.size} pages · tap to open", Modifier.weight(1f).padding(start = FolioSpacing.dp8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ pageBrowserGrid = !pageBrowserGrid }) {
                Icon(if (pageBrowserGrid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    if (pageBrowserGrid) "Show page list" else "Show page thumbnails")
            }
        }
        TextButton({ batching = true }) { Text("Select pages for batch actions") }
        val rowHeight = 112.dp
        val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragDelta by remember { mutableFloatStateOf(0f) }
        var dragY by remember { mutableStateOf<Float?>(null) }
        var dragScroll by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(dragFrom) {
            while (dragY != null && dragFrom != null) {
                val y = dragY!!
                val height = browserPages.layoutInfo.viewportSize.height
                val delta = when { y < 80f -> -18f; y > height - 80f -> 18f; else -> 0f }
                if (delta != 0f) { var consumed = 0f; browserPages.scroll { consumed = scrollBy(delta) }; dragScroll += consumed }
                kotlinx.coroutines.delay(24)
            }
        }
        val insertion = dragFrom?.let { (it + ((dragDelta + dragScroll) / rowHeightPx).roundToInt()).coerceIn(0, note.pages.lastIndex) }
        if (insertion != null) Text("Release to move page ${dragFrom!! + 1} to position ${insertion + 1}", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelMedium)
        if (pageBrowserGrid && visiblePages.isNotEmpty()) LazyVerticalGrid(
            columns = GridCells.Adaptive(144.dp), state = browserGrid,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp8),
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
        ) {
            gridItems(visiblePages, key = { it.value.id }) { (index, item) ->
                var menu by remember(item.id) { mutableStateOf(false) }
                Surface(onClick = { jumpTo(index); pageBrowser = false }, shape = FolioShapes.large,
                    color = if (index == state.pageIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        PageThumbnail(note.id, item, model.thumbnails, Modifier.fillMaxWidth().height(152.dp), previewWidth = 144.dp)
                        Text(item.displayTitle(index), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            IconButton({ model.togglePageBookmark(item.id) }) {
                                Icon(if (item.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Bookmark page ${index + 1}")
                            }
                            Box {
                                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Page ${index + 1} options") }
                                PageRowMenu(item, menu, { menu = false },
                                    canMoveUp = index > 0, canMoveDown = index < note.pages.lastIndex, canDelete = note.pages.size > 1,
                                    onName = { namedPage = item; pageTitle = item.title }, onBookmark = { model.togglePageBookmark(item.id) },
                                    onRedoFlag = { model.setPageRedoFlag(note.id, item.id, !item.redoFlag) },
                                    onMoveTo = { movingPage = item.id; destinationPage = (index + 1).toString() },
                                    onMoveUp = { model.movePage(index, index - 1) }, onMoveDown = { model.movePage(index, index + 1) },
                                    onInsert = { model.insertPage(index + 1) }, onDuplicate = { model.duplicatePage(index) },
                                    onDelete = { deletingPage = item.id })
                            }
                        }
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = browserPages, contentPadding = PaddingValues(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            if (visiblePages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Icon(Icons.Rounded.SearchOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No pages match", style = MaterialTheme.typography.titleSmall)
                    Text("Try another name or filter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton({ pageQuery = ""; pageFilter = PageFilter.ALL }) { Text("Show all pages") }
                }
            }
            items(visiblePages, key = { it.value.id }) { (index, item) ->
                val dragging = dragFrom == index
                PageRow(item, index, state.pageIndex == index, dragging,
                    Modifier.height(rowHeight)
                        .then(if (insertion == index && dragFrom != index) Modifier.drawBehind { drawLine(androidx.compose.ui.graphics.Color(0xFF527A5A), androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 4.dp.toPx()) } else Modifier)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragDelta else 0f }
                        .pointerInput(item.id, index, canDrag, note.pages.size) {
                            if (canDrag) detectDragGesturesAfterLongPress(
                                onDragStart = { dragFrom = index; dragDelta = 0f; dragScroll = 0f; dragY = browserPages.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }?.offset?.toFloat() ?: 0f },
                                onDrag = { change, amount -> change.consume(); dragDelta += amount.y; dragY = (browserPages.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }?.offset ?: 0) + change.position.y },
                                onDragEnd = {
                                    val from = dragFrom
                                    if (from != null) {
                                        val to = (from + ((dragDelta + dragScroll) / rowHeightPx).roundToInt()).coerceIn(0, note.pages.lastIndex)
                                        if (to != from) model.movePage(from, to)
                                    }
                                    dragFrom = null; dragDelta = 0f; dragY = null; dragScroll = 0f
                                },
                                onDragCancel = { dragFrom = null; dragDelta = 0f; dragY = null; dragScroll = 0f }
                            )
                        },
                    onOpen = { jumpTo(index); pageBrowser = false },
                    onMoveUp = { model.movePage(index, index - 1) }, onMoveDown = { model.movePage(index, index + 1) },
                    onDuplicate = { model.duplicatePage(index) }, onInsert = { model.insertPage(index + 1) },
                    onDelete = { deletingPage = item.id },
                    onName = { namedPage = item; pageTitle = item.title },
                    onBookmark = { model.togglePageBookmark(item.id) },
                    onRedoFlag = { model.setPageRedoFlag(note.id, item.id, !item.redoFlag) },
                    onMoveTo = { movingPage = item.id; destinationPage = (index + 1).toString() },
                    canMoveUp = index > 0, canMoveDown = index < note.pages.lastIndex, canDelete = note.pages.size > 1,
                    noteId = note.id, thumbnails = model.thumbnails)
            }
        }
        HorizontalDivider()
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12),
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            FilledTonalButton({ addPage(); pageBrowser = false }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add page")
            }
            OutlinedButton({ revealNewPage(model.insertPage(state.pageIndex + 1)); pageBrowser = false }, shapes = ButtonDefaults.shapes()) {
                Text("Insert after ${state.pageIndex + 1}")
            }
            TextButton({ model.duplicatePage()?.let { revealNewPage(it) }; pageBrowser = false }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Duplicate")
            }
            TextButton({ pageBrowser = false; openPaperMenu(true) }, shapes = ButtonDefaults.shapes()) { Text("Choose paper") }
        }
        EditorPageDialogs(note, model, overlays)
    }
}
