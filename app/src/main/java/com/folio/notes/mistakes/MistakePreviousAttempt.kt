@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folio.notes.FolioViewModel
import com.folio.notes.Notebook

/**
 * Read-only look at how the same question was worked before. The current review
 * page stays mounted underneath, so nothing written is lost by peeking.
 */
@Composable
internal fun PreviousAttemptDialog(
    attempts: List<Pair<Notebook, LocalMistakeReviewAttempt>>,
    folio: FolioViewModel,
    onClose: () -> Unit,
) {
    var selected by remember(attempts) { mutableIntStateOf(0) }
    val current = attempts.getOrNull(selected.coerceIn(attempts.indices))
    var enlargedPage by remember(attempts, selected) { mutableIntStateOf(-1) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Close previous working") }
                    Column(Modifier.weight(1f)) {
                        Text("Your previous working", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Read-only · your current page keeps saving underneath",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (current == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No previous attempts yet — your first page will appear here next time.")
                    }
                } else {
                    if (attempts.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            attempts.forEachIndexed { index, (_, a) ->
                                val label = buildString {
                                    append(a.completedAt?.take(10) ?: "Unfinished")
                                    a.rating?.let { append(" · ${it.replaceFirstChar(Char::uppercase)}") }
                                }
                                FilterChip(
                                    selected = index == selected,
                                    onClick = { selected = index },
                                    label = { Text(label, maxLines = 1) },
                                )
                            }
                        }
                    }
                    val (note, attempt) = current
                    Text(
                        attemptLabel(attempt, note),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val totalPages = note.pages.size
                        note.pages.forEachIndexed { pageIndex, page ->
                            PreviousAttemptPage(
                                noteId = note.id,
                                page = page,
                                pageIndex = pageIndex,
                                totalPages = totalPages,
                                folio = folio,
                                onEnlarge = { enlargedPage = pageIndex },
                            )
                        }
                        Text(
                            "Stuck? Copy the approach, not the answer — then close this and keep writing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                    FilledTonalButton(onClose, shapes = ButtonDefaults.shapes()) { Text("Back to this attempt") }
                }
            }
        }
    }
    val note = current?.first
    if (enlargedPage >= 0 && note != null && enlargedPage < note.pages.size) {
        PreviousPageViewer(
            noteId = note.id,
            pages = note.pages,
            startIndex = enlargedPage,
            folio = folio,
            onClose = { enlargedPage = -1 },
        )
    }
}

private fun attemptLabel(a: LocalMistakeReviewAttempt, note: Notebook): String {
    val date = a.completedAt?.take(10) ?: "Unfinished"
    val rating = a.rating?.replaceFirstChar(Char::uppercase)?.let { " · $it" } ?: ""
    val pages = "${note.pages.size} page${if (note.pages.size == 1) "" else "s"}"
    return "$date$rating · $pages · read-only · tap a page to zoom"
}

@Composable
private fun PreviousAttemptPage(
    noteId: String,
    page: com.folio.notes.NotePage,
    pageIndex: Int,
    totalPages: Int,
    folio: FolioViewModel,
    onEnlarge: () -> Unit,
) {
    // Render at ~2x display width so full-width ink stays sharp on dense screens
    // and holds up to a first level of pinch zoom. The old 420px bucket stretched
    // 2-4x on modern phones/tablets, which is what looked pixelated.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val targetWidthPx = remember(maxWidth, density) {
            (maxWidth.value * density.density * 2f).toInt().coerceIn(720, 2560)
        }
        var bitmap by remember(noteId, page.id, page.revision, targetWidthPx) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(noteId, page.id, page.revision) { mutableStateOf(false) }
        var retry by remember(noteId, page.id, page.revision) { mutableIntStateOf(0) }
        LaunchedEffect(noteId, page.id, page.revision, targetWidthPx, retry) {
            try {
                bitmap = folio.thumbnails.fullPage(noteId, page, targetWidthPx)
                failed = bitmap == null
            } catch (_: Exception) { failed = true }
        }
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
            Column {
                Box(
                    Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = bitmap
                    when {
                        image != null -> Image(
                            image.asImageBitmap(),
                            "Previous working, page ${pageIndex + 1} — tap to enlarge",
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                .clickable(onClickLabel = "Enlarge page ${pageIndex + 1}") { onEnlarge() },
                            contentScale = ContentScale.FillWidth,
                        )
                        failed -> Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "This page could not be drawn.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Your current ink is unaffected.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton({ retry++ }, shapes = ButtonDefaults.shapes()) { Text("Retry") }
                        }
                        else -> Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            LoadingIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Loading page ${pageIndex + 1}…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Page ${pageIndex + 1} of $totalPages",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (bitmap != null) TextButton(onEnlarge, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.ZoomIn, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Zoom")
                    }
                }
            }
        }
    }
}

