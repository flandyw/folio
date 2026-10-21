package com.folio.notes.editor

import com.folio.notes.model.Tool

/** User-editable canvas-only letter keys. Duplicates and non-letter bindings are rejected. */
internal object EditorKeyBindings {
    val defaults = linkedMapOf('P' to Tool.PEN, 'E' to Tool.ERASER, 'H' to Tool.HIGHLIGHTER, 'L' to Tool.LASSO, 'V' to Tool.HAND, 'T' to Tool.TEXT)
    fun decode(raw: String?): Map<Char, Tool> {
        if (raw == null) return defaults
        val values = raw.split(',').mapNotNull { token ->
            val parts = token.split(':')
            val letter = parts.getOrNull(0)?.singleOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: return@mapNotNull null
            val tool = defaults.values.find { it.name == parts.getOrNull(1) } ?: return@mapNotNull null
            letter to tool
        }
        if (values.size != defaults.size || values.map { it.first }.distinct().size != values.size || values.map { it.second }.distinct().size != defaults.size) return defaults
        return values.toMap()
    }
    fun encode(values: Map<Char, Tool>): String = values.entries.joinToString(",") { "${it.key}:${it.value.name}" }
}
