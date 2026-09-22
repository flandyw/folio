package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject

/** One page's editable content, the unit a journal edit transforms. */
data class PageContent(
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextBox> = emptyList(),
    val images: List<PageImage> = emptyList()
) {
    companion object { val EMPTY = PageContent() }
}

/** A change to a page's strokes, expressed as the smallest thing that reproduces the new list. */
sealed interface StrokesEdit {
    /** [strokes] were appended after the ones already on the page. */
    data class Add(val strokes: List<Stroke>) : StrokesEdit
    /** The strokes at [indices] in the page's list were dropped. */
    data class Remove(val indices: List<Int>) : StrokesEdit
    /** The whole list was replaced, for a transform or a page that does not look like the old one. */
    data class Set(val strokes: List<Stroke>) : StrokesEdit
}

/**
 * One durable edit to a page. A null channel means "untouched", so a lasso that moved ink, text and
 * pictures together is a single journal record — and a single undo step. An empty list is not null:
 * it deliberately clears that channel.
 */
data class PageEdit(
    val strokes: StrokesEdit? = null,
    val texts: List<TextBox>? = null,
    val images: List<PageImage>? = null
) {
    val isEmpty: Boolean get() = strokes == null && texts == null && images == null
}

/** One journal line: an edit plus the page-local sequence number that orders it. */
data class JournalRecord(val seq: Int, val edit: PageEdit)

/** This page's editable content, the shape the journal edits. */
fun NotePage.content(): PageContent = PageContent(strokes, texts, images)

/**
 * The pure half of append-only page durability: it turns a before/after pair into the smallest edit
 * that reproduces the change, applies edits back onto content, and reads and writes the JSONL
 * records the repository appends. Nothing here touches the file system, so the whole log shape is
 * unit-testable without Android.
 *
 * Stroke comparison is by reference: the editor reuses stroke objects when it appends, erases,
 * undoes and redoes, so identity recognises "same stroke" without walking every point. A transform
 * that builds new objects simply falls back to a whole-list [StrokesEdit.Set].
 */
object PageJournal {
    /**
     * The cheapest edit that turns [before] into [after], or null when nothing changed. Texts and
     * pictures are small, so a changed channel is stored whole; ink, which dominates a page, keeps
     * its append/remove shape so a pen-up appends a handful of points instead of the page.
     */
    fun diff(before: PageContent, after: PageContent): PageEdit? {
        val strokes = diffStrokes(before.strokes, after.strokes)
        val texts = if (before.texts == after.texts) null else after.texts
        val images = if (before.images == after.images) null else after.images
        if (strokes == null && texts == null && images == null) return null
        return PageEdit(strokes, texts, images)
    }

    private fun diffStrokes(before: List<Stroke>, after: List<Stroke>): StrokesEdit? {
        if (before.size == after.size && before.indices.all { before[it] === after[it] }) return null
        // Append: every stroke the page already had is still the prefix of the new list.
        if (after.size > before.size && before.indices.all { before[it] === after[it] }) {
            return StrokesEdit.Add(after.subList(before.size, after.size).toList())
        }
        // Removal: walk the old list and pick out the strokes the new one no longer holds.
        if (after.size < before.size) {
            val removed = ArrayList<Int>()
            var kept = 0
            for (index in before.indices) {
                if (kept < after.size && before[index] === after[kept]) kept++ else removed += index
            }
            if (kept == after.size) {
                // Removing more than half is cheaper to write as the survivors than the casualties.
                return if (removed.size > after.size) StrokesEdit.Set(after) else StrokesEdit.Remove(removed)
            }
        }
        return StrokesEdit.Set(after)
    }

    /** Replays one edit onto [content]; a null channel is left exactly as it was. */
    fun apply(content: PageContent, edit: PageEdit): PageContent = PageContent(
        strokes = when (val strokes = edit.strokes) {
            null -> content.strokes
            is StrokesEdit.Add -> content.strokes + strokes.strokes
            is StrokesEdit.Remove -> {
                val removed = strokes.indices.toHashSet()
                content.strokes.filterIndexed { index, _ -> index !in removed }
            }
            is StrokesEdit.Set -> strokes.strokes
        },
        texts = edit.texts ?: content.texts,
        images = edit.images ?: content.images
    )

    /**
     * Replays [records] in order, skipping any already folded into [baseSeq]'s snapshot. Skipping is
     * what makes compaction crash-safe: if the process dies after the new snapshot lands but before
     * the journal is cleared, the next open simply ignores the records the snapshot already holds.
     */
    fun replay(base: PageContent, baseSeq: Int, records: List<JournalRecord>): PageContent {
        var content = base
        for (record in records) if (record.seq > baseSeq) content = apply(content, record.edit)
        return content
    }

