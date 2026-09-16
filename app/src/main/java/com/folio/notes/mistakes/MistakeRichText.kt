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
 * Supported LaTeX: \frac, \sqrt, ^ _ superscripts/subscripts, \text, Greek letters,
 * common operators/functions (\times, \le, \sin…). Unknown commands degrade to their
 * name instead of vanishing, so content is never lost.
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

// ---- Math AST (pure) ---------------------------------------------------------------------------

sealed interface MathNode {
    data class Text(val value: String) : MathNode
    data class Sym(val value: String) : MathNode
    data class Func(val name: String) : MathNode
    data class Frac(val num: List<MathNode>, val den: List<MathNode>) : MathNode
    data class Sqrt(val body: List<MathNode>, val index: List<MathNode>? = null) : MathNode
    data class SupSub(val base: List<MathNode>?, val sup: List<MathNode>?, val sub: List<MathNode>?) : MathNode
    data class Group(val children: List<MathNode>) : MathNode
    data object ThinSpace : MathNode
    data object QuadSpace : MathNode
    data object LineBreak : MathNode
}

object LatexSymbols {
    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "varpi" to "π", "rho" to "ρ", "sigma" to "σ",
        "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ",
        "Psi" to "Ψ", "Omega" to "Ω"
    )
    private val ops = mapOf(
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "sim" to "∼",
        "simeq" to "≃", "propto" to "∝", "infty" to "∞", "partial" to "∂",
        "nabla" to "∇", "forall" to "∀", "exists" to "∃", "in" to "∈",
        "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆",
        "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
        "vee" to "∨", "wedge" to "∧", "neg" to "¬", "lnot" to "¬",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
        "mapsto" to "↦", "dots" to "…", "ldots" to "…", "cdots" to "⋯",
        "equiv" to "≡", "cong" to "≅", "perp" to "⊥", "parallel" to "∥",
        "angle" to "∠", "degree" to "°", "prime" to "′", "surd" to "√",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
        "sqrt" to "√", "aleph" to "ℵ", "hbar" to "ℏ", "ell" to "ℓ",
        "Re" to "ℜ", "Im" to "ℑ", "checkmark" to "✓", "circ" to "∘",
        "bullet" to "•", "star" to "★", "dagger" to "†", "ddagger" to "‡"
    )
    val functions = setOf("sin", "cos", "tan", "sec", "csc", "cot", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "log", "ln", "lg", "exp", "det", "dim", "gcd", "hom", "ker",
        "max", "min", "sup", "inf", "lim", "limsup", "liminf", "arg", "deg", "Pr")

    fun command(name: String): String? = greek[name] ?: ops[name]
}

object MathParser {
    fun parse(latex: String): List<MathNode> = Parser(latex).parseSequence(null).nodes

    fun isComplex(nodes: List<MathNode>): Boolean = nodes.any {
        when (it) {
            is MathNode.Frac, is MathNode.Sqrt, is MathNode.SupSub -> true
            is MathNode.Group -> isComplex(it.children)
            else -> false
        }
    }

    /** Lossy but readable single-line fallback, used inside cards and semantics. */
    fun toUnicode(nodes: List<MathNode>): String = buildString {
        fun emit(list: List<MathNode>) {
            list.forEach { n ->
                when (n) {
                    is MathNode.Text -> append(n.value)
                    is MathNode.Sym -> append(n.value)
                    is MathNode.Func -> { append(n.name); append(" ") }
                    is MathNode.Frac -> { append("("); emit(n.num); append(")/("); emit(n.den); append(")") }
                    is MathNode.Sqrt -> { append("√("); emit(n.body); append(")") }
                    is MathNode.SupSub -> {
                        n.base?.let { emit(it) }
                        n.sup?.let { append("^("); emit(it); append(")") }
                        n.sub?.let { append("_("); emit(it); append(")") }
                    }
                    is MathNode.Group -> emit(n.children)
                    MathNode.ThinSpace -> append(" ")
                    MathNode.QuadSpace -> append("  ")
                    MathNode.LineBreak -> append(" ")
                }
            }
        }
        emit(nodes)
    }

