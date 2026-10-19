package com.folio.notes.mistakes

import com.folio.notes.NotePage
import com.folio.notes.PageLayers
import com.folio.notes.TextBox

/** A response is ordinary page text, so journals, backups, exports and history all keep it. */
internal object MistakeTypedResponse {
    fun find(page: NotePage, reviewId: String): TextBox? =
        page.texts.firstOrNull { it.id == "mistake-response-$reviewId" }

    fun box(page: NotePage, reviewId: String): TextBox {
        find(page, reviewId)?.let { return it }
        // Keep existing working clear when someone switches to typing halfway through a review.
        val right = maxOf(
            page.strokes.maxOfOrNull { stroke -> stroke.points.maxOfOrNull { it.x } ?: 0f } ?: 0f,
            page.texts.maxOfOrNull { it.x + it.width } ?: 0f,
            page.images.maxOfOrNull { it.x + it.width } ?: 0f,
        )
        return TextBox(id = "mistake-response-$reviewId", x = maxOf(48f, right + 32f), y = 48f,
            width = 640f, size = 26f, layer = PageLayers.initialActive(page.layers))
    }

    fun words(text: String): Int = text.trim().takeIf(String::isNotEmpty)?.split(Regex("\\s+"))?.size ?: 0
}

/** Text plus selection, independent of Compose so the typing bar's edits stay testable. */
internal data class TypedEdit(val text: String, val start: Int, val end: Int = start)

/**
 * One tap on the typing bar. [math] is inserted inside `$…$`; outside it the snippet is
 * [plain] when there is one, otherwise [math] wrapped in `$…$`. [CARET] marks where the
 * caret (or the selected text) goes.
 */
internal data class TypingSnippet(val label: String, val description: String, val math: String, val plain: String? = null)

internal object TypingBar {
    const val CARET = '\u0000'

    val MATHS = TypingSnippet("\$x\$", "Maths", "$CARET")
    val snippets = listOf(
        TypingSnippet("x²", "Power", "^{$CARET}"),
        TypingSnippet("x₂", "Subscript", "_{$CARET}"),
        TypingSnippet("a⁄b", "Fraction", "\\frac{$CARET}{}"),
        TypingSnippet("√", "Square root", "\\sqrt{$CARET}"),
        TypingSnippet("π", "Pi", "\\pi ", "π"),
        TypingSnippet("θ", "Theta", "\\theta ", "θ"),
        TypingSnippet("±", "Plus or minus", "\\pm ", "±"),
        TypingSnippet("×", "Times", "\\times ", "×"),
        TypingSnippet("÷", "Divide", "\\div ", "÷"),
        TypingSnippet("≤", "Less than or equal to", "\\le ", "≤"),
        TypingSnippet("≥", "Greater than or equal to", "\\ge ", "≥"),
        TypingSnippet("≠", "Not equal to", "\\ne ", "≠"),
        TypingSnippet("≈", "Approximately", "\\approx ", "≈"),
        TypingSnippet("→", "Arrow", "\\to ", "→"),
        TypingSnippet("°", "Degrees", "^\\circ ", "°"),
        TypingSnippet("∞", "Infinity", "\\infty ", "∞"),
    )

    /** True when [caret] sits between an opening and closing `$` or `$$` (escaped `\$` ignored). */
    fun insideMath(text: String, caret: Int): Boolean {
        var inline = false
        var display = false
        var i = 0
        val stop = caret.coerceIn(0, text.length)
        while (i < stop) {
            when {
                text[i] == '\\' -> i += 2
                text.startsWith("$$", i) && i + 1 < stop && !inline -> { display = !display; i += 2 }
                text[i] == '$' && !display -> { inline = !inline; i++ }
                else -> i++
            }
        }
        return inline || display
    }

