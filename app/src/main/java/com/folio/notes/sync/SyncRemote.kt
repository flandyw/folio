package com.folio.notes.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** What the log says in reply to a batch: one receipt per change, with the sequence it was given. */
data class ApplyReceipt(val changeId: String, val seq: Long, val replayed: Boolean)
data class ApplyStale(val changeId: String, val current: SyncProtocol.RowState?)
data class ApplyResult(val receipts: List<ApplyReceipt>, val stale: List<ApplyStale>, val head: Long)

/** Either more log rows, or the whole materialized state when this cursor is too old to tail. */
data class ReadResult(
    val mode: String,
    val floor: Long,
    val head: Long,
    val changes: List<SyncProtocol.Change>,
    val rows: List<SyncProtocol.RowState>,
    val serverNow: String? = null
)

/**
 * The two calls the protocol needs. An interface so the engine can be tested without a
 * network, and so the mistakes sync and the notes sync share one implementation.
 */
interface SyncRemote {
    suspend fun apply(accountId: String, changes: List<SyncProtocol.QueuedChange>, clientId: String): ApplyResult
    suspend fun read(accountId: String, cursor: Long, limit: Int = 500): ReadResult
    suspend fun mutateStudySession(accountId: String, command: JSONObject): JSONObject
}

/**
 * Publishing and reading go through the log's RPCs, which force the caller's identity, so a
 * client cannot write on someone else's behalf and cannot read a row that is not theirs.
 */
class SupabaseSyncRemote(private val client: SupabaseClient) : SyncRemote {
    override suspend fun apply(
        accountId: String,
        changes: List<SyncProtocol.QueuedChange>,
        clientId: String
    ): ApplyResult {
        awaitFocalSyncSession(client, accountId)
        val result = rpcObject(client.postgrest.rpc("sync_apply_changes", applyParams(accountId, changes, clientId)))
        val receipts = result.optJSONArray("receipts") ?: JSONArray()
        val applied = (0 until receipts.length()).mapNotNull { index ->
            val receipt = receipts.optJSONObject(index) ?: return@mapNotNull null
            val changeId = receipt.optString("change_id")
            if (changeId.isEmpty()) null
            else ApplyReceipt(changeId, receipt.optLong("seq"), receipt.optBoolean("replayed"))
        }
        val staleRows = result.optJSONArray("stale") ?: JSONArray()
        val stale = (0 until staleRows.length()).mapNotNull { index ->
            val row = staleRows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("change_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ApplyStale(id, row.optJSONObject("current")?.let(SyncProtocol::parseRowState))
        }
        return ApplyResult(applied, stale, result.optLong("head"))
    }

    override suspend fun read(accountId: String, cursor: Long, limit: Int): ReadResult {
        // Only reads retry here. A write's durable mutation/receipt belongs to its caller.
        var attempt = 0
        var response: PostgrestResult
        while (true) {
            awaitFocalSyncSession(client, accountId)
            try {
                response = client.postgrest.rpc("sync_read_changes", readParams(accountId, cursor, limit))
                break
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt >= 2 || !focalReadCanRetry(e)) throw e
                delay(750L shl attempt++)
            }
        }
        val result = rpcObject(response)
        val rows = result.optJSONArray("rows") ?: JSONArray()
        val snapshot = result.optString("mode") == "snapshot"
        return ReadResult(
            mode = if (snapshot) "snapshot" else "changes",
            floor = result.optLong("floor"),
            head = result.optLong("head"),
            changes = if (snapshot) emptyList() else (0 until rows.length()).mapNotNull { SyncProtocol.parseChange(rows.getJSONObject(it)) },
            rows = if (snapshot) (0 until rows.length()).mapNotNull { SyncProtocol.parseRowState(rows.getJSONObject(it)) } else emptyList(),
            serverNow = result.optString("server_now").takeIf { it.isNotBlank() }
        )
    }

