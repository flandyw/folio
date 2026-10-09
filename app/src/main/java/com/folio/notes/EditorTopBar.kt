@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
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
    layerStatus: String?,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onInsertImage: () -> Unit,
    onContents: (() -> Unit)?,
    paperTitle: String,
    onPaper: (() -> Unit)?,
    layersPopover: @Composable () -> Unit,
    onInsertPage: () -> Unit,
    onDuplicatePage: () -> Unit,
    notebookActions: @Composable (() -> Unit) -> Unit = {},
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onKeyboardShortcuts: () -> Unit,
    pageActions: @Composable (() -> Unit) -> Unit,
    /** False where leaving the editor mid-task is wrong (mistake review has its own back button). */
    showBack: Boolean = true
) {
    var navMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Size against this editor pane. Only the tool tray scrolls; the left and right pills
        // always remain on screen. The timer is measured at its natural width so a chip is never
        // clipped: it either fits beside the tools or lives in the notebook menu.
        val timerWidth = remember { mutableStateOf(0.dp) }
        MeasureNaturalWidth(timerWidth) { timer() }
        val timerNeed = timerWidth.value + 12.dp + FolioSpacing.dp8 // surface padding + slack
        val toolsNeed = 400.dp
        val groupGaps = FolioSpacing.dp6 * 2
        val documentWidth = 40.dp * 2 + FolioSpacing.dp8
        val expandedNavWidth = 40.dp * ((if (showBack) 1 else 0) + 4) + FolioSpacing.dp8
        // Both side pills take the wider side's width so the tools and the ink bar below them
        // share the screen's centre line.
        // Keep navigation compact on most tablets so drawing tools get the width. Wide
        // windows can expose the individual actions without adding another toolbar row.
        val foldNav = maxWidth < toolsNeed + maxOf(expandedNavWidth, documentWidth) * 2 + groupGaps
        val navButtons = (if (showBack) 1 else 0) + if (foldNav) 1 else 4
        val leftNatural = 40.dp * navButtons + FolioSpacing.dp8
        val rightWithTimer = documentWidth + timerNeed + FolioSpacing.dp6
        val compact = maxWidth < toolsNeed + maxOf(leftNatural, rightWithTimer) * 2 + groupGaps
        val rightNatural = documentWidth + if (compact) 0.dp else timerNeed + FolioSpacing.dp6
        val sideWidth = maxOf(leftNatural, rightNatural)
        // Reflow before either side squeezes the tool tray below a useful width. This also
        // accounts for a running timer's actual size instead of a fixed window breakpoint.
        val narrow = maxWidth < toolsNeed + sideWidth * 2 + groupGaps
        @Composable fun NavigationControls() {
            Box(Modifier, contentAlignment = Alignment.CenterStart) { EditorGlassSurface {
                Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                    if (showBack) DockButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks", onClose)
                    if (foldNav) Box {
                        DockButton(Icons.Rounded.MoreHoriz, "Pages, search and layers", { navMenu = true })
                        FolioMenuPopover(navMenu, { navMenu = false }, modifier = Modifier.guardUiTouches(), title = "Notebook pages") {
                            val closeThen: (() -> Unit) -> Unit = { navMenu = false; it() }
                            FolioMenuItem({ Text("Browse pages") }, { closeThen(onPages) }, leadingIcon = { Icon(Icons.Rounded.GridView, null) })
                            FolioMenuItem({ Text("Blank page after this") }, { closeThen(onInsertPage) }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                            FolioMenuItem({ Text("Blank page at end") }, { closeThen(onAdd) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                            FolioMenuItem({ Text("Duplicate this page") }, { closeThen(onDuplicatePage) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                            FolioMenuItem({ Text("Find in notes") }, { closeThen(onSearch) }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
                            FolioMenuItem({ Text("Layers") }, { closeThen(onLayers) }, leadingIcon = { Icon(Icons.Rounded.Layers, null) })
                        }
                        layersPopover()
                    } else {
                    DockButton(Icons.Rounded.GridView, "Browse pages", onPages)
                    Box {
                        DockButton(Icons.Rounded.AddBox, "Add or duplicate page", { addMenu = true })
                        FolioMenuPopover(addMenu, { addMenu = false }, modifier = Modifier.guardUiTouches(), title = "Add a page") {
                            FolioMenuItem({ Text("Blank page after this") }, { addMenu = false; onInsertPage() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                            FolioMenuItem({ Text("Blank page at end") }, { addMenu = false; onAdd() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                            FolioMenuItem({ Text("Duplicate this page") }, { addMenu = false; onDuplicatePage() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                        }
                    }
                    DockButton(Icons.Rounded.Search, "Find in notes", onSearch)
                    Box {
                        DockButton(Icons.Rounded.Layers, "Layers", onLayers)
                        layersPopover()
                    }
                    }
                }
            } }
        }
        @Composable fun DocumentControls() {
            Row(Modifier, verticalAlignment = Alignment.CenterVertically,
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
                            if (overflow) FolioPopover({ overflow = false }, width = 320.dp) {
                                val dismiss = { overflow = false }
                                val run: (() -> Unit) -> Unit = { dismiss(); it() }
                                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                SaveStatus(saveFailed, retryingSave, saveFailureReason, lastSaveProgressAt, saving, onRetrySave, onClose)
                                if (compact) timer()
                                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    PopoverTile(Icons.Rounded.Edit, "Rename", Modifier.weight(1f)) { run(onRename) }
                                    PopoverTile(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        "Favourite", Modifier.weight(1f), active = starred) { run(onStar) }
                                    PopoverTile(Icons.Rounded.Dashboard, "Pages", Modifier.weight(1f)) { run(onPages) }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                    PopoverGroup("Go to page · ${pageIndex + 1} of $pageCount") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                            PopoverTile(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous", Modifier.weight(1f), enabled = pageIndex > 0) { run(onPrevious) }
                                            PopoverTile(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next", Modifier.weight(1f), enabled = pageIndex < pageCount - 1) { run(onNext) }
                                        }
                                        PopoverRow(Icons.Rounded.FirstPage, "First page", enabled = pageIndex > 0) { run(onFirstPage) }
                                        PopoverRow(Icons.AutoMirrored.Rounded.LastPage, "Last page", enabled = pageIndex < pageCount - 1) { run(onLastPage) }
                                    }
                                    HorizontalDivider()
                                    PopoverGroup("Add page") {
                                        PopoverRow(Icons.Rounded.Add, "Add page at end") { run(onAdd) }
                                        PopoverRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Insert after this page") { run(onInsertPage) }
                                        PopoverRow(Icons.Rounded.ContentCopy, "Duplicate this page") { run(onDuplicatePage) }
                                    }
                                    HorizontalDivider()
                                    PopoverGroup("View") {
                                        PopoverRow(Icons.Rounded.FitScreen, (if (onFitAll != null) "Return to origin" else "Reset zoom") + " · $zoomPercent%") { run(onFit) }
                                        if (onFitAll != null) PopoverRow(Icons.Rounded.CenterFocusStrong, "Fit all content") { run(onFitAll) }
                                    }
                                    HorizontalDivider()
                                    pageActions(dismiss)
                                    HorizontalDivider()
                                    PopoverGroup("Workspace") { notebookActions(dismiss) }
                                    HorizontalDivider()
                                    PopoverRow(Icons.Rounded.Keyboard, "Keyboard shortcuts") { run(onKeyboardShortcuts) }
                                    PopoverRow(Icons.Rounded.Tune, "App settings…") { run(onSettings) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
            if (layerStatus != null) Box {
                TextButton(onLayers) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8))
                    Text(layerStatus, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(FolioSpacing.dp8)); Text("Layers")
                }
            }
            run {
                if (narrow) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                        NavigationControls()
                        DocumentControls()
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { mainTools() }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                        Box(Modifier.width(sideWidth), contentAlignment = Alignment.CenterStart) { NavigationControls() }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { mainTools() }
                        Box(Modifier.width(sideWidth), contentAlignment = Alignment.CenterEnd) { DocumentControls() }
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
