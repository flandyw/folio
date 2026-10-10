package com.folio.notes

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** One thing a list row does when swiped; the same action must also exist as a button or menu item. */
class SwipeAction(val icon: ImageVector, val label: String, val destructive: Boolean = false, val perform: () -> Unit)

/**
 * A list row with swipe shortcuts: [startToEnd] on a right swipe, [endToStart] on a left swipe
 * (either may be null). The row always springs back — an action that needs confirmation opens its
 * own dialog rather than removing the row here, so nothing is lost by a stray swipe.
 */
@Composable fun FolioSwipeRow(
    modifier: Modifier = Modifier, startToEnd: SwipeAction? = null, endToStart: SwipeAction? = null,
    shape: Shape = RoundedCornerShape(20.dp), content: @Composable () -> Unit,
) {
    if (startToEnd == null && endToStart == null) { Box(modifier) { content() }; return }
    val haptics = LocalHapticFeedback.current
    val start by rememberUpdatedState(startToEnd)
    val end by rememberUpdatedState(endToStart)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        val action = when (value) {
            SwipeToDismissBoxValue.StartToEnd -> start
            SwipeToDismissBoxValue.EndToStart -> end
            SwipeToDismissBoxValue.Settled -> null
        }
        action?.let { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it.perform() }
        false
    })
    SwipeToDismissBox(state, modifier = modifier,
        enableDismissFromStartToEnd = startToEnd != null, enableDismissFromEndToStart = endToStart != null,
        backgroundContent = {
            val direction = state.dismissDirection
            val action = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> startToEnd
                SwipeToDismissBoxValue.EndToStart -> endToStart
                SwipeToDismissBoxValue.Settled -> null
            }
            if (action != null) {
                val scheme = MaterialTheme.colorScheme
                val content = if (action.destructive) scheme.onErrorContainer else scheme.onSecondaryContainer
                Box(Modifier.fillMaxSize().clip(shape).background(if (action.destructive) scheme.errorContainer else scheme.secondaryContainer).padding(horizontal = FolioSpacing.dp24),
                    contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                    // Name the action under the finger, not just its icon.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        if (direction == SwipeToDismissBoxValue.EndToStart) Text(action.label, style = MaterialTheme.typography.labelLarge, color = content)
                        Icon(action.icon, null, tint = content)
                        if (direction == SwipeToDismissBoxValue.StartToEnd) Text(action.label, style = MaterialTheme.typography.labelLarge, color = content)
                    }
                }
            }
        }) { content() }
}

/** A labelled button on the strip a [FolioRevealRow] uncovers. */
@Composable fun RevealActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, destructive: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick, modifier.width(RevealActionWidth).fillMaxHeight(), shape = RoundedCornerShape(0.dp),
        color = if (destructive) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (destructive) scheme.onErrorContainer else scheme.onSecondaryContainer) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

val RevealActionWidth = 76.dp

/**
 * A row that slides left to uncover [actionCount] [RevealActionButton]s instead of performing one
 * action. Opening is controlled ([revealed]) so a list can keep a single row open; while open, a
 * tap anywhere on the row closes it rather than reaching the row's own click.
 */
@Composable fun FolioRevealRow(
    revealed: Boolean, onRevealedChange: (Boolean) -> Unit, actionCount: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true, shape: Shape = RoundedCornerShape(20.dp),
    actions: @Composable RowScope.() -> Unit, content: @Composable () -> Unit,
) {
    if (!enabled || actionCount == 0) { Box(modifier) { content() }; return }
    val width = with(LocalDensity.current) { (RevealActionWidth * actionCount).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(revealed, width) { offset.animateTo(if (revealed) -width else 0f, folioSpring()) }
    Box(modifier) {
        if (offset.value < 0f) Row(Modifier.matchParentSize().clip(shape), horizontalArrangement = Arrangement.End) { actions() }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.draggable(
            rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(-width, 0f)) } },
            Orientation.Horizontal,
            onDragStopped = { velocity ->
                val open = if (abs(velocity) > 800f) velocity < 0 else offset.value < -width / 2
                if (open != revealed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onRevealedChange(open)
                offset.animateTo(if (open) -width else 0f, folioSpring())
            })) {
            content()
            if (revealed) Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null, onClickLabel = "Hide actions") { onRevealedChange(false) })
        }
    }
}
