package com.folio.notes.study

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * Pure rules behind the study timer screens: labels, history days, weekly totals, streaks and
 * plan filtering/validation. No Android imports, so `StudyTimerRulesTest` exercises them on the JVM.
 */

/** "45 min", "1 h", "1 h 5 min". */
internal fun formatStudyMinutes(minutes: Long): String {
    val safe = minutes.coerceAtLeast(0L)
    val h = safe / 60
    val m = safe % 60
    return if (h == 0L) "$m min" else if (m == 0L) "$h h" else "$h h $m min"
}

/** Whole minutes for a duration, rounded to the nearest minute (30 s rounds up). */
internal fun studyRoundedMinutes(millis: Long): Long = (millis.coerceAtLeast(0L) + 30_000L) / 60_000L

/** The big clock: always h:mm:ss so the digits never shift width as the hour arrives. */
internal fun formatStudyClock(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
}

/** The toolbar pill: mm:ss under an hour, h:mm:ss after. */
internal fun formatStudyChipClock(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return if (seconds >= 3600L) formatStudyClock(millis)
    else String.format(Locale.ROOT, "%02d:%02d", (seconds / 60) % 60, seconds % 60)
}

/** Active time of a running or paused shared session: its intervals, never less than what the server reported. */
internal fun focalEntryElapsed(entry: FocalStudyEntry, now: Long): Long =
    entry.intervals.sumOf { ((it.endAt ?: now) - it.startAt).coerceAtLeast(0L) }.coerceAtLeast(entry.activeMillis)

/** Minutes a finished session counts for in history, never shown as zero. */
internal fun focalHistoryMinutes(entry: FocalStudyEntry): Long =
    studyRoundedMinutes(focalActiveMillisBetween(entry, entry.startedAt, entry.endedAt)).coerceAtLeast(1L)

/** "Today", "Yesterday", the weekday within the last week, else a date. */
internal fun studyDayLabel(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date == today.plusDays(1) -> "Tomorrow"
    date.isBefore(today) && date.isAfter(today.minusDays(7)) -> date.format(DateTimeFormatter.ofPattern("EEEE", locale))
    date.year == today.year -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
}

/** One day of finished sessions, newest first, with the active study it adds up to. */
internal data class StudyHistoryDay(val date: LocalDate, val entries: List<FocalStudyEntry>, val studyMinutes: Long)

/** Groups finished sessions by the local day they ended on; exams are listed but never added to study time. */
internal fun focalHistoryDays(history: List<FocalStudyEntry>, zone: ZoneId): List<StudyHistoryDay> =
    history.sortedByDescending { it.endedAt }
        .groupBy { Instant.ofEpochMilli(it.endedAt).atZone(zone).toLocalDate() }
        .map { (date, entries) ->
            StudyHistoryDay(date, entries, entries.filter { it.kind == "study" }.sumOf { focalHistoryMinutes(it) })
        }

private fun startOf(date: LocalDate, zone: ZoneId) = date.atStartOfDay(zone).toInstant().toEpochMilli()

/** Active study since the start of this week (Monday), up to [now]. */
internal fun focalWeekMillis(entries: List<FocalStudyEntry>, now: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return focalStudyMillisBetween(entries, startOf(monday, zone), now)
}

/**
 * Days in a row with at least [minimumMinutes] of study, ending today. A day not yet studied
 * does not break the streak until it is over, so this morning still shows yesterday's run.
 */
internal fun focalStudyStreak(entries: List<FocalStudyEntry>, now: Long, zone: ZoneId,
                              minimumMinutes: Long = 1L, maxDays: Int = 365): Int {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val counted = focalCountedStudy(entries)
    fun studied(date: LocalDate): Boolean {
        val from = startOf(date, zone)
        val until = minOf(now, startOf(date.plusDays(1), zone))
        // Sessions entirely outside the day are skipped before any interval arithmetic.
        return counted.filter { it.startedAt < until && (it.active || it.endedAt > from) }
            .sumOf { focalMeasuredMillisBetween(it, from, until) } >= minimumMinutes * 60_000L
    }
    var day = if (studied(today)) today else today.minusDays(1)
    var streak = 0
    while (streak < maxDays && studied(day)) { streak++; day = day.minusDays(1) }
    return streak
}

