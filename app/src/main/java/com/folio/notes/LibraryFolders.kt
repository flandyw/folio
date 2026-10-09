package com.folio.notes

import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Forest rules shared by the explorer, destination pickers and backup restore. */
object LibraryFolders {
    fun path(folders: List<Folder>, id: String?): List<Folder> {
        val byId = folders.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val path = mutableListOf<Folder>()
        var next = id
        while (next != null && seen.add(next)) {
            val folder = byId[next] ?: break
            path += folder
            next = folder.parentId
        }
        return path.asReversed()
    }

    fun label(folders: List<Folder>, id: String?) = path(folders, id).joinToString(" / ") { it.name }

    fun descendants(folders: List<Folder>, id: String): Set<String> {
        val children = folders.groupBy { it.parentId }
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (result.add(current)) children[current].orEmpty().forEach { queue.add(it.id) }
        }
        return result
    }

    fun canMove(folders: List<Folder>, id: String, parent: String?): Boolean =
        folders.any { it.id == id } && (parent == null ||
            (folders.any { it.id == parent } && parent !in descendants(folders, id)))

    fun valid(folders: List<Folder>): Boolean {
        val parents = folders.associate { it.id to it.parentId }
        if (parents.size != folders.size) return false
        val completed = mutableSetOf<String>()
        for (folder in folders) {
            val seen = mutableSetOf<String>()
            var next: String? = folder.id
            while (next != null && next !in completed) {
                if (next !in parents || !seen.add(next)) return false
                next = parents[next]
            }
            completed += seen
        }
        return true
    }

    fun remap(folders: List<Folder>): Pair<List<Folder>, Map<String, String>> {
        require(valid(folders)) { "Invalid folder hierarchy" }
        val ids = folders.associate { it.id to UUID.randomUUID().toString() }
        return folders.map { it.copy(id = ids.getValue(it.id), parentId = it.parentId?.let(ids::getValue)) } to ids
    }
}

object FolderCodec {
    fun encode(folder: Folder) = JSONObject().put("id", folder.id).put("name", folder.name).apply {
        folder.parentId?.let { put("parent", it) }
        if (folder.color != 0) put("color", folder.color)
    }
    fun decode(value: JSONObject) = Folder(value.getString("id"), value.getString("name"),
        value.optString("parent", "").takeIf { it.isNotEmpty() && it != "null" }, value.optInt("color", 0).coerceAtLeast(0))
}

object NotebookTags {
    const val MAX_TAGS = 32
    const val MAX_LENGTH = 40
    fun normalize(tags: Iterable<String>, limit: Int = MAX_TAGS): List<String> = tags.map { it.trim().take(MAX_LENGTH) }
        .filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }.take(limit.coerceAtLeast(0)).sortedBy { it.lowercase(Locale.ROOT) }
    fun decode(array: JSONArray?): List<String> = normalize(if (array == null) emptyList()
        else (0 until array.length()).map { array.optString(it, "") })
}
