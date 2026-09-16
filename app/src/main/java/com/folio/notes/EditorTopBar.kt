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
    Surface(modifier = Modifier.guardUiTouches(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                val compact = maxWidth < 600.dp
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks") }
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val statusColor = if (saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(when {
                    saveFailed -> Icons.Rounded.ErrorOutline
                    pendingSaves > 0 -> Icons.Rounded.Sync
                    else -> Icons.Rounded.CheckCircleOutline
                }, null, Modifier.size(13.dp), tint = statusColor)
                Text(when {
                    saveFailed -> "Changes not saved"
                    pendingSaves > 0 -> "Saving…"
                    else -> "Saved on device"
                }, style = MaterialTheme.typography.labelSmall, color = statusColor, maxLines = 1)
            }
        }
        var notebookMenu by remember { mutableStateOf(false) }
        Box {
            IconButton({ notebookMenu = true }) { Icon(Icons.Rounded.MoreVert, "Notebook options") }
            DropdownMenu(notebookMenu, { notebookMenu = false }, modifier = Modifier.guardUiTouches()) {
                DropdownMenuItem(text = { Text("Rename notebook") }, onClick = {
                    notebookMenu = false
                    onRename()
                }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            }
        }
        if (saveFailed) TextButton(onRetrySave) { Text("Retry") }
        IconToggleButton(checked = starred, onCheckedChange = { onStar() }) {
            Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (starred) "Remove from favorites" else "Add to favorites",
                tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onPrevious, enabled = pageIndex > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page") }
                TextButton(onPages, modifier = Modifier.semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount. Browse pages" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("${pageIndex + 1} / $pageCount") }
                IconButton(onNext, enabled = pageIndex < pageCount - 1) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page") }
            }
        }
        TextButton(onFit, modifier = Modifier.semantics { contentDescription = "Zoom $zoomPercent percent. Reset zoom" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("$zoomPercent%") }
        FilledTonalIconButton(onAdd) { Icon(Icons.Rounded.Add, "Add page") }
    }
}
