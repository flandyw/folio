package com.folio.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitPanesTests {
    @Test fun releaseSnapsNearSnapPoints() {
        assertEquals(0.3f, SplitPanes.snap(0.32f))
        assertEquals(0.5f, SplitPanes.snap(0.5f))
        assertEquals(0.7f, SplitPanes.snap(0.74f))
        assertEquals(0.42f, SplitPanes.snap(0.42f))
    }

    @Test fun fractionsStayInRange() {
        assertEquals(0.2f, SplitPanes.coerce(0f))
        assertEquals(0.8f, SplitPanes.coerce(9f))
        assertEquals(0.5f, SplitPanes.coerce(Float.NaN))
        assertEquals(0.5f, SplitPanes.snap(Float.NaN))
    }

    @Test fun dragsGrowTheFirstPaneUnlessInverted() {
        assertEquals(0.6f, SplitPanes.dragged(0.5f, 100f, 1000f), 0.001f)
        assertEquals(0.4f, SplitPanes.dragged(0.5f, 100f, 1000f, invert = true), 0.001f)
        assertEquals(0.5f, SplitPanes.dragged(0.5f, 100f, 0f), 0.001f)
        assertEquals(0.8f, SplitPanes.dragged(0.7f, 500f, 1000f), 0.001f)
    }

    private fun notebook(id: String, pages: Int) = Notebook(
        id = id, title = id,
        pages = List(pages) { NotePage(id = "$id-p$it") }
    )

    @Test fun linkedSameNotebookFollowsSamePage() {
        val note = notebook("q", 5)
        assertEquals("q-p3", linkedCompanionTarget(note, note, 3))
        assertEquals("q-p4", linkedCompanionTarget(note, note, 99))
    }

    @Test fun linkedDifferentNotebooksFollowIndexClamped() {
        val questions = notebook("q", 12)
        val solutions = notebook("s", 4)
        assertEquals("s-p2", linkedCompanionTarget(questions, solutions, 2))
        assertEquals("s-p3", linkedCompanionTarget(questions, solutions, 11))
        assertNull(linkedCompanionTarget(questions, solutions.copy(pages = emptyList()), 1))
    }
}
