@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The editor's top chrome: identity, navigation and selection only.
 *
 * The ink toolbar floats over the page below this bar, so it is deliberately
 * not part of this container — keeping it here would stretch this bar's
 * background behind the floating pills.
 */
@Composable internal fun EditorTopBar(
    title: String,
    saveFailed: Boolean,
    pendingSaves: Int,
    starred: Boolean,
    onStar: () -> Unit,
    onRename: () -> Unit,
    onRetrySave: () -> Unit,
    onClose: () -> Unit,
    timer: @Composable () -> Unit,
    pageIndex: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPages: () -> Unit,
    zoomPercent: Int,
    onFit: () -> Unit,
    onAdd: () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(modifier = Modifier.guardUiTouches(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, shadowElevation = 1.dp) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp)) {
                val compact = maxWidth < 600.dp
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IdentityContent(
                                title = title,
                                saveFailed = saveFailed,
                                pendingSaves = pendingSaves,
                                starred = starred,
                                onStar = onStar,
                                onRename = onRename,
                                onRetrySave = onRetrySave,
                                onClose = onClose,
                                timer = timer,
                                modifier = Modifier.weight(1f)
                            )
                            actions()
                        }
                        AppNavigationRow(
                            pageIndex = pageIndex,
                            pageCount = pageCount,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onPages = onPages,
                            zoomPercent = zoomPercent,
                            onFit = onFit,
                            onAdd = onAdd,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IdentityContent(
                            title = title,
                            saveFailed = saveFailed,
                            pendingSaves = pendingSaves,
                            starred = starred,
                            onStar = onStar,
                            onRename = onRename,
                            onRetrySave = onRetrySave,
                            onClose = onClose,
                            timer = timer,
                            modifier = Modifier.weight(1f)
                        )
                        AppNavigationRow(
                            pageIndex = pageIndex,
                            pageCount = pageCount,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onPages = onPages,
                            zoomPercent = zoomPercent,
                            onFit = onFit,
                            onAdd = onAdd,
                            modifier = Modifier
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable private fun IdentityContent(
    title: String,
    saveFailed: Boolean,
    pendingSaves: Int,
    starred: Boolean,
    onStar: () -> Unit,
    onRename: () -> Unit,
    onRetrySave: () -> Unit,
    onClose: () -> Unit,
    timer: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks") }
        Column(Modifier.weight(1f).padding(vertical = 1.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val statusColor = if (saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                androidx.compose.animation.Crossfade(targetState = saveFailed to (pendingSaves > 0), label = "saveStatus") { (failed, saving) ->
                    Icon(when {
                        failed -> Icons.Rounded.ErrorOutline
                        saving -> Icons.Rounded.Sync
                        else -> Icons.Rounded.Check
                    }, null, Modifier.size(13.dp), tint = statusColor)
                }
                Text(when {
                    saveFailed -> "Couldn't save — tap Retry"
                    pendingSaves > 0 -> "Saving…"
                    else -> "Saved on device"
                }, style = MaterialTheme.typography.labelSmall, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        var notebookMenu by remember { mutableStateOf(false) }
        Box {
            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Notebook options") } }, state = rememberTooltipState()) {
                IconButton({ notebookMenu = true }, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Notebook options") }
            }
            DropdownMenu(notebookMenu, { notebookMenu = false }, modifier = Modifier.guardUiTouches()) {
                DropdownMenuItem(text = { Text("Rename notebook") }, onClick = {
                    notebookMenu = false
                    onRename()
                }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                DropdownMenuItem(text = { Text(if (starred) "Remove from favourites" else "Add to favourites") }, onClick = {
                    notebookMenu = false
                    onStar()
                }, leadingIcon = { Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) })
            }
        }
        if (saveFailed) TextButton(onRetrySave, contentPadding = PaddingValues(horizontal = 10.dp), shapes = ButtonDefaults.shapes()) { Text("Retry") }
        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(if (starred) "Favourited" else "Add to favourites") } }, state = rememberTooltipState()) {
            IconToggleButton(checked = starred, onCheckedChange = { onStar() }, modifier = Modifier.size(40.dp)) {
                Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (starred) "Remove from favorites" else "Add to favorites",
                    tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        timer()
    }
}

@Composable private fun AppNavigationRow(
    pageIndex: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPages: () -> Unit,
    zoomPercent: Int,
    onFit: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                IconButton(onPrevious, enabled = pageIndex > 0, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page") }
                TextButton(onPages, modifier = Modifier.semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount. Browse pages" }, contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), shapes = ButtonDefaults.shapes()) {
                    Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
                IconButton(onNext, enabled = pageIndex < pageCount - 1, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page") }
            }
        }
        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Zoom $zoomPercent% — tap to reset") } }, state = rememberTooltipState()) {
            Surface(onClick = onFit, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                modifier = Modifier.height(40.dp).semantics { contentDescription = "Zoom $zoomPercent percent. Reset zoom" }) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.FitScreen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$zoomPercent%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text("Add page") } }, state = rememberTooltipState()) {
            FilledTonalIconButton(onAdd, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Add page") }
        }
    }
}
