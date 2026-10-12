package com.folio.notes

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp

/** Native input settings shared by every layout. Raw samples stay in InkView. */
internal data class InkSurfaceConfig(
    val tool: Tool,
    val options: ToolOptions,
    val finger: Boolean,
    val snapEnabled: Boolean,
    val shapeRecognition: Boolean,
    val eraserPressureEnabled: Boolean = true,
    val scribbleToErase: Boolean = true,
    val scribbleSensitivity: Float = ScribbleSensitivity.DEFAULT,
    val eraserWholeStroke: Boolean = false,
    val shapeMeasurements: Boolean = true,
    val multiTouchUndo: Boolean = true,
    val graphStyle: GraphStyle = GraphStyle.DEFAULT,
    val palmRejectMs: Long = AppPrefs.DEFAULT_PALM_MS,
    val panMultiplier: Float = 1f,
    val inkStyle: StrokeStyle = StrokeStyle.SOLID,
    val activeLayer: Int = 0,
    val followEnabled: Boolean = false,
    val rulerEnabled: Boolean = false,
    val ruler: RulerLine? = null,
    val writingHand: WritingHand = WritingHand.RIGHT,
    val followZoom: Float = 1f,
    val autoDetectAnswerAreas: Boolean = false,
    val showAnswerAreas: Boolean = true,
    val inputBlocked: Boolean = false,
    val zoomWritingPane: Boolean = false
)

internal data class PagePresentation(
    val active: Boolean,
    val pageLabel: String = "Notebook page",
    val fullscreen: Boolean = false,
    val pageCamera: Boolean = false,
    val canvasReset: Int = 0,
    val selectedImageId: String? = null,
    val pdfLinks: List<PdfLink> = emptyList(),
    val readOnly: Boolean = false,
    val hideFromAccessibility: Boolean = false,
    val initialViewport: WorkspaceViewport? = null,
    val peekRegion: PeekAnchor? = null,
    val writingWindowTarget: PeekAnchor? = null,
    val selectionAnchor: Rect? = null,
    val paperWidth: Dp? = null,
    val textDraft: TextBox? = null,
    val textTopInset: Float = 0f,
    val selectionMenu: (@Composable (Dp) -> Unit)? = null,
    val selectionMenuViewport: Rect? = null,
    val markZones: List<MarkZone> = emptyList(),
    val markAssist: Boolean = false,
    val markColor: Int = Marking.DEFAULT_COLOR
)

internal data class EditorPageActions(
    val onActive: () -> Unit,
    val onPan: (Float, Float) -> Unit,
    val onPanEnd: (Float) -> Unit,
    val onSelection: (CanvasSelection) -> Unit,
    val onTextEdit: (TextBox) -> Unit,
    val onTextCreate: (InkPoint) -> Unit,
    val onLoad: () -> Unit,
    val onPageKey: ((android.view.KeyEvent) -> Boolean)? = null,
    val onCanvasZoom: (Float) -> Unit = {},
    val onCanvasViewport: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    val onPageEdges: (Boolean, Boolean) -> Unit = { _, _ -> },
    val onImageSelected: (PageImage?) -> Unit = {},
    val onPdfLink: (PdfLink) -> Unit = {},
    val onEraserFinished: (() -> Unit)? = null,
    val onUndo: (() -> Unit)? = null,
    val onRedo: (() -> Unit)? = null,
    val onSelectAllView: ((InkView) -> Unit)? = null,
    val onCameraChanged: (WorkspaceViewport) -> Unit = {},
    val onRulerChanged: (RulerLine) -> Unit = {},
    val onFollowPan: (Float, Float) -> Pair<Float, Float> = { _, _ -> 0f to 0f },
    val onStickyDraw: () -> Unit = {},
    val onTextDraft: (TextBox) -> Unit = {},
    val onTextDone: () -> Unit = {},
    val onSelectionAnchor: (Rect?) -> Unit = {},
    val onReplaceZone: (old: MarkZone?, new: MarkZone?) -> Unit = { _, _ -> },
    val onPageFrame: (String, Rect?) -> Unit = { _, _ -> },
    val onPageRegion: (String, Rect?) -> Unit = { _, _ -> },
    val onObjectLift: ((ObjectLift?) -> Unit)? = null,
    val onObjectDrop: ((String, ObjectLift) -> Boolean)? = null,
    val onStickyLift: ((StickyLift?) -> Unit)? = null,
    val onStickyDrop: ((String, StickyLift) -> Boolean)? = null,
    val onCropMode: (Boolean) -> Unit = {},
    val onNavigating: (Boolean) -> Unit = {},
    val onLongPress: (Float, Float, InkPoint) -> Unit = { _, _, _ -> },
    val onSelectionDrop: (String, CanvasSelection, Float, Float) -> Boolean = { _, _, _, _ -> false }
)

