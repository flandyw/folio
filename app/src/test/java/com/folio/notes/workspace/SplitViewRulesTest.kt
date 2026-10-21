package com.folio.notes.workspace

import com.folio.notes.model.Notebook
import org.junit.Assert.*
import org.junit.Test

class SplitViewRulesTest {
    @Test fun releasingNearAnEdgeClosesTheSqueezedPane() {
        assertEquals(SplitRelease.CloseEditor, SplitPanes.release(0.1f))
        assertEquals(SplitRelease.CloseEditor, SplitPanes.release(SplitPanes.CLOSE_EDGE))
        assertEquals(SplitRelease.CloseCompanion, SplitPanes.release(0.9f))
        assertEquals(SplitRelease.Settle(0.5f), SplitPanes.release(0.53f))
        assertEquals(SplitRelease.Settle(SplitPanes.MIN_FRACTION), SplitPanes.release(0.16f))
        assertEquals(SplitRelease.Settle(SplitPanes.EQUAL), SplitPanes.release(Float.NaN))
    }

    @Test fun aLiveDragTravelsPastTheResizeLimitsButNotOffScreen() {
        assertEquals(0.1f, SplitPanes.dragged(0.2f, -100f, 1000f), 1e-4f)
        assertEquals(SplitPanes.LIVE_MIN, SplitPanes.dragged(0.2f, -1000f, 1000f), 1e-4f)
        assertEquals(SplitPanes.LIVE_MAX, SplitPanes.dragged(0.8f, -1000f, 1000f, invert = true), 1e-4f)
        assertEquals(0.4f, SplitPanes.dragged(0.4f, 50f, 0f), 1e-4f)
    }

    @Test fun chooserOffersThisNotebookThenTabsThenRecentWork() {
        val active = Notebook(id = "a", title = "Methods notes", updated = 10)
        val tab = Notebook(id = "t", title = "Chem SAC", updated = 1)
        val newer = Notebook(id = "n", title = "Physics", updated = 30, tags = listOf("Unit 3"))
        val older = Notebook(id = "o", title = "English", updated = 20)
        val practice = Notebook(id = "p", title = "Practice", mistakePractice = true, updated = 99)
        val score = Notebook(id = "s", title = "Score", musicScoreId = "m", updated = 98)
        val tabs = listOf(EditorTab("a", "a", "p1", "Methods notes"), EditorTab("t", "t", "p1", "Chem SAC"))
        val all = listOf(active, tab, newer, older, practice, score)
        val choices = SplitChooser.choices(all, tabs, "a")
        assertEquals("a", choices.current?.id)
        assertEquals(listOf("t"), choices.tabs.map { it.id })
        assertEquals(listOf("n", "o"), choices.recent.map { it.id })
        assertEquals(listOf("n"), SplitChooser.choices(all, tabs, "a", "unit 3").recent.map { it.id })
        assertNull(SplitChooser.choices(all, tabs, "a", "chem").current)
        assertTrue(SplitChooser.choices(all, tabs, "a", "nothing like this").isEmpty)
    }
}
