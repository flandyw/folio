package com.folio.notes.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire shapes of sync protocol v3, shared with Focal: log changes, applied row state and queued
 * mutations. Pure parsing and encoding only; publishing and conflict handling live with each feature.
 */
object SyncProtocol {
    /**
     * Every entity either project allows. A client only applies the ones it understands;
     * the rest sit in its applied-state table and cost it nothing, which is what lets one
     * log carry Focal, ExamTrack and Folio data side by side.
     */
    val ENTITIES = listOf(
        "projects", "events", "study_sessions", "custom_subjects", "hidden_subjects",
        "timetable_config", "user_settings", "mistakes", "attempts", "user_state",
        "folio_notebooks", "folio_pages", "folio_strokes"
    )

    /** A change as the log stores it. [seq] is the only ordering authority. */
    data class Change(
        val seq: Long,
        val changeId: String,
        val clientId: String,
        val entity: String,
        val rowId: String,
        val operation: String,
        val payload: JSONObject?,
        val lamport: Long,
        val createdAt: String
    ) {
        val key: String get() = "$entity:$rowId"
        fun toJson(): JSONObject = JSONObject()
            .put("seq", seq)
            .put("changeId", changeId)
            .put("clientId", clientId)
            .put("entity", entity)
            .put("rowId", rowId)
            .put("operation", operation)
            .put("payload", payload ?: JSONObject.NULL)
            .put("lamport", lamport)
            .put("createdAt", createdAt)
    }

    /** The last version of a row this device has applied. */
    data class RowState(
        val entity: String,
        val rowId: String,
        val operation: String,
        val payload: JSONObject?,
        val lamport: Long,
        val clientId: String,
        val seq: Long
    ) {
        val key: String get() = "$entity:$rowId"
        fun toJson(): JSONObject = JSONObject()
            .put("entity", entity)
            .put("rowId", rowId)
            .put("operation", operation)
            .put("payload", payload ?: JSONObject.NULL)
            .put("lamport", lamport)
            .put("clientId", clientId)
            .put("seq", seq)
    }

    /** A change waiting to be published. One per row, so a burst collapses instead of growing. */
    data class QueuedChange(
        val changeId: String,
        val entity: String,
        val rowId: String,
        val operation: String,
        val payload: JSONObject?,
        val lamport: Long,
        val createdAt: String,
        val expectedSeq: Long? = null
    ) {
        val key: String get() = "$entity:$rowId"

        fun toJson(): JSONObject = JSONObject()
            .put("changeId", changeId)
            .put("entity", entity)
            .put("rowId", rowId)
            .put("operation", operation)
            .put("payload", payload ?: JSONObject.NULL)
            .put("lamport", lamport)
            .put("createdAt", createdAt)
            .put("expectedSeq", expectedSeq ?: JSONObject.NULL)

        companion object {
            fun fromJson(o: JSONObject) = QueuedChange(
                changeId = o.getString("changeId"),
                entity = o.getString("entity"),
                rowId = o.getString("rowId"),
                operation = o.getString("operation"),
                payload = o.optJSONObject("payload"),
                lamport = o.optLong("lamport"),
                createdAt = o.optString("createdAt"),
                expectedSeq = if (o.isNull("expectedSeq")) null else o.optLong("expectedSeq")
            )
        }
    }

    fun parseChange(o: JSONObject): Change? {
        val entity = o.optString("entity")
        val operation = o.optString("operation")
        val rowId = o.optString("row_id", o.optString("rowId"))
        val changeId = o.optString("change_id", o.optString("changeId"))
        val clientId = o.optString("client_id", o.optString("clientId"))
        if (entity !in ENTITIES || (operation != "put" && operation != "delete")) return null
        if (rowId.isEmpty() || changeId.isEmpty() || clientId.isEmpty()) return null
        if (!o.has("seq")) return null
        val payload = o.optJSONObject("payload")
        if (operation == "put" && payload == null) return null
        return Change(
            seq = o.optLong("seq"),
            changeId = changeId,
            clientId = clientId,
            entity = entity,
            rowId = rowId,
            operation = operation,
            payload = payload,
            lamport = o.optLong("lamport"),
            createdAt = o.optString("created_at", o.optString("createdAt"))
        )
    }

    fun parseRowState(o: JSONObject): RowState? {
        val entity = o.optString("entity")
        val operation = o.optString("operation")
        val rowId = o.optString("row_id", o.optString("rowId"))
        if (entity !in ENTITIES || (operation != "put" && operation != "delete")) return null
        if (rowId.isEmpty() || !o.has("seq")) return null
        val payload = o.optJSONObject("payload")
        if (operation == "put" && payload == null) return null
        return RowState(
            entity, rowId, operation, payload, o.optLong("lamport"),
            o.optString("client_id", o.optString("clientId")), o.optLong("seq")
        )
    }
}

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
