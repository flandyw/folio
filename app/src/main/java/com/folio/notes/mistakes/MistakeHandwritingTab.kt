@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioViewModel
import com.folio.notes.Notebook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val handwritingDateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

private data class PracticeUsage(val bytes: Long, val empty: Boolean)

/**
 * Practice-page management: how much device space handwriting uses, per-notebook
 * purge, empty cleanup, and a link back to the question each page belongs to.
 */
internal fun LazyGridScope.handwritingTab(
    notes: List<Notebook>,
    mistakes: Map<String, ExamTrackMistake>,
    userId: String?,
    folio: FolioViewModel,
    model: MistakesViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenNotebook: (String) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        HandwritingStorageCard(
            notes = notes,
            mistakes = mistakes,
            userId = userId,
            folio = folio,
            model = model,
            scope = scope,
            onShowMessage = onShowMessage,
        )
    }
    if (notes.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Your first handwritten review will appear here. Untouched pages are never saved, so this list only holds work you started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    items(notes.size, key = { notes[it].id }) { index ->
        HandwritingNotebookRow(
            note = notes[index],
            mistakes = mistakes,
            userId = userId,
            folio = folio,
            model = model,
            scope = scope,
            onOpenNotebook = onOpenNotebook,
            onShowMessage = onShowMessage,
        )
    }
}

