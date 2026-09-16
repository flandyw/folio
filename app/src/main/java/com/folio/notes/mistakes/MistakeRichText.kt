package com.folio.notes.mistakes

/**
 * Offline Markdown-lite + LaTeX renderer for ExamTrack content.
 *
 * ExamTrack stores question / correction / explanation as Markdown with LaTeX math
 * ($…$, $$…$$, \(…\), \[…\]). There is no WebView here on purpose: review must work
 * in airplane mode, must never drop content on malformed input, and must stay unit
 * testable on the JVM. [RichTextParser] is pure Kotlin with no Compose dependency;
 * the composables below only render what it produces.
 *
 * Supported Markdown: **bold**, *italic*, `code`, ~~strike~~, # headings, - bullets,
 * 1. numbered lists, > quotes, ``` fences, --- dividers, line breaks.
 * Supported LaTeX: everything [LatexParser] understands — fractions and binomials, radicals,
 * scripts with the correct limits placement, the Greek alphabet, the operator/relation/arrow
 * tables, accents, `\left…\right`, matrices and cases, text and font commands, colours and
 * spacing. Unknown commands degrade to their own name instead of vanishing, so content is
 * never lost.
 */

// ---- Inline + block model (pure, no Android types) ----------------------------------------------

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
                isDisplayMathFence(trimmed) && trimmed.length > 4 -> {
                    // Single-line $$…$$.
                    blocks += RichBlock.DisplayMath(trimmed.removePrefix("$$").removeSuffix("$$").trim())
                    i++
                }
                trimmed == "$$" -> {
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && lines[i].trim() != "$$") { buf.appendLine(lines[i]); i++ }
                    if (i < lines.size) i++
                    blocks += RichBlock.DisplayMath(buf.toString().trim())
                }
                trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4 -> {
                    blocks += RichBlock.DisplayMath(trimmed.removePrefix("\\[").removeSuffix("\\]").trim())
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
                        if (t.isEmpty() || t.startsWith("```") || t == "$$" || isDisplayMathFence(t) ||
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

    private fun isDisplayMathFence(t: String) = t.startsWith("$$") && t.endsWith("$$")
    private fun isBullet(t: String) = (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• "))
    private fun isNumbered(t: String): Boolean {
        val dot = t.indexOf(". ")
        if (dot !in 1..3) return false
        return t.substring(0, dot).all { it.isDigit() }
    }

    /** Split display math out first so Markdown never eats $…$ contents. */
    fun parseInlines(source: String): List<RichInline> {
        if (source.isEmpty()) return emptyList()
        val out = mutableListOf<RichInline>()
        var i = 0
        var text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { out.addAll(parseMarkdownSpans(text.toString())); text = StringBuilder() } }
        while (i < source.length) {
            when {
                source.startsWith("$$", i) -> {
                    val end = source.indexOf("$$", i + 2)
                    if (end == -1) { text.append(source.substring(i)); break }
                    flush()
                    out += RichInline.Math(source.substring(i + 2, end).trim(), display = true)
                    i = end + 2
                }
                source.startsWith("\\[", i) -> {
                    val end = source.indexOf("\\]", i + 2)
                    if (end == -1) { text.append(source.substring(i)); break }
                    flush()
                    out += RichInline.Math(source.substring(i + 2, end).trim(), display = true)
                    i = end + 2
                }
                source.startsWith("\\(", i) -> {
                    val end = source.indexOf("\\)", i + 2)
                    if (end == -1) { text.append(source.substring(i)); break }
                    flush()
                    out += RichInline.Math(source.substring(i + 2, end).trim(), display = false)
                    i = end + 2
                }
                source[i] == '$' && (i == 0 || source[i - 1] != '\\') -> {
                    val end = findClosingDollar(source, i + 1)
                    if (end == -1) { text.append(source[i]); i++ }
                    else {
                        flush()
                        out += RichInline.Math(source.substring(i + 1, end).trim(), display = false)
                        i = end + 1
                    }
                }
                source[i] == '\n' -> { flush(); out += RichInline.Break; i++ }
                else -> { text.append(source[i]); i++ }
            }
        }
        flush()
        return out
    }

    private fun findClosingDollar(s: String, from: Int): Int {
        var j = from
        // Opening $ must be followed by a non-space to start math (avoids "$ 5" / currency noise).
        if (j >= s.length || s[j].isWhitespace()) return -1
        while (j < s.length) {
            if (s[j] == '$' && s[j - 1] != '\\') {
                // A $$ opener inside inline math ends the search; treat as unclosed.
                if (j + 1 < s.length && s[j + 1] == '$') return -1
                // Closing $ must be preceded by a non-space ($5 stays text without a real closer).
                if (!s[j - 1].isWhitespace()) return j
                return -1
            }
            if (s[j] == '\n' && j + 1 < s.length && s[j + 1] == '\n') return -1
            j++
        }
        return -1
    }

    /** Bold / italic / code / strike on text that contains no math. */
    private fun parseMarkdownSpans(source: String): List<RichInline> {
        val out = mutableListOf<RichInline>()
        var i = 0
        fun push(text: String, bold: Boolean = false, italic: Boolean = false, code: Boolean = false, strike: Boolean = false) {
            if (text.isEmpty()) return
            // Keep escaped dollars readable: "\$5" renders as "$5" and never starts math.
            val unescaped = if (!code) text.replace("\\$", "$") else text
            out += RichInline.Run(unescaped, bold, italic, code, strike)
        }
        while (i < source.length) {
            when {
                source.startsWith("```", i) || (source[i] == '`') -> {
                    val fence = if (source.startsWith("```", i)) "```" else "`"
                    val end = source.indexOf(fence, i + fence.length)
                    if (end == -1) { push(source.substring(i)); break }
                    push(source.substring(i + fence.length, end), code = true)
                    i = end + fence.length
                }
                source.startsWith("**", i) || source.startsWith("__", i) -> {
                    val mark = source.substring(i, i + 2)
                    val end = source.indexOf(mark, i + 2)
                    if (end == -1) { push(source[i].toString()); i++ }
                    else {
                        val inner = source.substring(i + 2, end)
                        if (inner.isBlank()) { push(source.substring(i, end + 2)); i = end + 2 }
                        else {
                            // Allow italic inside bold.
                            parseMarkdownSpans(inner).forEach {
                                if (it is RichInline.Run) out += it.copy(bold = true) else out += it
                            }
                            i = end + 2
                        }
                    }
                }
                source.startsWith("~~", i) -> {
                    val end = source.indexOf("~~", i + 2)
                    if (end == -1) { push(source[i].toString()); i++ }
                    else { push(source.substring(i + 2, end), strike = true); i = end + 2 }
                }
                source[i] == '*' || source[i] == '_' -> {
                    val mark = source[i]
                    // Avoid treating a_b or snake_case as emphasis.
                    val prevIsWord = i > 0 && (source[i - 1].isLetterOrDigit())
                    var end = -1
                    var j = i + 1
                    while (j < source.length) {
                        if (source[j] == mark && source[j - 1] != '\\' && source[j - 1] != ' ') { end = j; break }
                        j++
                    }
                    if (end == -1 || (prevIsWord && mark == '_')) { push(mark.toString()); i++ }
                    else {
                        val inner = source.substring(i + 1, end)
                        if (inner.isBlank()) { push(source.substring(i, end + 1)); i = end + 1 }
                        else { push(inner, italic = true); i = end + 1 }
                    }
                }
                else -> {
                    var j = i
                    while (j < source.length && source[j] != '`' && source[j] != '*' &&
                        source[j] != '_' && !source.startsWith("~~", j)
                    ) j++
                    push(source.substring(i, j))
                    i = j
                }
            }
        }
        return out
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
                    is RichInline.Math -> sb.append(mathPlainText(it.latex))
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
                is RichBlock.DisplayMath -> sb.append(mathPlainText(b.latex))
                RichBlock.Divider -> Unit
            }
            sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= maxLength) it else it.take(maxLength - 1).trimEnd() + "…"
        }
    }

    /** Never throws: a formula the parser chokes on still contributes its raw source. */
    private fun mathPlainText(latex: String): String =
        runCatching { LatexParser.plainText(latex) }.getOrDefault(latex.trim())

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
