package com.folio.notes.mistakes

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day of the Today forecast; [count] questions fall due on [date]. */
internal data class TodayForecastDay(val date: LocalDate, val count: Int)

/**
 * What the Today view says about the user's day, free of Android types like the scheduler.
 * [laterToday] counts questions that fall due later today but are not due yet; [week] is the
 * six days after today; [nextDueAt] is the soonest not-yet-due question, for the caught-up state.
 */
internal data class TodayStats(
    val reviewedToday: Int,
    val streak: Int,
    val laterToday: Int,
    val week: List<TodayForecastDay>,
    val nextDueAt: String?,
)

internal object MistakeToday {
    const val FORECAST_DAYS = 6

    fun stats(
        mistakes: List<ExamTrackMistake>,
        schedules: Map<String, MistakeSchedule>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): TodayStats {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun day(iso: String): LocalDate? = runCatching { Instant.ofEpochMilli(timestamp(iso)).atZone(zone).toLocalDate() }.getOrNull()
        val reviewDays = HashSet<LocalDate>()
        var reviewedToday = 0
        mistakes.forEach { m ->
            m.reviewHistory.forEach { r ->
                val d = day(r.completedAt) ?: return@forEach
                reviewDays += d
                if (d == today) reviewedToday++
            }
        }
        // A streak survives until the end of a day without reviews, so yesterday still counts.
        var cursor = if (today in reviewDays) today else today.minusDays(1)
        var streak = 0
        while (cursor in reviewDays) { streak++; cursor = cursor.minusDays(1) }
        val counts = IntArray(FORECAST_DAYS + 1)
        var laterToday = 0
        var next: Pair<Long, String>? = null
        mistakes.forEach { m ->
            if (m.suspended) return@forEach
            val dueAt = schedules[m.id]?.dueAt ?: return@forEach
            val at = runCatching { timestamp(dueAt) }.getOrNull() ?: return@forEach
            if (at <= now) return@forEach
            if (next == null || at < next!!.first) next = at to dueAt
            val offset = java.time.temporal.ChronoUnit.DAYS.between(today, day(dueAt) ?: return@forEach)
            if (offset == 0L) laterToday++
            else if (offset in 1..FORECAST_DAYS) counts[offset.toInt()]++
        }
        return TodayStats(
            reviewedToday = reviewedToday,
            streak = streak,
            laterToday = laterToday,
            week = (1..FORECAST_DAYS).map { TodayForecastDay(today.plusDays(it.toLong()), counts[it]) },
            nextDueAt = next?.second,
        )
    }

    /** Due questions per subject, largest first; blank subjects share one bucket. */
    fun subjects(due: List<ExamTrackMistake>, subjectOf: (ExamTrackMistake) -> String): List<Pair<String, Int>> =
        due.groupingBy { subjectOf(it).ifBlank { UNSORTED } }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    const val UNSORTED = "Other"
}
