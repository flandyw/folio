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

    /**
     * Restrained, opaque writing inks. Each clears 4.5:1 contrast on white and warm paper
     * (#FFF8E7), keeping fine handwriting legible without neon or washed-out pastels.
     * The first five balance everyday writing, colour coding and corrections.
     */
    val namedSwatches: List<Pair<String, Int>> = listOf(
        "Graphite" to ink(0xFF252A30),
        "Blue ink" to ink(0xFF2854A0),
        "Deep teal" to ink(0xFF176B68),
        "Crimson" to ink(0xFFA83246),
        "Aubergine" to ink(0xFF704080),
        "Midnight navy" to ink(0xFF203858),
        "Forest green" to ink(0xFF356344),
        "Burnt sienna" to ink(0xFFA04B2D),
        "Indigo" to ink(0xFF514B91),
        "Slate" to ink(0xFF596573)
    )

    val swatches: List<Int> = namedSwatches.map { it.second }
    val defaultPalette = ColorPalette("Writing inks", swatches)

    /**
     * Ready-made quick rows, each five restrained inks that keep the 4.5:1 contrast of the writing
     * inks. Names are stored with the row, so never rename one without a migration.
     */
    val palettes: List<ColorPalette> = listOf(
        defaultPalette,
        ColorPalette("Colour coding", listOf(ink(0xFF252A30), ink(0xFF1F5FA8), ink(0xFFB3261E), ink(0xFF2E6B3A), ink(0xFF6B3FA0))),
        ColorPalette("Classroom", listOf(ink(0xFF252A30), ink(0xFF2854A0), ink(0xFFA83246), ink(0xFF356344), ink(0xFF9A5200))),
        ColorPalette("Earth", listOf(ink(0xFF3E2F25), ink(0xFFA04B2D), ink(0xFF5E6B2A), ink(0xFF2F5D62), ink(0xFF7A5C3E))),
        ColorPalette("Ocean", listOf(ink(0xFF0B3C5D), ink(0xFF1D5C8C), ink(0xFF176B68), ink(0xFF514B91), ink(0xFF203858))),
        ColorPalette("Berry", listOf(ink(0xFF3B1F3F), ink(0xFF704080), ink(0xFFA83246), ink(0xFF8A2D5F), ink(0xFF514B91))),
        ColorPalette("Greys", listOf(ink(0xFF111418), ink(0xFF2F363D), ink(0xFF454E58), ink(0xFF596573), ink(0xFF6B7480)))
    )
    val defaultQuick: List<Int> = quickRow(swatches)

    /** Retired built-in rows are replaced; hand-edited rows and saved presets are retained. */
    fun restoredQuick(group: String, colors: List<Int>?, palette: String?): List<Int> =
        if (!palette.isNullOrEmpty() && palettes.none { it.name == palette }) defaultQuick(group)
        else colors?.let { quickRow(it, defaultQuick(group)) } ?: defaultQuick(group)

    /** The colour group a tool writes with: the highlighter stands apart from the ink tools. */
    fun groupOf(tool: Tool): String = if (tool == Tool.HIGHLIGHTER) HIGHLIGHTER_GROUP else INK_GROUP

    /** The palette a fresh option row for [group] starts from. */
    fun paletteFor(group: String): ColorPalette = defaultPalette

    /** The row of exactly [SLOT_COUNT] colours a fresh [group] starts from. */
    fun defaultQuick(group: String): List<Int> {
        val colors = paletteFor(group).colors
        return quickRow(colors, colors)
    }

    /**
     * The row of exactly [SLOT_COUNT] colours, padded from [fallback] (by default the writing inks)
     * when [colors] is short, so the toolbar always renders the same number of swatches.
     */
    fun quickRow(colors: List<Int>, fallback: List<Int> = swatches): List<Int> {
        val base = fallback.take(SLOT_COUNT).ifEmpty { swatches.take(SLOT_COUNT) }
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
            val storedPalette = prefs.getString(key(group, KEY_PALETTE), legacyPalette)
            rows[group] = InkColors.restoredQuick(group, InkColors.decodeColors(prefs.getString(key(group, KEY_COLORS), legacyRow)), storedPalette)
            saved[group] = InkColors.decodePresets(prefs.getString(key(group, KEY_PRESETS), legacyPresets))
            chosen[group] = storedPalette?.takeIf { it.isEmpty() || InkColors.palettes.any { palette -> palette.name == it } }
                ?: InkColors.paletteFor(group).name
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
