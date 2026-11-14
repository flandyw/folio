package com.folio.notes.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp

/**
 * Original Folio glyphs: filled silhouettes on a centred 24 dp canvas.
 * Details use even-odd cut-outs; open marks use rounded 2 dp strokes.
 * Related glyphs use the same contour; selection must never change their size.
 * [icon] fits the visible bounds to 20 dp (smaller for bare navigation marks),
 * keeping stroke weight constant. No shape-dependent resizing or stock icon fallback.
 */
internal object FolioIcons {
    private val ink = SolidColor(Color.Black)
    private const val STROKE = 2f
    /** [mirror] flips a directional glyph in a right-to-left layout. */
    private fun icon(line: String, solid: String = "", size: Float = 20f, mirror: Boolean = false): Lazy<ImageVector> = lazy {
        val lines = addPathNodes(line)
        val solids = addPathNodes(solid)
        // Measured along the flattened outline: the platform's own path bounds include curve control
        // points, which would make every curved glyph come out a little small.
        fun outline(nodes: List<PathNode>) = if (nodes.isEmpty()) FloatArray(0) else nodes.toPath().asAndroidPath().approximate(0.02f)
        fun bounds(points: FloatArray): Rect? {
            if (points.isEmpty()) return null
            val xs = points.filterIndexed { index, _ -> index % 3 == 1 }
            val ys = points.filterIndexed { index, _ -> index % 3 == 2 }
            return Rect(xs.min(), ys.min(), xs.max(), ys.max())
        }
        val linePoints = outline(lines)
        val solidPoints = outline(solids)
        val drawn = bounds(linePoints)
        val filled = bounds(solidPoints)
        /** Everything drawn, with the line work grown by [halfStroke], and the scale that fits it to [target]. */
        fun fit(halfStroke: Float, target: Float): Pair<Rect, Float> {
            val box = listOfNotNull(drawn?.inflate(halfStroke), filled).reduce { a, b ->
                Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
            }
            return box to target / maxOf(box.width, box.height)
        }
        // The stroke's share of the box depends on the scale, so settle it in two passes.
        val (box, scale) = fit(STROKE / 2f / fit(0f, size).second, size)
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f, autoMirror = mirror).apply {
            addGroup(pivotX = box.center.x, pivotY = box.center.y, scaleX = scale, scaleY = scale,
                translationX = 12f - box.center.x, translationY = 12f - box.center.y)
            if (lines.isNotEmpty()) addPath(lines, stroke = ink, strokeLineWidth = STROKE / scale,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            if (solids.isNotEmpty()) addPath(solids, fill = ink, pathFillType = PathFillType.EvenOdd)
            clearGroup()
        }.build()
    }

    // Tools
    val Pen by icon("", "M15.4 4.2L17.1 2.5Q18.2 1.4 19.3 2.5L21.5 4.7Q22.6 5.8 21.5 6.9L19.8 8.6Z M14.2 5.4L18.6 9.8L9.5 18.9Q9.1 19.3 8.5 19.5L3.5 21Q2.6 21.3 2.9 20.4L4.5 15.5Q4.7 14.9 5.1 14.5Z M5.6 16.1L4.7 19.2L7.8 18.3Z")
    val Pencil by icon("", "M18.29 3.44L20.56 5.71L20.56 7.12L18.79 8.89L15.11 5.21L16.88 3.44Z M14.19 6.13L17.87 9.81L11.01 16.67L7.33 12.99Z M6.41 13.91L10.09 17.59L4.58 19.42Z")
    val Fountain by icon("", "M12 22L6 12 9.5 3H14.5L18 12Z M10.4 11A1.6 1.6 0 1 0 13.6 11A1.6 1.6 0 1 0 10.4 11ZM11.4 14H12.6V20H11.4Z")
    val Marker by icon("", "M17.59 2.74L21.26 6.41L21.26 7.83L13.84 15.25L11.29 15.82L8.18 12.71L8.75 10.16L16.17 2.74Z M7.69 13.91L10.09 16.31L7.9 18.51L4.58 19.42L5.49 16.1Z")
    val Brush by icon("", "M18.5 3.5C19.5 4.5 19.5 5.7 18.5 6.7L12 13.2 8.8 10 15.3 3.5C16.3 2.5 17.5 2.5 18.5 3.5Z M7.5 11.5C5.2 11.9 4 13.6 4 15.8 4 17.6 3 18.5 2 19 5.8 20.2 10.6 19 10.8 14.6Z")
    val Highlighter by icon("", "M14.4 3.2Q15.8 1.8 17.2 3.2L20.8 6.8Q22.2 8.2 20.8 9.6L14.6 15.8Q13.8 16.6 12.8 16.2L7.8 11.2Q7.4 10.2 8.2 9.4Z M7 12.4L11.6 17L9.3 19.3Q9 19.6 8.5 19.6H3.5Q2.7 19.6 3.3 19Z M12.2 7L17 11.8L18.1 10.7L13.3 5.9Z")
    val Eraser by icon("", "M11.7 3.6Q13.1 2.2 14.5 3.6L20.4 9.5Q21.8 10.9 20.4 12.3L15.7 17L7 8.3Z M5.8 9.5L14.5 18.2L12.5 20.2Q11.7 21 10.6 21H8.1Q7 21 6.2 20.2L2.8 16.8Q1.4 15.4 2.8 14Z")
    val Lasso by icon("M20.42 10.84A8.5 6 0 0 1 18.71 13.68 M16.32 15.17A8.5 6 0 0 1 12.11 16 M8.97 15.61A8.5 6 0 0 1 5.42 13.8 M3.9 11.83A8.5 6 0 0 1 3.69 8.74 M4.93 6.67A8.5 6 0 0 1 8.21 4.63 M11.28 4.02A8.5 6 0 0 1 15.59 4.56 M18.17 5.88A8.5 6 0 0 1 20.26 8.59 M11 16.2C10 18 10.8 19.5 8.5 20.8")
    val Text by icon("", "M5 4.5H19V7H13.3V20H10.7V7H5Z", 17f)
    val Hand by icon("", "M8 13V6A1.5 1.5 0 0 1 11 6V4.5A1.5 1.5 0 0 1 14 4.5V5.5A1.5 1.5 0 0 1 17 5.5V8A1.5 1.5 0 0 1 20 8V14.5C20 18.5 17.3 21 13.5 21H12.5C10.1 21 8.6 20.1 7.3 18.3L4.5 14.6C3.9 13.7 4.1 12.7 4.8 12.2 5.6 11.6 6.6 11.8 7.2 12.6Z M10.45 7.5H11.55V12.5H10.45ZM13.45 6.5H14.55V12.5H13.45ZM16.45 9H17.55V12.5H16.45Z")
    val Sticky by icon("", "M5.5 4H18.5A1.5 1.5 0 0 1 20 5.5V13H13V20H5.5A1.5 1.5 0 0 1 4 18.5V5.5A1.5 1.5 0 0 1 5.5 4Z M14.8 14.8H20L14.8 20Z M7 7.5H17V9H7ZM7 11H11.5V12.5H7Z")
    val Laser by icon("M15.9 1.5V3 M22.5 8.1H21 M20.6 3.4L19.5 4.5 M11.2 3.4L12.3 4.5 M20.6 12.8L19.5 11.7", "M9.53 11.65L12.35 14.47L6.34 20.49L5.64 20.49L3.51 18.36L3.51 17.66ZM12.9 8.1A3 3 0 1 0 18.9 8.1A3 3 0 1 0 12.9 8.1Z")
    val Ruler by icon("", "M2.9 15.6L15.6 2.9 21.1 8.4 8.4 21.1Z M6.68 11.82L7.52 10.98 9.62 13.08 8.78 13.92Z M9.68 8.82L10.52 7.98 11.92 9.38 11.08 10.22Z M12.68 5.82L13.52 4.98 15.62 7.08 14.78 7.92Z")
    val MarkArea by icon("M4 8.5V5.5A1.5 1.5 0 0 1 5.5 4H8.5 M15.5 4H18.5A1.5 1.5 0 0 1 20 5.5V8.5 M20 15.5V18.5A1.5 1.5 0 0 1 18.5 20H15.5 M8.5 20H5.5A1.5 1.5 0 0 1 4 18.5V15.5 M8.5 12.2L11 14.7 15.5 9.8")

