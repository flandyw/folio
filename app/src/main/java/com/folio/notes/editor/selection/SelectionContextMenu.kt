@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.editor.selection

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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

/** One touch target in the icon bars; every bar below sizes its overflow thresholds from this. */
private val BarButton: Dp @Composable get() = if (largeEditorControls()) 48.dp else 36.dp
@Composable private fun barWidth(buttons: Int): Dp = BarButton * buttons + 8.dp

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

/** Fixed touch targets and icon-only primary actions; secondary actions stay in one overflow menu. */
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
) {
    var overflow by remember { mutableStateOf(false) }
    val showCopy = availableWidth >= barWidth(2)
    val showDelete = availableWidth >= barWidth(3)
    val showStyle = canRestyle && availableWidth >= barWidth(4)
    val showDuplicate = availableWidth >= barWidth(5)
    fun run(action: () -> Unit) { overflow = false; action() }
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.guardUiTouches().semanticsLabel("Selection options")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
            if (showCopy) SelectionAction(Icons.Rounded.ContentCopy, "Copy selection") { run(onCopy) }
            if (showStyle) SelectionAction(Icons.Rounded.Palette, "Style selection") { run(onStyle) }
            if (showDuplicate) SelectionAction(Icons.Rounded.DynamicFeed, "Duplicate selection") { run(onDuplicate) }
            if (showDelete) SelectionAction(Icons.Rounded.DeleteOutline, "Delete selection", destructive = true) { run(onDelete) }
            SelectionAction(Icons.Rounded.MoreHoriz, "More selection options") { overflow = !overflow }
        }
        // Inline, not a DropdownMenu: a dropdown inside this non-focusable popup is placed against the wrong window.
        if (overflow) Column(Modifier.width(minOf(184.dp, availableWidth - 8.dp)).padding(bottom = FolioSpacing.dp4)) {
            if (!showCopy) PageMenuRow(Icons.Rounded.ContentCopy, "Copy", true) { run(onCopy) }
            PageMenuRow(Icons.Rounded.ContentCut, "Cut", true) { run(onCut) }
            PageMenuRow(Icons.Rounded.ContentPaste, "Paste", canPaste) { run(onPaste) }
            PageMenuRow(Icons.AutoMirrored.Rounded.DriveFileMove, "Move to page…", canMove) { run(onMove) }
            if (!showDuplicate) PageMenuRow(Icons.Rounded.DynamicFeed, "Duplicate", true) { run(onDuplicate) }
            if (canRestyle && !showStyle) PageMenuRow(Icons.Rounded.Palette, "Style", true) { run(onStyle) }
            if (onTidyShapes != null) PageMenuRow(Icons.Rounded.AutoFixHigh, "Tidy up shapes…", true) { run(onTidyShapes) }
            if (onTidyHandwriting != null) PageMenuRow(Icons.Rounded.Gesture, "Tidy up handwriting…", true) { run(onTidyHandwriting) }
            if (onConvertText != null) PageMenuRow(Icons.Rounded.TextFields, "Convert handwriting to text", true) { run(onConvertText) }
            if (!showDelete) PageMenuRow(Icons.Rounded.DeleteOutline, "Delete", true) { run(onDelete) }
            if (onPlacement != null) PageMenuRow(Icons.AutoMirrored.Rounded.AlignHorizontalLeft, "Align and distribute…", true) { run(onPlacement) }
            if (onSaveSnippet != null) PageMenuRow(Icons.Rounded.Bookmarks, "Save as reusable snippet…", true) { run(onSaveSnippet) }
            HorizontalDivider()
            PageMenuRow(Icons.Rounded.SelectAll, "Select all", true) { run(onSelectAll) }
            PageMenuRow(Icons.Rounded.Close, "Deselect", true) { run(onDeselect) }
        }
        }
    }
}

@Composable internal fun SelectionAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, modifier = Modifier.size(BarButton), shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.iconButtonColors(contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {
            Icon(icon, label, Modifier.size(18.dp))
        }
    }
}

