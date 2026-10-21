package com.folio.notes.model

import com.folio.notes.document.DocumentHistoryCodec
import com.folio.notes.exam.ExamTagsCodec
import com.folio.notes.marking.LongResponseCodec
import com.folio.notes.mistakes.LocalMistakeReviewAttempt
import com.folio.notes.storage.InkCodec
import com.folio.notes.storage.NotebookTags
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

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
        if (note.pinned) put("pinned", true)
        if (note.pinnedInFolder) put("pinnedInFolder", true)
        if (note.documentHistory.enabled) put("documentHistory", DocumentHistoryCodec.encode(note.documentHistory))
        put("exam", ExamTagsCodec.encode(note.exam))
        if (note.tags.isNotEmpty()) put("tags", JSONArray(note.tags))
        put("attempts", ExamTagsCodec.encodeAttempts(note.attempts))
        if (!note.pageCover) put("pageCover", false)
        note.defaultPaper?.let { put("defaultPaper", it.name) }
        note.longResponse?.let { put("longResponse", LongResponseCodec.encode(it)) }
        if (note.feedbackActions.isNotEmpty()) put("feedbackActions", LongResponseCodec.encodeActions(note.feedbackActions))
        put("mistakePractice", note.mistakePractice)
        note.musicScoreId?.let { put("musicScoreId", it) }
        put("mistakeReviews", JSONArray(note.mistakeReviews.map { it.encode() }))
        note.peekAnchor?.let { put(PeekAnchor.KEY, it.encode()) }
        put("pages", JSONArray().apply { note.pages.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("width", p.width); put("height", p.height); put("paper", p.paper.name)
            put("pdf", p.pdfIndex ?: JSONObject.NULL); put("revision", p.revision)
            if (p.redoFlag) put("redo", true)
            if (p.infinite) put("infinite", true)
            put("title", p.title); put("bookmarked", p.bookmarked)
            put("strokes", InkCodec.encodeStrokes(p.strokes))
            put("texts", InkCodec.encodeTexts(p.texts))
            put("images", InkCodec.encodeImages(p.images))
            if (p.layers.isNotEmpty()) put("layers", InkCodec.encodeLayers(p.layers))
        }) } })
    }.toString()

    fun decode(value: String): Notebook {
        val o = JSONObject(value)
        require(o.getInt("version") == 1) { "Unsupported notebook version" }
        val pageObjects = o.getJSONArray("pages").objects()
        return Notebook(o.getString("id"), o.getString("title"),
            if (o.isNull("folder")) null else o.getString("folder"), o.getInt("cover"),
            o.getBoolean("starred"), o.getLong("updated"), pageObjects.map { p ->
                NotePage(p.getString("id"), p.getDouble("width").toFloat(), p.getDouble("height").toFloat(),
                    Paper.safeValueOf(p.getString("paper")), if (p.isNull("pdf")) null else p.getInt("pdf"),
                    InkCodec.decodeStrokes(p.optJSONArray("strokes")), InkCodec.decodeTexts(p.optJSONArray("texts")),
                    InkCodec.decodeImages(p.optJSONArray("images")),
                    p.optInt("revision", 0), redoFlag = p.optBoolean("redo", false), infinite = p.optBoolean("infinite", false),
                    title = p.optString("title", ""), bookmarked = p.optBoolean("bookmarked", false),
                    layers = InkCodec.decodeLayers(p.optJSONArray("layers")))
            }.also { require(it.isNotEmpty()) { "Notebook has no pages" } },
            ExamTagsCodec.decode(o.optJSONObject("exam")),
            ExamTagsCodec.decodeAttempts(o.optJSONArray("attempts")),
            // Older backups have no cover choice and default to the first-page cover.
            pageCover = o.optBoolean("pageCover", true),
            mistakePractice = o.optBoolean("mistakePractice", false),
            musicScoreId = o.optString("musicScoreId", "").takeIf { it.isNotEmpty() },
            mistakeReviews = decodeMistakeReviews(o),
            defaultPaper = o.optString("defaultPaper", "").takeIf { it.isNotEmpty() }?.let(Paper::safeValueOf),
            longResponse = LongResponseCodec.decode(o.optJSONObject("longResponse")),
            feedbackActions = LongResponseCodec.decodeActions(o.optJSONArray("feedbackActions")),
            pinned = o.optBoolean("pinned", false),
            pinnedInFolder = o.optBoolean("pinnedInFolder", false),
            tags = NotebookTags.decode(o.optJSONArray("tags")),
            documentHistory = DocumentHistoryCodec.decode(o.optJSONObject("documentHistory"))).let { note ->
                note.copy(peekAnchor = PeekAnchor.decodeNotebook(o, note.pages, pageObjects))
            }
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}

internal fun decodeMistakeReviews(o: JSONObject): List<LocalMistakeReviewAttempt> =
    o.optJSONArray("mistakeReviews")?.let { a -> (0 until a.length()).map {
        LocalMistakeReviewAttempt.decode(a.getJSONObject(it))
    } }.orEmpty()