    /** The highest sequence number in [records], or 0 when the journal is empty. */
    fun lastSeq(records: List<JournalRecord>): Int = records.maxOfOrNull { it.seq } ?: 0

    // ---- Wire format ---------------------------------------------------------------------

    /**
     * The byte length of the longest prefix of a journal file that is complete records. A process
     * death can leave a final line cut in half; trimming to this length lets the next append follow
     * a clean record instead of hiding behind a torn one, and lets a reader keep every record before
     * it. A final line with no newline is by definition incomplete and is never counted.
     */
    fun completePrefixLength(bytes: ByteArray): Int {
        var start = 0
        var validEnd = 0
        val newlineByte = '\n'.code.toByte()
        while (start < bytes.size) {
            var newline = -1
            var cursor = start
            while (cursor < bytes.size) { if (bytes[cursor] == newlineByte) { newline = cursor; break }; cursor++ }
            if (newline < 0) break
            val text = String(bytes, start, newline - start, Charsets.UTF_8)
            if (text.isBlank() || decode(text) != null) validEnd = newline + 1 else break
            start = newline + 1
        }
        return validEnd
    }

    /** One JSONL line: the sequence number plus whichever channels the edit touched. */
    fun encode(seq: Int, edit: PageEdit): String = JSONObject().apply {
        put("seq", seq)
        encodeInto(this, edit)
    }.toString()

    /** Reads one JSONL line, or null when it is torn, truncated or otherwise unreadable. */
    fun decode(line: String): JournalRecord? = try {
        val o = JSONObject(line)
        val edit = decodeEdit(o) ?: return null
        JournalRecord(o.getInt("seq"), edit)
    } catch (_: Exception) { null }

    fun encodeEdit(edit: PageEdit): JSONObject = JSONObject().apply { encodeInto(this, edit) }

    fun decodeEdit(o: JSONObject): PageEdit? {
        val strokes = o.optJSONObject("st")?.let { st ->
            when {
                st.has("add") -> StrokesEdit.Add(InkCodec.decodeStrokes(st.getJSONArray("add")))
                st.has("del") -> StrokesEdit.Remove((0 until st.getJSONArray("del").length()).map { st.getJSONArray("del").getInt(it) })
                st.has("set") -> StrokesEdit.Set(InkCodec.decodeStrokes(st.getJSONArray("set")))
                else -> null
            }
        }
        val texts = o.optJSONArray("tx")?.let { InkCodec.decodeTexts(it) }
        val images = o.optJSONArray("im")?.let { InkCodec.decodeImages(it) }
        if (strokes == null && texts == null && images == null) return null
        return PageEdit(strokes, texts, images)
    }

    private fun encodeInto(o: JSONObject, edit: PageEdit) {
        edit.strokes?.let { strokes -> o.put("st", JSONObject().apply {
            when (strokes) {
                is StrokesEdit.Add -> put("add", InkCodec.encodeStrokes(strokes.strokes))
                is StrokesEdit.Remove -> put("del", JSONArray().apply { strokes.indices.forEach { put(it) } })
                is StrokesEdit.Set -> put("set", InkCodec.encodeStrokes(strokes.strokes))
            }
        }) }
        edit.texts?.let { o.put("tx", InkCodec.encodeTexts(it)) }
        edit.images?.let { o.put("im", InkCodec.encodeImages(it)) }
    }

    // ---- Persistent undo/redo ------------------------------------------------------------

    /** The undo and redo stacks, small and bounded, kept as one atomically rewritten file. */
    data class History(val undo: List<PageEdit>, val redo: List<PageEdit>) {
        companion object { val EMPTY = History(emptyList(), emptyList()) }
    }

    fun encodeHistory(history: History): String = JSONObject().apply {
        put("v", 1)
        put("undo", JSONArray().apply { history.undo.forEach { put(encodeEdit(it)) } })
        put("redo", JSONArray().apply { history.redo.forEach { put(encodeEdit(it)) } })
    }.toString()

    fun decodeHistory(value: String?): History {
        if (value.isNullOrBlank()) return History.EMPTY
        return try {
            val o = JSONObject(value)
            History(
                undo = o.optJSONArray("undo").toEdits(),
                redo = o.optJSONArray("redo").toEdits()
            )
        } catch (_: Exception) { History.EMPTY }
    }

    private fun JSONArray?.toEdits(): List<PageEdit> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> runCatching { decodeEdit(getJSONObject(index)) }.getOrNull() }
    }
}
