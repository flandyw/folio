package com.folio.notes.ink

import androidx.compose.ui.geometry.Rect
import com.folio.notes.editor.sticky.StickyNotes
import com.folio.notes.ink.shapes.ShapeRecognition
import com.folio.notes.model.InkPoint
import com.folio.notes.model.PageImage
import com.folio.notes.model.Paper
import com.folio.notes.model.PolygonTools
import com.folio.notes.model.SelectionStyle
import com.folio.notes.model.ShapeTools
import com.folio.notes.model.Stroke
import com.folio.notes.model.StrokeStyle
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import kotlin.math.*

/** Shared shape geometry for ink rendering and whole-stroke erasing. */
object InkGeometry {
    fun pathPoints(stroke: Stroke): List<InkPoint> {
        if (stroke.points.size < 2) return stroke.points
        // Rotated polygons store their explicit closed outline instead of two drag corners.
        if (stroke.tool in PolygonTools && stroke.points.size > 2) return stroke.points
        val a = stroke.points.first(); val b = stroke.points.last()
        return when (stroke.tool) {
            // Erased shapes become unsmoothed line paths, possibly with several vertices.
            Tool.LINE -> stroke.points
            Tool.RECTANGLE -> listOf(a, InkPoint(b.x, a.y), b, InkPoint(a.x, b.y), a)
            Tool.TRIANGLE -> polygonPoints(a, b, 3)
            Tool.DIAMOND -> polygonPoints(a, b, 4)
            Tool.PENTAGON -> polygonPoints(a, b, 5)
            Tool.HEXAGON -> polygonPoints(a, b, 6)
            Tool.STAR -> polygonPoints(a, b, 10, star = true)
            Tool.ELLIPSE -> (0..64).map { i ->
                val t = i * 2 * PI / 64
                InkPoint((a.x + b.x) / 2 + abs(b.x - a.x) / 2 * cos(t).toFloat(),
                    (a.y + b.y) / 2 + abs(b.y - a.y) / 2 * sin(t).toFloat())
            }
            else -> stroke.points
        }
    }
    /** Normalize polygons into the drag box, independent of drag direction. */
    private fun polygonPoints(a: InkPoint, b: InkPoint, vertices: Int, star: Boolean = false): List<InkPoint> {
        val unit = (0 until vertices).map { i ->
            val angle = -PI / 2 + i * 2 * PI / vertices
            val radius = if (star && i % 2 == 1) 0.42 else 1.0
            InkPoint((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
        }
        val minX = unit.minOf { it.x }; val maxX = unit.maxOf { it.x }
        val minY = unit.minOf { it.y }; val maxY = unit.maxOf { it.y }
        val left = min(a.x, b.x); val top = min(a.y, b.y)
        val points = unit.map {
            InkPoint(left + (it.x - minX) / (maxX - minX) * abs(b.x - a.x),
                top + (it.y - minY) / (maxY - minY) * abs(b.y - a.y))
        }
        return points + points.first()
    }

    /** True when [point] falls inside the freeform loop, treating the samples as a closed polygon. */
    fun lassoContains(polygon: List<InkPoint>, point: InkPoint): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            // Ray casting: odd crossings to the left mean the point is enclosed.
            if ((a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    private fun pointBounds(points: List<InkPoint>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Loop bounds as `[minX, minY, maxX, maxY]`. Hoist this once per lasso gesture: the old
     * per-stroke lookup re-walked the loop for every stroke on the page (O(N×L)).
     */
    fun lassoBounds(polygon: List<InkPoint>): FloatArray = pointBounds(polygon)

    /** A stroke is selected only when every sample is enclosed, so a half-crossed stroke stays put. */
    fun lassoSelects(polygon: List<InkPoint>, stroke: Stroke): Boolean {
        if (polygon.size < 3) return false
        return lassoSelects(polygon, pointBounds(polygon), stroke)
    }

    /**
     * [lassoSelects] with a hoisted [loop] from [lassoBounds]: a dense page pays O(L) once for
     * the loop plus O(nearby) ray casts, instead of O(N×L) loop walks.
     */
    fun lassoSelects(polygon: List<InkPoint>, loop: FloatArray, stroke: Stroke): Boolean {
        if (polygon.size < 3 || loop.size < 4) return false
        val points = pathPoints(stroke)
        if (points.isEmpty()) return false
        // Cheap box reject: a stroke fully outside the loop's bounds needs no ray casts.
        val box = pointBounds(points)
        if (box[2] < loop[0] || box[0] > loop[2] || box[3] < loop[1] || box[1] > loop[3]) return false
        return points.all { lassoContains(polygon, it) }
    }
    /**
     * True when the whole axis-aligned rectangle is inside the loop. Text boxes and pictures use
     * the same all-or-nothing rule as ink, so a half-crossed box stays put rather than half
     * selected. A degenerate loop never selects anything.
     */
    fun lassoSelectsRect(polygon: List<InkPoint>, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        if (polygon.size < 3) return false
        return lassoSelectsRect(polygon, pointBounds(polygon), left, top, right, bottom)
    }

    /** [lassoSelectsRect] with hoisted [loop] bounds, so boxes skip the loop walk and ray casts when outside. */
    fun lassoSelectsRect(polygon: List<InkPoint>, loop: FloatArray, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        if (polygon.size < 3 || loop.size < 4) return false
        if (right < loop[0] || left > loop[2] || bottom < loop[1] || top > loop[3]) return false
        return lassoContains(polygon, InkPoint(left, top)) &&
            lassoContains(polygon, InkPoint(right, top)) &&
            lassoContains(polygon, InkPoint(right, bottom)) &&
            lassoContains(polygon, InkPoint(left, bottom))
    }
    /** A text box is selected only when its whole rendered extent is enclosed. */
    fun lassoSelectsText(polygon: List<InkPoint>, box: TextBox, height: Float): Boolean =
        lassoSelectsRect(polygon, box.x, box.y, box.x + box.width, box.y + height)
    /** A text box with hoisted loop bounds. */
    fun lassoSelectsText(polygon: List<InkPoint>, loop: FloatArray, box: TextBox, height: Float): Boolean =
        lassoSelectsRect(polygon, loop, box.x, box.y, box.x + box.width, box.y + height)
    /** A picture is selected only when its whole frame is enclosed. */
    fun lassoSelectsImage(polygon: List<InkPoint>, image: PageImage): Boolean =
        lassoSelectsRect(polygon, image.x, image.y, image.x + image.width, image.y + image.height)
    /** A picture with hoisted loop bounds. */
    fun lassoSelectsImage(polygon: List<InkPoint>, loop: FloatArray, image: PageImage): Boolean =
        lassoSelectsRect(polygon, loop, image.x, image.y, image.x + image.width, image.y + image.height)
    /**
     * The bounding box of a mixed selection as `[minX, minY, maxX, maxY]`, or null when there is
     * nothing selected. [textHeight] measures each box's rendered height, which is not stored.
     */
    fun selectionBounds(
        strokes: List<Stroke>,
        texts: List<TextBox>,
        images: List<PageImage>,
        textHeight: (TextBox) -> Float,
        margin: Float = 0f
    ): FloatArray? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var any = false
        fun include(left: Float, top: Float, right: Float, bottom: Float) {
            any = true
            if (left < minX) minX = left
            if (top < minY) minY = top
            if (right > maxX) maxX = right
            if (bottom > maxY) maxY = bottom
        }
        for (stroke in strokes) {
            // Shape corners already bound the geometry, without expanding ellipses into 65 samples.
            val pts = if (stroke.tool in ShapeTools) stroke.points else pathPoints(stroke)
            for (p in pts) include(p.x, p.y, p.x, p.y)
        }
        // Cache text heights: the same box height is needed once per bounds call, not per point.
        for (box in texts) include(box.x, box.y, box.x + box.width, box.y + textHeight(box))
        images.forEach { image -> include(image.x, image.y, image.x + image.width, image.y + image.height) }
        if (!any) return null
        return floatArrayOf(minX - margin, minY - margin, maxX + margin, maxY + margin)
    }

    /** The middle of a mixed selection's bounding box, used as the pivot for rotation and resizing. */
    fun selectionCenter(
        strokes: List<Stroke>,
        texts: List<TextBox>,
        images: List<PageImage>,
        textHeight: (TextBox) -> Float
    ): InkPoint? =
        selectionBounds(strokes, texts, images, textHeight)?.let { InkPoint((it[0] + it[2]) / 2f, (it[1] + it[3]) / 2f) }

    /** Turns one point about [center] by [degrees], matching [rotate] for strokes. */
    fun rotatePoint(point: InkPoint, center: InkPoint, degrees: Float): InkPoint {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = cos(radians).toFloat(); val sin = sin(radians).toFloat()
        val dx = point.x - center.x; val dy = point.y - center.y
        return InkPoint(center.x + dx * cos - dy * sin, center.y + dx * sin + dy * cos, point.pressure)
    }

    /**
     * Moves each text box's position about [center] by [degrees]. Boxes stay axis-aligned — only
     * their place turns, never their glyphs — which is what an editor without rotated text can
     * honestly describe.
     */
    fun rotateTexts(texts: List<TextBox>, center: InkPoint, degrees: Float): List<TextBox> =
        texts.map { box ->
            val at = rotatePoint(InkPoint(box.x, box.y), center, degrees)
            box.copy(x = at.x, y = at.y)
        }

    /** Moves each picture's frame about [center] by [degrees]; the bitmap itself is never rotated. */
    fun rotateImages(images: List<PageImage>, center: InkPoint, degrees: Float): List<PageImage> =
        images.map { image ->
            val at = rotatePoint(InkPoint(image.x, image.y), center, degrees)
            image.copy(x = at.x, y = at.y)
        }

    /**
     * Grows or shrinks each text box about [center], scaling its width and type size with its
     * place so the selection stays in proportion. Sizes clamp to the same limits as the text
     * dialog, so a box can never become invisible or huge.
     */
    fun scaleTexts(texts: List<TextBox>, center: InkPoint, factor: Float): List<TextBox> =
        texts.map { box ->
            box.copy(
                x = center.x + (box.x - center.x) * factor,
                y = center.y + (box.y - center.y) * factor,
                width = (box.width * factor).coerceIn(if (box.isSticky) StickyNotes.SMALLEST_DIMENSION else TextBox.MIN_WIDTH, TextBox.MAX_WIDTH),
                size = (box.size * factor).coerceIn(TextBox.MIN_SIZE, TextBox.MAX_SIZE),
                stickyHeight = if (box.isSticky) (box.stickyHeight * factor).coerceIn(StickyNotes.SMALLEST_DIMENSION, StickyNotes.MAX_HEIGHT) else box.stickyHeight,
                stickyInk = scale(box.stickyInk, InkPoint(0f, 0f), factor)
            )
        }

    /**
     * Grows or shrinks each picture about [center], scaling its frame uniformly so the aspect
     * ratio never distorts. Frames clamp to the same limits as the resize handle.
     */
    fun scaleImages(images: List<PageImage>, center: InkPoint, factor: Float): List<PageImage> =
        images.map { image ->
            val width = (image.width * factor).coerceIn(PageImage.MIN_SIZE, PageImage.MAX_SIZE)
            val height = (image.height * factor).coerceIn(PageImage.MIN_SIZE, PageImage.MAX_SIZE)
            image.copy(
                x = center.x + (image.x - center.x) * factor,
                y = center.y + (image.y - center.y) * factor,
                width = width, height = height
            )
        }
    /** Moves every sample of a stroke without changing its tool, colour, width or pressure. */
    fun translate(stroke: Stroke, dx: Float, dy: Float): Stroke =
        stroke.copy(points = stroke.points.map { InkPoint(it.x + dx, it.y + dy, it.pressure) })

    /** The bounding box of a stroke set as `[minX, minY, maxX, maxY]`, or null when there is no ink. */
    fun bounds(strokes: List<Stroke>, margin: Float = 0f): FloatArray? =
        selectionBounds(strokes, emptyList(), emptyList(), { 0f }, margin)

    /** The middle of a stroke set's bounding box, used as the pivot for rotation and resizing. */
    fun center(strokes: List<Stroke>): InkPoint? =
        bounds(strokes)?.let { InkPoint((it[0] + it[2]) / 2f, (it[1] + it[3]) / 2f) }

    /**
     * Turns a whole stroke set about [center] by [degrees]. Polygons expand to explicit vertices
     * so their orientation survives rotation. Rectangles and ellipses are drawn between two
     * corners, which only describes them at right angles, so any other turn keeps their outline
     * as an explicit (line) path instead of re-deriving an axis-aligned shape from moved corners.
     */
    fun rotate(strokes: List<Stroke>, center: InkPoint, degrees: Float): List<Stroke> {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = cos(radians).toFloat(); val sin = sin(radians).toFloat()
        val rightAngle = abs(degrees / 90f - (degrees / 90f).roundToInt()) < 1e-4f
        return strokes.map { stroke ->
            val outline = !rightAngle && (stroke.tool == Tool.RECTANGLE || stroke.tool == Tool.ELLIPSE) && stroke.points.size >= 2
            stroke.copy(tool = if (outline) Tool.LINE else stroke.tool, points =
            (if (stroke.tool in PolygonTools || outline) pathPoints(stroke) else stroke.points).map { point ->
            val dx = point.x - center.x; val dy = point.y - center.y
            InkPoint(center.x + dx * cos - dy * sin, center.y + dx * sin + dy * cos, point.pressure)
        }) }
    }

    /** Clamp bounds shared by resizing and restyling, so ink can never become invisible or huge. */
    private const val MIN_STROKE_WIDTH = 0.5f
    private const val MAX_STROKE_WIDTH = 140f
    private const val MIN_OPACITY = 0.05f
    private const val MAX_OPACITY = 1f

    /** Resizes a stroke set about [center], scaling the drawn width so it stays in proportion. */
    fun scale(strokes: List<Stroke>, center: InkPoint, factor: Float): List<Stroke> = strokes.map { stroke ->
        stroke.copy(width = (stroke.width * factor).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH), points = stroke.points.map { point ->
            InkPoint(center.x + (point.x - center.x) * factor, center.y + (point.y - center.y) * factor, point.pressure)
        })
    }

    /**
     * Recolours, re-thickens or fades a selection without touching its geometry, tool or pressure.
     *
     * A null argument leaves that property exactly as it was, so one control can be changed on its
     * own. [widthScale] is relative rather than absolute, which keeps the thickness differences
     * inside a selection intact — a 1x scale is therefore "leave the thickness alone".
     * [style] is a line pattern and only applies to shape tools (line, rectangle, ellipse), so
     * pressure-varying ink never breaks into uneven dashes; [brush] only applies to freehand
     * pen and highlighter ink that offers it ([StrokeStyle.brushes]).
     */
    fun restyle(
        strokes: List<Stroke>,
        color: Int? = null,
        widthScale: Float? = null,
        opacity: Float? = null,
        style: StrokeStyle? = null,
        brush: StrokeStyle? = null
    ): List<Stroke> = strokes.map { stroke ->
        stroke.copy(
            color = color ?: stroke.color,
            width = widthScale?.let { (stroke.width * it).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH) } ?: stroke.width,
            opacity = opacity?.coerceIn(MIN_OPACITY, MAX_OPACITY) ?: stroke.opacity,
            style = style?.takeIf { !it.isBrush && stroke.tool in ShapeTools }
                ?: brush?.takeIf { it in StrokeStyle.brushes(stroke.tool) }
                ?: stroke.style
        )
    }

    /** The look a restyle sheet starts from, read from the first stroke of a selection. */
    fun styleOf(strokes: List<Stroke>): SelectionStyle? =
        strokes.firstOrNull()?.let { SelectionStyle(it.color, it.width, it.opacity) }

    /** Which part of a lasso selection's frame a press landed on, if any. */
    enum class SelectionHandle { NONE, RESIZE, ROTATE }

    /** Touch radius around each selection handle, in page units. */
    const val SELECTION_HANDLE_TOUCH = 30f

    /** How far the rotate handle floats above the selection's top edge, in page units. */
    const val SELECTION_ROTATE_LIFT = 52f

    /**
     * Hit-tests a press against the selection frame: a resize handle on its
     * bottom-right corner and a rotate handle floating above its top-center.
     * Resize wins ties, so a tiny selection still resizes predictably.
     */
    fun selectionHandleAt(
        bounds: FloatArray,
        at: InkPoint,
        touch: Float = SELECTION_HANDLE_TOUCH,
        rotateLift: Float = SELECTION_ROTATE_LIFT
    ): SelectionHandle {
        if (bounds.size < 4) return SelectionHandle.NONE
        if (hypot(at.x - bounds[2], at.y - bounds[3]) <= touch) return SelectionHandle.RESIZE
        val cx = (bounds[0] + bounds[2]) / 2f
        if (hypot(at.x - cx, at.y - (bounds[1] - rotateLift)) <= touch) return SelectionHandle.ROTATE
        return SelectionHandle.NONE
    }

    /** Pointer angle about [center] in degrees, for the rotate handle's drag math. */
    fun angleOf(center: InkPoint, at: InkPoint): Float =
        Math.toDegrees(atan2((at.y - center.y).toDouble(), (at.x - center.x).toDouble())).toFloat()

    /** Shortest signed turn from one pointer angle to another, in -180..180 degrees. */
    fun rotationDelta(fromDeg: Float, toDeg: Float): Float =
        ((toDeg - fromDeg + 540f) % 360f) - 180f

    /**
     * The union of everything drawn on an infinite canvas page: each stroke's
     * points padded by its own width, every text box at its rendered height and
     * every picture frame. An empty page falls back to the page rect itself, so
     * "fit all content" always has somewhere to go.
     */
    fun contentBounds(
        strokes: List<Stroke>,
        texts: List<TextBox>,
        images: List<PageImage>,
        textHeight: (TextBox) -> Float,
        pageWidth: Float,
        pageHeight: Float
    ): Rect {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var any = false
        fun include(left: Float, top: Float, right: Float, bottom: Float) {
            any = true
            if (left < minX) minX = left
            if (top < minY) minY = top
            if (right > maxX) maxX = right
            if (bottom > maxY) maxY = bottom
        }
        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.isEmpty()) continue
            // Single pass: the old minOf/maxOf ×4 walked dense strokes four times per bounds call.
            var sMinX = Float.MAX_VALUE; var sMinY = Float.MAX_VALUE
            var sMaxX = -Float.MAX_VALUE; var sMaxY = -Float.MAX_VALUE
            for (p in pts) {
                if (p.x < sMinX) sMinX = p.x
                if (p.x > sMaxX) sMaxX = p.x
                if (p.y < sMinY) sMinY = p.y
                if (p.y > sMaxY) sMaxY = p.y
            }
            val pad = stroke.width.coerceAtLeast(1f) * 2f
            include(sMinX - pad, sMinY - pad, sMaxX + pad, sMaxY + pad)
        }
        for (box in texts) include(box.x, box.y, box.x + box.width, box.y + textHeight(box))
        images.forEach { image -> include(image.x, image.y, image.x + image.width, image.y + image.height) }
        if (!any) return Rect(0f, 0f, pageWidth, pageHeight)
        return Rect(minX, minY, maxX, maxY)
    }

