package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

/** Higher values accept shorter, less exact scrubs; coverage of existing ink is always required. */
object ScribbleSensitivity {
    const val DEFAULT = 0.5f
    fun normalize(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT
    fun reversals(value: Float): Int = when {
        normalize(value) < .25f -> 4
        normalize(value) < .8f -> 3
        else -> 2
    }
    fun passes(value: Float) = if (normalize(value) < .25f) 3 else 2
}

enum class Tool { PEN, HIGHLIGHTER, ERASER, LINE, RECTANGLE, ELLIPSE, TEXT, LASSO, HAND }
/** Line pattern for shape tools, like GoodNotes' dashed and dotted lines for diagrams. */
enum class StrokeStyle {
    SOLID, DASHED, DOTTED;
    companion object {
        fun safeValueOf(name: String): StrokeStyle = try { valueOf(name) } catch (_: Exception) { SOLID }
    }
}
/** Horizontal alignment for typed text boxes, matching Notability's text controls. */
enum class TextAlignMode {
    LEFT, CENTER, RIGHT;
    companion object {
        fun safeValueOf(name: String): TextAlignMode = try { valueOf(name) } catch (_: Exception) { LEFT }
    }
}
enum class Paper { PLAIN, RULED, DOTS, GRID, MATH_GRID, GRAPH, MC_SHEET, TIAN_GRID, MI_GRID;
    /** Spacing used for paper rendering and for snap-to-grid when that paper is active. */
    val gridSpacing: Float get() = when (this) {
        MATH_GRID, GRAPH -> 20f
        GRID -> 28f
        TIAN_GRID, MI_GRID -> HANZI_CELL
        else -> 20f
    }
    val isGrid: Boolean get() = this == GRID || this == MATH_GRID || this == GRAPH
    /** Character-practice papers tile large squares with printed guides, like exercise books. */
    val isHanzi: Boolean get() = this == TIAN_GRID || this == MI_GRID
    val hasPrintedAxes: Boolean get() = this == GRAPH
    companion object {
        /** Side of one hanzi practice cell, in page units; divides the 840 pt page width evenly. */
        const val HANZI_CELL = 84f
        fun safeValueOf(name: String): Paper = try { valueOf(name) } catch (_: Exception) { DOTS }
    }
}
data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f)

/** The colour, drawn width and opacity a restyle starts from. */
data class SelectionStyle(val color: Int, val width: Float, val opacity: Float)
data class Stroke(
    val tool: Tool, val color: Int, val width: Float, val points: List<InkPoint>,
    val opacity: Float = if (tool == Tool.HIGHLIGHTER) 72f / 255f else 1f,
    /**
     * Wall-clock milliseconds when the stroke landed on the page, for exam timing reports and
     * replay. Zero means unknown — ink from before timestamps existed — and is never analysed.
     */
    val createdAt: Long = 0L,
    /**
     * Line pattern for shape tools (line, rectangle, ellipse). Freehand pen and highlighter
     * always draw solid so pressure-varying ink never breaks into uneven dashes.
     */
    val style: StrokeStyle = StrokeStyle.SOLID
)

/**
 * Editable typed text on a page, positioned in page units from its top-left corner. The text wraps
 * at [width] and grows downwards, so a box needs no stored height and stays correct after edits.
 */
data class TextBox(
    val id: String = UUID.randomUUID().toString(),
    val x: Float, val y: Float, val width: Float = DEFAULT_WIDTH,
    val text: String = "", val size: Float = 26f,
    val color: Int = 0xFF303431.toInt(),
    val bold: Boolean = false, val italic: Boolean = false,
    val align: TextAlignMode = TextAlignMode.LEFT,
    val underline: Boolean = false
) {
    fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    companion object {
        const val DEFAULT_WIDTH = 360f
        const val MIN_WIDTH = 90f
        const val MAX_WIDTH = 1800f
        const val MIN_SIZE = 10f
        const val MAX_SIZE = 120f
    }
}

/**
 * A photo or figure placed on a page, stored as an image file beside the notebook with only its
 * placement kept in the page JSON. Coordinates are page units from the page's top-left corner,
 * matching [TextBox] so ink drawn over the picture lines up in exports and thumbnails.
 */
data class PageImage(
    val id: String = UUID.randomUUID().toString(),
    val x: Float, val y: Float, val width: Float, val height: Float
) {
    fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    companion object {
        const val MIN_SIZE = 40f
        const val MAX_SIZE = 2400f
        /** Half-size of the bottom-right resize handle, in page units. */
        const val HANDLE_HALF = 22f
    }
}

/**
 * Everything a lasso loop picked up in one pass: ink plus the typed text boxes and placed
 * pictures it enclosed, so they move, copy, rotate, resize and delete together like one
 * GoodNotes-style selection. Strokes match by value (they carry no id); texts and pictures
 * match by id, which survives moves and restyles.
 */
