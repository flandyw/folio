@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val EditorFloatingGroupHeight = 46.dp

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
    onSearch: () -> Unit,
    onLayers: () -> Unit,
    layersPopover: @Composable () -> Unit,
    onInsertPage: () -> Unit,
    onDuplicatePage: () -> Unit,
    notebookActions: @Composable (() -> Unit) -> Unit = {},
    onExport: () -> Unit,
    onPageOptions: () -> Unit,
    additionalMenus: @Composable () -> Unit
) {
    var overflow by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<TopSub?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Size against this editor pane. Only the tool tray scrolls; the left and right pills
        // always remain on screen. The timer is measured at its natural width so a chip is never
        // clipped: it either fits beside the tools or lives in the notebook menu.
        val timerWidth = remember { mutableStateOf(0.dp) }
        MeasureNaturalWidth(timerWidth) { timer() }
        val timerNeed = timerWidth.value + 12.dp + FolioSpacing.dp8 // surface padding + slack
        val compact = maxWidth - 760.dp < timerNeed
        // Both side pills take the wider side's width so the tools and the ink bar below them
        // share the screen's centre line.
        val leftNatural = 40.dp * 5 + FolioSpacing.dp8
        val rightNatural = 40.dp * 2 + FolioSpacing.dp8 + if (compact) 0.dp else timerNeed + FolioSpacing.dp6
        val sideWidth = maxOf(leftNatural, rightNatural)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
            Box(Modifier.width(sideWidth), contentAlignment = Alignment.CenterStart) { EditorGlassSurface {
                Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                    DockButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks", onClose)
                    DockButton(Icons.Rounded.GridView, "Browse pages", onPages)
                    DockButton(Icons.Rounded.AddBox, "Add page at end", onAdd)
                    DockButton(Icons.Rounded.Search, "Find in notes", onSearch)
                    Box {
                        DockButton(Icons.Rounded.Layers, "Layers", onLayers)
                        layersPopover()
                    }
                }
            } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { mainTools() }
            Row(Modifier.width(sideWidth), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6, Alignment.End)) {
                if (!compact) EditorGlassSurface {
                    Row(Modifier.padding(horizontal = FolioSpacing.dp6),
                        verticalAlignment = Alignment.CenterVertically) { timer() }
                }
                EditorGlassSurface {
                    Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                        DockButton(Icons.Rounded.IosShare, "Share or export", onExport)
                        Box {
                            DockButton(if (saveFailed) Icons.Rounded.ErrorOutline else Icons.Rounded.MoreVert,
                                if (saveFailed) "Save failed. Notebook actions" else "Notebook actions",
                                { overflow = true },
                                tint = if (saveFailed) MaterialTheme.colorScheme.error else LocalContentColor.current)
                    DropdownMenu(overflow, { overflow = false }, modifier = Modifier.guardUiTouches()) {
                        Text(title, Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                            style = MaterialTheme.typography.titleSmall)
                        DropdownMenuItem({ Text("Page options") }, { overflow = false; onPageOptions() },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null) })
                        HorizontalDivider()
                        Box(Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10)) {
                            SaveStatus(saveFailed, retryingSave, saveFailureReason, lastSaveProgressAt, saving, onRetrySave, onClose)
                        }
                        if (compact) Box(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp4)) { timer() }
                        HorizontalDivider()
                        SubmenuItem("Notebook", Icons.AutoMirrored.Rounded.MenuBook, sub == TopSub.NOTEBOOK, { sub = if (sub == TopSub.NOTEBOOK) null else TopSub.NOTEBOOK }) {
                            DropdownMenuItem({ Text("Rename notebook") }, { overflow = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                            DropdownMenuItem({ Text(if (starred) "Remove from favourites" else "Add to favourites") }, { overflow = false; onStar() }, leadingIcon = { Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) })
                        }
                        SubmenuItem("Pages", Icons.Rounded.Dashboard, sub == TopSub.PAGES, { sub = if (sub == TopSub.PAGES) null else TopSub.PAGES }) {
                            DropdownMenuItem({ Text("Browse pages") }, { overflow = false; onPages() }, leadingIcon = { Icon(Icons.Rounded.Dashboard, null) })
                            DropdownMenuItem({ Text("Previous page") }, { overflow = false; onPrevious() }, enabled = pageIndex > 0, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null) })
                            DropdownMenuItem({ Text("Next page") }, { overflow = false; onNext() }, enabled = pageIndex < pageCount - 1, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) })
                            DropdownMenuItem({ Text("First page") }, { overflow = false; onFirstPage() }, enabled = pageIndex > 0)
                            DropdownMenuItem({ Text("Last page") }, { overflow = false; onLastPage() }, enabled = pageIndex < pageCount - 1)
                        }
                        SubmenuItem("Add page", Icons.Rounded.Add, sub == TopSub.ADD, { sub = if (sub == TopSub.ADD) null else TopSub.ADD }) {
                            DropdownMenuItem({ Text("Add page at end") }, { overflow = false; onAdd() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                            DropdownMenuItem({ Text("Insert after this page") }, { overflow = false; onInsertPage() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                            DropdownMenuItem({ Text("Duplicate this page") }, { overflow = false; onDuplicatePage() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                        }
                        SubmenuItem("View", Icons.Rounded.FitScreen, sub == TopSub.VIEW, { sub = if (sub == TopSub.VIEW) null else TopSub.VIEW }) {
                            DropdownMenuItem({ Text("Reset zoom · $zoomPercent%") }, { overflow = false; onFit() }, leadingIcon = { Icon(Icons.Rounded.FitScreen, null) })
                            if (onFitAll != null) DropdownMenuItem({ Text("Fit all content") }, { overflow = false; onFitAll() })
                        }
                        SubmenuItem("Workspace", Icons.AutoMirrored.Rounded.ChromeReaderMode, sub == TopSub.WORKSPACE, { sub = if (sub == TopSub.WORKSPACE) null else TopSub.WORKSPACE }) {
                            notebookActions { overflow = false }
                        }
                    }
                    additionalMenus()
                        }
                    }
                }
            }
        }
    }
}

/** One compact 40dp icon button shared by the dock pills. */
@Composable private fun DockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color = LocalContentColor.current) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, modifier = Modifier.size(40.dp)) { Icon(icon, label, Modifier.size(20.dp), tint = tint) }
    }
}

/** Composes [content] once, unconstrained and invisible, and reports how wide it wants to be. */
@Composable private fun MeasureNaturalWidth(width: MutableState<androidx.compose.ui.unit.Dp>, content: @Composable () -> Unit) {
    SubcomposeLayout(Modifier) { constraints ->
        val placeable = subcompose("natural", content).map { it.measure(androidx.compose.ui.unit.Constraints()) }
        val natural = (placeable.maxOfOrNull { it.width } ?: 0).toDp()
        if (width.value != natural) width.value = natural
        layout(0, 0) {}
    }
}

@Composable private fun SaveStatus(
    saveFailed: Boolean,
    retryingSave: Boolean,
    saveFailureReason: String?,
    lastSaveProgressAt: Long?,
    saving: Boolean,
    onRetrySave: () -> Unit,
    onClose: () -> Unit
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

private enum class TopSub { NOTEBOOK, PAGES, ADD, VIEW, WORKSPACE }