    // ---- Placed images ---------------------------------------------------------------

    /** True when [point] lands on the picture itself, so a drag there moves it. */
    fun imageContains(image: PageImage, point: InkPoint): Boolean =
        point.x >= image.x && point.x <= image.x + image.width &&
            point.y >= image.y && point.y <= image.y + image.height

    /** The topmost picture under [point], or null when the touch lands on bare page. */
    fun imageAt(images: List<PageImage>, point: InkPoint): PageImage? =
        images.lastOrNull { imageContains(it, point) }

    /**
     * Resizes [image] keeping its aspect ratio, anchored at its top-left corner so the picture
     * grows towards the finger. [targetWidth] usually comes from the handle drag position.
     */
    fun resizeImage(image: PageImage, targetWidth: Float): PageImage {
        if (image.width <= 0f || image.height <= 0f) return image
        val aspect = image.height / image.width
        val width = targetWidth.coerceIn(PageImage.MIN_SIZE, PageImage.MAX_SIZE)
        val height = (width * aspect).coerceIn(PageImage.MIN_SIZE, PageImage.MAX_SIZE)
        // When the height clamps first the width follows it, so the ratio never distorts.
        return if (height >= PageImage.MAX_SIZE || height <= PageImage.MIN_SIZE)
            image.copy(width = (height / aspect).coerceIn(PageImage.MIN_SIZE, PageImage.MAX_SIZE), height = height)
        else image.copy(width = width, height = height)
    }

