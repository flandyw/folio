@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.music

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.input.guardUiTouches
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.FolioSwipeRow
import com.folio.notes.ui.SwipeAction
import com.folio.notes.ui.folioSelected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A score on the shelf: its first page as the cover, like a notebook's page cover. */
@Composable internal fun ScoreCard(score: MusicScore, model: MusicViewModel, modifier: Modifier, open: () -> Unit, star: () -> Unit, menu: @Composable () -> Unit) {
    Column(modifier) {
        Box {
            ScoreFace(score, model, Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${score.title}", onClick = open))
            // Only scores that have been read carry a track, so a fresh library stays unmarked.
            if (score.readingProgress() > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().clip(FolioShapes.medium)) {
                LinearProgressIndicator(progress = { score.readingProgress() }, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
            // A soft scrim keeps the star legible over busy engraving on the white cover.
            IconButton(star, modifier = Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp2), shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = .68f))) {
                Icon(if (score.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (score.starred) "Remove from favorites" else "Add to favorites",
                    Modifier.size(21.dp).folioSelected(score.starred),
                    tint = if (score.starred) MaterialTheme.colorScheme.primary else Color(0xFF5F6368))
            }
        }
        Row(Modifier.padding(top = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = open)) {
                Text(score.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(scoreMeta(score), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            menu()
        }
    }
}

@Composable internal fun ScoreRow(score: MusicScore, model: MusicViewModel, modifier: Modifier, open: () -> Unit, star: () -> Unit, delete: () -> Unit, menu: @Composable () -> Unit) {
    FolioSwipeRow(modifier, shape = FolioShapes.large,
        startToEnd = SwipeAction(if (score.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (score.starred) "Remove from favorites" else "Add to favorites", perform = star),
        endToStart = SwipeAction(Icons.Rounded.DeleteOutline, "Delete score", destructive = true, perform = delete)) {
    Surface(onClick = open, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Box {
            Row(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                ScoreFace(score, model, Modifier.width(38.dp).height(50.dp), small = true)
                Column(Modifier.weight(1f)) {
                    Text(score.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(scoreMeta(score), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(star, shapes = IconButtonDefaults.shapes()) {
                    Icon(if (score.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (score.starred) "Remove from favorites" else "Add to favorites",
                        Modifier.folioSelected(score.starred))
                }
                menu()
            }
            if (score.readingProgress() > 0f) LinearProgressIndicator(progress = { score.readingProgress() },
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp))
        }
    }
    }
}

/** A set list entry: its place in the running order, with the order controls beside it. */
@Composable internal fun SetScoreRow(position: Int, score: MusicScore, model: MusicViewModel, modifier: Modifier,
    onOpen: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?, menu: @Composable () -> Unit) {
    Surface(onClick = onOpen, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4, top = FolioSpacing.dp8, bottom = FolioSpacing.dp8),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(30.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("$position", style = MaterialTheme.typography.labelLarge) }
            }
            ScoreFace(score, model, Modifier.width(38.dp).height(50.dp), small = true)
            Column(Modifier.weight(1f)) {
                Text(score.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(scoreMeta(score), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ onUp?.invoke() }, enabled = onUp != null, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ArrowUpward, "Play ${score.title} earlier") }
            IconButton({ onDown?.invoke() }, enabled = onDown != null, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ArrowDownward, "Play ${score.title} later") }
            menu()
        }
    }
}

@Composable internal fun ScoreMenu(score: MusicScore, busy: Boolean, onDetails: () -> Unit, onSets: () -> Unit, onExtract: () -> Unit,
    onExport: () -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit, onMove: () -> Unit, onRemoveFromSet: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for ${score.title}") }
        FolioMenuPopover(menu, { menu = false }, modifier = Modifier.guardUiTouches(), title = "Score actions") {
            FolioMenuItem({ Text("Details & rehearsal notes") }, { menu = false; onDetails() }, leadingIcon = { Icon(Icons.Rounded.EditNote, null) })
            FolioMenuItem({ Text("Add to set list") }, { menu = false; onSets() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
            FolioMenuItem({ Text("Move to folder") }, { menu = false; onMove() }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
            FolioMenuItem({ Text("Duplicate score") }, { menu = false; onDuplicate() }, enabled = !busy, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
            if (onRemoveFromSet != null) FolioMenuItem({ Text("Remove from this set list") }, { menu = false; onRemoveFromSet() },
                leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) }, destructive = true)
            FolioMenuItem({ Text("Extract instrument parts") }, { menu = false; onExtract() }, enabled = !busy, leadingIcon = { Icon(Icons.Rounded.ContentCut, null) })
            FolioMenuItem({ Text("Export original PDF") }, { menu = false; onExport() }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) })
            FolioMenuItem({ Text("Delete") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, destructive = true)
        }
    }
}

/** Composer, part, size and how marked-up a score is, all on one line for a card or row. */
internal fun scoreMeta(score: MusicScore): String {
    val bits = mutableListOf<String>()
    bits += listOf(score.composer, score.part).filter { it.isNotBlank() }
    bits += "${score.pages} ${if (score.pages == 1) "page" else "pages"}"
    if (score.marks.isNotEmpty()) bits += "${score.marks.size} ${if (score.marks.size == 1) "mark" else "marks"}"
    if (score.annotationCount() > 0) bits += "${score.annotationCount()} ${if (score.annotationCount() == 1) "note" else "notes"}"
    return bits.joinToString(" · ")
}

/**
 * The first page drawn small, on white like printed paper. Until it arrives (or if the PDF cannot
 * be drawn) a blank manuscript page holds the same box, so the shelf never jumps.
 */
@Composable internal fun ScoreFace(score: MusicScore, model: MusicViewModel, modifier: Modifier = Modifier, small: Boolean = false) {
    val width = with(LocalDensity.current) { (if (small) 48.dp else 220.dp).roundToPx() }
    val cover by produceState<Bitmap?>(null, score.id, width) {
        value = withContext(Dispatchers.IO) { runCatching { model.pageCache.cover(model.store.pdf(score.id), width) }.getOrNull() }
    }
    val image = remember(cover) { cover?.asImageBitmap() }
    val shape = if (small) FolioShapes.small else FolioShapes.medium
    Box(modifier.then(if (small) Modifier else Modifier.aspectRatio(.71f)).clip(shape).background(Color.White)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, "First page of ${score.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else ManuscriptPage(Modifier.fillMaxSize(), sparse = small)
    }
}

/** Blank staves: the placeholder for a score cover and the shelf's empty-state artwork. */
@Composable private fun ManuscriptPage(modifier: Modifier, sparse: Boolean = false) {
    val line = Color(0xFFB9BDC3)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (!sparse) drawRoundRect(line.copy(alpha = .55f), Offset(w * .3f, h * .07f), Size(w * .4f, h * .028f), CornerRadius(h * .014f))
        val systems = if (sparse) 4 else 6
        val gap = h * (if (sparse) .022f else .013f)
        val top = h * (if (sparse) .16f else .17f)
        val pitch = (h * .9f - top) / systems
        repeat(systems) { system ->
            val y = top + system * pitch
            repeat(5) { drawLine(line, Offset(w * .1f, y + it * gap), Offset(w * .9f, y + it * gap), strokeWidth = (h * .0022f).coerceAtLeast(1f)) }
        }
    }
}

/** Two sheets of manuscript, the front one stamped with a note: the empty shelf's cover. */
@Composable internal fun ScoreStack(modifier: Modifier) {
    Box(modifier) {
        Box(Modifier.fillMaxSize(.86f).align(Alignment.TopStart).rotate(-6f).clip(FolioShapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer))
        Box(Modifier.fillMaxSize(.86f).align(Alignment.BottomEnd).rotate(3f).clip(FolioShapes.medium).background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FolioShapes.medium)) {
            ManuscriptPage(Modifier.fillMaxSize())
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).padding(FolioSpacing.dp8)) {
                Icon(Icons.Rounded.MusicNote, null, Modifier.padding(FolioSpacing.dp6).size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
