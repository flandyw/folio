@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

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
    onStyle: () -> Unit, onDelete: () -> Unit, onDeselect: () -> Unit, onSelectAll: () -> Unit
) {
    var overflow by remember { mutableStateOf(false) }
    val showCopy = availableWidth >= 104.dp
    val showDelete = availableWidth >= 152.dp
    val showStyle = canRestyle && availableWidth >= 200.dp
    val showDuplicate = availableWidth >= 248.dp
    fun run(action: () -> Unit) { overflow = false; action() }
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.guardUiTouches().semanticsLabel("Selection options")) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showCopy) SelectionAction(Icons.Rounded.ContentCopy, "Copy selection") { run(onCopy) }
            if (showStyle) SelectionAction(Icons.Rounded.Palette, "Style selection") { run(onStyle) }
            if (showDuplicate) SelectionAction(Icons.Rounded.DynamicFeed, "Duplicate selection") { run(onDuplicate) }
            if (showDelete) SelectionAction(Icons.Rounded.DeleteOutline, "Delete selection", destructive = true) { run(onDelete) }
            SelectionAction(Icons.Rounded.MoreHoriz, "More selection options") { overflow = !overflow }
        }
        // Inline, not a DropdownMenu: a dropdown inside this non-focusable popup is placed against the wrong window.
        if (overflow) Column(Modifier.width(minOf(200.dp, availableWidth - 8.dp)).padding(bottom = 4.dp)) {
            if (!showCopy) PageMenuRow(Icons.Rounded.ContentCopy, "Copy", true) { run(onCopy) }
            PageMenuRow(Icons.Rounded.ContentCut, "Cut", true) { run(onCut) }
            PageMenuRow(Icons.Rounded.ContentPaste, "Paste", canPaste) { run(onPaste) }
            PageMenuRow(Icons.AutoMirrored.Rounded.DriveFileMove, "Move to page…", canMove) { run(onMove) }
            if (!showDuplicate) PageMenuRow(Icons.Rounded.DynamicFeed, "Duplicate", true) { run(onDuplicate) }
            if (canRestyle && !showStyle) PageMenuRow(Icons.Rounded.Palette, "Style", true) { run(onStyle) }
            if (!showDelete) PageMenuRow(Icons.Rounded.DeleteOutline, "Delete", true) { run(onDelete) }
            HorizontalDivider()
            PageMenuRow(Icons.Rounded.SelectAll, "Select all", true) { run(onSelectAll) }
            PageMenuRow(Icons.Rounded.Close, "Deselect", true) { run(onDeselect) }
        }
        }
    }
}

@Composable private fun SelectionAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.iconButtonColors(contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {
            Icon(icon, label, Modifier.size(20.dp))
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
    val showLeft = availableWidth >= 152.dp
    val showRight = availableWidth >= 200.dp
    val showCrop = availableWidth >= 104.dp
    val showDelete = availableWidth >= 248.dp
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.guardUiTouches().semanticsLabel("Picture options")) {
        Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showLeft) SelectionAction(Icons.AutoMirrored.Rounded.RotateLeft, "Rotate left", onClick = onRotateLeft)
                if (showRight) SelectionAction(Icons.AutoMirrored.Rounded.RotateRight, "Rotate right", onClick = onRotateRight)
                if (showCrop) SelectionAction(Icons.Rounded.Crop, "Crop", onClick = onCrop)
                if (showDelete) SelectionAction(Icons.Rounded.DeleteOutline, "Remove picture", destructive = true, onClick = onDelete)
                SelectionAction(Icons.Rounded.MoreHoriz, "More picture options") { more = !more }
            }
            if (more) Column(Modifier.width(minOf(200.dp, availableWidth - 8.dp))) {
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.guardUiTouches().semanticsLabel("Crop options")) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionAction(Icons.Rounded.Close, "Cancel crop", onClick = onCancel)
            SelectionAction(Icons.Rounded.Check, "Apply crop", onClick = onApply)
        }
    }
}

/** Finger long-press menu on the page itself; opens above the press, or below when there is no room. */
@Composable internal fun PageContextMenu(
    windowX: Float, windowY: Float, onPaste: () -> Unit, onSelectAll: () -> Unit, onText: () -> Unit, onImage: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, onDismiss: () -> Unit,
    canPaste: Boolean = true
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
    fun run(action: () -> Unit) { onDismiss(); action() }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.width(200.dp).semanticsLabel("Page options")) {
            Column(Modifier.padding(vertical = 4.dp)) {
                PageMenuRow(Icons.Rounded.ContentPaste, "Paste", canPaste) { run(onPaste) }
                PageMenuRow(Icons.Rounded.TextFields, "Add text here", true) { run(onText) }
                PageMenuRow(Icons.Rounded.Image, "Insert image", true) { run(onImage) }
                PageMenuRow(Icons.Rounded.SelectAll, "Select all", true) { run(onSelectAll) }
                HorizontalDivider()
                PageMenuRow(Icons.AutoMirrored.Rounded.Undo, "Undo", canUndo) { run(onUndo) }
                PageMenuRow(Icons.AutoMirrored.Rounded.Redo, "Redo", canRedo) { run(onRedo) }
            }
        }
    }
}

@Composable private fun PageMenuRow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** Sticky note actions, in the same nonmodal icon bar as a lasso selection. Tapping away dismisses it. */
@Composable internal fun StickyNoteContextMenu(
    typing: Boolean, onType: () -> Unit, onDone: () -> Unit, onDraw: () -> Unit, onDelete: () -> Unit
) {
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.guardUiTouches().semanticsLabel("Sticky note options")) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (typing) SelectionAction(Icons.Rounded.Check, "Finish typing", onClick = onDone)
            else SelectionAction(Icons.Rounded.TextFields, "Type in sticky note", onClick = onType)
            SelectionAction(Icons.Rounded.Edit, "Draw in sticky note", onClick = onDraw)
            SelectionAction(Icons.Rounded.DeleteOutline, "Delete sticky note", destructive = true, onClick = onDelete)
        }
    }
}
