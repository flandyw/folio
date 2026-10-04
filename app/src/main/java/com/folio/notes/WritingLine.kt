package com.folio.notes

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** An axis-aligned box in page units. */
data class InkBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) * .5f
    val centerY get() = (top + bottom) * .5f
    fun intersects(o: InkBox) = left <= o.right && right >= o.left && top <= o.bottom && bottom >= o.top

    companion object {
        fun of(points: List<InkPoint>): InkBox? {
            if (points.isEmpty()) return null
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            for (p in points) {
                if (!p.x.isFinite() || !p.y.isFinite()) return null
                l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
            }
            return InkBox(l, t, r, b)
        }
    }
}

/**
 * One finished pen stroke as the line reader sees it. [straight] marks a long flat stroke drawn in
 * one direction: an underline, a strike-through or a fraction bar, never a letter.
 */
data class InkMark(val box: InkBox, val straight: Boolean) {
    companion object {
        fun of(points: List<InkPoint>): InkMark? {
            val box = InkBox.of(points) ?: return null
            var travel = 0f
            for (i in 1..points.lastIndex) travel += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            val direct = hypot(points.last().x - points.first().x, points.last().y - points.first().y)
            return InkMark(box, travel > 0f && direct >= travel * .9f && box.width >= box.height * 4f)
        }
    }
}

/** User intent is independent of the hand holding the pen. */
enum class WritingDirection { LTR, RTL }
enum class WritingHand { RIGHT, LEFT }
/** Text follows along a line; maths only reveals room below growing working. */
enum class FollowMode { TEXT, MATH }

/**
 * A line of handwriting, measured from the ink that is on the page right now.
 *
 * Nothing here is remembered from earlier strokes, so undo, erasing, moved ink and corrections can
 * never leave the follow engine believing in writing that no longer exists.
 */
data class WritingLine(
    /** Where letter bodies sit: a printed rule, or the densest run of stroke bottoms. */
    val baseline: Float,
    /** Typical stroke height on this line; every threshold scales with it. */
    val body: Float,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    /** Distance to the next line: printed spacing, the measured gap to the line above, or a guess. */
    val pitch: Float,
    /** The printed rule this line sits on, if any. */
    val guide: WritingGuide? = null,
    /** Strokes that make up the line; zero for a line placed by Next line and not yet written. */
    val strokes: Int = 0,
    /** True when [pitch] was measured rather than guessed. */
    val measuredPitch: Boolean = false,
    /** Rise of unruled handwriting: baseline change per page unit across, positive when it sinks. */
    val slope: Float = 0f,
    /** Text only: the room one more word needs, from this line's own words (a typical word plus its gap). */
    val wordWidth: Float? = null,
    /** Maths only: where the whole row of working starts, which a block touching the stroke may not reach. */
    val rowLeft: Float? = null,
    /** Maths only: the left edge of the first `=` in the row, which the next row may line up under. */
    val equalsAt: Float? = null,
) {
    /** Where the baseline sits at [x]; [baseline] itself is read at the middle of the line. */
    fun baselineAt(x: Float) = baseline + slope * (x - (left + right) * .5f)
    fun start(direction: WritingDirection) = if (direction == WritingDirection.LTR) left else right
    fun frontier(direction: WritingDirection) = if (direction == WritingDirection.LTR) right else left
}

/** What a finished stroke turned out to be. */
sealed interface LineRead {
    data class Line(val line: WritingLine) : LineRead
    /** A dot, comma or crossbar: too small to say anything about the line. */
    data object Minor : LineRead
    /** An underline or strike-through. */
    data object Rule : LineRead
    /** Much taller than the writing around it: a diagram, bracket or arrow. */
    data object Tall : LineRead
}

object LineReader {
    /** How far apart two words on one line may be; wider gaps separate columns. */
    private const val WORD_GAP = 4f
    /** The steepest tilt of a line of writing (about 19 degrees); anything steeper is a diagram. */
    private const val MAX_SLOPE = .35f

    /** The window of ink worth looking at around a stroke. */
    fun window(seed: InkBox, scale: Float): InkBox {
        val s = max(scale, 2f)
        return InkBox(seed.left - s * 40f, seed.top - s * 7f, seed.right + s * 40f, seed.bottom + s * 3f)
    }

