package com.folio.notes.exam

internal fun validExamYear(text: String): Boolean = text.isBlank() ||
    (text.length == 4 && text.toIntOrNull() in 1000..9999)

internal fun validExamUnit(text: String): Boolean = text.isBlank() || text.toIntOrNull() in 1..4

internal fun validExamTotal(text: String): Boolean = text.isBlank() || text.toIntOrNull() in 1..9999

/** Keep the measured seconds until the user actually changes the displayed minutes. */
internal fun recordedSeconds(timed: Boolean, minutes: String, measured: Int?, edited: Boolean): Int? {
    if (!timed) return null
    if (!edited && measured != null && measured > 0) return measured
    return minutes.toIntOrNull()?.takeIf { it in 1..999 }?.times(60)
}

internal fun customTimerMinutes(text: String, maximum: Int): Int? =
    text.toIntOrNull()?.takeIf { it in 1..maximum }

/**
 * Whether the batch draft holds anything the user chose, ticked or not: a value typed into a section
 * that was then unticked is still kept by the panel, so it still counts.
 */
internal fun batchDraftEdited(
    ticked: Boolean, subject: String?, customSubject: String, year: String,
    company: String, type: String?, status: String
): Boolean = ticked || subject != null || customSubject.isNotBlank() || year.isNotEmpty() ||
    company.isNotBlank() || type != null || status != ExamStatus.TO_DO.name

/**
 * Whether the mark form holds input the user would lose. Each `auto` value is what the dialog filled in
 * by itself (stamped score, exam or printed total, measured minutes), so prefills alone are never edits
 * and typing back to them clears the guard. Hidden minutes only count while the sitting is timed.
 */
internal fun markDraftEdited(
    score: String, autoScore: String, total: String, autoTotal: String,
    timed: Boolean, autoTimed: Boolean, minutes: String, autoMinutes: String
): Boolean = score != autoScore || total != autoTotal || timed != autoTimed || (timed && minutes != autoMinutes)
