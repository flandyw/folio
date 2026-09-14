package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A one-tap favorite tool setup, like GoodNotes' three pen slots: the drawing tool plus the
 * colour, width and opacity it writes with. Pressure and sensitivity stay with the tool itself,
 * so applying a preset never silently changes how the pen responds to pressure.
 */
data class ToolPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tool: Tool,
    val color: Int,
    val width: Float,
    val opacity: Float,
    val style: StrokeStyle = StrokeStyle.SOLID
)

/**
 * Pure encoding and list rules for tool presets, free of Android types so the rules stay
 * JVM-testable. Persistence itself lives in [ToolPresetState].
 */
object ToolPresets {
    const val MAX_PRESETS = 8
    const val MAX_NAME = 20

    fun normalizedName(name: String): String = name.trim().take(MAX_NAME)

    fun encode(presets: List<ToolPreset>): String = JSONArray().apply {
        presets.forEach { preset ->
            put(JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("tool", preset.tool.name)
                put("color", preset.color)
                put("width", preset.width.toDouble())
                put("opacity", preset.opacity.toDouble())
                if (preset.style != StrokeStyle.SOLID) put("style", preset.style.name)
            })
        }
    }.toString()

    fun decode(value: String?): List<ToolPreset> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val name = normalizedName(entry.optString("name"))
                val tool = try { Tool.valueOf(entry.optString("tool")) } catch (_: Exception) { return@mapNotNull null }
                if (name.isEmpty()) return@mapNotNull null
                // Only drawing tools make sense as presets; the eraser, lasso, text and hand
                // tools have no colour/width worth remembering.
                if (tool != Tool.PEN && tool != Tool.HIGHLIGHTER && tool != Tool.LINE &&
                    tool != Tool.RECTANGLE && tool != Tool.ELLIPSE
                ) return@mapNotNull null
                val id = entry.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                ToolPreset(
                    id = id,
                    name = name,
                    tool = tool,
                    color = entry.optInt("color", 0xFF303431.toInt()),
                    width = entry.optDouble("width", 2.2).toFloat().coerceIn(0.5f, 72f),
                    opacity = entry.optDouble("opacity", 1.0).toFloat().coerceIn(0.05f, 1f),
                    style = if (entry.isNull("style")) StrokeStyle.SOLID
                    else StrokeStyle.safeValueOf(entry.optString("style", "SOLID"))
                )
            }.take(MAX_PRESETS)
        } catch (_: Exception) { emptyList() }
    }

    /** Replaces any preset with the same name (ignoring case), newest last, capped. */
    fun upsert(presets: List<ToolPreset>, preset: ToolPreset): List<ToolPreset> =
        (presets.filterNot { it.name.equals(preset.name, ignoreCase = true) } + preset).takeLast(MAX_PRESETS)

    fun remove(presets: List<ToolPreset>, id: String): List<ToolPreset> =
        presets.filterNot { it.id == id }

    fun fromOptions(name: String, tool: Tool, options: ToolOptions, style: StrokeStyle = StrokeStyle.SOLID): ToolPreset? {
        val trimmed = normalizedName(name)
        if (trimmed.isEmpty()) return null
        if (tool != Tool.PEN && tool != Tool.HIGHLIGHTER && tool != Tool.LINE &&
            tool != Tool.RECTANGLE && tool != Tool.ELLIPSE
        ) return null
        return ToolPreset(name = trimmed, tool = tool, color = options.color,
            width = options.width, opacity = options.opacity, style = style)
    }
}

/**
 * Live, persisted tool presets shared by the toolbar and the tool settings sheet, so saving in
 * one place is visible in the other immediately.
 */
@Stable
class ToolPresetState(private val prefs: SharedPreferences) {
    private val _presets = mutableStateListOf<ToolPreset>()

    val presets: List<ToolPreset> get() = _presets.toList()

    init {
        _presets.addAll(ToolPresets.decode(prefs.getString(KEY, null)))
    }

    fun save(name: String, tool: Tool, options: ToolOptions, style: StrokeStyle = StrokeStyle.SOLID): Boolean {
        val preset = ToolPresets.fromOptions(name, tool, options, style) ?: return false
        if (_presets.size >= ToolPresets.MAX_PRESETS &&
            _presets.none { it.name.equals(preset.name, ignoreCase = true) }
        ) return false
        val updated = ToolPresets.upsert(_presets.toList(), preset)
        _presets.clear()
        _presets.addAll(updated)
        persist()
        return true
    }

    fun delete(id: String) {
        val updated = ToolPresets.remove(_presets.toList(), id)
        if (updated.size == _presets.size) return
        _presets.clear()
        _presets.addAll(updated)
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY, ToolPresets.encode(_presets.toList())).apply()
    }

    private companion object {
        const val KEY = "toolPresets"
    }
}