    /**
     * Reads the line [seed] belongs to from [marks] (the ink near it, which may or may not include
     * the seed itself). [bodyFloor] is the last known body size so a lone dot can be recognised.
     */
    fun read(seed: InkMark, marks: List<InkMark>, guides: List<WritingGuide>, mode: FollowMode,
             bodyFloor: Float = 0f, pitchHint: Float? = null): LineRead {
        val all = if (marks.any { it === seed || it == seed }) marks else marks + seed
        val box = seed.box
        // Body size from the seed's neighbourhood, never from history: the ink nearby is the truth.
        val reach = max(max(box.height, box.width / 3f), max(bodyFloor, 2f))
        val near = InkBox(box.left - reach * 8f, box.top - reach * 1.5f, box.right + reach * 8f, box.bottom + reach * 1.5f)
        val body0 = upperMedian(all.filter { !it.straight && it.box.intersects(near) }.map { it.box.height })
            ?.coerceAtLeast(2f) ?: max(box.height, 2f)
        if (box.height < body0 * .4f && box.width < body0 * 1.5f) return LineRead.Minor
        if (mode == FollowMode.TEXT && seed.straight && box.width >= body0 * 3f) return LineRead.Rule
        if (box.height > body0 * 3.2f) return LineRead.Tall
        return LineRead.Line(if (mode == FollowMode.MATH) readWorking(seed, all, guides, body0, pitchHint)
            else readText(seed, all, guides, body0, pitchHint))
    }

    /**
     * Roughly where a stroke's body sits. A third of the way down is above every descender and below
     * every ascender, so 'g', 'f' and a capital all land on the same line as an 'a' beside them.
     */
    private fun probe(b: InkBox) = b.top + b.height * .3f

    private fun readText(seed: InkMark, all: List<InkMark>, guides: List<WritingGuide>, body0: Float, pitchHint: Float?): WritingLine {
        val guide = guideFor(seed.box, guides)
        var body = body0
        var lineProbe = probe(seed.box)
        var slope = 0f
        var anchor = seed.box.centerX
        var members = listOf(seed.box)
        fun usable(m: InkMark, b: Float) = !m.straight && m.box.height <= b * 3.2f &&
            !(m.box.height < b * .4f && m.box.width < b * 1.5f)
        // A few passes: the first estimate settles the line height and tilt, the next re-collects with them,
        // and each one can reach further along a line that climbs or sinks.
        repeat(if (guide != null) 1 else 5) {
            val pitch = pitchHint ?: (body * 2.2f)
            val onLine = all.filter { m ->
                usable(m, body) && if (guide != null) guideFor(m.box, guides) == guide
                    else abs(probe(m.box) - (lineProbe + slope * (m.box.centerX - anchor))) <= pitch * .45f
            }.map { it.box }
            members = chain(seed.box, onLine, body)
            body = upperMedian(members.map { it.height })?.coerceAtLeast(2f) ?: body
            if (guide == null) {
                val trend = trend(members, body)
                slope = trend.first; anchor = trend.second; lineProbe = trend.third
            }
        }
        val left = members.minOf { it.left }
        val right = members.maxOf { it.right }
        val middle = (left + right) * .5f
        val baseline = guide?.y ?: densestBottom(members, body, slope, middle)
        val spacing = guide?.let { WritingGuides.spacing(it, guides) }
        val above = if (guide == null) lineAbove(all, left, right, lineProbe, anchor, slope, body, pitchHint ?: body * 2.2f) else null
        val pitch = spacing ?: above?.let { lineProbe - it } ?: pitchHint ?: (body * 2.2f)
        return WritingLine(baseline, body, left, right, members.minOf { it.top }, members.maxOf { it.bottom },
            pitch, guide, members.size, measuredPitch = spacing != null || above != null, slope = slope,
            wordWidth = wordSpan(members, body))
    }

    /**
     * The room one more word needs on this line, measured from its own words: a typical (three
     * quarters) word width plus the gap writers leave between words. Letter gaps are far smaller than
     * word gaps, so anything wider than about half a letter height (or well above the usual gap) is
     * a word break. Null while the line has too few words, or none can be told apart, to say.
     */
    internal fun wordSpan(members: List<InkBox>, body: Float): Float? {
        if (members.size < 6) return null
        val sorted = members.sortedBy { it.left }
        val gaps = ArrayList<Float>(sorted.size)
        var right = sorted.first().right
        for (b in sorted.drop(1)) { gaps += max(0f, b.left - right); right = max(right, b.right) }
        val threshold = max(body * .5f, (median(gaps) ?: 0f) * 2.5f)
        val widths = ArrayList<Float>()
        val breaks = ArrayList<Float>()
        var start = sorted.first().left
        right = sorted.first().right
        for ((i, b) in sorted.drop(1).withIndex()) {
            if (gaps[i] > threshold) { widths += right - start; breaks += gaps[i]; start = b.left }
            right = max(right, b.right)
        }
        widths += right - start
        // The ends are a word still being written and whatever started the line: judge the words between.
        val whole = if (widths.size >= 5) widths.subList(1, widths.size - 1) else widths
        if (breaks.size < 2) return null
        val typical = whole.sorted()[((whole.size - 1) * .75f).toInt()]
        return typical + (median(breaks) ?: threshold)
    }