    // Shapes: outlines, because that is what the tools draw
    val Line by icon("M7.5 16.5L16.5 7.5", "M3 18.5A2.5 2.5 0 1 0 8 18.5A2.5 2.5 0 1 0 3 18.5ZM16 5.5A2.5 2.5 0 1 0 21 5.5A2.5 2.5 0 1 0 16 5.5Z")
    val Rectangle by icon("M5.5 4H18.5A1.5 1.5 0 0 1 20 5.5V18.5A1.5 1.5 0 0 1 18.5 20H5.5A1.5 1.5 0 0 1 4 18.5V5.5A1.5 1.5 0 0 1 5.5 4Z")
    val Ellipse by icon("M4 12A8 8 0 1 0 20 12A8 8 0 1 0 4 12Z")
    val Triangle by icon("M12 4.5L20.5 19.5H3.5Z")
    val Diamond by icon("M12 3L20 12 12 21 4 12Z")
    val Pentagon by icon("M12 3.5L20.56 9.72 17.29 19.78H6.71L3.44 9.72Z")
    val Hexagon by icon("M12 3L19.79 7.5V16.5L12 21 4.21 16.5V7.5Z")
    val Star by icon("M12 3.1L14.47 9.2 21.03 9.66 15.99 13.9 17.58 20.29 12 16.8 6.42 20.29 8.01 13.9 2.97 9.66 9.53 9.2Z")
    val StarFilled by icon("M12 3.1L14.47 9.2 21.03 9.66 15.99 13.9 17.58 20.29 12 16.8 6.42 20.29 8.01 13.9 2.97 9.66 9.53 9.2Z", "M12 3.1L14.47 9.2 21.03 9.66 15.99 13.9 17.58 20.29 12 16.8 6.42 20.29 8.01 13.9 2.97 9.66 9.53 9.2Z")
    val Graph by icon("M3 12H21 M12 3V21 M6 9L3 12 6 15 M18 9L21 12 18 15 M9 6L12 3 15 6 M9 18L12 21 15 18")

    // Bar
    val Undo by icon("M8.5 5L4 9.5 8.5 14 M4 9.5H14.5A5 5 0 0 1 14.5 19.5H11", "", 20f, mirror = true)
    val Redo by icon("M15.5 5L20 9.5 15.5 14 M20 9.5H9.5A5 5 0 0 0 9.5 19.5H13", "", 20f, mirror = true)
    val More by icon("", "M3 12A2 2 0 1 0 7 12A2 2 0 1 0 3 12ZM10 12A2 2 0 1 0 14 12A2 2 0 1 0 10 12ZM17 12A2 2 0 1 0 21 12A2 2 0 1 0 17 12Z", 16f)
    val MoreVertical by icon("", "M10 5A2 2 0 1 0 14 5A2 2 0 1 0 10 5ZM10 12A2 2 0 1 0 14 12A2 2 0 1 0 10 12ZM10 19A2 2 0 1 0 14 19A2 2 0 1 0 10 19Z", 16f)
    val ChevronDown by icon("M6 9.5L12 15.5 18 9.5", "", 12f)
    val ChevronUp by icon("M6 14.5L12 8.5 18 14.5", "", 12f)
    val ChevronRight by icon("M9.5 6L15.5 12 9.5 18", "", 12f, mirror = true)
    val Tune by icon("M4 7H11 M19 7H20 M4 17H5 M13 17H20", "M12.2 7A2.8 2.8 0 1 0 17.8 7A2.8 2.8 0 1 0 12.2 7ZM6.2 17A2.8 2.8 0 1 0 11.8 17A2.8 2.8 0 1 0 6.2 17Z")
    val Pages by icon("", "M5 4H10A1 1 0 0 1 11 5V10A1 1 0 0 1 10 11H5A1 1 0 0 1 4 10V5A1 1 0 0 1 5 4ZM14 4H19A1 1 0 0 1 20 5V10A1 1 0 0 1 19 11H14A1 1 0 0 1 13 10V5A1 1 0 0 1 14 4ZM5 13H10A1 1 0 0 1 11 14V19A1 1 0 0 1 10 20H5A1 1 0 0 1 4 19V14A1 1 0 0 1 5 13ZM14 13H19A1 1 0 0 1 20 14V19A1 1 0 0 1 19 20H14A1 1 0 0 1 13 19V14A1 1 0 0 1 14 13Z")
    val Grid by icon("M4 9H20 M4 15H20 M9 4V20 M15 4V20")
    val Search by icon("M4 10.5A6.5 6.5 0 1 0 17 10.5A6.5 6.5 0 1 0 4 10.5Z M15.3 15.3L20 20")
    val Layers by icon("M3.5 12.5L12 17 20.5 12.5 M3.5 16.5L12 21 20.5 16.5", "M12 3L21 7.5 12 12 3 7.5Z")
    val AddPage by icon("", "M6.5 3H14L19 8V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V4.5A1.5 1.5 0 0 1 6.5 3Z M11.2 10.5H12.8V13.2H15.5V14.8H12.8V17.5H11.2V14.8H8.5V13.2H11.2Z")
    val Bookmark by icon("M8 4H16Q18 4 18 6V20L12 16L6 20V6Q6 4 8 4Z")
    val BookmarkFilled by icon("M8 4H16Q18 4 18 6V20L12 16L6 20V6Q6 4 8 4Z", "M8 4H16Q18 4 18 6V20L12 16L6 20V6Q6 4 8 4Z")
    val Image by icon("", "M4.5 4H19.5A1.5 1.5 0 0 1 21 5.5V18.5A1.5 1.5 0 0 1 19.5 20H4.5A1.5 1.5 0 0 1 3 18.5V5.5A1.5 1.5 0 0 1 4.5 4Z M5.5 17.5L9.5 12.5 12.5 16 15 13 18.5 17.5Z M6.9 8.5A1.6 1.6 0 1 0 10.1 8.5A1.6 1.6 0 1 0 6.9 8.5Z")
    val Fit by icon("M4 8.5V5.5A1.5 1.5 0 0 1 5.5 4H8.5 M15.5 4H18.5A1.5 1.5 0 0 1 20 5.5V8.5 M20 15.5V18.5A1.5 1.5 0 0 1 18.5 20H15.5 M8.5 20H5.5A1.5 1.5 0 0 1 4 18.5V15.5", "M8 8.5H16V15.5H8Z")
    val FitAll by icon("M4 8.5V5.5A1.5 1.5 0 0 1 5.5 4H8.5 M15.5 4H18.5A1.5 1.5 0 0 1 20 5.5V8.5 M20 15.5V18.5A1.5 1.5 0 0 1 18.5 20H15.5 M8.5 20H5.5A1.5 1.5 0 0 1 4 18.5V15.5", "M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12Z")
    val Sparkle by icon("", "M10.5 3L12.6 8.9 18.5 11 12.6 13.1 10.5 19 8.4 13.1 2.5 11 8.4 8.9Z M18.5 14.5L19.5 17.5 22.5 18.5 19.5 19.5 18.5 22.5 17.5 19.5 14.5 18.5 17.5 17.5Z")
    val Split by icon("", "M4.5 4H19.5A1.5 1.5 0 0 1 21 5.5V18.5A1.5 1.5 0 0 1 19.5 20H4.5A1.5 1.5 0 0 1 3 18.5V5.5A1.5 1.5 0 0 1 4.5 4ZM11.2 4H12.8V20H11.2Z")
    val Share by icon("M12 3.5V14.5 M8 7.5L12 3.5 16 7.5 M7 11.5H5.5A1.5 1.5 0 0 0 4 13V19A1.5 1.5 0 0 0 5.5 20.5H18.5A1.5 1.5 0 0 0 20 19V13A1.5 1.5 0 0 0 18.5 11.5H17")
    val Back by icon("M11 5L4 12 11 19 M4 12H20", "", 20f, mirror = true)
    val Alert by icon("", "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12ZM11.1 6.8H12.9V13.4H11.1ZM10.85 16.5A1.15 1.15 0 1 0 13.15 16.5A1.15 1.15 0 1 0 10.85 16.5Z")
    val Keyboard by icon("", "M4 6H20A1.5 1.5 0 0 1 21.5 7.5V16.5A1.5 1.5 0 0 1 20 18H4A1.5 1.5 0 0 1 2.5 16.5V7.5A1.5 1.5 0 0 1 4 6ZM5.5 9H7.2V10.7H5.5ZM9 9H10.7V10.7H9ZM13.3 9H15V10.7H13.3ZM16.8 9H18.5V10.7H16.8ZM8 13.3H16V15H8Z")
    val Lock by icon("M8.5 10.5V8A3.5 3.5 0 0 1 15.5 8V10.5", "M6.5 10.5H17.5A1.5 1.5 0 0 1 19 12V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V12A1.5 1.5 0 0 1 6.5 10.5ZM10.5 15.7A1.5 1.5 0 1 0 13.5 15.7A1.5 1.5 0 1 0 10.5 15.7Z")
    val Unlock by icon("M8.5 10.5V8A3.5 3.5 0 0 1 15.2 6.6", "M6.5 10.5H17.5A1.5 1.5 0 0 1 19 12V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V12A1.5 1.5 0 0 1 6.5 10.5ZM10.5 15.7A1.5 1.5 0 1 0 13.5 15.7A1.5 1.5 0 1 0 10.5 15.7Z")
    val Visible by icon("", "M2.5 12C5 7.3 8.3 5 12 5S19 7.3 21.5 12C19 16.7 15.7 19 12 19S5 16.7 2.5 12ZM8.2 12A3.8 3.8 0 1 0 15.8 12A3.8 3.8 0 1 0 8.2 12ZM10.1 12A1.9 1.9 0 1 0 13.9 12A1.9 1.9 0 1 0 10.1 12Z")
    val Hidden by icon("M2.5 12C5 7.3 8.3 5 12 5S19 7.3 21.5 12C19 16.7 15.7 19 12 19S5 16.7 2.5 12Z M4 4L20 20")
    val Pause by icon("", "M7.5 5H9.5A1 1 0 0 1 10.5 6V18A1 1 0 0 1 9.5 19H7.5A1 1 0 0 1 6.5 18V6A1 1 0 0 1 7.5 5ZM14.5 5H16.5A1 1 0 0 1 17.5 6V18A1 1 0 0 1 16.5 19H14.5A1 1 0 0 1 13.5 18V6A1 1 0 0 1 14.5 5Z", 16f)
    val Book by icon("", "M3 5.5C6 4.3 9 4.5 11.2 6V20C9 18.6 6 18.4 3 19.5Z M21 5.5C18 4.3 15 4.5 12.8 6V20C15 18.6 18 18.4 21 19.5Z")

