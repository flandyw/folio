package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/** One page's editable content, the unit a journal edit transforms. */
data class PageContent(
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextBox> = emptyList(),
    val images: List<PageImage> = emptyList(),
    val layers: List<PageLayer> = emptyList()
) {
    companion object { val EMPTY = PageContent() }
}

/** A stroke plus the position it takes in the page's list, the address every indexed edit uses. */
data class IndexedStroke(val index: Int, val stroke: Stroke)

/**
 * A change to a page's strokes, expressed as the smallest thing that reproduces the new list.
 *
 * Every variant except [Set] is proportional to the strokes that actually changed, so a pen-up or an
 * eraser on a page holding fifty thousand strokes writes a few dozen bytes, not the page. The point
 * is that *undo* is cheap too: the inverse of an eraser is the three strokes it took away, not a copy
 * of everything it left behind.
 */
sealed interface StrokesEdit {
    /** [strokes] were appended after the ones already on the page. */
    data class Add(val strokes: List<Stroke>) : StrokesEdit
    /** The strokes at [indices] in the page's list were dropped. */
    data class Remove(val indices: List<Int>) : StrokesEdit
    /**
     * [strokes] are new objects placed at their final [IndexedStroke.index] in the grown list. The
     * inverse of an eraser, or of a paste: the strokes come back where they were, in order.
     */
    data class Insert(val strokes: List<IndexedStroke>) : StrokesEdit
    /**
     * The page's length is unchanged; the strokes at these final indices are new objects. What a
     * lasso move, restyle or resize of a few strokes produces, and the inverse of the same.
     */
    data class Replace(val strokes: List<IndexedStroke>) : StrokesEdit
    /**
     * Removals and placements in one step — the exact inverse of a [Remove] or a [Replace], and the
     * one edit an operation that both drops and creates strokes needs. [placed] indexes the list
     * *after* [removed] is gone, so applying this rebuilds the new list from the survivors alone.
     */
    data class Rewrite(val removed: List<Int>, val placed: List<IndexedStroke>) : StrokesEdit
    /**
     * The whole list was replaced. Reserved for migration and for a page that does not look like the
     * old one at all; never the normal cost of an edit, because that is what the shapes above avoid.
     */
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
    val images: List<PageImage>? = null,
    /** The page's whole layer list; layer changes are small, so a changed list is stored whole. */
    val layers: List<PageLayer>? = null
) {
    val isEmpty: Boolean get() = strokes == null && texts == null && images == null && layers == null
}

/**
 * One page transaction: the single durability record for one user action. It carries the forward
 * edit that changes the page, the page revision that edit produced, and exactly what the action did
 * to the undo and redo stacks — so one append to the page's log makes both the ink and its undo
 * state survive a crash, and nothing else has to be rewritten per stroke.
 *
 * [seq] is assigned by the store when the record is appended; a transaction built by the editor
 * carries 0 and is stamped on the way to disk.
 */
data class PageTransaction(
    val seq: Int,
    val revision: Int = 0,
    val forward: PageEdit,
    val undoPush: PageEdit? = null,
    val undoPop: Boolean = false,
    val redoPush: PageEdit? = null,
    val redoPop: Boolean = false,
    val clearRedo: Boolean = false
)

/** This page's editable content, the shape the journal edits. */
fun NotePage.content(): PageContent = PageContent(strokes, texts, images, layers)

/**
 * The pure half of append-only page durability: it turns a before/after pair into the smallest edit
 * that reproduces the change, applies edits back onto content, folds a log into undo/redo stacks,
 * and reads and writes the JSONL records the repository appends. Nothing here touches the file
 * system, so the whole log shape is unit-testable without Android.
 *
 * Stroke comparison is by reference: the editor reuses stroke objects when it appends, erases,
 * undoes and redoes, so identity recognises "same stroke" without walking every point. A transform
 * that builds new objects is matched positionally by [align], which yields a [StrokesEdit.Replace]
 * or a [StrokesEdit.Rewrite] over just the indices that differ — a whole-page [StrokesEdit.Set] is
 * only ever written when more strokes vanish than survive.
 */