@Composable
private fun HandwritingStorageCard(
    notes: List<Notebook>,
    mistakes: Map<String, ExamTrackMistake>,
    userId: String?,
    folio: FolioViewModel,
    model: MistakesViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onShowMessage: (String) -> Unit,
) {
    var usage by remember { mutableStateOf<Map<String, PracticeUsage>>(emptyMap()) }
    var measuring by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    // Auto-purge notebooks left empty by older versions the first time this tab loads.
    var autoPurged by remember { mutableStateOf(false) }
    LaunchedEffect(autoPurged) {
        if (autoPurged) return@LaunchedEffect
        autoPurged = true
        try { folio.purgeEmptyMistakeNotebooks() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    val noteKeys = remember(notes) { notes.map { it.id to (it.updated to it.pages.size) } }
    LaunchedEffect(noteKeys) {
        measuring = true
        try {
            val sizes = folio.practiceSizes(notes.map { it.id })
            val resolved = notes.associate { note ->
                val bytes = sizes[note.id] ?: 0L
                val empty = try {
                    // Repository reads unloaded pages from disk; an unreadable page keeps its work.
                    folio.repository.isNotebookEmpty(note)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
                note.id to PracticeUsage(bytes, empty)
            }
            usage = resolved
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
        finally { measuring = false }
    }
    val totalBytes = usage.values.sumOf { it.bytes }
    val emptyCount = usage.values.count { it.empty }
    // Before measurement finishes, fall back to the cheap in-memory check so the
    // empty count never claims work is missing.
    val displayEmpty = if (usage.size == notes.size && notes.isNotEmpty()) emptyCount
        else notes.count { isPracticeNotebookEmpty(it) }
    val totalPages = notes.sumOf { it.pages.size }

    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your working, kept.", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
            Text(
                "Every practice page saved on this device, including unfinished reviews and pages from previous accounts. Untouched pages are never saved; empty ones are removed automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (measuring) LoadingIndicator(Modifier.size(18.dp))
                else Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (measuring && notes.isNotEmpty() && usage.isEmpty()) "Measuring practice pages…"
                    else if (notes.isEmpty()) "No practice pages on this device."
                    else "Practice pages use ${formatPracticeBytes(totalBytes)} on this device · ${notes.size} notebooks · $totalPages pages",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (notes.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { confirmEmpty = true },
                        enabled = !working && !measuring && displayEmpty > 0,
                        modifier = Modifier.weight(1f),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (displayEmpty > 0) "Delete empty ($displayEmpty)" else "No empty pages")
                    }
                    OutlinedButton(
                        onClick = { confirmAll = true },
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.DeleteForever, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete all")
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { if (!working) confirmEmpty = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text("Delete empty practice pages?") },
            text = { Text("$displayEmpty notebook${if (displayEmpty == 1) "" else "s"} with no handwriting will be removed. Pages with ink are kept.") },
            dismissButton = { TextButton({ confirmEmpty = false }, enabled = !working, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
            confirmButton = {
                Button(
                    onClick = {
                        working = true
                        scope.launch {
                            try {
                                val removed = folio.purgeEmptyMistakeNotebooks()
                                if (userId != null && removed > 0) {
                                    // Cache references are tiny, but dropping them keeps resume
                                    // lists from pointing at notebooks that no longer exist.
                                    val ids = notes.map { it.id }.toSet()
                                    model.removeAttemptsForNotebooks(ids)
                                }
                                usage = emptyMap()
                                onShowMessage(if (removed > 0) "Removed $removed empty practice notebook${if (removed == 1) "" else "s"}." else "No empty practice pages found.")
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { onShowMessage("Could not delete empty pages. Please try again.") }
                            finally { working = false; confirmEmpty = false }
                        }
                    },
                    enabled = !working,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(if (working) "Deleting…" else "Delete empty") }
            },
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { if (!working) confirmAll = false },
            icon = { Icon(Icons.Rounded.DeleteForever, null) },
            title = { Text("Delete all practice pages?") },
            text = { Text("All ${notes.size} practice notebooks (${formatPracticeBytes(totalBytes)}) will be removed from this device. Your ExamTrack questions and ratings stay; handwriting cannot be recovered.") },
            dismissButton = { TextButton({ confirmAll = false }, enabled = !working, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
            confirmButton = {
                Button(
                    onClick = {
                        working = true
                        scope.launch {
                            try {
                                val ids = notes.map { it.id }.toSet()
                                folio.deleteNotebooks(ids)
                                if (userId != null) model.removeAttemptsForNotebooks(ids)
                                usage = emptyMap()
                                onShowMessage("Deleted ${ids.size} practice notebook${if (ids.size == 1) "" else "s"}.")
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { onShowMessage("Could not delete practice pages. Please try again.") }
                            finally { working = false; confirmAll = false }
                        }
                    },
                    enabled = !working,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(if (working) "Deleting…" else "Delete all") }
            },
        )
    }
}

@Composable
private fun HandwritingNotebookRow(
    note: Notebook,
    mistakes: Map<String, ExamTrackMistake>,
    userId: String?,
    folio: FolioViewModel,
    model: MistakesViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenNotebook: (String) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    var size by remember(note.id, note.updated) { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(note.id, note.updated, note.pages.size) {
        size = try { folio.repository.notebookSize(note.id) } catch (_: Exception) { null }
    }
    val attempt = note.mistakeReviews.firstOrNull()
    val question = attempt?.mistakeId?.let { mistakes[it]?.question }
    val status = when {
        attempt == null -> "Practice page"
        userId != null && attempt.userId != userId -> "Previous account · kept on this device"
        attempt.completedAt != null -> "Reviewed ${attempt.completedAt.take(10)} · ${(attempt.rating ?: "reviewed").replaceFirstChar(Char::uppercase)}"
        userId != null && attempt.userId == userId -> "Unfinished · resume from the question"
        else -> "Unfinished review"
    }
    val date = remember(note.updated) {
        runCatching { handwritingDateFormat.get()!!.format(Date(note.updated)) }.getOrDefault("")
    }
    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (question != null) Text(question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else if (attempt != null) Text("Question removed from ExamTrack · handwriting kept", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ confirmDelete = true }, enabled = !working, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.DeleteOutline, "Delete ${note.title}")
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildList {
                        add("${note.pages.size} page${if (note.pages.size == 1) "" else "s"}")
                        size?.let { add(formatPracticeBytes(it)) }
                        if (date.isNotBlank()) add(date)
                        if (note.mistakeReviews.size > 1) add("${note.mistakeReviews.size} reviews")
                    }.joinToString(" · "),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton({ onOpenNotebook(note.id) }, enabled = !working, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!working) confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete this practice page?") },
            text = { Text("“${note.title}”${size?.let { " (${formatPracticeBytes(it)})" } ?: ""} will be removed from this device. This cannot be undone.") },
            dismissButton = { TextButton({ confirmDelete = false }, enabled = !working, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
            confirmButton = {
                Button(
                    onClick = {
                        working = true
                        scope.launch {
                            try {
                                folio.deleteNotebooks(setOf(note.id))
                                if (userId != null) model.removeAttemptsForNotebooks(setOf(note.id))
                                onShowMessage("Deleted practice page.")
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { onShowMessage("Could not delete this page. Please try again.") }
                            finally { working = false; confirmDelete = false }
                        }
                    },
                    enabled = !working,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(if (working) "Deleting…" else "Delete") }
            },
        )
    }
}
