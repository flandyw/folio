package com.folio.notes

import kotlin.math.max
import kotlin.math.min

/**
 * Where the axes cross inside the dragged box. [xBoth]/[yBoth] say whether each axis runs on both
 * sides of the crossing (negative values) or only the positive way, which is what decides arrows,
 * grid, ticks and where the numbers sit.
 */
enum class GraphOrigin(val xBoth: Boolean, val yBoth: Boolean, val label: String) {
    /** All four quadrants. */
    CENTRE(true, true, "Centre"),
    /** First quadrant only: x and y both start at the bottom-left corner. */
    CORNER(false, false, "Bottom left"),
    /** y ≥ 0 with x either side: parabolas, distributions, bar-style work. */
    BOTTOM(true, false, "Bottom centre"),
    /** x ≥ 0 with y either side: sin/cos, motion and decay graphs. */
    LEFT(false, true, "Left centre")
}

/**
 * How the graph tool dresses the axes it draws: where they cross, how many divisions each half
 * axis has, and whether ticks, unit numbers, the x/y letters and a grid come with them. Every
 * choice still lands as ordinary editable ink, so a labelled axis erases and re-styles like any
 * other pen work.
 *
 * Saved as one compact preference string so the editor and the tool sheet can each hold a copy and
 * stay in step without a shared holder.
 */
data class GraphStyle(
    val origin: GraphOrigin = GraphOrigin.CENTRE,
    /** Divisions per half axis (of the shorter one when [squareCells]). 0 draws bare axes. */
    val divisions: Int = 4,
    /** Units per division along x when numbers are on, so the labels read 1, 2, 3 or 0.5, 1, 1.5. */
    val step: Double = 1.0,
    /** Units per division along y; 0 means "same as x", the usual case. */
    val stepY: Double = 0.0,
    /** When above 0 the x labels count in multiples of π/[piDen] (π, 2π … or π/2, π, 3π/2 …). */
    val piDen: Int = 0,
    val grid: Boolean = false,
    val ticks: Boolean = false,
    val numbers: Boolean = false,
    val letters: Boolean = false,
    val arrows: Boolean = true,
    /** Forces the dragged box square, which is how most exam graphs are drawn. */
    val square: Boolean = false,
    /** Writes the conventional O where the axes cross. */
    val originLabel: Boolean = false,
    /**
     * Keeps every grid cell square whatever shape is dragged: [divisions] fits the shorter half
     * axis and the longer one simply gets more divisions. Off gives [divisions] on both.
     */
    val squareCells: Boolean = true
) {
    /** Units per division along y. */
    val yUnit: Double get() = if (stepY > 0.0) stepY else step

    fun encode(): String = listOf(
        origin.name, divisions.toString(), graphNumber(step),
        if (grid) "1" else "0", if (ticks) "1" else "0",
        if (numbers) "1" else "0", if (letters) "1" else "0",
        if (arrows) "1" else "0", if (square) "1" else "0",
        graphNumber(stepY), piDen.toString(),
        if (originLabel) "1" else "0", if (squareCells) "1" else "0"
    ).joinToString("|")

    companion object {
        val DEFAULT = GraphStyle()
        /** Preference key the editor watches, so a change in the tool sheet applies to the page. */
        const val PREF_KEY = "graph.style"
        /** Divisions offered in the sheet; 0 is the bare-axes escape hatch. */
        val DIVISIONS = listOf(0, 2, 3, 4, 5, 6, 8, 10)
        /** Steps offered in the sheet, in exam order. */
        val STEPS = listOf(0.1, 0.2, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
        /** x labels in multiples of π/n offered for trigonometry. */
        val PI_DENOMINATORS = listOf(1, 2, 3, 4, 6)

        /** One-tap setups for the graphs a maths student draws most. */
        val PRESETS: List<Pair<String, GraphStyle>> = listOf(
            "Four quadrants" to GraphStyle(divisions = 5, grid = true, ticks = true, numbers = true, letters = true, originLabel = true),
            "First quadrant" to GraphStyle(origin = GraphOrigin.CORNER, divisions = 6, grid = true, ticks = true, numbers = true, letters = true, originLabel = true),
            "Trig (π)" to GraphStyle(origin = GraphOrigin.LEFT, divisions = 4, step = 0.5, piDen = 2, ticks = true, numbers = true, letters = true, originLabel = true, squareCells = false),
            "y ≥ 0" to GraphStyle(origin = GraphOrigin.BOTTOM, divisions = 5, ticks = true, numbers = true, letters = true, originLabel = true),
            "Bare axes" to GraphStyle()
        )

        /**
         * Reads an encoded style, repairing anything a hand-edited or truncated preference leaves
         * out: unknown names, negative divisions and non-positive steps fall back to the default.
         * Strings written by older builds (nine fields, whole-number steps) read unchanged.
         */
        fun decode(raw: String?): GraphStyle {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.split('|')
            fun flag(at: Int, fallback: Boolean) = when (parts.getOrNull(at)) { "1" -> true; "0" -> false; else -> fallback }
            return GraphStyle(
                origin = runCatching { GraphOrigin.valueOf(parts.getOrNull(0).orEmpty()) }.getOrDefault(DEFAULT.origin),
                divisions = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..16 } ?: DEFAULT.divisions,
                step = parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.001..1000.0 } ?: DEFAULT.step,
                grid = flag(3, DEFAULT.grid),
                ticks = flag(4, DEFAULT.ticks),
                numbers = flag(5, DEFAULT.numbers),
                letters = flag(6, DEFAULT.letters),
                arrows = flag(7, DEFAULT.arrows),
                square = flag(8, DEFAULT.square),
                stepY = parts.getOrNull(9)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.001..1000.0 } ?: DEFAULT.stepY,
                piDen = parts.getOrNull(10)?.toIntOrNull()?.takeIf { it in 0..12 } ?: DEFAULT.piDen,
                originLabel = flag(11, DEFAULT.originLabel),
                squareCells = flag(12, DEFAULT.squareCells)
            )
        }

        fun load(prefs: android.content.SharedPreferences): GraphStyle = decode(prefs.getString(PREF_KEY, null))

        fun save(prefs: android.content.SharedPreferences, style: GraphStyle) {
            prefs.edit().putString(PREF_KEY, style.encode()).apply()
        }
    }
}

