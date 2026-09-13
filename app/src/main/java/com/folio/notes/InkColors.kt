package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject

/** A named set of quick ink colours the user can apply to their row in one tap. */
data class ColorPalette(val name: String, val colors: List<Int>)

/** A quick-colour row the user saved, so a favourite mix can be restored in any notebook. */
data class ColorPreset(val name: String, val colors: List<Int>)

/** ARGB colour literal kept as an Int, matching how ink colours are stored on a stroke. */
private fun ink(value: Long): Int = value.toInt()

/**
 * The built-in ink palettes plus the pure encoding used to persist the user's own quick colours
 * and presets. Deliberately free of Android and Compose types so the rules stay JVM-testable.
 */
object InkColors {
    /** How many swatches the editable quick row holds. */
    const val SLOT_COUNT = 5

    /** A cap so the preset list can never grow without bound. */
    const val MAX_PRESETS = 12
    const val MAX_PRESET_NAME = 24

    /** Ink tools (pen, shapes, highlighter off) share one quick row. */
    const val INK_GROUP = "INK"

    /** The highlighter keeps its own quick row and presets, because its colours are translucent. */
    const val HIGHLIGHTER_GROUP = "HIGHLIGHTER"

    /** Every colour group that has its own row and presets. */
    val groups: List<String> = listOf(INK_GROUP, HIGHLIGHTER_GROUP)

    /** Curated exam colours: black, two blues, teal, red, purple, orange, grey. */
    val ExamColors: List<Int> = ToolOptions.ExamColors

    /** Bright, translucent-friendly colours that read well through the highlighter's low opacity. */
    val highlighterPalette = ColorPalette("Highlighter", listOf(
        ink(0xFFE9BF44), ink(0xFF8FD18F), ink(0xFFE879A0), ink(0xFF7FB3E8), ink(0xFFF0A860), ink(0xFFB79CE0), ink(0xFF9FE0D8)
    ))

    /** Palettes offered in the editor, the exam-relevant ones first. */
    val palettes: List<ColorPalette> = listOf(
        ColorPalette("Exam", ExamColors),
        highlighterPalette,
        ColorPalette("Classic", listOf(ink(0xFF202124), ink(0xFFD93025), ink(0xFF1A73E8), ink(0xFF188038), ink(0xFFF9AB00), ink(0xFF9334E6), ink(0xFFE8710A))),
        ColorPalette("Pastel", listOf(ink(0xFFE8A0A0), ink(0xFFA8C6E8), ink(0xFFA8D5BA), ink(0xFFE8D5A0), ink(0xFFC9A8E8), ink(0xFFA0D5D5), ink(0xFFD8C3A5))),
        ColorPalette("Vivid", listOf(ink(0xFFFF3B30), ink(0xFF007AFF), ink(0xFF34C759), ink(0xFFFFCC00), ink(0xFFFF9500), ink(0xFFAF52DE), ink(0xFFFF2D55))),
        ColorPalette("Cool", listOf(ink(0xFF0B3C5D), ink(0xFF1A73E8), ink(0xFF00A6A6), ink(0xFF2E8B57), ink(0xFF6A5ACD), ink(0xFF4682B4), ink(0xFF3A3A3A))),
        ColorPalette("Warm", listOf(ink(0xFF8B2E2E), ink(0xFFC0392B), ink(0xFFD2691E), ink(0xFFE67E22), ink(0xFFB8860B), ink(0xFF8B4513), ink(0xFF3A3A3A))),
        ColorPalette("Graphite", listOf(ink(0xFF1A1C1A), ink(0xFF3A3A3A), ink(0xFF6E6E6E), ink(0xFF9E9E9E), ink(0xFFC7C7C7), ink(0xFFFFFFFF), ink(0xFF000000)))
    )

    /** Applied to a new ink row when the user has not chosen a palette yet. */
    val defaultPalette: ColorPalette = palettes.first()

