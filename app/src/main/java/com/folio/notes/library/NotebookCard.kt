@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes.library

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.editor.page.PageThumbnailCache
import com.folio.notes.exam.ExamBadges
import com.folio.notes.input.FolioTouch
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioActionPopover
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.PopoverRow
import com.folio.notes.ui.PopoverTile
import com.folio.notes.ui.folioSelected

@Composable internal fun NotebookCard(note: Notebook, thumbnails: PageThumbnailCache, folder: String?, open: () -> Unit, star: () -> Unit, rename: () -> Unit, move: () -> Unit, delete: () -> Unit, examDetails: () -> Unit = {}, recordMark: (() -> Unit)? = null, selecting: Boolean = false, redoCount: Int = 0, selected: Boolean = false, onLongPress: () -> Unit = {}, duplicate: () -> Unit = {}, backupExcluded: Boolean = false, onBackupToggle: () -> Unit = {}, onChangeCover: () -> Unit = {}, onTags: () -> Unit = {}, pinned: Boolean = false, pin: (() -> Unit)? = null, createLike: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box {
            NotebookFace(note, thumbnails, Modifier.fillMaxWidth().combinedClickable(onClickLabel = if (selecting) "Toggle selection for ${note.title}" else "Open ${note.title}", onClick = open, onLongClick = onLongPress))
            if (pinned) Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(FolioSpacing.dp8),
                shape = FolioShapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) { Icon(Icons.Rounded.PushPin, "Pinned", Modifier.padding(FolioSpacing.dp4).size(16.dp)) }
            if (selecting) {
                val checked = selected
                Surface(
                    onClick = open,
                    shape = FolioShapes.medium,
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .92f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.align(Alignment.TopStart).padding(FolioSpacing.dp8).size(FolioTouch.target).folioSelected(checked).semanticsLabel(if (checked) "Deselect ${note.title}" else "Select ${note.title}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            } else {
                IconButton(star, modifier = Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp2), shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        if (note.starred) "Remove from favorites" else "Add to favorites",
                        tint = if (note.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .85f),
                        modifier = Modifier.size(21.dp).folioSelected(note.starred)
                    )
                }
            }
        }
        Row(Modifier.padding(top = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).combinedClickable(onClick = open, onLongClick = onLongPress)) {
                                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull("${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"}", folder, libraryLastEditedLabel(note.updated)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
            if (!selecting) NotebookMenu(rename, move, delete, examDetails, recordMark, duplicate, backupExcluded, onBackupToggle, onChangeCover, onTags, title = note.title, pinned = pinned, pin = pin, createLike = createLike)
        }
        if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (backupExcluded) Text("Excluded from library backups", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExamBadges(note, modifier = Modifier.padding(top = FolioSpacing.dp4), redoCount = redoCount)
    }
}

@Composable internal fun NotebookMenu(rename: () -> Unit, move: () -> Unit, delete: () -> Unit, examDetails: () -> Unit = {}, recordMark: (() -> Unit)? = null, duplicate: () -> Unit = {}, backupExcluded: Boolean = false, onBackupToggle: () -> Unit = {}, onChangeCover: () -> Unit = {}, onTags: () -> Unit = {}, title: String = "Notebook options", pinned: Boolean = false, pin: (() -> Unit)? = null, createLike: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for $title") }
        if (menu) FolioActionPopover(title, { menu = false }) {
            val run: (() -> Unit) -> Unit = { menu = false; it() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                PopoverTile(Icons.Rounded.Edit, "Rename", Modifier.weight(1f)) { run(rename) }
                PopoverTile(Icons.Rounded.FolderOpen, "Move", Modifier.weight(1f)) { run(move) }
                PopoverTile(Icons.Rounded.ContentCopy, "Duplicate", Modifier.weight(1f)) { run(duplicate) }
            }
            createLike?.let { action -> PopoverRow(Icons.AutoMirrored.Rounded.NoteAdd, "Create another like this") { run(action) } }
            pin?.let { action ->
                PopoverRow(Icons.Rounded.PushPin, if (pinned) "Unpin" else "Pin to top") { run(action) }
            }
            PopoverRow(Icons.Rounded.Sell, "Tags") { run(onTags) }
            PopoverRow(Icons.AutoMirrored.Rounded.FactCheck, "Exam details") { run(examDetails) }
            recordMark?.let { action -> PopoverRow(Icons.AutoMirrored.Rounded.Grading, "Record a mark") { run(action) } }
            PopoverRow(Icons.Rounded.Palette, "Change cover") { run(onChangeCover) }
            PopoverRow(if (backupExcluded) Icons.Rounded.Backup else Icons.Rounded.CloudOff,
                if (backupExcluded) "Include in library backups" else "Exclude from library backups") { run(onBackupToggle) }
            HorizontalDivider(Modifier.padding(vertical = FolioSpacing.dp8))
            PopoverRow(Icons.Rounded.DeleteOutline, "Move to Trash", destructive = true) { run(delete) }
        }
    }
}

