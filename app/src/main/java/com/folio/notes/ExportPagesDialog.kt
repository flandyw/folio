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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
    onDismiss: () -> Unit,
    onExport: (PageExportRequest) -> Unit
) {
    var selected by remember(note.id, initialIndex) {
        mutableStateOf(if (note.pages.isEmpty()) emptySet() else setOf(initialIndex.coerceIn(0, note.pages.lastIndex)))
    }
    var format by remember(note.id) { mutableStateOf(PageExportFormat.PDF) }
    var rangeText by remember(note.id) { mutableStateOf("") }
    var rangeError by remember { mutableStateOf<String?>(null) }

    FolioPanel(title = "Export pages", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Choose which pages to export. A PDF keeps them in order; PNG saves one image per page.",
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
            if (format == PageExportFormat.PNG && selected.size > 1) {
                Text(
                    "Several PNGs arrive as one .zip file, so they still need a single save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + index else selected - index
                                rangeError = null
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
            Button(
                onClick = {
                    val indices = normalizeExportIndices(selected, note.pages.size)
                    if (indices.isNotEmpty()) onExport(PageExportRequest(note, indices, format))
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes()
            ) {
                val count = selected.size
                Text(
                    when {
                        count == 0 -> "Select pages to export"
                        format == PageExportFormat.PDF && count == 1 -> "Export page ${selected.sorted().first() + 1} as PDF"
                        format == PageExportFormat.PDF -> "Export $count pages as PDF"
                        count == 1 -> "Export page ${selected.sorted().first() + 1} as PNG"
                        else -> "Export $count pages as PNG (.zip)"
                    }
                )
            }
        }
    }
}
