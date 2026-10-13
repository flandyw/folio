@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import androidx.compose.ui.input.pointer.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable internal fun MarkChip(
    zone: MarkZone, rect: android.graphics.RectF, page: NotePage, stamp: Long,
    onAward: (Int) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit, onEdit: (() -> Unit)? = null
) {
    val existing = remember(zone, page.texts) { MarkZones.awarded(zone.canonical(), page)?.roundToInt() }
    var adjusting by remember(zone) { mutableStateOf(false) }
    var value by remember(zone) { mutableIntStateOf(existing ?: 0) }
    // Every hover or tap restarts the clock; a chip being adjusted is given longer.
    LaunchedEffect(stamp, adjusting, value) { delay(if (adjusting) 10_000L else 3_000L); onDismiss() }
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(LocalDensity.current) { 8.dp.toPx() }
        Surface(
            Modifier.onSizeChanged { size = it }.pointerInput(onDismiss) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type == PointerEventType.Exit) onDismiss()
                    }
                }
            }.offset {
                val x = (rect.centerX() - size.width / 2f).coerceIn(0f, (maxWidthPx - size.width).coerceAtLeast(0f))
                val above = rect.top - size.height - gap
                IntOffset(x.roundToInt(), (if (above >= 0f) above else rect.bottom + gap).roundToInt())
            },
            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                if (!adjusting) {
                    Text(if (existing != null) "+$existing / ${zone.marks}" else "${zone.marks} ${if (zone.marks == 1) "mark" else "marks"}",
                        Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4), style = MaterialTheme.typography.labelLarge)
                    IconButton({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAward(zone.marks) }, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Check, "Award all ${zone.marks}", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton({ adjusting = true }, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Close, "Award fewer marks", tint = MaterialTheme.colorScheme.error)
                    }
                    if (existing != null) IconButton(onClear, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Delete, "Remove this mark")
                    }
                    if (onEdit != null) IconButton(onEdit, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Edit, "Change this area's marks or remove it")
                    }
                } else {
                    IconButton({ value = (value - 1).coerceAtLeast(0) }, Modifier.size(44.dp), enabled = value > 0) { Icon(Icons.Rounded.Remove, "One fewer mark") }
                    Text("$value / ${zone.marks}", style = MaterialTheme.typography.titleSmall)
                    IconButton({ value = (value + 1).coerceAtMost(zone.marks) }, Modifier.size(44.dp), enabled = value < zone.marks) { Icon(Icons.Rounded.Add, "One more mark") }
                    IconButton({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAward(value) }, Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Check, "Confirm $value marks", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** A boxed allocation awaiting its value: [existing] is the hand-drawn zone being edited, null for a new one. */
internal data class AreaEntry(val existing: MarkZone?, val x: Float, val y: Float, val width: Float, val height: Float, val rect: android.graphics.RectF, val parts: List<MarkZonePart> = emptyList())

/**
 * Asks how many marks the label the marker just boxed is worth. It sits beside the rectangle, stays until
 * answered or dismissed (it is a decision, not a hint), and a tick confirms — the box then behaves exactly
 * like an allocation the scan had found.
 */
@Composable internal fun MarkAreaPopover(
    rect: android.graphics.RectF, initial: Int?, guess: Int?, pageCount: Int = 1, onConfirm: (Int) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit
) {
    var value by remember { mutableIntStateOf(initial ?: 1) }
    var touched by remember { mutableStateOf(initial != null) }
    LaunchedEffect(guess) { if (guess != null && !touched) value = guess }
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(LocalDensity.current) { 8.dp.toPx() }
        Surface(
            Modifier.onSizeChanged { size = it }.offset {
                val x = (rect.centerX() - size.width / 2f).coerceIn(0f, (maxWidthPx - size.width).coerceAtLeast(0f))
                val above = rect.top - size.height - gap
                IntOffset(x.roundToInt(), (if (above >= 0f) above else rect.bottom + gap).roundToInt())
            },
            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                Text(if (pageCount > 1) "Marks · $pageCount pages" else "Marks", Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp2), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ touched = true; value = (value - 1).coerceAtLeast(1) }, Modifier.size(44.dp), enabled = value > 1) { Icon(Icons.Rounded.Remove, "One fewer mark") }
                Text("$value", Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                IconButton({ touched = true; value = (value + 1).coerceAtMost(MarkZones.MAX_MARKS) }, Modifier.size(44.dp), enabled = value < MarkZones.MAX_MARKS) { Icon(Icons.Rounded.Add, "One more mark") }
                IconButton({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onConfirm(value) }, Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Check, "Set this area to $value ${if (value == 1) "mark" else "marks"}", tint = MaterialTheme.colorScheme.primary)
                }
                if (onRemove != null) IconButton(onRemove, Modifier.size(44.dp)) { Icon(Icons.Rounded.Delete, "Remove this area") }
                IconButton(onDismiss, Modifier.size(44.dp)) { Icon(Icons.Rounded.Close, "Cancel") }
            }
        }
    }
}

/** Says what the mark-area tool wants while it is selected; non-interactive, so it never steals a drag. */
@Composable internal fun MarkAreaHint(modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Text("Drag a box around a “[n marks]” label the scan missed", Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10),
            style = MaterialTheme.typography.labelLarge)
    }
}

