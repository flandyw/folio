package com.folio.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PDF-quality choice shown whenever the notebook contains imported PDF pages.
 * Preserve is the default; rasterise is an intentional compatibility mode for
 * downstream viewers, parsers and document-analysis tools that ignore overlays.
 */
@Composable fun PdfQualitySection(
    selected: PdfExportMode,
    onSelect: (PdfExportMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("PDF quality", style = MaterialTheme.typography.titleSmall)
        PdfModeRow(
            selected = selected == PdfExportMode.PRESERVE,
            onClick = { onSelect(PdfExportMode.PRESERVE) },
            title = "Preserve original PDF",
            subtitle = "Keeps searchable/selectable text, vectors and links where possible. Folio annotations are added as an overlay."
        )
        PdfModeRow(
            selected = selected == PdfExportMode.RASTERISE,
            onClick = { onSelect(PdfExportMode.RASTERISE) },
            title = "Rasterise pages",
            subtitle = "Flattens each page and its annotations into an image. Larger files and non-selectable text, but maximum visual compatibility."
        )
    }
}

@Composable private fun PdfModeRow(selected: Boolean, onClick: () -> Unit, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
