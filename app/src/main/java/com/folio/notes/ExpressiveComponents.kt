@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Keep one toggle mounted so press and selection changes morph between Expressive shapes. */
@Composable internal fun FolioToolToggle(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    FilledTonalIconToggleButton(
        checked = selected,
        // Tapping the active pen still opens its options rather than deselecting the tool.
        onCheckedChange = { onClick() },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Icon(icon, label) }
}

@Preview(name = "Expressive light", showBackground = true)
@Preview(name = "Expressive large text", showBackground = true, fontScale = 1.5f)
@Composable private fun ExpressiveLightPreview() { ExpressivePreview(dark = false) }

@Preview(name = "Expressive dark", showBackground = true)
@Composable private fun ExpressiveDarkPreview() { ExpressivePreview(dark = true) }

@Composable private fun ExpressivePreview(dark: Boolean) {
    FolioTheme(dark = dark) {
        Surface {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Your notebooks", style = MaterialTheme.typography.headlineMedium)
                FilledTonalButton(onClick = {}, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("New notebook")
                }
                var penSelected by remember { mutableStateOf(true) }
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        FolioToolToggle(penSelected, { penSelected = true }, Icons.Rounded.Edit, "Pen")
                        FolioToolToggle(!penSelected, { penSelected = false }, Icons.Rounded.Gesture, "Lasso select")
                    }
                }
                LoadingIndicator()
            }
        }
    }
}
