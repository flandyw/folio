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
    /**
     * Pencil marks per page (strokes plus labels), for the shelf and the page grid. The marks
     * themselves live in the score's notebook, written by the real editor; [ink] and [texts] only
     * hold what an older Folio drew, until the first open carries it over and empties them.
     */
    val pencil: List<Int> = emptyList(),
    /** Where a new score's pencil marks come from on its first open: pages of another score's notebook. */
    val seed: MusicSeed? = null,
)
/** Copy [pages] (indexes into the source score, in order) from the source score's notebook. */
internal data class MusicSeed(val from: String, val pages: List<Int>)
internal data class MusicSet(val id: String, val name: String, val scores: List<String> = emptyList())
internal data class MusicLibrary(val scores: List<MusicScore> = emptyList(), val sets: List<MusicSet> = emptyList())

/** Pencil marks on one page of a score. */
internal fun MusicScore.pencilOn(page: Int): Int = pencil.getOrNull(page) ?: 0

internal fun validMusicId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

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
                pencil = s.optJSONArray("pencil")?.let { a -> (0 until minOf(a.length(), pages)).map { a.getInt(it).coerceAtLeast(0) } } ?: emptyList(),
                seed = s.optJSONObject("seed")?.let { o ->
                    val from = o.optString("from")
                    val list = o.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
                    if (validMusicId(from) && list.isNotEmpty()) MusicSeed(from, list) else null
                },
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
                .apply { if (s.pencil.any { it > 0 }) put("pencil", JSONArray(s.pencil)) }
                .apply { s.seed?.let { put("seed", JSONObject().put("from", it.from).put("pages", JSONArray(it.pages))) } }
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
