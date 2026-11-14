@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.editor.selection

import com.folio.notes.ui.FolioIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.folio.notes.editor.sticky.StickyNotes
import com.folio.notes.input.guardUiTouches
import com.folio.notes.library.semanticsLabel
import com.folio.notes.model.NotePage
import com.folio.notes.settings.largeEditorControls
import com.folio.notes.ui.FolioPopoverSurface
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.folioSpring
import kotlin.math.roundToInt

/**
 * One command of a context menu. Every menu on the page is a list of these, drawn either as a
 * [ContextBar] beside what it acts on or as a [PressMenu] at a long-press. [active] lights a toggle.
 */
internal class MenuAction(
    val icon: ImageVector, val label: String, val enabled: Boolean = true,
    val destructive: Boolean = false, val active: Boolean = false, val onClick: () -> Unit
)

/** One touch target in a context bar; the bar budgets its buttons from this. */
private val BarButton: Dp @Composable get() = if (largeEditorControls()) 48.dp else 40.dp

private fun Rect.menuRect() = SelectionMenuRect(left, top, right, bottom)

/** A nonmodal popup: only the bar receives input, so canvas taps and stylus gestures still work. */
@Composable internal fun SelectionContextPopup(
    anchor: Rect?, pageFrame: Rect?, viewport: Rect?, tightGap: Boolean = false, content: @Composable (Dp) -> Unit
) {
    if (anchor == null || pageFrame == null) return
    val visibleViewport = viewport ?: pageFrame
    val selection = SelectionMenuRect(
        pageFrame.left + anchor.left * pageFrame.width, pageFrame.top + anchor.top * pageFrame.height,
        pageFrame.left + anchor.right * pageFrame.width, pageFrame.top + anchor.bottom * pageFrame.height)
    if (selection.intersect(visibleViewport.menuRect()) == null) return
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.toPx() }
    val aboveGap = with(density) { (if (tightGap) 12.dp else 48.dp).toPx() }
    val belowGap = with(density) { 16.dp.toPx() }
    val availableWidth = with(density) { (visibleViewport.width - margin * 2).coerceAtLeast(0f).toDp() }
    if (availableWidth < 56.dp || with(density) { visibleViewport.height.toDp() } < 64.dp) return
    val provider = remember(selection, visibleViewport, margin, aboveGap, belowGap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val viewportInWindow = visibleViewport.menuRect().intersect(
                    SelectionMenuRect(0f, 0f, windowSize.width.toFloat(), windowSize.height.toFloat()))
                    ?: return IntOffset.Zero
                val position = SelectionMenuGeometry.place(selection, viewportInWindow,
                    popupContentSize.width, popupContentSize.height, margin, aboveGap, belowGap)
                    ?: return IntOffset(viewportInWindow.left.toInt(), viewportInWindow.top.toInt())
                return IntOffset(position.x, position.y)
            }
        }
    }
    Popup(popupPositionProvider = provider,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false, dismissOnBackPress = false)) {
        // A popup that appears while the pen has just lifted can sit undrawn until its window sees input
        // (a hover). A few state-driven frames make it paint on its own.
        var paint by remember(selection) { mutableIntStateOf(0) }
        LaunchedEffect(selection) { repeat(4) { withFrameNanos { }; paint++ } }
        Box(Modifier.widthIn(max = availableWidth).drawBehind { paint }) { content(availableWidth) }
    }
}

/**
 * The floating bar beside a selection, picture or sticky note. [actions] are icon buttons; whatever
 * the pane is too narrow for joins [more] (null is a divider) in a list that opens under the bar,
 * and a destructive action is the last to leave. The list is inline rather than a dropdown, because
 * a dropdown inside this non-focusable popup is placed against the wrong window.
 * [lead] sits before the buttons and [panel] under them (a note's paper colours).
 */
