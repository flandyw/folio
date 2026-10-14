package com.folio.notes

/** Preferences and cleanup rules shared by live drawing, practice and lasso cleanup. */
internal data class ShapeAssistOptions(
    val lines: Boolean = true,
    val arrows: Boolean = true,
    val closed: Boolean = true,
    val perfect: Boolean = false,
    val regular: Boolean = false,
    val highlighter: Boolean = true,
) {
    fun encode() = listOf(lines, arrows, closed, perfect, regular, highlighter).joinToString(",") { if (it) "1" else "0" }
    companion object {
        fun decode(value: String?): ShapeAssistOptions {
            val parts = value?.split(',').orEmpty()
            fun flag(index: Int, default: Boolean) = when (parts.getOrNull(index)) { "1" -> true; "0" -> false; else -> default }
            return ShapeAssistOptions(flag(0, true), flag(1, true), flag(2, true), flag(3, false), flag(4, false), flag(5, true))
        }
    }
}