data class CanvasSelection(
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextBox> = emptyList(),
    val images: List<PageImage> = emptyList()
) {
    fun isEmpty(): Boolean = strokes.isEmpty() && texts.isEmpty() && images.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
    /** Every selected item — ink, text and pictures — as one count for the selection bar. */
    val size: Int get() = strokes.size + texts.size + images.size
}
data class NotePage(
    val id: String = UUID.randomUUID().toString(),
    val width: Float = 840f, val height: Float = 1188f,
    val paper: Paper = Paper.DOTS, val pdfIndex: Int? = null,
    val strokes: List<Stroke> = emptyList(), val texts: List<TextBox> = emptyList(),
    val images: List<PageImage> = emptyList(),
    /** Bumped on every change to this page, so a cached preview can tell a stale copy from a fresh one. */
    val revision: Int = 0,
    /** False while only this page's summary is in memory; its ink and text are still on disk. */
    val loaded: Boolean = true,
    /** Queued for the redo list — a question worth another attempt before the exam. */
    val redoFlag: Boolean = false,
    val infinite: Boolean = false,
    val title: String = "",
    val bookmarked: Boolean = false,
    val peekAnchor: PeekAnchor? = null
)
data class Notebook(
    val id: String = UUID.randomUUID().toString(), val title: String,
    val folderId: String? = null, val cover: Int = 0, val starred: Boolean = false,
    val updated: Long = System.currentTimeMillis(), val pages: List<NotePage> = listOf(NotePage()),
    /** Exam metadata: subject, year, company and so on, carried as [ExamTags]. */
    val exam: ExamTags = ExamTags(),
    /** A [ExamSet] this notebook belongs to, e.g. Exam 1 within "VCAA 2022 Methods". */
    val setId: String? = null,
    /** Marked attempts with scores and time taken, newest last. */
    val attempts: List<ExamAttempt> = emptyList(),
    /**
     * True shows the first page itself as the shelf cover, with no book decoration; false keeps
     * the decorative default cover. A fresh notebook has no drawn preview yet, so it shows the
     * default cover either way until its first page has been rendered.
     */
    val pageCover: Boolean = true,
    val mistakePractice: Boolean = false,
    val mistakeReviews: List<com.folio.notes.mistakes.LocalMistakeReviewAttempt> = emptyList()
) {
    /** The share of the best attempt's score, 0..1, or null while nothing has been marked. */
    val bestScore: Float? get() = attempts.mapNotNull { it.share }.maxOrNull()
}
data class Folder(val id: String = UUID.randomUUID().toString(), val name: String)

/** A new page identity keeps copied ink and PDF backgrounds independent in undo history. */
/** Appends an attempt, or replaces one with the same id where it sits, keeping the history in order. */
fun Notebook.withAttempt(attempt: ExamAttempt): Notebook {
    val index = attempts.indexOfFirst { it.id == attempt.id }
    if (index < 0) return copy(attempts = attempts + attempt)
    return copy(attempts = attempts.toMutableList().apply { set(index, attempt) })
}

fun Notebook.withDuplicatedPage(index: Int): Notebook {
    if (index !in pages.indices) return this
    val duplicate = pages[index].copy(id = UUID.randomUUID().toString())
    return copy(pages = pages.toMutableList().apply { add(index + 1, duplicate) })
}

/** Reorders one page, clamped to the notebook; a bad index leaves the notebook untouched. */
fun Notebook.withMovedPage(from: Int, to: Int): Notebook {
    if (from !in pages.indices) return this
    val target = to.coerceIn(0, pages.lastIndex)
    if (from == target) return this
    return copy(pages = pages.toMutableList().apply { add(target, removeAt(from)) })
}

/** Removes a page, keeping one blank page so a notebook always has somewhere to write. */
fun Notebook.withDeletedPage(index: Int): Notebook {
    if (index !in pages.indices) return this
    val removedId = pages[index].id
    val remaining = pages.toMutableList().apply { removeAt(index) }.map {
        if (it.peekAnchor?.pageId == removedId) it.copy(peekAnchor = null) else it
    }
    return copy(pages = remaining.ifEmpty { listOf(NotePage()) })
}

/** Inserts a page at [index], clamped to the notebook's bounds. */
fun Notebook.withInsertedPage(index: Int, page: NotePage = NotePage()): Notebook {
    val at = index.coerceIn(0, pages.size)
    return copy(pages = pages.toMutableList().apply { add(at, page) })
}

/** Swaps in a page whose content has just arrived from disk, keeping its place in the notebook. */
fun Notebook.withPage(page: NotePage): Notebook =
    copy(pages = pages.map { if (it.id == page.id) page else it })

/** This page as the on-disk index holds it: its identity, paper and revision, but none of its ink. */
fun NotePage.asSummary(): NotePage = copy(strokes = emptyList(), texts = emptyList(), images = emptyList(), loaded = false)

/** Marks a content change, so cached previews and exports know their copy is out of date. */
fun NotePage.revised(): NotePage = copy(revision = revision + 1)

/**
 * Where the page you were reading ends up once a page moves from [from] to [to], so reordering
 * keeps the open page on screen instead of jumping to an unrelated one.
 */
fun movedPageIndex(current: Int, from: Int, to: Int): Int = when {
    current == from -> to
    from < to && current in (from + 1)..to -> current - 1
    to < from && current in to..(from - 1) -> current + 1
    else -> current
}

