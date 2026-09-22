package com.folio.notes.mistakes

import com.folio.notes.NotePage
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

/** True when a page carries no ink, typed text or pictures worth keeping. */
internal fun isPracticePageEmpty(page: NotePage): Boolean =
    page.strokes.isEmpty() && page.images.isEmpty() && page.texts.all { it.text.isBlank() }

/** In-memory empty check: true when every page of [note] has no content yet. */
internal fun isPracticeNotebookEmpty(note: Notebook): Boolean =
    note.pages.all(::isPracticePageEmpty)

/** Previous reviews of the same question, newest first, excluding [excludeReviewId]. */
internal fun previousPracticeAttempts(
    notes: List<Notebook>,
    userId: String,
    mistakeId: String,
    excludeReviewId: String? = null,
): List<Pair<Notebook, LocalMistakeReviewAttempt>> =
    notes.flatMap { note -> note.mistakeReviews.map { note to it } }
        .filter { (_, a) ->
            a.userId == userId && a.mistakeId == mistakeId && a.reviewId != excludeReviewId
        }
        .sortedByDescending { (_, a) -> a.completedAt ?: "" }

/** Human-readable device storage, e.g. 850 B, 12.4 KB, 3.1 MB. */
internal fun formatPracticeBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return if (unit == 0) "$bytes B"
    else {
        val rounded = kotlin.math.round(value * 10) / 10.0
        if (rounded % 1.0 == 0.0) "${rounded.toLong()} ${units[unit]}" else "$rounded ${units[unit]}"
    }
}

