package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class PdfSearchTests {
    private val pages = listOf(
        PdfPageText(0, "Question 1. Solve x + 2 = 5 for x."),
        PdfPageText(1, "Working space.\n\nQuestion 2. Differentiate x squared."),
        PdfPageText(2, "FORMULAE\n\nThe quadratic formula solves a quadratic."),
        PdfPageText(3, "")
    )

    @Test fun aBlankQueryOrNoPagesFindsNothing() {
        assertTrue(PdfSearch.search(pages, "").isEmpty())
        assertTrue(PdfSearch.search(pages, "   ").isEmpty())
        assertTrue(PdfSearch.search(emptyList(), "x").isEmpty())
    }

    @Test fun matchingIgnoresCaseAndRanksMostMatchesFirst() {
        val hits = PdfSearch.search(pages, "question")
        assertEquals(listOf(0, 1), hits.map { it.pageIndex })
        assertEquals(listOf(1, 1), hits.map { it.matchCount })
        val many = PdfSearch.search(
            listOf(PdfPageText(0, "x marks the spot"), PdfPageText(1, "x x x and x")),
            "x"
        )
        assertEquals(1, many.first().pageIndex)
        assertEquals(4, many.first().matchCount)
        assertEquals(0, many.last().pageIndex)
    }

    @Test fun aMatchAcrossALineBreakIsFound() {
        val split = listOf(PdfPageText(0, "Answer Exam\n1 Section A below."))
        val hits = PdfSearch.search(split, "exam 1")
        assertEquals(1, hits.size)
        assertEquals(1, hits.single().matchCount)
    }

    @Test fun pagesWithoutTheQueryOrWithoutTextAreExcluded() {
        val hits = PdfSearch.search(pages, "quadratic")
        assertEquals(listOf(2), hits.map { it.pageIndex })
        assertEquals(2, hits.single().matchCount)
    }

    @Test fun countingDoesNotOverlap() {
        val hits = PdfSearch.search(listOf(PdfPageText(0, "aaa")), "aa")
        assertEquals(1, hits.single().matchCount)
    }

    @Test fun aSnippetCarriesTheMatchWithEllipses() {
        val long = "Introduction. " + "Padding words ".repeat(20) + "needle hides here. " + "Trailing words ".repeat(20)
        val snippet = PdfSearch.snippet(PdfSearch.collapse(long), PdfSearch.collapse(long).indexOf("needle"), "needle".length)
        assertTrue(snippet.startsWith("… "))
        assertTrue(snippet.endsWith(" …"))
        assertTrue(snippet.contains("needle hides here"))
    }

    @Test fun aSnippetAtTheVeryStartHasNoLeadingEllipsis() {
        val snippet = PdfSearch.snippet("Question one starts here and runs on", 0, "Question".length)
        assertFalse(snippet.startsWith("…"))
        assertTrue(snippet.startsWith("Question"))
    }

    @Test fun snippetsNeverEscapeTheirInputs() {
        assertEquals("", PdfSearch.snippet("", 0, 3))
        assertEquals("", PdfSearch.snippet("hi", 9, 2))
        val whole = PdfSearch.snippet("short", 0, "short".length)
        assertEquals("short", whole)
    }

    @Test fun collapsingNormalisesEveryWhitespaceRun() {
        assertEquals("exam 1", PdfSearch.collapse("  exam\n\t 1  "))
        assertEquals("", PdfSearch.collapse(" \n "))
    }
}
