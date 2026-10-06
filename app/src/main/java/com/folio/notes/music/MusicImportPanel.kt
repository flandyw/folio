@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folio.notes.EditorGlassSurface
import com.folio.notes.FolioExpand
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.guardUiTouches

/**
 * Reviewing an imported PDF before it joins Music: the page preview sits on the editor's desk,
 * and each suggested part is a card that expands to its title and pages when ticked.
 */
@Composable internal fun MusicImportPanel(review: MusicImportReview, model: MusicViewModel, busy: Boolean, message: String?) {
    val source = review.source
    // The title a part gets until the user writes their own: the PDF's title plus the instrument.
    fun autoTitle(instrument: String) = if (instrument.isBlank()) source.title else "${source.title} — ${instrument.trim()}"
    var instruments by rememberSaveable(source.id) { mutableStateOf(review.suggestions.map { it.instrument }.ifEmpty { listOf("") }) }
    var titles by rememberSaveable(source.id) { mutableStateOf(instruments.map(::autoTitle)) }
    var ranges by rememberSaveable(source.id) { mutableStateOf(review.suggestions.map { MusicParts.pageLabel(it.pages) }.ifEmpty { listOf("") }) }
    var checked by rememberSaveable(source.id) { mutableStateOf(instruments.map { review.suggestions.isEmpty() }) }
    var previewPage by rememberSaveable(source.id) { mutableIntStateOf(0) }
    val parsed = ranges.map { runCatching { MusicParts.parsePages(it, source.pages) } }
    val selected = checked.indices.filter { checked[it] }
    val valid = selected.isNotEmpty() && selected.all { titles[it].isNotBlank() && parsed[it].isSuccess }
    // Which part the preview page belongs to, so the user can see the boundary they are checking.
    val previewParts = parsed.indices.filter { parsed[it].getOrNull()?.contains(previewPage) == true }
    val listState = rememberLazyListState()
    val initialParts = remember(source.id) { instruments.size }
    // A part added by hand lands below the fold on a phone, so bring it into view.
    LaunchedEffect(instruments.size) { if (instruments.size > initialParts) listState.animateScrollToItem(instruments.size) }
    fun preview(page: Int) { previewPage = page.coerceIn(0, source.pages - 1) }
    Dialog(onDismissRequest = { if (!busy) model.dismissReview(source.id) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().guardUiTouches(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp24, end = FolioSpacing.dp8, top = FolioSpacing.dp8, bottom = FolioSpacing.dp8),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose your parts", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        Text(source.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ model.dismissReview(source.id) }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, if (review.existing) "Cancel" else "Skip this PDF")
                    }
                }
                FolioExpand(busy) { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24)) }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val previewContent: @Composable (Modifier) -> Unit = { modifier ->
                        Surface(modifier.padding(FolioSpacing.dp12), shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(FolioSpacing.dp12), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                MusicPage(model.store.pdf(source.id), previewPage, source.pages, model.pageCache,
                                    strokes = source.ink.filter { it.page == previewPage },
                                    texts = source.texts.filter { it.page == previewPage },
                                    onTurn = { forward -> preview(previewPage + if (forward) 1 else -1) },
                                    modifier = Modifier.weight(1f).fillMaxWidth())
                                EditorGlassSurface {
                                    Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                                        IconButton({ preview(previewPage - 1) }, enabled = previewPage > 0) {
                                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous PDF page")
                                        }
                                        Column(Modifier.widthIn(min = 120.dp).padding(horizontal = FolioSpacing.dp4), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("PDF page ${previewPage + 1} of ${source.pages}", style = MaterialTheme.typography.labelLarge)
                                            Text(previewParts.joinToString(", ") { instruments[it].ifBlank { "Custom part" } }.ifEmpty { "Not in any part" },
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                                        }
                                        IconButton({ preview(previewPage + 1) }, enabled = previewPage < source.pages - 1) {
                                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next PDF page")
                                        }
                                    }
                                }
                                if (source.pages > 2) Slider(previewPage.toFloat(), { preview(it.roundToIntSafe()) }, valueRange = 0f..(source.pages - 1).toFloat(),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp8))
                            }
                        }
                    }
                    val choices: @Composable (Modifier) -> Unit = { modifier ->
                        LazyColumn(modifier, state = listState, contentPadding = PaddingValues(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Only keep the parts you play", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                                        if (instruments.size > 1) {
                                            val all = checked.all { it }
                                            TextButton({ checked = checked.map { !all } }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(if (all) "Clear" else "Select all") }
                                        }
                                    }
                                    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Row(Modifier.padding(FolioSpacing.dp12), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                                Text(review.notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                                Text("Page numbers count every PDF page, covers included. Parts are whole pages, not single staves.",
                                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                            }
                                        }
                                    }
                                    if (message != null && message.startsWith("Music:")) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            items(instruments.indices.toList(), key = { it }) { index ->
                                val on = checked[index]
                                Surface(shape = FolioShapes.extraLarge,
                                    color = if (on) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = if (on) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null) {
                                    Column(Modifier.padding(start = FolioSpacing.dp4, end = FolioSpacing.dp12, top = FolioSpacing.dp4, bottom = FolioSpacing.dp12),
                                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                        // The whole header toggles the part, not just the checkbox.
                                        Row(Modifier.clickable(enabled = !busy) { checked = checked.toMutableList().also { it[index] = !on } },
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(on, { value -> checked = checked.toMutableList().also { it[index] = value } }, enabled = !busy)
                                            Column(Modifier.weight(1f)) {
                                                Text(instruments[index].ifBlank { "Custom part" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(parsed[index].getOrNull()?.let { "Pages ${ranges[index]} · ${it.size} ${if (it.size == 1) "page" else "pages"}" } ?: "Choose pages",
                                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            TextButton({ parsed[index].getOrNull()?.firstOrNull()?.let(::preview) }, enabled = parsed[index].isSuccess, shapes = ButtonDefaults.shapes()) {
                                                Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("Show")
                                            }
                                        }
                                        review.suggestions.getOrNull(index)?.takeIf { it.inferredPages > 0 }?.let {
                                            Text("${it.inferredPages} unlabelled ${if (it.inferredPages == 1) "page was" else "pages were"} added after the heading — check them in the preview.",
                                                Modifier.padding(start = FolioSpacing.dp12), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                        }
                                        FolioExpand(on) {
                                            Column(Modifier.padding(start = FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                                    OutlinedTextField(instruments[index], { value ->
                                                        // A title still on its automatic wording follows the instrument as it is typed.
                                                        if (titles[index] == autoTitle(instruments[index])) titles = titles.toMutableList().also { it[index] = autoTitle(value) }
                                                        instruments = instruments.toMutableList().also { it[index] = value }
                                                    },
                                                        Modifier.weight(1f), label = { Text("Instrument / part") }, singleLine = true, enabled = !busy)
                                                    OutlinedTextField(ranges[index], { value -> ranges = ranges.toMutableList().also { it[index] = value } },
                                                        Modifier.weight(1f), label = { Text("Pages, e.g. 3-6, 9") }, singleLine = true, enabled = !busy,
                                                        isError = ranges[index].isNotBlank() && parsed[index].isFailure)
                                                }
                                                parsed[index].exceptionOrNull()?.takeIf { ranges[index].isNotBlank() }?.let {
                                                    Text(it.message ?: "Check the pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                                }
                                                OutlinedTextField(titles[index], { value -> titles = titles.toMutableList().also { it[index] = value } },
                                                    Modifier.fillMaxWidth(), label = { Text("Title in Music") }, singleLine = true, enabled = !busy)
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    TextButton({
                                        instruments = instruments + ""; titles = titles + source.title; ranges = ranges + ""; checked = checked + true
                                    }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add a custom part")
                                    }
                                    if (!review.existing) TextButton({ model.keepWhole(source.id) }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                                        Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Keep the complete PDF instead")
                                    }
                                }
                            }
                        }
                    }
                    if (maxWidth >= 720.dp) Row(Modifier.fillMaxSize()) {
                        previewContent(Modifier.weight(1f).fillMaxHeight()); choices(Modifier.weight(1f).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize()) {
                        previewContent(Modifier.weight(0.45f).fillMaxWidth()); choices(Modifier.weight(0.55f).fillMaxWidth())
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Text(if (selected.isEmpty()) "Tick the parts you play" else "${selected.size} ${if (selected.size == 1) "part" else "parts"} selected",
                            Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ model.dismissReview(source.id) }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(if (review.existing) "Cancel" else "Skip PDF") }
                        Button({ model.extractParts(source.id, selected.map { MusicPartRequest(titles[it], instruments[it], parsed[it].getOrThrow()) }) },
                            enabled = valid && !busy, shapes = ButtonDefaults.shapes()) {
                            Text(if (busy) "Preparing parts…" else if (selected.size > 1) "Add ${selected.size} parts" else "Add part")
                        }
                    }
                }
            }
        }
    }
}

private fun Float.roundToIntSafe() = kotlin.math.round(this).toInt()
