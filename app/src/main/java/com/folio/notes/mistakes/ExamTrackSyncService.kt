package com.folio.notes.mistakes

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.folio.notes.sync.ReadResult
import com.folio.notes.sync.SupabaseSyncRemote
import org.json.JSONArray
import org.json.JSONObject

class ExamTrackSyncService(private val client: SupabaseClient) : ExamTrackRemote {
    private val syncRemote = SupabaseSyncRemote(client)
    private fun checkUser(user: String) { check(client.auth.currentUserOrNull()?.id == user) { "Sign in again" } }
    override suspend fun read(userId: String, cursor: Long, limit: Int): ReadResult =
        syncRemote.read(userId, cursor, limit)

    override suspend fun fetch(userId: String, ids: Set<String>): List<RemoteMistakeRow> {
        checkUser(userId)
        // ponytail: queued edits are normally few; targeted reads keep sync incremental. Batch with PostgREST `in` if offline queues become large.
        return ids.flatMap { id ->
            checkUser(userId)
            val data = client.from("mistakes").select {
                filter { eq("user_id", userId); eq("id", id) }
            }.data
            val rows = JSONArray(data)
            (0 until rows.length()).map { rows.getJSONObject(it) }.map { row ->
                RemoteMistakeRow(row.getString("id"), row.optJSONObject("payload")?.toString(),
                    row.getString("updated_at"), row.opt("deleted_at") as? String)
            }
        }
    }
    override suspend fun update(userId: String, expected: RemoteMistakeRow, payload: ExamTrackMistake): Boolean {
        checkUser(userId)
        val patch = JSONObject().put("payload", JSONObject(payload.originalJson)).put("updated_at", payload.updatedAt)
        val data = client.from("mistakes").update(Json.parseToJsonElement(patch.toString()).jsonObject) {
            filter {
                eq("user_id", userId); eq("id", expected.id); eq("updated_at", expected.updatedAt)
                exact("deleted_at", null)
            }
            select()
        }.data
        return JSONArray(data).length() == 1
    }
    override suspend fun delete(userId: String, expected: RemoteMistakeRow, deletedAt: String): Boolean {
        checkUser(userId)
        // ExamTrack's mistakes_deleted_payload constraint requires tombstones to have no payload.
        val patch = JSONObject().put("payload", JSONObject.NULL)
            .put("deleted_at", deletedAt).put("updated_at", deletedAt)
        val data = client.from("mistakes").update(Json.parseToJsonElement(patch.toString()).jsonObject) {
            filter {
                eq("user_id", userId); eq("id", expected.id); eq("updated_at", expected.updatedAt)
                exact("deleted_at", null)
            }
            select()
        }.data
        return JSONArray(data).length() == 1
    }
}
