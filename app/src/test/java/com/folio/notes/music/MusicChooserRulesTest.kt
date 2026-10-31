package com.folio.notes.music

import org.junit.Assert.*
import org.junit.Test

class MusicChooserRulesTest {
    @Test fun addScoresCanUseTheSameCrossFieldSearchAsTheShelf() {
        val score = MusicScore("score", "Concerto", 4, composer = "Elgar", part = "Cello",
            notes = "Watch the conductor", marks = listOf(MusicMark(1, "Allegro")))
        assertTrue(score.matchesQuery("elgar cello"))
        assertTrue(score.matchesQuery("conductor allegro"))
        assertFalse(score.matchesQuery("elgar violin"))
    }

    @Test fun mergedPartsKeepEverySelectedPageOnce() {
        val merged = MusicParts.merged(listOf(
            MusicPartRequest("Cello", "Cello", listOf(1, 2)),
            MusicPartRequest("Bass", "Bass", listOf(2, 3))))!!
        assertEquals(listOf(1, 2, 3), merged.pages)
        assertEquals("Cello, Bass", merged.instrument)
        assertEquals("Cello", merged.title)
    }
}
