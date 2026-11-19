package com.folio.notes.study

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class StudyHistoryPage(
    val entries: List<FocalStudyEntry>,
    val totalCount: Int,
    val days: List<StudyHistoryDay>,
)

internal fun focalFilterHistory(
    entries: List<FocalStudyEntry>,
    subjectNames: Map<String, String>,
    query: String = "",
    from: LocalDate? = null,
    to: LocalDate? = null,
    zone: ZoneId,
    limit: Int,
): StudyHistoryPage {
    val search = query.trim()
    val filtered = entries.filter { entry ->
        val date = Instant.ofEpochMilli(entry.endedAt).atZone(zone).toLocalDate()
        entry.completed && !entry.deleted && !focalIsCalendarPlaceholder(entry) &&
            (search.isEmpty() || entry.title.contains(search, ignoreCase = true) ||
                subjectNames[entry.subjectId].orEmpty().contains(search, ignoreCase = true)) &&
            (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
    }.sortedByDescending { it.endedAt }
    val page = filtered.take(limit.coerceAtLeast(0))
    val totals = focalHistoryDays(filtered, zone).associate { it.date to it.studyMinutes }
    return StudyHistoryPage(page, filtered.size,
        focalHistoryDays(page, zone).map { it.copy(studyMinutes = totals.getValue(it.date)) })
}
