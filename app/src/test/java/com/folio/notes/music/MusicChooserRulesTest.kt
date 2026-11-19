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

    @Test fun searchSplitsOnUnicodeSpacesAndStillNeedsEveryTerm() {
        val score = MusicScore("score", "Concerto", 4, composer = "Elgar", part = "Cello")
        listOf("elgar\u00a0cello", "elgar\u202fcello", "elgar\u2003cello", " elgar \t\u00a0 cello\n").forEach {
            assertTrue(it, score.matchesQuery(it))
        }
        assertFalse(score.matchesQuery("elgar\u00a0violin"))
        listOf("", "   ", "\u00a0\u2003").forEach { assertTrue(score.matchesQuery(it)) }
    }

    @Test fun composerChoicesTrimAndFoldCaseLikeTheFilter() {
        val scores = listOf(
            MusicScore("a", "Concerto", 4, composer = "  Elgar "),
            MusicScore("b", "Enigma", 4, composer = "elgar"),
            MusicScore("c", "Cello suite", 4, composer = "Bach"),
            MusicScore("d", "Untitled", 4, composer = "   "))
        val names = composerNames(scores)
        assertEquals(listOf("Bach", "Elgar"), names)
        // Every choice the menu offers keeps the scores it came from.
        assertEquals(setOf("a", "b"), organizeScores(scores, "", MusicSort.ADDED, MusicFilter(composer = "Elgar")).map { it.id }.toSet())
        assertEquals(listOf("c"), organizeScores(scores, "", MusicSort.ADDED, MusicFilter(composer = names[0])).map { it.id })
        assertEquals(4, organizeScores(scores, "", MusicSort.ADDED, MusicFilter()).size)
    }

    @Test fun composerChoicesFoldNonAsciiCaseExactlyLikeTheFilter() {
        // lowercase() keeps long s apart from s, but the filter's ignore-case match treats them as one.
        val scores = listOf(MusicScore("a", "Waltz", 4, composer = "Strauss"), MusicScore("b", "Polka", 4, composer = "Strau\u017fs"))
        assertEquals(listOf("Strauss"), composerNames(scores))
        assertEquals(2, organizeScores(scores, "", MusicSort.ADDED, MusicFilter(composer = "Strauss")).size)
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