    // Actions
    val Copy by icon("M16 7V5.5A1.5 1.5 0 0 0 14.5 4H5.5A1.5 1.5 0 0 0 4 5.5V14.5A1.5 1.5 0 0 0 5.5 16H7", "M10.5 9H19.5A1.5 1.5 0 0 1 21 10.5V19.5A1.5 1.5 0 0 1 19.5 21H10.5A1.5 1.5 0 0 1 9 19.5V10.5A1.5 1.5 0 0 1 10.5 9Z")
    val Duplicate by icon("M16 7V5.5A1.5 1.5 0 0 0 14.5 4H5.5A1.5 1.5 0 0 0 4 5.5V14.5A1.5 1.5 0 0 0 5.5 16H7", "M10.5 9H19.5A1.5 1.5 0 0 1 21 10.5V19.5A1.5 1.5 0 0 1 19.5 21H10.5A1.5 1.5 0 0 1 9 19.5V10.5A1.5 1.5 0 0 1 10.5 9ZM14.2 11.5H15.8V14.2H18.5V15.8H15.8V18.5H14.2V15.8H11.5V14.2H14.2Z")
    val Cut by icon("M4 7A2.5 2.5 0 1 0 9 7A2.5 2.5 0 1 0 4 7ZM4 17A2.5 2.5 0 1 0 9 17A2.5 2.5 0 1 0 4 17Z M8.6 8.5L20 18 M8.6 15.5L20 6")
    val Paste by icon("", "M6.5 4.5H9V3.5A1 1 0 0 1 10 2.5H14A1 1 0 0 1 15 3.5V4.5H17.5A1.5 1.5 0 0 1 19 6V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V6A1.5 1.5 0 0 1 6.5 4.5Z M10.6 4H13.4V5.6H10.6ZM8.5 10.5H15.5V12H8.5ZM8.5 14.5H13V16H8.5Z")
    val Style by icon("", "M12 3C15.8 7.3 18.5 10.3 18.5 13.8A6.5 6.5 0 0 1 5.5 13.8C5.5 10.3 8.2 7.3 12 3Z")
    val Delete by icon("", "M4.5 5.5H9L10 4H14L15 5.5H19.5V7.5H4.5Z M6 9H18L17.2 19.6A1.5 1.5 0 0 1 15.7 21H8.3A1.5 1.5 0 0 1 6.8 19.6Z M9.6 11.5H11V18H9.6ZM13 11.5H14.4V18H13Z")
    val MoveToPage by icon("M13 3.5H6.5A1.5 1.5 0 0 0 5 5V19A1.5 1.5 0 0 0 6.5 20.5H17.5A1.5 1.5 0 0 0 19 19V16.5 M11.5 12H21 M17.5 8.5L21 12 17.5 15.5", "", 20f, mirror = true)
    val Tidy by icon("M4 20L14 10", "M12.5 8.5L15.5 11.5 17.5 9.5 14.5 6.5Z M19 2L19.8 4.2 22 5 19.8 5.8 19 8 18.2 5.8 16 5 18.2 4.2Z M20.5 11L21 12.5 22.5 13 21 13.5 20.5 15 20 13.5 18.5 13 20 12.5Z M9 2.5L9.5 4 11 4.5 9.5 5 9 6.5 8.5 5 7 4.5 8.5 4Z")
    val Handwriting by icon("M3 15C5 9 7 6 8.5 6 10.5 6 9 15 11 15 12.5 15 13 11 14.5 11S15.5 15 17 15C18.2 15 19 14 20.5 12 M3 20H21")
    val ToText by icon("M4 8.5V5.5A1.5 1.5 0 0 1 5.5 4H8.5 M15.5 4H18.5A1.5 1.5 0 0 1 20 5.5V8.5 M20 15.5V18.5A1.5 1.5 0 0 1 18.5 20H15.5 M8.5 20H5.5A1.5 1.5 0 0 1 4 18.5V15.5", "M8 8H16V10H13V16H11V10H8Z")
    val Align by icon("M4 3.5V20.5", "M8.5 6H19A1 1 0 0 1 20 7V9.5A1 1 0 0 1 19 10.5H8.5A1 1 0 0 1 7.5 9.5V7A1 1 0 0 1 8.5 6ZM8.5 13.5H14.5A1 1 0 0 1 15.5 14.5V17A1 1 0 0 1 14.5 18H8.5A1 1 0 0 1 7.5 17V14.5A1 1 0 0 1 8.5 13.5Z")
    val Snippet by icon("", "M5 19.5H19V21.5H5Z M7 18V15A2 2 0 0 1 9 13H10.2C10.8 13 11 12.5 10.8 11.9L10.1 10.2A3 3 0 1 1 13.9 10.2L13.2 11.9C13 12.5 13.2 13 13.8 13H15A2 2 0 0 1 17 15V18Z")
    val SelectAll by icon("M4 6V4H6 M10 4H14 M18 4H20V6 M20 10V14 M20 18V20H18 M14 20H10 M6 20H4V18 M4 14V10", "M8 8H16V16H8Z")
    val Close by icon("M6 6L18 18 M18 6L6 18", "", 14f)
    val Check by icon("M5 12.5L9.5 17 19 7.5", "", 16f)
    val RotateLeft by icon("M4.27 9.93A8 8 0 1 1 6.34 17.66 M4.2 4.5V10H9.7")
    val RotateRight by icon("M19.73 9.93A8 8 0 1 0 17.66 17.66 M19.8 4.5V10H14.3")
    val Crop by icon("M7 3V15.5A1.5 1.5 0 0 0 8.5 17H21 M3 7H15.5A1.5 1.5 0 0 1 17 8.5V21")
    val Expand by icon("M14 4H20V10 M20 4L13.5 10.5 M10 20H4V14 M4 20L10.5 13.5")
    val ToFront by icon("M3 13.5L12 18 21 13.5", "M12 4L21 8.5 12 13 3 8.5Z")
    val ToBack by icon("M12 4L21 8.5 12 13 3 8.5Z", "M3 12.5L12 17 21 12.5V16L12 20.5 3 16Z")
    val Rename by icon("M3.5 17L7 7 10.5 17 M4.7 13.5H9.3 M14 5C15 5 16 5.5 16 6.5V17.5C16 18.5 15 19 14 19 M18 5C17 5 16 5.5 16 6.5 M18 19C17 19 16 18.5 16 17.5")
    val Reorder by icon("M4 7H13 M4 12H13 M4 17H13 M18 5V19 M15.5 16.5L18 19 20.5 16.5 M15.5 7.5L18 5 20.5 7.5")
    val Up by icon("M12 19V5 M6 11L12 5 18 11", "", 16f)
    val Down by icon("M12 5V19 M6 13L12 19 18 13", "", 16f)
    val Plus by icon("M12 5V19 M5 12H19", "", 16f)
    val Minus by icon("M5 12H19", "", 16f)
    val Flag by icon("M6 21V4H18L15.5 8.5L18 13H6", "M6 4H18L15.5 8.5L18 13H6Z")
    val FlagOutline by icon("M6 21V4H18L15.5 8.5L18 13H6")
    val TextSize by icon("M3 18L6 10 9 18 M4.1 15.5H7.9 M11 18L15.5 6 20 18 M12.7 14H18.3")
    val ClearInk by icon("M4 14C6 10 7.5 8 9 8 11.2 8 9.8 15 12 15 13.6 15 14.4 11.5 16 11.5 17.2 11.5 17.5 13 19 13 M5 20L19 4")
    val Paper by icon("", "M6.5 3H17.5A1.5 1.5 0 0 1 19 4.5V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V4.5A1.5 1.5 0 0 1 6.5 3ZM8 7.5H16V9H8ZM8 11.2H16V12.7H8ZM8 15H13V16.5H8Z")
    val ClearPage by icon("", "M6.5 3H17.5A1.5 1.5 0 0 1 19 4.5V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V4.5A1.5 1.5 0 0 1 6.5 3ZM9.4 8.3L12 10.9 14.6 8.3 15.7 9.4 13.1 12 15.7 14.6 14.6 15.7 12 13.1 9.4 15.7 8.3 14.6 10.9 12 8.3 9.4Z")
    val Lines by icon("M4 7H20 M4 12H20 M4 17H14")
    val Resize by icon("M4 8.5V5.5A1.5 1.5 0 0 1 5.5 4H8.5 M15.5 4H18.5A1.5 1.5 0 0 1 20 5.5V8.5 M20 15.5V18.5A1.5 1.5 0 0 1 18.5 20H15.5 M8.5 20H5.5A1.5 1.5 0 0 1 4 18.5V15.5 M9 15L15 9 M11.5 9H15V12.5")
    val Timer by icon("M9.5 2.5H14.5", "M4 13.5A8 8 0 1 0 20 13.5A8 8 0 1 0 4 13.5ZM11.2 9H12.8V13.1L15.4 14.7 14.6 16 11.2 14Z")
    val Home by icon("", "M12 3L21.5 11.5H18.5V20.5H5.5V11.5H2.5Z M10 14H14V20.5H10Z")

