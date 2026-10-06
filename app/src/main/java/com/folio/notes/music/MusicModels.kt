package com.folio.notes.music

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class MusicMark(val page: Int, val name: String)
internal data class MusicPoint(val x: Float, val y: Float)

/** Default annotation ink: the reader's rehearsal blue. */
internal val MUSIC_INK = 0xFF154DB4.toInt()

/** The editor's own tool names, so a score's annotations read the same as a page's ink. */
internal const val MUSIC_PEN = "PEN"
internal const val MUSIC_HIGHLIGHTER = "HIGHLIGHTER"
internal const val MUSIC_SOLID = "SOLID"

/** Stroke width in the editor's scale, where a page is 1000 units wide. */
internal const val MUSIC_WIDTH = 3f

/** Text size in the same scale: 40 is 4% of the page width, a legible rehearsal label. */
internal const val MUSIC_TEXT_SIZE = 40f

/**
 * One annotation on a score: a freehand pen or highlighter stroke, or a shape, which keeps only the
 * two drag corners and is expanded by [MusicInk.shapePoints] at render time — exactly how the
 * editor stores a shape. Every field has a default that reproduces the original plain blue pen, so
 * an index written before annotations had styles still reads, and a default stroke writes no extra
 * keys (an older Folio still opens the index).
 */
internal data class MusicStroke(
    val page: Int, val points: List<MusicPoint>,
    val tool: String = MUSIC_PEN, val color: Int = MUSIC_INK, val width: Float = MUSIC_WIDTH,
    val opacity: Float = 1f, val style: String = MUSIC_SOLID,
)

/** A typed label on a score: a rehearsal letter, a dynamic, a reminder. */
internal data class MusicText(
    val page: Int, val x: Float, val y: Float, val text: String,
    val color: Int = MUSIC_INK, val size: Float = MUSIC_TEXT_SIZE,
)

internal data class MusicScore(
    val id: String, val title: String, val pages: Int,
    val composer: String = "", val part: String = "", val notes: String = "",
    val starred: Boolean = false, val page: Int = 0, val bpm: Int = 80, val beats: Int = 4,
    val marks: List<MusicMark> = emptyList(), val ink: List<MusicStroke> = emptyList(),
    /** Epoch millis the score was last opened in the reader; 0 = never. Optional in version 1. */
    val opened: Long = 0,
    /** Typed labels, added alongside ink when the reader gained the editor's tools. */
    val texts: List<MusicText> = emptyList(),
)
internal data class MusicSet(val id: String, val name: String, val scores: List<String> = emptyList())
internal data class MusicLibrary(val scores: List<MusicScore> = emptyList(), val sets: List<MusicSet> = emptyList())

internal fun validMusicId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

/**
 * The score's annotation edits, in page fractions, kept free of Android types so they stay cheap to
 * exercise. The reader is the only caller; the editor's own ink engine is far heavier (layers,
 * pressure, whole-page journal edits) and has nothing to say about a bitmap score. It carves its
 * shapes out of two drag corners rather than reusing `InkGeometry`, which lives in an Android-heavy
 * model file the pure smoke build cannot compile — the engine here is a tenth of its size.
 */
internal object MusicInk {
    /** Shape kinds the reader offers; every one expands from two drag corners. */
    val SHAPES = setOf("LINE", "RECTANGLE", "ELLIPSE", "TRIANGLE", "DIAMOND", "PENTAGON", "HEXAGON", "STAR")

    fun isShape(tool: String) = tool in SHAPES

    /** A freehand path the pen or highlighter drew. */
    fun isFreehand(tool: String) = tool == MUSIC_PEN || tool == MUSIC_HIGHLIGHTER

