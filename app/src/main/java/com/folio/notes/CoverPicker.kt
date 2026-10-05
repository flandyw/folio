@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Picks a notebook cover: a live preview, the design row and the colour row. [cover] is the packed
 * colour + design int a notebook stores, so one callback carries both choices. Shared by the
 * new-notebook panel, the shelf's "Change cover" dialog and the default-cover setting.
 */
@Composable fun CoverPicker(cover: Int, onChange: (Int) -> Unit, title: String, document: Boolean = false, modifier: Modifier = Modifier) {
    val colors = coverColors(rememberCustomCoverColors())
    val style = CoverStyle.styleOf(cover)
    val colorIndex = CoverStyle.colorIndex(cover)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        CoverFace(title.ifBlank { "Your next idea" }, document, cover, Modifier.align(Alignment.CenterHorizontally).size(112.dp, 150.dp), compact = true)
        Text("Design", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            CoverStyle.entries.forEach { option ->
                val on = option == style
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Surface(
                        onClick = { onChange(CoverStyle.withStyle(cover, option)) },
                        shape = FolioShapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.semanticsLabel(if (on) "${option.label} design, selected" else "${option.label} design"),
                    ) {
                        CoverFace("", false, CoverStyle.withStyle(cover, option), Modifier.padding(3.dp).size(54.dp, 72.dp), compact = true, showText = false)
                    }
                    Text(option.label, style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            colors.forEachIndexed { index, color ->
                val on = index == colorIndex.coerceAtMost(colors.lastIndex)
                Surface(
                    onClick = { onChange(CoverStyle.withColor(cover, index)) }, shape = FolioShapes.medium, color = color,
                    border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(40.dp).semanticsLabel(if (on) "Cover colour ${index + 1}, selected" else "Cover colour ${index + 1}"),
                ) {
                    if (on) Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = if (color.luminance() > .3f) Color(0xFF2E302B) else Color(0xFFF6F1E7))
                    }
                }
            }
        }
    }
}
