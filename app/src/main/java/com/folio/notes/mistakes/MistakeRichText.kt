package com.folio.notes.mistakes

/**
 * Pure Markdown-lite and math-delimiter segmentation. Math is opaque source for bundled KaTeX;
 * this layer knows no TeX commands or layout rules. Code spans/fences remain literal.
 */
sealed interface RichInline {
    data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false,
        val code: Boolean = false, val strike: Boolean = false) : RichInline
    data class Math(val latex: String, val display: Boolean = false) : RichInline
    data object Break : RichInline
}

sealed interface RichBlock {
    data class Para(val inlines: List<RichInline>) : RichBlock
    data class Heading(val level: Int, val inlines: List<RichInline>) : RichBlock
    data class Bullets(val items: List<List<RichInline>>) : RichBlock
    data class Numbers(val items: List<List<RichInline>>) : RichBlock
    data class Quote(val inlines: List<RichInline>) : RichBlock
    data class Code(val code: String) : RichBlock
    data class DisplayMath(val latex: String) : RichBlock
    data object Divider : RichBlock
}

object RichTextParser {
    fun parse(source: String): List<RichBlock> {
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()
        val lines = text.split('\n')
        val blocks = mutableListOf<RichBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> i++
                trimmed.startsWith("```") -> {
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        buf.appendLine(lines[i]); i++
                    }
                    if (i < lines.size) i++ // closing fence
                    blocks += RichBlock.Code(buf.toString().trimEnd('\n'))
                }
                trimmed == "$$" || trimmed == "\\[" -> {
                    val close = if (trimmed == "$$") "$$" else "\\]"
                    val end = (i + 1 until lines.size).firstOrNull { lines[it].trim() == close }
                    if (end == null) {
                        blocks += RichBlock.Para(listOf(RichInline.Run(lines.drop(i).joinToString("\n"))))
                        i = lines.size
                    } else {
                        // Preserve every character between delimiters, including edge newlines.
                        val body = lines.subList(i, end + 1).joinToString("\n")
                        blocks += RichBlock.DisplayMath(body.substringAfter(trimmed).substringBeforeLast(close))
                        i = end + 1
                    }
                }
                isDisplayMathFence(trimmed) -> {
                    blocks += RichBlock.DisplayMath(trimmed.substring(2, trimmed.length - 2))
                    i++
                }
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> { blocks += RichBlock.Divider; i++ }
                trimmed.startsWith("#") && trimmed.dropWhile { it == '#' }.startsWith(" ") -> {
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                    blocks += RichBlock.Heading(level, parseInlines(trimmed.drop(level).trim()))
                    i++
                }
                trimmed.startsWith(">") -> {
                    val buf = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        buf += lines[i].trim().removePrefix(">").trimStart()
                        i++
                    }
                    blocks += RichBlock.Quote(parseInlines(buf.joinToString("\n")))
                }
                isBullet(trimmed) -> {
                    val items = mutableListOf<List<RichInline>>()
                    while (i < lines.size && isBullet(lines[i].trim())) {
                        items += parseInlines(lines[i].trim().drop(2).trim())
                        i++
                    }
                    blocks += RichBlock.Bullets(items)
                }
                isNumbered(trimmed) -> {
                    val items = mutableListOf<List<RichInline>>()
                    while (i < lines.size && isNumbered(lines[i].trim())) {
                        items += parseInlines(lines[i].trim().substringAfter(' ').trim())
                        i++
                    }
                    blocks += RichBlock.Numbers(items)
                }
                else -> {
                    // Paragraph: join until a blank line or another block starter.
                    val buf = mutableListOf<String>()
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        if (t.isEmpty() || t.startsWith("```") || t == "$$" || t == "\\[" || isDisplayMathFence(t) ||
                            (t.startsWith("#") && t.dropWhile { it == '#' }.startsWith(" ")) ||
                            t.startsWith(">") || isBullet(t) || isNumbered(t) ||
                            t == "---" || t == "***" || t == "___"
                        ) break
                        buf += lines[i]
                        i++
                    }
                    blocks += RichBlock.Para(parseInlines(buf.joinToString("\n")))
                }
            }
        }
        return blocks
    }

    private fun isDisplayMathFence(t: String): Boolean {
        if (t.length <= 4) return false
        val close = when {
            t.startsWith("$$") -> "$$"
            t.startsWith("\\[") -> "\\]"
            else -> return false
        }
        return findClosing(t, close, 2) == t.length - 2
    }
    private fun isBullet(t: String) = (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• "))
    private fun isNumbered(t: String): Boolean {
        val dot = t.indexOf(". ")
        if (dot !in 1..3) return false
        return t.substring(0, dot).all { it.isDigit() }
    }

    /** Delimiters only: never inspect, trim or translate the mathematical source. */
    fun parseInlines(source: String): List<RichInline> {
        val out = mutableListOf<RichInline>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) { out += RichInline.Run(text.toString()); text.clear() }
        }
        var i = 0
        while (i < source.length) {
            // Markdown escapes and code take precedence over math delimiters.
            if (source[i] == '\\' && i + 1 < source.length && source[i + 1] in "$\\*_`~") {
                text.append(source[i + 1]); i += 2; continue
            }
            if (source[i] == '`') {
                val fence = source.substring(i).takeWhile { it == '`' }
                val end = source.indexOf(fence, i + fence.length)
                if (end >= 0) {
                    flush(); out += RichInline.Run(source.substring(i + fence.length, end), code = true)
                    i = end + fence.length; continue
                }
            }
            val delimiter = when {
                source.startsWith("$$", i) -> "$$" to "$$"
                source.startsWith("\\[", i) -> "\\[" to "\\]"
                source.startsWith("\\(", i) -> "\\(" to "\\)"
                source[i] == '$' -> "$" to "$"
                else -> null
            }
            if (delimiter != null) {
                val (open, close) = delimiter
                val start = i + open.length
                val end = if (open == "$") findClosingDollar(source, start) else findClosing(source, close, start)
                if (end > start) {
                    flush()
                    out += RichInline.Math(source.substring(start, end), open == "$$" || open == "\\[")
                    i = end + close.length; continue
                }
                // Keep malformed source literal, including its delimiter and Markdown-like characters.
                if (open != "$") { text.append(source.substring(i)); break }
            }
            val mark = when {
                source.startsWith("**", i) -> "**"
                source.startsWith("__", i) -> "__"
                source.startsWith("~~", i) -> "~~"
                source[i] == '*' -> "*"
                source[i] == '_' && (i == 0 || !source[i - 1].isLetterOrDigit()) -> "_"
                else -> null
            }
            if (mark != null) {
                val end = findClosing(source, mark, i + mark.length)
                if (end > i + mark.length) {
                    flush()
                    out += parseInlines(source.substring(i + mark.length, end)).map {
                        if (it is RichInline.Run) it.copy(
                            bold = it.bold || mark == "**" || mark == "__",
                            italic = it.italic || mark == "*" || mark == "_",
                            strike = it.strike || mark == "~~"
                        ) else it
                    }
                    i = end + mark.length; continue
                }
            }
            if (source[i] == '\n') { flush(); out += RichInline.Break } else text.append(source[i])
            i++
        }
        flush()
        return out
    }

    private fun isEscaped(source: String, index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && source[i--] == '\\') backslashes++
        return backslashes % 2 == 1
    }

    private fun findClosing(source: String, delimiter: String, from: Int): Int {
        var end = source.indexOf(delimiter, from)
        while (end >= 0 && isEscaped(source, end)) end = source.indexOf(delimiter, end + delimiter.length)
        return end
    }

    private fun findClosingDollar(source: String, from: Int): Int {
        if (from >= source.length || source[from].isWhitespace()) return -1
        val end = findClosing(source, "$", from)
        if (end < 0 || source[end - 1].isWhitespace() || source.startsWith("$$", end)) return -1
        if (source.substring(from, end).contains("\n\n")) return -1
        return end
    }

    /** Readable one-line fallback for previews, semantics and notifications. */
    fun plainText(source: String, maxLength: Int = 160): String {
        if (source.isBlank()) return ""
        val blocks = runCatching { parse(source) }.getOrDefault(emptyList())
        if (blocks.isEmpty()) return source.trim().take(maxLength)
        val sb = StringBuilder()
        fun inlines(list: List<RichInline>) {
            list.forEach {
                when (it) {
                    is RichInline.Run -> sb.append(it.text)
                    is RichInline.Math -> sb.append(it.latex)
                    RichInline.Break -> sb.append(' ')
                }
            }
        }
        blocks.forEach { b ->
            when (b) {
                is RichBlock.Para -> inlines(b.inlines)
                is RichBlock.Heading -> inlines(b.inlines)
                is RichBlock.Bullets -> b.items.forEach { inlines(it); sb.append(' ') }
                is RichBlock.Numbers -> b.items.forEach { inlines(it); sb.append(' ') }
                is RichBlock.Quote -> inlines(b.inlines)
                is RichBlock.Code -> sb.append(b.code)
                is RichBlock.DisplayMath -> sb.append(b.latex)
                RichBlock.Divider -> Unit
            }
            sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= maxLength) it else it.take(maxLength - 1).trimEnd() + "…"
        }
    }

    fun containsMath(source: String): Boolean {
        if (!source.contains('$') && !source.contains('\\')) return false
        return runCatching { parse(source) }.getOrNull()?.any { block ->
            when (block) {
                is RichBlock.DisplayMath -> true
                is RichBlock.Para -> block.inlines.any { it is RichInline.Math }
                is RichBlock.Heading -> block.inlines.any { it is RichInline.Math }
                is RichBlock.Bullets -> block.items.any { items -> items.any { it is RichInline.Math } }
                is RichBlock.Numbers -> block.items.any { items -> items.any { it is RichInline.Math } }
                is RichBlock.Quote -> block.inlines.any { it is RichInline.Math }
                else -> false
            }
        } == true
    }
}
