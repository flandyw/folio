package com.folio.notes.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfSearchTest {
    @Test fun matchesCaseOnlyWhenRequested() {
        val pages = listOf(PdfPageText(0, "Cat cat CAT bobcat"))

        assertEquals(4, PdfSearch.search(pages, "cat").single().matchCount)
        assertEquals(2, PdfSearch.search(pages, "cat", matchCase = true).single().matchCount)
        assertEquals(1, PdfSearch.search(pages, "cat", matchCase = true, wholeWord = true).single().matchCount)
    }

    @Test fun punctuationAndHyphensSeparateWordsButLettersDigitsAndUnderscoresDoNot() {
        val pages = listOf(PdfPageText(0, "cat (CAT), bobcat catfish cat-like cat2 _cat cat_ cat"))

        assertEquals(4, PdfSearch.search(pages, "cat", wholeWord = true).single().matchCount)
    }

    @Test fun unicodeLettersAndCombiningMarksContinueAWord() {
        val pages = listOf(PdfPageText(0, "écat caté cat\u0301 cat𐐀 cat"))

        assertEquals(1, PdfSearch.search(pages, "cat", wholeWord = true).single().matchCount)
    }

    @Test fun wholePhrasesMatchAcrossPdfLineBreaks() {
        val pages = listOf(PdfPageText(0, "Pre-exam\n  1, exam\t1a, mockexam 1"))

        assertEquals(1, PdfSearch.search(pages, " exam   1 ", wholeWord = true).single().matchCount)
        assertEquals(emptyList<PdfSearchHit>(), PdfSearch.search(pages, " \n\t ", wholeWord = true))
    }

    @Test fun snippetsKeepOriginalCasingAndContextAroundTheFirstAcceptedMatch() {
        val text = "a".repeat(51) + " bobcat\n Cat " + "b".repeat(74)
        val hit = PdfSearch.search(listOf(PdfPageText(0, text)), "cat", wholeWord = true).single()

        assertEquals(PdfSearchHit(0, 1, "… ${"a".repeat(34)} bobcat Cat ${"b".repeat(71)} …"), hit)
    }

    @Test fun hitsRemainRankedByCountThenPageOrder() {
        val pages = listOf(PdfPageText(3, "cat"), PdfPageText(2, "cat, CAT"), PdfPageText(1, "cat"))

        assertEquals(listOf(2, 1, 3), PdfSearch.search(pages, "cat", wholeWord = true).map { it.pageIndex })
    }
}