    // Navigation and playback
    val Forward by icon("M13 5L20 12 13 19 M20 12H4", "", 20f, mirror = true)
    val ChevronLeft by icon("M14.5 6L8.5 12 14.5 18", "", 12f, mirror = true)
    val DropDown by icon("", "M6 9.5H18L12 16Z", 12f)
    val Unfold by icon("M8 9L12 5 16 9 M8 15L12 19 16 15", "", 16f)
    val Return by icon("M20 5V11A2 2 0 0 1 18 13H5 M9 9L5 13 9 17", "", 20f, mirror = true)
    val Tab by icon("M4 12H15 M11 8L15 12 11 16 M19.5 6V18", "", 20f, mirror = true)
    val Menu by icon("M4 6.5H20 M4 12H20 M4 17.5H20")
    val MenuOpen by icon("M4 6.5H15 M4 12H13 M4 17.5H15 M20 8.5L16.5 12 20 15.5", "", 20f, mirror = true)
    val OpenInNew by icon("M11 5H6.5A1.5 1.5 0 0 0 5 6.5V17.5A1.5 1.5 0 0 0 6.5 19H17.5A1.5 1.5 0 0 0 19 17.5V13 M14 4H20V10 M20 4L11.5 12.5", "", 20f, mirror = true)
    val Login by icon("M14 4H18A1.5 1.5 0 0 1 19.5 5.5V18.5A1.5 1.5 0 0 1 18 20H14 M3.5 12H14 M10 8L14 12 10 16", "", 20f, mirror = true)
    val Logout by icon("M10 4H6A1.5 1.5 0 0 0 4.5 5.5V18.5A1.5 1.5 0 0 0 6 20H10 M10 12H20.5 M16.5 8L20.5 12 16.5 16", "", 20f, mirror = true)
    val Fullscreen by icon("M4 8.5V5.5A1.5 1.5 0 0 1 5.5 4H8.5 M15.5 4H18.5A1.5 1.5 0 0 1 20 5.5V8.5 M20 15.5V18.5A1.5 1.5 0 0 1 18.5 20H15.5 M8.5 20H5.5A1.5 1.5 0 0 1 4 18.5V15.5")
    val FullscreenExit by icon("M8.5 4V8.5H4 M15.5 4V8.5H20 M20 15.5H15.5V20 M4 15.5H8.5V20")
    val ZoomIn by icon("M4 10.5A6.5 6.5 0 1 0 17 10.5A6.5 6.5 0 1 0 4 10.5Z M15.3 15.3L20 20 M10.5 7.5V13.5 M7.5 10.5H13.5")
    val SearchOff by icon("M4 10.5A6.5 6.5 0 1 0 17 10.5A6.5 6.5 0 1 0 4 10.5Z M15.3 15.3L20 20 M8.5 8.5L12.5 12.5 M12.5 8.5L8.5 12.5")
    val AlignTop by icon("M4 4H20 M12 20V8.5 M8 12.5L12 8.5 16 12.5")
    val AlignBottom by icon("M4 20H20 M12 4V15.5 M8 11.5L12 15.5 16 11.5")
    val SwapVertical by icon("M8 4V20 M4.5 7.5L8 4 11.5 7.5 M16 20V4 M12.5 16.5L16 20 19.5 16.5")
    val SwapHorizontal by icon("M4 8H20 M16.5 4.5L20 8 16.5 11.5 M20 16H4 M7.5 12.5L4 16 7.5 19.5")
    val Merge by icon("M6 4V7.5C6 11.5 12 11.5 12 15.5V20 M18 4V7.5C18 11.5 12 11.5 12 15.5 M9 17L12 20 15 17")
    val Play by icon("", "M7 4.5V19.5A1 1 0 0 0 8.5 20.3L20 12.8A1 1 0 0 0 20 11.2L8.5 3.7A1 1 0 0 0 7 4.5Z", 16f)
    val Stop by icon("", "M7 5H17A2 2 0 0 1 19 7V17A2 2 0 0 1 17 19H7A2 2 0 0 1 5 17V7A2 2 0 0 1 7 5Z", 14f)
    val SkipNext by icon("", "M5 5L15 12 5 19Z M16.5 5H19V19H16.5Z", 16f)
    val Shuffle by icon("M3 7H6.5C9.5 7 12.5 17 16 17H20.5 M3 17H6.5C8 17 9 15.5 10 14 M14 10C14.8 8.5 15.5 7 17 7H20.5 M18 4.5L20.5 7 18 9.5 M18 14.5L20.5 17 18 19.5")
    val Repeat by icon("M17 3.5L20 6.5 17 9.5 M4 11V9.5A3 3 0 0 1 7 6.5H20 M7 20.5L4 17.5 7 14.5 M20 13V14.5A3 3 0 0 1 17 17.5H4")
    val Sync by icon("M19 8A8 8 0 0 0 5.1 9.5 M5 16A8 8 0 0 0 18.9 14.5 M19.5 4V8.5H15 M4.5 20V15.5H9")
    val History by icon("M4.27 9.93A8 8 0 1 1 6.34 17.66 M4.2 4.5V10H9.7 M12 8V12.5L15 14.5")
    val Volume by icon("M15.5 9A4.2 4.2 0 0 1 15.5 15 M18 6A8.5 8.5 0 0 1 18 18", "M3.5 9.5H7.5L12.5 5V19L7.5 14.5H3.5Z", 20f, mirror = true)
    val VolumeOff by icon("M16 9.5L21 14.5 M21 9.5L16 14.5", "M3.5 9.5H7.5L12.5 5V19L7.5 14.5H3.5Z", 20f, mirror = true)

