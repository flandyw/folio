package com.folio.notes.progress

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * What identifies a sitting, independent of where it came from. Pure, so the "is this already logged?"
 * rules can be exercised without a device and re-run on every keystroke while a result is being edited.
 */
data class ResultFacts(val subject: String, val provider: String, val title: String, val year: Int?, val paper: String,
    val score: Double?, val max: Double?, val date: LocalDate?, val notebookId: String? = null) {
    companion object {
        fun of(e: LoggedExam) = ResultFacts(e.subject, e.provider, e.title, e.examYear, e.paper, e.rawScore, e.rawMax, e.date, e.notebookId)
    }
}

/** How sure we are that a result is already in the log, with the reasons shown to the user. */
data class LoggedMatch(val existing: LoggedExam, val confidence: Double, val reasons: List<String>) {
    val level get() = when {
        confidence >= LoggedMatcher.LIKELY -> MatchLevel.LIKELY
        confidence >= LoggedMatcher.POSSIBLE -> MatchLevel.POSSIBLE
        else -> MatchLevel.NONE
    }
}

enum class MatchLevel { NONE, POSSIBLE, LIKELY }

object LoggedMatcher {
    const val LIKELY = 0.75
    const val POSSIBLE = 0.50

    // Weights sum to 1.0. Identical marks and the notebook link carry the most: a re-typed provider or a
    // title that differs by a word must not hide a true duplicate, while two different papers rarely score alike.
    private const val W_SCORE = 0.25
    private const val W_NOTEBOOK = 0.20
    private const val W_DATE = 0.13
    private const val W_SUBJECT = 0.12
    private const val W_TITLE = 0.10
    private const val W_YEAR = 0.08
    private const val W_PAPER = 0.08
    private const val W_PROVIDER = 0.04

    fun score(candidate: ResultFacts, existing: LoggedExam): LoggedMatch {
        val other = ResultFacts.of(existing)
        val reasons = mutableListOf<String>()
        var total = 0.0
        val sameSubject = comparisonName(candidate.subject) == comparisonName(other.subject)
        val sameMarks = candidate.score != null && candidate.max != null && candidate.score == other.score && candidate.max == other.max
        if (sameMarks) { total += W_SCORE; reasons += "same mark (${candidate.score!!.display(0)}/${candidate.max!!.display(0)})" }
        else if (candidate.score != null && candidate.max != null && candidate.max > 0 && other.max!! > 0 &&
            abs(candidate.score / candidate.max - other.score!! / other.max) <= 0.01) {
            total += W_SCORE * 0.5; reasons += "same percentage"
        }
        if (candidate.notebookId != null && candidate.notebookId == other.notebookId) { total += W_NOTEBOOK; reasons += "linked to the same notebook" }
        val days = if (candidate.date != null && other.date != null) abs(ChronoUnit.DAYS.between(candidate.date, other.date)) else null
        when {
            days == null -> Unit
            days <= 1 -> { total += W_DATE; reasons += if (days == 0L) "same day" else "a day apart" }
            days <= 7 -> { total += W_DATE * 0.6; reasons += "$days days apart" }
            days <= 30 -> total += W_DATE * 0.2
        }
        if (sameSubject) { total += W_SUBJECT; reasons += "same subject" }
        if (candidate.title.isNotBlank() && normalised(candidate.title) == normalised(other.title)) { total += W_TITLE; reasons += "same title" }
        if (candidate.year != null && candidate.year == other.year) { total += W_YEAR; reasons += "same year" }
        if (comparisonName(candidate.paper) == comparisonName(other.paper)) { total += W_PAPER; reasons += "same paper" }
        // A blank provider is unknown rather than different.
        if (candidate.provider.isBlank() || other.provider.isBlank() || comparisonName(candidate.provider) == comparisonName(other.provider)) total += W_PROVIDER
        else reasons += "different provider"
        // Without identical marks or the notebook link, agreeing metadata alone never makes a strong claim:
        // sitting the same paper twice on different days is legitimate practice, not a duplicate.
        val anchored = sameMarks || (candidate.notebookId != null && candidate.notebookId == other.notebookId)
        val capped = if (!sameSubject) minOf(total, 0.30) else if (!anchored) minOf(total, 0.70) else total
        return LoggedMatch(existing, capped.coerceIn(0.0, 1.0), reasons)
    }

    /** The most likely existing entry, or null when nothing reaches [POSSIBLE]. */
    fun best(candidate: ResultFacts, logged: List<LoggedExam>, ignoreId: String? = null): LoggedMatch? =
        logged.asSequence().filter { it.id != ignoreId }.map { score(candidate, it) }
            .filter { it.confidence >= POSSIBLE }.maxByOrNull { it.confidence }

    private fun normalised(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
