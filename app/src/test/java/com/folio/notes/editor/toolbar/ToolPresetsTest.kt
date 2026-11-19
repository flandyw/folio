package com.folio.notes.editor.toolbar

import com.folio.notes.model.Tool
import org.junit.Assert.*
import org.junit.Test

class ToolPresetsTest {
    private val first = ToolPreset(id = "first", name = "Black", tool = Tool.PEN,
        color = 0xFF000000.toInt(), width = 2f, opacity = 1f)
    private val second = first.copy(id = "second", name = "Blue")
    private val saved = listOf(first, second)

    @Test fun renameOnlyChangesNameAndKeepsPresetAndPinnedOrder() {
        val layout = ToolbarLayout(pinnedPresetIds = listOf(second.id, first.id))
        val renamed = ToolPresets.rename(saved, first.id, "  Fine pen  ")!!
        assertEquals(listOf(first.copy(name = "Fine pen"), second), renamed)
        assertEquals(saved.map { it.id }, renamed.map { it.id })
        assertEquals(listOf("Blue", "Fine pen"), layout.pinnedPresetIds.map { id ->
            renamed.single { it.id == id }.name
        })
        assertEquals("Black", first.name)
    }

    @Test fun emptyNameIsRejected() {
        assertNotNull(ToolPresets.renameError(saved, first.id, ""))
        assertNull(ToolPresets.rename(saved, first.id, ""))
    }

    @Test fun whitespaceNameIsRejected() {
        assertNotNull(ToolPresets.renameError(saved, first.id, " \t\n "))
        assertNull(ToolPresets.rename(saved, first.id, " \t\n "))
    }

    @Test fun duplicateNameIsRejectedIgnoringCaseAndSurroundingWhitespace() {
        assertNotNull(ToolPresets.renameError(saved, first.id, " bLuE "))
        assertNull(ToolPresets.rename(saved, first.id, " bLuE "))
    }

    @Test fun ownNameIsAllowed() {
        assertNull(ToolPresets.renameError(saved, first.id, first.name))
        assertEquals(saved, ToolPresets.rename(saved, first.id, " Black "))
        assertEquals(first.copy(name = "BLACK"), ToolPresets.rename(saved, first.id, "BLACK")!!.first())
    }

    @Test fun missingPresetIsRejected() {
        assertNotNull(ToolPresets.renameError(saved, "missing", "New"))
        assertNull(ToolPresets.rename(saved, "missing", "New"))
        assertNull(ToolPresets.rename(emptyList(), first.id, "New"))
    }
}