/** The plan list's time filters. */
internal enum class PlanScope(val label: String) { ALL("All"), TODAY("Today"), OVERDUE("Overdue"), UPCOMING("Upcoming") }

/** A plan is overdue once its last block has ended without it being started. */
internal fun focalPlanOverdue(slots: List<FocalStudyInterval>, now: Long): Boolean =
    slots.lastOrNull()?.endAt?.let { it < now } == true

internal fun focalPlanInScope(slots: List<FocalStudyInterval>, scope: PlanScope, now: Long, dayStart: Long, dayEnd: Long): Boolean =
    when (scope) {
        PlanScope.ALL -> true
        PlanScope.TODAY -> slots.any { it.startAt < dayEnd && (it.endAt ?: it.startAt) > dayStart }
        PlanScope.OVERDUE -> focalPlanOverdue(slots, now)
        PlanScope.UPCOMING -> slots.any { (it.endAt ?: it.startAt) >= now }
    }

/** Every word of [query] must appear in the title, subject, notes or topics, in any order. */
internal fun focalPlanMatchesQuery(query: String, vararg fields: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    if (words.isEmpty()) return true
    val haystack = fields.joinToString(" ").lowercase()
    return words.all { it in haystack }
}

/** A short, glanceable "when" for a plan card. */
internal fun focalPlanWhen(slots: List<FocalStudyInterval>, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String? {
    val first = slots.firstOrNull() ?: return null
    val last = slots.last()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    if (slots.any { it.startAt <= now && (it.endAt ?: it.startAt) > now }) return "Scheduled now"
    if (focalPlanOverdue(slots, now)) {
        val ended = Instant.ofEpochMilli(requireNotNull(last.endAt)).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return "Overdue · " + if (ended.toLocalDate() == today) "ended ${ended.format(time)}" else studyDayLabel(ended.toLocalDate(), today, locale)
    }
    val next = slots.firstOrNull { it.startAt > now } ?: first
    val minutes = (next.startAt - now) / 60_000L
    if (minutes < 60) return "Starts in ${minutes.coerceAtLeast(1L)} min"
    val start = Instant.ofEpochMilli(next.startAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return studyDayLabel(start.toLocalDate(), today, locale) + " · " + start.format(time)
}

/** "Tue 21 Oct, 4:00 pm – 5:30 pm", or both dates when a block runs past midnight. */
internal fun focalSlotLabel(slot: FocalStudyInterval, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
    val start = Instant.ofEpochMilli(slot.startAt).atZone(zone)
    val end = Instant.ofEpochMilli(slot.endAt ?: slot.startAt).atZone(zone)
    val day = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return if (start.toLocalDate() == end.toLocalDate()) "${start.format(day)}, ${start.format(time)} – ${end.format(time)}"
    else "${start.format(day)}, ${start.format(time)} – ${end.format(day)}, ${end.format(time)}"
}

/**
 * What is wrong with each block of a plan being edited, by its position in [slots]; empty when
 * the plan can be saved. Mirrors `focalPlanEdit`'s checks so Save can explain itself up front.
 */
internal fun focalPlanSlotProblems(slots: List<FocalStudyInterval>): Map<Int, String> {
    val problems = linkedMapOf<Int, String>()
    slots.forEachIndexed { index, slot ->
        val end = slot.endAt
        when {
            end == null || end <= slot.startAt -> problems[index] = "Ends before it starts"
            end - slot.startAt > 24 * 60 * 60_000L -> problems[index] = "Longer than 24 hours"
        }
    }
    val order = slots.indices.filter { it !in problems }.sortedBy { slots[it].startAt }
    order.zipWithNext().forEach { (a, b) ->
        if (requireNotNull(slots[a].endAt) > slots[b].startAt) {
            problems.putIfAbsent(b, "Overlaps block ${a + 1}")
        }
    }
    return problems
}

/** The subject to offer when nothing else suggests one: the one studied most recently. */
internal fun focalRecentSubject(entries: List<FocalStudyEntry>, subjects: List<FocalSubject>): String? =
    entries.asSequence().filter { it.kind == "study" && !it.planned && !it.deleted && subjects.any { s -> s.id == it.subjectId } }
        .maxByOrNull { it.endedAt }?.subjectId
