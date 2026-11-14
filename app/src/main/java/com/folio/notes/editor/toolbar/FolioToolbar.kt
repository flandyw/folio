@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.editor.toolbar

import com.folio.notes.ui.FolioIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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

internal val EditorFloatingGroupHeight: Dp @Composable get() = if (showEditorToolNames()) (if (largeEditorControls()) 72.dp else 60.dp) else if (largeEditorControls()) 56.dp else 40.dp
internal val DockButtonWidth: Dp @Composable get() = if (largeEditorControls()) 48.dp else 40.dp
internal val DockButtonHeight: Dp @Composable get() = if (largeEditorControls()) 48.dp else 36.dp
/** The gap between neighbouring buttons in the left and right groups. */
internal val DockGap = FolioSpacing.dp4
/** Width of [buttons] dock buttons, each followed by a [DockGap] (so a row errs a gap wide, never narrow). */
@Composable internal fun dockWidth(buttons: Int): Dp = (DockButtonWidth + DockGap) * buttons
/** What the tool strip needs before a bar gives it a row of its own. */
private val ToolsNeed = 400.dp
private val BarGap = FolioSpacing.dp12

/** The floating capsule for controls that sit over the page: one flat tone, a hairline and a soft shadow. */
@Composable internal fun EditorGlassSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(EditorFloatingGroupHeight).guardUiTouches(),
        shape = FolioShapes.panel,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f))
    ) { Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { content() } }
}

/**
 * The bar's second row: [left] and [right] in equal [side] columns so the tools stay centred. A
 * [stacked] pane is too narrow for that, so the two groups wrap onto one row (never clipped) and
 * the tools take the next.
 */
@Composable private fun ColumnScope.BarRows(stacked: Boolean, side: Dp, left: @Composable () -> Unit, right: @Composable () -> Unit, tools: @Composable () -> Unit) {
    val row = Modifier.fillMaxWidth().height(BarRowHeight).padding(horizontal = BarInset)
    if (stacked) {
        FlowRow(Modifier.fillMaxWidth().heightIn(min = BarRowHeight).padding(horizontal = BarInset),
            horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) { left(); right() }
        HorizontalDivider(Modifier.padding(horizontal = BarInset), color = BarTones.divider)
        Box(row, contentAlignment = Alignment.Center) { tools() }
    } else Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BarGap)) {
        Box(Modifier.width(side), contentAlignment = Alignment.CenterStart) { left() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { tools() }
        Box(Modifier.width(side), contentAlignment = Alignment.CenterEnd) { right() }
    }
}

@Composable private fun DockRow(content: @Composable RowScope.() -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DockGap), content = content)

