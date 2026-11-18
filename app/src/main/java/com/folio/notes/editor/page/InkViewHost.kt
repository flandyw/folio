@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes.editor.page

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import com.folio.notes.app.AppPrefs
import com.folio.notes.editor.EditorPageActions
import com.folio.notes.editor.EditorSurfaceRole
import com.folio.notes.editor.InkSurfaceConfig
import com.folio.notes.editor.LocalEditorSurfaces
import com.folio.notes.editor.PagePresentation
import com.folio.notes.editor.applySurface
import com.folio.notes.editor.bindMutations
import com.folio.notes.editor.layout.LocalDocumentInput
import com.folio.notes.ink.InkView
import com.folio.notes.ink.shapes.ShapeAssistOptions
import com.folio.notes.input.StylusActivity
import com.folio.notes.model.NotePage
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The single Compose/native bridge. Samples, cancellation and raster teardown remain native. */
@Composable internal fun InkViewHost(
    noteId: String, page: NotePage, surface: InkSurfaceConfig,
    presentation: PagePresentation, actions: EditorPageActions, assets: PageAssets, inputStylusActivity: StylusActivity,
    penSmoothing: Float, shapeHoldMs: Long, shapeAssistOptions: ShapeAssistOptions, stickyTextSize: Float, onBound: (InkView) -> Unit,
    onReleased: (InkView) -> Unit, configure: (InkView) -> Unit,
) {
    val prefs = rememberPrefs()
    val maxZoom by rememberPref(prefs, AppPrefs.MAX_ZOOM) { AppPrefs.maxZoom(it.getFloat(AppPrefs.MAX_ZOOM, AppPrefs.DEFAULT_MAX_ZOOM)) }
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
    val detailScope = rememberCoroutineScope()
    val pdfDetailSource = remember(assets.pdfRegion) {
        assets.pdfRegion?.let { render -> { region: android.graphics.RectF, scale: Float, done: (android.graphics.Bitmap?) -> Unit ->
            detailScope.launch {
                var bitmap: android.graphics.Bitmap? = null
                try { bitmap = render(region, scale) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {}
                finally { done(bitmap) }
            }
            Unit
        } }
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
            view.maxZoom = maxZoom
            view.pdfDetailSource = pdfDetailSource
            onBound(view)
            view.inputStylusActivity = inputStylusActivity
            view.applySurface(surface, penSmoothing, shapeHoldMs)
            val readOnly = presentation.readOnly
            val hideFromAccessibility = presentation.hideFromAccessibility
            view.zoomWritingPane = surface.zoomWritingPane
            view.shapeAssistOptions = shapeAssistOptions
            view.stickyTextSize = stickyTextSize
            view.bindMutations(actions.mutations, readOnly)
            view.importantForAccessibility = if (hideFromAccessibility) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            view.isFocusable = !hideFromAccessibility
            view.contentDescription = if (readOnly) "Reference page. Use the hand or two fingers to pan and zoom. Read only."
                else "${presentation.pageLabel}. Draw with a pen or finger. Palm touches are ignored while you write with a stylus. Use two fingers to zoom and pan."
            view.onCanvasViewport = actions.onCanvasViewport; view.onPageEdges = actions.onPageEdges; view.onCanvasZoom = actions.onCanvasZoom
            if (view.page !== page || view.background !== assets.background || view.imageBitmaps !== assets.pictures) view.bind(page, assets.background, assets.pictures)
            view.resetCanvas(presentation.canvasReset); view.restoreWorkspaceCamera(presentation.initialViewport)
            view.onWorkspaceCamera = actions.onCameraChanged; view.readOnly = readOnly; view.pageCamera = presentation.pageCamera; view.onPageKey = actions.onPageKey
            view.answerAreaColor = areaColor; view.showAnswerAreas = surface.showAnswerAreas; view.writingGuides = assets.guides
            view.followEnabled = surface.followEnabled; view.writingHand = surface.writingHand; view.documentFollowZoom = surface.followZoom
            view.onFollowPan = actions.onFollowPan
            view.peekRegion = presentation.peekRegion
            view.activeLayer = surface.activeLayer
            view.onLayerBlocked = { android.widget.Toast.makeText(view.context, "That layer is hidden or locked. Pick another layer to draw.", android.widget.Toast.LENGTH_SHORT).show() }
            view.rulerEnabled = surface.rulerEnabled; view.ruler = surface.ruler; view.onRulerChanged = actions.onRulerChanged
            view.onEraserFinished = actions.onEraserFinished
            view.onUndoRequest = actions.onUndo; view.onRedoRequest = actions.onRedo
            if (surface.zoomWritingPane && presentation.writingWindowTarget != null) view.frameWritingWindow(presentation.writingWindowTarget)
            view.onActive = actions.onActive; view.onDocumentPan = actions.onPan; view.onDocumentPanEnd = actions.onPanEnd
            view.onSelectionChanged = actions.onSelection
            view.onSelectionViewBounds = actions.onSelectionAnchor
            view.onTextEdit = actions.onTextEdit; view.onTextCreate = actions.onTextCreate
            view.selectedImageId = presentation.selectedImageId?.takeIf { id -> page.images.any { it.id == id } }
            view.onImageSelected = actions.onImageSelected
            view.onImageToolTap = actions.onImageToolTap
            view.onCropMode = actions.onCropMode; view.onNavigating = actions.onNavigating; view.onLongPress = actions.onLongPress
            view.pdfLinks = pageLinks
            view.onPdfLink = actions.onPdfLink
            view.markZones = pageZones
            configure(view)
            if (continuous) documentInput?.selectionOn(page.id)?.let(view::bindDocumentSelection)
        },
    )
}

private data class HostedSurfaceIdentity(var role: EditorSurfaceRole, var pageId: String)
