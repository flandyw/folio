package com.folio.notes.editor.toolbar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Folio's own glyphs for the editor: every tool, bar button and context-menu action. One 24 dp grid,
 * one 1.75 dp round stroke, solid accents only where a shape needs weight. Each glyph is SVG path
 * data (an outline, then an optional solid part), so a new one is a single line.
 */
internal object EditorIcons {
    private val ink = SolidColor(Color.Black)

    private fun icon(outline: String, solid: String = ""): Lazy<ImageVector> = lazy {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            if (outline.isNotEmpty()) addPath(addPathNodes(outline), stroke = ink, strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            if (solid.isNotEmpty()) addPath(addPathNodes(solid), fill = ink)
        }.build()
    }

    private const val FRAME = "M4 8.5V5.5A1.5 1.5 0 0 1 5.5 4H8.5 M15.5 4H18.5A1.5 1.5 0 0 1 20 5.5V8.5 M20 15.5V18.5A1.5 1.5 0 0 1 18.5 20H15.5 M8.5 20H5.5A1.5 1.5 0 0 1 4 18.5V15.5"
    private const val SHEET = "M6.5 3.5H17.5A1.5 1.5 0 0 1 19 5V19A1.5 1.5 0 0 1 17.5 20.5H6.5A1.5 1.5 0 0 1 5 19V5A1.5 1.5 0 0 1 6.5 3.5Z"
    private const val BACK_CARD = "M16 8V5.5A1.5 1.5 0 0 0 14.5 4H5.5A1.5 1.5 0 0 0 4 5.5V14.5A1.5 1.5 0 0 0 5.5 16H8"
    private const val FRONT_CARD = "M9.5 8H18.5A1.5 1.5 0 0 1 20 9.5V18.5A1.5 1.5 0 0 1 18.5 20H9.5A1.5 1.5 0 0 1 8 18.5V9.5A1.5 1.5 0 0 1 9.5 8Z"
    private const val STAR = "M12 3.1L14.47 9.2 21.03 9.66 15.99 13.9 17.58 20.29 12 16.8 6.42 20.29 8.01 13.9 2.97 9.66 9.53 9.2Z"
    private const val BOOKMARK = "M7 4H17A1 1 0 0 1 18 5V20L12 15.5 6 20V5A1 1 0 0 1 7 4Z"

