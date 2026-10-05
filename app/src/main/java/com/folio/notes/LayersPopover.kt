@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The page's layers, topmost first. Tapping a row makes it the layer new ink, text and pictures go
 * to; the eye hides it (hidden layers also stay out of exports and previews) and the lock keeps it
 * from being drawn on, erased or selected. Every change here is one undoable step on the page.
 */
@Composable internal fun LayersPopover(
    page: NotePage,
    active: Int,
    selectionCount: Int,
    model: FolioViewModel,
    onMoveSelection: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val layers = PageLayers.effective(page.layers)
    val content = page.content()
    var renaming by remember { mutableStateOf<PageLayer?>(null) }
    FolioPopover(onDismiss = onDismiss, width = 360.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Layers, null, Modifier.size(20.dp))
            Text("Layers", Modifier.weight(1f).padding(start = FolioSpacing.dp8), style = MaterialTheme.typography.titleMedium)
            IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Close layers") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
            Text(if (layers.size == PageLayers.MAX_LAYERS) "Layer limit reached"
                    else "${layers.size} of ${PageLayers.MAX_LAYERS} layers", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            FilledTonalButton({ model.addLayer() }, enabled = layers.size < PageLayers.MAX_LAYERS, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp6)); Text("New layer")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            for (layer in layers.asReversed()) key(layer.id) {
                val index = layers.indexOfFirst { it.id == layer.id }
                val isActive = layer.id == active
                Surface(
                    shape = FolioShapes.medium,
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clip(FolioShapes.medium).selectable(isActive, role = Role.RadioButton) { model.setActiveLayer(page.id, layer.id) }
                ) {
                    Column(Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ model.setLayerVisible(layer.id, !layer.visible) }, Modifier.size(48.dp)) {
                                Icon(if (layer.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    if (layer.visible) "Hide ${layer.name}" else "Show ${layer.name}", Modifier.size(20.dp))
                            }
                            IconButton({ model.setLayerLocked(layer.id, !layer.locked) }, Modifier.size(48.dp)) {
                                Icon(if (layer.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                    if (layer.locked) "Unlock ${layer.name}" else "Lock ${layer.name}", Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f).padding(horizontal = FolioSpacing.dp6)) {
                                Text(layer.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val count = PageLayers.count(content, layer.id)
                                Text(buildString {
                                    append(if (count == 1) "1 item" else "$count items")
                                    if (!layer.visible) append(" · hidden")
                                    if (layer.locked) append(" · locked")
                                    if (isActive) append(if (layer.visible && !layer.locked) " · drawing here" else " · active")
                                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End) {
                            IconButton({ model.moveLayer(layer.id, up = true) }, Modifier.size(48.dp), enabled = index < layers.lastIndex) {
                                Icon(Icons.Rounded.KeyboardArrowUp, "Move ${layer.name} up", Modifier.size(20.dp))
                            }
                            IconButton({ model.moveLayer(layer.id, up = false) }, Modifier.size(48.dp), enabled = index > 0) {
                                Icon(Icons.Rounded.KeyboardArrowDown, "Move ${layer.name} down", Modifier.size(20.dp))
                            }
                            IconButton({ renaming = layer }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Edit, "Rename ${layer.name}", Modifier.size(18.dp)) }
                            IconButton({ model.deleteLayer(layer.id) }, Modifier.size(48.dp), enabled = layers.size > 1) {
                                Icon(Icons.Rounded.Delete, "Delete ${layer.name}", Modifier.size(18.dp))
                            }
                        }
                        if (selectionCount > 0) {
                            TextButton({ onMoveSelection(layer.id) }, enabled = layer.id != active, shapes = ButtonDefaults.shapes()) {
                                Text("Move $selectionCount selected here")
                            }
                        }
                    }
                }
            }
        }
        Text("Deleting a layer moves its items to the layer beneath it. Hidden layers are left out of exports. Show and unlock a layer before drawing on it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = FolioSpacing.dp8))
    }
    renaming?.let { layer ->
        var name by remember(layer.id) { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, modifier = Modifier.guardUiTouches(),
            title = { Text("Rename layer") },
            text = { OutlinedTextField(name, { name = it.take(PageLayers.MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Layer name") }, supportingText = { Text("${name.length}/${PageLayers.MAX_NAME}") }) },
            confirmButton = { TextButton({ model.renameLayer(layer.id, name); renaming = null }, enabled = name.isNotBlank() && name.trim() != layer.name) { Text("Rename") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } }
        )
    }
}
