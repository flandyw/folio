package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class WritingFollowTests {
    @Test fun comfortPreservesPersonalChoicesAndReportsCustomChanges() {
        val original = FollowPreferences(direction = WritingDirection.RTL, mode = FollowMode.MATH,
            automaticReturn = true, position = .4f, horizontalFollow = false, autoSwitchAreas = false)
        val balanced = FollowComfort.apply(original, .5f)
        assertEquals(original.direction, balanced.direction)
        assertEquals(original.mode, balanced.mode)
        assertEquals(original.automaticReturn, balanced.automaticReturn)
        assertEquals(original.position, balanced.position)
        assertFalse(balanced.horizontalFollow)
        assertFalse(balanced.autoSwitchAreas)
        assertTrue(FollowComfort.matches(balanced, .5f))
        assertFalse(FollowComfort.matches(balanced.copy(glideDurationMs = 700), .5f))
        val relaxed = FollowComfort.apply(original, 0f)
        val responsive = FollowComfort.apply(original, 1f)
        assertTrue(relaxed.returnDelayMs > responsive.returnDelayMs)
        assertTrue(relaxed.glideDurationMs > responsive.glideDurationMs)
        assertTrue(relaxed.edgeThreshold > responsive.edgeThreshold)
        assertTrue(relaxed.verticalDeadBand > responsive.verticalDeadBand)
    }

    @Test fun faintDashedRulesKeepColumnsSeparate() {
        val width = 500
        val pixels = IntArray(width * 200) { -1 }
        for (y in listOf(50, 78, 106)) {
            for (range in listOf(30..220, 280..470)) {
                for (x in range) if ((x - range.first) % 10 < 6) pixels[y * width + x] = 0xffcccccc.toInt()
            }
        }
        val guides = WritingGuides.detect(pixels, width, 200, 500f, 200f)
        assertEquals(6, guides.size)
        val regions = WritingGuides.regions(guides)
        assertEquals(2, regions.size)
        assertNull(WritingGuides.regionAt(regions, 250f, 78f))
    }

    @Test fun tableBordersAndIsolatedUnderlinesAreRejected() {
        val width = 300
        val pixels = IntArray(width * 200) { -1 }
        for (y in listOf(40, 68, 96)) for (x in 20..250) pixels[y * width + x] = 0xff000000.toInt()
        for (y in 40..96) for (x in listOf(20, 250)) pixels[y * width + x] = 0xff000000.toInt()
        for (x in 20..250) pixels[170 * width + x] = 0xff000000.toInt()
        assertTrue(WritingGuides.detect(pixels, width, 200, 300f, 200f).isEmpty())
    }

    @Test fun dotsAndInvalidCoordinatesDoNotChangeLane() {
        val follow = WritingFollow()
        follow.completed(listOf(InkPoint(20f, 80f), InkPoint(30f, 100f)), 1)
        val before = follow.state
        follow.completed(listOf(InkPoint(70f, 90f), InkPoint(71f, 91f)), 2)
        assertEquals(before, follow.state)
        follow.completed(listOf(InkPoint(Float.NaN, 100f)), 3)
        assertEquals(before, follow.state)
        assertFalse(follow.progresses(listOf(InkPoint(Float.NaN, 100f)), WritingDirection.LTR))
    }

    @Test fun triggerMirrorsDirectionAndFixedTimingRespectsChoice() {
        val follow = WritingFollow()
        assertEquals(300, follow.sameLineDelayMs(300, adaptive = false))
        assertEquals(0f, follow.horizontalShift(.8f, .5f, WritingDirection.LTR, .9f))
        val left = follow.horizontalShift(.8f, .5f, WritingDirection.LTR, .7f)
        val right = follow.horizontalShift(.2f, .5f, WritingDirection.RTL, .7f)
        assertTrue(left < 0f)
        assertEquals(-left, right, .0001f)
    }
}
