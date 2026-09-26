package com.folio.notes.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.result.PostgrestResult
import org.json.JSONArray
import org.json.JSONObject

/** What the log says in reply to a batch: one receipt per change, with the sequence it was given. */
data class ApplyReceipt(val changeId: String, val seq: Long, val replayed: Boolean)

/** Either more log rows, or the whole materialized state when this cursor is too old to tail. */
data class ReadResult(
    val mode: String,
    val floor: Long,
    val head: Long,
    val changes: List<SyncProtocol.Change>,
    val rows: List<SyncProtocol.RowState>
)

/**
 * The two calls the protocol needs. An interface so the engine can be tested without a
 * network, and so the mistakes sync and the notes sync share one implementation.
 */
interface SyncRemote {
    suspend fun apply(accountId: String, changes: List<SyncProtocol.QueuedChange>, clientId: String): Pair<List<ApplyReceipt>, Long>
    suspend fun read(accountId: String, cursor: Long, limit: Int = 500): ReadResult
}

/**
 * Publishing and reading go through the log's RPCs, which force the caller's identity, so a
 * client cannot write on someone else's behalf and cannot read a row that is not theirs.
 */
class SupabaseSyncRemote(private val client: SupabaseClient) : SyncRemote {
    private fun checkUser(accountId: String) {
        check(client.auth.currentUserOrNull()?.id == accountId) { "Sign in again" }
    }

    override suspend fun apply(
        accountId: String,
        changes: List<SyncProtocol.QueuedChange>,
        clientId: String
    ): Pair<List<ApplyReceipt>, Long> {
        checkUser(accountId)
        val payload = JSONArray().apply {
            changes.forEach { change ->
                put(
                    JSONObject()
                        .put("change_id", change.changeId)
                        .put("client_id", clientId)
                        .put("entity", change.entity)
                        .put("row_id", change.rowId)
                        .put("operation", change.operation)
                        .put("payload", change.payload ?: JSONObject.NULL)
                        .put("lamport", change.lamport)
                )
            }
        }
        val result = rpcObject(
            client.postgrest.rpc("sync_apply_changes", mapOf("p_changes" to payload.toString()))
        )
        val receipts = result.optJSONArray("receipts") ?: JSONArray()
        return (0 until receipts.length()).mapNotNull { index ->
            val receipt = receipts.optJSONObject(index) ?: return@mapNotNull null
            val changeId = receipt.optString("change_id")
            if (changeId.isEmpty()) null
            else ApplyReceipt(changeId, receipt.optLong("seq"), receipt.optBoolean("replayed"))
        } to result.optLong("head")
    }

    override suspend fun read(accountId: String, cursor: Long, limit: Int): ReadResult {
        checkUser(accountId)
        val result = rpcObject(
            client.postgrest.rpc("sync_read_changes", mapOf("p_after" to cursor, "p_limit" to limit))
        )
        val rows = result.optJSONArray("rows") ?: JSONArray()
        val snapshot = result.optString("mode") == "snapshot"
        return ReadResult(
            mode = if (snapshot) "snapshot" else "changes",
            floor = result.optLong("floor"),
            head = result.optLong("head"),
            changes = if (snapshot) emptyList() else (0 until rows.length()).mapNotNull { SyncProtocol.parseChange(rows.getJSONObject(it)) },
            rows = if (snapshot) (0 until rows.length()).mapNotNull { SyncProtocol.parseRowState(rows.getJSONObject(it)) } else emptyList()
        )
    }
}

/**
 * The RPC's answer as a document. Supabase reports a failure in the response body rather
 * than by throwing, so a body that carries neither `rows` nor `receipts` is an error to
 * surface — treating it as an empty page would look like "nothing new" and stall the sync.
 */
internal fun rpcObject(response: PostgrestResult): JSONObject {
    val body = response.data
    val parsed = if (body.trimStart().startsWith("{")) JSONObject(body) else JSONObject()
    if (!parsed.has("rows") && !parsed.has("receipts")) {
        throw IllegalStateException(parsed.optString("message", "Sync request failed"))
    }
    return parsed
}
