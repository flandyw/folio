package com.folio.notes.math

/**
 * Pure math-delimiter segmentation for text boxes and sticky notes. Plain text is never reinterpreted:
 * unlike the mistake-review rich text there is no Markdown here, only `$…$`, `$$…$$`, `\(…\)` and
 * `\[…\]`. Math is opaque source for the bundled KaTeX; a note without any math is left byte-for-byte alone.
 */
internal object TextMath {
    sealed interface Part {
        data class Text(val text: String) : Part
        /** [source] is the text as typed, delimiters included: what shows until the formula has rendered. */
        data class Formula(val source: String, val latex: String, val display: Boolean) : Part
    }

    /** Cheap pre-check so ordinary notes skip parsing on every frame. */
    fun mayHaveMath(text: String): Boolean = text.indexOf('$') >= 0 || text.contains("\\(") || text.contains("\\[")

    fun hasMath(text: String): Boolean = mayHaveMath(text) && split(text).any { it is Part.Formula }

    /** The text with math as separate parts, or one [Part.Text] with the original string when it has none. */
    fun split(text: String): List<Part> {
        if (!mayHaveMath(text)) return listOf(Part.Text(text))
        val parts = mutableListOf<Part>()
        val run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { parts += Part.Text(run.toString()); run.clear() } }
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] == '$') { run.append('$'); i += 2; continue }
            val open = when {
                text.startsWith("$$", i) -> "$$"
                text.startsWith("\\[", i) -> "\\["
                text.startsWith("\\(", i) -> "\\("
                text[i] == '$' -> "$"
                else -> null
            }
            if (open != null) {
                val close = when (open) { "\\[" -> "\\]"; "\\(" -> "\\)"; else -> open }
                val start = i + open.length
                val end = if (open == "$") closingDollar(text, start) else closing(text, close, start)
                if (end > start && text.substring(start, end).isNotBlank()) {
                    flush()
                    parts += Part.Formula(text.substring(i, end + close.length), text.substring(start, end),
                        display = open == "$$" || open == "\\[")
                    i = end + close.length
                    continue
                }
            }
            run.append(text[i]); i++
        }
        flush()
        return if (parts.none { it is Part.Formula }) listOf(Part.Text(text)) else parts
    }

    private fun escaped(text: String, index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && text[i--] == '\\') backslashes++
        return backslashes % 2 == 1
    }

    private fun closing(text: String, delimiter: String, from: Int): Int {
        var end = text.indexOf(delimiter, from)
        while (end >= 0 && escaped(text, end)) end = text.indexOf(delimiter, end + delimiter.length)
        return end
    }

    /** `$5 and $6` is money, not math: the formula may not start or end on a space, nor span a blank line. */
    private fun closingDollar(text: String, from: Int): Int {
        if (from >= text.length || text[from].isWhitespace()) return -1
        val end = closing(text, "$", from)
        if (end < 0 || text[end - 1].isWhitespace() || text.startsWith("$$", end)) return -1
        if (text.substring(from, end).contains("\n\n")) return -1
        return end
    }
}
