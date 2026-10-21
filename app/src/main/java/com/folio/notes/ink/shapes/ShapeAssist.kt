package com.folio.notes.ink.shapes

/** Preferences and cleanup rules shared by live drawing, practice and lasso cleanup. */
internal data class ShapeAssistOptions(
    val lines: Boolean = true,
    val arrows: Boolean = true,
    val closed: Boolean = true,
    val perfect: Boolean = false,
    val regular: Boolean = false,
    val highlighter: Boolean = true,
    val curves: Boolean = true,
) {
    fun encode() = listOf(lines, arrows, closed, perfect, regular, highlighter, curves).joinToString(",") { if (it) "1" else "0" }

    /** Whether the family a recognised shape belongs to is switched on. */
    fun allows(kind: ShapeKind) = when {
        kind == ShapeKind.LINE -> lines
        kind == ShapeKind.ARROW -> arrows
        kind.curve -> curves
        else -> closed
    }

    companion object {
        fun decode(value: String?): ShapeAssistOptions {
            val parts = value?.split(',').orEmpty()
            fun flag(index: Int, default: Boolean) = when (parts.getOrNull(index)) { "1" -> true; "0" -> false; else -> default }
            return ShapeAssistOptions(flag(0, true), flag(1, true), flag(2, true), flag(3, false), flag(4, false), flag(5, true), flag(6, true))
        }
    }
}
