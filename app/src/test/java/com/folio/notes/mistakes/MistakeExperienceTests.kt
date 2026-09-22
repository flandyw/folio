package com.folio.notes.mistakes

import com.folio.notes.InkPoint
import com.folio.notes.NotePage
import com.folio.notes.Notebook
import com.folio.notes.Stroke
import com.folio.notes.TextBox
import com.folio.notes.Tool
import org.junit.Assert.*
import org.junit.Test

class MistakeExperienceTests {
    private fun attempt(id: String = "review", user: String = "student") =
        LocalMistakeReviewAttempt(user, "mistake", id, "notebook", "page")
    private fun notebook(vararg attempts: LocalMistakeReviewAttempt) = Notebook(
        id = "notebook", title = "Practice", pages = listOf(NotePage(id = "page")),
        mistakePractice = true, mistakeReviews = attempts.toList()
    )

    @Test fun resumesUnfinishedPageWithoutLosingItsIdentity() {
        val unfinished = attempt()
        assertEquals(unfinished, unfinishedAttempt(listOf(notebook(unfinished)), "student", "mistake"))
    }

    @Test fun neverResumesAnotherAccountsOrCompletedWork() {
        val completed = attempt("finished").copy(completedAt = "2026-09-21T00:00:00.000Z", rating = "good")
        assertTrue(unfinishedAttempts(listOf(notebook(attempt(user = "someone-else"), completed)), "student").isEmpty())
    }

    @Test fun deletedPagesAndStaleNotebookReferencesAreExcluded() {
        assertTrue(unfinishedAttempts(listOf(notebook(attempt().copy(practicePageId = "deleted"))), "student").isEmpty())
        assertTrue(unfinishedAttempts(listOf(notebook(attempt().copy(practiceNotebookId = "missing"))), "student").isEmpty())
    }

    @Test fun picksTheMostRecentlyUpdatedNotebookRegardlessOfListOrder() {
        val older = notebook(attempt("old")).copy(updated = 100)
        val newestAttempt = attempt("new").copy(practiceNotebookId = "new-notebook")
        val newer = notebook(newestAttempt).copy(id = "new-notebook", updated = 200)
        assertEquals(newestAttempt, unfinishedAttempt(listOf(newer, older), "student", "mistake"))
    }

    @Test fun emptyPagesAreDetectedForAutoPurge() {
        val empty = NotePage(id = "empty")
        assertTrue(isPracticePageEmpty(empty))
        assertTrue(isPracticePageEmpty(empty.copy(texts = listOf(TextBox(x = 0f, y = 0f, text = "  ")))))
        val inked = empty.copy(strokes = listOf(Stroke(Tool.PEN, 0, 3f, listOf(InkPoint(0f, 0f), InkPoint(1f, 1f)))))
        assertFalse(isPracticePageEmpty(inked))
        val typed = empty.copy(texts = listOf(TextBox(x = 0f, y = 0f, text = "x=2")))
        assertFalse(isPracticePageEmpty(typed))
        assertTrue(isPracticeNotebookEmpty(notebook(attempt())))
        assertFalse(isPracticeNotebookEmpty(notebook(attempt()).copy(pages = listOf(inked))))
    }

    @Test fun previousAttemptsExcludeCurrentAndSortNewestFirst() {
        fun stored(id: String, at: String?, notebookId: String) =
            attempt(id).copy(completedAt = at, practiceNotebookId = notebookId)
        val current = stored("current", null, "note-current")
        val older = stored("older", "2026-09-10T00:00:00.000Z", "note-older")
        val newer = stored("newer", "2026-09-12T00:00:00.000Z", "note-newer")
        val notes = listOf(
            notebook(current).copy(id = "note-current"),
            notebook(older).copy(id = "note-older"),
            notebook(newer).copy(id = "note-newer"),
            notebook(attempt("other-mistake").copy(mistakeId = "other")).copy(id = "note-other"),
        )
        val previous = previousPracticeAttempts(notes, "student", "mistake", "current")
        assertEquals(listOf("newer", "older"), previous.map { it.second.reviewId })
        assertTrue(previousPracticeAttempts(notes, "someone-else", "mistake", null).isEmpty())
    }

    @Test fun practiceBytesFormatStaysReadable() {
        assertEquals("0 B", formatPracticeBytes(0))
        assertEquals("850 B", formatPracticeBytes(850))
        assertEquals("1.5 KB", formatPracticeBytes(1536))
        assertEquals("3.1 MB", formatPracticeBytes((3.1 * 1024 * 1024).toLong()))
    }
}
