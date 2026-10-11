package com.folio.notes

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Lazy, keyed sheets share one continuous track; the model changes only on a completed turn. */
@Composable internal fun SinglePageSheet(
    pages: List<NotePage>, index: Int, top: Dp, swipeTurns: Boolean, zoomed: Boolean, haptics: Boolean,
    onSelect: (origin: String, destination: String) -> Unit,
    sheet: @Composable (NotePage, Int, preview: Boolean) -> Unit,
) {
    val pager = rememberPagerState(initialPage = index) { pages.size }
    val scope = rememberCoroutineScope()
    val stylusActivity = LocalStylusActivity.current
    val density = LocalDensity.current.density
    val feedback = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect)
    val ids = pages.map { it.id }
    val selectedId by rememberUpdatedState(ids[index])
    var width by remember { mutableFloatStateOf(1f) }
    var edge by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var programmatic by remember { mutableStateOf(false) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val animation = spring<Float>(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = 1f)

    // A newer button, search, or thumbnail request cancels any unfinished swipe. No delayed
    // relative "next" callback can move a page that was selected in the meantime.
    LaunchedEffect(index, ids) {
        settle?.cancel()
        settle = null
        edge = 0f
        dragging = false
        programmatic = true
        try { pager.animateScrollToPage(index, animationSpec = animation) }
        finally { programmatic = false }
    }

    fun finish(target: Int, origin: String, commit: Boolean) {
        val destination = ids[target]
        settle?.cancel()
        settle = scope.launch {
            val job = currentCoroutineContext()[Job]
            try {
                if (edge != 0f) animate(edge, 0f, animationSpec = animation) { value, _ -> edge = value }
                pager.animateScrollToPage(target, animationSpec = animation)
                // Clear the job before selecting: the selection effect must not cancel this callback.
                settle = null
                dragging = false
                if (commit) {
                    select(origin, destination)
                    if (haptics) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            } finally {
                if (settle === job) { settle = null; dragging = false }
            }
        }
    }

    Box(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = top, bottom = 8.dp)
        .clipToBounds().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(stylusActivity, swipeTurns, zoomed, index, ids) {
            if (!swipeTurns || zoomed) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Touch || down.isConsumed || programmatic) return@awaitEachGesture
                val serial = stylusActivity.cancellationSerial
                val origin = ids[index]
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                val slop = viewConfiguration.touchSlop
                var claimed = false
                var released = false
                var distance = 0f
                var applied = 0f
                var startingDistance = 0f
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (serial != stylusActivity.cancellationSerial || event.changes.size != 1 ||
                            event.changes.any { it.type != PointerType.Touch }) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val delta = change.position - down.position
                        if (!claimed) {
                            if (!change.pressed || change.isConsumed) break
                            if (abs(delta.y) > slop && abs(delta.y) >= abs(delta.x)) break
                            if (abs(delta.x) <= slop || abs(delta.x) < abs(delta.y) * 1.25f) continue
                            // A fresh drag can catch a settling sheet without jumping back to its origin.
                            settle?.cancel()
                            settle = null
                            applied = (pager.currentPage - index + pager.currentPageOffsetFraction) * width
                            startingDistance = if (edge != 0f) PageSwipe.edgeDistance(edge, width) else -applied
                            claimed = true
                            dragging = true
                        }
                        change.consume()
                        // Remove touch slop so the paper never jumps when the gesture is claimed.
                        distance = startingDistance + (abs(delta.x) - slop).coerceAtLeast(0f) * kotlin.math.sign(delta.x)
                        val offset = PageSwipe.offset(distance, width, index, ids.size)
                        val atEdge = (distance > 0f && index == 0) || (distance < 0f && index == ids.lastIndex)
                        edge = if (atEdge) offset else 0f
                        val scroll = if (atEdge) 0f else -offset
                        pager.dispatchRawDelta(scroll - applied)
                        applied = scroll
                        if (!change.pressed) { released = true; break }
                    }
                } finally {
                    if (claimed && selectedId == origin) {
                        val target = if (released && serial == stylusActivity.cancellationSerial)
                            PageSwipe.target(index, ids.size, distance, tracker.calculateVelocity().x, width, density)
                        else index
                        finish(target, origin, target != index)
                    }
                }
            }
        }) {
        HorizontalPager(state = pager, userScrollEnabled = false, beyondViewportPageCount = 1,
            key = { ids[it] }, modifier = Modifier.fillMaxSize().graphicsLayer { translationX = edge }) { at ->
            val preview = at != index || dragging || programmatic || settle?.isActive == true
            Box(Modifier.fillMaxSize().clip(FolioShapes.medium)
                .then(if (preview) Modifier.clearAndSetSemantics {} else Modifier)) {
                sheet(pages[at], at, preview)
            }
        }
    }
}

/** One recognizer owns tap and hold, including cancellation and accessible long-click actions. */
@Composable internal fun PageNavigationArrow(forward: Boolean, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val feedback = LocalHapticFeedback.current
    val label = if (forward) "Next page" else "Previous page"
    val hold = if (forward) "Go to last page" else "Go to first page"
    Box(Modifier.size(48.dp).clip(CircleShape)
        .combinedClickable(enabled = enabled, role = Role.Button, onClickLabel = label, onLongClickLabel = hold,
            onLongClick = { feedback.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(if (forward) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            label, tint = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
    }
}