@Composable internal fun ContextBar(
    name: String, actions: List<MenuAction>, more: List<MenuAction?> = emptyList(), availableWidth: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier, lead: (@Composable RowScope.() -> Unit)? = null, panel: (@Composable () -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    val slots = if (availableWidth.isSpecified) ((availableWidth - 8.dp) / BarButton).toInt() else actions.size + 1
    val shown = contextBarButtons(actions, slots, more.isNotEmpty()) { it.destructive }
    val list = (actions - shown.toSet()) + more
    Surface(modifier.guardUiTouches().semanticsLabel(name), shape = FolioShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                lead?.invoke(this)
                shown.forEach { BarAction(it) }
                if (list.isNotEmpty()) BarAction(MenuAction(FolioIcons.More, "More $name".lowercase().replaceFirstChar(Char::uppercase), active = open) { open = !open })
            }
            if (open && list.isNotEmpty()) Column(Modifier.width(if (availableWidth.isSpecified) minOf(208.dp, availableWidth - 8.dp) else 208.dp).padding(bottom = FolioSpacing.dp4)) {
                list.forEach { action ->
                    if (action == null) HorizontalDivider(Modifier.padding(vertical = FolioSpacing.dp2), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    else MenuRow(action) { open = false; action.onClick() }
                }
            }
            panel?.invoke()
        }
    }
}

@Composable private fun BarAction(action: MenuAction) {
    val scheme = MaterialTheme.colorScheme
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(action.label) } }, state = rememberTooltipState()) {
        IconButton(action.onClick, enabled = action.enabled, modifier = Modifier.size(BarButton), shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (action.active) scheme.secondaryContainer else Color.Transparent,
                contentColor = when { action.destructive -> scheme.error; action.active -> scheme.onSecondaryContainer; else -> scheme.onSurface })) {
            Icon(action.icon, action.label, Modifier.size(20.dp))
        }
    }
}

