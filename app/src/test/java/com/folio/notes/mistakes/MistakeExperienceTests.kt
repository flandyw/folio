package com.folio.notes.mistakes

import com.folio.notes.NotePage
import com.folio.notes.Notebook
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
}
