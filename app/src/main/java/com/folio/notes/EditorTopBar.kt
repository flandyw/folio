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
import androidx.compose.ui.text.font.FontFamily
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
    onFirstPage: () -> Unit,
    onLastPage: () -> Unit,
    zoomPercent: Int,
    onFit: () -> Unit,
    onFitAll: (() -> Unit)? = null,
    onAdd: () -> Unit,
    onInsertPage: () -> Unit,
    onDuplicatePage: () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(modifier = Modifier.guardUiTouches(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, shadowElevation = 1.dp) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp)) {
                val compact = maxWidth < 600.dp
                if (compact) {
                    // One scrollable row instead of two stacked rows: saves ~60dp of
                    // vertical page space on phones. Everything stays reachable via
                    // horizontal scroll; nothing is dropped or moved into menus.
                    var notebookMenu by remember { mutableStateOf(false) }
                    var pageMenu by remember { mutableStateOf(false) }
                    var zoomMenu by remember { mutableStateOf(false) }
                    var addMenu by remember { mutableStateOf(false) }
                    val topHold = rememberLongPressGuard()
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(onClose, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks") }
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp).longPressAction(topHold, onRename))
                        val statusColor = if (saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(when {
                            saveFailed -> Icons.Rounded.ErrorOutline
                            pendingSaves > 0 -> Icons.Rounded.Sync
                            else -> Icons.Rounded.Check
                        }, when {
                            saveFailed -> "Couldn't save — tap Retry"
                            pendingSaves > 0 -> "Saving…"
                            else -> "Saved on device"
                        }, Modifier.size(13.dp), tint = statusColor)
                        if (saveFailed) TextButton(onRetrySave, contentPadding = PaddingValues(horizontal = 6.dp), shapes = ButtonDefaults.shapes()) {
                            Text("Retry", style = MaterialTheme.typography.labelSmall)
                        }
                        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text(if (starred) "Favourited" else "Add to favourites") } }, state = rememberTooltipState()) {
                            IconToggleButton(checked = starred, onCheckedChange = { onStar() }, modifier = Modifier.size(36.dp)) {
                                Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    if (starred) "Remove from favorites" else "Add to favorites",
                                    Modifier.size(20.dp),
                                    tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        timer()
                        IconButton(onPrevious, enabled = pageIndex > 0, modifier = Modifier.size(36.dp), shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page", Modifier.size(20.dp))
                        }
                        Box {
                            TextButton(topHold.click(onPages), modifier = Modifier.semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount. Browse pages" }
                                .longPressAction(topHold) { pageMenu = true }, contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), shapes = ButtonDefaults.shapes()) {
                                Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                            }
                            DropdownMenu(pageMenu, { pageMenu = false }, modifier = Modifier.guardUiTouches()) {
                                DropdownMenuItem({ Text("First page") }, { pageMenu = false; onFirstPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null) })
                                DropdownMenuItem({ Text("Last page") }, { pageMenu = false; onLastPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) })
                                DropdownMenuItem({ Text("Browse pages") }, { pageMenu = false; onPages() }, leadingIcon = { Icon(Icons.Rounded.Dashboard, null) })
                            }
                        }
                        IconButton(onNext, enabled = pageIndex < pageCount - 1, modifier = Modifier.size(36.dp), shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page", Modifier.size(20.dp))
                        }
                        Box {
                            TextButton(topHold.click(onFit), contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.semantics { contentDescription = "Zoom $zoomPercent percent. Reset zoom" }
                                    .then(if (onFitAll != null) Modifier.longPressAction(topHold) { zoomMenu = true } else Modifier)) {
                                Text("$zoomPercent%", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                            }
                            DropdownMenu(zoomMenu, { zoomMenu = false }, modifier = Modifier.guardUiTouches()) {
                                DropdownMenuItem({ Text("Reset zoom to 100%") }, { zoomMenu = false; onFit() }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) })
                                if (onFitAll != null) DropdownMenuItem({ Text("Fit all content") }, { zoomMenu = false; onFitAll() }, leadingIcon = { Icon(Icons.Rounded.Fullscreen, null) })
                            }
                        }
                        Box {
                            FilledTonalIconButton(topHold.click(onAdd), modifier = Modifier.size(36.dp).longPressAction(topHold) { addMenu = true }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Add, "Add page", Modifier.size(20.dp))
                            }
                            DropdownMenu(addMenu, { addMenu = false }, modifier = Modifier.guardUiTouches()) {
                                DropdownMenuItem({ Text("Add page at end") }, { addMenu = false; onAdd() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                                DropdownMenuItem({ Text("Insert page after this one") }, { addMenu = false; onInsertPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                                DropdownMenuItem({ Text("Duplicate this page") }, { addMenu = false; onDuplicatePage() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                            }
                        }
                        Box {
                            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text("Notebook options") } }, state = rememberTooltipState()) {
                                IconButton({ notebookMenu = true }, modifier = Modifier.size(36.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Notebook options", Modifier.size(20.dp)) }
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
                        actions()
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
                            onFirstPage = onFirstPage,
                            onLastPage = onLastPage,
                            zoomPercent = zoomPercent,
                            onFit = onFit,
                            onFitAll = onFitAll,
                            onAdd = onAdd,
                            onInsertPage = onInsertPage,
                            onDuplicatePage = onDuplicatePage,
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
    val identityHold = rememberLongPressGuard()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks") }
        Column(Modifier.weight(1f).padding(vertical = 1.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.longPressAction(identityHold, onRename))
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
            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text("Notebook options") } }, state = rememberTooltipState()) {
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
        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text(if (starred) "Favourited" else "Add to favourites") } }, state = rememberTooltipState()) {
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
    onFirstPage: () -> Unit,
    onLastPage: () -> Unit,
    zoomPercent: Int,
    onFit: () -> Unit,
    onFitAll: (() -> Unit)?,
    onAdd: () -> Unit,
    onInsertPage: () -> Unit,
    onDuplicatePage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pageMenu by remember { mutableStateOf(false) }
    var zoomMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val navHold = rememberLongPressGuard()
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                IconButton(onPrevious, enabled = pageIndex > 0, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page") }
                Box {
                    TextButton(navHold.click(onPages), modifier = Modifier.semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount. Browse pages" }
                        .longPressAction(navHold) { pageMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), shapes = ButtonDefaults.shapes()) {
                        Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                    }
                    DropdownMenu(pageMenu, { pageMenu = false }, modifier = Modifier.guardUiTouches()) {
                        DropdownMenuItem({ Text("First page") }, { pageMenu = false; onFirstPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null) })
                        DropdownMenuItem({ Text("Last page") }, { pageMenu = false; onLastPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) })
                        DropdownMenuItem({ Text("Browse pages") }, { pageMenu = false; onPages() }, leadingIcon = { Icon(Icons.Rounded.Dashboard, null) })
                    }
                }
                IconButton(onNext, enabled = pageIndex < pageCount - 1, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page") }
            }
        }
        Box {
            Surface(onClick = navHold.click(onFit), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                modifier = Modifier.height(40.dp).semantics { contentDescription = "Zoom $zoomPercent percent. Reset zoom" }
                    .then(if (onFitAll != null) Modifier.longPressAction(navHold) { zoomMenu = true } else Modifier)) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.FitScreen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$zoomPercent%", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            DropdownMenu(zoomMenu, { zoomMenu = false }, modifier = Modifier.guardUiTouches()) {
                DropdownMenuItem({ Text("Reset zoom to 100%") }, { zoomMenu = false; onFit() }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) })
                if (onFitAll != null) DropdownMenuItem({ Text("Fit all content") }, { zoomMenu = false; onFitAll() }, leadingIcon = { Icon(Icons.Rounded.Fullscreen, null) })
            }
        }
        Box {
            FilledTonalIconButton(navHold.click(onAdd), modifier = Modifier.size(40.dp).longPressAction(navHold) { addMenu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Add page") }
            DropdownMenu(addMenu, { addMenu = false }, modifier = Modifier.guardUiTouches()) {
                DropdownMenuItem({ Text("Add page at end") }, { addMenu = false; onAdd() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                DropdownMenuItem({ Text("Insert page after this one") }, { addMenu = false; onInsertPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                DropdownMenuItem({ Text("Duplicate this page") }, { addMenu = false; onDuplicatePage() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
            }
        }
    }
}
