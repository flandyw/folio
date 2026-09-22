@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
                        attemptLabel(attempt),
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
}

private fun attemptLabel(a: LocalMistakeReviewAttempt): String {
    val date = a.completedAt?.take(10) ?: "Unfinished"
    val rating = a.rating?.replaceFirstChar(Char::uppercase)?.let { " · $it" } ?: ""
    return "$date$rating · read-only"
}

@Composable
private fun PreviousAttemptPage(
    noteId: String,
    page: com.folio.notes.NotePage,
    pageIndex: Int,
    totalPages: Int,
    folio: FolioViewModel,
) {
    val widthPx = with(LocalDensity.current) { 420.dp.roundToPx() }
    var bitmap by remember(noteId, page.id, page.revision) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(noteId, page.id, page.revision) { mutableStateOf(false) }
    LaunchedEffect(noteId, page.id, page.revision) {
        try {
            bitmap = folio.thumbnails.thumbnail(noteId, page, widthPx)
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
                        "Previous working, page ${pageIndex + 1}",
                        Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                    failed -> Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "This page could not be drawn.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            Text(
                "Page ${pageIndex + 1} of $totalPages",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