    fun insert(edit: TypedEdit, snippet: TypingSnippet): TypedEdit {
        val start = minOf(edit.start, edit.end).coerceIn(0, edit.text.length)
        val end = maxOf(edit.start, edit.end).coerceIn(0, edit.text.length)
        val selected = edit.text.substring(start, end)
        val inside = insideMath(edit.text, start)
        if (snippet == MATHS && inside) {
            // Inside maths the same key steps out past the closing delimiter.
            val close = closingDollar(edit.text, end) ?: return edit
            return TypedEdit(edit.text, close, close)
        }
        var from = start
        val body = when {
            inside -> snippet.math
            snippet.plain != null -> snippet.plain
            snippet == MATHS -> "\$$CARET\$"
            snippet.math.startsWith("^") || snippet.math.startsWith("_") -> {
                // x² after "x" means x squared: take the word before the caret as the base.
                val base = if (selected.isNotEmpty()) "" else baseBefore(edit.text, start)
                from = start - base.length
                "\$${base.ifEmpty { "$CARET" }}${if (base.isEmpty()) snippet.math.replace("$CARET", "") else snippet.math}\$"
            }
            else -> "\$${snippet.math}\$"
        }
        val marker = body.indexOf(CARET)
        val filled = if (marker < 0) body else body.replaceRange(marker, marker + 1, selected)
        val caret = from + (if (marker < 0) filled.length else marker + selected.length)
        val text = edit.text.substring(0, from) + filled + edit.text.substring(end)
        return TypedEdit(text, caret)
    }

    /** Continues a `- ` or `1. ` list when Enter is pressed; Enter on an empty item ends the list. */
    fun continueList(before: TypedEdit, after: TypedEdit): TypedEdit {
        val at = before.start
        if (before.start != before.end || after.start != after.end || after.start != at + 1 ||
            after.text.length != before.text.length + 1 || after.text.getOrNull(at) != '\n' ||
            after.text.substring(0, at) != before.text.substring(0, at)) return after
        val lineStart = before.text.lastIndexOf('\n', at - 1) + 1
        val line = before.text.substring(lineStart, at)
        val marker = Regex("^(\\s*)([-*•]|(\\d{1,3})[.)]) ").find(line) ?: return after
        if (line.length == marker.value.length) {
            // An empty item: drop its marker instead of starting another.
            val text = before.text.substring(0, lineStart) + before.text.substring(at)
            return TypedEdit(text, lineStart)
        }
        val number = marker.groupValues[3].toIntOrNull()
        val next = if (number == null) marker.value
            else marker.groupValues[1] + (number + 1) + marker.groupValues[2].last() + " "
        val text = after.text.substring(0, at + 1) + next + after.text.substring(at + 1)
        return TypedEdit(text, at + 1 + next.length)
    }

    /** Turns the caret's line into a `- ` item, or back into plain text. */
    fun toggleList(edit: TypedEdit): TypedEdit {
        val at = edit.start.coerceIn(0, edit.text.length)
        val lineStart = edit.text.lastIndexOf('\n', at - 1) + 1
        val existing = Regex("^([-*•]|\\d{1,3}[.)]) ").find(edit.text.substring(lineStart))?.value
        val text = if (existing != null) edit.text.removeRange(lineStart, lineStart + existing.length)
            else edit.text.substring(0, lineStart) + "- " + edit.text.substring(lineStart)
        val shift = if (existing != null) -existing.length else 2
        fun moved(i: Int) = if (i < lineStart) i else maxOf(lineStart, i + shift)
        return TypedEdit(text, moved(edit.start), moved(edit.end))
    }

    private fun baseBefore(text: String, caret: Int): String {
        var i = caret
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == ')')) i--
        return text.substring(i, caret)
    }

    private fun closingDollar(text: String, from: Int): Int? {
        var i = from
        while (i < text.length) {
            when {
                text[i] == '\\' -> i += 2
                text.startsWith("$$", i) -> return i + 2
                text[i] == '$' -> return i + 1
                else -> i++
            }
        }
        return null
    }
}
