@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.editor.toolbar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.notes.input.guardUiTouches
import com.folio.notes.input.longPressAction
import com.folio.notes.input.rememberLongPressGuard
import com.folio.notes.settings.largeEditorControls
import com.folio.notes.settings.showEditorToolNames
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioPopover
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.PopoverGroup
import com.folio.notes.ui.PopoverRow
import com.folio.notes.ui.PopoverTile
import com.folio.notes.workspace.LocalSplitView
import com.folio.notes.workspace.SplitViewControl

internal val EditorFloatingGroupHeight: Dp @Composable get() = if (showEditorToolNames()) (if (largeEditorControls()) 72.dp else 60.dp) else if (largeEditorControls()) 56.dp else 40.dp
internal val DockButtonWidth: Dp @Composable get() = if (largeEditorControls()) 48.dp else 40.dp
internal val DockButtonHeight: Dp @Composable get() = if (largeEditorControls()) 48.dp else 36.dp
/** The gap between neighbouring buttons in the left and right groups. */
internal val DockGap = FolioSpacing.dp4
/** Width of [buttons] dock buttons, each followed by a [DockGap] (so a row errs a gap wide, never narrow). */
@Composable internal fun dockWidth(buttons: Int): Dp = (DockButtonWidth + DockGap) * buttons

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
    onPages: () -> Unit,
    zoomPercent: Int,
    onFit: () -> Unit,
    onFitAll: (() -> Unit)? = null,
    onAdd: () -> Unit,
    onSearch: () -> Unit,
    /** What the search button finds here: an imported PDF searches its printed text. */
    searchLabel: String = "Find in notes",
    onLayers: () -> Unit,
    layerStatus: String?,
    /** The active layer is hidden rather than locked, which picks the banner's icon. */
    layerHidden: Boolean = false,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onInsertImage: () -> Unit,
    layersPopover: @Composable () -> Unit,
    onInsertPage: () -> Unit,
    onDuplicatePage: () -> Unit,
    notebookActions: @Composable (() -> Unit) -> Unit = {},
    onExport: () -> Unit,
    /** Long-press on the share button; null leaves the long-press inert. */
    onShareLongPress: (() -> Unit)? = null,
    /** Null hides the review button (hidden notebooks such as mistake practice pages have none). */
    onChatGpt: (() -> Unit)? = null,
    onSettings: () -> Unit,
    onKeyboardShortcuts: () -> Unit,
    documentActions: @Composable (() -> Unit) -> Unit,
    /** False where leaving the editor mid-task is wrong (mistake review has its own back button). */
    showBack: Boolean = true,
    toolbarOptions: ToolbarOptions = ToolbarOptions(),
    /** Open documents for the tab strip. */
    tabs: List<EditorTabChip> = emptyList(),
    activeTabId: String? = null,
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    /** Null hides the strip's "+" (nowhere to pick another document from). */
    onNewTab: (() -> Unit)? = null
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
        val toolsNeed = 400.dp
        val split = LocalSplitView.current
        val shareButtons = 1 + (if (onChatGpt != null) 1 else 0) + (if (split != null) 1 else 0)
        val docExtraWidth = dockWidth(2)
        @Composable fun PageExtras() {
            DockButton(Icons.Rounded.Image, "Insert picture", onInsertImage)
            DockButton(Icons.Rounded.FitScreen, (if (onFitAll != null) "Return to origin" else "Reset zoom") + " · $zoomPercent%", onFit)
        }
        @Composable fun NotebookMenu(timerInMenu: Boolean, pageExtrasInMenu: Boolean) {
            FolioPopover({ overflow = false }, width = 320.dp) {
                val dismiss = { overflow = false }
                val run: (() -> Unit) -> Unit = { dismiss(); it() }
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SaveStatus(saveFailed, retryingSave, saveFailureReason, lastSaveProgressAt, saving, onRetrySave, onClose)
                if (timerInMenu) timer()
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    PopoverTile(Icons.Rounded.Edit, "Rename", Modifier.weight(1f)) { run(onRename) }
                    PopoverTile(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        "Favourite", Modifier.weight(1f), active = starred) { run(onStar) }
                    PopoverTile(Icons.Rounded.Dashboard, "Pages", Modifier.weight(1f)) { run(onPages) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    PopoverGroup("Page") {
                        PopoverRow(Icons.Rounded.Add, "Add page at end") { run(onAdd) }
                        PopoverRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Insert after this page") { run(onInsertPage) }
                        PopoverRow(Icons.Rounded.ContentCopy, "Duplicate this page") { run(onDuplicatePage) }
                        // The bar only shows the bookmark when it is wide; it must still be reachable here.
                        if (pageExtrasInMenu) PopoverRow(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (bookmarked) "Remove bookmark" else "Bookmark this page") { run(onBookmark) }
                    }
                    HorizontalDivider()
                    if (onFitAll != null || pageExtrasInMenu) {
                        PopoverGroup("View") {
                            if (pageExtrasInMenu) PopoverRow(Icons.Rounded.FitScreen,
                                (if (onFitAll != null) "Return to origin" else "Reset zoom") + " · $zoomPercent%") { run(onFit) }
                            if (onFitAll != null) PopoverRow(Icons.Rounded.CenterFocusStrong, "Fit all content") { run(onFitAll) }
                        }
                        HorizontalDivider()
                    }
                    documentActions(dismiss)
                    HorizontalDivider()
                    PopoverGroup("Workspace") { notebookActions(dismiss) }
                    HorizontalDivider()
                    PopoverRow(Icons.Rounded.Keyboard, "Keyboard shortcuts") { run(onKeyboardShortcuts) }
                    PopoverRow(Icons.Rounded.Tune, "App settings…") { run(onSettings) }
                }
            }
        }
        run {
            // One flat bar: pages, find, layers and add page on the left; the tools in the middle; the timer,
            // share and notebook menu on the right. Narrow panes fold the left side into a menu and
            // finally stack the tools onto their own row, so nothing goes missing.
            // The row's side insets come out of the width first, so the fold points match what is drawn.
            val rowWidth = maxWidth - BarInset * 2
            val barGap = FolioSpacing.dp12
            // With the tab strip turned off, the way home moves onto the bar.
            val showTabs = showBack && toolbarOptions.tabs
            val homeOnBar = showBack && !showTabs
            val homeCount = if (homeOnBar) 1 else 0
            // The floating page navigator already turns pages, so the bar keeps only the bookmark from it.
            val leftFull = dockWidth(homeCount + 4)
            val leftFolded = dockWidth(homeCount + 3)
            val leftDense = dockWidth(homeCount + 5)
            val rightBase = dockWidth(shareButtons + 1)
            val rightWithTimer = rightBase + timerWidth.value + DockGap
            val timerInline = toolbarOptions.timer && rowWidth >= toolsNeed + maxOf(leftFull, rightWithTimer) * 2 + barGap * 2
            val fold = rowWidth < toolsNeed + maxOf(leftFull, rightBase) * 2 + barGap * 2
            val stacked = rowWidth < toolsNeed + maxOf(leftFolded, rightBase) * 2 + barGap * 2
            val rightNow = if (timerInline) rightWithTimer else rightBase
            val denseBar = rowWidth >= toolsNeed + maxOf(leftDense, rightNow + docExtraWidth) * 2 + barGap * 2
            val side = if (denseBar) maxOf(leftDense, rightNow + docExtraWidth)
                else maxOf(if (fold) leftFolded else leftFull, rightNow)
            @Composable fun LeftControls() {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DockGap)) {
                    if (homeOnBar) DockButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks", onClose)
                    DockButton(Icons.Rounded.GridView, "Browse pages", onPages)
                    if (fold) Box {
                        DockButton(Icons.Rounded.MoreHoriz, "Find and layers", { navMenu = true })
                        FolioMenuPopover(navMenu, { navMenu = false }, modifier = Modifier.guardUiTouches(), title = "Find & layers") {
                            val closeThen: (() -> Unit) -> Unit = { navMenu = false; it() }
                            FolioMenuItem({ Text(searchLabel) }, { closeThen(onSearch) }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
                            FolioMenuItem({ Text("Layers") }, { closeThen(onLayers) }, leadingIcon = { Icon(Icons.Rounded.Layers, null) })
                        }
                        layersPopover()
                    } else {
                        DockButton(Icons.Rounded.Search, searchLabel, onSearch)
                        Box {
                            DockButton(Icons.Rounded.Layers, "Layers", onLayers)
                            layersPopover()
                        }
                    }
                    Box {
                        DockButton(Icons.Rounded.AddBox, "Add or duplicate page", { addMenu = true })
                        FolioMenuPopover(addMenu, { addMenu = false }, modifier = Modifier.guardUiTouches(), title = "Add a page") {
                            FolioMenuItem({ Text("Blank page after this") }, { addMenu = false; onInsertPage() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                            FolioMenuItem({ Text("Blank page at end") }, { addMenu = false; onAdd() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                            FolioMenuItem({ Text("Duplicate this page") }, { addMenu = false; onDuplicatePage() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                        }
                    }
                    if (denseBar) DockButton(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        if (bookmarked) "Remove bookmark" else "Bookmark this page", onBookmark,
                        tint = if (bookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            }
            @Composable fun RightControls() {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DockGap)) {
                    if (timerInline) Row(verticalAlignment = Alignment.CenterVertically) { timer() }
                    if (denseBar) PageExtras()
                    onChatGpt?.let { DockButton(Icons.Rounded.AutoAwesome, "Review page with ChatGPT", it) }
                    split?.let { SplitViewButton(it) }
                    DockButton(Icons.Rounded.IosShare, "Share or export", onExport, onLongClick = onShareLongPress)
                    Box {
                        DockButton(if (saveFailed) Icons.Rounded.ErrorOutline else Icons.Rounded.MoreVert,
                            if (saveFailed) "Save failed. Notebook actions" else "Notebook actions",
                            { overflow = true },
                            tint = if (saveFailed) MaterialTheme.colorScheme.error else LocalContentColor.current)
                        if (overflow) NotebookMenu(timerInMenu = !timerInline, pageExtrasInMenu = !denseBar)
                    }
                }
            }
            EditorBar(
                tabs = if (showTabs) tabs else null, activeId = activeTabId,
                onHome = onClose, onSelectTab = onSelectTab, onCloseTab = onCloseTab, onNewTab = onNewTab
            ) {
                val row = Modifier.fillMaxWidth().height(BarRowHeight).padding(horizontal = BarInset)
                if (stacked) {
                    FlowRow(Modifier.fillMaxWidth().heightIn(min = BarRowHeight).padding(horizontal = BarInset),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        LeftControls(); RightControls()
                    }
                    HorizontalDivider(Modifier.padding(horizontal = BarInset), color = BarTones.divider)
                    Box(row, contentAlignment = Alignment.Center) { mainTools() }
                } else {
                    Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(barGap)) {
                        Box(Modifier.width(side), contentAlignment = Alignment.CenterStart) { LeftControls() }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { mainTools() }
                        Box(Modifier.width(side), contentAlignment = Alignment.CenterEnd) { RightControls() }
                    }
                }
                if (layerStatus != null) TextButton(onLayers, Modifier.align(Alignment.CenterHorizontally).padding(bottom = FolioSpacing.dp4),
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
                    Icon(if (layerHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8))
                    Text(layerStatus, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(FolioSpacing.dp8)); Text("Layers")
                }
            }
        }
    }
}

/** Shared dock button; reserve the full Material touch width in the row budget. */
/** Opens a second document beside this one, or closes the split; lit while the split is open. */
@Composable private fun SplitViewButton(split: SplitViewControl) {
    DockButton(Icons.Rounded.VerticalSplit, if (split.open) "Close split view" else "Split view — open a document beside",
        split.toggle, tint = if (split.open) MaterialTheme.colorScheme.primary else LocalContentColor.current)
}

@Composable private fun DockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color = LocalContentColor.current, onLongClick: (() -> Unit)? = null, width: Dp = DockButtonWidth, enabled: Boolean = true) {
    val hold = rememberLongPressGuard()
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
        // The tooltip's own long-press would swallow the hold, so a button with a hold action gives it up.
        enableUserInput = onLongClick == null) {
        // The hold and the tap share one guard so a long-press never also fires the tap on release.
        IconButton(hold.click(onClick), enabled = enabled, modifier = Modifier.width(width).height(DockButtonHeight).then(if (onLongClick != null) Modifier.longPressAction(hold, onLongClick) else Modifier)) {
            Icon(icon, label, Modifier.size(20.dp), tint = tint)
        }
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
            when {
                saveFailed && !retryingSave -> TextButton({ detailsOpen = false; onRetrySave() }) { Text("Retry save") }
                saving -> TextButton({ detailsOpen = false }) { Text("Stay here") }
                else -> TextButton({ detailsOpen = false }) { Text("OK") }
            }
        },
        // Two buttons only when they do different things; a finished save has nothing to choose.
        dismissButton = {
            if (!saveFailed && saving) TextButton({ detailsOpen = false; onClose() }) {
                Text("Library · save continues")
            } else if (saveFailed && !retryingSave) TextButton({ detailsOpen = false }) { Text("Close") }
        }
    )
}