    // Tools
    val Pen by icon("M17 3L21 7 8.5 19.5 3 21 4.5 15.5Z M14.5 5.5L18.5 9.5")
    val Pencil by icon("M17 3L21 7 8.5 19.5 3 21 4.5 15.5Z M14.5 5.5L18.5 9.5 M4.5 15.5L8.5 19.5", "M3 21L3.8 18 6 20.2Z")
    val Fountain by icon("M12 21L6.5 12 9.5 4H14.5L17.5 12Z M12 21V13.5", "M10.6 11.8A1.4 1.4 0 1 0 13.4 11.8 1.4 1.4 0 1 0 10.6 11.8Z")
    val Marker by icon("M9 3H15V12L13.5 15H10.5L9 12Z M9 7.5H15 M10.5 15V18.5L12 21 13.5 18.5V15")
    val Brush by icon("M18.5 3.5C19.5 4.5 19.5 5.7 18.5 6.7L11.5 13.7 8.3 10.5 15.3 3.5C16.3 2.5 17.5 2.5 18.5 3.5Z M8.3 10.5C5.7 10.8 4.3 12.5 4.3 15.1 4.3 17.1 3.3 18.1 2.5 18.5 6 19.5 11.3 18.7 11.5 13.7")
    val Highlighter by icon("M15.5 3.5L20.5 8.5 12.5 16.5 7.5 11.5Z M3.5 21H12", "M7.5 11.5L12.5 16.5 6.5 17.5Z")
    val Eraser by icon("M14.5 3.6L19.4 8.5 9.5 18.4 4.6 13.5Z M10.94 12.06L13.4 14.5 M12 21H20", "M4.6 13.5L8.47 9.6 13.4 14.5 9.5 18.4Z")
    val Lasso by icon("M20.42 10.84A8.5 6 0 0 1 18.71 13.68 M16.32 15.17A8.5 6 0 0 1 12.11 16 M8.97 15.61A8.5 6 0 0 1 5.42 13.8 M3.9 11.83A8.5 6 0 0 1 3.69 8.74 M4.93 6.67A8.5 6 0 0 1 8.21 4.63 M11.28 4.02A8.5 6 0 0 1 15.59 4.56 M18.17 5.88A8.5 6 0 0 1 20.26 8.59 M11 16.2C10 18 10.8 19.5 8.5 20.8")
    val Text by icon("M5 6.5V4.5H19V6.5 M12 4.5V19.5 M9 19.5H15")
    val Hand by icon("M8 13V6A1.5 1.5 0 0 1 11 6V11 M11 10.5V4.5A1.5 1.5 0 0 1 14 4.5V10.5 M14 10.5V5.5A1.5 1.5 0 0 1 17 5.5V11 M17 11V8A1.5 1.5 0 0 1 20 8V14.5C20 18.5 17.3 21 13.5 21H12.5C10.1 21 8.6 20.1 7.3 18.3L4.5 14.6C3.9 13.7 4.1 12.7 4.8 12.2 5.6 11.6 6.6 11.8 7.2 12.6L8 13.7")
    val Sticky by icon("M4 4H20V14L14 20H4Z M20 14H14V20 M8 9H16")
    val Laser by icon("M3.5 18.5C7 20.5 9.5 12 12 13.5 13.8 14.6 13 17.5 15 16.5 M19 3.5V5 M22 9H20.5 M21.2 5.8L20.2 6.8", "M14.8 9.8A3 3 0 1 0 20.8 9.8 3 3 0 1 0 14.8 9.8Z")
    val Ruler by icon("M2.9 15.6L15.6 2.9 21.1 8.4 8.4 21.1Z M7.1 11.4L9.2 13.5 M10.1 8.4L11.5 9.8 M13.1 5.4L15.2 7.5")
    val MarkArea by icon("$FRAME M8.5 12.2L11 14.7 15.5 9.8")

    // Shapes
    val Line by icon("M7.5 16.5L16.5 7.5", "M3 18.5A2.5 2.5 0 1 0 8 18.5 2.5 2.5 0 1 0 3 18.5Z M16 5.5A2.5 2.5 0 1 0 21 5.5 2.5 2.5 0 1 0 16 5.5Z")
    val Rectangle by icon("M6 4.5H18A1.5 1.5 0 0 1 19.5 6V18A1.5 1.5 0 0 1 18 19.5H6A1.5 1.5 0 0 1 4.5 18V6A1.5 1.5 0 0 1 6 4.5Z")
    val Ellipse by icon("M4 12A8 8 0 1 0 20 12 8 8 0 1 0 4 12Z")
    val Triangle by icon("M12 4.5L20.5 19.5H3.5Z")
    val Diamond by icon("M12 3L20 12 12 21 4 12Z")
    val Pentagon by icon("M12 3.5L20.56 9.72 17.29 19.78H6.71L3.44 9.72Z")
    val Hexagon by icon("M12 3L19.79 7.5V16.5L12 21 4.21 16.5V7.5Z")
    val Star by icon(STAR)
    val StarFilled by icon(STAR, STAR)
    val Graph by icon("M3 12H21 M12 3V21 M6 9L3 12 6 15 M18 9L21 12 18 15 M9 6L12 3 15 6 M9 18L12 21 15 18")

