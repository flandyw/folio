@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun ExportPagesDialog(
    note: Notebook,
    initialIndex: Int,
    initialPdfMode: PdfExportMode = PdfExportMode.PRESERVE,
    onDismiss: () -> Unit,
    onExport: (PageExportRequest) -> Unit,
    onShare: (PageExportRequest) -> Unit,
    onPdfModeChange: ((PdfExportMode) -> Unit)? = null
) {
    var selected by remember(note.id, initialIndex) {
        mutableStateOf(if (note.pages.isEmpty()) emptySet() else setOf(initialIndex.coerceIn(0, note.pages.lastIndex)))
    }
    var format by remember(note.id) { mutableStateOf(PageExportFormat.PDF) }
    var pdfMode by remember(note.id) { mutableStateOf(initialPdfMode) }
    var rangeText by remember(note.id) { mutableStateOf("") }
    var rangeError by remember { mutableStateOf<String?>(null) }

    FolioPanel(title = "Export pages", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Choose which pages to save or share. A PDF keeps them in order; one PNG saves to your gallery, several PNGs make one .zip. Long-press a page to pick only that one.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = format == PageExportFormat.PDF,
                    onClick = { format = PageExportFormat.PDF },
                    label = { Text("PDF") },
                    leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = format == PageExportFormat.PNG,
                    onClick = { format = PageExportFormat.PNG },
                    label = { Text("PNG images") },
                    leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)) }
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
                shape = RoundedCornerShape(16.dp),
                supportingText = {
                    rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        ?: Text("${selected.size} selected · ${formatExportSelection(selected.sorted())}")
                },
                trailingIcon = {
                    TextButton(
                        onClick = {
                            val parsed = parsePageRange(rangeText, note.pages.size)
                            if (parsed.isEmpty()) rangeError = "No pages match “${rangeText.trim()}”"
                            else {
                                selected = parsed.toSet()
                                rangeError = null
                            }
                        },
                        shapes = ButtonDefaults.shapes()
                    ) { Text("Apply") }
                }
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { selected = note.pages.indices.toSet(); rangeError = null },
                    shapes = ButtonDefaults.shapes()
                ) { Text("Select all") }
                TextButton(
                    onClick = { selected = emptySet(); rangeError = null },
                    shapes = ButtonDefaults.shapes()
                ) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                Text(
                    "${selected.size}/${note.pages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(note.pages.indices.toList(), key = { note.pages[it].id }) { index ->
                    val page = note.pages[index]
                    val checked = index in selected
                    val hold = rememberLongPressGuard()
                    Row(
                        Modifier.fillMaxWidth().longPressAction(hold) { selected = setOf(index); rangeError = null },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                hold.click {
                                    selected = if (on) selected + index else selected - index
                                    rangeError = null
                                }()
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                page.displayTitle(index),
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (page.bookmarked || page.redoFlag) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (page.bookmarked) {
                                        Icon(
                                            Icons.Rounded.Bookmark,
                                            null,
                                            Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "Bookmarked",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (page.redoFlag) {
                                        if (page.bookmarked) {
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            "Redo",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (index == initialIndex) {
                            Text(
                                "Current",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val count = selected.size
                val indices = normalizeExportIndices(selected, note.pages.size)
                Button(
                    onClick = { if (indices.isNotEmpty()) onExport(PageExportRequest(note, indices, format, pdfMode)) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            count == 0 -> "Save"
                            format == PageExportFormat.PDF -> "Save PDF"
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
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            count == 0 -> "Share"
                            format == PageExportFormat.PDF -> "Share PDF"
                            count == 1 -> "Share PNG"
                            else -> "Share PNGs"
                        }
                    )
                }
            }
        }
    }
}
