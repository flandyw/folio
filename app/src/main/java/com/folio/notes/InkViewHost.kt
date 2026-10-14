@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView

/** The single Compose/native bridge. Samples, cancellation and raster teardown remain native. */
@Composable internal fun InkViewHost(
    noteId: String, page: NotePage, surface: InkSurfaceConfig,
    presentation: PagePresentation, actions: EditorPageActions, assets: PageAssets, inputStylusActivity: StylusActivity,
    penSmoothing: Float, shapeHoldMs: Long, shapeAssistOptions: ShapeAssistOptions, stickyTextSize: Float, onBound: (InkView) -> Unit,
    onReleased: (InkView) -> Unit, configure: (InkView) -> Unit,
) {
    val registry = LocalEditorSurfaces.current
    val documentInput = LocalDocumentInput.current
    val role = when {
        presentation.hideFromAccessibility -> EditorSurfaceRole.PREVIEW
        surface.zoomWritingPane -> EditorSurfaceRole.ZOOM
        presentation.readOnly -> EditorSurfaceRole.PEEK
        else -> EditorSurfaceRole.MAIN
    }
    val identity = remember { HostedSurfaceIdentity(role, page.id) }
    val release by rememberUpdatedState(onReleased)
    val areaColor = MaterialTheme.colorScheme.primary.toArgb()
    val pageLinks = remember(presentation.pdfLinks, page.pdfIndex) { presentation.pdfLinks.filter { it.pageIndex == page.pdfIndex } }
    val pageZones = remember(presentation.markZones, page.id, page.pdfIndex, presentation.markAssist) {
        presentation.markZones.filter { presentation.markAssist || it.manual }.mapNotNull { it.onPage(page) }
    }
    AndroidView(
        factory = { context -> InkView(context).also { view ->
            view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) { registry?.bind(view, identity.role, identity.pageId) }
                override fun onViewDetachedFromWindow(v: android.view.View) { registry?.release(view) }
            })
        } },
        modifier = Modifier.fillMaxSize(),
        onRelease = { view -> registry?.release(view); release(view) },
        update = { view ->
            identity.role = role; identity.pageId = page.id
            registry?.bind(view, role, page.id)
            view.documentInputBusy = if (!presentation.readOnly && documentInput != null) documentInput::isHistoryBusy else null
            // Rendering ownership follows layout. Blocking input must not move notes into the
            // clipped page surface for a frame (peek/zoom gestures used to make them flash).
            val continuous = presentation.paperWidth != null && !page.infinite && !presentation.fullscreen &&
                !presentation.pageCamera && !presentation.readOnly && !surface.zoomWritingPane
            view.workspaceNotes = continuous
            view.onDocumentGestureStart = if (continuous && documentInput != null) { stroke, lasso -> documentInput.begin(page.id, stroke, lasso) } else null
            view.onDocumentGestureSamples = if (continuous && documentInput != null) { points -> documentInput.samples(page.id, points) } else null
            view.onDocumentGestureFinish = if (documentInput != null) { { documentInput.finish(page.id) } } else null
            view.onDocumentGestureCancel = if (documentInput != null) { { documentInput.cancel() } } else null
            view.onDocumentSelectionDrag = if (continuous && documentInput != null) { dx, dy, commit -> documentInput.dragSelection(page.id, dx, dy, commit) } else null
            view.documentSelectionOwner = continuous && documentInput?.selectedOn(page.id) == true
            onBound(view)
            val tool = surface.tool
            val options = surface.options
            val finger = surface.finger
            val snapEnabled = surface.snapEnabled
            val shapeRecognition = surface.shapeRecognition
            val eraserPressureEnabled = surface.eraserPressureEnabled
            val scribbleToErase = surface.scribbleToErase
            val scribbleSensitivity = surface.scribbleSensitivity
            val eraserWholeStroke = surface.eraserWholeStroke
            val shapeMeasurements = surface.shapeMeasurements
            val multiTouchUndo = surface.multiTouchUndo
            val graphStyle = surface.graphStyle
            val palmRejectMs = surface.palmRejectMs
            val panMultiplier = surface.panMultiplier
            val inkStyle = surface.inkStyle
            val activeLayer = surface.activeLayer
            val followEnabled = surface.followEnabled
            val rulerEnabled = surface.rulerEnabled
            val ruler = surface.ruler
            val writingHand = surface.writingHand
            val followZoom = surface.followZoom
            val showAnswerAreas = surface.showAnswerAreas
            val inputBlocked = surface.inputBlocked
            val zoomWritingPane = surface.zoomWritingPane
            val pageCamera = presentation.pageCamera
            val canvasReset = presentation.canvasReset
            val selectedImageId = presentation.selectedImageId
            val readOnly = presentation.readOnly
            val hideFromAccessibility = presentation.hideFromAccessibility
            val initialViewport = presentation.initialViewport
            val peekRegion = presentation.peekRegion
            val writingWindowTarget = presentation.writingWindowTarget
            val onActive = actions.onActive
            val onPan = actions.onPan
            val onPanEnd = actions.onPanEnd
            val onSelection = actions.onSelection
            val onTextEdit = actions.onTextEdit
            val onTextCreate = actions.onTextCreate
            val onPageKey = actions.onPageKey
            val onCanvasZoom = actions.onCanvasZoom
            val onCanvasViewport = actions.onCanvasViewport
            val onPageEdges = actions.onPageEdges
            val onImageSelected = actions.onImageSelected
            val onPdfLink = actions.onPdfLink
            val onEraserFinished = actions.onEraserFinished
            val onUndo = actions.onUndo
            val onRedo = actions.onRedo
            val onCameraChanged = actions.onCameraChanged
            val onRulerChanged = actions.onRulerChanged
            val onFollowPan = actions.onFollowPan
            val onSelectionAnchor = actions.onSelectionAnchor
            val onCropMode = actions.onCropMode
            val onNavigating = actions.onNavigating
            val onLongPress = actions.onLongPress
            val background = assets.background
            val pictures = assets.pictures
            val writingGuides = assets.guides
                view.inputStylusActivity = inputStylusActivity
                view.zoomWritingPane = zoomWritingPane
                view.inkWidth = options.width; view.inkOpacity = options.opacity; view.inkStyle = inkStyle; view.pressureEnabled = options.pressure; view.fingerDrawing = finger
                view.pressureSensitivity = options.pressureSensitivity; view.pressureVariation = options.pressureVariation
                view.penSmoothing = penSmoothing
                view.snapEnabled = snapEnabled; view.graphStyle = graphStyle
                view.shapeRecognition = shapeRecognition
                view.shapeHoldMs = shapeHoldMs
                view.shapeAssistOptions = shapeAssistOptions
                view.stickyTextSize = stickyTextSize
                view.onStrokesChanged = { if (!readOnly) actions.mutations.strokes(it) }
                view.onStrokeAppended = { before, stroke, after ->
                    if (!readOnly) actions.mutations.appendStroke(before, stroke, after)
                }
                view.onStrokeWithdrawn = { if (!readOnly) actions.mutations.withdrawStroke(it) }
                view.onPenInput = { beginsStroke -> if (!readOnly) actions.mutations.penActivity(beginsStroke) }
                view.onContentChanged = { strokes, texts, images -> if (!readOnly) actions.mutations.content(strokes, texts, images) }
                view.onTextsChanged = { if (!readOnly) actions.mutations.texts(it) }
                view.onImagesChanged = { if (!readOnly) actions.mutations.images(it) }
                view.importantForAccessibility = if (hideFromAccessibility) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                view.isFocusable = !hideFromAccessibility
                view.contentDescription = if (readOnly) "Reference page. Use the hand or two fingers to pan and zoom. Read only."
                    else "${presentation.pageLabel}. Draw with a pen or finger. Palm touches are ignored while you write with a stylus. Use two fingers to zoom and pan."
                view.onCanvasViewport = onCanvasViewport; view.onPageEdges = onPageEdges; view.onCanvasZoom = onCanvasZoom; if (view.page !== page || view.background !== background || view.imageBitmaps !== pictures) view.bind(page, background, pictures); view.resetCanvas(canvasReset); view.restoreWorkspaceCamera(initialViewport); view.onWorkspaceCamera = onCameraChanged; view.readOnly = readOnly; view.pageCamera = pageCamera; view.onPageKey = onPageKey; view.tool = tool; view.inkColor = options.color
                view.answerAreaColor = areaColor; view.showAnswerAreas = showAnswerAreas; view.writingGuides = writingGuides; view.followEnabled = followEnabled; view.writingHand = writingHand; view.documentFollowZoom = followZoom
                view.onFollowPan = onFollowPan; view.inputBlocked = inputBlocked
                view.peekRegion = peekRegion
                view.activeLayer = activeLayer
                view.onLayerBlocked = { android.widget.Toast.makeText(view.context, "That layer is hidden or locked. Pick another layer to draw.", android.widget.Toast.LENGTH_SHORT).show() }
                view.rulerEnabled = rulerEnabled; view.ruler = ruler; view.onRulerChanged = onRulerChanged; view.eraserPressureEnabled = eraserPressureEnabled; view.scribbleToErase = scribbleToErase; view.scribbleSensitivity = scribbleSensitivity; view.eraserWholeStroke = eraserWholeStroke; view.shapeMeasurements = shapeMeasurements; view.multiTouchUndo = multiTouchUndo; view.palmRejectMs = palmRejectMs; view.panMultiplier = panMultiplier; view.onEraserFinished = onEraserFinished
                view.onUndoRequest = onUndo; view.onRedoRequest = onRedo
                if (zoomWritingPane && writingWindowTarget != null) view.frameWritingWindow(writingWindowTarget)
                view.onActive = onActive; view.onDocumentPan = onPan; view.onDocumentPanEnd = onPanEnd
                view.onSelectionChanged = onSelection
                view.onSelectionViewBounds = onSelectionAnchor
                view.onTextEdit = onTextEdit; view.onTextCreate = onTextCreate
                view.selectedImageId = selectedImageId?.takeIf { id -> page.images.any { it.id == id } }
                view.onImageSelected = onImageSelected
                view.onCropMode = onCropMode; view.onNavigating = onNavigating; view.onLongPress = onLongPress
                view.pdfLinks = pageLinks
                view.onPdfLink = onPdfLink
                view.markZones = pageZones
            configure(view)
            if (continuous) documentInput?.selectionOn(page.id)?.let(view::bindDocumentSelection)
        },
    )
}

private data class HostedSurfaceIdentity(var role: EditorSurfaceRole, var pageId: String)