    /** Fits [originalWidth]×[originalHeight] inside [maxSize] preserving the aspect ratio. */
    fun fitImage(originalWidth: Float, originalHeight: Float, maxSize: Float): Pair<Float, Float> {
        if (originalWidth <= 0f || originalHeight <= 0f) return PageImage.MIN_SIZE to PageImage.MIN_SIZE
        val scale = (maxSize / max(originalWidth, originalHeight)).coerceAtMost(1f)
        return (originalWidth * scale).coerceIn(PageImage.MIN_SIZE, maxSize) to
            (originalHeight * scale).coerceIn(PageImage.MIN_SIZE, maxSize)
    }

    fun hits(stroke: Stroke, point: InkPoint, radius: Float): Boolean {
        val path = pathPoints(stroke)
        val distance = radius + stroke.width / 2
        if (path.size == 1) return hypot(path[0].x - point.x, path[0].y - point.y) <= distance
        return anySegment(path) { a, b -> segmentDistance(point, a, b) <= distance }
    }

    /** Walk adjacent samples lazily, without building a list of pairs before the first hit. */
    private inline fun anySegment(points: List<InkPoint>, matches: (InkPoint, InkPoint) -> Boolean): Boolean {
        val samples = points.iterator()
        if (!samples.hasNext()) return false
        var a = samples.next()
        while (samples.hasNext()) {
            val b = samples.next()
            if (matches(a, b)) return true
            a = b
        }
        return false
    }

