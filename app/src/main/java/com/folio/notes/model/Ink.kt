package com.folio.notes.model

import kotlin.math.*

/** Appended only: presets and preferences store the name, so an older build simply falls back for a newer tool. */
enum class Tool { PEN, HIGHLIGHTER, ERASER, LINE, RECTANGLE, ELLIPSE, TEXT, LASSO, HAND, TRIANGLE, DIAMOND, PENTAGON, HEXAGON, STAR, GRAPH, MARK_AREA, STICKY_NOTE, LASER }

/** Two-corner shapes shared by input, rendering and editing. */
val ShapeTools = setOf(Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE, Tool.TRIANGLE,
    Tool.DIAMOND, Tool.PENTAGON, Tool.HEXAGON, Tool.STAR)
/** GRAPH is a drawing command that expands into ordinary line paths on pen-up. */
val ShapePickerTools = ShapeTools + Tool.GRAPH
val DrawingTools = ShapePickerTools + setOf(Tool.PEN, Tool.HIGHLIGHTER)
val PolygonTools = setOf(Tool.TRIANGLE, Tool.DIAMOND, Tool.PENTAGON, Tool.HEXAGON, Tool.STAR)
/** Line pattern for shape tools, like GoodNotes' dashed and dotted lines for diagrams. */
enum class StrokeStyle {
    SOLID, DASHED, DOTTED, LONG_DASH, DASH_DOT, DENSE_DOTS;
    /** Shown in menus and chips. */
    val label: String get() = when (this) {
        SOLID -> "Solid"; DASHED -> "Dashed"; DOTTED -> "Dotted"
        LONG_DASH -> "Long dash"; DASH_DOT -> "Dash-dot"; DENSE_DOTS -> "Dense dots"
    }
    /** The next style when a single control cycles through them. */
    fun next(): StrokeStyle = entries[(ordinal + 1) % entries.size]
    companion object {
        fun safeValueOf(name: String): StrokeStyle = try { valueOf(name) } catch (_: Exception) { SOLID }
    }
}

data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f)

/** The colour, drawn width and opacity a restyle starts from. */
data class SelectionStyle(val color: Int, val width: Float, val opacity: Float)
data class Stroke(
    val tool: Tool, val color: Int, val width: Float, val points: List<InkPoint>,
    val opacity: Float = if (tool == Tool.HIGHLIGHTER) 72f / 255f else 1f,
    /**
     * Line pattern for shape tools (line, rectangle, ellipse). Freehand pen and highlighter
     * always draw solid so pressure-varying ink never breaks into uneven dashes.
     */
    val style: StrokeStyle = StrokeStyle.SOLID,
    /** The [PageLayer.id] this stroke belongs to; 0 is the base layer every page has. */
    val layer: Int = 0
)
