package com.folio.notes

/**
 * What a stylus shortcut (currently the OnePlus/OPPO Pencil double tap) should do.
 *
 * The OEM event is only a trigger; the action stays app-owned and user-configurable, so the
 * same preference can later be fed by another vendor's stylus adapter.
 */
enum class StylusShortcut(val label: String, val description: String) {
    PEN_ERASER("Pen \u2194 eraser", "Double tap swaps between the pen and the eraser"),
    PREVIOUS_TOOL("Previous tool", "Double tap erases, then returns to the tool you used before"),
    PEN_HIGHLIGHTER("Pen \u2194 highlighter", "Double tap swaps between the pen and the highlighter"),
    LASSO("Lasso", "Double tap switches to the lasso selection tool"),
    COLOR_PALETTE("Colour palette", "Double tap opens colour, width and opacity options"),
    UNDO("Undo", "Double tap undoes the last stroke"),
    NEXT_LINE("Next writing line", "Double tap moves to the next writing line"),
    DISABLED("Disabled", "Ignore the stylus double tap");

    companion object {
        const val PREF_KEY = "stylusDoubleTap"

        /** Reads a stored preference, defaulting to the pen/eraser swap ColorOS also uses. */
        fun of(value: String?): StylusShortcut = entries.find { it.name == value } ?: PEN_ERASER
    }
}

/** The editor action a shortcut resolves to, kept free of Android types so it stays testable. */
sealed interface StylusShortcutEffect {
    data class SwitchTool(val tool: Tool) : StylusShortcutEffect
    data object OpenPalette : StylusShortcutEffect
    data object Undo : StylusShortcutEffect
    data object NextLine : StylusShortcutEffect
    data object None : StylusShortcutEffect
}

object StylusShortcuts {
    /**
     * Resolves the configured shortcut against the current tool and the last drawing tool.
     *
     * [previous] is the tool that was active before the eraser, so both directions of a toggle
     * restore the user's real tool instead of always falling back to the pen.
     */
    fun effect(action: StylusShortcut, current: Tool, previous: Tool): StylusShortcutEffect = when (action) {
        StylusShortcut.PEN_ERASER -> StylusShortcutEffect.SwitchTool(if (current == Tool.ERASER) Tool.PEN else Tool.ERASER)
        StylusShortcut.PREVIOUS_TOOL -> StylusShortcutEffect.SwitchTool(if (current == Tool.ERASER) previous.orPen() else Tool.ERASER)
        StylusShortcut.PEN_HIGHLIGHTER -> StylusShortcutEffect.SwitchTool(if (current == Tool.HIGHLIGHTER) Tool.PEN else Tool.HIGHLIGHTER)
        StylusShortcut.LASSO -> StylusShortcutEffect.SwitchTool(Tool.LASSO)
        StylusShortcut.COLOR_PALETTE -> StylusShortcutEffect.OpenPalette
        StylusShortcut.UNDO -> StylusShortcutEffect.Undo
        StylusShortcut.NEXT_LINE -> StylusShortcutEffect.NextLine
        StylusShortcut.DISABLED -> StylusShortcutEffect.None
    }

    /** Tools that leave ink on the page, so a shortcut returns to one of these rather than to a mode. */
    fun isDrawingTool(tool: Tool) = tool in setOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.LINE, Tool.RECTANGLE, Tool.ELLIPSE)

    private fun Tool.orPen() = if (isDrawingTool(this)) this else Tool.PEN
}