/** The editor bar: pages, find, layers and add page on the left, the tools in the middle, share and the notebook menu on the right. */
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
    onCloseTabs: ((List<String>) -> Unit)? = null,
    /** Null hides the strip's "+" (nowhere to pick another document from). */
    onNewTab: (() -> Unit)? = null
) {
    var navMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val fitLabel = (if (onFitAll != null) "Return to origin" else "Reset zoom") + " · $zoomPercent%"
    val bookmarkIcon = if (bookmarked) FolioIcons.BookmarkFilled else FolioIcons.Bookmark
    val bookmarkLabel = if (bookmarked) "Remove bookmark" else "Bookmark this page"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Size against this editor pane. The timer is measured at its natural width so a chip is never
        // clipped: it either fits beside the tools or lives in the notebook menu.
        val timerWidth = remember { mutableStateOf(0.dp) }
        MeasureNaturalWidth(timerWidth) { timer() }
        val split = LocalSplitView.current
        // Narrow panes fold find and layers into a menu and finally stack the tools onto their own
        // row, so nothing goes missing; a wide pane also takes the bookmark, picture and zoom buttons.
        // The row's side insets come out of the width first, so the fold points match what is drawn.
        val rowWidth = maxWidth - BarInset * 2
        // With the tab strip turned off, the way home moves onto the bar.
        val showTabs = showBack && toolbarOptions.tabs
        val home = if (showBack && !showTabs) 1 else 0
        val leftFull = dockWidth(home + 4)
        val leftFolded = dockWidth(home + 3)
        val leftDense = dockWidth(home + 5)
        val rightBase = dockWidth(2 + (if (onChatGpt != null) 1 else 0) + (if (split != null) 1 else 0))
        val rightWithTimer = rightBase + timerWidth.value + DockGap
        fun fits(side: Dp) = rowWidth >= ToolsNeed + side * 2 + BarGap * 2
        val timerInline = toolbarOptions.timer && fits(maxOf(leftFull, rightWithTimer))
        val fold = !fits(maxOf(leftFull, rightBase))
        val stacked = !fits(maxOf(leftFolded, rightBase))
        val rightNow = if (timerInline) rightWithTimer else rightBase
        val dense = fits(maxOf(leftDense, rightNow + dockWidth(2)))
        val side = if (dense) maxOf(leftDense, rightNow + dockWidth(2)) else maxOf(if (fold) leftFolded else leftFull, rightNow)
        EditorBar(
            tabs = if (showTabs) tabs else null, activeId = activeTabId,
            onHome = onClose, onSelectTab = onSelectTab, onCloseTab = onCloseTab, onNewTab = onNewTab, onCloseTabs = onCloseTabs
        ) {
            BarRows(stacked, side, tools = mainTools, left = { DockRow {
                if (home == 1) DockButton(FolioIcons.Back, "Back to notebooks", onClose)
                DockButton(FolioIcons.Pages, "Browse pages", onPages)
                if (fold) Box {
                    DockButton(FolioIcons.More, "Find and layers", { navMenu = true })
                    FolioMenuPopover(navMenu, { navMenu = false }, modifier = Modifier.guardUiTouches(), title = "Find & layers") {
                        FolioMenuItem({ Text(searchLabel) }, { navMenu = false; onSearch() }, leadingIcon = { Icon(FolioIcons.Search, null) })
                        FolioMenuItem({ Text("Layers") }, { navMenu = false; onLayers() }, leadingIcon = { Icon(FolioIcons.Layers, null) })
                    }
                    layersPopover()
                } else {
                    DockButton(FolioIcons.Search, searchLabel, onSearch)
                    Box { DockButton(FolioIcons.Layers, "Layers", onLayers); layersPopover() }
                }
                Box {
                    DockButton(FolioIcons.AddPage, "Add or duplicate page", { addMenu = true })
                    FolioMenuPopover(addMenu, { addMenu = false }, modifier = Modifier.guardUiTouches(), title = "Add a page") {
                        FolioMenuItem({ Text("Blank page after this") }, { addMenu = false; onInsertPage() }, leadingIcon = { Icon(FolioIcons.Plus, null) })
                        FolioMenuItem({ Text("Blank page at end") }, { addMenu = false; onAdd() }, leadingIcon = { Icon(FolioIcons.AddPage, null) })
                        FolioMenuItem({ Text("Duplicate this page") }, { addMenu = false; onDuplicatePage() }, leadingIcon = { Icon(FolioIcons.Duplicate, null) })
                    }
                }
                if (dense) DockButton(bookmarkIcon, bookmarkLabel, onBookmark, tint = if (bookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            } }, right = { DockRow {
                if (timerInline) timer()
                if (dense) {
                    DockButton(FolioIcons.Image, "Insert picture", onInsertImage)
                    DockButton(FolioIcons.Fit, fitLabel, onFit)
                }
                onChatGpt?.let { DockButton(FolioIcons.Sparkle, "Review page with ChatGPT", it) }
                // Opens a second document beside this one, or closes the split; lit while the split is open.
                split?.let { DockButton(FolioIcons.Split, if (it.open) "Close split view" else "Split view — open a document beside",
                    it.toggle, tint = if (it.open) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                DockButton(FolioIcons.Share, "Share or export", onExport, onLongClick = onShareLongPress)
                Box {
                    DockButton(if (saveFailed) FolioIcons.Alert else FolioIcons.MoreVertical,
                        if (saveFailed) "Save failed. Notebook actions" else "Notebook actions", { overflow = true },
                        tint = if (saveFailed) MaterialTheme.colorScheme.error else LocalContentColor.current)
                    if (overflow) FolioPopover({ overflow = false }, width = 320.dp) {
                        val dismiss = { overflow = false }
                        val run: (() -> Unit) -> Unit = { dismiss(); it() }
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        SaveStatus(saveFailed, retryingSave, saveFailureReason, lastSaveProgressAt, saving, onRetrySave, onClose)
                        if (!timerInline) timer()
                        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            PopoverTile(FolioIcons.Rename, "Rename", Modifier.weight(1f)) { run(onRename) }
                            PopoverTile(if (starred) FolioIcons.StarFilled else FolioIcons.Star, "Favourite", Modifier.weight(1f), active = starred) { run(onStar) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                            if (onFitAll != null || !dense) {
                                PopoverGroup("Page") {
                                    // The bar only shows these when it is wide; they must still be reachable here.
                                    if (!dense) PopoverRow(bookmarkIcon, bookmarkLabel) { run(onBookmark) }
                                    if (!dense) PopoverRow(FolioIcons.Image, "Insert picture") { run(onInsertImage) }
                                    if (!dense) PopoverRow(FolioIcons.Fit, fitLabel) { run(onFit) }
                                    if (onFitAll != null) PopoverRow(FolioIcons.FitAll, "Fit all content") { run(onFitAll) }
                                }
                                HorizontalDivider()
                            }
                            documentActions(dismiss)
                            HorizontalDivider()
                            PopoverGroup("Workspace") { notebookActions(dismiss) }
                            HorizontalDivider()
                            PopoverRow(FolioIcons.Keyboard, "Keyboard shortcuts") { run(onKeyboardShortcuts) }
                            PopoverRow(FolioIcons.Tune, "App settings…") { run(onSettings) }
                        }
                    }
                }
            } })
            if (layerStatus != null) TextButton(onLayers, Modifier.align(Alignment.CenterHorizontally).padding(bottom = FolioSpacing.dp4),
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
                Icon(if (layerHidden) FolioIcons.Hidden else FolioIcons.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8))
                Text(layerStatus, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(FolioSpacing.dp8)); Text("Layers")
            }
        }
    }
}

/**
 * The notebook bar for a mistake's exam paper: the same bar, dock buttons and centred tool strip as
 * [EditorTopBar], folding the same way on narrow panes. A sheet is one page with no layers, so the
 * left side holds the answer space (a lines stepper), picture sizing and fit instead of pages, find,
 * layers and add page; the right holds the clock and share. The review's own bar above keeps the
 * menu, so this one has none.
 */
@Composable internal fun ExamTopBar(
    mainTools: @Composable () -> Unit,
    timer: @Composable () -> Unit,
    lines: Int,
    onMoreLines: () -> Unit,
    onFewerLines: (() -> Unit)?,
    resizing: Boolean,
    onResize: (() -> Unit)?,
    onFit: () -> Unit,
    onShare: () -> Unit,
    onShareQuestion: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val timerWidth = remember { mutableStateOf(0.dp) }
        MeasureNaturalWidth(timerWidth) { timer() }
        val left = DockButtonWidth * 2 + 72.dp + DockGap + dockWidth(1 + if (onResize != null) 1 else 0)
        val right = timerWidth.value + DockGap + dockWidth(1)
        val side = maxOf(left, right)
        val stacked = maxWidth - BarInset * 2 < ToolsNeed + side * 2 + BarGap * 2
        EditorBar(tabs = null, activeId = null, onHome = {}, onSelectTab = {}, onCloseTab = {}, onNewTab = null) {
            BarRows(stacked, side, tools = mainTools, left = { DockRow {
                // Answer space reads as a count with its own − / +, not as two loose zoom-like buttons.
                Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DockButton(FolioIcons.Minus, "Remove answer lines", { onFewerLines?.invoke() }, enabled = onFewerLines != null)
                        Row(Modifier.widthIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4, Alignment.CenterHorizontally)) {
                            Icon(FolioIcons.Lines, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$lines lines", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                        DockButton(FolioIcons.Plus, "Add answer lines", onMoreLines)
                    }
                }
                if (onResize != null) DockButton(FolioIcons.Resize, if (resizing) "Done resizing pictures" else "Resize pictures", onResize,
                    tint = if (resizing) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                DockButton(FolioIcons.Fit, "Fit the page to the screen", onFit)
            } }, right = { DockRow {
                timer()
                DockButton(FolioIcons.Share, "Share a picture of your answer · hold to share the question text", onShare, onLongClick = onShareQuestion)
            } })
        }
    }
}

/** Shared dock button; reserve the full Material touch width in the row budget. */
@Composable private fun DockButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = LocalContentColor.current, onLongClick: (() -> Unit)? = null, enabled: Boolean = true) {
    val hold = rememberLongPressGuard()
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
        // The tooltip's own long-press would swallow the hold, so a button with a hold action gives it up.
        enableUserInput = onLongClick == null) {
        // The hold and the tap share one guard so a long-press never also fires the tap on release.
        IconButton(hold.click(onClick), enabled = enabled, modifier = Modifier.width(DockButtonWidth).height(DockButtonHeight).then(if (onLongClick != null) Modifier.longPressAction(hold, onLongClick) else Modifier)) {
            Icon(icon, label, Modifier.size(22.dp), tint = tint)
        }
    }
}

/** Composes [content] once, unconstrained and invisible, and reports how wide it wants to be. */
@Composable private fun MeasureNaturalWidth(width: MutableState<Dp>, content: @Composable () -> Unit) {
    SubcomposeLayout(Modifier) {
        val natural = (subcompose("natural", content).maxOfOrNull { it.measure(Constraints()).width } ?: 0).toDp()
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
            saveFailed && !retryingSave -> FolioIcons.Alert
            saving -> FolioIcons.Timer
            else -> FolioIcons.Check
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