    private class Parser(val s: String) {
        var i = 0
        data class Seq(val nodes: MutableList<MathNode> = mutableListOf())

        fun parseSequence(stop: Char?): Seq {
            val out = Seq()
            var text = StringBuilder()
            fun flush() { if (text.isNotEmpty()) { out.nodes += MathNode.Text(text.toString()); text = StringBuilder() } }
            while (i < s.length) {
                val c = s[i]
                if (stop != null && c == stop) break
                when {
                    c == '\\' -> {
                        flush()
                        parseCommand(out)
                    }
                    c == '{' -> {
                        flush(); i++
                        val inner = parseSequence('}')
                        if (i < s.length && s[i] == '}') i++
                        val group = MathNode.Group(inner.nodes)
                        attachSupSub(out, listOf(group))
                    }
                    c == '}' || c == ')' || c == ']' -> break
                    c == '^' || c == '_' -> {
                        flush()
                        val isSup = c == '^'
                        i++
                        skipSpaces()
                        val arg = readScriptArg()
                        val prev = out.nodes.removeLastOrNull()
                        val base = prev?.let { listOf(it) }
                        val lastSupSub = null // merged below via SupSub node
                        if (prev is MathNode.SupSub && ((isSup && prev.sup == null) || (!isSup && prev.sub == null))) {
                            out.nodes += if (isSup) prev.copy(sup = arg) else prev.copy(sub = arg)
                        } else {
                            // Peek a following _/^ to merge x^a_b into one node.
                            var sup: List<MathNode>? = if (isSup) arg else null
                            var sub: List<MathNode>? = if (!isSup) arg else null
                            val save = i
                            skipSpaces()
                            if (i < s.length && ((isSup && s[i] == '_') || (!isSup && s[i] == '^'))) {
                                val secondSup = s[i] == '^'
                                i++; skipSpaces()
                                val arg2 = readScriptArg()
                                if (secondSup) sup = arg2 else sub = arg2
                            } else i = save
                            out.nodes += MathNode.SupSub(base, sup, sub)
                            @Suppress("UNUSED_EXPRESSION") lastSupSub
                        }
                    }
                    c == '&' -> { flush(); out.nodes += MathNode.ThinSpace; i++ }
                    c == '\n' -> { flush(); out.nodes += MathNode.LineBreak; i++ }
                    c == ' ' || c == '\t' -> { text.append(' '); i++ }
                    else -> { text.append(c); i++ }
                }
            }
            flush()
            return out
        }

        private fun skipSpaces() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n')) i++ }

        private fun readScriptArg(): List<MathNode> {
            skipSpaces()
            if (i >= s.length) return listOf(MathNode.Text(""))
            return when {
                s[i] == '{' -> {
                    i++
                    val inner = parseSequence('}')
                    if (i < s.length && s[i] == '}') i++
                    inner.nodes.ifEmpty { listOf(MathNode.Text("")) }
                }
                s[i] == '\\' -> {
                    val seq = Seq()
                    parseCommand(seq)
                    seq.nodes.ifEmpty { listOf(MathNode.Text("")) }
                }
                else -> listOf(MathNode.Text(s[i++].toString()))
            }
        }

        private fun readGroup(): List<MathNode> {
            skipSpaces()
            if (i < s.length && s[i] == '{') {
                i++
                val inner = parseSequence('}')
                if (i < s.length && s[i] == '}') i++
                return inner.nodes
            }
            return readScriptArg()
        }

        private fun readOptional(): List<MathNode>? {
            skipSpaces()
            if (i < s.length && s[i] == '[') {
                i++
                val buf = StringBuilder()
                while (i < s.length && s[i] != ']') buf.append(s[i++])
                if (i < s.length) i++
                return Parser(buf.toString()).parseSequence(null).nodes
            }
            return null
        }

