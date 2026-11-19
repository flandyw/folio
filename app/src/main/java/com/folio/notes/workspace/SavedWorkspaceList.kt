package com.folio.notes.workspace

import org.json.JSONArray
import org.json.JSONObject

internal object SavedWorkspaceList {
    fun parse(raw: String?): List<JSONObject> {
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    fun renameError(items: List<JSONObject>, index: Int, name: String): String? = when {
        index !in items.indices -> "This workspace is no longer available."
        name.isBlank() -> "Enter a workspace name."
        items.indices.any { it != index && items[it].optString("name").trim().equals(name.trim(), true) } ->
            "A workspace with this name already exists."
        else -> null
    }

    fun rename(items: List<JSONObject>, index: Int, name: String): List<JSONObject> {
        require(renameError(items, index, name) == null)
        return items.mapIndexed { i, item ->
            if (i == index) JSONObject(item.toString()).put("name", name.trim()) else item
        }
    }

    fun move(items: List<JSONObject>, index: Int, offset: Int): List<JSONObject> {
        if (offset !in listOf(-1, 1) || index !in items.indices || index + offset !in items.indices) return items
        return items.toMutableList().apply { add(index + offset, removeAt(index)) }
    }
}
