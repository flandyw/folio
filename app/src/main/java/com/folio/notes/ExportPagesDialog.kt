@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun ExportPagesDialog(
    note: Notebook,
    thumbnails: PageThumbnailCache,
    initialIndex: Int,
    initialFormat: PageExportFormat = PageExportFormat.PDF,
    initialPdfMode: PdfExportMode = PdfExportMode.PRESERVE,
    onDismiss: () -> Unit,
    onExport: (PageExportRequest) -> Unit,
    onShare: (PageExportRequest) -> Unit,
    onFormatChange: ((PageExportFormat) -> Unit)? = null,
    onPdfModeChange: ((PdfExportMode) -> Unit)? = null
) {
    var selected by remember(note.id, initialIndex) {
        mutableStateOf(if (note.pages.isEmpty()) emptySet() else setOf(initialIndex.coerceIn(0, note.pages.lastIndex)))
    }
    var format by remember(note.id) { mutableStateOf(initialFormat) }
    var pdfMode by remember(note.id) { mutableStateOf(initialPdfMode) }
    var rangeText by remember(note.id) { mutableStateOf("") }
    var rangeError by remember { mutableStateOf<String?>(null) }

    FolioPanel(title = "Export pages", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
        ) {
            Text(
                "Choose which pages to save or share. A PDF keeps them in order; one PNG saves to your gallery, several PNGs make one .zip. Long-press a page to pick only that one.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            fun chooseFormat(next: PageExportFormat) {
                format = next
                onFormatChange?.invoke(next)
            }
            FolioButtonGroup(Modifier.fillMaxWidth()) {
                toggleableItem(
                    checked = format == PageExportFormat.PDF,
                    onCheckedChange = { chooseFormat(PageExportFormat.PDF) },
                    label = "PDF",
                    icon = { Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)) }
                )
                toggleableItem(
                    checked = format == PageExportFormat.PNG,
                    onCheckedChange = { chooseFormat(PageExportFormat.PNG) },
                    label = "PNG images",
                    icon = { Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)) }
                )
            }
            if (format == PageExportFormat.PNG && selected.size == 1) {
                Text(
                    "Saving one PNG goes straight to your photo gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (format == PageExportFormat.PNG && selected.size > 1) {
                Text(
                    "Saving several PNGs makes one .zip file; sharing sends each image separately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (format == PageExportFormat.PDF && shouldShowPdfQuality(note)) {
                PdfQualitySection(
                    selected = pdfMode,
                    onSelect = {
                        pdfMode = it
                        onPdfModeChange?.invoke(it)
                    }
                )
            }
            OutlinedTextField(
                value = rangeText,
                onValueChange = { rangeText = it; rangeError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pages, e.g. 1-3, 5") },
                placeholder = { Text("1–${note.pages.size}") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    if (rangeText.isNotBlank() && note.pages.isNotEmpty()) {
                        val parsed = parsePageRange(rangeText, note.pages.size)
                        if (parsed.isEmpty()) rangeError = "Enter page numbers from 1 to ${note.pages.size}, such as 1-3, 5."
                        else { selected = parsed.toSet(); rangeError = null }
                    }
                }),
                isError = rangeError != null,
                shape = FolioShapes.large,
                supportingText = {
                    rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        ?: Text(if (selected.isEmpty()) "Select at least one page to save or share."
                            else "${selected.size} selected · ${formatExportSelection(selected.sorted())}")
                },
                trailingIcon = {
                    TextButton(
                        onClick = {
                            val parsed = parsePageRange(rangeText, note.pages.size)
                            if (parsed.isEmpty()) rangeError = "Enter page numbers from 1 to ${note.pages.size}, such as 1-3, 5."
                            else {
                                selected = parsed.toSet()
                                rangeError = null
                            }
                        },
                        enabled = rangeText.isNotBlank() && note.pages.isNotEmpty(),
                        shapes = ButtonDefaults.shapes()
                    ) { Text("Apply") }
                }
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                FilterChip(selected == setOf(initialIndex) && initialIndex in note.pages.indices,
                    { selected = setOf(initialIndex).filter { it in note.pages.indices }.toSet(); rangeError = null },
                    { Text("Current page") }, enabled = initialIndex in note.pages.indices)
                FilterChip(selected.size == note.pages.size && selected.isNotEmpty(),
                    { selected = note.pages.indices.toSet(); rangeError = null }, { Text("All pages") }, enabled = note.pages.isNotEmpty())
                val bookmarks = remember(note.pages) { note.pages.indices.filter { note.pages[it].bookmarked }.toSet() }
                FilterChip(selected == bookmarks && bookmarks.isNotEmpty(),
                    { selected = bookmarks; rangeError = null }, { Text("Bookmarks (${bookmarks.size})") }, enabled = bookmarks.isNotEmpty())
                val odd = remember(note.pages.size) { note.pages.indices.filter { it % 2 == 0 }.toSet() }
                val even = remember(note.pages.size) { note.pages.indices.filter { it % 2 == 1 }.toSet() }
                FilterChip(selected == odd && odd.isNotEmpty(), { selected = odd; rangeError = null }, { Text("Odd pages") }, enabled = note.pages.size > 1)
                FilterChip(selected == even && even.isNotEmpty(), { selected = even; rangeError = null }, { Text("Even pages") }, enabled = even.isNotEmpty())
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)
            ) {
                TextButton(
                    onClick = { selected = note.pages.indices.toSet(); rangeError = null },
                    enabled = selected.size < note.pages.size,
                    shapes = ButtonDefaults.shapes()
                ) { Text("Select all") }
                TextButton(
                    onClick = { selected = emptySet(); rangeError = null },
                    enabled = selected.isNotEmpty(),
                    shapes = ButtonDefaults.shapes()
                ) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                Text(
                    "${selected.size}/${note.pages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)
            ) {
                items(count = note.pages.size, key = { note.pages[it].id }) { index ->
                    ExportPageTile(
                        note = note,
                        index = index,
                        current = index == initialIndex,
                        checked = index in selected,
                        thumbnails = thumbnails,
                        onToggle = { selected = if (index in selected) selected - index else selected + index; rangeError = null },
                        onOnly = { selected = setOf(index); rangeError = null }
                    )
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12),
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)
        ) {
            val count = selected.size
            val indices = remember(selected, note.pages.size) { normalizeExportIndices(selected, note.pages.size) }
            Button(
                onClick = { if (indices.isNotEmpty()) onExport(PageExportRequest(note, indices, format, pdfMode)) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shapes = ButtonDefaults.shapes()
            ) {
                Icon(Icons.Rounded.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(
                    when {
                        count == 0 -> "Save"
                        format == PageExportFormat.PDF -> "Save PDF · $count"
                        count == 1 -> "Save to gallery"
                        else -> "Save ZIP"
                    }
                )
            }
            FilledTonalButton(
                onClick = { if (indices.isNotEmpty()) onShare(PageExportRequest(note, indices, format, pdfMode)) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shapes = ButtonDefaults.shapes()
            ) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(
                    when {
                        count == 0 -> "Share"
                        format == PageExportFormat.PDF -> "Share PDF · $count"
                        count == 1 -> "Share PNG"
                        else -> "Share PNGs"
                    }
                )
            }
        }
    }
}

/**
 * One page in the export grid. Tapping toggles it and a long-press keeps only this page.
 * A checked page takes the container colour, lifts slightly and shows a filled check badge,
 * so the selection reads from across the grid without relying on small checkboxes.
 */
@Composable private fun ExportPageTile(
    note: Notebook,
    index: Int,
    current: Boolean,
    checked: Boolean,
    thumbnails: PageThumbnailCache,
    onToggle: () -> Unit,
    onOnly: () -> Unit,
) {
    val page = note.pages[index]
    val hold = rememberLongPressGuard()
    Surface(
        shape = FolioShapes.large,
        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            if (checked) 2.dp else 1.dp,
            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth().folioSelected(checked)
            .longPressAction(hold, onOnly)
            .toggleable(checked, role = Role.Checkbox, onValueChange = { hold.click(onToggle)() })
            .semantics {
                customActions = listOf(CustomAccessibilityAction("Select only this page") { onOnly(); true })
            }
    ) {
        Column(Modifier.padding(FolioSpacing.dp6), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
            Box(Modifier.fillMaxWidth()) {
                PageThumbnail(note.id, page, thumbnails, Modifier.fillMaxWidth().height(104.dp), previewWidth = 96.dp)
                if (current) Surface(
                    Modifier.align(Alignment.TopStart).padding(FolioSpacing.dp4),
                    shape = FolioShapes.small,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text("Current", Modifier.padding(horizontal = FolioSpacing.dp6, vertical = FolioSpacing.dp2), style = MaterialTheme.typography.labelSmall)
                }
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp4).size(22.dp),
                    shape = CircleShape,
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = if (checked) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Text(
                    page.displayTitle(index),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (page.bookmarked) Icon(Icons.Rounded.Bookmark, "Bookmarked", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (page.redoFlag) Icon(Icons.Rounded.Refresh, "Flagged for practice", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