    /**
     * A shape's outline in page fractions, from its two drag corners. Closed shapes repeat their
     * first point so the renderer can stroke them as one open path, as the editor's does.
     */
    fun shapePoints(tool: String, points: List<MusicPoint>): List<MusicPoint> {
        if (points.size < 2) return points
        val a = points.first(); val b = points.last()
        fun polygon(vertices: Int, star: Boolean = false): List<MusicPoint> {
            val unit = (0 until vertices).map { i ->
                val angle = -Math.PI / 2 + i * 2 * Math.PI / vertices
                val radius = if (star && i % 2 == 1) 0.42 else 1.0
                MusicPoint((Math.cos(angle) * radius).toFloat(), (Math.sin(angle) * radius).toFloat())
            }
            val minX = unit.minOf { it.x }; val maxX = unit.maxOf { it.x }
            val minY = unit.minOf { it.y }; val maxY = unit.maxOf { it.y }
            val left = min(a.x, b.x); val top = min(a.y, b.y)
            val outline = unit.map {
                MusicPoint(left + (it.x - minX) / (maxX - minX) * Math.abs(b.x - a.x),
                    top + (it.y - minY) / (maxY - minY) * Math.abs(b.y - a.y))
            }
            return outline + outline.first()
        }
        return when (tool) {
            "LINE" -> listOf(a, b)
            "RECTANGLE" -> listOf(a, MusicPoint(b.x, a.y), b, MusicPoint(a.x, b.y), a)
            "TRIANGLE" -> polygon(3)
            "DIAMOND" -> polygon(4)
            "PENTAGON" -> polygon(5)
            "HEXAGON" -> polygon(6)
            "STAR" -> polygon(10, star = true)
            "ELLIPSE" -> (0..64).map { i ->
                val t = i * 2 * Math.PI / 64
                MusicPoint((a.x + b.x) / 2 + Math.abs(b.x - a.x) / 2 * Math.cos(t).toFloat(),
                    (a.y + b.y) / 2 + Math.abs(b.y - a.y) / 2 * Math.sin(t).toFloat())
            }
            else -> points
        }
    }

    /** The drawn outline of [stroke]: a shape expands, freehand ink is its own samples. */
    fun outline(stroke: MusicStroke): List<MusicPoint> =
        if (isShape(stroke.tool)) shapePoints(stroke.tool, stroke.points) else stroke.points

    /** A label's frame in page fractions, sized from its text and font size. */
    fun textBounds(text: MusicText): FloatArray {
        val height = text.size * 1.3f / 1000f
        val width = max(height * 0.5f, text.text.length * text.size * 0.55f / 1000f)
        return floatArrayOf(text.x, text.y, text.x + width, text.y + height)
    }

    /** True when [at] falls on a label, so a tap can edit rather than create. */
    fun hits(text: MusicText, at: MusicPoint, pad: Float = 0f): Boolean {
        val frame = textBounds(text)
        return at.x in (frame[0] - pad)..(frame[2] + pad) && at.y in (frame[1] - pad)..(frame[3] + pad)
    }

    /** True when two samples are within [pad] of each other in both axes. */
    fun within(point: MusicPoint, at: MusicPoint, pad: Float): Boolean =
        Math.abs(point.x - at.x) <= pad && Math.abs(point.y - at.y) <= pad

    fun textAt(texts: List<MusicText>, page: Int, at: MusicPoint): Int? =
        texts.indices.lastOrNull { texts[it].page == page && hits(texts[it], at) }

