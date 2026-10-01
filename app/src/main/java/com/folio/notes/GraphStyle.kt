package com.folio.notes

import kotlin.math.max
import kotlin.math.min

/** Where the axes cross inside the dragged box. */
enum class GraphOrigin { CENTRE, CORNER }

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
    /** Divisions per half axis. 0 draws bare axes; each division is one grid/tick interval. */
    val divisions: Int = 4,
    /** Units per division when numbers are on, so the labels read 1, 2, 3 or 5, 10, 15. */
    val step: Int = 1,
    val grid: Boolean = false,
    val ticks: Boolean = false,
    val numbers: Boolean = false,
    val letters: Boolean = false,
    val arrows: Boolean = true,
    /** Forces the dragged box square, which is how most exam graphs are drawn. */
    val square: Boolean = false
) {
    fun encode(): String = listOf(
        origin.name, divisions.toString(), step.toString(),
        if (grid) "1" else "0", if (ticks) "1" else "0",
        if (numbers) "1" else "0", if (letters) "1" else "0",
        if (arrows) "1" else "0", if (square) "1" else "0"
    ).joinToString("|")

    companion object {
        val DEFAULT = GraphStyle()
        /** Preference key the editor watches, so a change in the tool sheet applies to the page. */
        const val PREF_KEY = "graph.style"
        /** Divisions offered in the sheet; 0 is the bare-axes escape hatch. */
        val DIVISIONS = listOf(0, 2, 4, 6, 8)
        /** Steps offered in the sheet, in exam order. */
        val STEPS = listOf(1, 2, 5, 10)

        /**
         * Reads an encoded style, repairing anything a hand-edited or truncated preference leaves
         * out: unknown names, negative divisions and steps below one fall back to the default.
         */
        fun decode(raw: String?): GraphStyle {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.split('|')
            fun flag(at: Int, fallback: Boolean) = when (parts.getOrNull(at)) { "1" -> true; "0" -> false; else -> fallback }
            return GraphStyle(
                origin = runCatching { GraphOrigin.valueOf(parts.getOrNull(0).orEmpty()) }.getOrDefault(DEFAULT.origin),
                divisions = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..16 } ?: DEFAULT.divisions,
                step = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..1000 } ?: DEFAULT.step,
                grid = flag(3, DEFAULT.grid),
                ticks = flag(4, DEFAULT.ticks),
                numbers = flag(5, DEFAULT.numbers),
                letters = flag(6, DEFAULT.letters),
                arrows = flag(7, DEFAULT.arrows),
                square = flag(8, DEFAULT.square)
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
    fun origin(style: GraphStyle): Pair<Float, Float> = when (style.origin) {
        GraphOrigin.CENTRE -> Pair(left + width / 2f, top + height / 2f)
        GraphOrigin.CORNER -> Pair(left, bottom)
    }

    /** Half-axis distance to the far edge, i.e. one division is this / divisions. */
    fun halfX(style: GraphStyle): Float = when (style.origin) {
        GraphOrigin.CENTRE -> width / 2f
        GraphOrigin.CORNER -> width
    }

    fun halfY(style: GraphStyle): Float = when (style.origin) {
        GraphOrigin.CENTRE -> height / 2f
        GraphOrigin.CORNER -> height
    }

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

/** A tick label such as "-15", built from the step and the division index. */
internal fun graphLabel(value: Int): String = if (value < 0) "-" + (-value) else value.toString()
