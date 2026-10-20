package com.folio.notes.mistakes

/** How the Library orders questions. Pure, so ordering stays JVM-testable. */
internal enum class MistakeLibrarySort(val label: String) {
    DUE("Due date"),
    NEWEST("Recently added"),
    MARKS("Most marks lost");

    fun sorted(mistakes: List<ExamTrackMistake>, schedules: Map<String, MistakeSchedule>): List<ExamTrackMistake> {
        fun due(m: ExamTrackMistake) = schedules[m.id]?.dueAt ?: m.updatedAt
        return when (this) {
            DUE -> mistakes.sortedWith(compareBy<ExamTrackMistake> { due(it) }.thenBy { it.id })
            NEWEST -> mistakes.sortedWith(compareByDescending<ExamTrackMistake> { it.createdAt }.thenBy { it.id })
            MARKS -> mistakes.sortedWith(compareByDescending<ExamTrackMistake> { it.marksLost ?: -1.0 }
                .thenBy { due(it) }.thenBy { it.id })
        }
    }

    companion object {
        fun parse(value: String?): MistakeLibrarySort = entries.find { it.name == value } ?: DUE
    }
}

/** "1 question", "3 questions"; [plural] defaults to [singular] + "s". */
internal fun countLabel(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"

/** A session's ratings as "2 Again · 3 Good", in rating order; empty when nothing was rated. */
internal fun ratingTally(ratings: List<String>): String =
    ReviewRating.entries.mapNotNull { rating ->
        val count = ratings.count { it == rating.wire }
        if (count == 0) null else "$count ${rating.wire.replaceFirstChar(Char::uppercase)}"
    }.joinToString(" · ")
