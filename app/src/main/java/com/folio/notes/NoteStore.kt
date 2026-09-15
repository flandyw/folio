package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stroke and text encoding, shared by the portable whole-notebook codec (which a `.folio` archive
 * carries) and the per-page store, so both read and write ink identically.
 */
object InkCodec {
    fun encodeStrokes(strokes: List<Stroke>): JSONArray = JSONArray().apply {
        strokes.forEach { s -> put(JSONObject().apply {
            put("opacity", s.opacity); put("tool", s.tool.name); put("color", s.color); put("width", s.width)
            if (s.createdAt > 0L) put("createdAt", s.createdAt)
            if (s.style != StrokeStyle.SOLID) put("style", s.style.name)
            put("points", JSONArray().apply { s.points.forEach { put(JSONArray(listOf(it.x, it.y, it.pressure))) } })
        }) }
    }

    fun decodeStrokes(array: JSONArray?): List<Stroke> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            val s = array.getJSONObject(index)
            val points = s.getJSONArray("points")
            Stroke(Tool.valueOf(s.getString("tool")), s.getInt("color"), s.getDouble("width").toFloat(),
                (0 until points.length()).map { i -> val pt = points.getJSONArray(i)
                    InkPoint(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat(), pt.getDouble(2).toFloat()) },
                s.optDouble("opacity", if (s.getString("tool") == "HIGHLIGHTER") 72.0 / 255.0 else 1.0).toFloat(),
                if (s.has("createdAt") && !s.isNull("createdAt")) s.optLong("createdAt") else 0L,
                if (s.isNull("style")) StrokeStyle.SOLID else StrokeStyle.safeValueOf(s.optString("style", "SOLID")))
        }
    }

    fun encodeTexts(texts: List<TextBox>): JSONArray = JSONArray().apply {
        texts.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("x", t.x); put("y", t.y); put("w", t.width); put("text", t.text)
            put("size", t.size); put("color", t.color); put("bold", t.bold); put("italic", t.italic)
            if (t.align != TextAlignMode.LEFT) put("align", t.align.name)
            if (t.underline) put("underline", true)
        }) }
    }

    fun decodeTexts(array: JSONArray?): List<TextBox> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            val t = array.getJSONObject(index)
            TextBox(t.getString("id"), t.getDouble("x").toFloat(), t.getDouble("y").toFloat(),
                t.getDouble("w").toFloat(), t.getString("text"), t.getDouble("size").toFloat(),
                t.getInt("color"), t.optBoolean("bold", false), t.optBoolean("italic", false),
                if (t.isNull("align")) TextAlignMode.LEFT else TextAlignMode.safeValueOf(t.optString("align", "LEFT")),
                t.optBoolean("underline", false))
        }
    }

    fun encodeImages(images: List<PageImage>): JSONArray = JSONArray().apply {
        images.forEach { image -> put(JSONObject().apply {
            put("id", image.id); put("x", image.x); put("y", image.y)
            put("w", image.width); put("h", image.height)
        }) }
    }

    fun decodeImages(array: JSONArray?): List<PageImage> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            PageImage(o.getString("id"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                o.getDouble("w").toFloat(), o.getDouble("h").toFloat())
        }
    }
}

/**
 * The stored shape of a notebook, one file per notebook:
 *
 * ```
 * files/notebooks/<uuid>/note.json        notebook fields plus one summary per page, with no ink
 * files/notebooks/<uuid>/pages/<id>.json  one page's ink and typed text, read on demand
 * ```
 *
 * Version 1 kept every page inline in `note.json`, which is also the portable shape a `.folio`
 * archive carries, so [NoteCodec] still reads and writes that. Version 2 was the split index
 * before exam metadata existed; version 3 adds exam tags, attempts, the exam-set link and the
 * per-page redo flag. Version 4 adds the first-page-versus-default cover choice. Version 5 adds
 * exam timing telemetry onto attempts; stroke timestamps ride inside the page files, which need
 * no version of their own because older files simply decode them as unknown.
 * An older file is migrated the first time it is opened.
 */
object NoteMetaCodec {
    const val VERSION = 5

    fun isCurrent(value: String): Boolean = try { JSONObject(value).optInt("version") == VERSION } catch (_: Exception) { false }

    /** True when [value] is the version-2 split index, which carries no ink but no exam tags either. */
    fun isSplitIndex(value: String): Boolean = try { JSONObject(value).optInt("version") == 2 } catch (_: Exception) { false }

    /** True for a version-3 index, which is current except for the cover choice (defaults to page cover). */
    fun isVersion3(value: String): Boolean = try { JSONObject(value).optInt("version") == 3 } catch (_: Exception) { false }

    /** True for a version-4 index, which is current except for attempt telemetry (defaults to none). */
    fun isVersion4(value: String): Boolean = try { JSONObject(value).optInt("version") == 4 } catch (_: Exception) { false }