    // Bar
    val Undo by icon("M8.5 5L4 9.5 8.5 14 M4 9.5H14.5A5 5 0 0 1 14.5 19.5H11")
    val Redo by icon("M15.5 5L20 9.5 15.5 14 M20 9.5H9.5A5 5 0 0 0 9.5 19.5H13")
    val More by icon("", "M3.9 12A1.6 1.6 0 1 0 7.1 12 1.6 1.6 0 1 0 3.9 12Z M10.4 12A1.6 1.6 0 1 0 13.6 12 1.6 1.6 0 1 0 10.4 12Z M16.9 12A1.6 1.6 0 1 0 20.1 12 1.6 1.6 0 1 0 16.9 12Z")
    val MoreVertical by icon("", "M10.4 5.5A1.6 1.6 0 1 0 13.6 5.5 1.6 1.6 0 1 0 10.4 5.5Z M10.4 12A1.6 1.6 0 1 0 13.6 12 1.6 1.6 0 1 0 10.4 12Z M10.4 18.5A1.6 1.6 0 1 0 13.6 18.5 1.6 1.6 0 1 0 10.4 18.5Z")
    val ChevronDown by icon("M6 9.5L12 15.5 18 9.5")
    val ChevronUp by icon("M6 14.5L12 8.5 18 14.5")
    val ChevronRight by icon("M9.5 6L15.5 12 9.5 18")
    val Tune by icon("M4 7H13 M17 7H20 M4 17H7 M11 17H20 M13 7A2 2 0 1 0 17 7 2 2 0 1 0 13 7Z M7 17A2 2 0 1 0 11 17 2 2 0 1 0 7 17Z")
    val Pages by icon("M4.5 4.5H10.5V10.5H4.5Z M13.5 4.5H19.5V10.5H13.5Z M4.5 13.5H10.5V19.5H4.5Z M13.5 13.5H19.5V19.5H13.5Z")
    val Grid by icon("M4 9H20 M4 15H20 M9 4V20 M15 4V20")
    val Search by icon("M4 10.5A6.5 6.5 0 1 0 17 10.5 6.5 6.5 0 1 0 4 10.5Z M15.3 15.3L20 20")
    val Layers by icon("M12 3.5L20.5 8 12 12.5 3.5 8Z M3.5 12L12 16.5 20.5 12 M3.5 16L12 20.5 20.5 16")
    val AddPage by icon("M6.5 3.5H14L19 8.5V19A1.5 1.5 0 0 1 17.5 20.5H6.5A1.5 1.5 0 0 1 5 19V5A1.5 1.5 0 0 1 6.5 3.5Z M12 11V17 M9 14H15")
    val Bookmark by icon(BOOKMARK)
    val BookmarkFilled by icon(BOOKMARK, BOOKMARK)
    val Image by icon("M5 4.5H19A1.5 1.5 0 0 1 20.5 6V18A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5Z M3.5 16.5L8 12 12 16 15 13 20.5 18.5", "M14 9A1.5 1.5 0 1 0 17 9 1.5 1.5 0 1 0 14 9Z")
    val Fit by icon("$FRAME M8.5 9H15.5V15H8.5Z")
    val FitAll by icon(FRAME, "M9.5 12A2.5 2.5 0 1 0 14.5 12 2.5 2.5 0 1 0 9.5 12Z")
    val Sparkle by icon("M10.5 4L12.4 9.1 17.5 11 12.4 12.9 10.5 18 8.6 12.9 3.5 11 8.6 9.1Z M18.5 15.5L19.3 17.7 21.5 18.5 19.3 19.3 18.5 21.5 17.7 19.3 15.5 18.5 17.7 17.7Z")
    val Split by icon("M5 4.5H19A1.5 1.5 0 0 1 20.5 6V18A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5Z M12 4.5V19.5")
    val Share by icon("M12 3.5V14.5 M8 7.5L12 3.5 16 7.5 M7 11.5H5.5A1.5 1.5 0 0 0 4 13V19A1.5 1.5 0 0 0 5.5 20.5H18.5A1.5 1.5 0 0 0 20 19V13A1.5 1.5 0 0 0 18.5 11.5H17")
    val Back by icon("M11 5L4 12 11 19 M4 12H20")
    val Alert by icon("M3.5 12A8.5 8.5 0 1 0 20.5 12 8.5 8.5 0 1 0 3.5 12Z M12 7.5V13", "M10.9 16.3A1.1 1.1 0 1 0 13.1 16.3 1.1 1.1 0 1 0 10.9 16.3Z")
    val Keyboard by icon("M4.5 6.5H19.5A1.5 1.5 0 0 1 21 8V16A1.5 1.5 0 0 1 19.5 17.5H4.5A1.5 1.5 0 0 1 3 16V8A1.5 1.5 0 0 1 4.5 6.5Z M7 10.5H7.01 M10.3 10.5H10.31 M13.7 10.5H13.71 M17 10.5H17.01 M8 14H16")
    val Lock by icon("M6.5 10.5H17.5A1.5 1.5 0 0 1 19 12V19A1.5 1.5 0 0 1 17.5 20.5H6.5A1.5 1.5 0 0 1 5 19V12A1.5 1.5 0 0 1 6.5 10.5Z M8.5 10.5V8A3.5 3.5 0 0 1 15.5 8V10.5")
    val Unlock by icon("M6.5 10.5H17.5A1.5 1.5 0 0 1 19 12V19A1.5 1.5 0 0 1 17.5 20.5H6.5A1.5 1.5 0 0 1 5 19V12A1.5 1.5 0 0 1 6.5 10.5Z M8.5 10.5V8A3.5 3.5 0 0 1 15.2 6.6")
    val Visible by icon("M3 12C5.5 7.5 8.5 5.5 12 5.5S18.5 7.5 21 12C18.5 16.5 15.5 18.5 12 18.5S5.5 16.5 3 12Z M9.5 12A2.5 2.5 0 1 0 14.5 12 2.5 2.5 0 1 0 9.5 12Z")
    val Pause by icon("M9 6V18 M15 6V18")
    val Book by icon("M4 5.5C6.5 4.5 9.5 4.5 12 6 14.5 4.5 17.5 4.5 20 5.5V18.5C17.5 17.5 14.5 17.5 12 19 9.5 17.5 6.5 17.5 4 18.5Z M12 6V19")
    val Hidden by icon("M3 12C5.5 7.5 8.5 5.5 12 5.5S18.5 7.5 21 12C18.5 16.5 15.5 18.5 12 18.5S5.5 16.5 3 12Z M4 4L20 20")

