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
/**
 * A stroke's look: a line pattern for shape tools, or a brush for freehand ink. Both share the one
 * stored `style` name, so brushes need no format change; an older build reads a brush it does not
 * know as [SOLID] and draws it as its plain pen. Only append entries: the names are stored.
 */
enum class StrokeStyle {
    SOLID, DASHED, DOTTED, LONG_DASH, DASH_DOT, DENSE_DOTS,
    /** Pressure swell with tapered ends. */
    FOUNTAIN,
    /** Textured graphite whose darkness follows pressure. */
    PENCIL,
    /** Bold, even, square-ish felt tip. */
    MARKER,
    /** A flat nib held at 45°: thick across, thin along. The highlighter's chisel tip. */
    CALLIGRAPHY;

    /** Shown in menus and chips. */
    val label: String get() = when (this) {
        SOLID -> "Solid"; DASHED -> "Dashed"; DOTTED -> "Dotted"
        LONG_DASH -> "Long dash"; DASH_DOT -> "Dash-dot"; DENSE_DOTS -> "Dense dots"
        FOUNTAIN -> "Fountain"; PENCIL -> "Pencil"; MARKER -> "Marker"; CALLIGRAPHY -> "Calligraphy"
    }
    /** A brush rather than a line pattern; [SOLID] is both, the plain pen and the plain line. */
    val isBrush: Boolean get() = ordinal >= FOUNTAIN.ordinal
    /** What this style is called on [tool]'s brush picker. */
    fun brushLabel(tool: Tool): String = when {
        this == SOLID -> if (tool == Tool.HIGHLIGHTER) "Round" else "Ballpoint"
        this == CALLIGRAPHY && tool == Tool.HIGHLIGHTER -> "Chisel"
        else -> label
    }
    /** Whether a stroke drawn with [tool] may carry this style. Shapes keep the brush of the ink they were tidied from. */
    fun accepts(tool: Tool): Boolean = this == SOLID || tool in ShapeTools || this in brushes(tool)
    /** The next line pattern when a single control cycles through them. */
    fun next(): StrokeStyle = PATTERNS[(PATTERNS.indexOf(this) + 1) % PATTERNS.size]
    companion object {
        val PATTERNS = listOf(SOLID, DASHED, DOTTED, LONG_DASH, DASH_DOT, DENSE_DOTS)
        /** The brushes [tool] offers, in picker order; empty when it has none. */
        fun brushes(tool: Tool): List<StrokeStyle> = when (tool) {
            Tool.PEN -> listOf(SOLID, FOUNTAIN, PENCIL, MARKER, CALLIGRAPHY)
            Tool.HIGHLIGHTER -> listOf(SOLID, CALLIGRAPHY)
            else -> emptyList()
        }
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
     * Line pattern for shape tools, or the brush of freehand ink ([StrokeStyle.accepts]). Freehand
     * ink never takes a dash pattern, so pressure-varying ink never breaks into uneven dashes.
     */
    val style: StrokeStyle = StrokeStyle.SOLID,
    /** The [PageLayer.id] this stroke belongs to; 0 is the base layer every page has. */
    val layer: Int = 0
)
