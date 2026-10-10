@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class NotebookDropZone {
    var bounds = Rect.Zero
    var destination: NotebookDropDestination = NotebookDropDestination.Favorites
    var navigate: (() -> Unit)? = null
}
private class NotebookDragGeometry { var coordinates: LayoutCoordinates? = null }
internal class NotebookDragScrollZone {
    var bounds = Rect.Zero
    var horizontal = false
    var scroll: suspend (Float) -> Unit = {}
}

@Stable internal class NotebookDragState(
    private val allowed: (NotebookDragPayload, NotebookDropDestination) -> Boolean,
    private val canOpen: (NotebookDragPayload, NotebookDropDestination) -> Boolean,
    private val commit: (NotebookDragPayload, NotebookDropDestination) -> Boolean,
) {
    var payload: NotebookDragPayload? by mutableStateOf(null)
        private set
    var hover: NotebookDropZone? by mutableStateOf(null)
        private set
    var position = Offset.Unspecified
        private set
    val active get() = payload != null
    val destinations = mutableSetOf<NotebookDropZone>()
    val scrollZones = mutableSetOf<NotebookDragScrollZone>()
    val snackbar = SnackbarHostState()
    var cancelNative: (() -> Unit)? = null
    private var onCommitted: () -> Unit = {}

    fun accepts(destination: NotebookDropDestination) = payload?.let { allowed(it, destination) } == true
    fun opens(destination: NotebookDropDestination) = payload?.let { canOpen(it, destination) } == true
    fun begin(payload: NotebookDragPayload, onCommitted: () -> Unit) {
        this.payload = payload; this.onCommitted = onCommitted
        position = Offset.Unspecified; hover = null
    }
    fun update(position: Offset) {
        this.position = position
        // An invalid folder must block a valid background underneath it.
        hover = destinations.filter { it.bounds.contains(position) }
            .minByOrNull { it.bounds.width * it.bounds.height }
    }
    fun geometryChanged() { if (active && position != Offset.Unspecified) update(position) }
    fun drop(): Boolean {
        val source = payload ?: return false
        val destination = hover?.destination ?: return false
        if (!allowed(source, destination) || !commit(source, destination)) return false
        onCommitted()
        return true
    }
    fun end() { payload = null; hover = null; position = Offset.Unspecified; onCommitted = {} }
    fun cancel() { val running = active; end(); if (running) cancelNative?.invoke() }
}

@Composable internal fun rememberNotebookDrag(state: FolioState, model: FolioViewModel): NotebookDragState {
    val latest by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    lateinit var drag: NotebookDragState
    drag = remember(model) {
        NotebookDragState(
            allowed = { payload, destination -> NotebookDropRules.canDrop(payload, destination, latest.notes, latest.folders) },
            canOpen = { payload, destination -> NotebookDropRules.canOpen(payload, destination, latest.folders) },
            commit = { payload, destination ->
                val before = latest
                var undo: () -> Unit = {}
                val success = when (payload) {
                    is NotebookDragPayload.Folder -> {
                        val folder = before.folders.first { it.id == payload.id }
                        val target = destination as NotebookDropDestination.Folder
                        undo = { model.moveFolder(folder.id, folder.parentId) }
                        model.moveFolder(folder.id, target.id)
                    }
                    is NotebookDragPayload.Notes -> {
                        val notes = before.notes.filter { it.id in payload.ids }
                        when (destination) {
                            is NotebookDropDestination.Folder -> {
                                model.moveNotebooks(payload.ids, destination.id)
                                undo = { notes.groupBy { it.folderId }.forEach { (parent, notes) -> model.moveNotebooks(notes.map { it.id }.toSet(), parent) } }
                            }
                            NotebookDropDestination.Favorites -> {
                                model.favoriteNotebooks(payload.ids, true)
                                undo = { model.favoriteNotebooks(notes.filterNot { it.starred }.map { it.id }.toSet(), false) }
                            }
                            is NotebookDropDestination.Tag -> {
                                model.updateNotebookTags(payload.ids, listOf(destination.tag))
                                undo = { model.updateNotebookTags(notes.filter { note -> note.tags.none { it.equals(destination.tag, true) } }.map { it.id }.toSet(), emptyList(), listOf(destination.tag)) }
                            }
                        }
                        true
                    }
                }
                if (success) scope.launch {
                    drag.snackbar.currentSnackbarData?.dismiss()
                    val message = when (destination) {
                        is NotebookDropDestination.Folder -> "Moved ${payload.title} to ${if (destination.id == null && payload is NotebookDragPayload.Folder) "the top level" else destination.label}"
                        NotebookDropDestination.Favorites -> "Added ${payload.title} to Favorites"
                        is NotebookDropDestination.Tag -> "Tagged ${payload.title} · ${destination.tag}"
                    }
                    if (drag.snackbar.showSnackbar(message, "Undo", withDismissAction = true) == SnackbarResult.ActionPerformed) undo()
                }
                success
            }
        )
    }
    return drag
}