    // Status
    val CheckCircle by icon("", "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12ZM7.2 12.4L8.6 11 10.8 13.2 15.6 8.4 17 9.8 10.8 16Z")
    val CheckCircleOutline by icon("M3.5 12A8.5 8.5 0 1 0 20.5 12A8.5 8.5 0 1 0 3.5 12Z M8 12.3L10.8 15 16 9.5")
    val MinusCircle by icon("M3.5 12A8.5 8.5 0 1 0 20.5 12A8.5 8.5 0 1 0 3.5 12Z M8 12H16")
    val PauseCircle by icon("", "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12ZM9 8H10.8V16H9ZM13.2 8H15V16H13.2Z")
    val Pending by icon("", "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12ZM6.8 12A1.2 1.2 0 1 0 9.2 12A1.2 1.2 0 1 0 6.8 12ZM10.8 12A1.2 1.2 0 1 0 13.2 12A1.2 1.2 0 1 0 10.8 12ZM14.8 12A1.2 1.2 0 1 0 17.2 12A1.2 1.2 0 1 0 14.8 12Z")
    val Info by icon("", "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12ZM11.1 10.8H12.9V17H11.1ZM10.85 7.8A1.15 1.15 0 1 0 13.15 7.8A1.15 1.15 0 1 0 10.85 7.8Z")
    val Help by icon("M3.5 12A8.5 8.5 0 1 0 20.5 12A8.5 8.5 0 1 0 3.5 12Z M9.5 9.5A2.5 2.5 0 1 1 13.2 11.8C12.4 12.3 12 12.9 12 13.8", "M10.9 16.8A1.1 1.1 0 1 0 13.1 16.8A1.1 1.1 0 1 0 10.9 16.8Z")
    val Warning by icon("", "M12 3L22 20.5H2Z M11.1 9.5H12.9V15H11.1ZM10.9 17.3A1.1 1.1 0 1 0 13.1 17.3A1.1 1.1 0 1 0 10.9 17.3Z")
    val Priority by icon("", "M12 4H12A1.3 1.3 0 0 1 13.3 5.3V13.7A1.3 1.3 0 0 1 12 15H12A1.3 1.3 0 0 1 10.7 13.7V5.3A1.3 1.3 0 0 1 12 4ZM10.4 19A1.6 1.6 0 1 0 13.6 19A1.6 1.6 0 1 0 10.4 19Z", 16f)
    val CheckAll by icon("M2.5 12.5L6.5 16.5 M11.5 11.5L15 8 M8.5 12.5L12.5 16.5 21 8")
    val AddTask by icon("M12.5 3.5A8.5 8.5 0 1 0 20.5 11.5 M8 12.3L10.8 15 15 10.5 M19 2.5V8.5 M16 5.5H22")
    val Checklist by icon("M3.5 6.5L5.5 8.5 8.5 4.5 M3.5 15.5L5.5 17.5 8.5 13.5 M12 7H20.5 M12 16H20.5", "", 20f, mirror = true)
    val Clock by icon("", "M3 12A9 9 0 1 0 21 12A9 9 0 1 0 3 12ZM11.2 7H12.8V11.6L15.7 13.4 14.9 14.7 11.2 12.5Z")
    val ClockAdd by icon("M4 13.5A7 7 0 1 0 18 13.5A7 7 0 1 0 4 13.5Z M11 10V13.5L13.5 15 M19 2.5V7.5 M16.5 5H21.5")
    val Hourglass by icon("", "M6.5 3H17.5V7L13.2 12 17.5 17V21H6.5V17L10.8 12 6.5 7Z")
    val HourglassEmpty by icon("M6.5 3H17.5V7L13.2 12 17.5 17V21H6.5V17L10.8 12 6.5 7Z")
    val Speed by icon("M5.2 18A9 9 0 1 1 18.8 18", "M11 14.5L16.5 8 13.4 15.9A1.6 1.6 0 0 1 11 14.5Z")
    val Flame by icon("", "M12.5 2.5C13 6 17.5 8.5 17.5 14A5.5 5.5 0 0 1 6.5 14C6.5 11.5 7.8 9.8 9 8.5 9.3 10.3 10 11 11 11.5 10.5 8 11 4.5 12.5 2.5Z")
    val Trophy by icon("M7 5.5H4.5V7.5A3 3 0 0 0 7 10.4 M17 5.5H19.5V7.5A3 3 0 0 1 17 10.4", "M7 3.5H17V10A5 5 0 0 1 7 10Z M11 14.5H13V18H11ZM8.3 18H15.7A0.8 0.8 0 0 1 16.5 18.8V19.7A0.8 0.8 0 0 1 15.7 20.5H8.3A0.8 0.8 0 0 1 7.5 19.7V18.8A0.8 0.8 0 0 1 8.3 18Z")
    val Stoplight by icon("", "M10 2.5H14A2.5 2.5 0 0 1 16.5 5V19A2.5 2.5 0 0 1 14 21.5H10A2.5 2.5 0 0 1 7.5 19V5A2.5 2.5 0 0 1 10 2.5ZM10.2 7A1.8 1.8 0 1 0 13.8 7A1.8 1.8 0 1 0 10.2 7ZM10.2 12A1.8 1.8 0 1 0 13.8 12A1.8 1.8 0 1 0 10.2 12ZM10.2 17A1.8 1.8 0 1 0 13.8 17A1.8 1.8 0 1 0 10.2 17Z")
    val Idea by icon("", "M12 2.5A6.5 6.5 0 0 0 8.2 14.3C8.8 14.8 9 15.3 9 16H15C15 15.3 15.2 14.8 15.8 14.3A6.5 6.5 0 0 0 12 2.5Z M9.8 17.2H14.2A0.8 0.8 0 0 1 15 18V18.2A0.8 0.8 0 0 1 14.2 19H9.8A0.8 0.8 0 0 1 9 18.2V18A0.8 0.8 0 0 1 9.8 17.2ZM10.2 20H13.8V21.5H10.2Z")
    val Sun by icon("M12 2.5V4.5 M12 19.5V21.5 M2.5 12H4.5 M19.5 12H21.5 M5.3 5.3L6.7 6.7 M17.3 17.3L18.7 18.7 M5.3 18.7L6.7 17.3 M17.3 6.7L18.7 5.3", "M7.5 12A4.5 4.5 0 1 0 16.5 12A4.5 4.5 0 1 0 7.5 12Z")
    val Moon by icon("", "M20 14.5A8.5 8.5 0 1 1 9.5 4A7 7 0 0 0 20 14.5Z")

