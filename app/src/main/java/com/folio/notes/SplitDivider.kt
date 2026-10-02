@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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
) {
    val drag = rememberDraggableState(onDrag)
    Box(
        contentAlignment = Alignment.Center,
        modifier = (if (vertical) Modifier.width(28.dp).fillMaxHeight() else Modifier.height(28.dp).fillMaxWidth())
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                onClick = onClick ?: {},
                onDoubleClick = onDoubleTap,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Pane options" else null,
            ) else Modifier)
            .semantics { this.contentDescription = contentDescription }
            .draggable(drag, if (vertical) Orientation.Horizontal else Orientation.Vertical, onDragStopped = { onRelease() })
    ) {
        Box(
            Modifier.then(if (vertical) Modifier.width(4.dp).height(48.dp) else Modifier.height(4.dp).width(48.dp))
                .background(MaterialTheme.colorScheme.outlineVariant, FolioShapes.hairline)
        )
    }
}