/** The picture's actions as one icon row, shown beside the picture instead of in a blocking panel. */
@Composable internal fun PictureContextMenu(
    cropped: Boolean, availableWidth: Dp = 300.dp, onRotateLeft: () -> Unit, onRotateRight: () -> Unit, onCrop: () -> Unit,
    onFullPhoto: () -> Unit, onFront: () -> Unit, onBack: () -> Unit, onDelete: () -> Unit
) {
    // Extra actions expand inline: a dropdown inside this non-focusable popup is placed against the wrong window.
    var more by remember { mutableStateOf(false) }
    val showLeft = availableWidth >= barWidth(3)
    val showRight = availableWidth >= barWidth(4)
    val showCrop = availableWidth >= barWidth(2)
    val showDelete = availableWidth >= barWidth(5)
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.guardUiTouches().semanticsLabel("Picture options")) {
        Column(Modifier.padding(horizontal = FolioSpacing.dp4), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showLeft) SelectionAction(Icons.AutoMirrored.Rounded.RotateLeft, "Rotate left", onClick = onRotateLeft)
                if (showRight) SelectionAction(Icons.AutoMirrored.Rounded.RotateRight, "Rotate right", onClick = onRotateRight)
                if (showCrop) SelectionAction(Icons.Rounded.Crop, "Crop", onClick = onCrop)
                if (showDelete) SelectionAction(Icons.Rounded.DeleteOutline, "Remove picture", destructive = true, onClick = onDelete)
                SelectionAction(Icons.Rounded.MoreHoriz, "More picture options") { more = !more }
            }
            if (more) Column(Modifier.width(minOf(184.dp, availableWidth - 8.dp))) {
                fun run(action: () -> Unit) { more = false; action() }
                if (!showLeft) PageMenuRow(Icons.AutoMirrored.Rounded.RotateLeft, "Rotate left", true) { run(onRotateLeft) }
                if (!showRight) PageMenuRow(Icons.AutoMirrored.Rounded.RotateRight, "Rotate right", true) { run(onRotateRight) }
                if (!showCrop) PageMenuRow(Icons.Rounded.Crop, "Crop", true) { run(onCrop) }
                if (!showDelete) PageMenuRow(Icons.Rounded.DeleteOutline, "Remove picture", true) { run(onDelete) }
                if (cropped) PageMenuRow(Icons.Rounded.RestartAlt, "Show full photo", true) { run(onFullPhoto) }
                PageMenuRow(Icons.Rounded.FlipToFront, "Bring to front", true) { run(onFront) }
                PageMenuRow(Icons.Rounded.FlipToBack, "Send to back", true) { run(onBack) }
            }
        }
    }
}

/** Shown while a picture is being cropped on the page: the frame is dragged in place, this ends it. */
@Composable internal fun CropContextMenu(onApply: () -> Unit, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.guardUiTouches().semanticsLabel("Crop options")) {
        Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
            SelectionAction(Icons.Rounded.Close, "Cancel crop", onClick = onCancel)
            SelectionAction(Icons.Rounded.Check, "Apply crop", onClick = onApply)
        }
    }
}