    // Files, folders and cloud
    val File by icon("", "M6.5 3H14L19 8V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V4.5A1.5 1.5 0 0 1 6.5 3Z M13.4 4.6L17.4 8.6H13.4Z")
    val Pdf by icon("", "M6.5 3H14L19 8V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V4.5A1.5 1.5 0 0 1 6.5 3Z M13.4 4.6L17.4 8.6H13.4ZM7.5 12.5H16.5V17H7.5Z")
    val FindInPage by icon("", "M6.5 3H14L19 8V19.5A1.5 1.5 0 0 1 17.5 21H6.5A1.5 1.5 0 0 1 5 19.5V4.5A1.5 1.5 0 0 1 6.5 3Z M13.4 4.6L17.4 8.6H13.4ZM8.3 13.5A3.2 3.2 0 1 0 14.7 13.5A3.2 3.2 0 1 0 8.3 13.5ZM9.7 13.5A1.8 1.8 0 1 0 13.3 13.5A1.8 1.8 0 1 0 9.7 13.5ZM13.4 16.4L14.4 15.4 17 18 16 19Z")
    val Folder by icon("", "M3 6.5A1.5 1.5 0 0 1 4.5 5H9.5L11.5 7H19.5A1.5 1.5 0 0 1 21 8.5V17.5A1.5 1.5 0 0 1 19.5 19H4.5A1.5 1.5 0 0 1 3 17.5Z")
    val FolderOpen by icon("M3 10V7Q3 5 5 5H9L12 8H19 M3 10H21L19 19H5Z")
    val FolderAdd by icon("", "M3 6.5A1.5 1.5 0 0 1 4.5 5H9.5L11.5 7H19.5A1.5 1.5 0 0 1 21 8.5V17.5A1.5 1.5 0 0 1 19.5 19H4.5A1.5 1.5 0 0 1 3 17.5ZM14.2 10H15.8V12.2H18V13.8H15.8V16H14.2V13.8H12V12.2H14.2Z")
    val FolderOff by icon("M3 6.5A1.5 1.5 0 0 1 4.5 5H9.5L11.5 7H19.5A1.5 1.5 0 0 1 21 8.5V17.5A1.5 1.5 0 0 1 19.5 19H4.5A1.5 1.5 0 0 1 3 17.5Z M4 2.5L21 21.5")
    val Archive by icon("", "M4 4H20A1 1 0 0 1 21 5V7.5A1 1 0 0 1 20 8.5H4A1 1 0 0 1 3 7.5V5A1 1 0 0 1 4 4ZM4.5 10H19.5V19A1.5 1.5 0 0 1 18 20.5H6A1.5 1.5 0 0 1 4.5 19Z M9.5 12.5H14.5V14.2H9.5Z")
    val Save by icon("", "M5 3.5H16L20.5 8V19A1.5 1.5 0 0 1 19 20.5H5A1.5 1.5 0 0 1 3.5 19V5A1.5 1.5 0 0 1 5 3.5Z M7 5.5H14V9H7ZM9.4 15A2.6 2.6 0 1 0 14.6 15A2.6 2.6 0 1 0 9.4 15Z")
    val Storage by icon("", "M4.5 3.5H19.5A1 1 0 0 1 20.5 4.5V7.5A1 1 0 0 1 19.5 8.5H4.5A1 1 0 0 1 3.5 7.5V4.5A1 1 0 0 1 4.5 3.5ZM5.5 6A1 1 0 1 0 7.5 6A1 1 0 1 0 5.5 6ZM4.5 9.5H19.5A1 1 0 0 1 20.5 10.5V13.5A1 1 0 0 1 19.5 14.5H4.5A1 1 0 0 1 3.5 13.5V10.5A1 1 0 0 1 4.5 9.5ZM5.5 12A1 1 0 1 0 7.5 12A1 1 0 1 0 5.5 12ZM4.5 15.5H19.5A1 1 0 0 1 20.5 16.5V19.5A1 1 0 0 1 19.5 20.5H4.5A1 1 0 0 1 3.5 19.5V16.5A1 1 0 0 1 4.5 15.5ZM5.5 18A1 1 0 1 0 7.5 18A1 1 0 1 0 5.5 18Z")
    val Download by icon("M12 3.5V15 M7 10.5L12 15.5 17 10.5 M4.5 20H19.5")
    val CloudOff by icon("M7 19A4.5 4.5 0 0 1 6.3 10.1A6 6 0 0 1 17.8 9.2A5 5 0 0 1 17.5 19Z M4 3L20 21.5")
    val CloudUpload by icon("", "M7 19A4.5 4.5 0 0 1 6.3 10.1A6 6 0 0 1 17.8 9.2A5 5 0 0 1 17.5 19ZM12 10L15.5 13.5H13V17H11V13.5H8.5Z")
    val CloudDone by icon("", "M7 19A4.5 4.5 0 0 1 6.3 10.1A6 6 0 0 1 17.8 9.2A5 5 0 0 1 17.5 19ZM8.6 13.6L9.8 12.4 11.3 13.9 14.6 10.6 15.8 11.8 11.3 16.3Z")
    val CloudSync by icon("", "M7 19A4.5 4.5 0 0 1 6.3 10.1A6 6 0 0 1 17.8 9.2A5 5 0 0 1 17.5 19ZM9 13.5A3 3 0 1 0 15 13.5A3 3 0 1 0 9 13.5ZM10.5 13.5A1.5 1.5 0 1 0 13.5 13.5A1.5 1.5 0 1 0 10.5 13.5Z")
    val DeleteForever by icon("", "M4.5 5.5H9L10 4H14L15 5.5H19.5V7.5H4.5Z M6 9H18L17.2 19.6A1.5 1.5 0 0 1 15.7 21H8.3A1.5 1.5 0 0 1 6.8 19.6Z M9.9 11.6L12 13.7 14.1 11.6 15.1 12.6 13 14.7 15.1 16.8 14.1 17.8 12 15.7 9.9 17.8 8.9 16.8 11 14.7 8.9 12.6Z")
    val DeleteSweep by icon("M15.5 8H21 M15.5 12H20 M15.5 16H18.5", "M2.5 6H5.5L6.2 5H9.8L10.5 6H13.5V7.6H2.5Z M3.5 9H12.5L12 18A1.3 1.3 0 0 1 10.7 19.2H5.3A1.3 1.3 0 0 1 4 18Z")
    val Restore by icon("", "M4.5 5.5H9L10 4H14L15 5.5H19.5V7.5H4.5Z M6 9H18L17.2 19.6A1.5 1.5 0 0 1 15.7 21H8.3A1.5 1.5 0 0 1 6.8 19.6Z M12 11.5L15 14.5H12.9V18H11.1V14.5H9Z")
    val Link by icon("M10 14A4 4 0 0 0 15.7 14L18.8 10.9A4 4 0 0 0 13.1 5.2L11.8 6.5 M14 10A4 4 0 0 0 8.3 10L5.2 13.1A4 4 0 0 0 10.9 18.8L12.2 17.5")
    val LinkOff by icon("M10 14A4 4 0 0 0 15.7 14L18.8 10.9A4 4 0 0 0 13.1 5.2L11.8 6.5 M14 10A4 4 0 0 0 8.3 10L5.2 13.1A4 4 0 0 0 10.9 18.8L12.2 17.5 M4 4L20 20")

