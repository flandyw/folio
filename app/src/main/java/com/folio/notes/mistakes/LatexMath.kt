package com.folio.notes.mistakes

/**
 * Offline LaTeX maths model for the Mistakes screens.
 *
 * This is a small, self-contained typesetting engine — a KaTeX-style pipeline written in
 * plain Kotlin instead of a WebView or a third-party renderer:
 *
 *   source → [LatexParser] (tokens → AST) → [LatexLayout] (AST → boxes + draw list) → Compose
 *
 * Everything here and in [LatexParser]/[LatexLayout] is free of Android types, so the
 * grammar and the box model are unit-testable on the JVM. [LatexView] is the only file
 * that touches Compose, and it only paints the draw list the engine produced.
 *
 * Coverage is deliberately generous rather than exhaustive: fractions and binomials, radicals,
 * superscripts/subscripts (including limits under large operators), the full Greek alphabet,
 * the common operator/relation/arrow/delimiter tables, accents, `\left…\right`, matrices and
 * cases, text and font commands, colours and spacing. Unknown commands degrade to their own
 * name instead of disappearing, so a question never loses content.
 */

/** TeX's eight math atom classes, which decide the inter-atom spacing. */
enum class AtomKind { Ord, Op, Bin, Rel, Open, Close, Punct, Inner }

/** Where a large operator's script limits go. `Auto` follows the display/text style. */
enum class LimitsMode { Auto, Force, Never }

/** TeX's four math styles; `Script`/`ScriptScript` are the shrunken ones. */
enum class MathStyle { Display, Text, Script, ScriptScript }

/** Font family used for a glyph run. Posture (italic/bold) is carried separately. */
enum class LatexFont { Serif, SansSerif, Monospace, Cursive }

/** The horizontal decorations that stretch across their whole base. */
enum class StretchyKind { Brace, Bracket, ArrowRight, ArrowLeft, ArrowBoth }

/** A parsed LaTeX math expression. */
sealed interface LatexNode {
    /** A literal glyph run that keeps its own posture (letters are italic, digits upright). */
    data class Symbol(
        val text: String,
        val kind: AtomKind = AtomKind.Ord,
        val italic: Boolean = false,
        val bold: Boolean = false,
        val font: LatexFont = LatexFont.Serif,
    ) : LatexNode

    /** Upright text produced by `\text`, `\mathrm`, `\operatorname`, … */
    data class Text(val text: String, val font: LatexFont = LatexFont.Serif, val bold: Boolean = false,
        val italic: Boolean = false) : LatexNode

    data class Group(val children: List<LatexNode>) : LatexNode

    /** `\frac`, `\dfrac`, `\binom`, … — `bar` is false for binomials. */
    data class Fraction(val num: List<LatexNode>, val den: List<LatexNode>, val bar: Boolean = true) : LatexNode

    data class Radical(val body: List<LatexNode>, val index: List<LatexNode>? = null) : LatexNode

    /** A base with `^`/`_` scripts. [base] is null for a leading script. */
    data class Script(val base: LatexNode?, val sup: List<LatexNode>? = null, val sub: List<LatexNode>? = null) : LatexNode

    /** `\sum`, `\int`, … — a real glyph operator whose limits follow [limits]. */
    data class BigOp(val text: String, val limits: LimitsMode = LimitsMode.Auto) : LatexNode

    /** `\lim`, `\Pr`, `\sin`, `\operatorname{…}` — upright name, limits follow [limits]. */
    data class OperatorName(val text: String, val limits: LimitsMode = LimitsMode.Never) : LatexNode

    /** `\hat`, `\vec`, `\tilde`, … — a fixed-size [mark] drawn above [base]. */
    data class Accent(val mark: String, val base: LatexNode?) : LatexNode

    /**
     * A decoration that grows with its base: `\overbrace`, `\underbrace`, `\overrightarrow`,
     * `\overleftarrow` and friends. Drawn from geometry rather than a single centred glyph, so it
     * always spans exactly the width of what it decorates. [above] places it over the base, and
     * [label] is optional content beyond it — the `[below]` argument of `\xrightarrow`.
     */
    data class Stretchy(
        val kind: StretchyKind,
        val base: LatexNode?,
        val above: Boolean = true,
        val label: List<LatexNode>? = null,
    ) : LatexNode

