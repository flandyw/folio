package com.folio.notes

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Printed answer rules in page coordinates, independent of zoom and handwriting. */
data class WritingGuide(val left: Float, val right: Float, val y: Float)
data class WritingAdvance(val from: WritingGuide, val to: WritingGuide) {
    fun startX(hand: WritingHand) = if (hand == WritingHand.RIGHT) to.left else to.right
}

object WritingGuides {
    /** Partition all printed rules into separate answer areas, including adjacent columns. */
    fun regions(guides: List<WritingGuide>): List<WritingLane> {
        val remaining = guides.toMutableSet()
        val result = mutableListOf<WritingLane>()
        while (remaining.isNotEmpty()) {
            val group = mutableSetOf(remaining.first())
            val queue = java.util.ArrayDeque<WritingGuide>().apply { add(remaining.first()) }
            while (queue.isNotEmpty()) {
                val line = queue.removeFirst()
                remaining.remove(line)
                val neighbours = remaining.filter { next(line, guides) == it || next(it, guides) == line }
                for (neighbour in neighbours) if (group.add(neighbour)) queue.add(neighbour)
            }
            if (group.size >= 2) result += WritingLane(group.minOf { it.left },
                (group.minOf { it.y } - 28f).coerceAtLeast(0f), group.maxOf { it.right }, group.maxOf { it.y })
        }
        return result.sortedWith(compareBy({ it.top }, { it.left }))
    }

    fun regionAt(regions: List<WritingLane>, x: Float, y: Float): WritingLane? = regions
        .filter { x in it.left..it.right && y in it.top..it.bottom }
        .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }

    /** Only move within the same answer column, never across a question-sized gap. */
    fun next(line: WritingGuide, guides: List<WritingGuide>): WritingGuide? = guides
        .filter {
            val overlap = min(line.right, it.right) - max(line.left, it.left)
            it.y - line.y in 12f..64f && overlap >= min(line.right - line.left, it.right - it.left) * .8f &&
                abs(it.left - line.left) <= 32f && abs(it.right - line.right) <= 32f
        }.minByOrNull { it.y }

    fun ruled(width: Float, height: Float): List<WritingGuide> =
        generateSequence(70f) { it + 28f }.takeWhile { it < height }
            .map { WritingGuide(36f, width - 36f, it) }.toList()

    /**
     * Scan the unannotated PDF raster off the UI thread. Join tiny antialiasing gaps,
     * merge the rows of each thin rule, and reject solid bars and table borders.
     * Requiring a neighbouring aligned rule avoids treating isolated underlines as lanes.
     */
    fun detect(pixels: IntArray, width: Int, height: Int, pageWidth: Float, pageHeight: Float): List<WritingGuide> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val sx = pageWidth / width
        val sy = pageHeight / height
        val gap = ceil(2f / sx).toInt().coerceAtLeast(1)
        val minLength = max(60f, pageWidth * .12f) / sx
        fun dark(x: Int, y: Int): Boolean {
            if (x !in 0 until width || y !in 0 until height) return false
            val color = pixels[y * width + x]
            return (color ushr 24) >= 128 && ((color ushr 16) and 255) < 190 &&
                ((color ushr 8) and 255) < 190 && (color and 255) < 190
        }
        data class Band(var left: Int, var right: Int, val top: Int, var bottom: Int)
        val bands = mutableListOf<Band>()
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                if (!dark(x, y)) { x++; continue }
                val left = x
                var right = x
                var ink = 0
                while (x < width && x - right <= gap) {
                    if (dark(x, y)) { right = x; ink++ }
                    x++
                }
                if (right - left < minLength || ink.toFloat() / (right - left + 1) < .8f) continue
                val band = bands.lastOrNull { it.bottom == y - 1 && abs(it.left - left) <= gap * 2 && abs(it.right - right) <= gap * 2 }
                if (band == null) bands += Band(left, right, y, y)
                else { band.left = min(band.left, left); band.right = max(band.right, right); band.bottom = y }
            }
        }
        val candidates = bands.filter { band ->
            val thin = (band.bottom - band.top + 1) * sy <= 3.5f
            val reach = ceil(8f / sy).toInt().coerceAtLeast(3)
            fun verticalBorder(x: Int): Boolean = (-1..1).any { offset ->
                (1..reach).count { dark(x + offset, band.top - it) } >= reach * .8f ||
                    (1..reach).count { dark(x + offset, band.bottom + it) } >= reach * .8f
            }
            thin && !verticalBorder(band.left) && !verticalBorder(band.right)
        }.map { WritingGuide(it.left * sx, it.right * sx, (it.top + it.bottom) * .5f * sy) }
        return candidates.filter { line -> next(line, candidates) != null || candidates.any { next(it, candidates) == line } }
            .sortedWith(compareBy({ it.y }, { it.left }))
    }
}