    fun segmentDistance(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }

    // ---- Smoothing, natural width and partial erasing ----------------------------------------

    /** Samples closer together than this are treated as one, so spline tangents stay finite. */
    private const val MIN_SAMPLE = 0.35f
    /** Page units between resampled spline points; already dense samples are left as they are. */
    private const val SMOOTH_STEP = 3f
    private const val MAX_SMOOTH_STEPS = 4
    /** How far a pen stroke eases up to full width from each end, in page units and as its share. */

    /**
     * Rounds a freehand centreline so handwriting reads as a curve instead of a polyline.
     *
     * Interior samples get one light averaging pass to take digitizer jitter out of the line, then
     * the line is resampled through a Catmull-Rom spline. The endpoints are preserved, so the drawn
     * line still starts and ends exactly where the pen did. Only the rendered line is smoothed:
     * erase, lasso and hit geometry keep using the stored samples.
     * [preserveEndpoints] also retains endpoints within the cleanup distance for handwriting joins.
     */
    fun smooth(points: List<InkPoint>, preserveEndpoints: Boolean = false): List<InkPoint> {
        val clean = ArrayList<InkPoint>(points.size)
        points.forEachIndexed { index, point ->
            if (clean.isEmpty() || (preserveEndpoints && index == points.lastIndex) ||
                distance(clean.last(), point) > MIN_SAMPLE) clean += point
        }
        if (clean.size < 2) return clean
        val averaged = if (clean.size < 3) clean else clean.mapIndexed { index, point ->
            if (index == 0 || index == clean.lastIndex) point
            else InkPoint(
                (clean[index - 1].x + 2f * point.x + clean[index + 1].x) / 4f,
                (clean[index - 1].y + 2f * point.y + clean[index + 1].y) / 4f,
                (clean[index - 1].pressure + 2f * point.pressure + clean[index + 1].pressure) / 4f)
        }
        val result = ArrayList<InkPoint>(averaged.size * 2)
        result += averaged.first()
        for (i in 0 until averaged.size - 1) {
            val p0 = averaged[(i - 1).coerceAtLeast(0)]
            val p1 = averaged[i]
            val p2 = averaged[i + 1]
            val p3 = averaged[(i + 2).coerceAtMost(averaged.lastIndex)]
            val steps = (distance(p1, p2) / SMOOTH_STEP).roundToInt().coerceIn(1, MAX_SMOOTH_STEPS)
            for (step in 1..steps) result += catmullRom(p0, p1, p2, p3, step.toFloat() / steps)
        }
        // Keep the exact endpoint (including pressure), avoiding spline round-off at section joins.
        if (preserveEndpoints) result[result.lastIndex] = clean.last()
        return result
    }

