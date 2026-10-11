package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One customizable slot on the drawing toolbar. The three shape tools share a
 * single [SHAPES] slot (matching the toolbar's shapes button), so hiding or
 * moving shapes always moves line, rectangle and ellipse together.
 */
enum class ToolbarSlot {
    PEN, SHAPES, HIGHLIGHTER, ERASER, TEXT, LASSO, HAND, MARK_AREA, STICKY_NOTE;

    /** Tools behind this slot; [SHAPES] expands to the last-used shape tool. */
    val tools: List<Tool> get() = when (this) {
        PEN -> listOf(Tool.PEN)
        SHAPES -> ShapePickerTools.toList()
        HIGHLIGHTER -> listOf(Tool.HIGHLIGHTER)
        ERASER -> listOf(Tool.ERASER)
        TEXT -> listOf(Tool.TEXT)
        LASSO -> listOf(Tool.LASSO)
        HAND -> listOf(Tool.HAND)
        MARK_AREA -> listOf(Tool.MARK_AREA)
        STICKY_NOTE -> listOf(Tool.STICKY_NOTE)
    }

    companion object {
        fun safeValueOf(name: String?): ToolbarSlot? =
            name?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}

/**
 * Which tools sit on the toolbar strip, which are hidden, how many lead the
 * strip, and which saved presets are pinned beside them.
 *
 * [order] holds every slot exactly once; [hidden] removes slots from both the
 * strip and the overflow menu (they return via Edit toolbar). The first
 * [maxPrimary] visible slots lead the strip; the rest live under "…" along
 * with the existing tool actions. [pinnedPresetIds] reference [ToolPreset.id]
 * values in toolbar order; unknown ids are ignored at render time.
 */
data class ToolbarLayout(
    val order: List<ToolbarSlot> = ToolbarLayouts.DEFAULT_ORDER,
    val hidden: Set<ToolbarSlot> = emptySet(),
    val maxPrimary: Int = ToolbarLayouts.DEFAULT_PRIMARY,
    val pinnedPresetIds: List<String> = emptyList()
) {
    /** Visible slots in toolbar order. */
    val visible: List<ToolbarSlot> get() = order.filterNot { it in hidden }

    /** Slots leading the strip; the remainder live under "…". */
    val primary: List<ToolbarSlot> get() = visible.take(maxPrimary.coerceIn(ToolbarLayouts.MIN_PRIMARY, ToolbarLayouts.MAX_PRIMARY + 1))

    /** Visible slots pushed into the overflow menu. */
    val overflow: List<ToolbarSlot> get() = visible.drop(primary.size)
}

/**
 * How the editor's chrome is laid out. [FOLIO] floats the navigation, tools and document actions
 * as separate glass pills; [GOODNOTES] is one flat bar with document tabs above the tools.
 * Both read the same [ToolbarLayout], tools and actions, so only the arrangement differs.
 */
enum class ToolbarStyle(val label: String, val description: String) {
    FOLIO("Folio", "Floating pills for navigation, tools and document actions."),
    GOODNOTES("Goodnotes-inspired", "One flat bar with document tabs on top and the tools in the middle.");

    companion object {
        val DEFAULT = FOLIO

        fun safeValueOf(name: String?): ToolbarStyle =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Which history buttons sit at the start of the tool strip; whatever is left off lives in the … menu. */
enum class UndoButtons(val label: String) {
    BOTH("Undo & redo"), UNDO("Undo only"), NONE("In … menu");

    companion object {
        fun safeValueOf(name: String?): UndoButtons = entries.firstOrNull { it.name == name } ?: BOTH
    }
}

/**
 * What the toolbar shows beyond the tools themselves, shared by both [ToolbarStyle]s. Nothing here
 * removes a feature: undo and redo fall back to the … menu, the timer to the notebook menu, and the
 * tab strip's way home to a button on the bar.
 */
data class ToolbarOptions(
    val undo: UndoButtons = UndoButtons.BOTH,
    /** The pen and highlighter buttons wear a dot of their current colour. */
    val colourDots: Boolean = true,
    /** Pinned presets sit beside the tools (they stay pinned when this is off). */
    val pinnedPresets: Boolean = true,
    /** The exam timer and Focal chips sit on the bar when there is room, otherwise in the notebook menu. */
    val timer: Boolean = true,
    /** The ink options bar (widths and colours) starts open when a notebook opens. */
    val inkOptionsOpen: Boolean = false,
    /** Goodnotes-inspired style only: open documents as tabs above the tools. */
    val tabs: Boolean = true,
) {
    companion object {
        val KEYS = arrayOf(AppPrefs.TOOLBAR_UNDO, AppPrefs.TOOLBAR_COLOUR_DOTS, AppPrefs.TOOLBAR_PINNED_PRESETS,
            AppPrefs.TOOLBAR_TIMER, AppPrefs.TOOLBAR_INK_OPTIONS_OPEN, AppPrefs.TOOLBAR_TABS)

        fun read(prefs: SharedPreferences): ToolbarOptions = ToolbarOptions(
            undo = UndoButtons.safeValueOf(prefs.getString(AppPrefs.TOOLBAR_UNDO, null)),
            colourDots = prefs.getBoolean(AppPrefs.TOOLBAR_COLOUR_DOTS, true),
            pinnedPresets = prefs.getBoolean(AppPrefs.TOOLBAR_PINNED_PRESETS, true),
            timer = prefs.getBoolean(AppPrefs.TOOLBAR_TIMER, true),
            inkOptionsOpen = prefs.getBoolean(AppPrefs.TOOLBAR_INK_OPTIONS_OPEN, false),
            tabs = prefs.getBoolean(AppPrefs.TOOLBAR_TABS, true),
        )
    }
}

/**
 * Pure ordering and persistence rules for [ToolbarLayout], free of Android
 * types (besides the state holder below) so the rules stay JVM-testable.
 */
object ToolbarLayouts {
    const val MIN_PRIMARY = 5
    const val MAX_PRIMARY = 7
    const val DEFAULT_PRIMARY = 7
    const val MAX_PINNED = 4

    val DEFAULT_ORDER: List<ToolbarSlot> = listOf(
        ToolbarSlot.PEN, ToolbarSlot.HIGHLIGHTER, ToolbarSlot.ERASER,
        ToolbarSlot.LASSO, ToolbarSlot.TEXT, ToolbarSlot.SHAPES, ToolbarSlot.HAND, ToolbarSlot.STICKY_NOTE, ToolbarSlot.MARK_AREA
    )

    /** Every slot exactly once, in a sane order even when the stored value is corrupt. */
    fun normalize(
        order: List<ToolbarSlot>?,
        hidden: Set<ToolbarSlot>?,
        maxPrimary: Int?,
        pinned: List<String>?
    ): ToolbarLayout {
        val saneOrder = buildList {
            order?.forEach { if (it !in this) add(it) }
            DEFAULT_ORDER.forEach { if (it !in this) add(it) }
        }
        return ToolbarLayout(
            order = saneOrder,
            hidden = hidden?.intersect(DEFAULT_ORDER.toSet()) ?: emptySet(),
            maxPrimary = (maxPrimary ?: DEFAULT_PRIMARY).coerceIn(MIN_PRIMARY, MAX_PRIMARY),
            pinnedPresetIds = (pinned ?: emptyList())
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PINNED)
        )
    }

    fun default(): ToolbarLayout = ToolbarLayout()

    /** Moves one entry of [order] from [from] to [to], clamped; a bad index leaves it untouched. */
    fun move(order: List<ToolbarSlot>, from: Int, to: Int): List<ToolbarSlot> {
        if (from !in order.indices) return order
        val target = to.coerceIn(0, order.lastIndex)
        if (from == target) return order
        return order.toMutableList().apply { add(target, removeAt(from)) }
    }

    /** Moves one pinned preset id; unknown ids leave the list untouched. */
    fun movePinned(pinned: List<String>, from: Int, to: Int): List<String> {
        if (from !in pinned.indices) return pinned
        val target = to.coerceIn(0, pinned.lastIndex)
        if (from == target) return pinned
        return pinned.toMutableList().apply { add(target, removeAt(from)) }
    }

    fun encodeOrder(order: List<ToolbarSlot>): String = order.joinToString(",") { it.name }
    fun decodeOrder(raw: String?): List<ToolbarSlot>? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.split(",").mapNotNull { part -> part.trim().takeIf { it.isNotBlank() }?.let { ToolbarSlot.safeValueOf(it) } }
        return parsed.takeIf { it.isNotEmpty() }
    }

    fun encodeHidden(hidden: Set<ToolbarSlot>): String = hidden.joinToString(",") { it.name }
    fun decodeHidden(raw: String?): Set<ToolbarSlot>? {
        if (raw == null) return null
        if (raw.isBlank()) return emptySet()
        return raw.split(",").mapNotNull { ToolbarSlot.safeValueOf(it.trim()) }.toSet()
    }

    fun encodePinned(ids: List<String>): String = ids.joinToString(",")
    fun decodePinned(raw: String?): List<String>? {
        if (raw == null) return null
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PINNED)
    }
}

/**
 * Live, persisted toolbar layout shared by the editor toolbar and its edit
 * sheet, so reordering in one place is visible in the other immediately.
 */
@Stable
class ToolbarLayoutState(private val prefs: SharedPreferences) {
    var layout by mutableStateOf(load())
        private set

    /** Picks up a change written by another holder (Settings while an editor is open). */
    fun reload() {
        val stored = load()
        if (stored != layout) layout = stored
    }

    private fun load(): ToolbarLayout = ToolbarLayouts.normalize(
        order = ToolbarLayouts.decodeOrder(prefs.getString(KEY_ORDER, null)),
        hidden = ToolbarLayouts.decodeHidden(prefs.getString(KEY_HIDDEN, null)),
        maxPrimary = prefs.getInt(KEY_PRIMARY, ToolbarLayouts.DEFAULT_PRIMARY)
            .takeIf { prefs.contains(KEY_PRIMARY) },
        pinned = ToolbarLayouts.decodePinned(prefs.getString(KEY_PINNED, null))
    )

    private fun persist(value: ToolbarLayout) {
        layout = value
        prefs.edit()
            .putString(KEY_ORDER, ToolbarLayouts.encodeOrder(value.order))
            .putString(KEY_HIDDEN, ToolbarLayouts.encodeHidden(value.hidden))
            .putInt(KEY_PRIMARY, value.maxPrimary)
            .putString(KEY_PINNED, ToolbarLayouts.encodePinned(value.pinnedPresetIds))
            .apply()
    }

    fun moveSlot(from: Int, to: Int) {
        persist(layout.copy(order = ToolbarLayouts.move(layout.order, from, to)))
    }

    fun hide(slot: ToolbarSlot) {
        // Keep at least one visible tool so the strip never empties itself.
        if (layout.visible.size <= 1) return
        persist(layout.copy(hidden = layout.hidden + slot))
    }

    fun show(slot: ToolbarSlot) {
        persist(layout.copy(hidden = layout.hidden - slot))
    }

    fun setMaxPrimary(count: Int) {
        persist(layout.copy(maxPrimary = count.coerceIn(ToolbarLayouts.MIN_PRIMARY, ToolbarLayouts.MAX_PRIMARY)))
    }

    fun togglePin(presetId: String) {
        val id = presetId.trim()
        if (id.isEmpty()) return
        val next = if (id in layout.pinnedPresetIds) layout.pinnedPresetIds - id
        else (layout.pinnedPresetIds + id).takeLast(ToolbarLayouts.MAX_PINNED)
        persist(layout.copy(pinnedPresetIds = next))
    }

    fun movePinned(from: Int, to: Int) {
        persist(layout.copy(pinnedPresetIds = ToolbarLayouts.movePinned(layout.pinnedPresetIds, from, to)))
    }

    fun reset() {
        persist(ToolbarLayouts.default())
    }

    internal companion object {
        const val KEY_ORDER = "toolbar.order"
        const val KEY_HIDDEN = "toolbar.hidden"
        const val KEY_PRIMARY = "toolbar.primary"
        const val KEY_PINNED = "toolbar.pinned"
        val KEYS = setOf(KEY_ORDER, KEY_HIDDEN, KEY_PRIMARY, KEY_PINNED)
    }
}

/** A [ToolbarLayoutState] that follows the store, so the editor and Settings never disagree. */
@Composable internal fun rememberToolbarLayoutState(prefs: SharedPreferences): ToolbarLayoutState {
    val state = remember(prefs) { ToolbarLayoutState(prefs) }
    DisposableEffect(prefs, state) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in ToolbarLayoutState.KEYS) state.reload() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
