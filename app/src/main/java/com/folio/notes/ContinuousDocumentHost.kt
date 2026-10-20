@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal data class DocumentHostLayout(val baseWidth: Dp, val viewportWidth: Float,
    val documentScale: Float, val toolbarTop: Dp, val windowBounds: Rect?, val eraser: EraserPreview? = null)

@Composable internal fun ContinuousDocumentHost(noteId: String, pageCount: Int,
    editor: EditorSession, pages: LazyListState, motion: DocumentMotion, viewportCoordinator: EditorViewportCoordinator,
    layout: DocumentHostLayout, activeView: InkView?, haptics: Boolean, pullAdding: Boolean,
    panBy: (Float, Float) -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val inputStylusActivity = LocalStylusActivity.current
    val density = LocalDensity.current
    val pullHaptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val activeInkView by rememberUpdatedState(activeView)
    val latestPan by rememberUpdatedState(panBy)
    val canvasWindowBounds by rememberUpdatedState(layout.windowBounds)
    val baseWidth = layout.baseWidth
    val baseWidthPx = with(density) { baseWidth.toPx() }
    val viewportWidth = layout.viewportWidth
    val documentScale = layout.documentScale
    val floatingToolbarTop = layout.toolbarTop
    val stripWidthPx = with(density) { FastScrollStripWidth.toPx() }
    val stripInsetPx = with(density) { FolioSpacing.dp2.toPx() }
    val trackTopPx = with(density) { (floatingToolbarTop + FolioSpacing.dp8).toPx() }
    val trackBottomPx = with(density) { FolioSpacing.dp16.toPx() }
    val minimumThumbPx = with(density) { FastScrollMinThumb.toPx() }
    var documentZoom by editor.viewport.zoom
    var documentPan by editor.viewport.pan
    var inkNavigating by editor.interaction.navigating
    var scrubbing by editor.interaction.scrubbing
    val stickyLift by editor.interaction.stickyLift
    val brushState = editor.interaction.eraserBrush
    val ringLive by remember { derivedStateOf { brushState.value != null } }
    val pen = LocalPenPointer.current
    val eraserPainter = remember { EraserRingPainter() }
    val objectLift by editor.interaction.objectLift
    Box(Modifier.fillMaxSize().pointerInput(inputStylusActivity, motion, viewportWidth, baseWidthPx, stripWidthPx, stripInsetPx, trackTopPx, trackBottomPx, minimumThumbPx, haptics, pageCount) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val cancellationSerial = inputStylusActivity.cancellationSerial
                    motion.stop()
                    scope.launch { pages.stopScroll() }
                    if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                        motion.reset()
                        return@awaitEachGesture
                    }
                    val span = (size.height - trackTopPx - trackBottomPx).coerceAtLeast(0f)
                    val geometry = fastScrollGeometry(pages, pageCount, span, minimumThumbPx)
                    // Only the visible thumb accepts a grab; the rest of the edge remains available
                    // for ordinary page gestures instead of jumping to a distant page on contact.
                    val onThumb = geometry != null &&
                        down.position.x in (size.width - stripInsetPx - stripWidthPx)..(size.width - stripInsetPx) &&
                        down.position.y in (trackTopPx + geometry.top)..(trackTopPx + geometry.top + geometry.height)
                    if (onThumb) {
                        motion.reset()
                        down.consume()
                        val travelSpan = (span - geometry.height).coerceAtLeast(1f)
                        val firstPageIndex = pages.firstVisibleItemIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                        val firstPageSize = pages.layoutInfo.visibleItemsInfo.firstOrNull { it.index < pageCount }?.size
                            ?: pages.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
                        val startProgress = if (!pages.canScrollForward) 1f else DocumentViewport.scrollProgress(firstPageIndex,
                            pages.firstVisibleItemScrollOffset, firstPageSize,
                            pageCount)
                        var lastPage = -1
                        fun scrubTo(progress: Float) {
                            val target = DocumentViewport.pageAt(progress, pageCount)
                            if (target != lastPage) {
                                if (lastPage >= 0 && haptics) pullHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastPage = target
                            }
                            pages.requestScrollToItem(target)
                        }
                        // Contact alone leaves the page and writing follow untouched. Wait for a
                        // deliberate vertical drag before requesting a page or showing the grab state.
                        lastPage = DocumentViewport.pageAt(startProgress, pageCount)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (inputStylusActivity.cancellationSerial != cancellationSerial) break
                                // Additional fingers or a pen cancel the scrub before it moves the page.
                                if (event.changes.any { it.id != down.id && it.pressed }) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                val delta = change.position - down.position
                                if (!scrubbing) {
                                    if (kotlin.math.abs(delta.y) <= viewConfiguration.touchSlop) continue
                                    activeInkView?.suspendWritingFollow()
                                    scrubbing = true
                                }
                                scrubTo(startProgress + delta.y / travelSpan)
                            }
                        } finally {
                            scrubbing = false
                        }
                    } else {
                        // Let ink and controls claim their down first. Unclaimed touches belong
                        // to the surrounding workspace, including page gaps and outer margins.
                        val backgroundPan = !awaitPointerEvent(PointerEventPass.Main)
                            .changes.first { it.id == down.id }.isConsumed
                        var transforming = false
                        val velocity = VelocityTracker()
                        var travel = Offset.Zero
                        velocity.addPosition(down.uptimeMillis, travel)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (inputStylusActivity.cancellationSerial != cancellationSerial) {
                                transforming = false
                                motion.reset()
                                break
                            }
                            if (event.changes.any { it.pressed && (it.type == PointerType.Stylus || it.type == PointerType.Eraser) }) {
                                transforming = false
                                motion.reset()
                                break
                            }
                            val fingers = event.changes.count { it.pressed }
                            if (backgroundPan && !transforming && fingers == 1) {
                                val change = event.changes.first { it.pressed }
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    transforming = true
                                }
                            }
                            if (fingers >= 2) {
                                activeInkView?.suspendWritingFollow()
                                if (!transforming) velocity.addPosition(event.changes.first().previousUptimeMillis, travel)
                                transforming = true
                                val factor = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val delta = event.calculatePan()
                                // Pan deltas exclude newly added/lifted pointers, so cumulative travel
                                // keeps release velocity intact when the fingers lift one at a time.
                                travel += delta
                                velocity.addPosition(event.changes.first().uptimeMillis, travel)
                                val oldZoom = documentZoom
                                val newZoom = (oldZoom * factor).coerceIn(0.5f, 4f)
                                val ratio = newZoom / oldZoom
                                documentPan = DocumentViewport.zoomPan(documentPan, centroid.x, viewportWidth, baseWidthPx * documentScale * newZoom, ratio)
                                documentZoom = newZoom
                                val offset = DocumentViewport.zoomScroll(pages.firstVisibleItemScrollOffset, centroid.y - pages.layoutInfo.beforeContentPadding, ratio)
                                if (kotlin.math.abs(factor - 1f) > .001f) {
                                    motion.reset()
                                    pages.requestScrollToItem(pages.firstVisibleItemIndex, offset - delta.y.roundToInt())
                                } else motion.drag(delta.y)
                                documentPan = DocumentViewport.clampPan(documentPan + delta.x, baseWidthPx * documentScale * newZoom, viewportWidth)
                            }
                            if (transforming && fingers < 2) {
                                val delta = event.calculatePan()
                                if (fingers == 1) {
                                    travel += delta
                                    latestPan(delta.x, delta.y)
                                }
                                velocity.addPosition(event.changes.first().uptimeMillis, travel)
                            }
                            if (transforming) event.changes.forEach { it.consume() }
                            inkNavigating = transforming
                        } while (event.changes.any { it.pressed })
                        inkNavigating = false
                        if (transforming) motion.release(velocity.calculateVelocity().y)
                        else if (motion.stretch != 0f) motion.release(0f)
                    }
                }
            }, contentAlignment = Alignment.TopCenter) {
                val anchorWidth = baseWidthPx * documentZoom
                // Mode return and pane resize retain page-local position and the continuous camera.
                LaunchedEffect(baseWidthPx) {
                    if (editor.viewport.anchor != null) viewportCoordinator.restore(anchorWidth)
                }
                ContinuousDocument(
                    state = pages,
                    modifier = Modifier.requiredWidth(baseWidth * documentScale * documentZoom).fillMaxHeight().offset { IntOffset(documentPan.roundToInt(), 0) }.graphicsLayer { translationY = motion.stretch }.holdPenFromScrolling(),
                    top = floatingToolbarTop,
                ) {
                    content()
                }
                DocumentStickyWorkspace(editor.surfaces, editor.interaction.transfers,
                    Modifier.fillMaxSize())
                // A sticky note being carried is drawn over every page, so it can leave its own page; the
                // list and the document follow it when it is held near an edge.
                stickyLift?.let { lift ->
                    val origin = canvasWindowBounds?.topLeft ?: Offset.Zero
                    Box(Modifier.fillMaxSize().drawBehind {
                        val box = lift.box
                        val w = box.width * lift.unit; val h = box.stickyHeight * lift.unit
                        val left = lift.left - origin.x; val top = lift.top - origin.y
                        drawRoundRect(Color.Black.copy(alpha = .22f), Offset(left + 3.dp.toPx(), top + 5.dp.toPx()),
                            androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(StickyNotes.CORNER * lift.unit))
                        drawIntoCanvas { c ->
                            val canvas = c.nativeCanvas
                            canvas.save()
                            canvas.translate(left, top); canvas.scale(lift.unit, lift.unit); canvas.translate(-box.x, -box.y)
                            InkRenderer.text(canvas, box)
                            lift.drawControls?.invoke(canvas)
                            canvas.restore()
                        }
                    })
                }
                // The eraser ring follows the pen over the whole editor space (page gaps, margins and notes
                // hanging off a page included), not only over paper. Toolbars and menus keep the pen to themselves.
                // The pen and the page's report are read while drawing, so moving the pen redraws only this.
                val eraserPreview = layout.eraser
                if (eraserPreview != null || ringLive) Box(Modifier.fillMaxSize().drawBehind {
                    val penAt = pen.at
                    val bounds = canvasWindowBounds
                    val brush = brushState.value
                    if (penAt == null || bounds == null || pen.overChrome || !bounds.contains(Offset(penAt.x, penAt.y))) return@drawBehind
                    if (eraserPreview == null && brush?.cutting != true) return@drawBehind
                    val radius = brush?.radiusPx ?: eraserPreview?.let { it.radius * anchorWidth / it.pageWidth } ?: return@drawBehind
                    drawIntoCanvas { c ->
                        eraserPainter.draw(c.nativeCanvas, penAt.x - bounds.left, penAt.y - bounds.top, radius, this.density,
                            brush?.cutting == true, brush?.whole ?: eraserPreview?.whole == true)
                    }
                })
                objectLift?.let { lift ->
                    val origin = canvasWindowBounds?.topLeft ?: Offset.Zero
                    Box(Modifier.fillMaxSize().drawBehind {
                        drawIntoCanvas { c ->
                            val canvas = c.nativeCanvas
                            canvas.save()
                            canvas.translate(lift.left - origin.x, lift.top - origin.y)
                            canvas.scale(lift.unit, lift.unit)
                            lift.draw(canvas)
                            canvas.restore()
                        }
                    })
                }
                val carriedObject by rememberUpdatedState(objectLift)
                val carried by rememberUpdatedState(stickyLift)
                LaunchedEffect(stickyLift != null || objectLift != null) {
                    if (carried == null && carriedObject == null) return@LaunchedEffect
                    val edge = with(density) { 72.dp.toPx() }
                    val topEdge = with(density) { floatingToolbarTop.toPx() } + edge
                    val maxStep = with(density) { 20.dp.toPx() }
                    while (true) {
                        withFrameNanos { }
                        val noteLift = carried?.takeIf { it.moving }
                        val selectionLift = carriedObject
                        if (noteLift == null && selectionLift == null) break
                        val bounds = canvasWindowBounds ?: continue
                        val x = (noteLift?.fingerX ?: selectionLift!!.fingerX) - bounds.left
                        val y = (noteLift?.fingerY ?: selectionLift!!.fingerY) - bounds.top
                        fun push(depth: Float) = (depth / edge).coerceIn(0f, 1f) * maxStep
                        val dy = when { y < topEdge -> -push(topEdge - y); y > bounds.height - edge -> push(y - (bounds.height - edge)); else -> 0f }
                        val dx = when { x < edge -> push(edge - x); x > bounds.width - edge -> -push(x - (bounds.width - edge)); else -> 0f }
                        if (dy != 0f) pages.dispatchRawDelta(dy)
                        if (dx != 0f) documentPan = DocumentViewport.clampPan(documentPan + dx, baseWidthPx * documentScale * documentZoom, viewportWidth)
                    }
                }
                // Pull past the last page: the squiggly loading shape fills as you pull, spins while the page is added.
                val pullProgress = if (pullAdding) 1f else motion.pullProgress
                LaunchedEffect(pullProgress >= 1f) { if (pullProgress >= 1f && !pullAdding) pullHaptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                if (pullProgress > 0.04f) Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = FolioSpacing.dp24).graphicsLayer {
                        alpha = if (pullAdding) 1f else (pullProgress * 1.6f).coerceAtMost(1f)
                        val grow = .7f + .3f * pullProgress
                        scaleX = grow; scaleY = grow
                    },
                    shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(start = FolioSpacing.dp8, end = FolioSpacing.dp16, top = FolioSpacing.dp4, bottom = FolioSpacing.dp4),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        if (pullAdding) LoadingIndicator(Modifier.size(40.dp).semanticsLabel("Adding page"))
                        else LoadingIndicator(progress = { pullProgress }, modifier = Modifier.size(40.dp))
                        Text(if (pullAdding) "Adding page…" else if (pullProgress >= 1f) "Release to add page" else "Pull to add page",
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
}