/** Places a long-press menu above the press, or below when there is no room. */
@Composable private fun rememberPressMenuPosition(windowX: Float, windowY: Float): PopupPositionProvider {
    val gap = with(LocalDensity.current) { 16.dp.toPx() }
    return remember(windowX, windowY, gap) {
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
}

/** The page commands that used to live behind the page's three-dot button, plus the position-based ones. */
internal class PageMenuActions(
    val page: NotePage, val canMoveUp: Boolean, val canMoveDown: Boolean, val canDelete: Boolean,
    val onName: () -> Unit, val onBookmark: () -> Unit, val onRedoFlag: () -> Unit, val onMoveTo: () -> Unit,
    val onMoveUp: () -> Unit, val onMoveDown: () -> Unit, val onInsert: () -> Unit, val onDuplicate: () -> Unit,
    val onDelete: () -> Unit
)

/**
 * Finger long-press popover on the page itself: what can be placed or edited at the press, then everything
 * that can be done to the page. Opens above the press, or below when there is no room.
 */
@Composable internal fun PageContextMenu(
    windowX: Float, windowY: Float, onPaste: () -> Unit, onSelectAll: () -> Unit, onText: () -> Unit, onImage: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onDismiss: () -> Unit,
    pageActions: PageMenuActions, canPaste: Boolean = true
) {
    val provider = rememberPressMenuPosition(windowX, windowY)
    fun run(action: () -> Unit) { onDismiss(); action() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, folioSpring(), label = "pagePopover")
    val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.7f).dp
    val page = pageActions.page
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        FolioPopoverSurface(Modifier.width(264.dp).heightIn(max = maxHeight).semanticsLabel("Page options"), progress) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    PageTile(Icons.Rounded.ContentPaste, "Paste", Modifier.weight(1f), enabled = canPaste) { run(onPaste) }
                    PageTile(Icons.Rounded.TextFields, "Text", Modifier.weight(1f), description = "Add text here") { run(onText) }
                    PageTile(Icons.Rounded.Image, "Picture", Modifier.weight(1f), description = "Insert image") { run(onImage) }
                    PageTile(Icons.Rounded.SelectAll, "Select", Modifier.weight(1f), description = "Select all") { run(onSelectAll) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    PageTile(Icons.AutoMirrored.Rounded.Undo, "Undo", Modifier.weight(1f), enabled = canUndo) { run(onUndo) }
                    PageTile(Icons.AutoMirrored.Rounded.Redo, "Redo", Modifier.weight(1f), enabled = canRedo) { run(onRedo) }
                    PageTile(if (page.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Bookmark", Modifier.weight(1f),
                        active = page.bookmarked, description = if (page.bookmarked) "Remove bookmark" else "Bookmark page") { run(pageActions.onBookmark) }
                    PageTile(if (page.redoFlag) Icons.Rounded.Refresh else Icons.Rounded.OutlinedFlag, "Flag", Modifier.weight(1f),
                        active = page.redoFlag, description = if (page.redoFlag) "Remove redo flag" else "Flag this page to redo") { run(pageActions.onRedoFlag) }
                }
                HorizontalDivider(Modifier.padding(vertical = FolioSpacing.dp2))
                PageMenuRow(Icons.Rounded.Edit, "Rename page", true) { run(pageActions.onName) }
                PageMenuRow(Icons.Rounded.LowPriority, "Move to position…", true) { run(pageActions.onMoveTo) }
                Row {
                    PageMenuRow(Icons.Rounded.KeyboardArrowUp, "Move up", pageActions.canMoveUp, modifier = Modifier.weight(1f)) { run(pageActions.onMoveUp) }
                    PageMenuRow(Icons.Rounded.KeyboardArrowDown, "Move down", pageActions.canMoveDown, modifier = Modifier.weight(1f)) { run(pageActions.onMoveDown) }
                }
                PageMenuRow(Icons.Rounded.Add, "Insert blank page after", true) { run(pageActions.onInsert) }
                PageMenuRow(Icons.Rounded.ContentCopy, "Duplicate page", true) { run(pageActions.onDuplicate) }
                HorizontalDivider(Modifier.padding(vertical = FolioSpacing.dp2))
                PageMenuRow(Icons.Rounded.DeleteOutline, "Delete page", pageActions.canDelete, destructive = true) { run(pageActions.onDelete) }
            }
        }
    }
}

/** Long-press menu on a typed text box: edit, clipboard, duplicate and delete without opening the editor first. */
@Composable internal fun TextBoxContextMenu(
    windowX: Float, windowY: Float, onEdit: () -> Unit, onCopy: () -> Unit, onCut: () -> Unit,
    onDuplicate: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit
) {
    val provider = rememberPressMenuPosition(windowX, windowY)
    fun run(action: () -> Unit) { onDismiss(); action() }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.width(172.dp).semanticsLabel("Text options")) {
            Column(Modifier.padding(vertical = FolioSpacing.dp4)) {
                PageMenuRow(Icons.Rounded.Edit, "Edit text", true) { run(onEdit) }
                PageMenuRow(Icons.Rounded.ContentCopy, "Copy", true) { run(onCopy) }
                PageMenuRow(Icons.Rounded.ContentCut, "Cut", true) { run(onCut) }
                PageMenuRow(Icons.Rounded.DynamicFeed, "Duplicate", true) { run(onDuplicate) }
                HorizontalDivider()
                PageMenuRow(Icons.Rounded.DeleteOutline, "Delete", true, destructive = true) { run(onDelete) }
            }
        }
    }
}

