package com.folio.notes.mistakes

/**
 * Tokenizer and recursive-descent parser for the LaTeX maths subset Folio renders.
 *
 * The parser never throws and never silently drops content: anything it does not recognise
 * becomes upright literal text, unbalanced braces, missing arguments or a stray `\end` simply
 * end the current group. That keeps a malformed question readable rather than blank.
 *
 * Grouping, `^`/`_` scripts (with TeX's rule that a script binds to the atom before it),
 * `\left…\right`, the `\begin…\end` environments, infix fractions (`a \over b`) and the
 * style/colour scopes are all handled here; positioning lives in [LatexLayout].
 */
object LatexParser {
    /** Parses [source] into a [LatexNode.Row] holding the top-level atoms. */
    fun parse(source: String): LatexNode = Parser(source).parseRoot()

    /** Readable single-line rendering, used for previews, semantics and notifications. */
    fun toUnicode(node: LatexNode): String = buildString { appendUnicode(node, this) }

    fun toUnicode(nodes: List<LatexNode>): String = buildString { nodes.forEach { appendUnicode(it, this) } }

    /** Convenience: parse then flatten. Blank or malformed input yields an empty string. */
    fun plainText(source: String): String =
        runCatching { toUnicode(parse(source)) }.getOrDefault("").trim()

    // ---- Tokens ---------------------------------------------------------------------------------

    private sealed interface Token {
        data class Command(val name: String) : Token
        // `kotlin.Char` is qualified because this class shadows the simple name inside itself.
        data class Char(val value: kotlin.Char) : Token
        data class Space(val text: String) : Token
        data object Open : Token
        data object Close : Token
        data object Sup : Token
        data object Sub : Token
        data object Align : Token
        data object NewRow : Token
    }