/**
 * A notebook's face on the shelf: either the first page itself with no book decoration, or the
 * decorative default cover. The first-page cover is drawn from the same preview cache the page
 * browser uses; until that page has been drawn there is nothing to show, so a fresh notebook
 * keeps its decorative cover and its title either way.
 */
@Composable fun NotebookFace(note: Notebook, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier) {
    val preview = rememberNotebookPreview(note, thumbnails, with(LocalDensity.current) { 260.dp.roundToPx() })
    val first = note.pages.firstOrNull()
    // Reserve the page's own aspect up front so the card never jumps when the preview
    // arrives a frame later; the decorative cover simply fills the same box until then.
    val aspect = first?.let { page ->
        val ratio = if (page.height > 0) page.width / page.height else 0.7f
        ratio.coerceIn(0.4f, 1.5f)
    } ?: 0.71f
    val bitmap = preview
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    if (note.pageCover && imageBitmap != null) {
        Box(
            modifier
                .aspectRatio(aspect)
                .clip(FolioShapes.medium)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FolioShapes.medium),
            contentAlignment = Alignment.Center
        ) {
            Image(imageBitmap, "First page preview of ${note.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    } else {
        NotebookCover(note, modifier.aspectRatio(aspect), compact = true)
    }
}

/** A compact list row's small preview, falling back to a type icon before one has been drawn. */
@Composable internal fun NotebookListThumbnail(note: Notebook, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier) {
    val preview = rememberNotebookPreview(note, thumbnails, with(LocalDensity.current) { 44.dp.roundToPx() })
    val imageBitmap = remember(preview) { preview?.asImageBitmap() }
    val isPdf = note.pages.any { it.pdfIndex != null }
    Box(modifier.clip(FolioShapes.small).background(Color.White), contentAlignment = Alignment.Center) {
        if (imageBitmap != null) Image(imageBitmap, "First page preview of ${note.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(if (isPdf) Icons.Rounded.PictureAsPdf else Icons.AutoMirrored.Rounded.MenuBook, if (isPdf) "PDF notebook ${note.title}" else "Notebook ${note.title}")
    }
}

/** The first page's preview, drawn once per revision and served from the cache after that. */
@Composable private fun rememberNotebookPreview(note: Notebook, thumbnails: PageThumbnailCache, widthPx: Int): Bitmap? {
    val first = note.pages.firstOrNull()
    var preview by remember(note.id, first?.id, first?.revision, widthPx) { mutableStateOf<Bitmap?>(null) }
    // Keep the previous preview until the new revision arrives so covers never flash blank.
    LaunchedEffect(note.id, first?.id, first?.revision, widthPx) {
        first?.let { preview = thumbnails.thumbnail(note.id, it, widthPx) }
    }
    return preview
}

@Composable fun NotebookCover(note: Notebook, modifier: Modifier = Modifier, compact: Boolean = false) {
    CoverFace(note.title, note.pages.any { it.pdfIndex != null }, note.cover, modifier, compact)
}
