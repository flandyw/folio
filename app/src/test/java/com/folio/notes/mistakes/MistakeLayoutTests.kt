package com.folio.notes.mistakes

import org.junit.Assert.*
import org.junit.Test

class MistakeLayoutTests {
    @Test fun tabletRotationChangesBothBrowsingAndWritingLayout() {
        assertEquals(MistakeLayout(2, false, false), mistakeLayout(800, 1200))
        assertEquals(MistakeLayout(3, true, true), mistakeLayout(1200, 800))
    }
    @Test fun largePortraitTabletKeepsAFullWidthWritingCanvas() {
        assertEquals(MistakeLayout(2, false, false), mistakeLayout(1200, 1600))
    }
    @Test fun narrowMultiWindowDoesNotSqueezeTwoPanesTogether() {
        assertEquals(MistakeLayout(1, false, false), mistakeLayout(599, 900))
        assertEquals(MistakeLayout(2, false, false), mistakeLayout(700, 900))
    }
    @Test fun smallerLandscapeWindowSplitsReviewOnlyWhenItFits() {
        assertEquals(MistakeLayout(2, false, false), mistakeLayout(800, 600))
        assertEquals(MistakeLayout(2, false, true), mistakeLayout(900, 600))
    }
}