/** Full-screen pinch-zoom viewer for one previous page, with prev/next across the notebook. */
@Composable
private fun PreviousPageViewer(
    noteId: String,
    pages: List<com.folio.notes.NotePage>,
    startIndex: Int,
    folio: FolioViewModel,
    onClose: () -> Unit,
) {
    var index by remember(pages, startIndex) { mutableIntStateOf(startIndex.coerceIn(pages.indices)) }
    val page = pages.getOrNull(index) ?: return
    var zoom by remember(noteId, page.id) { mutableFloatStateOf(1f) }
    var offset by remember(noteId, page.id) { mutableStateOf(Offset.Zero) }
    fun resetView() { zoom = 1f; offset = Offset.Zero }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Close enlarged page") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Page ${index + 1} of ${pages.size}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${(zoom * 100).toInt()}% · pinch or use the buttons · read-only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton({ resetView() }, shapes = ButtonDefaults.shapes()) { Text("Reset") }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val density = LocalDensity.current
                    // Zoom headroom: render wider than the viewport so 2-3x zoom stays crisp.
                    val targetWidthPx = remember(maxWidth, density, index) {
                        (maxWidth.value * density.density * 2f).toInt().coerceIn(900, 2560)
                    }
                    var bitmap by remember(noteId, page.id, page.revision, targetWidthPx) { mutableStateOf<Bitmap?>(null) }
                    var failed by remember(noteId, page.id, page.revision) { mutableStateOf(false) }
                    LaunchedEffect(noteId, page.id, page.revision, targetWidthPx) {
                        resetView()
                        try {
                            bitmap = folio.thumbnails.fullPage(noteId, page, targetWidthPx)
                            failed = bitmap == null
                        } catch (_: Exception) { failed = true }
                    }
                    LaunchedEffect(index) { resetView() }
                    val image = bitmap
                    when {
                        image != null -> Box(
                            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                                .pointerInput(noteId, page.id) {
                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                        val next = (zoom * gestureZoom).coerceIn(1f, 5f)
                                        // When fully zoomed out the page is centred; only pan a zoomed page.
                                        if (next > 1f) {
                                            zoom = next
                                            val maxX = size.width * (zoom - 1f) / 2f
                                            val maxY = size.height * (zoom - 1f) / 2f
                                            offset = Offset(
                                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                                            )
                                        } else resetView()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                image.asImageBitmap(),
                                "Previous working, page ${index + 1}, enlarged",
                                Modifier.fillMaxSize().graphicsLayer {
                                    scaleX = zoom; scaleY = zoom
                                    translationX = offset.x; translationY = offset.y
                                },
                                contentScale = ContentScale.Fit,
                            )
                        }
                        failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.BrokenImage, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("This page could not be drawn.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LoadingIndicator(Modifier.size(20.dp))
                                Text("Loading page ${index + 1}…")
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        { if (index > 0) index-- },
                        enabled = index > 0,
                        shapes = IconButtonDefaults.shapes(),
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous page") }
                    TextButton(
                        { zoom = (zoom - .5f).coerceAtLeast(1f); if (zoom == 1f) offset = Offset.Zero },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("−") }
                    Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
                    TextButton(
                        { zoom = (zoom + .5f).coerceAtMost(5f) },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("+") }
                    IconButton(
                        { if (index < pages.lastIndex) index++ },
                        enabled = index < pages.lastIndex,
                        shapes = IconButtonDefaults.shapes(),
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next page") }
                }
            }
        }
    }
}