    private fun isCommandLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private fun tokenize(source: String): List<Token> {
        val out = ArrayList<Token>(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' -> {
                    i++
                    if (i >= source.length) {
                        out += Token.Char('\\')
                        break
                    }
                    if (isCommandLetter(source[i])) {
                        val start = i
                        while (i < source.length && isCommandLetter(source[i])) i++
                        var name = source.substring(start, i)
                        // `\operatorname*` / `\tag*` keep the star so the parser can see it.
                        if (i < source.length && source[i] == '*') { name += "*"; i++ }
                        out += Token.Command(name)
                    } else {
                        val name = source[i]
                        i++
                        out += if (name == '\\') Token.NewRow else Token.Command(name.toString())
                    }
                }
                c == '{' -> { out += Token.Open; i++ }
                c == '}' -> { out += Token.Close; i++ }
                c == '^' -> { out += Token.Sup; i++ }
                c == '_' -> { out += Token.Sub; i++ }
                c == '&' -> { out += Token.Align; i++ }
                c == '%' -> while (i < source.length && source[i] != '\n') i++
                c.isWhitespace() -> {
                    val start = i
                    while (i < source.length && source[i].isWhitespace()) i++
                    out += Token.Space(source.substring(start, i))
                }
                else -> { out += Token.Char(c); i++ }
            }
        }
        return out
    }

    // ---- Parser ---------------------------------------------------------------------------------

    private class Parser(private val source: String) {
        private val tokens = tokenize(source)
        private var pos = 0
        private var forcedStyle: MathStyle? = null
        private var activeColor: Long? = null
        private var depth = 0

        fun parseRoot(): LatexNode = LatexNode.Row(sequence(inGroup = false, stopAtAlignRow = false, stopAtRight = false))

        private fun peek(): Token? = tokens.getOrNull(pos)

        private fun next(): Token? = tokens.getOrNull(pos).also { if (it != null) pos++ }

        /** Wraps a freshly emitted atom in the active colour/style scopes. */
        private fun emitWrap(node: LatexNode): LatexNode {
            var result = node
            activeColor?.let { result = LatexNode.Colored(it, listOf(result)) }
            forcedStyle?.let { result = LatexNode.Styled(it, listOf(result)) }
            return result
        }

        private fun sequence(inGroup: Boolean, stopAtAlignRow: Boolean, stopAtRight: Boolean): List<LatexNode> {
            // Question text arrives from the cloud, so a pathological `{{{{…` must not be able to
            // exhaust the stack. Past the limit the group simply stops nesting; the caller still
            // consumes tokens one at a time, so parsing always terminates.
            if (depth >= MAX_DEPTH) return emptyList()
            val savedStyle = forcedStyle
            val savedColor = activeColor
            val out = mutableListOf<LatexNode>()
            depth++
            try {
                while (step(out, inGroup, stopAtAlignRow, stopAtRight)) {
                    // step() advances the token cursor or returns false at a stop token.
                }
            } finally {
                depth--
                forcedStyle = savedStyle
                activeColor = savedColor
            }
            return out
        }

        /** Consumes at most one atom (plus its scripts). Returns false when stopped. */
        private fun step(
            out: MutableList<LatexNode>,
            inGroup: Boolean,
            stopAtAlignRow: Boolean,
            stopAtRight: Boolean,
        ): Boolean {
            val token = peek() ?: return false
            when (token) {
                Token.Close -> {
                    next()
                    if (inGroup) return false
                }
                Token.Align -> {
                    if (stopAtAlignRow) return false
                    next()
                    out += LatexNode.Space(COLUMN_GAP_EM)
                }
                Token.NewRow -> {
                    if (stopAtAlignRow) return false
                    next()
                    out += LatexNode.LineBreak
                }
                is Token.Space -> next()
                Token.Sup, Token.Sub -> {
                    next()
                    val argument = scriptArgument()
                    val base = out.removeLastOrNull()
                    out += mergeScript(base, token == Token.Sup, argument)
                }
                Token.Open -> {
                    next()
                    out += emitWrap(LatexNode.Group(sequence(inGroup = true, stopAtAlignRow = false, stopAtRight = false)))
                }
                is Token.Char -> {
                    next()
                    if (token.value == '\'') addPrime(out) else charNodes(token.value).forEach { out += emitWrap(it) }
                }
                is Token.Command -> {
                    val name = token.name
                    if (stopAtRight && name == "right") return false
                    if (stopAtAlignRow && name == "end") return false
                    next()
                    when (name) {
                        "displaystyle" -> { forcedStyle = MathStyle.Display; return true }
                        "textstyle" -> { forcedStyle = MathStyle.Text; return true }
                        "scriptstyle" -> { forcedStyle = MathStyle.Script; return true }
                        "scriptscriptstyle" -> { forcedStyle = MathStyle.ScriptScript; return true }
                        "limits" -> { setLimits(out, LimitsMode.Force); return true }
                        "nolimits" -> { setLimits(out, LimitsMode.Never); return true }
                    }
                    if (name == "over" || name == "atop" || name == "choose" || name == "brace" || name == "brack") {
                        val numerator = out.toList()
                        out.clear()
                        val denominator = sequence(inGroup, stopAtAlignRow, stopAtRight)
                        out += emitWrap(infixFraction(name, numerator, denominator))
                        return true
                    }
                    commandNodes(name).forEach { out += emitWrap(it) }
                }
            }
            return true
        }

        private fun charNodes(c: Char): List<LatexNode> = when (c) {
            in 'a'..'z', in 'A'..'Z' -> listOf(LatexNode.Symbol(c.toString(), AtomKind.Ord, italic = true))
            in '0'..'9' -> listOf(LatexNode.Symbol(c.toString()))
            '+' -> listOf(LatexNode.Symbol("+", AtomKind.Bin))
            '-' -> listOf(LatexNode.Symbol("−", AtomKind.Bin))
            '*' -> listOf(LatexNode.Symbol("∗", AtomKind.Bin))
            '/' -> listOf(LatexNode.Symbol("/", AtomKind.Bin))
            '=' -> listOf(LatexNode.Symbol("=", AtomKind.Rel))
            '<' -> listOf(LatexNode.Symbol("<", AtomKind.Rel))
            '>' -> listOf(LatexNode.Symbol(">", AtomKind.Rel))
            ',' -> listOf(LatexNode.Symbol(",", AtomKind.Punct))
            ';' -> listOf(LatexNode.Symbol(";", AtomKind.Punct))
            ':' -> listOf(LatexNode.Symbol(":", AtomKind.Rel))
            '(', '[' -> listOf(LatexNode.Symbol(c.toString(), AtomKind.Open))
            ')', ']' -> listOf(LatexNode.Symbol(c.toString(), AtomKind.Close))
            '|' -> listOf(LatexNode.Symbol("|", AtomKind.Ord))
            '!' -> listOf(LatexNode.Symbol("!", AtomKind.Ord))
            '?' -> listOf(LatexNode.Symbol("?"))
            '~' -> listOf(LatexNode.Space(0.3f))
            else -> listOf(LatexNode.Symbol(c.toString()))
        }

        /** `x'` and `x''` become superscript primes on the preceding atom. */
        private fun addPrime(out: MutableList<LatexNode>) {
            val prime = LatexNode.Symbol("\u2032")
            val base = out.lastOrNull()
            when {
                base is LatexNode.Script -> out[out.size - 1] = base.copy(sup = (base.sup ?: emptyList()) + prime)
                base == null -> out += prime
                else -> out[out.size - 1] = LatexNode.Script(base, sup = listOf(prime))
            }
        }

        private fun mergeScript(base: LatexNode?, isSup: Boolean, argument: List<LatexNode>): LatexNode {
            if (base is LatexNode.Script) {
                return if (isSup) base.copy(sup = (base.sup ?: emptyList()) + argument)
                else base.copy(sub = (base.sub ?: emptyList()) + argument)
            }
            val root = base ?: LatexNode.Symbol("")
            return if (isSup) LatexNode.Script(root, sup = argument) else LatexNode.Script(root, sub = argument)
        }

        private fun setLimits(out: MutableList<LatexNode>, mode: LimitsMode) {
            val last = out.lastOrNull() ?: return
            out[out.size - 1] = when (last) {
                is LatexNode.Script -> last.base?.let { LatexNode.Script(it.withLimits(mode), last.sup, last.sub) } ?: last
                else -> last.withLimits(mode)
            }
        }

        private fun scriptArgument(): List<LatexNode> {
            val token = peek() ?: return emptyList()
            return when (token) {
                Token.Open -> { next(); sequence(inGroup = true, stopAtAlignRow = false, stopAtRight = false) }
                is Token.Char -> {
                    next()
                    if (token.value == '\'') {
                        val out = mutableListOf<LatexNode>()
                        addPrime(out)
                        out
                    } else charNodes(token.value).map { emitWrap(it) }
                }
                is Token.Command -> { next(); commandNodes(token.name).map { emitWrap(it) } }
                Token.Sup -> { next(); listOf(LatexNode.Symbol("^")) }
                Token.Sub -> { next(); listOf(LatexNode.Symbol("_")) }
                else -> { next(); emptyList() }
            }
        }

        private fun readArgument(): List<LatexNode> {
            val token = peek() ?: return emptyList()
            return when (token) {
                Token.Open -> { next(); sequence(inGroup = true, stopAtAlignRow = false, stopAtRight = false) }
                is Token.Char -> {
                    next()
                    if (token.value == '\'') {
                        val out = mutableListOf<LatexNode>()
                        addPrime(out)
                        out
                    } else charNodes(token.value).map { emitWrap(it) }
                }
                is Token.Command -> { next(); commandNodes(token.name).map { emitWrap(it) } }
                else -> { next(); emptyList() }
            }
        }

        /** Reads `[…]`, used by `\sqrt[3]{}` and friends. Null when there is no bracket. */
        private fun readOptionalArgument(): List<LatexNode>? {
            val token = peek()
            if (token !is Token.Char || token.value != '[') return null
            next()
            val out = mutableListOf<LatexNode>()
            while (true) {
                val current = peek() ?: break
                if (current is Token.Char && current.value == ']') { next(); break }
                if (current == Token.Close) { next(); break }
                if (!step(out, inGroup = false, stopAtAlignRow = false, stopAtRight = false)) break
            }
            return out
        }

        /** Reads a braced argument as literal source, preserving spaces for `\text`. */
        private fun readRawArgument(): String {
            val token = peek()
            if (token is Token.Command) {
                next()
                return token.name
            }
            if (token !is Token.Open) {
                val single = if (token is Token.Char) { next(); (token.value).toString() }
                else if (token is Token.Space) { next(); " " } else { next(); "" }
                return single
            }
            next()
            val sb = StringBuilder()
            var depth = 1
            while (pos < tokens.size) {
                when (val current = tokens[pos]) {
                    Token.Open -> { depth++; sb.append('{'); pos++ }
                    Token.Close -> {
                        depth--
                        pos++
                        if (depth == 0) return sb.toString()
                        sb.append('}')
                    }
                    is Token.Char -> { sb.append(current.value); pos++ }
                    is Token.Command -> {
                        if (current.name.length == 1 && !isCommandLetter(current.name[0])) sb.append(current.name)
                        else sb.append('\\').append(current.name)
                        pos++
                    }
                    is Token.Space -> { sb.append(' '); pos++ }
                    Token.Sup -> { sb.append('^'); pos++ }
                    Token.Sub -> { sb.append('_'); pos++ }
                    Token.Align -> { sb.append('&'); pos++ }
                    Token.NewRow -> { sb.append("\\\\"); pos++ }
                }
            }
            return sb.toString()
        }

        private fun textFromRaw(raw: String): String = raw.replace(WHITESPACE_RUN, " ")

        private fun readDelimiter(): String? {
            val token = peek() ?: return null
            next()
            return when (token) {
                is Token.Char -> if (token.value == '.') null
                else LatexSymbols.delimiters[token.value.toString()] ?: token.value.toString()
                is Token.Command -> {
                    when (token.name) {
                        "." -> null
                        // `\|` is the double bar, unlike the single `|` character.
                        "|" -> "\u2016"
                        else -> LatexSymbols.delimiters[token.name]?.ifEmpty { null } ?: token.name
                    }
                }
                else -> null
            }
        }

        private fun parseLeftRight(): LatexNode {
            val left = readDelimiter()
            val body = sequence(inGroup = false, stopAtAlignRow = false, stopAtRight = true)
            val closing = peek()
            val right = if (closing is Token.Command && closing.name == "right") {
                next()
                readDelimiter()
            } else null
            return LatexNode.Delimited(left, right, body)
        }

        private fun parseEnvironment(name: String, spec: String?): LatexNode {
            val rows = mutableListOf<List<List<LatexNode>>>()
            var cells = mutableListOf<List<LatexNode>>()
            while (true) {
                cells += sequence(inGroup = false, stopAtAlignRow = true, stopAtRight = false)
                when (val stop = peek()) {
                    null -> { rows += cells; break }
                    Token.Align -> next()
                    Token.NewRow -> { next(); rows += cells; cells = mutableListOf() }
                    is Token.Command -> {
                        if (stop.name == "end") {
                            next()
                            readRawArgument()
                            rows += cells
                            break
                        }
                        next()
                    }
                    else -> next()
                }
            }
            val (left, right) = environmentDelimiters(name)
            val columns = rows.maxOfOrNull { it.size } ?: 0
            val template = name in ALIGN_TEMPLATE_ENVIRONMENTS
            val aligns = when {
                name == "array" || name == "subarray" -> columnAligns(spec)
                // amsmath's align family alternates right-aligned and left-aligned columns, which is
                // what makes a run of `&= … \\ &= …` line up on the relation.
                template && columns > 0 -> List(columns) { if (it % 2 == 0) 'r' else 'l' }
                name == "cases" && columns > 0 -> List(columns) { 'l' }
                else -> null
            }
            return LatexNode.Matrix(rows, left, right, aligns, template)
        }

        private fun environmentDelimiters(name: String): Pair<String?, String?> = when (name) {
            "pmatrix" -> "(" to ")"
            "bmatrix" -> "[" to "]"
            "Bmatrix" -> "{" to "}"
            "vmatrix" -> "|" to "|"
            "Vmatrix" -> "\u2016" to "\u2016"
            "cases" -> "{" to null
            else -> null to null
        }

        private fun columnAligns(spec: String?): List<Char>? {
            if (spec.isNullOrBlank()) return null
            val cleaned = spec.replace(Regex("@\\{[^{}]*\\}"), "")
            val aligned = cleaned.filter { it == 'c' || it == 'l' || it == 'r' }.toList()
            return aligned.ifEmpty { null }
        }

        private fun infixFraction(name: String, num: List<LatexNode>, den: List<LatexNode>): LatexNode {
            val fraction = LatexNode.Fraction(num, den, bar = name == "over" || name == "brace" || name == "brack")
            return when (name) {
                "choose" -> LatexNode.Delimited("(", ")", listOf(fraction))
                "brace" -> LatexNode.Delimited("{", "}", listOf(fraction))
                "brack" -> LatexNode.Delimited("[", "]", listOf(fraction))
                else -> fraction
            }
        }

        private fun commandNodes(name: String): List<LatexNode> {
            if (name.length == 1 && !isCommandLetter(name[0])) return singleCharCommand(name[0])

            LatexSymbols.bigOperators[name]?.let { return listOf(LatexNode.BigOp(it.text, it.limits)) }
            LatexSymbols.operatorNames[name]?.let {
                return listOf(LatexNode.OperatorName(LatexSymbols.operatorDisplay[name] ?: name, it))
            }
            LatexSymbols.delimiterSizes[name]?.let {
                return listOf(LatexNode.SizedDelimiter(readDelimiter() ?: "", it))
            }
            LatexSymbols.accents[name]?.let {
                if (name == "bar") return listOf(LatexNode.Overline(nodeOf(readArgument())))
                return listOf(LatexNode.Accent(it, nodeOf(readArgument())))
            }

            when (name) {
                "frac", "dfrac", "cfrac" -> return listOf(LatexNode.Fraction(readArgument(), readArgument()))
                "tfrac" -> return listOf(LatexNode.Styled(MathStyle.Text, listOf(LatexNode.Fraction(readArgument(), readArgument()))))
                "binom" -> return listOf(LatexNode.Delimited("(", ")", listOf(LatexNode.Fraction(readArgument(), readArgument(), bar = false))))
                "dbinom" -> return listOf(LatexNode.Delimited("(", ")", listOf(LatexNode.Styled(MathStyle.Display, listOf(LatexNode.Fraction(readArgument(), readArgument(), bar = false))))))
                "tbinom" -> return listOf(LatexNode.Delimited("(", ")", listOf(LatexNode.Styled(MathStyle.Text, listOf(LatexNode.Fraction(readArgument(), readArgument(), bar = false))))))
                "sqrt" -> {
                    // The index comes before the radicand: `\sqrt[3]{x}`.
                    val index = readOptionalArgument()
                    return listOf(LatexNode.Radical(readArgument(), index))
                }
                "overline" -> return listOf(LatexNode.Overline(nodeOf(readArgument())))
                "underline" -> return listOf(LatexNode.Underline(nodeOf(readArgument())))
                "overbrace", "underbrace" ->
                    return listOf(LatexNode.Stretchy(StretchyKind.Brace, nodeOf(readArgument()), above = name.startsWith("over")))
                "overbracket", "underbracket" ->
                    return listOf(LatexNode.Stretchy(StretchyKind.Bracket, nodeOf(readArgument()), above = name.startsWith("over")))
                "overrightarrow", "underrightarrow" ->
                    return listOf(LatexNode.Stretchy(StretchyKind.ArrowRight, nodeOf(readArgument()), above = name.startsWith("over")))
                "overleftarrow", "underleftarrow" ->
                    return listOf(LatexNode.Stretchy(StretchyKind.ArrowLeft, nodeOf(readArgument()), above = name.startsWith("over")))
                "overleftrightarrow", "underleftrightarrow" ->
                    return listOf(LatexNode.Stretchy(StretchyKind.ArrowBoth, nodeOf(readArgument()), above = name.startsWith("over")))
                "boxed" -> return listOf(LatexNode.Boxed(nodeOf(readArgument())))
                "cancel", "sout" -> return listOf(LatexNode.Cancelled(nodeOf(readArgument()), forward = true, backward = false))
                "bcancel" -> return listOf(LatexNode.Cancelled(nodeOf(readArgument()), forward = false, backward = true))
                "xcancel" -> return listOf(LatexNode.Cancelled(nodeOf(readArgument()), forward = true, backward = true))
                "not" -> return listOf(LatexNode.Negated(nodeOf(readArgument())))
                "phantom", "hphantom", "vphantom", "smash" -> return listOf(LatexNode.Phantomed(readArgument()))
                "left" -> return listOf(parseLeftRight())
                "right", "end", "nonumber", "notag", "limits", "nolimits" -> return emptyList()
                "middle" -> return listOf(LatexNode.SizedDelimiter(readDelimiter() ?: "", 1.6f))
                "begin" -> {
                    // `array`/`subarray` declare a column spec and `alignat` a pair count; both arrive
                    // as one braced argument straight after the environment name.
                    val environment = readRawArgument()
                    val spec = if (environment in ARGUMENT_ENVIRONMENTS) readRawArgument() else null
                    return listOf(parseEnvironment(environment, spec))
                }
                "text", "textrm", "textnormal", "textup", "mbox", "hbox" -> return listOf(LatexNode.Text(textFromRaw(readRawArgument())))
                "textit" -> return listOf(LatexNode.Text(textFromRaw(readRawArgument()), italic = true))
                "textbf" -> return listOf(LatexNode.Text(textFromRaw(readRawArgument()), bold = true))
                "textsf" -> return listOf(LatexNode.Text(textFromRaw(readRawArgument()), font = LatexFont.SansSerif))
                "texttt" -> return listOf(LatexNode.Text(textFromRaw(readRawArgument()), font = LatexFont.Monospace))
                "textbackslash" -> return listOf(LatexNode.Text("\\"))
                "mathrm" -> return listOf(upright(readArgument()))
                "mathbf", "boldsymbol", "bm" -> return listOf(bolden(readArgument()))
                "mathit" -> return listOf(italicise(readArgument()))
                "mathsf" -> return listOf(reFont(readArgument(), LatexFont.SansSerif))
                "mathtt" -> return listOf(reFont(readArgument(), LatexFont.Monospace))
                "mathcal" -> return listOf(reFont(readArgument(), LatexFont.Cursive))
                "mathbb" -> return listOf(bolden(readArgument()))
                "mathfrak" -> return listOf(reFontBold(readArgument(), LatexFont.Cursive))
                "mathnormal" -> return readArgument()
                "operatorname" -> return listOf(LatexNode.OperatorName(textFromRaw(readRawArgument()), LimitsMode.Never))
                "operatorname*" -> return listOf(LatexNode.OperatorName(textFromRaw(readRawArgument()), LimitsMode.Auto))
                "mathop" -> return listOf(LatexNode.Classed(AtomKind.Op, nodeOf(readArgument())))
                "mathbin" -> return listOf(LatexNode.Classed(AtomKind.Bin, nodeOf(readArgument())))
                "mathrel" -> return listOf(LatexNode.Classed(AtomKind.Rel, nodeOf(readArgument())))
                "mathord" -> return listOf(LatexNode.Classed(AtomKind.Ord, nodeOf(readArgument())))
                "mathopen" -> return listOf(LatexNode.Classed(AtomKind.Open, nodeOf(readArgument())))
                "mathclose" -> return listOf(LatexNode.Classed(AtomKind.Close, nodeOf(readArgument())))
                "mathpunct" -> return listOf(LatexNode.Classed(AtomKind.Punct, nodeOf(readArgument())))
                "mathinner" -> return listOf(LatexNode.Classed(AtomKind.Inner, nodeOf(readArgument())))
                "overset", "stackrel" -> {
                    val top = readArgument()
                    return listOf(LatexNode.Script(nodeOf(readArgument()), sup = top))
                }
                "underset" -> {
                    val bottom = readArgument()
                    return listOf(LatexNode.Script(nodeOf(readArgument()), sub = bottom))
                }
                "xrightarrow", "xleftarrow", "xleftrightarrow" -> {
                    // amsmath's extensible arrows: the mandatory argument is the label *above* the
                    // arrow, which is why the arrow is the decoration and the label its base.
                    val below = readOptionalArgument()
                    val above = readArgument()
                    val kind = when (name) {
                        "xleftarrow" -> StretchyKind.ArrowLeft
                        "xleftrightarrow" -> StretchyKind.ArrowBoth
                        else -> StretchyKind.ArrowRight
                    }
                    return listOf(
                        // `\xrightarrow` is a relation, so it takes relation spacing on both sides, and
                        // both of its labels are set in script size, as amsmath's limits do.
                        LatexNode.Classed(
                            AtomKind.Rel,
                            LatexNode.Stretchy(
                                kind,
                                LatexNode.Styled(MathStyle.Script, above),
                                above = false,
                                label = below,
                            ),
                        ),
                    )
                }
                "pmod" -> {
                    val argument = readArgument()
                    return listOf(LatexNode.Group(listOf(
                        LatexNode.Symbol("(", AtomKind.Open),
                        LatexNode.OperatorName("mod", LimitsMode.Never),
                        LatexNode.Space(0.25f),
                    ) + argument + LatexNode.Symbol(")", AtomKind.Close)))
                }
                "bmod", "mod" -> return listOf(LatexNode.OperatorName("mod", LimitsMode.Never))
                "pod" -> return listOf(LatexNode.Delimited("(", ")", readArgument()))
                "hspace", "kern", "mkern", "mskip", "hspace*" -> {
                    readArgument()
                    return listOf(LatexNode.Space(0.3f))
                }
                "quad" -> return listOf(LatexNode.Space(1f))
                "qquad" -> return listOf(LatexNode.Space(2f))
                "tag", "tag*" -> return listOf(LatexNode.Text("(" + textFromRaw(readRawArgument()) + ")"))
                "color" -> {
                    activeColor = parseColor(readRawArgument())
                    return emptyList()
                }
                "textcolor" -> {
                    val color = parseColor(readRawArgument())
                    return listOf(LatexNode.Colored(color, readArgument()))
                }
                "class", "htmlClass", "htmlId", "cssId", "label", "ref", "eqref", "rule" -> {
                    readRawArgument()
                    return emptyList()
                }
            }

            LatexSymbols.commands[name]?.let {
                return listOf(LatexNode.Symbol(it.text, it.kind, it.italic, it.bold, it.font))
            }
            // Unknown command: keep the name visible instead of dropping the question.
            return listOf(LatexNode.Text(name))
        }

        private fun singleCharCommand(c: Char): List<LatexNode> = when (c) {
            '{' -> listOf(LatexNode.Symbol("{", AtomKind.Open))
            '}' -> listOf(LatexNode.Symbol("}", AtomKind.Close))
            '|' -> listOf(LatexNode.Symbol("\u2016"))
            ',' -> listOf(LatexNode.Space(3f / 18f))
            ';' -> listOf(LatexNode.Space(5f / 18f))
            ':' -> listOf(LatexNode.Space(4f / 18f))
            '!' -> listOf(LatexNode.Space(-3f / 18f))
            ' ' -> listOf(LatexNode.Space(1f / 3f))
            '%', '$', '&', '#', '_' -> listOf(LatexNode.Text(c.toString()))
            '/' -> listOf(LatexNode.Symbol("/"))
            else -> listOf(LatexNode.Symbol(c.toString()))
        }

        private fun parseColor(raw: String): Long {
            val value = raw.trim()
            if (value.isEmpty()) return DEFAULT_MATH_COLOR
            LatexSymbols.colors[value.lowercase()]?.let { return it }
            val hex = value.removePrefix("#")
            return when (hex.length) {
                3 -> runCatching {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
                }.getOrDefault(DEFAULT_MATH_COLOR)
                6 -> runCatching { 0xFF000000L or hex.toLong(16) }.getOrDefault(DEFAULT_MATH_COLOR)
                8 -> runCatching { hex.toLong(16) }.getOrDefault(DEFAULT_MATH_COLOR)
                else -> DEFAULT_MATH_COLOR
            }
        }

        private fun nodeOf(nodes: List<LatexNode>): LatexNode =
            if (nodes.size == 1) nodes.first() else LatexNode.Group(nodes)

        private fun upright(nodes: List<LatexNode>): LatexNode = LatexNode.Group(nodes.map { mapPosture(it, italic = false, bold = null, font = null) })
        private fun italicise(nodes: List<LatexNode>): LatexNode = LatexNode.Group(nodes.map { mapPosture(it, italic = true, bold = null, font = null) })
        private fun bolden(nodes: List<LatexNode>): LatexNode = LatexNode.Group(nodes.map { mapPosture(it, italic = false, bold = true, font = null) })
        private fun reFont(nodes: List<LatexNode>, font: LatexFont): LatexNode = LatexNode.Group(nodes.map { mapPosture(it, italic = null, bold = null, font = font) })
        private fun reFontBold(nodes: List<LatexNode>, font: LatexFont): LatexNode = LatexNode.Group(nodes.map { mapPosture(it, italic = null, bold = true, font = font) })

        private fun mapPosture(node: LatexNode, italic: Boolean?, bold: Boolean?, font: LatexFont?): LatexNode = when (node) {
            is LatexNode.Symbol -> node.copy(
                italic = italic ?: node.italic,
                bold = bold ?: node.bold,
                font = font ?: node.font,
            )
            is LatexNode.Text -> node.copy(
                italic = italic ?: node.italic,
                bold = bold ?: node.bold,
                font = font ?: node.font,
            )
            is LatexNode.Group -> node.copy(children = node.children.map { mapPosture(it, italic, bold, font) })
            else -> node
        }
    }

    // ---- AST helpers ----------------------------------------------------------------------------

    private fun LatexNode.withLimits(mode: LimitsMode): LatexNode = when (this) {
        is LatexNode.BigOp -> copy(limits = mode)
        is LatexNode.OperatorName -> copy(limits = mode)
        is LatexNode.Styled -> copy(children = children.map { it.withLimits(mode) })
        is LatexNode.Classed -> copy(base = base?.withLimits(mode))
        else -> this
    }

    // ---- Plain-text rendering -------------------------------------------------------------------

    private fun appendUnicode(node: LatexNode?, sb: StringBuilder) {
        when (node) {
            null -> Unit
            is LatexNode.Symbol -> sb.append(node.text)
            is LatexNode.Text -> sb.append(node.text)
            is LatexNode.Group -> node.children.forEach { appendUnicode(it, sb) }
            is LatexNode.Row -> node.children.forEach { appendUnicode(it, sb) }
            is LatexNode.Fraction -> {
                if (node.bar) {
                    sb.append('('); node.num.forEach { appendUnicode(it, sb) }; sb.append(")/(")
                    node.den.forEach { appendUnicode(it, sb) }; sb.append(')')
                } else {
                    sb.append('('); node.num.forEach { appendUnicode(it, sb) }
                    sb.append(" choose "); node.den.forEach { appendUnicode(it, sb) }; sb.append(')')
                }
            }
            is LatexNode.Radical -> {
                sb.append('\u221A')
                node.index?.takeIf { it.isNotEmpty() }?.let {
                    sb.append('['); it.forEach { child -> appendUnicode(child, sb) }; sb.append(']')
                }
                sb.append('('); node.body.forEach { appendUnicode(it, sb) }; sb.append(')')
            }
            is LatexNode.Script -> {
                appendUnicode(node.base, sb)
                node.sup?.takeIf { it.isNotEmpty() }?.let {
                    sb.append('^'); if (it.size > 1) sb.append('('); it.forEach { child -> appendUnicode(child, sb) }
                    if (it.size > 1) sb.append(')')
                }
                node.sub?.takeIf { it.isNotEmpty() }?.let {
                    sb.append('_'); if (it.size > 1) sb.append('('); it.forEach { child -> appendUnicode(child, sb) }
                    if (it.size > 1) sb.append(')')
                }
            }
            is LatexNode.BigOp -> { sb.append(node.text); sb.append(' ') }
            is LatexNode.OperatorName -> { sb.append(node.text); sb.append(' ') }
            is LatexNode.Accent -> appendUnicode(node.base, sb)
            is LatexNode.Stretchy -> {
                    // An arrow with a mandatory label (`\xrightarrow{f}`) reads as `→f`; a decoration
                    // that sits over its base (`\overrightarrow{v}`) reads as `v→`.
                    val glyph = stretchyGlyph(node.kind)
                    if (node.above) {
                        appendUnicode(node.base, sb)
                        sb.append(glyph)
                    } else {
                        sb.append(glyph)
                        appendUnicode(node.base, sb)
                    }
                    node.label?.takeIf { it.isNotEmpty() }?.let { children ->
                        sb.append(' ')
                        children.forEach { appendUnicode(it, sb) }
                    }
                }
            is LatexNode.Overline -> appendUnicode(node.base, sb)
            is LatexNode.Underline -> appendUnicode(node.base, sb)
            is LatexNode.Delimited -> {
                node.left?.let { sb.append(it) }
                node.body.forEach { appendUnicode(it, sb) }
                node.right?.let { sb.append(it) }
            }
            is LatexNode.SizedDelimiter -> sb.append(node.text)
            is LatexNode.Matrix -> {
                node.left?.let { sb.append(it) }
                node.rows.forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) sb.append("; ")
                    row.forEachIndexed { cellIndex, cell ->
                        if (cellIndex > 0) sb.append(", ")
                        cell.forEach { appendUnicode(it, sb) }
                    }
                }
                node.right?.let { sb.append(it) }
            }
            is LatexNode.Styled -> node.children.forEach { appendUnicode(it, sb) }
            is LatexNode.Colored -> node.children.forEach { appendUnicode(it, sb) }
            is LatexNode.Space -> sb.append(' ')
            is LatexNode.Phantomed -> node.children.forEach { appendUnicode(it, sb) }
            is LatexNode.Boxed -> appendUnicode(node.base, sb)
            is LatexNode.Cancelled -> appendUnicode(node.base, sb)
            is LatexNode.Negated -> appendUnicode(node.base, sb)
            is LatexNode.Classed -> appendUnicode(node.base, sb)
            is LatexNode.LineBreak -> sb.append(' ')
        }
    }
}