/**
 * The portable whole-notebook format: every page with its ink inline. It is what a `.folio` archive
 * carries, so a backup stays a single self-contained file that any version of the app can read.
 * On-device storage uses the split layout in [NoteMetaCodec] and [NotePageCodec] instead.
 */
object NoteCodec {
    fun encode(note: Notebook): String = JSONObject().apply {
        put("version", 1); put("id", note.id); put("title", note.title)
        put("folder", note.folderId ?: JSONObject.NULL); put("cover", note.cover)
        put("starred", note.starred); put("updated", note.updated)
        put("exam", ExamTagsCodec.encode(note.exam))
        put("set", note.setId ?: JSONObject.NULL)
        put("attempts", ExamTagsCodec.encodeAttempts(note.attempts))
        if (!note.pageCover) put("pageCover", false)
        put("mistakePractice", note.mistakePractice)
        put("mistakeReviews", JSONArray(note.mistakeReviews.map { it.encode() }))
        put("pages", JSONArray().apply { note.pages.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("width", p.width); put("height", p.height); put("paper", p.paper.name)
            put("pdf", p.pdfIndex ?: JSONObject.NULL); put("revision", p.revision)
            if (p.redoFlag) put("redo", true)
            if (p.infinite) put("infinite", true)
            put("title", p.title); put("bookmarked", p.bookmarked)
            p.peekAnchor?.let { put("peekAnchor", it.encode()) }
            put("strokes", InkCodec.encodeStrokes(p.strokes))
            put("texts", InkCodec.encodeTexts(p.texts))
            put("images", InkCodec.encodeImages(p.images))
        }) } })
    }.toString()

    fun decode(value: String): Notebook {
        val o = JSONObject(value)
        require(o.getInt("version") == 1) { "Unsupported notebook version" }
        return Notebook(o.getString("id"), o.getString("title"),
            if (o.isNull("folder")) null else o.getString("folder"), o.getInt("cover"),
            o.getBoolean("starred"), o.getLong("updated"), o.getJSONArray("pages").objects().map { p ->
                NotePage(p.getString("id"), p.getDouble("width").toFloat(), p.getDouble("height").toFloat(),
                    Paper.safeValueOf(p.getString("paper")), if (p.isNull("pdf")) null else p.getInt("pdf"),
                    InkCodec.decodeStrokes(p.optJSONArray("strokes")), InkCodec.decodeTexts(p.optJSONArray("texts")),
                    InkCodec.decodeImages(p.optJSONArray("images")),
                    p.optInt("revision", 0), redoFlag = p.optBoolean("redo", false), infinite = p.optBoolean("infinite", false),
                    title = p.optString("title", ""), bookmarked = p.optBoolean("bookmarked", false), peekAnchor = PeekAnchor.decode(p.optJSONObject("peekAnchor")))
            }.also { require(it.isNotEmpty()) { "Notebook has no pages" } },
            ExamTagsCodec.decode(o.optJSONObject("exam")),
            if (o.isNull("set")) null else o.optString("set"),
            ExamTagsCodec.decodeAttempts(o.optJSONArray("attempts")),
            // Older backups have no cover choice and default to the first-page cover.
            pageCover = o.optBoolean("pageCover", true),
            mistakePractice = o.optBoolean("mistakePractice", false),
            mistakeReviews = decodeMistakeReviews(o))
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}

