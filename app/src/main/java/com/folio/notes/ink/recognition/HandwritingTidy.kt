package com.folio.notes.ink.recognition

import com.folio.notes.model.InkPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * A gentle clean-up for handwriting that has been lasso-selected. Strokes are grouped into lines of
 * writing; a drifting line is levelled by sliding each stroke straight up or down (the letters keep
 * their own slant and size), and uneven gaps between letters and between words are halfway evened out
 * by sliding clusters of strokes sideways. Nothing is redrawn or resized, only moved, so the result is
 * still the writer's hand. Pure: points in, points out, in the same order.
 */
internal object HandwritingTidy {
    data class Options(val levelLines: Boolean = true, val evenSpacing: Boolean = true)
    class Result(val strokes: List<List<InkPoint>>, val lines: Int, val moved: Int) {
        val changed: Boolean get() = moved > 0
    }

    /** The steepest drift (about 10°) a line may be levelled by; steeper is more likely a drawn diagonal. */
    private val MAX_SLOPE = tan(Math.toRadians(10.0)).toFloat()
    private val MIN_SLOPE = tan(Math.toRadians(.6)).toFloat()
    private const val MIN_LINE_STROKES = 4
    private const val EVEN = .5f

    private class Box(val index: Int, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val cx get() = (minX + maxX) / 2f
        val cy get() = (minY + maxY) / 2f
        val width get() = maxX - minX
        val height get() = maxY - minY
    }

    private class Line {
        val members = ArrayList<Box>()
        var sumX = 0.0; var sumY = 0.0; var sumXX = 0.0; var sumXY = 0.0
        fun add(box: Box) {
            members += box
            val x = box.cx.toDouble(); val y = box.cy.toDouble()
            sumX += x; sumY += y; sumXX += x * x; sumXY += x * y
        }
        /** Where the line's trend puts a stroke centred at [x]; flat until enough strokes show a direction. */
        fun predict(x: Float, spread: Float): Float {
            val n = members.size
            val meanY = (sumY / n).toFloat()
            if (n < 3) return members.last().cy
            val varX = sumXX - sumX * sumX / n
            if (varX < spread * spread) return meanY
            val slope = ((sumXY - sumX * sumY / n) / varX).toFloat().coerceIn(-.3f, .3f)
            return meanY + slope * (x - (sumX / n).toFloat())
        }
        fun typicalHeight() = median(members.map { it.height })
    }

    fun tidy(strokes: List<List<InkPoint>>, options: Options = Options()): Result {
        val boxes = strokes.mapIndexedNotNull { index, points -> box(index, points) }
        val none = Result(strokes, 0, 0)
        if (boxes.size < MIN_LINE_STROKES) return none
        val typical = median(boxes.map { it.height }).coerceAtLeast(1f)
        // Underlines, boxes and long rules aren't letters: they neither belong to a line nor move with one.
        val writing = boxes.filter { it.height <= typical * 3f && it.width <= typical * 6f }
        if (writing.size < MIN_LINE_STROKES) return none

        // Dots, commas and accents sit away from the letters' middles: they join a line, never start one.
        val (marks, body) = writing.partition { it.height < typical * .3f && it.width < typical * .3f }
        val lines = ArrayList<Line>()
        for (box in body.sortedBy { it.minX }) {
            val line = nearest(lines, box, typical, 1f)
            (line ?: Line().also { lines += it }).add(box)
        }
        for (box in marks.sortedBy { it.minX }) nearest(lines, box, typical, 1.6f)?.add(box)

        val dx = FloatArray(strokes.size); val dy = FloatArray(strokes.size)
        var usable = 0
        for (line in lines) {
            if (line.members.size < MIN_LINE_STROKES) continue
            usable++
            if (options.levelLines) level(line, typical, dy)
            if (options.evenSpacing) space(line, typical, dx)
        }
        var moved = 0
        val result = strokes.mapIndexed { index, points ->
            if (abs(dx[index]) < .05f && abs(dy[index]) < .05f) points
            else { moved++; points.map { InkPoint(it.x + dx[index], it.y + dy[index], it.pressure) } }
        }
        return Result(result, usable, moved)
    }

    private fun nearest(lines: List<Line>, box: Box, typical: Float, reach: Float): Line? {
        var best: Line? = null; var bestDistance = Float.MAX_VALUE
        for (line in lines) {
            val band = max(line.typicalHeight(), typical * .6f) * .75f * reach
            val distance = abs(line.predict(box.cx, typical) - box.cy)
            if (distance <= band && distance < bestDistance) { best = line; bestDistance = distance }
        }
        return best
    }

    /** Theil-Sen slope of the stroke centres: the median of pairwise slopes, so an ascender or descender can't tilt the line. */
    private fun level(line: Line, typical: Float, dy: FloatArray) {
        val members = line.members
        val slopes = ArrayList<Float>()
        for (i in members.indices) for (j in i + 1 until members.size) {
            val run = members[j].cx - members[i].cx
            if (abs(run) >= typical * .5f) slopes += (members[j].cy - members[i].cy) / run
        }
        if (slopes.size < 3) return
        val slope = median(slopes)
        if (abs(slope) < MIN_SLOPE || abs(slope) > MAX_SLOPE) return
        val reference = members.map { it.cx }.average().toFloat()
        for (box in members) dy[box.index] += -slope * (box.cx - reference)
    }

    private class Cluster(var minX: Float, var maxX: Float) { val strokes = ArrayList<Box>() }

    private fun space(line: Line, typical: Float, dx: FloatArray) {
        val tolerance = max(line.typicalHeight(), typical * .6f) * .08f
        val clusters = ArrayList<Cluster>()
        for (box in line.members.sortedBy { it.minX }) {
            val last = clusters.lastOrNull()
            if (last != null && box.minX <= last.maxX + tolerance) {
                last.maxX = max(last.maxX, box.maxX); last.strokes += box
            } else clusters += Cluster(box.minX, box.maxX).also { it.strokes += box }
        }
        if (clusters.size < MIN_LINE_STROKES) return
        val gaps = (1 until clusters.size).map { clusters[it].minX - clusters[it - 1].maxX }
        val threshold = max(max(line.typicalHeight(), typical * .6f) * .4f, median(gaps) * 2f)
        val letterGaps = gaps.filter { it <= threshold }
        val wordGaps = gaps.filter { it > threshold }
        val letterTarget = if (letterGaps.size >= 2) median(letterGaps) else null
        val wordTarget = if (wordGaps.size >= 2) median(wordGaps) else null
        var shift = 0f
        for ((i, cluster) in clusters.withIndex()) {
            if (i > 0) {
                val gap = gaps[i - 1]
                val target = (if (gap > threshold) wordTarget else letterTarget) ?: gap
                shift += (target - gap) * EVEN
            }
            for (box in cluster.strokes) dx[box.index] += shift
        }
    }

    private fun box(index: Int, points: List<InkPoint>): Box? {
        if (points.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) return null
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        return Box(index, minX, minY, maxX, maxY)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
