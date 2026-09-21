package com.folio.notes.mistakes

import com.folio.notes.Notebook

/** Only resume pages that still exist, belong to this account, and have not been rated. */
internal fun unfinishedAttempts(notes: List<Notebook>, userId: String): Map<String, LocalMistakeReviewAttempt> = buildMap {
    notes.sortedBy { it.updated }.forEach { note ->
        val pages = note.pages.map { it.id }.toSet()
        note.mistakeReviews.forEach { attempt ->
            if (attempt.userId == userId && attempt.completedAt == null &&
                attempt.practiceNotebookId == note.id && attempt.practicePageId in pages) {
                put(attempt.mistakeId, attempt)
            }
        }
    }
}

internal fun unfinishedAttempt(notes: List<Notebook>, userId: String, mistakeId: String): LocalMistakeReviewAttempt? =
    unfinishedAttempts(notes, userId)[mistakeId]