@Composable private fun MenuRow(action: MenuAction, onClick: () -> Unit = action.onClick) {
    val scheme = MaterialTheme.colorScheme
    val tint = when { !action.enabled -> scheme.onSurface.copy(alpha = .38f); action.destructive -> scheme.error; else -> scheme.onSurface }
    Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp4).clip(FolioShapes.medium)
        .clickable(enabled = action.enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = FolioSpacing.dp10),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        Icon(action.icon, null, Modifier.size(20.dp), tint = tint)
        Text(action.label, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** An icon over a word, for the handful of everyday commands at the top of a [PressMenu]. */
@Composable private fun MenuTile(action: MenuAction, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick, modifier.semanticsLabel(action.label), enabled = action.enabled, shape = FolioShapes.large,
        color = if (action.active) scheme.secondaryContainer else scheme.surfaceContainerHighest,
        contentColor = (if (action.active) scheme.onSecondaryContainer else scheme.onSurface).let { if (action.enabled) it else it.copy(alpha = .38f) }) {
        Column(Modifier.padding(vertical = FolioSpacing.dp8, horizontal = FolioSpacing.dp2).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Icon(action.icon, null, Modifier.size(20.dp))
            // The tile shows the first word; the full label is what a screen reader hears.
            Text(action.label.substringBefore(' '), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The menu a finger long-press opens, above the press or below it when there is no room: [tiles]
 * four to a row, then [rows] (null is a divider). Choosing anything closes it.
 */
@Composable internal fun PressMenu(
    name: String, windowX: Float, windowY: Float, onDismiss: () -> Unit,
    tiles: List<MenuAction> = emptyList(), rows: List<MenuAction?>
) {
    val gap = with(LocalDensity.current) { 16.dp.toPx() }
    val provider = remember(windowX, windowY, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (windowX - popupContentSize.width / 2f).coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
                val above = windowY - popupContentSize.height - gap * 2
                val y = if (above >= gap) above else windowY + gap * 2
                return IntOffset(x.toInt(), y.toInt().coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, folioSpring(), label = "pressMenu")
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        FolioPopoverSurface(Modifier.width(if (tiles.isEmpty()) 208.dp else 272.dp).heightIn(max = maxHeight).semanticsLabel(name), progress) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = FolioSpacing.dp8)) {
                tiles.chunked(4).forEach { line ->
                    Row(Modifier.padding(horizontal = FolioSpacing.dp8).padding(bottom = FolioSpacing.dp4), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        line.forEach { tile -> MenuTile(tile, Modifier.weight(1f)) { onDismiss(); tile.onClick() } }
                        repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                rows.forEach { row ->
                    if (row == null) HorizontalDivider(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp4), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    else MenuRow(row) { onDismiss(); row.onClick() }
                }
            }
        }
    }
}

/** The lasso selection's bar: clipboard, style and delete up front, everything else in its list. */
@Composable internal fun SelectionContextMenu(
    availableWidth: Dp, canRestyle: Boolean,
    onCopy: () -> Unit, onCut: () -> Unit, onDuplicate: () -> Unit,
    onMove: () -> Unit, canMove: Boolean, onPaste: () -> Unit, canPaste: Boolean = true,
    onStyle: () -> Unit, onDelete: () -> Unit, onDeselect: () -> Unit, onSelectAll: () -> Unit,
    /** Null hides the row; a selection with no handwriting, or one already converting, passes null. */
    onConvertText: (() -> Unit)? = null,
    onTidyShapes: (() -> Unit)? = null,
    /** Null hides the row; offered when the selection holds enough pen ink to look like writing. */
    onTidyHandwriting: (() -> Unit)? = null,
    onPlacement: (() -> Unit)? = null, onSaveSnippet: (() -> Unit)? = null
) = ContextBar("Selection options", availableWidth = availableWidth,
    actions = listOfNotNull(
        MenuAction(FolioIcons.Copy, "Copy", onClick = onCopy),
        MenuAction(FolioIcons.Duplicate, "Duplicate", onClick = onDuplicate),
        if (canRestyle) MenuAction(FolioIcons.Style, "Colour and width", onClick = onStyle) else null,
        MenuAction(FolioIcons.Delete, "Delete", destructive = true, onClick = onDelete)),
    more = listOfNotNull(
        MenuAction(FolioIcons.Cut, "Cut", onClick = onCut),
        MenuAction(FolioIcons.Paste, "Paste", canPaste, onClick = onPaste),
        MenuAction(FolioIcons.MoveToPage, "Move to page…", canMove, onClick = onMove),
        onPlacement?.let { MenuAction(FolioIcons.Align, "Align and distribute…", onClick = it) },
        onTidyShapes?.let { MenuAction(FolioIcons.Tidy, "Tidy up shapes…", onClick = it) },
        onTidyHandwriting?.let { MenuAction(FolioIcons.Handwriting, "Tidy up handwriting…", onClick = it) },
        onConvertText?.let { MenuAction(FolioIcons.ToText, "Convert handwriting to text", onClick = it) },
        onSaveSnippet?.let { MenuAction(FolioIcons.Snippet, "Save as reusable snippet…", onClick = it) }) +
        listOf(null, MenuAction(FolioIcons.SelectAll, "Select all", onClick = onSelectAll), MenuAction(FolioIcons.Close, "Deselect", onClick = onDeselect)))

/** The picture's actions beside the picture; while cropping, the frame is dragged in place and this ends it. */
@Composable internal fun PictureContextMenu(
    cropping: Boolean, cropped: Boolean, availableWidth: Dp, onRotateLeft: () -> Unit, onRotateRight: () -> Unit, onCrop: () -> Unit,
    onEndCrop: (apply: Boolean) -> Unit, onFullPhoto: () -> Unit, onCopy: () -> Unit, onDuplicate: () -> Unit,
    onFront: () -> Unit, onBack: () -> Unit, onDelete: () -> Unit
) = if (cropping) ContextBar("Crop options", listOf(
        MenuAction(FolioIcons.Close, "Cancel crop") { onEndCrop(false) },
        MenuAction(FolioIcons.Check, "Apply crop") { onEndCrop(true) }), availableWidth = availableWidth)
    else ContextBar("Picture options", availableWidth = availableWidth,
        actions = listOf(
            MenuAction(FolioIcons.Crop, "Crop", onClick = onCrop),
            MenuAction(FolioIcons.RotateLeft, "Rotate left", onClick = onRotateLeft),
            MenuAction(FolioIcons.RotateRight, "Rotate right", onClick = onRotateRight),
            MenuAction(FolioIcons.Delete, "Remove picture", destructive = true, onClick = onDelete)),
        more = listOfNotNull(
            MenuAction(FolioIcons.Copy, "Copy", onClick = onCopy),
            MenuAction(FolioIcons.Duplicate, "Duplicate", onClick = onDuplicate),
            if (cropped) MenuAction(FolioIcons.Expand, "Show full photo", onClick = onFullPhoto) else null,
            MenuAction(FolioIcons.ToFront, "Bring to front", onClick = onFront),
            MenuAction(FolioIcons.ToBack, "Send to back", onClick = onBack)))

/** The page commands that used to live behind the page's three-dot button, plus the position-based ones. */
internal class PageMenuActions(
    val page: NotePage, val canMoveUp: Boolean, val canMoveDown: Boolean, val canDelete: Boolean,
    val onName: () -> Unit, val onBookmark: () -> Unit, val onRedoFlag: () -> Unit, val onMoveTo: () -> Unit,
    val onMoveUp: () -> Unit, val onMoveDown: () -> Unit, val onInsert: () -> Unit, val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    /** Null on a page that keeps its own paper (an imported PDF). */
    val onPaper: (() -> Unit)? = null,
    /** Null when there is nothing on the page to clear. */
    val onClear: (() -> Unit)? = null
)

/** Finger long-press on the page: what can be placed or undone at the press, then everything about the page. */
@Composable internal fun PageContextMenu(
    windowX: Float, windowY: Float, onPaste: () -> Unit, onSelectAll: () -> Unit, onText: () -> Unit, onImage: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onDismiss: () -> Unit,
    pageActions: PageMenuActions, canPaste: Boolean = true
) {
    val page = pageActions.page
    PressMenu("Page options", windowX, windowY, onDismiss,
        tiles = listOf(
            MenuAction(FolioIcons.Paste, "Paste", canPaste, onClick = onPaste),
            MenuAction(FolioIcons.Text, "Text here", onClick = onText),
            MenuAction(FolioIcons.Image, "Picture here", onClick = onImage),
            MenuAction(FolioIcons.SelectAll, "Select all", onClick = onSelectAll),
            MenuAction(FolioIcons.Undo, "Undo", canUndo, onClick = onUndo),
            MenuAction(FolioIcons.Redo, "Redo", canRedo, onClick = onRedo),
            MenuAction(if (page.bookmarked) FolioIcons.BookmarkFilled else FolioIcons.Bookmark,
                if (page.bookmarked) "Bookmark page, on" else "Bookmark page", active = page.bookmarked, onClick = pageActions.onBookmark),
            MenuAction(FolioIcons.Flag, if (page.redoFlag) "Flag to redo, on" else "Flag to redo", active = page.redoFlag, onClick = pageActions.onRedoFlag)),
        rows = listOfNotNull(
            MenuAction(FolioIcons.Rename, "Rename page", onClick = pageActions.onName),
            pageActions.onPaper?.let { MenuAction(FolioIcons.Paper, "Change paper…", onClick = it) },
            null,
            MenuAction(FolioIcons.AddPage, "Insert blank page after", onClick = pageActions.onInsert),
            MenuAction(FolioIcons.Duplicate, "Duplicate page", onClick = pageActions.onDuplicate),
            MenuAction(FolioIcons.Up, "Move up", pageActions.canMoveUp, onClick = pageActions.onMoveUp),
            MenuAction(FolioIcons.Down, "Move down", pageActions.canMoveDown, onClick = pageActions.onMoveDown),
            MenuAction(FolioIcons.Reorder, "Move to position…", onClick = pageActions.onMoveTo)) +
            listOfNotNull(null,
                pageActions.onClear?.let { MenuAction(FolioIcons.ClearPage, "Clear page", destructive = true, onClick = it) },
                MenuAction(FolioIcons.Delete, "Delete page", pageActions.canDelete, destructive = true, onClick = pageActions.onDelete)))
}

/** Long-press menu on a typed text box: edit, clipboard, duplicate and delete without opening the editor first. */
@Composable internal fun TextBoxContextMenu(
    windowX: Float, windowY: Float, onEdit: () -> Unit, onCopy: () -> Unit, onCut: () -> Unit,
    onDuplicate: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit
) = PressMenu("Text options", windowX, windowY, onDismiss, rows = listOf(
    MenuAction(FolioIcons.Rename, "Edit text", onClick = onEdit),
    MenuAction(FolioIcons.Copy, "Copy", onClick = onCopy),
    MenuAction(FolioIcons.Cut, "Cut", onClick = onCut),
    MenuAction(FolioIcons.Duplicate, "Duplicate", onClick = onDuplicate),
    null,
    MenuAction(FolioIcons.Delete, "Delete", destructive = true, onClick = onDelete)))

/**
 * Sticky note actions, in the same nonmodal bar as a lasso selection. Tapping away dismisses it.
 * The paper colours and the type size open under the bar. A null [onDraw] leaves Draw out, e.g.
 * when a pen is already in hand.
 */
@Composable internal fun StickyNoteContextMenu(
    typing: Boolean, paper: Int, onType: () -> Unit, onDone: () -> Unit, onDraw: (() -> Unit)?, onDelete: () -> Unit,
    onPaper: (Int) -> Unit, onDuplicate: () -> Unit, onFront: () -> Unit, onClearInk: (() -> Unit)?,
    textSize: Float, onTextSize: (Float) -> Unit, availableWidth: Dp = Dp.Unspecified
) {
    var colours by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    ContextBar("Sticky note options", availableWidth = availableWidth,
        actions = listOfNotNull(
            if (typing) MenuAction(FolioIcons.Check, "Finish typing", onClick = onDone) else MenuAction(FolioIcons.Text, "Type in sticky note", onClick = onType),
            onDraw?.let { MenuAction(FolioIcons.Pen, "Draw in sticky note", onClick = it) },
            MenuAction(FolioIcons.Style, "Sticky note colour", active = colours) { colours = !colours; sizing = false },
            MenuAction(FolioIcons.TextSize, "Text size", active = sizing) { sizing = !sizing; colours = false },
            MenuAction(FolioIcons.Duplicate, "Duplicate sticky note", onClick = onDuplicate),
            MenuAction(FolioIcons.ToFront, "Bring to front", onClick = onFront),
            onClearInk?.let { MenuAction(FolioIcons.ClearInk, "Clear sticky note drawing", onClick = it) },
            MenuAction(FolioIcons.Delete, "Delete sticky note", destructive = true, onClick = onDelete))) {
        if (colours) Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp12, bottom = FolioSpacing.dp8),
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
            StickyNotes.papers.forEachIndexed { index, value ->
                val selected = StickyNotes.fill(value) == StickyNotes.fill(paper)
                Surface(onClick = { onPaper(value) }, shape = CircleShape, color = Color(StickyNotes.fill(value)),
                    border = BorderStroke(if (selected) 3.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(32.dp).semanticsLabel("Paper colour ${index + 1}" + if (selected) ", selected" else "")) {
                    if (selected) Box(contentAlignment = Alignment.Center) {
                        Icon(FolioIcons.Check, null, Modifier.size(16.dp), tint = Color(0xFF2B3440))
                    }
                }
            }
        }
        // The thumb previews while it is dragged and the size is written once on release, so one
        // drag is one undo step rather than one per position.
        if (sizing) {
            var shown by remember(textSize) { mutableFloatStateOf(textSize) }
            Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp12, bottom = FolioSpacing.dp8).width(260.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Slider(shown, { shown = it }, valueRange = StickyNotes.TEXT_SIZE_MIN..StickyNotes.TEXT_SIZE_MAX,
                    onValueChangeFinished = { onTextSize(shown) }, modifier = Modifier.weight(1f).semanticsLabel("Sticky note text size"))
                Text("${shown.roundToInt()} pt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
