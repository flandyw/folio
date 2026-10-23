@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.notes.input.FolioTouch

/**
 * The draggable split between two panes, shared by the workspace editor/companion split and
 * the mistake question/editor split so both resize with the same gesture.
 *
 * Drag resizes, a release settles on the caller's snap point, double-tap restores the caller's
 * default, and the optional tap and long-press actions are only offered when supplied — the
 * mistake split has nothing to flip or configure, so it stays a plain handle.
 *
 * [vertical] means the panes sit side by side (drag along x); otherwise they are stacked.
 */
@Composable
fun SplitDivider(
    vertical: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    onDoubleTap: () -> Unit,
    contentDescription: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    thickness: Dp = FolioTouch.target,
    /** How wide the grab area is; it may exceed [thickness] and overlap the panes. */
    touch: Dp = FolioTouch.target,
) {
    val drag = rememberDraggableState(onDrag)
    val interaction = remember { MutableInteractionSource() }
    // The pill answers the finger instead of a ripple spreading over both panes' edges.
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val held = dragged || pressed
    val gripLength by animateDpAsState(if (held) 64.dp else 40.dp, label = "dividerGrip")
    val gripColor by animateColorAsState(if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "dividerGripColor")
    // A slim divider reserves less space, but its gesture target still extends over the
    // adjoining pane edges. Keep it above those panes so the whole target receives drags.
    Box(
        contentAlignment = Alignment.Center,
        modifier = (if (vertical) Modifier.width(thickness).fillMaxHeight() else Modifier.height(thickness).fillMaxWidth())
            .zIndex(1f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = (if (vertical) Modifier.requiredWidth(touch).fillMaxHeight() else Modifier.requiredHeight(touch).fillMaxWidth())
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick ?: {},
                onDoubleClick = onDoubleTap,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Pane options" else null,
            )
            .semantics { this.contentDescription = contentDescription }
            .draggable(drag, if (vertical) Orientation.Horizontal else Orientation.Vertical, interactionSource = interaction, onDragStopped = { onRelease() })
        ) {
            // Never wider than the bar itself, so the pill sits exactly on the seam.
            val grip = minOf(if (thickness < FolioTouch.target) 3.dp else 4.dp, thickness - 2.dp).coerceAtLeast(2.dp)
            Box(
                Modifier.then(if (vertical) Modifier.width(grip).height(gripLength) else Modifier.height(grip).width(gripLength))
                    .background(gripColor, CircleShape)
            )
        }
    }
}
