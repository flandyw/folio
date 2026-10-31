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
