@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
    val shape = FolioShapes.panel
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
    saving: Boolean,
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
    onExport: () -> Unit,
    onPageOptions: () -> Unit,
    additionalMenus: @Composable () -> Unit
) {
    var overflow by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Size against this editor pane, including the narrower mistake-review split.
        // Only the tool tray scrolls; Back and overflow always remain on screen.
        // Hide the title first, then move timing controls into the notebook menu.
        val collapsedTitle = maxWidth < 840.dp
        val compact = maxWidth < 680.dp
        val sideWidth = ((maxWidth - 480.dp) / 2).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
            Row(if (collapsedTitle) Modifier else Modifier.width(sideWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                EditorGlassSurface(Modifier.width(48.dp)) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks") }
                }
                if (!collapsedTitle) EditorGlassSurface(Modifier.weight(1f, fill = false)) {
                    Row(Modifier.clickable(role = Role.Button, onClickLabel = "Notebook actions") { overflow = true }
                        .padding(horizontal = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp))
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { mainTools() }
            Row(if (collapsedTitle) Modifier else Modifier.width(sideWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2, Alignment.End)) {
                if (!compact) Box(
                    if (collapsedTitle) Modifier.widthIn(max = sideWidth) else Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    EditorGlassSurface {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp6),
                            verticalAlignment = Alignment.CenterVertically) { timer() }
                    }
                }
                EditorGlassSurface(Modifier.width(48.dp)) {
                    IconButton(onExport) { Icon(Icons.Rounded.IosShare, "Share or export") }
                }
                Box {
                    EditorGlassSurface(Modifier.width(48.dp)) {
                        IconButton({ overflow = true }) {
                            Icon(if (saveFailed) Icons.Rounded.ErrorOutline else Icons.Rounded.MoreVert,
                                if (saveFailed) "Save failed. Notebook actions" else "Notebook actions",
                                tint = if (saveFailed) MaterialTheme.colorScheme.error else LocalContentColor.current)
                        }
                    }
                    DropdownMenu(overflow, { overflow = false }, modifier = Modifier.guardUiTouches()) {
                        Text(title, Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                            style = MaterialTheme.typography.titleSmall)
                        DropdownMenuItem({ Text("Page options") }, { overflow = false; onPageOptions() },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null) })
                        HorizontalDivider()
                    Text("Notebook", Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    DropdownMenuItem({ Text("Rename notebook") }, { overflow = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text(if (starred) "Remove from favourites" else "Add to favourites") }, { overflow = false; onStar() }, leadingIcon = { Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) })
                    HorizontalDivider()
                    Box(Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10)) {
                        SaveStatus(saveFailed, retryingSave, saveFailureReason, lastSaveProgressAt, saving, onRetrySave, onClose)
                    }
                    notebookActions { overflow = false }
                        if (compact) {
                            HorizontalDivider()
                            Box(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp4)) { timer() }
                        }
                        HorizontalDivider()
                            Text("Pages", Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            run {
                                DropdownMenuItem({ Text("Previous page") }, { overflow = false; onPrevious() }, enabled = pageIndex > 0, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null) })
                                DropdownMenuItem({ Text("Next page") }, { overflow = false; onNext() }, enabled = pageIndex < pageCount - 1, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) })
                            }
                            DropdownMenuItem({ Text("Browse pages") }, { overflow = false; onPages() }, leadingIcon = { Icon(Icons.Rounded.Dashboard, null) })
                            DropdownMenuItem({ Text("First page") }, { overflow = false; onFirstPage() }, enabled = pageIndex > 0)
                            DropdownMenuItem({ Text("Last page") }, { overflow = false; onLastPage() }, enabled = pageIndex < pageCount - 1)
                            HorizontalDivider()
                            DropdownMenuItem({ Text("Add page at end") }, { overflow = false; onAdd() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                            DropdownMenuItem({ Text("Insert after this page") }, { overflow = false; onInsertPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                            DropdownMenuItem({ Text("Duplicate this page") }, { overflow = false; onDuplicatePage() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                            HorizontalDivider()
                            DropdownMenuItem({ Text("Reset zoom · $zoomPercent%") }, { overflow = false; onFit() }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) })
                            if (onFitAll != null) DropdownMenuItem({ Text("Fit all content") }, { overflow = false; onFitAll() })
                    }
                    additionalMenus()
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
    saving: Boolean,
    onRetrySave: () -> Unit,
    onClose: () -> Unit,
    compact: Boolean = false
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var detailsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(saving) {
        while (saving) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val slow = saving && lastSaveProgressAt != null && now - lastSaveProgressAt >= 5_000L
    val label = when {
        retryingSave -> "Retrying local save…"
        saveFailed -> "Save failed · Details"
        slow -> "Still saving…"
        saving -> "Saving on device…"
        else -> "Saved on device"
    }
    val statusColor = if (saveFailed && !retryingSave) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    // A slim clickable row rather than a TextButton: TextButton forces a 40dp min
    // height, which pushed the status line away from the title in the wide layout.
    Row(Modifier.clip(FolioShapes.small)
        .clickable(role = Role.Button) { detailsOpen = true }
        .padding(horizontal = FolioSpacing.dp2, vertical = FolioSpacing.dp2),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(when {
            saveFailed && !retryingSave -> Icons.Rounded.ErrorOutline
            saving -> Icons.Rounded.Sync
            else -> Icons.Rounded.Check
        }, null, Modifier.size(12.dp), tint = statusColor)
        Spacer(Modifier.width(FolioSpacing.dp4))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = statusColor)
    }
    if (detailsOpen) AlertDialog(
        onDismissRequest = { detailsOpen = false }, modifier = Modifier.guardUiTouches(),
        title = { Text(when {
            retryingSave -> "Retrying the save"
            saveFailed -> "Changes could not be saved"
            slow -> "Saving is taking longer"
            saving -> "Saving on this device"
            else -> "Changes saved"
        }) },
        text = { Text(when {
            retryingSave -> "Folio is writing the latest notebook and library data to this device. Keep the app open until it says Saved on device."
            saveFailed -> "${saveFailureReason ?: "The device could not finish the write."} Your latest changes are still open in Folio. Retry the save before closing the app."
            slow -> "Folio is still writing to this device. Large pages, images, or busy storage can slow writes. Keep the app open until it says Saved on device."
            saving -> "The changes you have made are on their way to this device. Folio combines them while you write, so this is usually the last stroke or two."
            else -> "All queued changes have been written to this device."
        }) },
        confirmButton = {
            if (saveFailed && !retryingSave) TextButton({ detailsOpen = false; onRetrySave() }) { Text("Retry save") }
            else TextButton({ detailsOpen = false }) { Text("Stay here") }
        },
        dismissButton = {
            if (!saveFailed && saving) TextButton({ detailsOpen = false; onClose() }) {
                Text("Library · save continues")
            } else TextButton({ detailsOpen = false }) { Text("Close") }
        }
    )
}
