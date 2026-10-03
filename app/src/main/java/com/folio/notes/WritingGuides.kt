package com.folio.notes

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Printed answer rules in page coordinates; [block] indexes the detected areas, or is null on ruled paper. */
data class WritingGuide(val left: Float, val right: Float, val y: Float, val block: Int? = null)

/** The answer space inferred from a group of response lines, not a printed rectangle. */
data class AnswerArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val size get() = (right - left) * (bottom - top)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** What the page's printed background offers the follow engine. */
data class DetectedGuides(val guides: List<WritingGuide>, val areas: List<AnswerArea>)

object WritingGuides {
    /** The tightest response-line area around the writer. */
    fun areaAt(areas: List<AnswerArea>, x: Float, y: Float): AnswerArea? =
        areas.filter { it.contains(x, y) }.minByOrNull { it.size }

    /** Only move within the same answer column, never across a question-sized gap. */
    fun next(line: WritingGuide, guides: List<WritingGuide>): WritingGuide? = guides.asSequence()
        .filter { follows(line, it) }.minByOrNull { it.y }

    private fun follows(line: WritingGuide, next: WritingGuide): Boolean {
        val overlap = min(line.right, next.right) - max(line.left, next.left)
        return line.block == next.block && next.y - line.y in 12f..64f &&
            overlap >= min(line.right - line.left, next.right - next.left) * .8f &&
            abs(next.left - line.left) <= 32f && abs(next.right - line.right) <= 32f
    }

    fun spacing(line: WritingGuide, guides: List<WritingGuide>): Float? =
        next(line, guides)?.let { it.y - line.y }
            ?: guides.asSequence().filter { follows(it, line) }.minOfOrNull { line.y - it.y }

    fun ruled(width: Float, height: Float): List<WritingGuide> =
        generateSequence(70f) { it + 28f }.takeWhile { it < height }
            .map { WritingGuide(36f, width - 36f, it) }.toList()

    /**
     * Scan the unannotated PDF raster off the UI thread. Join dots, dashes and antialiasing gaps,
     * merge the rows of each thin rule, and reject text, solid bars and table borders.
     * Short rules need an aligned neighbour; a long isolated rule can be a one-line answer.
     */
    fun detect(pixels: IntArray, width: Int, height: Int, pageWidth: Float, pageHeight: Float): List<WritingGuide> =
        analyze(pixels, width, height, pageWidth, pageHeight).guides

    /** Group response lines into answer areas using alignment, spacing and clear space between them. */
    fun analyze(pixels: IntArray, width: Int, height: Int, pageWidth: Float, pageHeight: Float): DetectedGuides {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0 && pageHeight > 0)
        val sx = pageWidth / width
        val sy = pageHeight / height
        val gap = ceil(6f / sx).toInt().coerceAtLeast(1)
        val minLength = max(60f, pageWidth * .12f) / sx
        fun dark(x: Int, y: Int): Boolean {
            if (x !in 0 until width || y !in 0 until height) return false
            val color = pixels[y * width + x]
            val alpha = (color ushr 24) / 255f
            val luminance = .2126f * ((color ushr 16) and 255) +
                .7152f * ((color ushr 8) and 255) + .0722f * (color and 255)
            return 255f - alpha * (255f - luminance) < 225f
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
                if (right - left < minLength || ink.toFloat() / (right - left + 1) < .15f) continue
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
            // Text rows have ink above and below the putative rule. Require a
            // mostly clear strip so joining dash gaps cannot join printed words.
            val clearance = ceil(4f / sy).toInt().coerceAtLeast(2)
            val occupied = (band.left..band.right).count { x ->
                (2..clearance).any { dark(x, band.top - it) || dark(x, band.bottom + it) }
            }
            thin && occupied.toFloat() / (band.right - band.left + 1) < .18f && !verticalBorder(band.left) && !verticalBorder(band.right)
        }.map { WritingGuide(it.left * sx, it.right * sx, (it.top + it.bottom) * .5f * sy) }
        // Only inspect the shared column: content in a neighbouring column is irrelevant.
        fun clearBetween(above: WritingGuide, below: WritingGuide): Boolean {
            val left = ceil(max(above.left, below.left) / sx).toInt()
            val right = (min(above.right, below.right) / sx).toInt()
            val from = ceil((above.y + 4f) / sy).toInt()
            val to = ((below.y - 4f) / sy).toInt()
            val minInk = ceil(3f / sx).toInt().coerceAtLeast(2)
            val textHeight = ceil(2f / sy).toInt().coerceAtLeast(2)
            var occupiedRows = 0
            for (y in from..to) {
                var ink = 0
                for (x in left..right) {
                    if (dark(x, y) && ++ink >= minInk) break
                }
                occupiedRows = if (ink >= minInk) occupiedRows + 1 else 0
                if (occupiedRows >= textHeight) return false
            }
            return true
        }
        val ordered = candidates.sortedWith(compareBy({ it.y }, { it.left }))
        val following = ordered.associateWith { above ->
            next(above, ordered)?.takeIf { clearBetween(above, it) }
        }
        // Measure the local pitch at both ends. A larger gap between two regularly spaced
        // groups is a question break, even when it is inside the broad 12..64 rule range.
        val pitches = mutableMapOf<WritingGuide, Float>()
        for ((above, below) in following) {
            if (below == null) continue
            val pitch = below.y - above.y
            for (guide in listOf(above, below)) pitches[guide] = min(pitches[guide] ?: pitch, pitch)
        }
        val blocks = mutableListOf<MutableList<WritingGuide>>()
        for (guide in ordered) {
            val block = blocks.lastOrNull { group ->
                val previous = group.last()
                following[previous] == guide && guide.y - previous.y <=
                    min(pitches.getValue(previous), pitches.getValue(guide)) * 1.35f + 2f
            }
            if (block == null) blocks += mutableListOf(guide) else block += guide
        }
        val guides = mutableListOf<WritingGuide>()
        val areas = mutableListOf<AnswerArea>()
        for (block in blocks) {
            if (block.size == 1 && block.first().right - block.first().left < max(120f, pageWidth * .25f)) continue
            val pitch = block.zipWithNext { a, b -> b.y - a.y }.minOrNull() ?: 28f
            val id = areas.size
            guides += block.map { it.copy(block = id) }
            areas += AnswerArea(block.minOf { it.left }, max(0f, block.first().y - pitch),
                block.maxOf { it.right }, block.last().y)
        }
        return DetectedGuides(guides.sortedWith(compareBy({ it.y }, { it.left })), areas)
    }
}
