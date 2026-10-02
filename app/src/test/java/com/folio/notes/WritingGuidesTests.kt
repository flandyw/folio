package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class WritingGuidesTests {
    @Test fun cachedNeighboursPreserveRegionsIncludingDuplicatesAndTies() {
        val random = Random(721)
        repeat(100) {
            val guides = List(30) {
                val left = random.nextInt(4) * 180f
                WritingGuide(left, left + random.nextInt(140, 180), random.nextInt(12) * 28f)
            }.let { it + it.take(5) }.shuffled(random)
            assertEquals(uncachedRegions(guides), WritingGuides.regions(guides))
        }
        val a = WritingGuide(0f, 150f, 40f)
        val b = WritingGuide(10f, 160f, 40f)
        val top = WritingGuide(0f, 150f, 12f)
        assertSame(b, WritingGuides.next(top, listOf(b, a, top)))
        assertSame(a, WritingGuides.next(top, listOf(a, b, top)))
        assertTrue(WritingGuides.regions(emptyList()).isEmpty())
        assertTrue(WritingGuides.regions(listOf(top, top)).isEmpty())
    }

    @Test fun regionNeighbourScansAreQuadraticRatherThanCubic() {
        val count = 100
        val guides = List(count) { WritingGuide(36f, 804f, it * 28f) }
        var reads = 0
        val counted = object : AbstractList<WritingGuide>() {
            override val size = guides.size
            override fun get(index: Int): WritingGuide { reads++; return guides[index] }
        }
        val expected = listOf(WritingLane(36f, 0f, 804f, 99 * 28f))
        assertEquals(expected, WritingGuides.regions(counted))
        val cachedReads = reads
        assertTrue("Expected at most 2N² input reads, got $reads", reads <= 2 * count * count)
        reads = 0
        assertEquals(expected, uncachedRegions(counted))
        assertTrue("The former edge scans re-read the input cubically", reads > count * count * count / 2)
        println("WritingGuides.regions: N=$count, cached reads=$cachedReads, former edge-discovery reads=$reads")
    }

    @Test fun detectionKeepsIncomingAndOutgoingRulesButRejectsIsolatedOnes() {
        val width = 300
        val height = 260
        val pixels = IntArray(width * height) { -1 }
        for (y in listOf(30, 58, 86, 170, 198, 240)) {
            for (x in 30..250) pixels[y * width + x] = 0xff000000.toInt()
        }
        // 240 is within 64pt of 198, so it is connected; 86 -> 170 is too far.
        val guides = WritingGuides.detect(pixels, width, height, width.toFloat(), height.toFloat())
        assertEquals(listOf(30f, 58f, 86f, 170f, 198f, 240f), guides.map { it.y })
        assertEquals(2, WritingGuides.regions(guides).size)
        val isolated = IntArray(width * height) { -1 }
        for (x in 30..250) isolated[130 * width + x] = 0xff000000.toInt()
        assertTrue(WritingGuides.detect(isolated, width, height, width.toFloat(), height.toFloat()).isEmpty())
    }

    /** The former edge discovery, kept as a parity oracle for the cached algorithm. */
    private fun uncachedRegions(guides: List<WritingGuide>): List<WritingLane> {
        val remaining = guides.sortedWith(compareBy({ it.y }, { it.left })).toMutableSet()
        val result = mutableListOf<WritingLane>()
        while (remaining.isNotEmpty()) {
            val group = mutableSetOf(remaining.first())
            val queue = java.util.ArrayDeque<WritingGuide>().apply { add(remaining.first()) }
            while (queue.isNotEmpty()) {
                val line = queue.removeFirst()
                remaining.remove(line)
                val neighbours = remaining.filter { WritingGuides.next(line, guides) == it || WritingGuides.next(it, guides) == line }
                for (neighbour in neighbours) if (group.add(neighbour)) queue.add(neighbour)
            }
            if (group.size >= 2) result += WritingLane(group.minOf { it.left },
                (group.minOf { it.y } - 28f).coerceAtLeast(0f), group.maxOf { it.right }, group.maxOf { it.y })
        }
        return result.sortedWith(compareBy({ it.top }, { it.left }))
    }
}