    override suspend fun mutateStudySession(accountId: String, command: JSONObject): JSONObject {
        awaitFocalSyncSession(client, accountId)
        return rpcObject(client.postgrest.rpc("study_session_mutate", mutateParams(accountId, command)))
    }
}

/** Shared by feed RPCs and authenticated attachment downloads. */
internal suspend fun awaitFocalSyncSession(client: SupabaseClient, accountId: String) {
    // RefreshFailure temporarily hides currentUserOrNull in SDK 3.0.3. Let its one
    // refresh job recover; starting another exchange would race refresh-token rotation.
    val status = withTimeoutOrNull(15_000) {
        client.auth.sessionStatus.first { status ->
            status is SessionStatus.NotAuthenticated || (status is SessionStatus.Authenticated &&
                (status.session.user?.id != accountId ||
                    status.session.expiresAt.toEpochMilliseconds() > System.currentTimeMillis()))
        }
    } ?: throw FocalSessionRefreshingException()
    if (status !is SessionStatus.Authenticated || status.session.user?.id != accountId)
        throw FocalSessionRequiredException()
}

internal class FocalSessionRefreshingException : IOException("Focal login refresh is still pending")
internal class FocalSessionRequiredException : IllegalStateException("Sign in to Focal again")

internal fun focalReadCanRetry(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    val response = causes.filterIsInstance<RestException>().firstOrNull()
    if (response != null) return response.statusCode == 408 || response.statusCode == 429 || response.statusCode in 500..599
    return causes.any { it is IOException || it is HttpRequestException || it is HttpRequestTimeoutException }
}

/**
 * The SDK encodes `rpc` arguments with kotlinx.serialization, choosing the serializer from
 * the argument's *static* type. A map built inline from a json array and a string infers
 * `Map<String, Any>`, and there is no serializer for `Any`, so the call dies with
 * `SerializationException: Serializer for class 'Any' is not found` before a request is sent.
 * Pinning every parameter map to `Map<String, JsonElement>` gives it a real serializer and
 * leaves the wire JSON unchanged. [SyncRemoteParamsTests] pins this down.
 */
internal fun applyParams(
    accountId: String,
    changes: List<SyncProtocol.QueuedChange>,
    clientId: String
): Map<String, JsonElement> = mapOf(
    "p_changes" to JsonArray(changes.map { change -> buildJsonObject {
        put("change_id", change.changeId)
        put("client_id", clientId)
        put("entity", change.entity)
        put("row_id", change.rowId)
        put("operation", change.operation)
        put("payload", change.payload?.let { Json.parseToJsonElement(it.toString()) } ?: JsonNull)
        put("lamport", change.lamport)
        change.expectedSeq?.let { put("expected_seq", it) }
    } }),
    "p_expected_user_id" to JsonPrimitive(accountId)
)

internal fun readParams(accountId: String, cursor: Long, limit: Int): Map<String, JsonElement> = mapOf(
    "p_after" to JsonPrimitive(cursor),
    "p_limit" to JsonPrimitive(limit),
    "p_expected_user_id" to JsonPrimitive(accountId)
)

internal fun mutateParams(accountId: String, command: JSONObject): Map<String, JsonElement> = mapOf(
    "p_command" to Json.parseToJsonElement(
        JSONObject(command.toString()).put("expected_user_id", accountId).toString()
    )
)

/**
 * The RPC's answer as a document. Supabase reports a failure in the response body rather
 * than by throwing, so a body that carries neither `rows` nor `receipts` is an error to
 * surface — treating it as an empty page would look like "nothing new" and stall the sync.
 */
internal fun rpcObject(response: PostgrestResult): JSONObject {
    val body = response.data
    val parsed = if (body.trimStart().startsWith("{")) JSONObject(body) else JSONObject()
    if (!parsed.has("rows") && !parsed.has("receipts") && !parsed.has("session") && !parsed.has("server_now")) {
        throw IllegalStateException(parsed.optString("message", "Sync request failed"))
    }
    return parsed
}