    /**
     * Append-only spline builder. Only the last three segments can change with new samples.
     * The returned list is owned by this builder and valid until its next update/reset.
     */
    internal class IncrementalSmooth(private val preserveEndpoints: Boolean = false) {
        private val clean = ArrayList<InkPoint>()
        private val settled = ArrayList<InkPoint>()
        private val tail = ArrayList<InkPoint>()
        private var consumed = 0
        private var settledSegments = 0
        /** Points in the returned centreline that no later append can change. */
        val stablePointCount: Int get() = settled.size
        private val result = object : AbstractList<InkPoint>() {
            override val size get() = settled.size + tail.size
            override fun get(index: Int): InkPoint =
                if (index < settled.size) settled[index] else tail[index - settled.size]
        }

        fun update(points: List<InkPoint>): List<InkPoint> {
            // A forced endpoint may be too close to survive cleanup once another sample arrives.
            // Keep it provisional rather than letting it influence future distance filtering.
            val stableCount = if (preserveEndpoints) (points.size - 1).coerceAtLeast(0) else points.size
            if (stableCount < consumed) reset()
            while (consumed < stableCount) {
                val point = points[consumed++]
                if (clean.isEmpty() || distance(clean.last(), point) > MIN_SAMPLE) clean.add(point)
            }
            if (settled.isEmpty() && clean.isNotEmpty()) settled.add(clean.first())
            while (settledSegments < clean.size - 3) appendSegment(settledSegments++, settled)
            val temporaryEnd = preserveEndpoints && points.isNotEmpty()
            if (temporaryEnd) clean.add(points.last())
            tail.clear()
            if (settled.isEmpty() && clean.isNotEmpty()) tail.add(clean.first())
            for (i in settledSegments until clean.size - 1) appendSegment(i, tail)
            if (preserveEndpoints && tail.isNotEmpty()) tail[tail.lastIndex] = clean.last()
            if (temporaryEnd) clean.removeAt(clean.lastIndex)
            return result
        }