object PageJournal {
    /** Undo and redo keep this many steps; the same bound applies live and after a restart. */
    const val HISTORY_LIMIT = 60

    /**
     * The cheapest edit that turns [before] into [after], or null when nothing changed. Texts and
     * pictures are small, so a changed channel is stored whole; ink, which dominates a page, keeps
     * its append/remove/insert/replace shape so a pen-up appends a handful of points instead of the
     * page, and erasing strokes stores the three it took rather than the ten thousand it left.
     */
    fun diff(before: PageContent, after: PageContent): PageEdit? {
        val strokes = diffStrokes(before.strokes, after.strokes)
        val texts = if (before.texts == after.texts) null else after.texts
        val images = if (before.images == after.images) null else after.images
        val layers = if (before.layers == after.layers) null else after.layers
        if (strokes == null && texts == null && images == null && layers == null) return null
        return PageEdit(strokes, texts, images, layers)
    }

    /**
     * The edit that undoes [edit], given the content it was applied to. This is not a second
     * [diff]: a diff only has to produce *some* edit that reaches the new state, and it will happily
     * call a page that grew at the end an append when those strokes really belong in the middle. Undo
     * has to put every stroke back exactly where it was, so it is read off the forward edit instead
     * of guessed — which is also one pass over the page rather than two.
     */
    fun invert(edit: PageEdit, before: PageContent): PageEdit = PageEdit(
        strokes = when (val strokes = edit.strokes) {
            null -> null
            // An append un-does by naming the tail it added.
            is StrokesEdit.Add ->
                StrokesEdit.Remove((before.strokes.size until before.strokes.size + strokes.strokes.size).toList())
            // Dropped strokes come back where they were, counting the survivors before each of them.
            // An index a damaged log left pointing nowhere is dropped rather than crashing an undo.
            is StrokesEdit.Remove ->
                StrokesEdit.Insert(strokes.indices.mapIndexedNotNull { rank, index ->
                    before.strokes.getOrNull(index)?.let { IndexedStroke(index - rank, it) }
                })
            // Placed strokes leave from where they landed: the k-th one is k positions past its
            // own place in the survivors.
            is StrokesEdit.Insert -> StrokesEdit.Remove(strokes.strokes.mapIndexed { rank, it -> it.index + rank })
            is StrokesEdit.Replace ->
                StrokesEdit.Replace(strokes.strokes.mapNotNull { placed ->
                    before.strokes.getOrNull(placed.index)?.let { IndexedStroke(placed.index, it) }
                })
            // A rewrite un-does by dropping what it placed and restoring what it dropped; each of
            // them moves between the two coordinate systems, the survivors counting the same way.
            is StrokesEdit.Rewrite -> StrokesEdit.Rewrite(
                strokes.placed.mapIndexed { rank, it -> it.index + rank },
                strokes.removed.mapIndexedNotNull { rank, index ->
                    before.strokes.getOrNull(index)?.let { IndexedStroke(index - rank, it) }
                })
            is StrokesEdit.Set -> StrokesEdit.Set(before.strokes)
        },
        texts = if (edit.texts != null) before.texts else null,
        images = if (edit.images != null) before.images else null,
        layers = if (edit.layers != null) before.layers else null
    )

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
        return reshape(before, after)
    }

    /**
     * Matches the two lists by identity in order, then writes only what the match does not already
     * cover. One linear pass with an identity index, so the cost is the length of the page plus the
     * number of strokes that changed — never a rebuild of either list.
     *
     * A placed stroke is addressed by where it sits among the survivors rather than by its final
     * position, which is what lets an undo put erased ink back between strokes that were left alone
     * instead of pushing the rest of the page along.
     */
    private fun reshape(before: List<Stroke>, after: List<Stroke>): StrokesEdit? {
        val firstAt = IdentityHashMap<Stroke, Int>(before.size.coerceAtLeast(1))
        before.forEachIndexed { index, stroke -> firstAt.putIfAbsent(stroke, index) }
        val origin = IntArray(after.size) { -1 }
        var floor = 0
        var aligned = true
        for (index in after.indices) {
            val candidate = firstAt[after[index]] ?: -1
            if (candidate >= floor) { origin[index] = candidate; floor = candidate + 1 }
            // A stroke that is genuinely new says nothing about where the others sit.
            if (origin[index] >= 0 && origin[index] != index) aligned = false
        }
        val keptOld = BooleanArray(before.size)
        // How many strokes that survived the edit precede each new one: the address a rewrite has to
        // use, since it is dropping strokes out from under them.
        val based = IntArray(after.size) { -1 }
        var survivors = 0
        for (index in after.indices) {
            val from = origin[index]
            if (from >= 0) { keptOld[from] = true; survivors++ } else based[index] = survivors
        }
        val removed = keptOld.indices.filterNot { keptOld[it] }
        val newStrokes = after.indices.filter { origin[it] < 0 }
        if (newStrokes.isEmpty() && removed.isEmpty()) return null
        // Nothing moved and nothing went: the new ink landed in the very slots of the ink it replaced.
        if (aligned && newStrokes == removed) {
            return StrokesEdit.Replace(newStrokes.map { IndexedStroke(it, after[it]) })
        }
        // Only additions: each is addressed by its place in the finished list, which is the same as
        // its place in the list it is being added to.
        if (removed.isEmpty()) return StrokesEdit.Insert(newStrokes.map { IndexedStroke(it, after[it]) })
        // Additions among removals: address them against the survivors they are dropped into.
        return StrokesEdit.Rewrite(removed, newStrokes.map { IndexedStroke(based[it], after[it]) })
    }

    /** Replays one edit onto [content]; a null channel is left exactly as it was. */
    fun apply(content: PageContent, edit: PageEdit): PageContent = PageContent(
        strokes = when (val strokes = edit.strokes) {
            null -> content.strokes
            is StrokesEdit.Add -> content.strokes + strokes.strokes
            is StrokesEdit.Remove -> drop(content.strokes, strokes.indices)
            is StrokesEdit.Insert -> insert(content.strokes, strokes.strokes)
            is StrokesEdit.Replace -> overwrite(content.strokes, strokes.strokes)
            is StrokesEdit.Rewrite -> insert(drop(content.strokes, strokes.removed), strokes.placed)
            is StrokesEdit.Set -> strokes.strokes
        },
        texts = edit.texts ?: content.texts,
        images = edit.images ?: content.images,
        layers = edit.layers ?: content.layers
    )

    private fun drop(strokes: List<Stroke>, indices: List<Int>): List<Stroke> {
        if (indices.isEmpty()) return strokes
        val removed = indices.toHashSet()
        return strokes.filterIndexed { index, _ -> index !in removed }
    }

    /**
     * Rebuilds [base] around [placed], each addressed by how many survivors precede it: copy up to
     * that point, add the stroke, carry on. An index a damaged record got wrong cannot crash a page
     * open, it only lands the stroke at the end.
     */
    private fun insert(base: List<Stroke>, placed: List<IndexedStroke>): List<Stroke> {
        if (placed.isEmpty()) return base
        val out = ArrayList<Stroke>(base.size + placed.size)
        var cursor = 0
        for (item in placed) {
            val at = item.index.coerceIn(0, base.size)
            if (at >= cursor) { while (cursor < at) { out.add(base[cursor]); cursor++ } }
            out.add(item.stroke)
        }
        while (cursor < base.size) { out.add(base[cursor]); cursor++ }
        return out
    }

    private fun overwrite(base: List<Stroke>, placed: List<IndexedStroke>): List<Stroke> {
        if (placed.isEmpty()) return base
        val out = base.toMutableList()
        for (item in placed) if (item.index in out.indices) out[item.index] = item.stroke
        return out
    }

    /**
     * Replays [records] in order, skipping any already folded into [baseSeq]'s snapshot. Skipping is
     * what makes compaction crash-safe: if the process dies after the new snapshot lands but before
     * the journal is cleared, the next open simply ignores the records the snapshot already holds.
     */
    fun replay(base: PageContent, baseSeq: Int, transactions: List<PageTransaction>): PageContent {
        var content = base
        // A run of plain pen-ups only appends, so it grows one list in place; copying the page for
        // every record made opening a long journal quadratic in the number of strokes.
        var appending: ArrayList<Stroke>? = null
        for (transaction in transactions) {
            if (transaction.seq <= baseSeq) continue
            val edit = transaction.forward
            val add = edit.strokes as? StrokesEdit.Add
            if (add != null && edit.texts == null && edit.images == null && edit.layers == null) {
                (appending ?: ArrayList(content.strokes).also { appending = it }).addAll(add.strokes)
            } else {
                appending?.let { content = content.copy(strokes = it); appending = null }
                content = apply(content, edit)
            }
        }
        appending?.let { content = content.copy(strokes = it) }
        return content
    }

    /** The highest sequence number in [transactions], or 0 when the journal is empty. */
    fun lastSeq(transactions: List<PageTransaction>): Int = transactions.maxOfOrNull { it.seq } ?: 0

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

    /** One JSONL line: a whole transaction, so the page and its undo state land together. */
    fun encode(transaction: PageTransaction): String = JSONObject().apply {
        put("seq", transaction.seq)
        if (transaction.revision > 0) put("rev", transaction.revision)
        put("f", encodeEdit(transaction.forward))
        transaction.undoPush?.let { put("u", encodeEdit(it)) }
        if (transaction.undoPop) put("up", true)
        transaction.redoPush?.let { put("d", encodeEdit(it)) }
        if (transaction.redoPop) put("rp", true)
        if (transaction.clearRedo) put("c", true)
    }.toString()

    /** One JSONL line for an edit with no undo bookkeeping; sequence and revision are dropped. */
    fun encode(seq: Int, edit: PageEdit): String = encode(PageTransaction(seq, forward = edit))

    /**
     * Reads one JSONL line, or null when it is torn, truncated or otherwise unreadable. A line from
     * an older build, which put the edit at the top level, reads as a transaction that only moved
     * ink — exactly what it did.
     */
    fun decode(line: String): PageTransaction? = try {
        val o = JSONObject(line)
        val forward = o.optJSONObject("f")?.let { decodeEdit(it) } ?: decodeEdit(o) ?: return null
        PageTransaction(
            seq = o.getInt("seq"),
            revision = o.optInt("rev", 0),
            forward = forward,
            undoPush = o.optJSONObject("u")?.let { decodeEdit(it) },
            undoPop = o.optBoolean("up", false),
            redoPush = o.optJSONObject("d")?.let { decodeEdit(it) },
            redoPop = o.optBoolean("rp", false),
            clearRedo = o.optBoolean("c", false)
        )
    } catch (_: Exception) { null }

    fun encodeEdit(edit: PageEdit): JSONObject = JSONObject().apply { encodeInto(this, edit) }

    fun decodeEdit(o: JSONObject): PageEdit? {
        val strokes = o.optJSONObject("st")?.let { st ->
            fun indexed(key: String): List<IndexedStroke> {
                val array = st.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).mapNotNull { slot ->
                    val pair = array.optJSONArray(slot) ?: return@mapNotNull null
                    val stroke = pair.optJSONObject(1) ?: return@mapNotNull null
                    val at = pair.optInt(0, -1)
                    if (at >= 0) IndexedStroke(at, InkCodec.decodeStroke(stroke)) else null
                }
            }
            when {
                st.has("set") -> StrokesEdit.Set(InkCodec.decodeStrokes(st.getJSONArray("set")))
                st.has("add") -> StrokesEdit.Add(InkCodec.decodeStrokes(st.getJSONArray("add")))
                st.has("rep") -> StrokesEdit.Replace(indexed("rep"))
                st.has("ins") && st.has("del") -> StrokesEdit.Rewrite(indicesOf(st, "del"), indexed("ins"))
                st.has("ins") -> StrokesEdit.Insert(indexed("ins"))
                st.has("del") -> StrokesEdit.Remove(indicesOf(st, "del"))
                else -> null
            }
        }
        val texts = o.optJSONArray("tx")?.let { InkCodec.decodeTexts(it) }
        val images = o.optJSONArray("im")?.let { InkCodec.decodeImages(it) }
        val layers = o.optJSONArray("ly")?.let { InkCodec.decodeLayers(it) }
        if (strokes == null && texts == null && images == null && layers == null) return null
        return PageEdit(strokes, texts, images, layers)
    }

    private fun encodeInto(o: JSONObject, edit: PageEdit) {
        edit.strokes?.let { strokes -> o.put("st", JSONObject().apply {
            when (strokes) {
                is StrokesEdit.Add -> put("add", InkCodec.encodeStrokes(strokes.strokes))
                is StrokesEdit.Remove -> put("del", JSONArray().apply { strokes.indices.forEach { put(it) } })
                is StrokesEdit.Insert -> put("ins", encodeIndexed(strokes.strokes))
                is StrokesEdit.Replace -> put("rep", encodeIndexed(strokes.strokes))
                is StrokesEdit.Rewrite -> {
                    put("del", JSONArray().apply { strokes.removed.forEach { put(it) } })
                    put("ins", encodeIndexed(strokes.placed))
                }
                is StrokesEdit.Set -> put("set", InkCodec.encodeStrokes(strokes.strokes))
            }
        }) }
        edit.texts?.let { o.put("tx", InkCodec.encodeTexts(it)) }
        edit.images?.let { o.put("im", InkCodec.encodeImages(it)) }
        edit.layers?.let { o.put("ly", InkCodec.encodeLayers(it)) }
    }

    private fun indicesOf(o: JSONObject, key: String): List<Int> {
        val array = o.getJSONArray(key)
        return (0 until array.length()).map { array.getInt(it) }
    }

    /** Indexed strokes as `[index, stroke]` pairs, the compact shape the log reads back. */
    private fun encodeIndexed(strokes: List<IndexedStroke>): JSONArray = JSONArray().apply {
        strokes.forEach { put(JSONArray().put(it.index).put(InkCodec.encodeStroke(it.stroke))) }
    }

    // ---- Persistent undo/redo ------------------------------------------------------------

    /** The undo and redo stacks, small and bounded, carried by the snapshot and the journal. */
    data class History(val undo: List<PageEdit>, val redo: List<PageEdit>) {
        companion object { val EMPTY = History(emptyList(), emptyList()) }
    }

    /**
     * The stacks a page has once every record after [afterSeq] in [transactions] has been applied to
     * [base]. Each record states its own stack effect, so a snapshot's stacks plus the records that
     * landed after it rebuild exactly the undo and redo the editor had. Records at or before
     * [afterSeq] are already counted in [base] and are skipped, which is what lets a snapshot be
     * read alongside a journal that still holds the records it folded in.
     */
    fun foldHistory(base: History, transactions: List<PageTransaction>, afterSeq: Int): History {
        val undo = ArrayDeque(base.undo.takeLast(HISTORY_LIMIT))
        val redo = ArrayDeque(base.redo.takeLast(HISTORY_LIMIT))
        fun ArrayDeque<PageEdit>.push(edit: PageEdit) {
            if (size == HISTORY_LIMIT) removeFirst()
            addLast(edit)
        }
        for (transaction in transactions) {
            if (transaction.seq <= afterSeq) continue
            if (transaction.undoPop) undo.removeLastOrNull()
            if (transaction.redoPop) redo.removeLastOrNull()
            if (transaction.clearRedo) redo.clear()
            transaction.undoPush?.let { undo.push(it) }
            transaction.redoPush?.let { redo.push(it) }
        }
        return History(undo.toList(), redo.toList())
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

/** A page's content plus its undo stacks, the pair a snapshot is written from. */
data class PageCheckpoint(val page: NotePage, val history: PageJournal.History)