    /** Ray casting, so a freehand loop can enclose a stroke on any side. */
    fun insideLoop(loop: List<MusicPoint>, point: MusicPoint): Boolean {
        if (loop.size < 3) return false
        var inside = false
        var j = loop.lastIndex
        for (i in loop.indices) {
            val a = loop[i]; val b = loop[j]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** A stroke is selected only when every sample is enclosed, so a half-crossed stroke stays put. */
    fun selects(loop: List<MusicPoint>, stroke: MusicStroke): Boolean {
        val points = outline(stroke)
        return points.isNotEmpty() && points.all { insideLoop(loop, it) }
    }

    /** A label is selected when its centre is enclosed. */
    fun selects(loop: List<MusicPoint>, text: MusicText): Boolean {
        val frame = textBounds(text)
        return insideLoop(loop, MusicPoint((frame[0] + frame[2]) / 2, (frame[1] + frame[3]) / 2))
    }

    fun translated(stroke: MusicStroke, dx: Float, dy: Float) =
        stroke.copy(points = stroke.points.map { MusicPoint(it.x + dx, it.y + dy) })

    fun translated(text: MusicText, dx: Float, dy: Float) = text.copy(x = text.x + dx, y = text.y + dy)

    fun recolored(stroke: MusicStroke, color: Int) =
        stroke.copy(color = color, opacity = if (stroke.tool == MUSIC_HIGHLIGHTER) max(stroke.opacity, 0.35f) else stroke.opacity)

    fun recolored(text: MusicText, color: Int) = text.copy(color = color)

    /** A duplicated copy sits a little down and right, so it is visibly a second mark. */
    fun duplicate(stroke: MusicStroke) = translated(stroke, 0.02f, 0.02f)

    fun duplicate(text: MusicText) = translated(text, 0.02f, 0.02f)

    /**
     * The box holding every given mark as `[left, top, right, bottom]` in page fractions, or null
     * when there is nothing to frame. A shape is measured by its drawn outline, so its box is the
     * box the eye sees rather than the two corners it is stored from.
     */
    fun bounds(strokes: List<MusicStroke>, texts: List<MusicText>): FloatArray? {
        var left = 1f; var top = 1f; var right = 0f; var bottom = 0f; var any = false
        fun cover(x: Float, y: Float) {
            any = true; left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
        }
        strokes.forEach { stroke -> outline(stroke).forEach { cover(it.x, it.y) } }
        texts.forEach { text -> val frame = textBounds(text); cover(frame[0], frame[1]); cover(frame[2], frame[3]) }
        return if (any) floatArrayOf(left, top, right, bottom) else null
    }

    /**
     * The last mark drawn under [at], so a long press picks up the stroke the finger landed on
     * rather than the one buried under it. Invents no hit: an empty page returns null.
     */
    fun strokeAt(strokes: List<MusicStroke>, at: MusicPoint, pad: Float = 0.02f): Int? =
        strokes.indices.lastOrNull { i -> outline(strokes[i]).any { within(it, at, pad) } }

    /** A stroke with a new look, every field clamped to what the codec can store. */
    fun restyled(stroke: MusicStroke, color: Int = stroke.color, width: Float = stroke.width,
        opacity: Float = stroke.opacity, style: String = stroke.style): MusicStroke =
        stroke.copy(color = color, width = width.coerceIn(0.4f, 96f), opacity = opacity.coerceIn(0.05f, 1f), style = style)

    fun restyled(text: MusicText, color: Int = text.color, size: Float = text.size): MusicText =
        text.copy(color = color, size = size.coerceIn(8f, 200f))

    /** Clamps a mark back onto the page after a drag that ran off the edge. */
    fun normalized(stroke: MusicStroke): MusicStroke =
        stroke.copy(points = stroke.points.map { MusicPoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) })

    fun normalized(text: MusicText): MusicText = text.copy(x = text.x.coerceIn(0f, 1f), y = text.y.coerceIn(0f, 1f))

    /**
     * The rubber eraser. [whole] drops a touched mark; otherwise the touched samples are removed and
     * each surviving run becomes its own stroke, so a line can be shortened without vanishing.
     * Shapes have no samples to trim, so a touched corner always drops them.
     */
    fun erased(strokes: List<MusicStroke>, at: MusicPoint, radius: Float, whole: Boolean): List<MusicStroke> {
        val reach = radius.coerceAtLeast(0.004f)
        return strokes.flatMap { stroke ->
            val points = stroke.points
            val touched = { p: MusicPoint -> hypot(p.x - at.x, p.y - at.y) <= reach }
            when {
                points.none(touched) -> listOf(stroke)
                whole || isShape(stroke.tool) -> emptyList()
                else -> points.split(touched).map { run -> stroke.copy(points = run) }
            }
        }
    }

    /** Consecutive samples that survive an eraser pass, as separate runs. */
    private fun List<MusicPoint>.split(erased: (MusicPoint) -> Boolean): List<List<MusicPoint>> {
        val runs = mutableListOf<List<MusicPoint>>()
        var current = mutableListOf<MusicPoint>()
        forEach { point ->
            if (erased(point)) {
                if (current.isNotEmpty()) runs += current.toList()
                current = mutableListOf()
            } else current += point
        }
        if (current.isNotEmpty()) runs += current.toList()
        return runs
    }
}

/** Independent music schema. Notebook formats never participate in these reads or writes. */
internal object MusicCodec {
    fun decode(text: String): MusicLibrary {
        val json = JSONObject(text)
        require(json.getInt("version") == 1) { "This music library needs a newer Folio version" }
        return MusicLibrary(json.getJSONArray("scores").objects().map { s ->
            val id = s.getString("id")
            require(validMusicId(id)) { "Invalid score ID" }
            val pages = s.getInt("pages").also { require(it > 0) }
            MusicScore(id = id, title = s.getString("title"), pages = pages,
                composer = s.optString("composer"), part = s.optString("part"),
                notes = s.optString("notes"), starred = s.optBoolean("starred"),
                page = s.optInt("page").coerceIn(0, pages - 1),
                bpm = s.optInt("bpm", 80).coerceIn(30, 240), beats = s.optInt("beats", 4).coerceIn(1, 12),
                marks = s.optJSONArray("marks").objects().map { MusicMark(it.getInt("page"), it.getString("name")) }.filter { it.page in 0 until pages },
                ink = s.optJSONArray("ink").objects().map { stroke ->
                    val points = stroke.getJSONArray("points")
                    MusicStroke(stroke.getInt("page"), (0 until points.length()).map { i ->
                        val p = points.getJSONArray(i)
                        MusicPoint(p.getDouble(0).toFloat().coerceIn(0f, 1f), p.getDouble(1).toFloat().coerceIn(0f, 1f))
                    }, stroke.optString("tool", MUSIC_PEN),
                        stroke.optInt("color", MUSIC_INK),
                        stroke.optDouble("width", MUSIC_WIDTH.toDouble()).toFloat().coerceIn(0.4f, 96f),
                        stroke.optDouble("opacity", 1.0).toFloat().coerceIn(0.05f, 1f),
                        stroke.optString("style", MUSIC_SOLID))
                }.filter { it.page in 0 until pages },
                opened = s.optLong("opened").coerceAtLeast(0),
                texts = s.optJSONArray("texts").objects().map { label ->
                    val body = label.optString("text").take(200)
                    MusicText(label.getInt("page"), label.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f),
                        label.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f), body,
                        label.optInt("color", MUSIC_INK),
                        label.optDouble("size", MUSIC_TEXT_SIZE.toDouble()).toFloat().coerceIn(8f, 200f))
                }.filter { it.page in 0 until pages && it.text.isNotBlank() })
        }, json.getJSONArray("sets").objects().map { s ->
            val ids = s.getJSONArray("scores")
            MusicSet(s.getString("id"), s.getString("name"), (0 until ids.length()).map { ids.getString(it) })
        })
    }

    fun encode(library: MusicLibrary): String {
        return JSONObject().put("version", 1).put("scores", JSONArray(library.scores.map { s ->
            JSONObject().put("id", s.id).put("title", s.title).put("pages", s.pages).put("composer", s.composer)
                .put("part", s.part).put("notes", s.notes).put("starred", s.starred).put("page", s.page)
                .put("bpm", s.bpm).put("beats", s.beats).put("opened", s.opened)
                .put("marks", JSONArray(s.marks.map { JSONObject().put("page", it.page).put("name", it.name) }))
                .put("ink", JSONArray(s.ink.map { stroke -> strokeJson(stroke) }))
                // Only scores that carry labels mention them, so untouched indexes keep their shape.
                .apply { if (s.texts.isNotEmpty()) put("texts", JSONArray(s.texts.map { textJson(it) })) }
        })).put("sets", JSONArray(library.sets.map {
            JSONObject().put("id", it.id).put("name", it.name).put("scores", JSONArray(it.scores))
        })).toString()
    }

    /** A plain blue pen stroke writes no style keys, so an older Folio still reads the score. */
    private fun strokeJson(stroke: MusicStroke) = JSONObject().put("page", stroke.page)
        .put("points", JSONArray(stroke.points.map { JSONArray(listOf(it.x, it.y)) }))
        .apply {
            if (stroke.tool != MUSIC_PEN) put("tool", stroke.tool)
            if (stroke.color != MUSIC_INK) put("color", stroke.color)
            if (stroke.width != MUSIC_WIDTH) put("width", stroke.width.toDouble())
            if (stroke.opacity != 1f) put("opacity", stroke.opacity.toDouble())
            if (stroke.style != MUSIC_SOLID) put("style", stroke.style)
        }

    private fun textJson(text: MusicText) = JSONObject().put("page", text.page).put("text", text.text)
        .put("x", text.x.toDouble()).put("y", text.y.toDouble())
        .apply {
            if (text.color != MUSIC_INK) put("color", text.color)
            if (text.size != MUSIC_TEXT_SIZE) put("size", text.size.toDouble())
        }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
