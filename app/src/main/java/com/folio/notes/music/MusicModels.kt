package com.folio.notes.music

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class MusicMark(val page: Int, val name: String)
internal data class MusicPoint(val x: Float, val y: Float)
internal data class MusicStroke(val page: Int, val points: List<MusicPoint>)
internal data class MusicScore(
    val id: String, val title: String, val pages: Int,
    val composer: String = "", val part: String = "", val notes: String = "",
    val starred: Boolean = false, val page: Int = 0, val bpm: Int = 80, val beats: Int = 4,
    val marks: List<MusicMark> = emptyList(), val ink: List<MusicStroke> = emptyList(),
    /** Epoch millis the score was last opened in the reader; 0 = never. Optional in version 1. */
    val opened: Long = 0,
)
internal data class MusicSet(val id: String, val name: String, val scores: List<String> = emptyList())
internal data class MusicLibrary(val scores: List<MusicScore> = emptyList(), val sets: List<MusicSet> = emptyList())

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
            MusicScore(id, s.getString("title"), pages, s.optString("composer"), s.optString("part"),
                s.optString("notes"), s.optBoolean("starred"), s.optInt("page").coerceIn(0, pages - 1),
                s.optInt("bpm", 80).coerceIn(30, 240), s.optInt("beats", 4).coerceIn(1, 12),
                s.optJSONArray("marks").objects().map { MusicMark(it.getInt("page"), it.getString("name")) }.filter { it.page in 0 until pages },
                s.optJSONArray("ink").objects().map { stroke ->
                    val points = stroke.getJSONArray("points")
                    MusicStroke(stroke.getInt("page"), (0 until points.length()).map { i ->
                        val p = points.getJSONArray(i)
                        MusicPoint(p.getDouble(0).toFloat().coerceIn(0f, 1f), p.getDouble(1).toFloat().coerceIn(0f, 1f))
                    })
                }.filter { it.page in 0 until pages }, s.optLong("opened").coerceAtLeast(0))
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
                .put("ink", JSONArray(s.ink.map { stroke -> JSONObject().put("page", stroke.page)
                    .put("points", JSONArray(stroke.points.map { JSONArray(listOf(it.x, it.y)) })) }))
        })).put("sets", JSONArray(library.sets.map {
            JSONObject().put("id", it.id).put("name", it.name).put("scores", JSONArray(it.scores))
        })).toString()
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
