package com.folio.notes.planner

import com.folio.notes.progress.ExamReference
import com.folio.notes.progress.LoggedExam
import com.folio.notes.progress.comparisonName

/** How the Grades tab orders the student's own logged results. */
enum class GradesSort(val label: String) {
    NEWEST("Newest first"), OLDEST("Oldest first"), HIGHEST("Highest mark"), LOWEST("Lowest mark");
    companion object { fun of(label: String) = entries.firstOrNull { it.label == label } ?: NEWEST }
}

const val ALL_SUBJECTS = "All subjects"

/** The subject filter's options: every subject with a logged result, after [ALL_SUBJECTS]. */
fun gradesSubjects(exams: List<LoggedExam>): List<String> =
    listOf(ALL_SUBJECTS) + exams.map { it.subject.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

/** Logged results in [subject] (case-insensitive; an unknown subject or [ALL_SUBJECTS] keeps everything), ordered by [sort], ties by id. */
fun gradesResults(exams: List<LoggedExam>, subject: String, sort: GradesSort): List<LoggedExam> {
    val key = subject.trim().lowercase()
    val filtered = if (key == ALL_SUBJECTS.lowercase() || exams.none { it.subject.trim().lowercase() == key }) exams
        else exams.filter { it.subject.trim().lowercase() == key }
    val byDate = compareBy<LoggedExam, java.time.LocalDate?>(nullsFirst()) { it.date }.thenBy { it.completedAt }
    val order = when (sort) {
        GradesSort.NEWEST -> byDate.reversed()
        GradesSort.OLDEST -> byDate
        GradesSort.HIGHEST -> compareByDescending<LoggedExam> { it.percentage }.then(byDate.reversed())
        GradesSort.LOWEST -> compareBy<LoggedExam> { it.percentage }.then(byDate.reversed())
    }
    return filtered.sortedWith(order.thenBy { it.id })
}

/** The VCAA paper a logged result compares against in any year, matched like [com.folio.notes.progress.referenceFor]: same normalized subject and paper, or the subject's only paper. */
fun comparisonReference(exam: LoggedExam, references: List<ExamReference>): ExamReference? {
    val subject = comparisonName(exam.subject); val paper = comparisonName(exam.paper)
    val candidates = references.filter { comparisonName(it.subject) == subject }
    return candidates.find { comparisonName(it.paper) == paper }
        ?: candidates.distinctBy { comparisonName(it.paper) }.singleOrNull()
}