/**
 * A small single-stroke font for the labels the graph tool draws — digits, a minus sign and the
 * x/y letters. Everything is polylines in a unit cell (left edge 0, top 0, cap height 1), so the
 * page only ever has to scale it. Keeping it as ink rather than a font means the numbers select,
 * erase, recolour and export exactly like the axes they label.
 */
object GraphGlyphs {
    /** Glyph cell width and step: digits are drawn in a square cell, so one is one unit wide. */
    const val WIDTH = 1f
    const val ADVANCE = 1.2f
    /**
     * Each glyph is a list of polylines, and each polyline a flat run of x, y pairs — compact
     * enough to read as a picture of the digit in the source.
     */
    private val digits: Map<Char, List<List<Float>>> = mapOf(
        '0' to listOf(
            listOf(.5f, 0f, .85f, .14f, 1f, .5f, .85f, .86f, .5f, 1f, .15f, .86f, 0f, .5f, .15f, .14f, .5f, 0f)
        ),
        '1' to listOf(listOf(.15f, .22f, .5f, 0f, .5f, 1f)),
        '2' to listOf(listOf(.02f, .24f, .2f, .02f, .72f, 0f, .98f, .26f, .88f, .58f, 0f, 1f, 1f, 1f)),
        '3' to listOf(listOf(.02f, .04f, .7f, 0f, .42f, .44f, .88f, .5f, 1f, .78f, .74f, 1f, .12f, .98f)),
        '4' to listOf(listOf(.72f, 1f, .72f, 0f, 0f, .68f), listOf(.72f, 1f, 1f, 1f)),
        '5' to listOf(listOf(.95f, 0f, .12f, 0f, .06f, .44f, .62f, .38f, 1f, .6f, .9f, .93f, .45f, 1f, .06f, .9f)),
        '6' to listOf(listOf(.85f, .04f, .3f, .1f, .06f, .5f, .12f, .84f, .5f, 1f, .88f, .84f, .92f, .56f, .5f, .44f, .1f, .58f)),
        '7' to listOf(listOf(0f, 0f, 1f, 0f, .34f, 1f)),
        '8' to listOf(
            listOf(.45f, 0f, .14f, .16f, .2f, .38f, .45f, .46f, .72f, .38f, .8f, .14f, .45f, 0f),
            listOf(.45f, .46f, .1f, .62f, .12f, .86f, .45f, 1f, .84f, .86f, .86f, .62f, .45f, .46f)
        ),
        '9' to listOf(listOf(.12f, 1f, .72f, .9f, .95f, .55f, .85f, .16f, .45f, 0f, .1f, .2f, .1f, .42f, .5f, .56f, .88f, .42f)),
        '-' to listOf(listOf(.05f, .5f, .95f, .5f)),
        '.' to listOf(listOf(.38f, .88f, .62f, .88f, .62f, 1f, .38f, 1f, .38f, .88f)),
        '/' to listOf(listOf(.85f, -.05f, .15f, 1.05f)),
        'O' to listOf(
            listOf(.5f, 0f, .85f, .14f, 1f, .5f, .85f, .86f, .5f, 1f, .15f, .86f, 0f, .5f, .15f, .14f, .5f, 0f)
        ),
        'π' to listOf(listOf(0f, .14f, 1f, .14f), listOf(.3f, .14f, .24f, 1f), listOf(.7f, .14f, .74f, .85f, .92f, 1f)),
        'x' to listOf(listOf(.05f, .05f, .95f, .95f), listOf(.95f, .05f, .05f, .95f)),
        'y' to listOf(listOf(.02f, 0f, .5f, .62f), listOf(.98f, 0f, .5f, .62f), listOf(.5f, .62f, .12f, 1.3f))
    )