        private fun averaged(index: Int): InkPoint {
            val i = index.coerceIn(0, clean.lastIndex)
            val point = clean[i]
            if (i == 0 || i == clean.lastIndex) return point
            val before = clean[i - 1]; val after = clean[i + 1]
            return InkPoint((before.x + 2f * point.x + after.x) / 4f,
                (before.y + 2f * point.y + after.y) / 4f,
                (before.pressure + 2f * point.pressure + after.pressure) / 4f)
        }

        private fun appendSegment(i: Int, target: MutableList<InkPoint>) {
            val p0 = averaged(i - 1); val p1 = averaged(i)
            val p2 = averaged(i + 1); val p3 = averaged(i + 2)
            val steps = (distance(p1, p2) / SMOOTH_STEP).roundToInt().coerceIn(1, MAX_SMOOTH_STEPS)
            for (step in 1..steps) target.add(catmullRom(p0, p1, p2, p3, step.toFloat() / steps))
        }

        fun reset() {
            clean.clear(); settled.clear(); tail.clear(); consumed = 0; settledSegments = 0
        }
    }

    /** The Catmull-Rom point between [p1] and [p2] at [t], so the curve passes through every sample. */
    private fun catmullRom(p0: InkPoint, p1: InkPoint, p2: InkPoint, p3: InkPoint, t: Float): InkPoint {
        val t2 = t * t; val t3 = t2 * t
        fun value(a: Float, b: Float, c: Float, d: Float) =
            .5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
        return InkPoint(
            value(p0.x, p1.x, p2.x, p3.x), value(p0.y, p1.y, p2.y, p3.y),
            value(p0.pressure, p1.pressure, p2.pressure, p3.pressure).coerceIn(.25f, 1.8f))
    }

    /**
     * Position multipliers: neutral (stroke ends keep the same width as the middle at equal
     * pressure) unless the user has turned on ink taper, see [InkTaper]. Shared by the live, saved
     * and selection rendering geometry.
     */
    fun taperScales(points: List<InkPoint>): List<Float> {
        val scales = taperScalesArray(points)
        return List(scales.size) { scales[it] }
    }

    /**
     * [taperScales] without the boxed list. The renderer reads one multiplier per centreline point on
     * every frame of a stroke in progress, so handing it primitives keeps that pass allocation-free
     * instead of churning a boxed Float per point per frame.
     */
    fun taperScalesArray(points: List<InkPoint>): FloatArray = InkTaper.scales(points)

    // ---- Eraser pressure ---------------------------------------------------------------------

