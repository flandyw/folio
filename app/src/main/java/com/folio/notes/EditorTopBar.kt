@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val EditorFloatingGroupHeight = 56.dp

/** Shared glass-like M3 surface. Translucency and a highlight rim work on every supported API. */
@Composable internal fun EditorGlassSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier.height(EditorFloatingGroupHeight).guardUiTouches(),
        shape = shape,
        color = colors.surfaceContainerHigh.copy(alpha = .9f),
        contentColor = colors.onSurface,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(
            colors.surfaceBright.copy(alpha = .9f),
            colors.outlineVariant.copy(alpha = .35f)
        )))
    ) {
        Box(
            Modifier.fillMaxHeight().background(Brush.verticalGradient(listOf(
                colors.surface.copy(alpha = .28f), Color.Transparent
            ))),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

/** First row of the floating editor dock; secondary actions stay in anchored menus. */
@Composable internal fun EditorTopBar(
    title: String,
    mainTools: @Composable () -> Unit,
    saveFailed: Boolean,
    retryingSave: Boolean,
    saveFailureReason: String?,
    lastSaveProgressAt: Long?,
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
    notebookActions: @Composable (() -> Unit) -> Unit = {},
    actions: @Composable RowScope.() -> Unit
) {
    var notebookMenu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep three balanced regions so the drawing tools stay centred. Small windows
        // scroll this single row instead of overlapping controls or creating a third row.
        val rowWidth = maxWidth.coerceAtLeast(900.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()).width(rowWidth),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EditorGlassSurface(Modifier.width(EditorFloatingGroupHeight)) {
                IconButton(onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks") }
            }
            // This slot takes the spare width; the capsule itself hugs the notebook name.
            // The timer, page and action groups are consequently anchored to the right.
            Box(Modifier.weight(1f)) {
                EditorGlassSurface() {
                    Row(
                        Modifier.height(EditorFloatingGroupHeight)
                            .clickable(role = Role.Button, onClickLabel = "Notebook actions") { notebookMenu = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (saveFailed || pendingSaves > 0) {
                            Icon(if (saveFailed) Icons.Rounded.ErrorOutline else Icons.Rounded.Sync,
                                if (saveFailed) "Save failed. Open notebook actions for details" else "Saving on device",
                                Modifier.padding(start = 4.dp).size(16.dp),
                                tint = if (saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                    }
                }
                DropdownMenu(notebookMenu, { notebookMenu = false }, modifier = Modifier.guardUiTouches()) {
                    Text("Notebook", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    DropdownMenuItem({ Text("Rename notebook") }, { notebookMenu = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text(if (starred) "Remove from favourites" else "Add to favourites") }, { notebookMenu = false; onStar() }, leadingIcon = { Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) })
                    HorizontalDivider()
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        SaveStatus(saveFailed, retryingSave, saveFailureReason, lastSaveProgressAt, pendingSaves, onRetrySave, onClose)
                    }
                    notebookActions { notebookMenu = false }
                }
            }
            }
            Box(Modifier.weight(1.6f), contentAlignment = Alignment.Center) { mainTools() }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            EditorGlassSurface { Box(Modifier.padding(horizontal = 6.dp)) { timer() } }
            EditorGlassSurface {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton({ pageMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount. Page actions" }) {
                            Text("${pageIndex + 1}/$pageCount", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        DropdownMenu(pageMenu, { pageMenu = false }, modifier = Modifier.guardUiTouches()) {
                            Text("Pages", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            run {
                                DropdownMenuItem({ Text("Previous page") }, { pageMenu = false; onPrevious() }, enabled = pageIndex > 0, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null) })
                                DropdownMenuItem({ Text("Next page") }, { pageMenu = false; onNext() }, enabled = pageIndex < pageCount - 1, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) })
                            }
                            DropdownMenuItem({ Text("Browse pages") }, { pageMenu = false; onPages() }, leadingIcon = { Icon(Icons.Rounded.Dashboard, null) })
                            DropdownMenuItem({ Text("First page") }, { pageMenu = false; onFirstPage() }, enabled = pageIndex > 0)
                            DropdownMenuItem({ Text("Last page") }, { pageMenu = false; onLastPage() }, enabled = pageIndex < pageCount - 1)
                            HorizontalDivider()
                            DropdownMenuItem({ Text("Add page at end") }, { pageMenu = false; onAdd() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                            DropdownMenuItem({ Text("Insert after this page") }, { pageMenu = false; onInsertPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                            DropdownMenuItem({ Text("Duplicate this page") }, { pageMenu = false; onDuplicatePage() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                            HorizontalDivider()
                            DropdownMenuItem({ Text("Reset zoom · $zoomPercent%") }, { pageMenu = false; onFit() }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) })
                            if (onFitAll != null) DropdownMenuItem({ Text("Fit all content") }, { pageMenu = false; onFitAll() })
                        }
                    }
                }
            }
            EditorGlassSurface {
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            }
        }
    }
}

@Composable private fun SaveStatus(
    saveFailed: Boolean,
    retryingSave: Boolean,
    saveFailureReason: String?,
    lastSaveProgressAt: Long?,
    pendingSaves: Int,
    onRetrySave: () -> Unit,
    onClose: () -> Unit,
    compact: Boolean = false
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var detailsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pendingSaves) {
        while (pendingSaves > 0) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val slow = pendingSaves > 0 && lastSaveProgressAt != null && now - lastSaveProgressAt >= 5_000L
    val label = when {
        retryingSave -> "Retrying local save…"
        saveFailed -> "Save failed · Details"
        slow -> "Still saving · $pendingSaves queued"
        pendingSaves > 0 -> "Saving on device…"
        else -> "Saved on device"
    }
    val statusColor = if (saveFailed && !retryingSave) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    // A slim clickable row rather than a TextButton: TextButton forces a 40dp min
    // height, which pushed the status line away from the title in the wide layout.
    Row(Modifier.clip(RoundedCornerShape(6.dp))
        .clickable(role = Role.Button) { detailsOpen = true }
        .padding(horizontal = if (compact) 2.dp else 1.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(when {
            saveFailed && !retryingSave -> Icons.Rounded.ErrorOutline
            pendingSaves > 0 -> Icons.Rounded.Sync
            else -> Icons.Rounded.Check
        }, null, Modifier.size(12.dp), tint = statusColor)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = statusColor)
    }
    if (detailsOpen) AlertDialog(
        onDismissRequest = { detailsOpen = false }, modifier = Modifier.guardUiTouches(),
        title = { Text(when {
            retryingSave -> "Retrying the save"
            saveFailed -> "Changes could not be saved"
            slow -> "Saving is taking longer"
            pendingSaves > 0 -> "Saving on this device"
            else -> "Changes saved"
        }) },
        text = { Text(when {
            retryingSave -> "Folio is writing the latest notebook and library data to this device. Keep the app open until it says Saved on device."
            saveFailed -> "${saveFailureReason ?: "The device could not finish the write."} Your latest changes are still open in Folio. Retry the save before closing the app."
            slow -> "Folio is still writing $pendingSaves queued change${if (pendingSaves == 1) "" else "s"} to this device. Large pages, images, or busy storage can slow writes. Keep the app open until it says Saved on device."
            pendingSaves > 0 -> "$pendingSaves change${if (pendingSaves == 1) " is" else "s are"} waiting to finish writing to this device."
            else -> "All queued changes have been written to this device."
        }) },
        confirmButton = {
            if (saveFailed && !retryingSave) TextButton({ detailsOpen = false; onRetrySave() }) { Text("Retry save") }
            else TextButton({ detailsOpen = false }) { Text("Stay here") }
        },
        dismissButton = {
            if (!saveFailed && pendingSaves > 0) TextButton({ detailsOpen = false; onClose() }) {
                Text("Library · save continues")
            } else TextButton({ detailsOpen = false }) { Text("Close") }
        }
    )
}