    /** The quick row shown to a new user: the first five exam colours. */
    val defaultQuick: List<Int> = quickRow(ExamColors)

    /** Everything the editor offers in its colour grid, deduplicated. */
    val swatches: List<Int> = (palettes.flatMap { it.colors } +
        listOf(ink(0xFFA64B30), ink(0xFF48674B), ink(0xFF426EAD), ink(0xFF8B60A3), ink(0xFF26A6A1))).distinct()

    /** The colour group a tool writes with: the highlighter stands apart from the ink tools. */
    fun groupOf(tool: Tool): String = if (tool == Tool.HIGHLIGHTER) HIGHLIGHTER_GROUP else INK_GROUP

    /** The palette a fresh option row for [group] starts from. */
    fun paletteFor(group: String): ColorPalette = if (group == HIGHLIGHTER_GROUP) highlighterPalette else defaultPalette

    /** The row of exactly [SLOT_COUNT] colours a fresh [group] starts from. */
    fun defaultQuick(group: String): List<Int> {
        val colors = paletteFor(group).colors
        return quickRow(colors, colors)
    }

    /**
     * The row of exactly [SLOT_COUNT] colours, padded from [fallback] (by default the exam colours)
     * when [colors] is short, so the toolbar always renders the same number of swatches.
     */
    fun quickRow(colors: List<Int>, fallback: List<Int> = ExamColors): List<Int> {
        val base = fallback.take(SLOT_COUNT).ifEmpty { ExamColors.take(SLOT_COUNT) }
        return (0 until SLOT_COUNT).map { index -> colors.getOrNull(index) ?: base.getOrElse(index) { base.last() } }
    }

    /** Six-digit hex without the leading `#`, used in labels and the hex field. */
    fun hex(color: Int): String = String.format(java.util.Locale.ROOT, "%06X", color and 0xFFFFFF)

    /** True when a white check mark would be hard to read, so the swatch switches to black. */
    fun isLight(color: Int): Boolean {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b > 0.6f
    }

    fun encodeColors(colors: List<Int>): String = JSONArray(colors).toString()

    /** The stored quick row, or null when nothing valid has been saved yet. */
    fun decodeColors(value: String?): List<Int>? {
        if (value.isNullOrBlank()) return null
        return try {
            val array = JSONArray(value)
            (0 until array.length()).map { array.getInt(it) }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    fun encodePresets(presets: List<ColorPreset>): String = JSONArray().apply {
        presets.forEach { preset ->
            put(JSONObject().apply {
                put("name", preset.name)
                put("colors", JSONArray(preset.colors))
            })
        }
    }.toString()

    fun decodePresets(value: String?): List<ColorPreset> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val name = normalizedPresetName(entry.optString("name"))
                val colors = entry.optJSONArray("colors")?.let { list -> (0 until list.length()).map { list.getInt(it) } }.orEmpty()
                if (name.isEmpty() || colors.isEmpty()) null else ColorPreset(name, colors)
            }.take(MAX_PRESETS)
        } catch (_: Exception) { emptyList() }
    }

    fun normalizedPresetName(name: String): String = name.trim().take(MAX_PRESET_NAME)

    /** Replaces any preset with the same name (ignoring case) and keeps the newest one last. */
    fun upsertPreset(presets: List<ColorPreset>, preset: ColorPreset): List<ColorPreset> =
        (presets.filterNot { it.name.equals(preset.name, ignoreCase = true) } + preset).takeLast(MAX_PRESETS)

    fun removePreset(presets: List<ColorPreset>, name: String): List<ColorPreset> =
        presets.filterNot { it.name.equals(name, ignoreCase = true) }
}

/**
 * Live, persisted quick-colour row and presets for the editor, kept per colour group so the
 * highlighter's mix never overwrites the pen's. A single instance is shared by the toolbar
 * swatches and the colour sheet, so an edit in either is reflected in both immediately.
 */