/** One native drag session outlives its source row, including hover-opened folders. */
@Composable internal fun Modifier.notebookDragHost(drag: NotebookDragState): Modifier {
    val density = LocalDensity.current
    val view = LocalView.current
    val target = remember(drag) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { val e = event.toAndroidDragEvent(); drag.update(Offset(e.x, e.y)) }
            override fun onMoved(event: DragAndDropEvent) { val e = event.toAndroidDragEvent(); drag.update(Offset(e.x, e.y)) }
            override fun onExited(event: DragAndDropEvent) { drag.update(Offset.Unspecified) }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val e = event.toAndroidDragEvent()
                drag.update(Offset(e.x, e.y))
                return drag.drop()
            }
            override fun onEnded(event: DragAndDropEvent) { drag.end() }
        }
    }
    LaunchedEffect(drag.hover, drag.active) {
        val target = drag.hover
        if (drag.active && target?.navigate != null && drag.opens(target.destination)) {
            delay(750)
            if (drag.hover === target && drag.opens(target.destination)) target.navigate?.invoke()
        }
    }
    LaunchedEffect(drag.active) {
        if (!drag.active) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val speed = with(density) { 660.dp.toPx() }
        var last = withFrameNanos { it }
        while (drag.active) {
            val now = withFrameNanos { it }
            val seconds = ((now - last) / 1_000_000_000f).coerceAtMost(.05f)
            last = now
            val zone = drag.scrollZones.filter { it.bounds.contains(drag.position) }.minByOrNull { it.bounds.width * it.bounds.height }
            if (zone != null) {
                val fraction = if (zone.horizontal) NotebookDropRules.edgeScroll(drag.position.x, zone.bounds.left, zone.bounds.right, edge)
                    else NotebookDropRules.edgeScroll(drag.position.y, zone.bounds.top, zone.bounds.bottom, edge)
                if (fraction != 0f) { zone.scroll(fraction * speed * seconds); drag.geometryChanged() }
            }
        }
    }
    DisposableEffect(drag, view) {
        drag.cancelNative = { view.cancelDragAndDrop() }
        onDispose { drag.cancel(); drag.cancelNative = null }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { it.toAndroidDragEvent().localState === drag }, target = target)
}