/** Atom classes in the TeXbook's inter-atom spacing table, in [AtomKind] ordinal order. */
internal val ATOM_SPACING = arrayOf(
    //          Ord  Op   Bin  Rel  Open Close Punct Inner
    floatArrayOf(0f, 1f, 2f, 3f, 0f, 0f, 0f, 1f), // Ord
    floatArrayOf(1f, 1f, 2f, 3f, 0f, 0f, 0f, 1f), // Op
    floatArrayOf(2f, 2f, 0f, 0f, 2f, 0f, 0f, 2f), // Bin
    floatArrayOf(3f, 3f, 0f, 0f, 3f, 0f, 0f, 3f), // Rel
    floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), // Open
    floatArrayOf(0f, 1f, 2f, 3f, 0f, 0f, 0f, 1f), // Close
    floatArrayOf(1f, 1f, 2f, 3f, 1f, 1f, 1f, 1f), // Punct
    floatArrayOf(1f, 1f, 2f, 3f, 1f, 0f, 1f, 1f), // Inner
)

/** Spacing units for the table above: 1 = thin, 2 = medium, 3 = thick, as a fraction of an em. */
internal fun spacingEm(level: Float): Float = when (level) {
    0f -> 0f
    1f -> 3f / 18f
    2f -> 4f / 18f
    else -> 5f / 18f
}

internal const val DEFAULT_MATH_COLOR: Long = 0xFF000000L
private const val COLUMN_GAP_EM = 0.28f

/** Deepest group nesting the parser will descend into before flattening the rest. */
private const val MAX_DEPTH = 64

/** Environments that take one braced argument before their body. */
private val ARGUMENT_ENVIRONMENTS = setOf("array", "subarray", "alignat", "alignat*")

/** Environments that follow amsmath's align template: display-style cells in `rl` column pairs. */
/** The nearest plain-text stand-in for a stretchy decoration, for the no-UI fallback. */
private fun stretchyGlyph(kind: StretchyKind): String = when (kind) {
    StretchyKind.Brace -> "\u23DE"
    StretchyKind.Bracket -> "\u23B4"
    StretchyKind.ArrowRight -> "\u2192"
    StretchyKind.ArrowLeft -> "\u2190"
    StretchyKind.ArrowBoth -> "\u2194"
}

private val ALIGN_TEMPLATE_ENVIRONMENTS = setOf(
    "aligned", "align", "align*", "split", "flalign", "flalign*", "alignat", "alignat*",
)
private val WHITESPACE_RUN = Regex("\\s+")
