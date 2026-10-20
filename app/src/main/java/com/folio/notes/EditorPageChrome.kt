@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable internal fun PageNavigator(
    index: Int, count: Int, singlePage: Boolean, onTurn: (forward: Boolean) -> Unit, onJumpToEnd: (last: Boolean) -> Unit,
    onToggleView: () -> Unit, onBrowse: () -> Unit, modifier: Modifier = Modifier,
) {
    // The writing-follow bar, repurposed: the same floating toolbar container, 44dp height and 40dp circular controls.
    // A second tap inside the settle window is a bounce or a resting palm, not a deliberate turn.
    var lastTap by remember { mutableLongStateOf(0L) }
    fun settled(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        return (now - lastTap >= 125L).also { if (it) lastTap = now }
    }
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier.height(44.dp).semantics { contentDescription = "Page ${index + 1} of $count" },
        contentPadding = PaddingValues(horizontal = FolioSpacing.dp4, vertical = FolioSpacing.dp2),
    ) {
        WritingFollowControl(if (singlePage) Icons.Rounded.ViewAgenda else Icons.Rounded.ViewCarousel,
            if (singlePage) "Switch to continuous scroll" else "Switch to single page view",
            active = singlePage, onClick = { if (settled()) onToggleView() })
        PageNavigationArrow(false, index > 0, { if (settled()) onTurn(false) }, { if (settled()) onJumpToEnd(false) })
        // Sized for the widest label ("12 / 12") with tabular digits, so the arrows never shift as the page changes.
        val counter = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
        Box(Modifier.height(40.dp).widthIn(min = 44.dp).clickable(onClickLabel = "Browse notebook pages", onClick = onBrowse), contentAlignment = Alignment.Center) {
            Text("$count / $count", style = counter, color = Color.Transparent)
            Text("${index + 1} / $count", textAlign = TextAlign.Center, style = counter, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PageNavigationArrow(true, index < count - 1, { if (settled()) onTurn(true) }, { if (settled()) onJumpToEnd(true) })
    }
}

/** Study and insert/find actions shared by the floating and stacked editor chrome. */
@Composable internal fun NotebookOptionsContent(
    dismiss: () -> Unit, hasPdf: Boolean, saveFailed: Boolean, onRetry: () -> Unit,
    onExam: () -> Unit, onRecordMark: () -> Unit, onTimer: () -> Unit, onInsertImage: () -> Unit, onSearchPdf: () -> Unit,
    onContents: () -> Unit, onSearchNotes: () -> Unit,
) {
    val run: (() -> Unit) -> Unit = { dismiss(); it() }
    PopoverGroup("Study") {
        PopoverRow(Icons.AutoMirrored.Rounded.FactCheck, "Exam details") { run(onExam) }
        PopoverRow(Icons.AutoMirrored.Rounded.Grading, "Record a mark") { run(onRecordMark) }
        PopoverRow(Icons.Rounded.Timer, "Timer & stopwatch") { run(onTimer) }
    }
    PopoverGroup("Insert & find") {
        PopoverRow(Icons.Rounded.AddPhotoAlternate, "Insert picture") { run(onInsertImage) }
        PopoverRow(Icons.Rounded.FindInPage, "Find in notes") { run(onSearchNotes) }
        PopoverRow(Icons.Rounded.Search, "Search PDF text", enabled = hasPdf) { run(onSearchPdf) }
        PopoverRow(Icons.AutoMirrored.Rounded.FormatListBulleted, "Contents", enabled = hasPdf) { run(onContents) }
    }
    if (saveFailed) PopoverRow(Icons.Rounded.Save, "Retry save") { run(onRetry) }
}

/**
 * The page commands shared by the page drawer and the page browser row, so one list of moves
 * governs a page wherever it is shown. Callers close their own menu via [onDismiss].
 */
@Composable internal fun PageRowMenu(
    page: NotePage, expanded: Boolean, onDismiss: () -> Unit,
    canMoveUp: Boolean, canMoveDown: Boolean, canDelete: Boolean,
    onName: () -> Unit, onBookmark: () -> Unit, onRedoFlag: () -> Unit, onMoveTo: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit, onInsert: () -> Unit, onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    if (!expanded) return
    val run: (() -> Unit) -> Unit = { onDismiss(); it() }
    FolioPopover(onDismiss, width = 280.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            PopoverTile(Icons.Rounded.Edit, "Rename", Modifier.weight(1f)) { run(onName) }
            PopoverTile(if (page.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                if (page.bookmarked) "Bookmarked" else "Bookmark", Modifier.weight(1f), active = page.bookmarked) { run(onBookmark) }
            PopoverTile(if (page.redoFlag) Icons.Rounded.Refresh else Icons.Rounded.OutlinedFlag,
                if (page.redoFlag) "Clear redo" else "Redo", Modifier.weight(1f), active = page.redoFlag) { run(onRedoFlag) }
        }
        Column {
            PopoverRow(Icons.Rounded.LowPriority, "Move to position…") { run(onMoveTo) }
            PopoverRow(Icons.Rounded.KeyboardArrowUp, "Move up", enabled = canMoveUp) { run(onMoveUp) }
            PopoverRow(Icons.Rounded.KeyboardArrowDown, "Move down", enabled = canMoveDown) { run(onMoveDown) }
            PopoverRow(Icons.Rounded.Add, "Insert blank page after") { run(onInsert) }
            PopoverRow(Icons.Rounded.ContentCopy, "Duplicate page") { run(onDuplicate) }
        }
        HorizontalDivider()
        PopoverRow(Icons.Rounded.DeleteOutline, "Delete page", enabled = canDelete, destructive = true) { run(onDelete) }
    }
}

/** One row of the page browser: a rendered thumbnail, its details, and reorder/duplicate/delete. */
@Composable internal fun PageRow(
    page: NotePage, index: Int, current: Boolean, dragging: Boolean, modifier: Modifier = Modifier,
    onOpen: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onDuplicate: () -> Unit, onInsert: () -> Unit, onDelete: () -> Unit,
    onName: () -> Unit, onBookmark: () -> Unit, onRedoFlag: () -> Unit, onMoveTo: () -> Unit,
    canMoveUp: Boolean, canMoveDown: Boolean, canDelete: Boolean,
    noteId: String, thumbnails: PageThumbnailCache
) {
    var menu by remember { mutableStateOf(false) }
    FolioSwipeRow(modifier.fillMaxWidth(), shape = FolioShapes.extraLarge,
        startToEnd = SwipeAction(if (page.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (page.bookmarked) "Remove bookmark" else "Bookmark page", perform = onBookmark),
        endToStart = if (canDelete) SwipeAction(Icons.Rounded.DeleteOutline, "Delete page", destructive = true, perform = onDelete) else null) {
    Surface(onClick = onOpen, shape = FolioShapes.extraLarge, modifier = Modifier.fillMaxSize(),
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = if (dragging) 6.dp else 1.dp, tonalElevation = if (current) 1.dp else 0.dp,
        border = BorderStroke(
            if (current) 1.5.dp else 1.dp,
            if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )) {
        Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp10, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            PageThumbnail(noteId, page, thumbnails, Modifier.width(64.dp).aspectRatio(page.width / page.height).clip(FolioShapes.small))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Text(page.displayTitle(index), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (current) Surface(shape = FolioShapes.small, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Text("Open", Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp2), style = MaterialTheme.typography.labelSmall)
                    }
                }
                // A page that has not been read yet cannot say how much ink it holds.
                val detail = "Page ${index + 1} · " + (if (page.pdfIndex != null) "Imported PDF" else editorPaperLabel(page.paper))
                val content = if (page.loaded) {
                    val bits = mutableListOf<String>()
                    if (page.strokes.isNotEmpty()) bits += "${page.strokes.size} marks"
                    if (page.texts.isNotEmpty()) bits += "${page.texts.size} text"
                    if (page.images.isNotEmpty()) bits += "${page.images.size} pics"
                    if (bits.isEmpty()) "$detail · Blank" else "$detail · " + bits.joinToString(" · ")
                } else detail
                Text(
                    content,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onRedoFlag, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
                Icon(if (page.redoFlag) Icons.Rounded.Refresh else Icons.Rounded.OutlinedFlag,
                    if (page.redoFlag) "Remove practice flag" else "Flag page for practice",
                    tint = if (page.redoFlag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onBookmark, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(if (page.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                if (page.bookmarked) "Remove bookmark" else "Bookmark page",
                tint = if (page.bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            Box {
                IconButton({ menu = true }, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Page ${index + 1} options") }
                PageRowMenu(page, menu, { menu = false },
                    canMoveUp = canMoveUp, canMoveDown = canMoveDown, canDelete = canDelete,
                    onName = onName, onBookmark = onBookmark,
                    onRedoFlag = onRedoFlag, onMoveTo = onMoveTo, onMoveUp = onMoveUp, onMoveDown = onMoveDown,
                    onInsert = onInsert, onDuplicate = onDuplicate, onDelete = onDelete)
            }
        }
    }
    }
}

/**
 * A page preview from the shared cache. The key carries the page's revision, so editing a page
 * simply fetches a new preview, and a preview that was drawn before is shown without reading the
 * page file at all.
 */
@Composable internal fun PageThumbnail(noteId: String, page: NotePage, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier, previewWidth: Dp = 64.dp) {
    val widthPx = with(LocalDensity.current) { previewWidth.roundToPx() }
    var preview by remember(noteId, page.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(noteId, page.id, page.revision, page.loaded, widthPx) {
        preview = thumbnails.thumbnail(noteId, page, widthPx)
    }
    Surface(modifier, shape = FolioShapes.small, color = Color.White, shadowElevation = 1.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = preview
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (!page.loaded) LoadingIndicator(Modifier.size(20.dp).semanticsLabel("Loading preview"))
        }
    }
}

/**
 * Changes the look of a lasso selection. Each control starts from the selection's own style and
 * only touches what the user actually changes, so recolouring never silently re-thickens the ink.
 */