    // Actions
    val Copy by icon("$FRONT_CARD $BACK_CARD")
    val Duplicate by icon("$FRONT_CARD $BACK_CARD M14 11.5V16.5 M11.5 14H16.5")
    val Cut by icon("M4 7A2.5 2.5 0 1 0 9 7 2.5 2.5 0 1 0 4 7Z M4 17A2.5 2.5 0 1 0 9 17 2.5 2.5 0 1 0 4 17Z M8.6 8.5L20 18 M8.6 15.5L20 6")
    val Paste by icon("M9 4.5H6.5A1.5 1.5 0 0 0 5 6V19A1.5 1.5 0 0 0 6.5 20.5H17.5A1.5 1.5 0 0 0 19 19V6A1.5 1.5 0 0 0 17.5 4.5H15 M9 3H15V6H9Z M9 12H15 M9 16H13")
    val Style by icon("M12 3.5C15.5 7.5 18 10.3 18 13.5A6 6 0 0 1 6 13.5C6 10.3 8.5 7.5 12 3.5Z M9 14A3 3 0 0 0 12 17")
    val Delete by icon("M4.5 6.5H19.5 M9.5 6.5V5A1.5 1.5 0 0 1 11 3.5H13A1.5 1.5 0 0 1 14.5 5V6.5 M6.5 6.5L7.3 18.6A1.5 1.5 0 0 0 8.8 20H15.2A1.5 1.5 0 0 0 16.7 18.6L17.5 6.5 M10 10.5V16 M14 10.5V16")
    val MoveToPage by icon("M13 3.5H6.5A1.5 1.5 0 0 0 5 5V19A1.5 1.5 0 0 0 6.5 20.5H17.5A1.5 1.5 0 0 0 19 19V16.5 M11.5 12H21 M17.5 8.5L21 12 17.5 15.5")
    val Tidy by icon("M4 20L14.5 9.5 M12.5 7.5L16.5 11.5 M18.5 3V6 M17 4.5H20 M20 11.5V13.5 M19 12.5H21 M8.5 3.5V5.5 M7.5 4.5H9.5")
    val Handwriting by icon("M3 15C5 9 7 6 8.5 6 10.5 6 9 15 11 15 12.5 15 13 11 14.5 11S15.5 15 17 15C18.2 15 19 14 20.5 12 M3 20H21")
    val ToText by icon("$FRAME M8.5 9V8.5H15.5V9 M12 8.5V15.5 M10.5 15.5H13.5")
    val Align by icon("M4 4V20 M8 7H19V10.5H8Z M8 13.5H15V17H8Z")
    val Snippet by icon("M5 20.5H19 M7 17H17V15A2 2 0 0 0 15 13H13.8C13.2 13 13 12.5 13.2 11.9L13.9 10.2A3 3 0 1 0 10.1 10.2L10.8 11.9C11 12.5 10.8 13 10.2 13H9A2 2 0 0 0 7 15Z")
    val SelectAll by icon("M4 6V4H6 M10 4H14 M18 4H20V6 M20 10V14 M20 18V20H18 M14 20H10 M6 20H4V18 M4 14V10", "M8.5 8.5H15.5V15.5H8.5Z")
    val Close by icon("M6 6L18 18 M18 6L6 18")
    val Check by icon("M5 12.5L9.5 17 19 7.5")
    val RotateLeft by icon("M4.27 9.93A8 8 0 1 1 6.34 17.66 M4.2 4.5V10H9.7")
    val RotateRight by icon("M19.73 9.93A8 8 0 1 0 17.66 17.66 M19.8 4.5V10H14.3")
    val Crop by icon("M7 3V15.5A1.5 1.5 0 0 0 8.5 17H21 M3 7H15.5A1.5 1.5 0 0 1 17 8.5V21")
    val Expand by icon("M14 4H20V10 M20 4L13.5 10.5 M10 20H4V14 M4 20L10.5 13.5")
    val ToFront by icon("M12 4L20.5 8.5 12 13 3.5 8.5Z M3.5 14L12 18.5 20.5 14", "M12 4L20.5 8.5 12 13 3.5 8.5Z")
    val ToBack by icon("M12 4L20.5 8.5 12 13 3.5 8.5Z", "M3.5 13L12 17.5 20.5 13V15.5L12 20 3.5 15.5Z")
    val Rename by icon("M3.5 17L7 7 10.5 17 M4.7 13.5H9.3 M14 5C15 5 16 5.5 16 6.5V17.5C16 18.5 15 19 14 19 M18 5C17 5 16 5.5 16 6.5 M18 19C17 19 16 18.5 16 17.5")
    val Reorder by icon("M4 7H13 M4 12H13 M4 17H13 M18 5V19 M15.5 16.5L18 19 20.5 16.5 M15.5 7.5L18 5 20.5 7.5")
    val Up by icon("M12 19V5 M6 11L12 5 18 11")
    val Down by icon("M12 5V19 M6 13L12 19 18 13")
    val Plus by icon("M12 5V19 M5 12H19")
    val Minus by icon("M5 12H19")
    val Flag by icon("M6 21V4 M6 4.5H18L15.5 8.5 18 12.5H6")
    val TextSize by icon("M3 18L6 10 9 18 M4.1 15.5H7.9 M11 18L15.5 6 20 18 M12.7 14H18.3")
    val ClearInk by icon("M4 14C6 10 7.5 8 9 8 11.2 8 9.8 15 12 15 13.6 15 14.4 11.5 16 11.5 17.2 11.5 17.5 13 19 13 M5 20L19 4")
    val Paper by icon("$SHEET M8.5 8.5H15.5 M8.5 12H15.5 M8.5 15.5H15.5")
    val ClearPage by icon("$SHEET M9.5 9.5L14.5 14.5 M14.5 9.5L9.5 14.5")
    val Lines by icon("M5 7H19 M5 12H19 M5 17H14")
    val Resize by icon("$FRAME M9 15L15 9 M11.5 9H15V12.5")
    val Timer by icon("M5 13A7 7 0 1 0 19 13 7 7 0 1 0 5 13Z M12 9.5V13L14.5 14.5 M9.5 3H14.5")
    val Home by icon("M4 11L12 4 20 11 M6 9.5V20H18V9.5 M10 20V14.5H14V20")
}
