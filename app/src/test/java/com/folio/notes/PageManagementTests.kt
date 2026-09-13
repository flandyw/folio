package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class PageManagementTests {
    private val dotted = NotePage(paper = Paper.DOTS)
    private val ruled = NotePage(paper = Paper.RULED)
    private val plain = NotePage(paper = Paper.PLAIN)
    private val note = Notebook(title = "Lecture", pages = listOf(dotted, ruled, plain))

    @Test fun insertingLandsOnTheRequestedIndexAndClampsAtTheEnds() {
        val inserted = note.withInsertedPage(1, NotePage(paper = Paper.GRAPH))
        assertEquals(4, inserted.pages.size)
        assertEquals(Paper.GRAPH, inserted.pages[1].paper)
        assertEquals(ruled, inserted.pages[2])
        // Out-of-range indexes clamp rather than throwing, so a page can always be added at an edge.
        assertNotEquals(inserted, inserted.withInsertedPage(-5))
        assertEquals(4, note.withInsertedPage(-5).pages.size)
        assertEquals(4, note.withInsertedPage(99).pages.size)
        // A negative index lands at the front, past the end lands last.
        assertEquals(Paper.GRAPH, note.withInsertedPage(-5, NotePage(paper = Paper.GRAPH)).pages.first().paper)
        assertEquals(Paper.GRAPH, note.withInsertedPage(99, NotePage(paper = Paper.GRAPH)).pages.last().paper)
    }

    @Test fun deletingKeepsTheOtherPagesAndNeverLeavesTheNotebookEmpty() {
        val shortened = note.withDeletedPage(1)
        assertEquals(listOf(dotted, plain), shortened.pages)
        assertEquals(note, note.withDeletedPage(7))
        // Removing the last page leaves one blank page to write on, not an empty notebook.
        val one = shortened.withDeletedPage(0)
        assertEquals(1, one.pages.size)
        val cleared = one.withDeletedPage(0)
        assertEquals(1, cleared.pages.size)
        assertNull(cleared.pages.first().pdfIndex)
        assertTrue(cleared.pages.first().strokes.isEmpty())
    }

    @Test fun movingAPageReordersItAndClampsTheDestination() {
        val moved = note.withMovedPage(0, 2)
        assertEquals(listOf(ruled, plain, dotted), moved.pages)
        assertEquals(listOf(plain, dotted, ruled), note.withMovedPage(2, 0).pages)
        assertEquals(note, note.withMovedPage(1, 1))
        assertEquals(note, note.withMovedPage(9, 0))
        assertEquals(listOf(ruled, plain, dotted), note.withMovedPage(0, 99).pages)
    }

    @Test fun theOpenPageFollowsTheOneThatMoved() {
        // Dragging the open page down two places leaves it at the bottom.
        assertEquals(2, movedPageIndex(0, 0, 2))
        // Pages skipped over shift up by one.
        assertEquals(0, movedPageIndex(1, 0, 2))
        assertEquals(1, movedPageIndex(2, 0, 2))
        // A page above the move is untouched.
        assertEquals(0, movedPageIndex(0, 1, 2))
        // Moving upwards shifts the pages in between down.
        assertEquals(1, movedPageIndex(0, 2, 0))
        assertEquals(2, movedPageIndex(1, 2, 0))
    }

    @Test fun pageStructureSurvivesSaveAndReload() {
        val edited = note.withMovedPage(0, 2).withInsertedPage(0, NotePage(paper = Paper.GRID)).withDeletedPage(3)
        val restored = NoteCodec.decode(NoteCodec.encode(edited))
        assertEquals(edited, restored)
        assertEquals(3, restored.pages.size)
    }
}