    // Content
    val Calendar by icon("M8 3V6.5 M16 3V6.5", "M5 5H19A1.5 1.5 0 0 1 20.5 6.5V19.5A1.5 1.5 0 0 1 19 21H5A1.5 1.5 0 0 1 3.5 19.5V6.5A1.5 1.5 0 0 1 5 5ZM5.5 9H18.5V10.3H5.5ZM7 13H9V15H7ZM11 13H13V15H11ZM15 13H17V15H15ZM7 16.5H9V18.5H7ZM11 16.5H13V18.5H11Z")
    val Today by icon("M8 3V6.5 M16 3V6.5", "M5 5H19A1.5 1.5 0 0 1 20.5 6.5V19.5A1.5 1.5 0 0 1 19 21H5A1.5 1.5 0 0 1 3.5 19.5V6.5A1.5 1.5 0 0 1 5 5ZM5.5 9H18.5V10.3H5.5ZM7.5 12.5H12V17H7.5Z")
    val CalendarCheck by icon("M8 3V6.5 M16 3V6.5", "M5 5H19A1.5 1.5 0 0 1 20.5 6.5V19.5A1.5 1.5 0 0 1 19 21H5A1.5 1.5 0 0 1 3.5 19.5V6.5A1.5 1.5 0 0 1 5 5ZM5.5 9H18.5V10.3H5.5ZM8.4 14.9L9.6 13.7 11.3 15.4 14.8 11.9 16 13.1 11.3 17.8Z")
    val CalendarOff by icon("M8 3V6.5 M16 3V6.5", "M5 5H19A1.5 1.5 0 0 1 20.5 6.5V19.5A1.5 1.5 0 0 1 19 21H5A1.5 1.5 0 0 1 3.5 19.5V6.5A1.5 1.5 0 0 1 5 5ZM5.5 9H18.5V10.3H5.5ZM10 12.4L12 14.4 14 12.4 15 13.4 13 15.4 15 17.4 14 18.4 12 16.4 10 18.4 9 17.4 11 15.4 9 13.4Z")
    val BrokenImage by icon("", "M4.5 4H19.5A1.5 1.5 0 0 1 21 5.5V18.5A1.5 1.5 0 0 1 19.5 20H4.5A1.5 1.5 0 0 1 3 18.5V5.5A1.5 1.5 0 0 1 4.5 4ZM3 11.2L8 12.7 12 10.2 16 12.7 21 10.7V12.6L16 14.6 12 12.1 8 14.6 3 13.1Z")
    val ImageAdd by icon("M20 2.5V8.5 M17 5.5H23", "M4 7H15.5A1.5 1.5 0 0 1 17 8.5V20A1.5 1.5 0 0 1 15.5 21.5H4A1.5 1.5 0 0 1 2.5 20V8.5A1.5 1.5 0 0 1 4 7ZM4.8 18.5L8.3 14 10.8 17 12.8 14.5 15 18.5Z")
    val List by icon("", "M4 5H7V8H4ZM4 10.5H7V13.5H4ZM4 16H7V19H4ZM10 5H19A1 1 0 0 1 20 6V7A1 1 0 0 1 19 8H10A1 1 0 0 1 9 7V6A1 1 0 0 1 10 5ZM10 10.5H19A1 1 0 0 1 20 11.5V12.5A1 1 0 0 1 19 13.5H10A1 1 0 0 1 9 12.5V11.5A1 1 0 0 1 10 10.5ZM10 16H19A1 1 0 0 1 20 17V18A1 1 0 0 1 19 19H10A1 1 0 0 1 9 18V17A1 1 0 0 1 10 16Z", 20f, mirror = true)
    val ListAdd by icon("M4 7H14 M4 12H14 M4 17H10 M17.5 13V20 M14 16.5H21", "", 20f, mirror = true)
    val Bullets by icon("M9.5 6.5H20 M9.5 12H20 M9.5 17.5H20", "M3.4 6.5A1.6 1.6 0 1 0 6.6 6.5A1.6 1.6 0 1 0 3.4 6.5ZM3.4 12A1.6 1.6 0 1 0 6.6 12A1.6 1.6 0 1 0 3.4 12ZM3.4 17.5A1.6 1.6 0 1 0 6.6 17.5A1.6 1.6 0 1 0 3.4 17.5Z", 20f, mirror = true)
    val Numbered by icon("M11 7H20 M11 17H20 M4 5L6 4V10 M4 10H8 M4 15Q4 13 6 13Q8 13 8 15Q8 16 4 20H8", "", 20f, mirror = true)
    val Sort by icon("M4 7H20 M4 12H15 M4 17H10", "", 20f, mirror = true)
    val Filter by icon("M4 7H20 M7 12H17 M10 17H14")
    val FilterOff by icon("M9 7H20 M7 12H17 M10 17H14 M4 3.5L20 20.5")
    val AlignLeft by icon("M4 7H20 M4 12H14 M4 17H20")
    val AlignCenter by icon("M4 7H20 M7 12H17 M4 17H20")
    val AlignRight by icon("M4 7H20 M10 12H20 M4 17H20")
    val Wrap by icon("M4 6H20 M4 12H16.5A3 3 0 0 1 16.5 18H12 M14 15.5L11.5 18 14 20.5 M4 18H8", "", 20f, mirror = true)
    val Bold by icon("", "M7 4H13A4 4 0 0 1 15.6 11.1A4.4 4.4 0 0 1 13.6 20H7Z M9.8 6.6V10.4H12.8A1.9 1.9 0 0 0 12.8 6.6Z M9.8 12.9V17.4H13.3A2.25 2.25 0 0 0 13.3 12.9Z", 16f)
    val Italic by icon("M10 4.5H17 M7 19.5H14 M14 4.5L10 19.5", "", 16f)
    val Underline by icon("M7 4V11A5 5 0 0 0 17 11V4 M5 20H19", "", 16f)
    val Sigma by icon("M17.5 6.5V5H6.5L12.5 12 6.5 19H17.5V17.5", "", 16f)
    val Hash by icon("M9.5 4L7.5 20 M16.5 4L14.5 20 M4.5 9H20 M4 15H19.5")
    val At by icon("M8.5 12A3.5 3.5 0 1 0 15.5 12A3.5 3.5 0 1 0 8.5 12Z M15.5 8.5V13A2.5 2.5 0 0 0 20.5 13V12A8.5 8.5 0 1 0 16.5 19.2")
    val Braces by icon("M9 4C7 4 6.5 5 6.5 6.5V9.5C6.5 11 5.5 12 4.5 12 5.5 12 6.5 13 6.5 14.5V17.5C6.5 19 7 20 9 20 M15 4C17 4 17.5 5 17.5 6.5V9.5C17.5 11 18.5 12 19.5 12 18.5 12 17.5 13 17.5 14.5V17.5C17.5 19 17 20 15 20")
    val EditNote by icon("M4 7H14 M4 12H11 M4 17H9", "M12.5 20.5L13.2 17.6 19 11.8 21.2 14 15.4 19.8Z", 20f, mirror = true)
    val Grading by icon("M4 6H14 M4 10.5H14 M4 15H9 M12.5 17.5L15 20 20.5 14", "", 20f, mirror = true)
    val FactCheck by icon("", "M4.5 4.5H19.5A1.5 1.5 0 0 1 21 6V18A1.5 1.5 0 0 1 19.5 19.5H4.5A1.5 1.5 0 0 1 3 18V6A1.5 1.5 0 0 1 4.5 4.5ZM5.8 12L6.9 10.9 8.4 12.4 11.2 9.6 12.3 10.7 8.4 14.6ZM14 9H18.5V10.4H14ZM14 13H18.5V14.4H14Z")
    val BookmarkAdd by icon("", "M7 3H17A1.5 1.5 0 0 1 18.5 4.5V21L12 16.5 5.5 21V4.5A1.5 1.5 0 0 1 7 3ZM11.2 6.5H12.8V8.7H15V10.3H12.8V12.5H11.2V10.3H9V8.7H11.2Z")
    val Bookmarks by icon("M9.5 3H18.5A1.5 1.5 0 0 1 20 4.5V16.5", "M5.5 7H14.5A1.5 1.5 0 0 1 16 8.5V21.5L10 17.8 4 21.5V8.5A1.5 1.5 0 0 1 5.5 7Z")
    val Tag by icon("", "M3.5 4.5A1 1 0 0 1 4.5 3.5H11.5L20.8 12.8A1.5 1.5 0 0 1 20.8 14.9L14.9 20.8A1.5 1.5 0 0 1 12.8 20.8L3.5 11.5Z M6 7.5A1.5 1.5 0 1 0 9 7.5A1.5 1.5 0 1 0 6 7.5Z")
    val Pin by icon("", "M9 3H15V4.5L14 5.5V10.5L17 13.5V15H12.9V21L12 22 11.1 21V15H7V13.5L10 10.5V5.5L9 4.5Z")
    val Palette by icon("", "M12 3A9 9 0 1 0 12 21C13.2 21 14 20.2 14 19.2 14 18.2 13.3 17.8 13.3 17 13.3 16.2 14 15.5 15 15.5H17A4 4 0 0 0 21 11.5C21 6.8 17 3 12 3Z M6.2 11.5A1.3 1.3 0 1 0 8.8 11.5A1.3 1.3 0 1 0 6.2 11.5ZM8.7 7.5A1.3 1.3 0 1 0 11.3 7.5A1.3 1.3 0 1 0 8.7 7.5ZM13.2 7.5A1.3 1.3 0 1 0 15.8 7.5A1.3 1.3 0 1 0 13.2 7.5ZM16.1 11A1.2 1.2 0 1 0 18.5 11A1.2 1.2 0 1 0 16.1 11Z")
    val Music by icon("", "M10 4.5L19 3V15.5A3 3 0 1 1 17.2 12.75V6.6L11.8 7.5V17.5A3 3 0 1 1 10 14.75Z")
    val QueueMusic by icon("M3.5 6.5H14 M3.5 11H14 M3.5 15.5H9", "M15.5 9.5L21 8.5V10.3L17.3 11V17.6A2.6 2.6 0 1 1 15.5 15.2Z")
    val MusicLibrary by icon("M3 7V19.5A1.5 1.5 0 0 0 4.5 21H17", "M7.5 3H19.5A1.5 1.5 0 0 1 21 4.5V16.5A1.5 1.5 0 0 1 19.5 18H7.5A1.5 1.5 0 0 1 6 16.5V4.5A1.5 1.5 0 0 1 7.5 3ZM12.5 6.5L17 5.7V7.3L14 7.9V12.6A2 2 0 1 1 12.5 10.9Z")
    val MailRead by icon("", "M4.5 5H19.5A1.5 1.5 0 0 1 21 6.5V17.5A1.5 1.5 0 0 1 19.5 19H4.5A1.5 1.5 0 0 1 3 17.5V6.5A1.5 1.5 0 0 1 4.5 5ZM5 8.2L12 13 19 8.2V10L12 14.8 5 10Z")

