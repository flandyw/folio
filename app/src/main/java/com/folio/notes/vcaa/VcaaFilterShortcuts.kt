package com.folio.notes.vcaa

import com.folio.notes.exam.ExamType
import org.json.JSONArray
import org.json.JSONObject

internal data class VcaaFilterSet(
    val study: String?,
    val year: Int?,
    val type: ExamType?,
) {
    val isEmpty: Boolean get() = study == null && year == null && type == null
}

internal data class VcaaFilterShortcut(val name: String, val filters: VcaaFilterSet)

internal object VcaaFilterShortcuts {
    const val MAX_NAME_LENGTH = 60

    fun nameError(name: String, shortcuts: List<VcaaFilterShortcut>): String? = when {
        name.isBlank() -> "Enter a name."
        name.trim().length > MAX_NAME_LENGTH -> "Use $MAX_NAME_LENGTH characters or fewer."
        shortcuts.any { it.name.equals(name.trim(), ignoreCase = true) } -> "A saved filter already has this name."
        else -> null
    }

    fun encode(shortcuts: List<VcaaFilterShortcut>): String = JSONArray(shortcuts.map { (name, filters) ->
        JSONObject().put("name", name)
            .put("study", filters.study)
            .put("year", filters.year)
            .put("type", filters.type?.name)
    }).toString()

    fun decode(raw: String?): List<VcaaFilterShortcut> {
        val entries = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return buildList {
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val name = (entry.opt("name") as? String)?.trim() ?: continue
                val study = if (entry.isNull("study")) null else (entry.opt("study") as? String)
                    ?.takeIf { it.isNotBlank() } ?: continue
                val year = if (entry.isNull("year")) null else (entry.opt("year") as? Int)
                    ?.takeIf { it > 0 } ?: continue
                val type = if (entry.isNull("type")) null else ExamType.safeValueOf(entry.opt("type") as? String)
                    ?.takeIf { it.isExam } ?: continue
                val filters = VcaaFilterSet(study, year, type)
                if (!filters.isEmpty && nameError(name, this) == null) add(VcaaFilterShortcut(name, filters))
            }
        }
    }
}