@Stable
class QuickColorsState(private val prefs: SharedPreferences) {
    private val rows = mutableStateMapOf<String, List<Int>>()
    private val saved = mutableStateMapOf<String, List<ColorPreset>>()

    /** Empty string means the row has been hand-edited away from any built-in palette. */
    private val chosen = mutableStateMapOf<String, String>()

    init {
        InkColors.groups.forEach { group ->
            // An earlier build stored a single shared row, so seed the ink group from it once.
            val legacyRow = if (group == InkColors.INK_GROUP) prefs.getString(KEY_COLORS, null) else null
            val legacyPresets = if (group == InkColors.INK_GROUP) prefs.getString(KEY_PRESETS, null) else null
            val legacyPalette = if (group == InkColors.INK_GROUP) prefs.getString(KEY_PALETTE, null) else null
            rows[group] = (InkColors.decodeColors(prefs.getString(key(group, KEY_COLORS), legacyRow)))?.let { InkColors.quickRow(it, InkColors.defaultQuick(group)) }
                ?: InkColors.defaultQuick(group)
            saved[group] = InkColors.decodePresets(prefs.getString(key(group, KEY_PRESETS), legacyPresets))
            chosen[group] = prefs.getString(key(group, KEY_PALETTE), legacyPalette) ?: InkColors.paletteFor(group).name
        }
    }

    /** The quick row for [group]; always exactly [InkColors.SLOT_COUNT] colours. */
    fun colors(group: String): List<Int> = rows[group] ?: InkColors.defaultQuick(group)

    fun presets(group: String): List<ColorPreset> = saved[group].orEmpty()

    /** Built-in palette currently loaded for [group], or null once its row has been hand-edited. */
    fun palette(group: String): String? = chosen[group]?.takeIf { it.isNotEmpty() }

    /** Writes [color] into one quick slot and marks the row as customised. */
    fun setSlot(group: String, index: Int, color: Int) {
        val current = colors(group)
        if (index !in current.indices) return
        rows[group] = current.toMutableList().also { it[index] = color }
        chosen[group] = ""
        persist(group)
    }

    /** Replaces the whole row with the first [InkColors.SLOT_COUNT] colours of [value]. */
    fun applyPalette(group: String, value: ColorPalette) {
        rows[group] = InkColors.quickRow(value.colors, InkColors.defaultQuick(group))
        chosen[group] = value.name
        persist(group)
    }

    fun applyPreset(group: String, preset: ColorPreset) {
        rows[group] = InkColors.quickRow(preset.colors, InkColors.defaultQuick(group))
        chosen[group] = ""
        persist(group)
    }

    /** Saves the group's current row under [name], replacing any preset with that name. */
    fun savePreset(group: String, name: String) {
        val trimmed = InkColors.normalizedPresetName(name)
        if (trimmed.isEmpty()) return
        val current = presets(group)
        if (current.size >= InkColors.MAX_PRESETS && current.none { it.name.equals(trimmed, ignoreCase = true) }) return
        saved[group] = InkColors.upsertPreset(current, ColorPreset(trimmed, colors(group)))
        persist(group)
    }

    fun deletePreset(group: String, preset: ColorPreset) {
        saved[group] = InkColors.removePreset(presets(group), preset.name)
        persist(group)
    }

    private fun persist(group: String) {
        prefs.edit()
            .putString(key(group, KEY_COLORS), InkColors.encodeColors(colors(group)))
            .putString(key(group, KEY_PRESETS), InkColors.encodePresets(presets(group)))
            .putString(key(group, KEY_PALETTE), chosen[group] ?: "")
            .apply()
    }

    private fun key(group: String, suffix: String) = "$suffix.$group"

    private companion object {
        const val KEY_COLORS = "quickColors"
        const val KEY_PRESETS = "colorPresets"
        const val KEY_PALETTE = "quickPalette"
    }
}