@Composable private fun PageMenuRow(icon: ImageVector, label: String, enabled: Boolean, destructive: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val tint = if (enabled) (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A small icon-over-label button for the handful of everyday page actions at the top of the page popover. */
@Composable private fun PageTile(icon: ImageVector, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, active: Boolean = false, description: String = label, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick, modifier.semanticsLabel(description), enabled = enabled, shape = FolioShapes.medium,
        color = if (active) scheme.primaryContainer else scheme.surfaceContainerHighest,
        contentColor = (if (active) scheme.onPrimaryContainer else scheme.onSurface).let { if (enabled) it else it.copy(alpha = 0.38f) }) {
        Column(Modifier.padding(vertical = FolioSpacing.dp6, horizontal = FolioSpacing.dp2).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Sticky note actions, in the same nonmodal icon bar as a lasso selection. Tapping away dismisses it.
 * The paper colours open inline under the bar (a dropdown inside this non-focusable popup would be
 * placed against the wrong window). A null [onDraw] leaves Draw out, e.g. when a pen is already in hand.
 */
@Composable internal fun StickyNoteContextMenu(
    typing: Boolean, paper: Int, onType: () -> Unit, onDone: () -> Unit, onDraw: (() -> Unit)?, onDelete: () -> Unit,
    onPaper: (Int) -> Unit, onDuplicate: () -> Unit, onFront: () -> Unit, onClearInk: (() -> Unit)?,
    textSize: Float, onTextSize: (Float) -> Unit
) {
    var colours by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.guardUiTouches().semanticsLabel("Sticky note options")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                if (typing) SelectionAction(Icons.Rounded.Check, "Finish typing", onClick = onDone)
                else SelectionAction(Icons.Rounded.TextFields, "Type in sticky note", onClick = onType)
                if (onDraw != null) SelectionAction(Icons.Rounded.Edit, "Draw in sticky note", onClick = onDraw)
                SelectionAction(Icons.Rounded.Palette, if (colours) "Hide colours" else "Sticky note colour") { colours = !colours; sizing = false }
                SelectionAction(Icons.Rounded.FormatSize, if (sizing) "Hide text size" else "Text size") { sizing = !sizing; colours = false }
                SelectionAction(Icons.Rounded.ContentCopy, "Duplicate sticky note", onClick = onDuplicate)
                SelectionAction(Icons.Rounded.FlipToFront, "Bring to front", onClick = onFront)
                if (onClearInk != null) SelectionAction(Icons.Rounded.LayersClear, "Clear sticky note drawing", onClick = onClearInk)
                SelectionAction(Icons.Rounded.DeleteOutline, "Delete sticky note", destructive = true, onClick = onDelete)
            }
            if (colours) Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp12, bottom = FolioSpacing.dp8),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                StickyNotes.papers.forEachIndexed { index, value ->
                    val selected = StickyNotes.fill(value) == StickyNotes.fill(paper)
                    Surface(onClick = { onPaper(value) }, shape = androidx.compose.foundation.shape.CircleShape,
                        color = androidx.compose.ui.graphics.Color(StickyNotes.fill(value)),
                        border = BorderStroke(if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(32.dp).semanticsLabel("Paper colour ${index + 1}" + if (selected) ", selected" else "")) {
                        if (selected) Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color(0xFF2B3440))
                        }
                    }
                }
            }
            if (sizing) StickyTextSizeRow(textSize, onTextSize)
        }
    }
}

/**
 * The note's own type size. The thumb previews while it is dragged and the size is written once on
 * release, so one drag is one undo step rather than one per position.
 */
@Composable private fun StickyTextSizeRow(size: Float, onSize: (Float) -> Unit) {
    var shown by remember(size) { mutableStateOf(size) }
    Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp12, bottom = FolioSpacing.dp8).width(260.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Slider(shown, { shown = it }, valueRange = StickyNotes.TEXT_SIZE_MIN..StickyNotes.TEXT_SIZE_MAX,
            onValueChangeFinished = { onSize(shown) }, modifier = Modifier.weight(1f).semanticsLabel("Sticky note text size"))
        Text("${shown.roundToInt()} pt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