    /**
     * The left edge of the first `=` among [boxes]: two short flat bars lying one above the other,
     * about as wide as each other and about half a letter height apart.
     */
    internal fun equalsAt(boxes: List<InkBox>, body: Float): Float? {
        val bars = boxes.filter { it.width in body * .45f..body * 2.6f && it.height <= it.width * .45f }
        var found: Float? = null
        for (a in bars) for (b in bars) {
            if (a === b || a.top >= b.top) continue
            val overlap = min(a.right, b.right) - max(a.left, b.left)
            val centres = (b.top + b.bottom - a.top - a.bottom) * .5f
            if (overlap < min(a.width, b.width) * .6f || centres !in body * .18f..body * .95f) continue
            if (max(a.width, b.width) > min(a.width, b.width) * 1.8f) continue
            val left = min(a.left, b.left)
            if (found == null || left < found) found = left
        }
        return found
    }

    /**
     * Whether [marks] hold the shape of written maths: an `=`, or a fraction bar with writing above
     * and below it. Text has neither, which is what makes this a useful hint that the writer is in
     * the wrong mode.
     */
    internal fun mathEvidence(marks: List<InkMark>, body: Float): Boolean {
        val boxes = marks.map { it.box }.take(120)
        if (equalsAt(boxes, body) != null) return true
        for (bar in boxes) {
            if (bar.width < body * 1.2f || bar.height > bar.width * .3f) continue
            val above = boxes.any { it !== bar && it.height > bar.height * 1.5f && it.centerX in bar.left..bar.right &&
                it.bottom in (bar.top - body * 2.2f)..(bar.bottom + body * .3f) }
            val below = boxes.any { it !== bar && it.height > bar.height * 1.5f && it.centerX in bar.left..bar.right &&
                it.top in (bar.top - body * .3f)..(bar.bottom + body * 2.2f) }
            if (above && below) return true
        }
        return false
    }

    /**
     * Handwriting on blank paper is rarely level. Fits a straight line through where the strokes sit
     * (slope, the x it is read at, and its probe height there). A tilt under half a letter height across
     * the whole line is noise, so level writing stays exactly level.
     */
    private fun trend(members: List<InkBox>, body: Float): Triple<Float, Float, Float> {
        val xs = members.map { it.centerX }
        val ys = members.map(::probe)
        val meanX = xs.average().toFloat()
        val meanY = ys.average().toFloat()
        val span = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
        var cov = 0f
        var variance = 0f
        for (i in xs.indices) { cov += (xs[i] - meanX) * (ys[i] - meanY); variance += (xs[i] - meanX) * (xs[i] - meanX) }
        val fitted = if (members.size >= 4 && variance > 0f) (cov / variance).coerceIn(-MAX_SLOPE, MAX_SLOPE) else 0f
        if (abs(fitted) * span < body * .5f) return Triple(0f, meanX, median(ys) ?: meanY)
        return Triple(fitted, meanX, meanY)
    }

    /**
     * Maths working is two-dimensional: numerator, bar and denominator belong together even though
     * they are not on one baseline. Everything touching the seed (with a little slack) is one block,
     * and the block's bottom is what has to stay in view.
     */
    private fun readWorking(seed: InkMark, all: List<InkMark>, guides: List<WritingGuide>, body: Float, pitchHint: Float?): WritingLine {
        val slack = body * .6f
        val block = mutableListOf(seed.box)
        val rest = all.map { it.box }.filter { it != seed.box }.toMutableList()
        var grown = true
        while (grown) {
            grown = false
            val iterator = rest.iterator()
            while (iterator.hasNext()) {
                val b = iterator.next()
                if (b.height > body * 3.2f) continue
                val wide = InkBox(b.left - slack, b.top - slack, b.right + slack, b.bottom + slack)
                if (block.any { it.intersects(wide) }) { block += b; iterator.remove(); grown = true }
            }
        }
        val bottom = block.maxOf { it.bottom }
        val guide = guides.filter { seed.box.centerX in (it.left - 6f)..(it.right + 6f) && abs(it.y - bottom) <= body * .6f }
            .minByOrNull { abs(it.y - bottom) }
        val pitch = guide?.let { WritingGuides.spacing(it, guides) } ?: pitchHint ?: (body * 2.2f)
        // The whole row of working may be wider than the block touching this stroke: terms are spaced out.
        val row = row(block, rest, body)
        return WritingLine(guide?.y ?: bottom, body, block.minOf { it.left }, block.maxOf { it.right },
            block.minOf { it.top }, bottom, pitch, guide, block.size, measuredPitch = guide != null,
            rowLeft = row.minOf { it.left }, equalsAt = equalsAt(row, body))
    }