    /**
     * Slight pressure response for the eraser, so a stylus still changes the size but never by much.
     * At rest (p=1) the radius is exactly the chosen width; at the softest press it is ~12% smaller
     * and at the hardest ~12% larger. Disabled pressure returns 1 so the radius is unchanged.
     */
    fun eraserScale(pressure: Float): Float {
        val p = pressure.coerceIn(0.25f, 1.8f)
        return (0.85f + 0.15f * p).coerceIn(0.85f, 1.15f)
    }

    /** Variant of [erase] where each centre has its own radius (for pressure-varying eraser). */
    fun erase(stroke: Stroke, centers: List<InkPoint>, radii: List<Float>): List<Stroke> {
        if (centers.isEmpty() || radii.isEmpty()) return listOf(stroke)
        val count = minOf(centers.size, radii.size)
        val c = centers.take(count); val r = radii.take(count)
        val points = pathPoints(stroke)
        if (points.isEmpty()) return listOf(stroke)
        if (points.size == 1) {
            return if (c.indices.any { i -> distance(points[0], c[i]) <= r[i] + stroke.width / 2f }) emptyList() else listOf(stroke)
        }
        if (!reachesVariable(points, c, r, stroke.width)) return listOf(stroke)
        return cutVariable(points, c, r, stroke.width)?.map { eraseFragment(stroke, it) } ?: listOf(stroke)
    }

    private fun reachesVariable(points: List<InkPoint>, centers: List<InkPoint>, radii: List<Float>, strokeWidth: Float): Boolean {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        points.forEach { if (it.x < minX) minX = it.x; if (it.x > maxX) maxX = it.x; if (it.y < minY) minY = it.y; if (it.y > maxY) maxY = it.y }
        return centers.indices.any { i ->
            val reach = radii[i] + strokeWidth / 2f
            centers[i].x >= minX - reach && centers[i].x <= maxX + reach && centers[i].y >= minY - reach && centers[i].y <= maxY + reach
        }
    }