    data class Overline(val base: LatexNode?) : LatexNode
    data class Underline(val base: LatexNode?) : LatexNode

    /** `\left( … \right)`; either delimiter may be null for a one-sided pair. */
    data class Delimited(val left: String?, val right: String?, val body: List<LatexNode>) : LatexNode

    /** `\bigl(` and friends — a delimiter at a fixed size multiple. */
    data class SizedDelimiter(val text: String, val scale: Float) : LatexNode

    /**
     * A `\begin…\end` grid. [aligns] gives each column's alignment — `'l'`, `'c'` or `'r'` — and
     * null centres every column. [amsmathTemplate] marks `aligned`/`align`/`split`, whose cells are
     * display-style and whose odd columns begin with `{}` so a leading relation keeps its spacing.
     */
    data class Matrix(
        val rows: List<List<List<LatexNode>>>,
        val left: String? = null,
        val right: String? = null,
        val aligns: List<Char>? = null,
        val amsmathTemplate: Boolean = false,
    ) : LatexNode

    /** `\displaystyle{…}` and the other explicit style commands. */
    data class Styled(val style: MathStyle, val children: List<LatexNode>) : LatexNode

    data class Colored(val argb: Long, val children: List<LatexNode>) : LatexNode

    /** Explicit math-mode spacing, in em. */
    data class Space(val em: Float) : LatexNode

    /** `\phantom{…}` reserves space without painting anything. */
    data class Phantomed(val children: List<LatexNode>) : LatexNode

    data class Boxed(val base: LatexNode?) : LatexNode

    /** `\cancel`, `\bcancel`, `\xcancel`. */
    data class Cancelled(val base: LatexNode?, val forward: Boolean = true, val backward: Boolean = false) : LatexNode

    /** `\not` — a slash struck through the following atom. */
    data class Negated(val base: LatexNode?) : LatexNode

    /** `\mathop{…}` and friends — forces an atom class. */
    data class Classed(val kind: AtomKind, val base: LatexNode?) : LatexNode

    data object LineBreak : LatexNode

    data class Row(val children: List<LatexNode>) : LatexNode
}

/** Command → glyph and atom class. Keys never carry the leading backslash. */
object LatexSymbols {
    data class Sym(
        val text: String,
        val kind: AtomKind = AtomKind.Ord,
        val italic: Boolean = false,
        val bold: Boolean = false,
        val font: LatexFont = LatexFont.Serif,
    )

    /** A large operator such as `\sum` or `\int`. */
    data class BigOpDef(val text: String, val limits: LimitsMode)

    private fun ord(t: String, italic: Boolean = false) = Sym(t, AtomKind.Ord, italic)
    private fun bin(t: String) = Sym(t, AtomKind.Bin)
    private fun rel(t: String) = Sym(t, AtomKind.Rel)
    private fun op(t: String) = Sym(t, AtomKind.Op)
    private fun open(t: String) = Sym(t, AtomKind.Open)
    private fun close(t: String) = Sym(t, AtomKind.Close)
    private fun punct(t: String) = Sym(t, AtomKind.Punct)