    /** Everything level with [block] and joined to it by word-sized gaps: the row of working it belongs to. */
    private fun row(block: List<InkBox>, others: List<InkBox>, body: Float): List<InkBox> {
        val top = block.minOf { it.top } - body * .3f
        val bottom = block.maxOf { it.bottom } + body * .3f
        var left = block.minOf { it.left }
        var right = block.maxOf { it.right }
        val gap = max(body * WORD_GAP, 8f)
        val level = others.filter { it.height <= body * 3.2f && it.bottom >= top && it.top <= bottom }
        val kept = block.toMutableList()
        var grown = true
        while (grown) {
            grown = false
            for (b in level) {
                if (b in kept) continue
                if (b.right >= left - gap && b.left <= right + gap) {
                    kept += b; left = min(left, b.left); right = max(right, b.right); grown = true
                }
            }
        }
        return kept
    }

    /**
     * The printed rule a stroke's body sits on: the nearest rule at or below its probe, within one
     * rule spacing, in the same column. A descender crossing the rule below still belongs above it.
     */
    fun guideFor(b: InkBox, guides: List<WritingGuide>): WritingGuide? {
        val p = probe(b)
        return guides.asSequence()
            .filter { b.centerX in (it.left - 6f)..(it.right + 6f) && it.y >= p - 1f }
            .minByOrNull { it.y }
            ?.takeIf { it.y - p <= (WritingGuides.spacing(it, guides) ?: if (it.block != null) 28f else 64f) }
    }

    /** Keeps only strokes joined to the seed by word-sized gaps, so a second column stays separate. */
    private fun chain(seed: InkBox, boxes: List<InkBox>, body: Float): List<InkBox> {
        val sorted = (boxes + seed).distinct().sortedBy { it.left }
        val gap = max(body * WORD_GAP, 8f)
        var left = seed.left
        var right = seed.right
        val kept = mutableListOf(seed)
        var changed = true
        while (changed) {
            changed = false
            for (b in sorted) {
                if (b in kept) continue
                if (b.right >= left - gap && b.left <= right + gap) {
                    kept += b; left = min(left, b.left); right = max(right, b.right); changed = true
                }
            }
        }
        return kept
    }

    /**
     * Letter bodies share a bottom; descenders each end somewhere different. The tightest cluster of
     * bottoms is therefore the baseline, even when the first letter or most letters descend.
     */
    private fun densestBottom(members: List<InkBox>, body: Float, slope: Float = 0f, at: Float = 0f): Float {
        // Bottoms are compared as if the line were level, then read back at [at] along its tilt.
        val bottoms = members.map { it.bottom - slope * (it.centerX - at) }
        val tolerance = max(body * .2f, 1f)
        var best = bottoms.first()
        var bestCount = 0
        for (b in bottoms) {
            val count = bottoms.count { abs(it - b) <= tolerance }
            if (count > bestCount || (count == bestCount && b < best)) { best = b; bestCount = count }
        }
        return median(bottoms.filter { abs(it - best) <= tolerance }) ?: best
    }

    /** The probe of the nearest line of writing above, overlapping this one, if it is a plausible line gap. */
    private fun lineAbove(all: List<InkMark>, left: Float, right: Float, lineProbe: Float, anchor: Float, slope: Float,
                          body: Float, pitch: Float): Float? {
        // Compared along this line's own tilt, so the far end of a sinking line is not "the line above".
        fun level(b: InkBox) = probe(b) - slope * (b.centerX - anchor)
        val candidates = all.filter { m ->
            val b = m.box
            !m.straight && b.height <= body * 3.2f && b.right >= left && b.left <= right &&
                level(b) < lineProbe - pitch * .45f && level(b) > lineProbe - body * 6f
        }
        val nearest = candidates.maxOfOrNull { level(it.box) } ?: return null
        val line = median(candidates.map { level(it.box) }.filter { it >= nearest - pitch * .45f }) ?: return null
        return line.takeIf { lineProbe - it in (body * 1.2f)..(body * 6f) }
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    private fun upperMedian(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
