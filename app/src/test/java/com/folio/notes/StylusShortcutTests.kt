package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class StylusShortcutTests {
    private fun tool(action: StylusShortcut, current: Tool, previous: Tool = Tool.PEN): Tool? =
        (StylusShortcuts.effect(action, current, previous) as? StylusShortcutEffect.SwitchTool)?.tool

    @Test fun penAndEraserSwapInBothDirections() {
        assertEquals(Tool.ERASER, tool(StylusShortcut.PEN_ERASER, Tool.PEN))
        assertEquals(Tool.PEN, tool(StylusShortcut.PEN_ERASER, Tool.ERASER))
        assertEquals(Tool.ERASER, tool(StylusShortcut.PEN_ERASER, Tool.HIGHLIGHTER))
    }

    @Test fun previousToolRestoresTheRealDrawingToolInsteadOfThePen() {
        assertEquals(Tool.HIGHLIGHTER, tool(StylusShortcut.PREVIOUS_TOOL, Tool.ERASER, previous = Tool.HIGHLIGHTER))
        assertEquals(Tool.RECTANGLE, tool(StylusShortcut.PREVIOUS_TOOL, Tool.ERASER, previous = Tool.RECTANGLE))
    }

    @Test fun previousToolErasesFromTheCurrentToolAndFallsBackToThePen() {
        assertEquals(Tool.ERASER, tool(StylusShortcut.PREVIOUS_TOOL, Tool.HIGHLIGHTER, previous = Tool.PEN))
        assertEquals(Tool.PEN, tool(StylusShortcut.PREVIOUS_TOOL, Tool.ERASER, previous = Tool.ERASER))
    }

    @Test fun highlighterSwapsWithThePen() {
        assertEquals(Tool.HIGHLIGHTER, tool(StylusShortcut.PEN_HIGHLIGHTER, Tool.PEN))
        assertEquals(Tool.PEN, tool(StylusShortcut.PEN_HIGHLIGHTER, Tool.HIGHLIGHTER))
    }

    @Test fun lassoShortcutArmsTheSelectionToolFromAnyTool() {
        assertEquals(Tool.LASSO, tool(StylusShortcut.LASSO, Tool.PEN))
        assertEquals(Tool.LASSO, tool(StylusShortcut.LASSO, Tool.HIGHLIGHTER))
        assertEquals(Tool.LASSO, tool(StylusShortcut.LASSO, Tool.ERASER))
    }

    @Test fun onlyInkToolsAreRememberedAsThePreviousTool() {
        assertTrue(StylusShortcuts.isDrawingTool(Tool.PEN))
        assertTrue(StylusShortcuts.isDrawingTool(Tool.ELLIPSE))
        assertFalse(StylusShortcuts.isDrawingTool(Tool.LASSO))
        assertFalse(StylusShortcuts.isDrawingTool(Tool.HAND))
        assertFalse(StylusShortcuts.isDrawingTool(Tool.ERASER))
    }

    @Test fun paletteUndoAndDisabledMapToTheirOwnEffects() {
        assertEquals(StylusShortcutEffect.OpenPalette, StylusShortcuts.effect(StylusShortcut.COLOR_PALETTE, Tool.PEN, Tool.PEN))
        assertEquals(StylusShortcutEffect.Undo, StylusShortcuts.effect(StylusShortcut.UNDO, Tool.PEN, Tool.PEN))
        assertEquals(StylusShortcutEffect.None, StylusShortcuts.effect(StylusShortcut.DISABLED, Tool.PEN, Tool.PEN))
    }

    @Test fun storedPreferenceFallsBackToThePenEraserSwap() {
        assertEquals(StylusShortcut.PEN_ERASER, StylusShortcut.of(null))
        assertEquals(StylusShortcut.PEN_ERASER, StylusShortcut.of("NOT_A_SHORTCUT"))
        assertEquals(StylusShortcut.UNDO, StylusShortcut.of("UNDO"))
        assertEquals(StylusShortcut.LASSO, StylusShortcut.of("LASSO"))
    }
}