    /** Every character has a glyph except the sign, which is drawn as a single line. */
    fun supports(text: String): Boolean = text.all { it == '+' || it in digits }

    /** Ink polylines for [text], left edge at (0, 0) and cap height 1. */
    fun polylines(text: String): List<List<InkPoint>> {
        val out = mutableListOf<List<InkPoint>>()
        text.forEachIndexed { index, char ->
            val glyph = when (char) {
                '+' -> listOf(listOf(.1f, .5f, .9f, .5f), listOf(.5f, .1f, .5f, .9f))
                else -> digits[char] ?: return@forEachIndexed
            }
            val dx = index * ADVANCE
            glyph.forEach { flat ->
                out.add(flat.chunked(2).map { p -> InkPoint(p[0] + dx, p[1]) })
            }
        }
        return out
    }

    /** How wide [text] is in glyph units, so a label can be centred on its tick. */
    fun width(text: String): Float = if (text.isEmpty()) 0f else (text.length - 1) * ADVANCE + WIDTH
}

/**
 * The frame a graph tool drag defines: a rectangle in page coordinates. A square graph grows to
 * the shorter side, since almost every exam graph is drawn 1:1.
 */
data class GraphFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    /** Where the axes cross, given the chosen origin. */
    fun origin(style: GraphStyle): Pair<Float, Float> = Pair(
        if (style.origin.xBoth) left + width / 2f else left,
        if (style.origin.yBoth) top + height / 2f else bottom
    )

    /** Half-axis distance to the far edge along x. */
    fun halfX(style: GraphStyle): Float = if (style.origin.xBoth) width / 2f else width

    fun halfY(style: GraphStyle): Float = if (style.origin.yBoth) height / 2f else height

    companion object {
        /**
         * The frame a drag from [a] to [b] covers, or null when the drag is too small to hold
         * axes. Non-finite coordinates and a zero-width or zero-height box are refused, so a
         * stray tap can never scatter marks across the page.
         */
        fun of(a: InkPoint, b: InkPoint, style: GraphStyle): GraphFrame? {
            if (!listOf(a.x, a.y, b.x, b.y).all { it.isFinite() }) return null
            var left = min(a.x, b.x); var right = max(a.x, b.x)
            var top = min(a.y, b.y); var bottom = max(a.y, b.y)
            if (right - left <= 0f || bottom - top <= 0f) return null
            if (style.square) {
                // Square to the shorter side, growing into the drag direction so the corner the
                // user started from stays where they put it.
                val side = min(right - left, bottom - top)
                if (a.x <= b.x) right = left + side else left = right - side
                if (a.y <= b.y) bottom = top + side else top = bottom - side
            }
            return GraphFrame(left, top, right, bottom)
        }
    }
}

/** A tick label such as "-15" or "0.5": at most three decimals, trailing zeros dropped. */
internal fun graphNumber(value: Double): String {
    val rounded = Math.round(value * 1000.0) / 1000.0
    if (rounded == 0.0) return "0"
    val text = if (rounded == Math.rint(rounded)) rounded.toLong().toString()
    else java.math.BigDecimal(rounded).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return text
}

/** The x label for the [index]th division of a π/[den] axis, e.g. "3π/2", "-π", "2π". */
internal fun graphPiLabel(index: Int, den: Int): String {
    if (index == 0 || den <= 0) return "0"
    var num = kotlin.math.abs(index); var bottom = den
    var a = num; var b = bottom
    while (b != 0) { val t = a % b; a = b; b = t }
    num /= a; bottom /= a
    val top = if (num == 1) "π" else "${num}π"
    return (if (index < 0) "-" else "") + if (bottom == 1) top else "$top/$bottom"
}