internal fun Modifier.notebookDropTarget(
    drag: NotebookDragState?, destination: NotebookDropDestination,
    shape: Shape = FolioShapes.large, onHoverOpen: (() -> Unit)? = null,
): Modifier = if (drag == null) this else composed {
    val zone = remember(drag) { NotebookDropZone() }
    val navigate by rememberUpdatedState(onHoverOpen)
    zone.destination = destination
    zone.navigate = if (onHoverOpen == null) null else { { navigate?.invoke() } }
    DisposableEffect(drag, zone) {
        drag.destinations.add(zone)
        onDispose { drag.destinations.remove(zone); drag.geometryChanged() }
    }
    val eligible = drag.accepts(destination)
    val hovering = drag.hover === zone && eligible
    onGloballyPositioned { zone.bounds = it.boundsInRoot(); drag.geometryChanged() }
        .then(if (eligible) Modifier.border(if (hovering) 3.dp else 1.dp,
            if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
}

internal fun Modifier.notebookDragScroll(drag: NotebookDragState, horizontal: Boolean = false, scroll: suspend (Float) -> Unit): Modifier = composed {
    val zone = remember(drag) { NotebookDragScrollZone() }
    zone.horizontal = horizontal
    val latest by rememberUpdatedState(scroll)
    zone.scroll = { latest(it) }
    DisposableEffect(drag, zone) { drag.scrollZones.add(zone); onDispose { drag.scrollZones.remove(zone) } }
    onGloballyPositioned { zone.bounds = it.boundsInRoot() }
}

/** A stationary hold still selects; movement after the hold lifts the original selection. */
internal fun Modifier.notebookDragSource(
    drag: NotebookDragState?, onCommitted: () -> Unit = {}, payload: () -> NotebookDragPayload
): Modifier = if (drag == null) this else composed {
    val view = LocalView.current
    val geometry = remember { NotebookDragGeometry() }
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    val latestPayload by rememberUpdatedState(payload)
    val completed by rememberUpdatedState(onCommitted)
    val colors = MaterialTheme.colorScheme
    val background = colors.primaryContainer.toArgb()
    val foreground = colors.onPrimaryContainer.toArgb()
    onGloballyPositioned { geometry.coordinates = it }.pointerInput(drag, view) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed) return@awaitEachGesture
            val captured = latestPayload()
            val origin = geometry.coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            // Let clickables consume the initial down; later scrolling can still cancel the hold.
            awaitPointerEvent(PointerEventPass.Final)
            if (down.type != PointerType.Mouse && awaitLongPressOrCancellation(down.id) == null) return@awaitEachGesture
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.find { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed) break
                val coordinates = geometry.coordinates?.takeIf { it.isAttached } ?: break
                if ((coordinates.localToRoot(change.position) - origin).getDistance() > viewConfiguration.touchSlop) {
                    change.consume()
                    drag.begin(captured) { completed() }
                    val data = ClipData(ClipDescription("Folio notebooks", arrayOf("application/vnd.folio.notebooks")), ClipData.Item(captured.title))
                    val shadow = NotebookDragShadow(view, captured.title, density, background, foreground)
                    if (view.startDragAndDrop(data, shadow, drag, 0)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    else drag.end()
                    break
                }
            }
        }
    }
}

private class NotebookDragShadow(view: View, private val title: String, private val density: Float,
    private val background: Int, private val foreground: Int) : View.DragShadowBuilder(view) {
    private val width = (200 * density).toInt()
    private val height = (64 * density).toInt()
    override fun onProvideShadowMetrics(size: Point, touch: Point) {
        size.set(width, height + (12 * density).toInt()); touch.set(width / 2, height + (8 * density).toInt())
    }
    override fun onDrawShadow(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = background
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20 * density, 20 * density, paint)
        paint.color = foreground; paint.textSize = 15 * density; paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val label = android.text.TextUtils.ellipsize(title, android.text.TextPaint(paint), width - 32 * density, android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, 16 * density, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

@Composable internal fun NotebookDragFeedback(drag: NotebookDragState, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        if (drag.active) Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(drag.hover?.destination?.let { if (drag.accepts(it)) "Release to drop · ${it.label}" else "Choose another destination · ${it.label}" }
                ?: "Drag to a folder, tag or Favorites · Hold to open folders",
                Modifier.padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp12), style = MaterialTheme.typography.labelLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        SnackbarHost(drag.snackbar)
    }
}