    private fun cutVariable(points: List<InkPoint>, centers: List<InkPoint>, radii: List<Float>, strokeWidth: Float): List<List<InkPoint>>? {
        val fragments = mutableListOf<List<InkPoint>>()
        val cuts = ArrayList<Pair<Float, Float>>(centers.size)
        val ranges = ArrayList<Pair<Float, Float>>(centers.size + 1)
        var current: MutableList<InkPoint>? = null
        var removed = false
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            cuts.clear()
            for (idx in centers.indices) {
                val reach = radii[idx] + strokeWidth / 2f
                span(a, b, centers[idx], reach)?.let { cuts.add(it) }
            }
            if (cuts.isEmpty()) {
                val open = current
                if (open != null) open.add(b) else current = mutableListOf(a, b)
                continue
            }
            removed = true
            cuts.sortBy { it.first }
            ranges.clear()
            var cursor = 0f
            for (bite in cuts) {
                if (bite.first > cursor) ranges.add(cursor to bite.first)
                if (bite.second > cursor) cursor = bite.second
            }
            if (cursor < 1f) ranges.add(cursor to 1f)
            if (ranges.isEmpty()) { current?.let { fragments.add(it) }; current = null; continue }
            for (range in ranges) {
                val start = lerp(a, b, range.first); val end = lerp(a, b, range.second)
                val open = current
                if (open != null && distance(open.last(), start) <= MIN_SAMPLE) open.add(end)
                else { open?.let { fragments.add(it) }; current = mutableListOf(start, end) }
            }
        }
        current?.let { fragments.add(it) }
        return if (removed) fragments else null
    }

    /**
     * Removes ink within [radius] of any of [centers] and returns the surviving fragments in
     * draw order, or [stroke] itself when none of them reaches it. Shapes are cut along their
     * outlines, with surviving vertices stored as line paths (no freehand smoothing).
     * [radius] is the eraser's own radius; each stroke widens it by half its own width, so
     * the cleared channel spans the visible line.
     */
    fun erase(stroke: Stroke, center: InkPoint, radius: Float): List<Stroke> = erase(stroke, listOf(center), radius)

    fun erase(stroke: Stroke, centers: List<InkPoint>, radius: Float): List<Stroke> {
        if (centers.isEmpty()) return listOf(stroke)
        val points = pathPoints(stroke)
        if (points.isEmpty()) return listOf(stroke)
        val reach = radius + stroke.width / 2f
        if (points.size == 1) return if (centers.any { distance(points[0], it) <= reach }) emptyList() else listOf(stroke)
        // One cheap box test decides most strokes without walking a single segment of them.
        if (!reaches(points, centers, reach)) return listOf(stroke)
        return cut(points, centers, reach)?.map { eraseFragment(stroke, it) } ?: listOf(stroke)
    }

    /** Keep shape fragments crisp and styled without re-deriving a closed shape from their ends. */
    private fun eraseFragment(stroke: Stroke, points: List<InkPoint>): Stroke =
        stroke.copy(tool = if (stroke.tool in ShapeTools) Tool.LINE else stroke.tool, points = points)

    /** True when any of [centers] lies within [reach] of the stroke's own bounding box. */
    private fun reaches(points: List<InkPoint>, centers: List<InkPoint>, reach: Float): Boolean {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        points.forEach {
            if (it.x < minX) minX = it.x
            if (it.x > maxX) maxX = it.x
            if (it.y < minY) minY = it.y
            if (it.y > maxY) maxY = it.y
        }
        return centers.any { it.x >= minX - reach && it.x <= maxX + reach && it.y >= minY - reach && it.y <= maxY + reach }
    }

    /**
     * The fragments of [points] left once every erase circle is taken out, or null when none of them
     * touched the stroke. Every circle is applied during the same walk of the samples, so a long drag
     * does not re-read the whole stroke once per sample.
     */
    private fun cut(points: List<InkPoint>, centers: List<InkPoint>, reach: Float): List<List<InkPoint>>? {
        val fragments = mutableListOf<List<InkPoint>>()
        val cuts = ArrayList<Pair<Float, Float>>(centers.size)
        val ranges = ArrayList<Pair<Float, Float>>(centers.size + 1)
        var current: MutableList<InkPoint>? = null
        var removed = false
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            cuts.clear()
            for (center in centers) span(a, b, center, reach)?.let { cuts.add(it) }
            if (cuts.isEmpty()) {
                val open = current
                if (open != null) open.add(b) else current = mutableListOf(a, b)
                continue
            }
            removed = true
            cuts.sortBy { it.first }
            ranges.clear()
            var cursor = 0f
            for (bite in cuts) {
                if (bite.first > cursor) ranges.add(cursor to bite.first)
                if (bite.second > cursor) cursor = bite.second
            }
            if (cursor < 1f) ranges.add(cursor to 1f)
            if (ranges.isEmpty()) { current?.let { fragments.add(it) }; current = null; continue }
            for (range in ranges) {
                val start = lerp(a, b, range.first)
                val end = lerp(a, b, range.second)
                val open = current
                if (open != null && distance(open.last(), start) <= MIN_SAMPLE) open.add(end)
                else { open?.let { fragments.add(it) }; current = mutableListOf(start, end) }
            }
        }
        current?.let { fragments.add(it) }
        return if (removed) fragments else null
    }

    /** The part of the segment [a]->[b] inside the erase circle, as a 0..1 range, or null. */
    private fun span(a: InkPoint, b: InkPoint, center: InkPoint, radius: Float): Pair<Float, Float>? {
        val dx = b.x - a.x; val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        val fromX = a.x - center.x; val fromY = a.y - center.y
        if (lengthSquared == 0f) return if (hypot(fromX, fromY) <= radius) 0f to 1f else null
        val slope = 2f * (fromX * dx + fromY * dy)
        val offset = fromX * fromX + fromY * fromY - radius * radius
        val discriminant = slope * slope - 4f * lengthSquared * offset
        // No crossing, or a single tangent touch: nothing is removed from this segment.
        if (discriminant <= 0f) return null
        val root = sqrt(discriminant)
        val entry = (-slope - root) / (2f * lengthSquared)
        val exit = (-slope + root) / (2f * lengthSquared)
        if (entry > 1f || exit < 0f) return null
        return entry.coerceIn(0f, 1f) to exit.coerceIn(0f, 1f)
    }

    private fun lerp(a: InkPoint, b: InkPoint, t: Float) =
        InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.pressure + (b.pressure - a.pressure) * t)

    private fun distance(a: InkPoint, b: InkPoint) = hypot(a.x - b.x, a.y - b.y)

    // ---- Shape recognition -------------------------------------------------------------

    /** Clean geometry for a deliberately held freehand pen stroke. */
    fun tidy(stroke: Stroke): List<Stroke>? = ShapeRecognition.tidy(stroke)

    // ---- Math / precision helpers ------------------------------------------------------

    /** Rounds [point] to the nearest intersection of a grid with [spacing] page units. */
    fun snapToGrid(point: InkPoint, spacing: Float): InkPoint {
        if (spacing <= 0f) return point
        fun snap(v: Float) = (v / spacing).roundToInt() * spacing
        return InkPoint(snap(point.x), snap(point.y), point.pressure)
    }

    /**
     * Returns a copy of [b] whose direction from [a] is snapped to the nearest [stepDegrees].
     * Length from [a] is preserved so drag distance still controls size.
     * Used for the LINE tool so a shaky drag becomes cleanly horizontal, vertical or diagonal.
     */
    fun snapAngle(a: InkPoint, b: InkPoint, stepDegrees: Float = 15f): InkPoint {
        val dx = b.x - a.x; val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (length < 0.5f || stepDegrees <= 0f) return b
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val snapped = (angle / stepDegrees).roundToInt() * stepDegrees
        val rad = Math.toRadians(snapped.toDouble())
        return InkPoint(
            a.x + length * cos(rad).toFloat(),
            a.y + length * sin(rad).toFloat(),
            b.pressure
        )
    }

}
