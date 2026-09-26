package com.folio.notes.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * The durable half of sync protocol v3 on this device: the queue waiting to be published,
 * the cursor saying how far the log has been read, the lamport clock that stamps new
 * changes, and the last version of each row that was applied.
 *
 * Every write is a write-temp, fsync, rename, so a crash leaves either the old file or the
 * new one and never a half-written queue. Reads tolerate a missing file: an account that
 * has never synced starts empty.
 *
 * Pure file I/O with no Android imports, so [com.folio.notes.sync.SyncStoreTests] can
 * exercise it against a temporary directory.
 */
class SyncStore(private val root: File) {
    private val lock = Any()

    fun accountDir(accountId: String): File {
        require(accountId.matches(Regex("[a-zA-Z0-9-]+"))) { "Unexpected account id" }
        return File(root, accountId)
    }

    // ------------------------------------------------------------------ outbox

    fun readOutbox(accountId: String): List<SyncProtocol.QueuedChange> = readJson(File(accountDir(accountId), "outbox.json"))
        ?.optJSONArray("changes")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(SyncProtocol.QueuedChange::fromJson) }
        } ?: emptyList()

    /**
     * One queued change per row, keeping the highest version. This is the local half of the
     * coalescing rule: a burst of edits to the same row costs one publish, so the queue is
     * bounded by dirty rows rather than by how much the user typed.
     */
    fun enqueue(
        accountId: String,
        entity: String,
        rowId: String,
        operation: String,
        payload: JSONObject?,
        lamport: Long,
        at: String
    ): List<SyncProtocol.QueuedChange> = synchronized(lock) {
        val change = SyncProtocol.QueuedChange(
            changeId = UUID.randomUUID().toString(),
            entity = entity,
            rowId = rowId,
            operation = operation,
            payload = payload,
            lamport = lamport,
            createdAt = at
        )
        val existing = readOutbox(accountId).filterNot { it.key == change.key } + change
        writeOutbox(accountId, existing)
        existing
    }

    fun replaceOutbox(accountId: String, changes: List<SyncProtocol.QueuedChange>): Unit = synchronized(lock) {
        writeOutbox(accountId, changes)
    }

    private fun writeOutbox(accountId: String, changes: List<SyncProtocol.QueuedChange>) {
        val array = JSONArray().apply { changes.forEach { put(it.toJson()) } }
        writeAtomic(File(accountDir(accountId), "outbox.json"), JSONObject().put("version", 1).put("changes", array).toString())
    }

    // ------------------------------------------------------------ cursor, clock

    fun readCursor(accountId: String): Pair<Long, Long> {
        val o = readJson(File(accountDir(accountId), "cursor.json")) ?: return 0L to 0L
        return o.optLong("seq") to o.optLong("lamport")
    }

    fun writeCursor(accountId: String, seq: Long, lamport: Long): Unit = synchronized(lock) {
        val (currentSeq, currentLamport) = readCursor(accountId)
        writeAtomic(
            File(accountDir(accountId), "cursor.json"),
            JSONObject().put("seq", maxOf(currentSeq, seq)).put("lamport", maxOf(currentLamport, lamport)).toString()
        )
    }

    /** The next version for a local change. Durable, so a crash cannot hand out the same one twice. */
    fun nextLamport(accountId: String): Long = synchronized(lock) {
        val (seq, lamport) = readCursor(accountId)
        val next = lamport + 1
        writeAtomic(File(accountDir(accountId), "cursor.json"), JSONObject().put("seq", seq).put("lamport", next).toString())
        next
    }

    // --------------------------------------------------------- applied versions

    fun readApplied(accountId: String): List<SyncProtocol.RowState> {
        val rows = readJson(File(accountDir(accountId), "applied.json"))?.optJSONArray("rows") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { SyncProtocol.parseRowState(rows.getJSONObject(it)) }
    }

    fun writeApplied(accountId: String, rows: List<SyncProtocol.RowState>): Unit = synchronized(lock) {
        val array = JSONArray().apply { rows.forEach { put(it.toJson()) } }
        writeAtomic(File(accountDir(accountId), "applied.json"), JSONObject().put("version", 1).put("rows", array).toString())
    }

    fun dropApplied(accountId: String, keys: Set<String>): Unit = synchronized(lock) {
        if (keys.isEmpty()) return
        writeApplied(accountId, readApplied(accountId).filterNot { it.key in keys })
    }

    // ------------------------------------------------------------------ helpers

    private fun readJson(file: File): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        // A damaged file is replaced by the next write; refusing to start would be worse.
        null
    }

    private fun writeAtomic(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(value.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Couldn't replace ${file.name}")
        }
    }
}
