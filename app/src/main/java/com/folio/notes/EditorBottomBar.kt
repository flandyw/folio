package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Identity and navigation wrap into two rows on phones, preserving full touch targets. */
@Composable internal fun EditorBottomBar(
    state: FolioState, zoomPercent: Int, selectedCount: Int,
    onRename: () -> Unit, onStar: () -> Unit, onRetry: () -> Unit,
    onPages: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onAdd: () -> Unit, onFit: () -> Unit,
    onDeselect: () -> Unit, onCopySelection: () -> Unit, onCutSelection: () -> Unit,
    onDuplicateSelection: () -> Unit, onRotateSelection: (Float) -> Unit,
    onResizeSelection: (Float) -> Unit, onRestyleSelection: () -> Unit, onDeleteSelection: () -> Unit
) {
    val note = state.active ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (selectedCount > 0) {
                // Every selection action scrolls into reach on a narrow phone rather than wrapping.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onDeselect) { Icon(Icons.Rounded.Close, "Deselect ink") }
                    Text("$selectedCount selected", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleSmall)
                    IconButton(onCopySelection) { Icon(Icons.Rounded.ContentCopy, "Copy ink") }
                    IconButton(onCutSelection) { Icon(Icons.Rounded.ContentCut, "Cut ink") }
                    IconButton(onDuplicateSelection) { Icon(Icons.Rounded.ControlPointDuplicate, "Duplicate ink in place") }
                    IconButton({ onRotateSelection(-90f) }) { Icon(Icons.Rounded.RotateLeft, "Rotate selection left") }
                    IconButton({ onRotateSelection(90f) }) { Icon(Icons.Rounded.RotateRight, "Rotate selection right") }
                    IconButton({ onResizeSelection(0.9f) }) { Icon(Icons.Rounded.ZoomOut, "Shrink selection") }
                    IconButton({ onResizeSelection(1.1f) }) { Icon(Icons.Rounded.ZoomIn, "Grow selection") }
                    IconButton(onRestyleSelection) { Icon(Icons.Rounded.Palette, "Restyle selection") }
                    TextButton(onDeleteSelection) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Delete ink")
                    }
                }
            } else BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                val compact = maxWidth < 600.dp
                @Composable fun Identity(modifier: Modifier) {
                    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "Rename notebook", onClick = onRename).padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(note.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Rounded.Edit, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                val statusColor = if (state.saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                Icon(when {
                                    state.saveFailed -> Icons.Rounded.ErrorOutline
                                    state.pendingSaves > 0 -> Icons.Rounded.Sync
                                    else -> Icons.Rounded.CheckCircleOutline
                                }, null, Modifier.size(13.dp), tint = statusColor)
                                Text(when {
                                    state.saveFailed -> "Changes not saved"
                                    state.pendingSaves > 0 -> "Saving…"
                                    else -> "Saved on device"
                                }, style = MaterialTheme.typography.labelSmall, color = statusColor, maxLines = 1)
                            }
                        }
                        if (state.saveFailed) TextButton(onRetry) { Text("Retry") }
                        IconToggleButton(checked = note.starred, onCheckedChange = { onStar() }) {
                            Icon(if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                if (note.starred) "Remove from favorites" else "Add to favorites",
                                tint = if (note.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                @Composable fun Navigation(modifier: Modifier) {
                    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onPrevious, enabled = state.pageIndex > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page") }
                                TextButton(onPages, modifier = Modifier.semantics { contentDescription = "Page ${state.pageIndex + 1} of ${note.pages.size}. Browse pages" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("${state.pageIndex + 1} / ${note.pages.size}") }
                                IconButton(onNext, enabled = state.pageIndex < note.pages.lastIndex) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page") }
                            }
                        }
                        if (compact) Spacer(Modifier.weight(1f))
                        TextButton(onFit, modifier = Modifier.semantics { contentDescription = "Zoom $zoomPercent percent. Reset zoom" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("$zoomPercent%") }
                        FilledTonalIconButton(onAdd) { Icon(Icons.Rounded.Add, "Add page") }
                    }
                }
                if (compact) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Identity(Modifier.fillMaxWidth())
                    Navigation(Modifier.fillMaxWidth())
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Identity(Modifier.weight(1f))
                    Navigation(Modifier)
                }
            }
        }
    }
}