    fun encode(note: Notebook): String = JSONObject().apply {
        put("version", VERSION); put("id", note.id); put("title", note.title)
        put("folder", note.folderId ?: JSONObject.NULL); put("cover", note.cover)
        put("starred", note.starred); put("updated", note.updated)
        put("exam", ExamTagsCodec.encode(note.exam))
        put("set", note.setId ?: JSONObject.NULL)
        put("attempts", ExamTagsCodec.encodeAttempts(note.attempts))
        put("pageCover", note.pageCover)
        put("pages", JSONArray().apply { note.pages.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("width", p.width); put("height", p.height)
            put("paper", p.paper.name); put("pdf", p.pdfIndex ?: JSONObject.NULL); put("revision", p.revision)
            if (p.redoFlag) put("redo", true)
            if (p.infinite) put("infinite", true)
            put("title", p.title); put("bookmarked", p.bookmarked)
            p.peekAnchor?.let { put("peekAnchor", it.encode()) }
        }) } })
    }.toString()

    /** The notebook's shape: every page present in order, none of them carrying content. */
    fun decode(value: String): Notebook = decodeIndex(value, VERSION)

    /** Reads the version-2 split index during migration; exam fields simply default. */
    fun decodeSplit(value: String): Notebook = decodeIndex(value, 2)

    /** Reads a version-3 index during migration; the cover choice defaults to the first page. */
    fun decodeVersion3(value: String): Notebook = decodeIndex(value, 3)

    /** Reads a version-4 index during migration; attempts simply carry no telemetry. */
    fun decodeVersion4(value: String): Notebook = decodeIndex(value, 4)

    private fun decodeIndex(value: String, version: Int): Notebook {
        val o = JSONObject(value)
        require(o.getInt("version") == version) { "Unsupported notebook index version" }
        return Notebook(o.getString("id"), o.getString("title"),
            if (o.isNull("folder")) null else o.getString("folder"), o.getInt("cover"),
            o.getBoolean("starred"), o.getLong("updated"), o.getJSONArray("pages").objects().map { p ->
                NotePage(p.getString("id"), p.getDouble("width").toFloat(), p.getDouble("height").toFloat(),
                    Paper.safeValueOf(p.getString("paper")), if (p.isNull("pdf")) null else p.getInt("pdf"),
                    revision = p.optInt("revision", 0), loaded = false, redoFlag = p.optBoolean("redo", false), infinite = p.optBoolean("infinite", false),
                    title = p.optString("title", ""), bookmarked = p.optBoolean("bookmarked", false), peekAnchor = PeekAnchor.decode(p.optJSONObject("peekAnchor")))
            }.also { require(it.isNotEmpty()) { "Notebook has no pages" } },
            exam = ExamTagsCodec.decode(o.optJSONObject("exam")),
            setId = if (o.isNull("set")) null else o.optString("set"),
            attempts = ExamTagsCodec.decodeAttempts(o.optJSONArray("attempts")),
            pageCover = o.optBoolean("pageCover", true))
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}

/** One page's ink and text, written and read on its own so a long notebook stays lazy. */
object NotePageCodec {
    const val VERSION = 1

    fun encode(page: NotePage): String = JSONObject().apply {
        put("version", VERSION)
        put("strokes", InkCodec.encodeStrokes(page.strokes))
        put("texts", InkCodec.encodeTexts(page.texts))
        put("images", InkCodec.encodeImages(page.images))
    }.toString()

    /**
     * Reads a page file back onto its index entry, which is where its size, paper and revision live.
     * The revision is deliberately taken from the summary rather than the file: the index is what
     * caches key on, so the two must not be able to disagree.
     */
    fun decode(value: String, summary: NotePage): NotePage {
        val o = JSONObject(value)
        require(o.getInt("version") == VERSION) { "Unsupported page version" }
        return summary.copy(
            strokes = InkCodec.decodeStrokes(o.optJSONArray("strokes")),
            texts = InkCodec.decodeTexts(o.optJSONArray("texts")),
            images = InkCodec.decodeImages(o.optJSONArray("images")),
            loaded = true
        )
    }
}

/**
 * Names for the on-disk page previews. A preview's name carries the size it was drawn at and the
 * page revision, so it is either exactly right or absent: an edited page or a different size simply
 * misses the cache, and only copies of the same page at the same size can be pruned.
 */
object ThumbnailKeys {
    fun name(pageId: String, revision: Int, widthPx: Int): String = "$pageId-$widthPx-$revision.png"

    /** True when [fileName] is a preview of [pageId] at [widthPx] left over from an earlier revision. */
    fun isStale(fileName: String, pageId: String, revision: Int, widthPx: Int): Boolean =
        fileName.startsWith("$pageId-$widthPx-") && fileName != name(pageId, revision, widthPx)
}
