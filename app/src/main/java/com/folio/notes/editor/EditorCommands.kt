package com.folio.notes.editor

internal data class EditorCommand(val title: String, val keys: String = "", val enabled: Boolean = true,
    val reason: String = "", val run: () -> Unit)

/** Every word may match the action, its shortcut or the explanation of why it is unavailable. */
internal fun matchingEditorCommands(commands: List<EditorCommand>, query: String): List<EditorCommand> {
    val plus = Regex("\\s*\\+\\s*")
    val words = query.replace(plus, "+").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return commands.filter { command ->
        val text = "${command.title} ${command.keys} ${command.reason}".replace(plus, "+")
        words.all { text.contains(it, ignoreCase = true) }
    }
}
