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

    @Test fun scoreDetailsAskBeforeDiscardingOnlyRealEdits() {
        val score = MusicScore("score", "Concerto", 4, composer = "Elgar", part = "Cello", notes = "Watch the cue")
        assertFalse(score.detailsEdited("Concerto", "Elgar", "Cello", "Watch the cue"))
        // Save trims, so stray whitespace is not an edit; a field changed back is not one either.
        assertFalse(score.detailsEdited(" Concerto ", "Elgar ", "Cello", "Watch the cue\n"))
        assertTrue(score.detailsEdited("Concerto in E minor", "Elgar", "Cello", "Watch the cue"))
        assertTrue(score.detailsEdited("Concerto", "Dvořák", "Cello", "Watch the cue"))
        assertTrue(score.detailsEdited("Concerto", "Elgar", "Viola", "Watch the cue"))
        assertTrue(score.detailsEdited("Concerto", "Elgar", "Cello", ""))
        assertTrue(score.detailsEdited("", "Elgar", "Cello", "Watch the cue"))
    }
}