    /** Lower-case Greek is italic, upper-case upright — the standard TeX convention. */
    private val greekLower = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ",
        "phi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "varepsilon" to "ϵ", "vartheta" to "ϑ", "varkappa" to "ϰ", "varpi" to "ϖ",
        "varrho" to "ϱ", "varsigma" to "ς", "varphi" to "φ",
    )

    private val greekUpper = mapOf(
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
        "Omega" to "Ω", "varGamma" to "Γ", "varDelta" to "Δ", "varTheta" to "Θ",
        "varLambda" to "Λ", "varXi" to "Ξ", "varPi" to "Π", "varSigma" to "Σ",
        "varUpsilon" to "Υ", "varPhi" to "Φ", "varPsi" to "Ψ", "varOmega" to "Ω",
    )

    val commands: Map<String, Sym> = buildMap {
        greekLower.forEach { (name, glyph) -> put(name, ord(glyph, italic = true)) }
        greekUpper.forEach { (name, glyph) -> put(name, ord(glyph)) }

        // Binary operators.
        listOf(
            "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "⋅",
            "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "∙", "oplus" to "⊕",
            "ominus" to "⊖", "otimes" to "⊗", "oslash" to "⊘", "odot" to "⊙",
            "dagger" to "†", "ddagger" to "‡", "amalg" to "⨿", "cup" to "∪", "cap" to "∩",
            "uplus" to "⊎", "sqcup" to "⊔", "sqcap" to "⊓", "vee" to "∨", "wedge" to "∧",
            "setminus" to "∖", "smallsetminus" to "∖", "wr" to "≀", "diamond" to "⋄",
            "bigtriangleup" to "△", "bigtriangledown" to "▽", "triangleleft" to "◁",
            "triangleright" to "▷", "lhd" to "◁", "rhd" to "▷", "unlhd" to "⊴",
            "unrhd" to "⊵", "dotplus" to "∔", "ltimes" to "⋉", "rtimes" to "⋊",
            "circledast" to "⊛", "circledcirc" to "⊚", "circleddash" to "⊝",
            "intercal" to "⊺", "centerdot" to "·",
        ).forEach { (name, glyph) -> put(name, bin(glyph)) }

        // Relations.
        listOf(
            "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠",
            "neq" to "≠", "equiv" to "≡", "approx" to "≈", "cong" to "≅", "sim" to "∼",
            "simeq" to "≃", "asymp" to "≍", "propto" to "∝", "prec" to "≺", "succ" to "≻",
            "preceq" to "⪯", "succeq" to "⪰", "ll" to "≪", "gg" to "≫", "subset" to "⊂",
            "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇", "sqsubset" to "⊏",
            "sqsupset" to "⊐", "sqsubseteq" to "⊑", "sqsupseteq" to "⊒", "in" to "∈",
            "ni" to "∋", "owns" to "∋", "notin" to "∉", "mid" to "∣", "nmid" to "∤",
            "parallel" to "∥", "nparallel" to "∦", "perp" to "⊥", "vdash" to "⊢",
            "dashv" to "⊣", "models" to "⊨", "vDash" to "⊨", "top" to "⊤", "bot" to "⊥",
            "smile" to "⌣", "frown" to "⌢", "doteq" to "≐", "triangleq" to "≜",
            "therefore" to "∴", "because" to "∵", "lesssim" to "≲", "gtrsim" to "≳",
            "lessgtr" to "≶", "gtrless" to "≷", "nleq" to "≰", "ngeq" to "≱",
            "ncong" to "≇", "nsim" to "≁", "coloneqq" to "≔", "eqqcolon" to "≕",
            "approxeq" to "≊", "subsetneq" to "⊊", "supsetneq" to "⊋", "bowtie" to "⋈",
            "pitchfork" to "⋔", "between" to "≬", "varpropto" to "∝", "nvdash" to "⊬",
            "nvDash" to "⊭", "nprec" to "⊀", "nsucc" to "⊁",
        ).forEach { (name, glyph) -> put(name, rel(glyph)) }

        // Arrows (relations for spacing purposes).
        listOf(
            "to" to "→", "rightarrow" to "→", "gets" to "←", "leftarrow" to "←",
            "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
            "Leftrightarrow" to "⇔", "implies" to "⟹", "iff" to "⟺", "impliedby" to "⟸",
            "mapsto" to "↦", "longmapsto" to "⟼", "longrightarrow" to "⟶",
            "longleftarrow" to "⟵", "longleftrightarrow" to "⟷", "Longrightarrow" to "⟹",
            "Longleftarrow" to "⟸", "Longleftrightarrow" to "⟺", "uparrow" to "↑",
            "downarrow" to "↓", "updownarrow" to "↕", "Uparrow" to "⇑", "Downarrow" to "⇓",
            "Updownarrow" to "⇕", "nearrow" to "↗", "searrow" to "↘", "swarrow" to "↙",
            "nwarrow" to "↖", "hookrightarrow" to "↪", "hookleftarrow" to "↩",
            "rightharpoonup" to "⇀", "rightharpoondown" to "⇁", "leftharpoonup" to "↼",
            "leftharpoondown" to "↽", "rightleftharpoons" to "⇌", "leftrightharpoons" to "⇋",
            "rightleftarrows" to "⇄", "leftrightarrows" to "⇆", "rightrightarrows" to "⇉",
            "leftleftarrows" to "⇇", "curvearrowright" to "↷", "curvearrowleft" to "↶",
            "nrightarrow" to "↛", "nleftarrow" to "↚", "nRightarrow" to "⇏", "nLeftarrow" to "⇍",
        ).forEach { (name, glyph) -> put(name, rel(glyph)) }

        // Ordinary symbols, delimiters and punctuation.
        listOf(
            "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃",
            "nexists" to "∄", "emptyset" to "∅", "varnothing" to "∅", "neg" to "¬",
            "lnot" to "¬", "angle" to "∠", "measuredangle" to "∡", "triangle" to "△",
            "square" to "□", "Box" to "□", "blacksquare" to "■", "surd" to "√",
            "checkmark" to "✓", "hbar" to "ℏ", "hslash" to "ℏ", "ell" to "ℓ", "imath" to "ı",
            "jmath" to "ȷ", "aleph" to "ℵ", "beth" to "ℶ", "gimel" to "ℷ", "daleth" to "ℸ",
            "wp" to "℘", "Re" to "ℜ", "Im" to "ℑ", "prime" to "′", "backprime" to "‵",
            "degree" to "°", "textdegree" to "°", "S" to "§", "P" to "¶", "copyright" to "©",
            "pounds" to "£", "clubsuit" to "♣", "diamondsuit" to "♢", "heartsuit" to "♡",
            "spadesuit" to "♠", "flat" to "♭", "natural" to "♮", "sharp" to "♯",
            "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
            "dotsb" to "⋯", "dotsc" to "…", "dotsi" to "⋯", "dotsm" to "⋯", "dotso" to "…",
            "ldotp" to ".", "lbrace" to "{", "rbrace" to "}", "lbrack" to "[",
            "rbrack" to "]", "lparen" to "(", "rparen" to ")", "langle" to "⟨",
            "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉", "lfloor" to "⌊",
            "rfloor" to "⌋", "lvert" to "|", "rvert" to "|", "lVert" to "‖", "rVert" to "‖",
            "vert" to "|", "Vert" to "‖", "backslash" to "\\", "slash" to "/",
            "lmoustache" to "⎰", "rmoustache" to "⎱", "lgroup" to "⟮", "rgroup" to "⟯",
        ).forEach { (name, glyph) ->
            val kind = when (name) {
                "lbrace", "lbrack", "lparen", "langle", "lceil", "lfloor", "lgroup" -> AtomKind.Open
                "rbrace", "rbrack", "rparen", "rangle", "rceil", "rfloor", "rgroup" -> AtomKind.Close
                else -> AtomKind.Ord
            }
            put(name, Sym(glyph, kind))
        }
        put("colon", punct(":"))
        put("lparen", open("("))
        put("rparen", close(")"))
        put("langle", open("⟨"))
        put("rangle", close("⟩"))
        put("lceil", open("⌈"))
        put("rceil", close("⌉"))
        put("lfloor", open("⌊"))
        put("rfloor", close("⌋"))
        put("lbrace", open("{"))
        put("rbrace", close("}"))
        put("lbrack", open("["))
        put("rbrack", close("]"))
    }

    /** Large operators; the limits mode says whether `_`/`^` go under/over in display style. */
    val bigOperators: Map<String, BigOpDef> = buildMap {
        listOf("sum", "prod", "coprod", "bigcup", "bigcap", "bigsqcup", "bigvee",
            "bigwedge", "bigodot", "bigotimes", "bigoplus", "biguplus").forEach {
            put(it, BigOpDef(bigOperatorGlyph(it), LimitsMode.Auto))
        }
        listOf("int", "iint", "iiint", "iiiint", "oint", "oiint", "oiiint", "smallint").forEach {
            put(it, BigOpDef(bigOperatorGlyph(it), LimitsMode.Never))
        }
    }

    private fun bigOperatorGlyph(name: String) = when (name) {
        "sum" -> "∑"; "prod" -> "∏"; "coprod" -> "∐"; "bigcup" -> "⋃"; "bigcap" -> "⋂"
        "bigsqcup" -> "⨆"; "bigvee" -> "⋁"; "bigwedge" -> "⋀"; "bigodot" -> "⨀"
        "bigotimes" -> "⨂"; "bigoplus" -> "⨁"; "biguplus" -> "⨄"; "int" -> "∫"
        "iint" -> "∬"; "iiint" -> "∭"; "iiiint" -> "⨌"; "oint" -> "∮"; "oiint" -> "∯"
        "oiiint" -> "∰"; "smallint" -> "∫"; else -> name
    }

    /**
     * Named operators with upright lettering. `Auto` matches LaTeX/amsmath for `\lim`, `\max`,
     * `\Pr`, … whose subscripts drop below in display style; the trig/log functions never do.
     */
    val operatorNames: Map<String, LimitsMode> = buildMap {
        listOf("lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "gcd", "hom",
            "ker", "arg", "Pr", "dim", "deg").forEach { put(it, LimitsMode.Auto) }
        listOf("sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
            "arccot", "sinh", "cosh", "tanh", "coth", "log", "ln", "lg", "exp").forEach {
            put(it, LimitsMode.Never)
        }
    }

    /** Display text for the named operators whose spelled-out form differs from the command. */
    val operatorDisplay: Map<String, String> = mapOf(
        "limsup" to "lim sup", "liminf" to "lim inf",
    )

    /** Delimiters accepted after `\left`, `\right`, `\middle` and the `\big…` family. */
    val delimiters: Map<String, String> = mapOf(
        "." to "", "(" to "(", ")" to ")", "[" to "[", "]" to "]", "{" to "{", "}" to "}",
        "|" to "|", "/" to "/", "<" to "⟨", ">" to "⟩",
        "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉",
        "lfloor" to "⌊", "rfloor" to "⌋", "lbrace" to "{", "rbrace" to "}",
        "lbrack" to "[", "rbrack" to "]", "lparen" to "(", "rparen" to ")",
        "vert" to "|", "Vert" to "‖", "lvert" to "|", "rvert" to "|", "lVert" to "‖",
        "rVert" to "‖", "backslash" to "\\", "slash" to "/", "uparrow" to "↑",
        "downarrow" to "↓", "updownarrow" to "↕", "Uparrow" to "⇑", "Downarrow" to "⇓",
        "Updownarrow" to "⇕", "lmoustache" to "⎰", "rmoustache" to "⎱", "lgroup" to "⟮",
        "rgroup" to "⟯", "arrowvert" to "|", "Arrowvert" to "‖",
    )

    /** Accent commands → the mark drawn above the base. */
    val accents: Map<String, String> = mapOf(
        "hat" to "^", "widehat" to "^", "tilde" to "~", "widetilde" to "~", "bar" to "‾",
        "vec" to "→", "dot" to "˙", "ddot" to "¨", "dddot" to "⃛", "check" to "ˇ",
        "breve" to "˘", "acute" to "´", "grave" to "`", "mathring" to "˚",
    )

    /** Colour names accepted by `\color`/`\textcolor`. */
    val colors: Map<String, Long> = mapOf(
        "black" to 0xFF000000L, "white" to 0xFFFFFFFFL, "red" to 0xFFE53935L,
        "green" to 0xFF2E7D32L, "blue" to 0xFF1565C0L, "cyan" to 0xFF00BCD4L,
        "magenta" to 0xFFD81B60L, "yellow" to 0xFFF9A825L, "orange" to 0xFFEF6C00L,
        "purple" to 0xFF6A1B9AL, "brown" to 0xFF6D4C41L, "gray" to 0xFF757575L,
        "grey" to 0xFF757575L, "lime" to 0xFF9E9D24L, "olive" to 0xFF827717L,
        "teal" to 0xFF00897BL, "navy" to 0xFF1A237EL, "pink" to 0xFFEC407AL,
        "violet" to 0xFF7E57C2L, "gold" to 0xFFFFB300L, "silver" to 0xFFBDBDBDL,
    )

    /** `\big(` scaling factors, indexed by the command name. */
    val delimiterSizes: Map<String, Float> = mapOf(
        "big" to 1.2f, "bigl" to 1.2f, "bigr" to 1.2f, "bigm" to 1.2f, "Big" to 1.5f,
        "Bigl" to 1.5f, "Bigr" to 1.5f, "Bigm" to 1.5f, "bigg" to 1.9f, "biggl" to 1.9f,
        "biggr" to 1.9f, "biggm" to 1.9f, "Bigg" to 2.4f, "Biggl" to 2.4f, "Biggr" to 2.4f,
        "Biggm" to 2.4f,
    )
}