        private fun attachSupSub(out: Seq, base: List<MathNode>) {
            // Attach a trailing ^/_ directly to this base when present (e.g. x^{2}).
            val save = i
            skipSpaces()
            if (i < s.length && (s[i] == '^' || s[i] == '_')) {
                val isSup = s[i] == '^'
                i++; skipSpaces()
                val arg = readScriptArg()
                var sup: List<MathNode>? = if (isSup) arg else null
                var sub: List<MathNode>? = if (!isSup) arg else null
                val save2 = i
                skipSpaces()
                if (i < s.length && ((isSup && s[i] == '_') || (!isSup && s[i] == '^'))) {
                    val secondSup = s[i] == '^'
                    i++; skipSpaces()
                    val arg2 = readScriptArg()
                    if (secondSup) sup = arg2 else sub = arg2
                } else i = save2
                // Merge multiple bases (e.g. \alpha^{2}) into one group base.
                out.nodes += MathNode.SupSub(if (base.size == 1) base else listOf(MathNode.Group(base)), sup, sub)
            } else {
                i = save
                out.nodes.addAll(base)
            }
        }

        private fun parseCommand(out: Seq) {
            // s[i] == '\\'
            i++
            if (i >= s.length) { out.nodes += MathNode.Text("\\"); return }
            val c = s[i]
            when {
                c == '\\' -> { out.nodes += MathNode.LineBreak; i++ }
                c in listOf('{', '}', '$', '&', '#', '%', '_', ' ', ',', ';', ':', '!', '/', '|', '(', ')', '[', ']') -> {
                    when (c) {
                        ',' -> out.nodes += MathNode.ThinSpace
                        ';', ':' -> out.nodes += MathNode.ThinSpace
                        '!' -> Unit // negative thin space: render as nothing
                        ' ' -> out.nodes += MathNode.ThinSpace
                        else -> out.nodes += MathNode.Text(c.toString())
                    }
                    i++
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetter()) i++
                    // A trailing * (e.g. \tag*) is part of the command for our purposes: ignore it.
                    if (i < s.length && s[i] == '*') i++
                    val name = s.substring(start, i).trimEnd('*')
                    // Consume one trailing space after a command, per TeX.
                    if (i < s.length && s[i] == ' ') i++
                    when (name) {
                        "frac", "dfrac", "tfrac", "cfrac" -> {
                            val num = readGroup()
                            val den = readGroup()
                            attachSupSub(out, listOf(MathNode.Frac(num, den)))
                        }
                        "sqrt" -> {
                            val idx = readOptional()
                            val body = readGroup()
                            attachSupSub(out, listOf(MathNode.Sqrt(body, idx)))
                        }
                        "text", "mathrm", "textup", "textrm", "mathbf", "mathit", "operatorname" -> {
                            val body = readGroup()
                            // Render upright: collapse to plain text.
                            attachSupSub(out, listOf(MathNode.Text(MathParser.toUnicode(body))))
                        }
                        "left" -> {
                            skipSpaces()
                            val d = if (i < s.length) s[i++].toString() else ""
                            if (d != ".") out.nodes += MathNode.Text(d)
                        }
                        "right" -> {
                            skipSpaces()
                            val d = if (i < s.length) s[i++].toString() else ""
                            if (d != ".") out.nodes += MathNode.Text(d)
                        }
                        "quad" -> out.nodes += MathNode.QuadSpace
                        "qquad" -> { out.nodes += MathNode.QuadSpace; out.nodes += MathNode.QuadSpace }
                        "hspace", "vspace" -> { readOptional(); readGroup(); out.nodes += MathNode.ThinSpace }
                        "begin", "end" -> { readGroup() /* environments ignored, inner content parsed normally */ }
                        else -> {
                            LatexSymbols.command(name)?.let {
                                attachSupSub(out, listOf(MathNode.Sym(it)))
                                return
                            }
                            if (name in LatexSymbols.functions) {
                                out.nodes += MathNode.Func(name)
                                return
                            }
                            // Unknown command: keep it readable rather than dropping content.
                            out.nodes += MathNode.Text(name)
                        }
                    }
                }
                else -> { out.nodes += MathNode.Text(c.toString()); i++ }
            }
        }
    }
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
                    is RichInline.Math -> sb.append(MathParser.toUnicode(runCatching { MathParser.parse(it.latex) }.getOrDefault(emptyList())))
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
                is RichBlock.DisplayMath -> sb.append(MathParser.toUnicode(runCatching { MathParser.parse(b.latex) }.getOrDefault(emptyList())))
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