/** Shared shape geometry for ink rendering and whole-stroke erasing. */
object InkGeometry {
    fun pathPoints(stroke: Stroke): List<InkPoint> {
        if (stroke.points.size < 2) return stroke.points
        val a = stroke.points.first(); val b = stroke.points.last()
        return when (stroke.tool) {
            Tool.LINE -> listOf(a, b)
            Tool.RECTANGLE -> listOf(a, InkPoint(b.x, a.y), b, InkPoint(a.x, b.y), a)
            Tool.ELLIPSE -> (0..64).map { i ->
                val t = i * 2 * PI / 64
                InkPoint((a.x + b.x) / 2 + abs(b.x - a.x) / 2 * cos(t).toFloat(),
                    (a.y + b.y) / 2 + abs(b.y - a.y) / 2 * sin(t).toFloat())
            }
            else -> stroke.points
        }
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

    private fun polygonBounds(polygon: List<InkPoint>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in polygon) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun strokeBoundsOf(points: List<InkPoint>): FloatArray {
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

    /** A stroke is selected only when every sample is enclosed, so a half-crossed stroke stays put. */
    fun lassoSelects(polygon: List<InkPoint>, stroke: Stroke): Boolean {
        if (polygon.size < 3) return false
        val points = pathPoints(stroke)
        if (points.isEmpty()) return false
        // Cheap box reject: a stroke fully outside the loop's bounds needs no ray casts.
        val loop = polygonBounds(polygon)
        val box = strokeBoundsOf(points)
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
        return lassoContains(polygon, InkPoint(left, top)) &&
            lassoContains(polygon, InkPoint(right, top)) &&
            lassoContains(polygon, InkPoint(right, bottom)) &&
            lassoContains(polygon, InkPoint(left, bottom))
    }
    /** A text box is selected only when its whole rendered extent is enclosed. */
    fun lassoSelectsText(polygon: List<InkPoint>, box: TextBox, height: Float): Boolean =
        lassoSelectsRect(polygon, box.x, box.y, box.x + box.width, box.y + height)
    /** A picture is selected only when its whole frame is enclosed. */
    fun lassoSelectsImage(polygon: List<InkPoint>, image: PageImage): Boolean =
        lassoSelectsRect(polygon, image.x, image.y, image.x + image.width, image.y + image.height)
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
            val pts = if (stroke.tool == Tool.LINE || stroke.tool == Tool.RECTANGLE || stroke.tool == Tool.ELLIPSE) stroke.points else pathPoints(stroke)
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
                width = (box.width * factor).coerceIn(TextBox.MIN_WIDTH, TextBox.MAX_WIDTH),
                size = (box.size * factor).coerceIn(TextBox.MIN_SIZE, TextBox.MAX_SIZE)
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
    fun bounds(strokes: List<Stroke>, margin: Float = 0f): FloatArray? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var any = false
        for (stroke in strokes) {
            // Shapes re-derive from two corners; their raw points already bound the geometry
            // without expanding an ellipse into 65 samples.
            val pts = if (stroke.tool == Tool.LINE || stroke.tool == Tool.RECTANGLE || stroke.tool == Tool.ELLIPSE) stroke.points else pathPoints(stroke)
            for (p in pts) {
                any = true
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
        }
        if (!any) return null
        return floatArrayOf(minX - margin, minY - margin, maxX + margin, maxY + margin)
    }

    /** The middle of a stroke set's bounding box, used as the pivot for rotation and resizing. */
    fun center(strokes: List<Stroke>): InkPoint? =
        bounds(strokes)?.let { InkPoint((it[0] + it[2]) / 2f, (it[1] + it[3]) / 2f) }

    /**
     * Turns a whole stroke set about [center] by [degrees]. The editor only offers right angles, so
     * a rectangle or ellipse, whose geometry is re-derived from two opposite corners, stays true.
     */
    fun rotate(strokes: List<Stroke>, center: InkPoint, degrees: Float): List<Stroke> {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = cos(radians).toFloat(); val sin = sin(radians).toFloat()
        return strokes.map { stroke -> stroke.copy(points = stroke.points.map { point ->
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
     * [style] only applies to shape tools (line, rectangle, ellipse); freehand ink keeps its
     * solid look so pressure-varying strokes never break into uneven dashes.
     */
    fun restyle(
        strokes: List<Stroke>,
        color: Int? = null,
        widthScale: Float? = null,
        opacity: Float? = null,
        style: StrokeStyle? = null
    ): List<Stroke> = strokes.map { stroke ->
        stroke.copy(
            color = color ?: stroke.color,
            width = widthScale?.let { (stroke.width * it).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH) } ?: stroke.width,
            opacity = opacity?.coerceIn(MIN_OPACITY, MAX_OPACITY) ?: stroke.opacity,
            style = style?.takeIf {
                stroke.tool == Tool.LINE || stroke.tool == Tool.RECTANGLE || stroke.tool == Tool.ELLIPSE
            } ?: stroke.style
        )
    }

    /** The look a restyle sheet starts from, read from the first stroke of a selection. */
    fun styleOf(strokes: List<Stroke>): SelectionStyle? =
        strokes.firstOrNull()?.let { SelectionStyle(it.color, it.width, it.opacity) }

    // ---- Placed images ---------------------------------------------------------------

    /** True when [point] lands on the picture itself, so a drag there moves it. */
    fun imageContains(image: PageImage, point: InkPoint): Boolean =
        point.x >= image.x && point.x <= image.x + image.width &&
            point.y >= image.y && point.y <= image.y + image.height

    /** True when [point] lands on the bottom-right resize handle. */
    fun imageHandleContains(image: PageImage, point: InkPoint): Boolean {
        val cx = image.x + image.width
        val cy = image.y + image.height
        val half = PageImage.HANDLE_HALF
        return point.x >= cx - half && point.x <= cx + half &&
            point.y >= cy - half && point.y <= cy + half
    }

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
        return path.zipWithNext().any { (a, b) -> segmentDistance(point, a, b) <= distance }
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
    private const val TAPER_RAMP = 14f
    private const val TAPER_SHARE = 0.3f
    private const val TAPER_FLOOR = 0.5f

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
     * Width multipliers that ease a pen stroke in and out of the page. The ramp is measured in page
     * units and capped at a share of the stroke, so a short tick keeps most of its width while a
     * long line trails off naturally at both ends.
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
    fun taperScalesArray(points: List<InkPoint>): FloatArray {
        val count = points.size
        val scales = FloatArray(count) { 1f }
        if (count < 2) return scales
        val travelled = FloatArray(count)
        for (i in 1 until count) travelled[i] = travelled[i - 1] + distance(points[i - 1], points[i])
        val total = travelled[count - 1]
        if (total <= 0f) return scales
        val ramp = min(TAPER_RAMP, total * TAPER_SHARE)
        for (i in 0 until count) {
            val edge = min(travelled[i], total - travelled[i])
            scales[i] = TAPER_FLOOR + (1f - TAPER_FLOOR) * (edge / ramp).coerceAtMost(1f)
        }
        return scales
    }

    // ---- Scribble-to-erase + eraser pressure -------------------------------------------------

    /**
     * Slight pressure response for the eraser, so a stylus still changes the size but never by much.
     * At rest (p=1) the radius is exactly the chosen width; at the softest press it is ~12% smaller
     * and at the hardest ~12% larger. Disabled pressure returns 1 so the radius is unchanged.
     */
    fun eraserScale(pressure: Float): Float {
        val p = pressure.coerceIn(0.25f, 1.8f)
        return (0.85f + 0.15f * p).coerceIn(0.85f, 1.15f)
    }

    /**
     * Recognize repeated scrubbing reversals independent of the device's sample density.
     *
     * Cursive writing also bends back on itself, so a bare direction-change count erases words.
     * A real scrub re-traces the same ground: each hairpin needs long legs that overlap the
     * previous leg along the stroke's dominant axis (back-and-forth over the same ink, not a
     * forward-moving `W` whose teeth only touch at a point), and the stroke as a whole must
     * end near where it travelled — low net progress along that axis for the distance covered.
     */
    fun isScribble(points: List<InkPoint>, sensitivity: Float = ScribbleSensitivity.DEFAULT): Boolean {
        val ease = ScribbleSensitivity.normalize(sensitivity)
        if (points.size < ScribbleSensitivity.reversals(ease) + 2) return false
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val width = maxX - minX; val height = maxY - minY
        val span = hypot(width, height)
        if (span < 14f) return false
        // Remove tiny wiggles and rounded turnaround samples before measuring direction changes.
        val corners = simplify(points, max(2f, span * .025f))
        val total = corners.zipWithNext().sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat()
        if (total < 80f - 20f * ease || total / span < 2.2f) return false
        // Letter cusps are short: a scrub leg must span a real share of the whole stroke.
        val minLeg = max(8f - 2f * ease, span * (.18f - .06f * ease))
        val horizontal = width >= height
        var reversals = 0
        for (i in 1 until corners.lastIndex) {
            val ax = corners[i].x - corners[i - 1].x; val ay = corners[i].y - corners[i - 1].y
            val bx = corners[i + 1].x - corners[i].x; val by = corners[i + 1].y - corners[i].y
            val al = hypot(ax, ay); val bl = hypot(bx, by)
            if (al < minLeg || bl < minLeg) continue
            // Hairpin only: ~117° or sharper, so rounded loop turns do not count.
            if ((ax * bx + ay * by) / (al * bl) >= -0.45f) continue
            // Successive legs must re-cover each other along the scrub axis. A forward-moving
            // cursive `W`/`M` only meets at a point (no overlap); a scrub fully re-traces it.
            val overlap = if (horizontal) segmentOverlap(
                corners[i - 1].x, corners[i].x, corners[i].x, corners[i + 1].x
            ) else segmentOverlap(
                corners[i - 1].y, corners[i].y, corners[i].y, corners[i + 1].y
            )
            if (overlap >= .6f - .1f * ease) reversals++
        }
        if (reversals < ScribbleSensitivity.reversals(ease)) return false
        // Cursive marches forward while a scrub stays put: net progress along the dominant
        // axis must be small next to the distance travelled along it.
        val travel = corners.zipWithNext().sumOf { (a, b) ->
            if (horizontal) abs(b.x - a.x).toDouble() else abs(b.y - a.y).toDouble()
        }.toFloat()
        if (travel <= 0f) return false
        val net = if (horizontal) abs(corners.last().x - corners.first().x)
        else abs(corners.last().y - corners.first().y)
        // Repeated small backtracks in a word must not add up to a scrub.
        val axisSpan = if (horizontal) width else height
        return net / travel <= .32f + .13f * ease && axisSpan / travel <= .32f + .13f * ease
    }

    /** Share of the longer 1-D segment covered by the overlap, 0 when they only touch. */
    private fun segmentOverlap(a1: Float, a2: Float, b1: Float, b2: Float): Float {
        val lo1 = min(a1, a2); val hi1 = max(a1, a2)
        val lo2 = min(b1, b2); val hi2 = max(b1, b2)
        val longer = max(hi1 - lo1, hi2 - lo2)
        if (longer <= 0f) return 0f
        return (min(hi1, hi2) - max(lo1, lo2)).coerceAtLeast(0f) / longer
    }

    /** Test the entire sweep, including crossings between widely spaced input samples. */
    fun scribbleHits(scribble: Stroke, target: Stroke, radius: Float): Boolean {
        val sweep = scribble.points
        val path = pathPoints(target)
        if (sweep.isEmpty() || path.isEmpty()) return false
        val reach = radius + target.width / 2f
        if (sweep.size == 1) return hits(target, sweep.first(), radius)
        // Cheap box reject before the O(sweep × path) narrow phase.
        val sweepBox = strokeBoundsOf(sweep)
        val pathBox = strokeBoundsOf(path)
        if (pathBox[2] < sweepBox[0] - reach || pathBox[0] > sweepBox[2] + reach ||
            pathBox[3] < sweepBox[1] - reach || pathBox[1] > sweepBox[3] + reach) return false
        return sweep.zipWithNext().any { (a, b) ->
            if (path.size == 1) segmentDistance(path.first(), a, b) <= reach
            else path.zipWithNext().any { (c, d) ->
                segmentsCross(a, b, c, d) ||
                    minOf(segmentDistance(a, c, d), segmentDistance(b, c, d),
                        segmentDistance(c, a, b), segmentDistance(d, a, b)) <= reach
            }
        }
    }

    private fun segmentsCross(a: InkPoint, b: InkPoint, c: InkPoint, d: InkPoint): Boolean {
        fun side(p: InkPoint, q: InkPoint, r: InkPoint) =
            (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        val cSide = side(a, b, c); val dSide = side(a, b, d)
        val aSide = side(c, d, a); val bSide = side(c, d, b)
        // Collinear and endpoint contacts are covered by the distance checks.
        return ((cSide < 0f && dSide > 0f) || (cSide > 0f && dSide < 0f)) &&
            ((aSide < 0f && bSide > 0f) || (aSide > 0f && bSide < 0f))
    }

    /** Require repeated coverage of existing ink; a nearby mark or one crossing is not enough. */
    fun scribbleErase(strokes: List<Stroke>, scribble: Stroke, radius: Float, sensitivity: Float = ScribbleSensitivity.DEFAULT): List<Stroke> {
        val ease = ScribbleSensitivity.normalize(sensitivity)
        if (strokes.isEmpty() || !isScribble(scribble.points, ease)) return strokes
        val bounds = strokeBoundsOf(scribble.points)
        val span = hypot(bounds[2] - bounds[0], bounds[3] - bounds[1])
        val legs = simplify(scribble.points, max(2f, span * .025f)).zipWithNext()
            .filter { (a, b) -> distance(a, b) >= max(8f - 2f * ease, span * (.18f - .06f * ease)) }
        // Use a narrow contact tolerance even for a broad highlighter or legacy erase radius.
        val contactRadius = min(radius.coerceAtLeast(0f), 2f)
        return strokes.filterNot { target ->
            var passes = 0
            legs.any { (a, b) ->
                if (scribbleHits(scribble.copy(points = listOf(a, b)), target, contactRadius)) passes++
                passes >= ScribbleSensitivity.passes(ease)
            }
        }
    }

    /** Variant of [erase] where each centre has its own radius (for pressure-varying eraser). */
    fun erase(stroke: Stroke, centers: List<InkPoint>, radii: List<Float>): List<Stroke> {
        if (centers.isEmpty() || radii.isEmpty()) return listOf(stroke)
        val count = minOf(centers.size, radii.size)
        val c = centers.take(count); val r = radii.take(count)
        if (stroke.tool == Tool.LINE || stroke.tool == Tool.RECTANGLE || stroke.tool == Tool.ELLIPSE) {
            return if (c.indices.any { i -> hits(stroke, c[i], r[i]) }) emptyList() else listOf(stroke)
        }
        val points = stroke.points
        if (points.isEmpty()) return listOf(stroke)
        if (points.size == 1) {
            return if (c.indices.any { i -> distance(points[0], c[i]) <= r[i] + stroke.width / 2f }) emptyList() else listOf(stroke)
        }
        if (!reachesVariable(points, c, r, stroke.width)) return listOf(stroke)
        return cutVariable(points, c, r, stroke.width)?.map { stroke.copy(points = it) } ?: listOf(stroke)
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
     * Removes the ink of a freehand stroke within [radius] of any of [centers] and returns the
     * surviving fragments in draw order, or [stroke] itself when none of them reaches it. Shapes
     * cannot be meaningfully cut, so they are kept whole or dropped
     * entirely. [radius] is the eraser's own radius; each stroke widens it by half its own width, so
     * the cleared channel spans the visible line.
     */
    fun erase(stroke: Stroke, center: InkPoint, radius: Float): List<Stroke> = erase(stroke, listOf(center), radius)

    fun erase(stroke: Stroke, centers: List<InkPoint>, radius: Float): List<Stroke> {
        if (centers.isEmpty()) return listOf(stroke)
        if (stroke.tool == Tool.LINE || stroke.tool == Tool.RECTANGLE || stroke.tool == Tool.ELLIPSE)
            return if (centers.any { hits(stroke, it, radius) }) emptyList() else listOf(stroke)
        val points = stroke.points
        if (points.isEmpty()) return listOf(stroke)
        val reach = radius + stroke.width / 2f
        if (points.size == 1) return if (centers.any { distance(points[0], it) <= reach }) emptyList() else listOf(stroke)
        // One cheap box test decides most strokes without walking a single segment of them.
        if (!reaches(points, centers, reach)) return listOf(stroke)
        return cut(points, centers, reach)?.map { stroke.copy(points = it) } ?: listOf(stroke)
    }

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

    /** Shortest stroke worth tidying, in page units. */
    private const val TIDY_MIN_LENGTH = 40f
    /** A freehand line is straight when it never strays this far from its chord, as a share of it. */
    private const val TIDY_LINE_SHARE = 0.055f
    /** How close the ends must come, relative to the stroke's size, for it to count as closed. */
    private const val TIDY_CLOSE_SHARE = 0.3f

    /**
     * Tidies a rough freehand drawing into a clean line, rectangle, ellipse or triangle, or returns
     * null when the drawing does not read as one of them. Pen strokes only — a highlighter sweep is
     * never a shape. Colour, width and opacity carry over to the tidy strokes.
     *
     * A line is recognised by how little it bends. A closed loop is simplified down to its corners:
     * four right-angled corners sitting on the drawing make a rectangle, three make a triangle, and
     * anything that hugs its own bounding box evenly is an ellipse.
     */
    fun tidy(stroke: Stroke): List<Stroke>? {
        if (stroke.tool != Tool.PEN) return null
        if (stroke.points.size < 5) return null
        // Cheap span check before smoothing: tiny ticks never tidy.
        val rawBox = bounds(listOf(stroke)) ?: return null
        val rawSpan = hypot(rawBox[2] - rawBox[0], rawBox[3] - rawBox[1])
        if (rawSpan < 26f) return null
        val points = smooth(stroke.points)
        if (points.size < 5) return null
        val box = bounds(listOf(stroke)) ?: return null
        val span = hypot(box[2] - box[0], box[3] - box[1])
        if (span < 26f || pointsLength(points) < TIDY_MIN_LENGTH) return null
        if (!isClosed(points, span)) {
            var worst = 0f
            points.forEach { worst = max(worst, segmentDistance(it, points.first(), points.last())) }
            return if (worst <= max(2.5f, span * TIDY_LINE_SHARE))
                listOf(stroke.copy(tool = Tool.LINE, points = listOf(points.first(), points.last()))) else null
        }
        val loop = simplify(points + points.first(), span * 0.11f).let {
            if (it.size > 1 && distance(it.first(), it.last()) < span * 0.02f) it.dropLast(1) else it
        }
        val corners = collapseStraight(loop)
        if (span >= 60f && corners.size == 4 && corners.indices.all { turnAt(it, corners) in 45f..135f } &&
            polygonDeviation(points, corners) <= span * 0.075f) return listOf(axisAligned(stroke, Tool.RECTANGLE, box))
        if (readsAsEllipse(points, box)) return listOf(axisAligned(stroke, Tool.ELLIPSE, box))
        if (corners.size == 3) return closesInto(stroke, corners)
        return null
    }

    /** True when the ends come near enough to read the stroke as a closed loop. */
    private fun isClosed(points: List<InkPoint>, span: Float) =
        distance(points.first(), points.last()) < span * TIDY_CLOSE_SHARE

    private fun axisAligned(stroke: Stroke, tool: Tool, box: FloatArray) = stroke.copy(tool = tool,
        points = listOf(InkPoint(box[0], box[1]), InkPoint(box[2], box[3])))

    /** A closed triangle becomes three clean edges, so it erases and exports like any other ink. */
    private fun closesInto(stroke: Stroke, corners: List<InkPoint>): List<Stroke> =
        corners.indices.map { i -> stroke.copy(tool = Tool.LINE, points = listOf(corners[i], corners[(i + 1) % corners.size])) }

    private fun pointsLength(points: List<InkPoint>): Float {
        var total = 0f
        for (i in 1..points.lastIndex) total += distance(points[i - 1], points[i])
        return total
    }

    /** Drops corners that barely bend, so a chamfered rectangle still reads as four corners. */
    private fun collapseStraight(corners: List<InkPoint>): List<InkPoint> {
        if (corners.size <= 3) return corners
        val kept = corners.indices.filter { turnAt(it, corners) >= 25f }.map { corners[it] }
        return if (kept.size >= 3) kept else corners
    }

    /** How far the path turns at corner [index], in degrees: 0 is straight, 90 is a right angle. */
    private fun turnAt(index: Int, corners: List<InkPoint>): Float {
        val previous = corners[(index - 1 + corners.size) % corners.size]
        val current = corners[index]
        val next = corners[(index + 1) % corners.size]
        val incoming = atan2(current.y - previous.y, current.x - previous.x)
        val outgoing = atan2(next.y - current.y, next.x - current.x)
        var turn = abs(Math.toDegrees((outgoing - incoming).toDouble()).toFloat())
        if (turn > 180f) turn = 360f - turn
        return turn
    }

    /** The worst distance from any sample to the closed corner polygon, i.e. the fit error. */
    private fun polygonDeviation(points: List<InkPoint>, corners: List<InkPoint>): Float {
        var worst = 0f
        points.forEach { point ->
            var nearest = Float.MAX_VALUE
            corners.indices.forEach { i ->
                nearest = min(nearest, segmentDistance(point, corners[i], corners[(i + 1) % corners.size]))
            }
            worst = max(worst, nearest)
        }
        return worst
    }

    /**
     * A closed loop reads as an ellipse when its samples sit an even distance from the centre, once
     * each axis is scaled to its own half-width. A rectangle fails this: its corners reach further
     * than its edge midpoints.
     */
    private fun readsAsEllipse(points: List<InkPoint>, box: FloatArray): Boolean {
        val halfWidth = (box[2] - box[0]) / 2f; val halfHeight = (box[3] - box[1]) / 2f
        if (halfWidth < 1f || halfHeight < 1f) return false
        val cx = (box[0] + box[2]) / 2f; val cy = (box[1] + box[3]) / 2f
        // Single pass mean + M2 (Welford) instead of map + average + sumOf (3 passes).
        var mean = 0.0
        var m2 = 0.0
        var n = 0
        for (p in points) {
            val r = hypot((p.x - cx) / halfWidth, (p.y - cy) / halfHeight).toDouble()
            n++
            val delta = r - mean
            mean += delta / n
            m2 += delta * (r - mean)
        }
        if (n == 0 || mean <= 0.0) return false
        val deviation = sqrt(m2 / n).toFloat()
        return deviation / mean.toFloat() < .13f
    }

    /** Douglas–Peucker: keeps only the samples that carry the drawing's corners. */
    private fun simplify(points: List<InkPoint>, tolerance: Float): List<InkPoint> {
        if (points.size < 3) return points
        val first = points.first(); val last = points.last()
        var worst = 0f; var index = 0
        for (i in 1 until points.size - 1) {
            val gap = segmentDistance(points[i], first, last)
            if (gap > worst) { worst = gap; index = i }
        }
        if (worst <= tolerance) return listOf(first, last)
        return simplify(points.subList(0, index + 1), tolerance).dropLast(1) +
            simplify(points.subList(index, points.size), tolerance)
    }

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

    /**
     * Snapped endpoints for a shape drag. On grid paper both points snap to the grid;
     * for a line the far end is additionally angle-snapped so horizontal/vertical/diagonal
     * are effortless. Returns the (possibly snapped) pair.
     */
    fun snappedShapePoints(a: InkPoint, b: InkPoint, paper: Paper, snapEnabled: Boolean): Pair<InkPoint, InkPoint> {
        if (!snapEnabled) return a to b
        var sa = a; var sb = b
        if (paper.isGrid) {
            val s = paper.gridSpacing
            sa = snapToGrid(sa, s)
            sb = snapToGrid(sb, s)
        }
        // Angle snap only for lines — rectangles/ellipses keep their grid corners.
        return sa to sb
    }

    /**
     * Two LINE strokes that form coordinate axes centred on [page]. Uses the page's own
     * width/height so axes always span most of the sheet. Arrowheads are separate short
     * strokes so they export and erase as expected.
     */
    fun mathAxes(page: NotePage, color: Int = 0xFF5A646E.toInt(), width: Float = 1.7f): List<Stroke> {
        val cx = page.width / 2f; val cy = page.height / 2f
        val pad = 22f
        val axis = listOf(
            Stroke(Tool.LINE, color, width, listOf(InkPoint(pad, cy), InkPoint(page.width - pad, cy))),
            Stroke(Tool.LINE, color, width, listOf(InkPoint(cx, pad), InkPoint(cx, page.height - pad)))
        )
        // Small V arrowheads — four 9 px ticks so the graph reads at a glance.
        val ah = 9f
        val arrows = listOf(
            Stroke(Tool.LINE, color, width, listOf(InkPoint(page.width - pad - ah, cy - ah / 1.9f), InkPoint(page.width - pad, cy))),
            Stroke(Tool.LINE, color, width, listOf(InkPoint(page.width - pad - ah, cy + ah / 1.9f), InkPoint(page.width - pad, cy))),
            Stroke(Tool.LINE, color, width, listOf(InkPoint(pad + ah, cy - ah / 1.9f), InkPoint(pad, cy))),
            Stroke(Tool.LINE, color, width, listOf(InkPoint(pad + ah, cy + ah / 1.9f), InkPoint(pad, cy))),
            Stroke(Tool.LINE, color, width, listOf(InkPoint(cx - ah / 1.9f, pad + ah), InkPoint(cx, pad))),
            Stroke(Tool.LINE, color, width, listOf(InkPoint(cx + ah / 1.9f, pad + ah), InkPoint(cx, pad))),
            Stroke(Tool.LINE, color, width, listOf(InkPoint(cx - ah / 1.9f, page.height - pad - ah), InkPoint(cx, page.height - pad))),
            Stroke(Tool.LINE, color, width, listOf(InkPoint(cx + ah / 1.9f, page.height - pad - ah), InkPoint(cx, page.height - pad)))
        )
        return axis + arrows
    }
}

internal fun decodeMistakeReviews(o: JSONObject): List<com.folio.notes.mistakes.LocalMistakeReviewAttempt> =
    o.optJSONArray("mistakeReviews")?.let { a -> (0 until a.length()).map {
        com.folio.notes.mistakes.LocalMistakeReviewAttempt.decode(a.getJSONObject(it))
    } }.orEmpty()
