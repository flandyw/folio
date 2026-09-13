package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class InkColorTests {
    @Test fun quickRowAlwaysFillsEverySlot() {
        // Short rows are padded from the exam defaults so the toolbar keeps five swatches.
        val padded = InkColors.quickRow(listOf(0x11223344, 0x22334455))
        assertEquals(InkColors.SLOT_COUNT, padded.size)
        assertEquals(listOf(0x11223344, 0x22334455), padded.take(2))
        assertEquals(InkColors.ExamColors.take(InkColors.SLOT_COUNT).drop(2), padded.drop(2))
        // Long rows are trimmed to the slot count.
        assertEquals(InkColors.SLOT_COUNT, InkColors.quickRow(List(9) { it }).size)
        assertEquals(InkColors.ExamColors.take(InkColors.SLOT_COUNT), InkColors.defaultQuick(InkColors.INK_GROUP))
        // A group can supply its own fallback so a short highlighter row never borrows ink colours.
        assertEquals(listOf(7, 7, 7, 7, 7), InkColors.quickRow(listOf(7), List(5) { 7 }))
    }

    @Test fun highlighterKeepsItsOwnRowSeparateFromThePen() {
        assertEquals(InkColors.HIGHLIGHTER_GROUP, InkColors.groupOf(Tool.HIGHLIGHTER))
        assertEquals(InkColors.INK_GROUP, InkColors.groupOf(Tool.PEN))
        assertEquals(InkColors.INK_GROUP, InkColors.groupOf(Tool.LINE))
        assertEquals(InkColors.INK_GROUP, InkColors.groupOf(Tool.RECTANGLE))
        assertEquals(InkColors.INK_GROUP, InkColors.groupOf(Tool.ELLIPSE))
        assertEquals(InkColors.groups, listOf(InkColors.INK_GROUP, InkColors.HIGHLIGHTER_GROUP))
        val highlighter = InkColors.defaultQuick(InkColors.HIGHLIGHTER_GROUP)
        assertEquals(InkColors.SLOT_COUNT, highlighter.size)
        assertEquals(InkColors.highlighterPalette.colors.take(InkColors.SLOT_COUNT), highlighter)
        assertNotEquals(InkColors.defaultQuick(InkColors.INK_GROUP), highlighter)
        assertEquals(InkColors.highlighterPalette, InkColors.paletteFor(InkColors.HIGHLIGHTER_GROUP))
        assertEquals(InkColors.defaultPalette, InkColors.paletteFor(InkColors.INK_GROUP))
        InkColors.groups.forEach { assertEquals(InkColors.SLOT_COUNT, InkColors.defaultQuick(it).size) }
    }

    @Test fun everyBuiltInPaletteFillsTheQuickRow() {
        assertEquals(InkColors.palettes.size, InkColors.palettes.map { it.name }.distinct().size)
        InkColors.palettes.forEach { palette ->
            assertTrue(palette.name.isNotBlank())
            assertTrue("${palette.name} is too short", palette.colors.size >= InkColors.SLOT_COUNT)
            assertEquals(InkColors.SLOT_COUNT, InkColors.quickRow(palette.colors).size)
        }
    }

    @Test fun quickColorsSurviveSaveAndReload() {
        val row = InkColors.quickRow(listOf(-1, 0x1A1C1A, 0x2E5AAC, 0xC0392B, 0x7A3BA6))
        assertEquals(row, InkColors.decodeColors(InkColors.encodeColors(row)))
    }

    @Test fun unusableStoredColorsFallBackToNull() {
        assertNull(InkColors.decodeColors(null))
        assertNull(InkColors.decodeColors(""))
        assertNull(InkColors.decodeColors("not json at all"))
        assertNull(InkColors.decodeColors("[]"))
    }

    @Test fun presetsSurviveSaveAndReload() {
        val presets = listOf(ColorPreset("Exam", InkColors.ExamColors), ColorPreset("Blue ink", listOf(1, 2, 3, 4, 5)))
        assertEquals(presets, InkColors.decodePresets(InkColors.encodePresets(presets)))
        assertTrue(InkColors.decodePresets(null).isEmpty())
        assertTrue(InkColors.decodePresets("{").isEmpty())
    }

    @Test fun malformedPresetsAreSkippedInsteadOfLosingTheRest() {
        val stored = """[{"name":"","colors":[1]},{"name":"Ok","colors":[]},{"name":"Good","colors":[1,2]}]"""
        assertEquals(listOf(ColorPreset("Good", listOf(1, 2))), InkColors.decodePresets(stored))
    }

    @Test fun presetListIsCapped() {
        val many = (1..InkColors.MAX_PRESETS + 5).map { ColorPreset("P$it", listOf(it)) }
        assertEquals(InkColors.MAX_PRESETS, InkColors.decodePresets(InkColors.encodePresets(many)).size)
    }

    @Test fun savingAPresetReplacesTheOneWithTheSameName() {
        val first = ColorPreset("My mix", listOf(1, 2, 3, 4, 5))
        val replacement = ColorPreset("my MIX", listOf(9, 8, 7, 6, 5))
        val updated = InkColors.upsertPreset(listOf(first), replacement)
        assertEquals(1, updated.size)
        assertEquals(replacement.colors, updated.first().colors)
        assertTrue(InkColors.removePreset(updated, "MY mix").isEmpty())
    }

    @Test fun presetNamesAreTrimmedAndBounded() {
        assertEquals("Revision", InkColors.normalizedPresetName("  Revision  "))
        assertEquals(InkColors.MAX_PRESET_NAME, InkColors.normalizedPresetName("x".repeat(60)).length)
        assertEquals("", InkColors.normalizedPresetName("   "))
    }

    @Test fun contrastAndHexMatchTheStoredColor() {
        assertEquals("303431", InkColors.hex(0xFF303431.toInt()))
        assertEquals("FFFFFF", InkColors.hex(0xFFFFFFFF.toInt()))
        assertTrue(InkColors.isLight(0xFFFFFFFF.toInt()))
        assertFalse(InkColors.isLight(0xFF1A1C1A.toInt()))
        // The check mark switches to black only for genuinely light swatches.
        assertTrue(InkColors.isLight(0xFFF9AB00.toInt()))
        assertFalse(InkColors.isLight(0xFF007AFF.toInt()))
    }
}
