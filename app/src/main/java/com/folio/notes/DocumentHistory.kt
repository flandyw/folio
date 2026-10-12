package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.zip.CRC32

/** The membership/order of a logical multi-page action. Actual inverses remain in page histories. */
data class DocumentHistoryStep(val id: String, val pages: List<String>, val sequence: Long)
data class DocumentHistory(val enabled: Boolean = false, val undo: List<DocumentHistoryStep> = emptyList(),
    val redo: List<DocumentHistoryStep> = emptyList(), val lastCommit: String? = null) {
    fun push(step: DocumentHistoryStep) = copy(enabled = true, undo = (undo + step).takeLast(PageJournal.HISTORY_LIMIT), redo = emptyList(), lastCommit = step.id)
    fun moved(step: DocumentHistoryStep, wasUndo: Boolean, sequence: Long): DocumentHistory = if (wasUndo)
        copy(undo = undo.filterNot { it.id == step.id }, redo = (redo + step.copy(sequence = sequence)).takeLast(PageJournal.HISTORY_LIMIT))
        else copy(redo = redo.filterNot { it.id == step.id }, undo = (undo + step.copy(sequence = sequence)).takeLast(PageJournal.HISTORY_LIMIT))
}

object DocumentHistoryCodec {
    fun encode(history: DocumentHistory): JSONObject = JSONObject().apply {
        put("enabled", history.enabled)
        history.lastCommit?.let { put("lastCommit", it) }
        fun steps(values: List<DocumentHistoryStep>) = JSONArray().apply { values.forEach { step ->
            put(JSONObject().put("id", step.id).put("pages", JSONArray(step.pages)).put("sequence", step.sequence))
        } }
        put("undo", steps(history.undo)); put("redo", steps(history.redo))
    }
    fun decode(o: JSONObject?): DocumentHistory {
        if (o == null) return DocumentHistory()
        fun steps(key: String): List<DocumentHistoryStep> {
            val a = o.optJSONArray(key) ?: return emptyList()
            require(a.length() <= PageJournal.HISTORY_LIMIT) { "Too many document history entries" }
            return (0 until a.length()).map { i -> val step = a.getJSONObject(i); val p = step.getJSONArray("pages")
                require(p.length() in 1..10000)
                DocumentHistoryStep(step.getString("id"), (0 until p.length()).map(p::getString).distinct(), step.getLong("sequence"))
            }.also { require(it.map { step -> step.id }.distinct().size == it.size) }
        }
        return DocumentHistory(o.optBoolean("enabled", true), steps("undo"), steps("redo"), o.optString("lastCommit").takeIf { it.isNotBlank() })
    }
}

/** Synced before any member page is appended. Recovery rolls forward, never guesses partial undo. */
data class DocumentCommit(val id: String, val history: DocumentHistory, val records: Map<String, PageTransaction>, val pageSummaries: Map<String, String> = emptyMap()) {
    init { require(id.isNotBlank() && records.isNotEmpty() && history.enabled && history.lastCommit == id); require(records.values.all { it.seq > 0 }); require(pageSummaries.isEmpty() || pageSummaries.keys == records.keys) }
}
object DocumentCommitCodec {
    fun encode(commit: DocumentCommit): String {
        val body = JSONObject().put("version", 1).put("id", commit.id).put("history", DocumentHistoryCodec.encode(commit.history))
            .put("pages", JSONObject(commit.pageSummaries))
            .put("records", JSONObject().apply { commit.records.forEach { (page, tx) -> put(page, Base64.getEncoder().encodeToString(JournalBinary.frame(tx))) } }).toString()
        val crc = CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value
        return JSONObject().put("body", body).put("crc", crc).toString()
    }
    fun decode(raw: String): DocumentCommit {
        val envelope = JSONObject(raw); val body = envelope.getString("body")
        require(CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value == envelope.getLong("crc")) { "Damaged document commit" }
        val o = JSONObject(body); require(o.getInt("version") == 1)
        val records = o.getJSONObject("records")
        val decoded = records.keys().asSequence().associateWith { page ->
            val bytes = Base64.getDecoder().decode(records.getString(page))
            val records = JournalBinary.readAll(bytes)
            require(JournalBinary.completePrefixLength(bytes) == bytes.size && records.size == 1) { "Invalid document member" }
            records.single()
        }
        val summaries = o.optJSONObject("pages")
        val pages = summaries?.keys()?.asSequence()?.associateWith { summaries.getString(it) }.orEmpty()
        return DocumentCommit(o.getString("id"), DocumentHistoryCodec.decode(o.getJSONObject("history")), decoded, pages)
    }
}

/** Injectable disk effects allow every interruption point to be exercised without Android. */
object DocumentCommitRecovery {
    fun replay(commit: DocumentCommit, applied: (String, PageTransaction) -> Boolean,
        append: (String, PageTransaction) -> Unit, checkpoint: (DocumentHistory) -> Unit, complete: () -> Unit) {
        commit.records.forEach { (page, tx) -> if (!applied(page, tx)) append(page, tx) }
        checkpoint(commit.history)
        complete()
    }
}
