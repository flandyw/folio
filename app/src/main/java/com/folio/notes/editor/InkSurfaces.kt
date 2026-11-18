package com.folio.notes.editor

import com.folio.notes.ink.InkView
import com.folio.notes.model.Tool
import com.folio.notes.editor.toolbar.ToolOptions

/** The editor's saved pen, eraser and input preferences as one surface config, for every editing page. */
internal fun EditorPreferences.inkSurface(
    tool: Tool, options: ToolOptions, finger: Boolean, toolOptionsFor: ((Tool) -> ToolOptions)?, inputBlocked: Boolean = false,
) = InkSurfaceConfig(
    tool = tool, options = options, toolOptionsFor = toolOptionsFor, finger = finger,
    snapEnabled = snapEnabled, shapeRecognition = shapeRecognition,
    eraserPressureEnabled = eraserPressure, scribbleToErase = scribbleToErase,
    scribbleSensitivity = scribbleSensitivity, scribbleHold = scribbleHold,
    eraserWholeStroke = eraserWholeStroke, shapeMeasurements = shapeMeasurements,
    multiTouchUndo = multiTouchUndo, graphStyle = graphStyle,
    palmRejectMs = palmRejectMs, panMultiplier = panMultiplier,
    inkStyle = options.style, inputBlocked = inputBlocked,
)

/** Applies the pen, eraser and input settings every editing page shares; page-specific wiring stays with the host. */
internal fun InkView.applySurface(surface: InkSurfaceConfig, penSmoothing: Float, shapeHoldMs: Long) {
    val options = surface.options
    tool = surface.tool
    inkColor = options.color; inkWidth = options.width; inkOpacity = options.opacity; inkStyle = surface.inkStyle
    pressureEnabled = options.pressure; pressureSensitivity = options.pressureSensitivity
    pressureVariation = options.pressureVariation; pressureCurve = options.pressureCurve
    penStartOptionsFor = surface.toolOptionsFor
    this.penSmoothing = penSmoothing
    fingerDrawing = surface.finger
    snapEnabled = surface.snapEnabled; graphStyle = surface.graphStyle
    shapeRecognition = surface.shapeRecognition; this.shapeHoldMs = shapeHoldMs
    shapeMeasurements = surface.shapeMeasurements
    eraserPressureEnabled = surface.eraserPressureEnabled; eraserWholeStroke = surface.eraserWholeStroke
    scribbleToErase = surface.scribbleToErase; scribbleSensitivity = surface.scribbleSensitivity; scribbleHold = surface.scribbleHold
    multiTouchUndo = surface.multiTouchUndo
    palmRejectMs = surface.palmRejectMs; panMultiplier = surface.panMultiplier
    inputBlocked = surface.inputBlocked
}

/** Routes every page edit the view makes to [mutations]; a read-only page drops them. */
internal fun InkView.bindMutations(mutations: EditorPageMutations, readOnly: Boolean = false, onPenInput: (() -> Unit)? = null) {
    onStrokesChanged = { if (!readOnly) mutations.strokes(it) }
    onStrokeAppended = { before, stroke, after -> if (!readOnly) mutations.appendStroke(before, stroke, after) }
    onStrokeWithdrawn = { if (!readOnly) mutations.withdrawStroke(it) }
    this.onPenInput = { begins -> onPenInput?.invoke(); if (!readOnly) mutations.penActivity(begins) }
    onContentChanged = { strokes, texts, images -> if (!readOnly) mutations.content(strokes, texts, images) }
    onTextsChanged = { if (!readOnly) mutations.texts(it) }
    onImagesChanged = { if (!readOnly) mutations.images(it) }
}
