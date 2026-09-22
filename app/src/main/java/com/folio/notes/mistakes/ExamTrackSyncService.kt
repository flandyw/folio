package com.folio.notes.mistakes

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject

class ExamTrackSyncService(private val client: SupabaseClient) : ExamTrackRemote {
    private fun checkUser(user: String) { check(client.auth.currentUserOrNull()?.id == user) { "Sign in again" } }
    private suspend fun rows(table: String, user: String): List<JSONObject> {
        checkUser(user)
        val result = mutableListOf<JSONObject>()
        var offset = 0L
        do {
            checkUser(user)
            val data = client.from(table).select {
                filter { eq("user_id", user) }
                order("id", Order.ASCENDING)
                range(offset, offset + 499)
            }.data
            val page = JSONArray(data)
            repeat(page.length()) { result += page.getJSONObject(it) }
            offset += page.length()
        } while (page.length() == 500)
        return result
    }
    override suspend fun fetch(userId: String) = rows("mistakes", userId).map { r ->
        RemoteMistakeRow(r.getString("id"), r.optJSONObject("payload")?.toString(),
            r.getString("updated_at"), r.opt("deleted_at") as? String)
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
        val patch = JSONObject().put("deleted_at", deletedAt).put("updated_at", deletedAt)
        val data = client.from("mistakes").update(Json.parseToJsonElement(patch.toString()).jsonObject) {
            filter {
                eq("user_id", userId); eq("id", expected.id); eq("updated_at", expected.updatedAt)
                exact("deleted_at", null)
            }
            select()
        }.data
        return JSONArray(data).length() == 1
    }
    override suspend fun contexts(userId: String): Map<String, ExamContext> = rows("attempts", userId)
        .filter { it.isNull("deleted_at") }.mapNotNull { r ->
            val p = r.optJSONObject("payload") ?: return@mapNotNull null
            r.getString("id") to ExamContext(p.optString("subject", ""), p.optString("title", ""), p.optString("paper", ""))
        }.toMap()
}