    // Charts and layout
    val Chart by icon("M4 4V20H20 M7.5 15.5L11 11.5 14 14 19 8")
    val Insights by icon("M3 17.5L8.5 12 12.5 16 20 7.5", "M6.5 12A2 2 0 1 0 10.5 12A2 2 0 1 0 6.5 12ZM10.5 16A2 2 0 1 0 14.5 16A2 2 0 1 0 10.5 16ZM18 7.5A2 2 0 1 0 22 7.5A2 2 0 1 0 18 7.5ZM1 17.5A2 2 0 1 0 5 17.5A2 2 0 1 0 1 17.5Z")
    val TrendUp by icon("M3 17L9.5 10.5 13.5 14.5 21 7 M15.5 7H21V12.5")
    val TrendDown by icon("M3 7L9.5 13.5 13.5 9.5 21 17 M15.5 17H21V11.5")
    val BarChart by icon("", "M4.8 11H7.2A0.8 0.8 0 0 1 8 11.8V19.2A0.8 0.8 0 0 1 7.2 20H4.8A0.8 0.8 0 0 1 4 19.2V11.8A0.8 0.8 0 0 1 4.8 11ZM10.8 4H13.2A0.8 0.8 0 0 1 14 4.8V19.2A0.8 0.8 0 0 1 13.2 20H10.8A0.8 0.8 0 0 1 10 19.2V4.8A0.8 0.8 0 0 1 10.8 4ZM16.8 8H19.2A0.8 0.8 0 0 1 20 8.8V19.2A0.8 0.8 0 0 1 19.2 20H16.8A0.8 0.8 0 0 1 16 19.2V8.8A0.8 0.8 0 0 1 16.8 8Z")
    val Route by icon("M6 17.5V9A3 3 0 0 1 12 9V15A3 3 0 0 0 18 15V6.5", "M3.8 19A2.2 2.2 0 1 0 8.2 19A2.2 2.2 0 1 0 3.8 19ZM15.8 5A2.2 2.2 0 1 0 20.2 5A2.2 2.2 0 1 0 15.8 5Z")
    val Infinity by icon("M12 12C10 9 8.5 7.5 6.5 7.5A4.5 4.5 0 0 0 6.5 16.5C8.5 16.5 10 15 12 12S15.5 7.5 17.5 7.5A4.5 4.5 0 0 1 17.5 16.5C15.5 16.5 14 15 12 12Z")
    val Carousel by icon("", "M8.2 5H15.8A1.2 1.2 0 0 1 17 6.2V17.8A1.2 1.2 0 0 1 15.8 19H8.2A1.2 1.2 0 0 1 7 17.8V6.2A1.2 1.2 0 0 1 8.2 5ZM3.3 7H4.7A0.8 0.8 0 0 1 5.5 7.8V16.2A0.8 0.8 0 0 1 4.7 17H3.3A0.8 0.8 0 0 1 2.5 16.2V7.8A0.8 0.8 0 0 1 3.3 7ZM19.3 7H20.7A0.8 0.8 0 0 1 21.5 7.8V16.2A0.8 0.8 0 0 1 20.7 17H19.3A0.8 0.8 0 0 1 18.5 16.2V7.8A0.8 0.8 0 0 1 19.3 7Z")
    val Agenda by icon("", "M5.2 4H18.8A1.2 1.2 0 0 1 20 5.2V9.8A1.2 1.2 0 0 1 18.8 11H5.2A1.2 1.2 0 0 1 4 9.8V5.2A1.2 1.2 0 0 1 5.2 4ZM5.2 13H18.8A1.2 1.2 0 0 1 20 14.2V18.8A1.2 1.2 0 0 1 18.8 20H5.2A1.2 1.2 0 0 1 4 18.8V14.2A1.2 1.2 0 0 1 5.2 13Z")
    val Dashboard by icon("", "M4.7 3.5H9.8A1.2 1.2 0 0 1 11 4.7V19.3A1.2 1.2 0 0 1 9.8 20.5H4.7A1.2 1.2 0 0 1 3.5 19.3V4.7A1.2 1.2 0 0 1 4.7 3.5ZM14.2 3.5H19.3A1.2 1.2 0 0 1 20.5 4.7V8.8A1.2 1.2 0 0 1 19.3 10H14.2A1.2 1.2 0 0 1 13 8.8V4.7A1.2 1.2 0 0 1 14.2 3.5ZM14.2 12H19.3A1.2 1.2 0 0 1 20.5 13.2V19.3A1.2 1.2 0 0 1 19.3 20.5H14.2A1.2 1.2 0 0 1 13 19.3V13.2A1.2 1.2 0 0 1 14.2 12Z")

    // People, devices and settings
    val School by icon("M21.5 9.5V16", "M12 3L1.5 9 12 15 22.5 9Z M6 13.3V17C6 19 9 20.5 12 20.5S18 19 18 17V13.3L12 16.7Z")
    val Account by icon("", "M2.5 12A9.5 9.5 0 1 0 21.5 12A9.5 9.5 0 1 0 2.5 12ZM9 9.3A3 3 0 1 0 15 9.3A3 3 0 1 0 9 9.3ZM6.2 17.6C7.5 15.6 9.6 14.6 12 14.6S16.5 15.6 17.8 17.6C16.4 19.2 14.3 20.2 12 20.2S7.6 19.2 6.2 17.6Z")
    val Person by icon("", "M8 7.5A4 4 0 1 0 16 7.5A4 4 0 1 0 8 7.5ZM4 20.5C4 16.5 7.5 14 12 14S20 16.5 20 20.5Z")
    val People by icon("", "M5.7 8A3.3 3.3 0 1 0 12.3 8A3.3 3.3 0 1 0 5.7 8ZM2.5 19.5C2.5 16.3 5.3 14 9 14S15.5 16.3 15.5 19.5ZM14.6 8.8A2.6 2.6 0 1 0 19.8 8.8A2.6 2.6 0 1 0 14.6 8.8ZM16.6 14.2C19.6 14.2 21.8 16.3 21.8 19.5H17.3C17.3 17.4 17.2 15.6 16.6 14.2Z")
    val Tablet by icon("", "M6 3H18A2 2 0 0 1 20 5V19A2 2 0 0 1 18 21H6A2 2 0 0 1 4 19V5A2 2 0 0 1 6 3ZM6 5.5H18V17H6Z")
    val Phone by icon("", "M9 2.5H15A2 2 0 0 1 17 4.5V19.5A2 2 0 0 1 15 21.5H9A2 2 0 0 1 7 19.5V4.5A2 2 0 0 1 9 2.5ZM8.8 5.5H15.2V17.5H8.8Z")
    val KeyboardHide by icon("", "M4 2.5H20A1.5 1.5 0 0 1 21.5 4V13A1.5 1.5 0 0 1 20 14.5H4A1.5 1.5 0 0 1 2.5 13V4A1.5 1.5 0 0 1 4 2.5ZM5.5 5.5H7.2V7.2H5.5ZM9 5.5H10.7V7.2H9ZM13.3 5.5H15V7.2H13.3ZM16.8 5.5H18.5V7.2H16.8ZM8 9.8H16V11.5H8ZM9 17.5H15L12 21Z")
    val Settings by icon("", "M10.11 4.95L10.47 2.32L13.53 2.32L13.89 4.95L15.65 5.68L17.76 4.07L19.93 6.24L18.32 8.35L19.05 10.11L21.68 10.47L21.68 13.53L19.05 13.89L18.32 15.65L19.93 17.76L17.76 19.93L15.65 18.32L13.89 19.05L13.53 21.68L10.47 21.68L10.11 19.05L8.35 18.32L6.24 19.93L4.07 17.76L5.68 15.65L4.95 13.89L2.32 13.53L2.32 10.47L4.95 10.11L5.68 8.35L4.07 6.24L6.24 4.07L8.35 5.68ZM8.8 12A3.2 3.2 0 1 0 15.2 12A3.2 3.2 0 1 0 8.8 12Z")

    val Move get() = Graph
    val Clipboard get() = Paste
}
