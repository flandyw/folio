package com.folio.notes.editor

import org.junit.Assert.*
import org.junit.Test

class EditorCommandSearchTest {
    private val undo = EditorCommand("Undo", "Ctrl + Z") {}
    private val paste = EditorCommand("Paste selection", "Ctrl + V", false, "Copy something first") {}
    private val commands = listOf(undo, paste)

    @Test fun compactAndSpacedShortcutsFindTheSameAction() {
        listOf("ctrl+z", "CTRL + Z", " Ctrl  +  Z ", "undo ctrl z").forEach {
            assertEquals(it, listOf(undo), matchingEditorCommands(commands, it))
        }
    }

    @Test fun unavailableActionsCanBeFoundByTheirExplanationButStayDisabled() {
        val result = matchingEditorCommands(commands, "first copy")
        assertEquals(listOf(paste), result)
        assertFalse(result.single().enabled)
    }

    @Test fun allTermsMustMatchAndBlankSearchPreservesOrder() {
        assertTrue(matchingEditorCommands(commands, "undo paste").isEmpty())
        assertEquals(commands, matchingEditorCommands(commands, " \t "))
    }

    @Test fun filteringNeverExecutesAnAction() {
        var called = false
        val command = EditorCommand("Delete") { called = true }
        assertEquals(listOf(command), matchingEditorCommands(listOf(command), "delete"))
        assertFalse(called)
    }
}
