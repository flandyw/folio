package com.folio.notes.mistakes

import com.folio.notes.mistakes.review.MistakeReviewPreferences
import com.folio.notes.mistakes.sync.ExamTrackMistakeCodec
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max

/** Port of examtrack/src/lib/exam-data.ts; no Android dependencies. */
object MistakeScheduler {
    private const val DAY = 86_400_000L
    fun reviewDayStart(now: Long, days: Long = 0): Long = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        .toLocalDate().plusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun daySlot(due: Long, now: Long): Long = java.time.temporal.ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(),
        Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()).toLocalDate()).coerceAtLeast(0)
    private fun round(n: Double) = floor(n + 0.5) // JavaScript Math.round (positive scheduling values)
    fun getMistakeSchedule(m: ExamTrackMistake): MistakeSchedule {
        val last = m.reviewHistory.lastOrNull()
        val state = m.reviewState ?: when {
            last == null -> if (m.resolved) ReviewState.REVIEW else ReviewState.NEW
            last.result == ReviewRating.AGAIN -> if (m.reviewHistory.size == 1) ReviewState.LEARNING else ReviewState.RELEARNING
            m.resolved || m.reviewHistory.count { it.result == ReviewRating.GOOD || it.result == ReviewRating.EASY } >= 2 -> ReviewState.REVIEW
            else -> ReviewState.LEARNING
        }
        val interval = m.intervalDays ?: last?.intervalDays ?: when {
            m.resolved -> 30.0
            last == null -> 0.0
            else -> when (last.result) { ReviewRating.AGAIN -> 0.0; ReviewRating.HARD -> 1.0; ReviewRating.EASY -> 7.0; else -> 3.0 }
        }
        return MistakeSchedule(state, m.dueAt ?: isoTime(timestamp(m.lastReviewedAt ?: last?.completedAt ?: m.updatedAt) + (interval * DAY).toLong()),
            interval, max(1.3, m.easeFactor ?: 2.5), m.repetitions ?: m.reviewHistory.count { it.result != ReviewRating.AGAIN },
            m.lapses ?: m.reviewHistory.count { it.result == ReviewRating.AGAIN }, m.resolved || state == ReviewState.REVIEW && interval >= 21)
    }
    fun previewMistakeReview(m: ExamTrackMistake, rating: ReviewRating, completedAt: String, preferences: MistakeReviewPreferences = MistakeReviewPreferences(), mistakes: List<ExamTrackMistake> = emptyList()): MistakeSchedule {
        val settings = preferences.normalized()
        val c = getMistakeSchedule(m)
        var ease = c.easeFactor
        var repetitions = c.repetitions
        var lapses = c.lapses
        val state: ReviewState
        var interval: Double
        val early = c.state == ReviewState.NEW || c.state == ReviewState.LEARNING
        when (rating) {
            ReviewRating.AGAIN -> {
                state = if (early) ReviewState.LEARNING else ReviewState.RELEARNING
                interval = if (c.state == ReviewState.REVIEW) max(1.0, round(c.intervalDays * 0.5)) else c.intervalDays
                ease = max(1.3, ease - 0.2); lapses++
            }
            ReviewRating.HARD -> {
                state = if (early) ReviewState.LEARNING else if (c.state == ReviewState.RELEARNING) ReviewState.RELEARNING else ReviewState.REVIEW
                interval = if (state == ReviewState.LEARNING || state == ReviewState.RELEARNING) 1.0 else max(1.0, round(max(1.0, c.intervalDays) * 1.2))
                ease = max(1.3, ease - 0.15); repetitions++
            }
            ReviewRating.GOOD -> {
                state = ReviewState.REVIEW
                interval = if (early) 3.0 else if (c.state == ReviewState.RELEARNING) max(2.0, c.intervalDays)
                    else max(c.intervalDays + 1, round(max(1.0, c.intervalDays) * ease))
                repetitions++
            }
            ReviewRating.EASY -> {
                state = ReviewState.REVIEW
                interval = if (early) 7.0 else max(7.0, round(max(1.0, c.intervalDays) * ease * 1.3))
                ease += 0.15; repetitions++
            }
        }
        // Successful early practice gets no extra overdue credit; late practice can grow gently.
        val elapsed = m.lastReviewedAt?.let { (timestamp(completedAt) - timestamp(it)).coerceAtLeast(0).toDouble() / DAY } ?: 0.0
        if (rating == ReviewRating.GOOD || rating == ReviewRating.EASY) {
            interval = round(interval * settings.spacingMultiplier + max(0.0, elapsed - c.intervalDays) * 0.25)
        }
        interval = if (rating == ReviewRating.AGAIN) settings.retryHours / 24.0
            else interval.coerceIn(1.0, settings.maximumIntervalDays.toDouble())
        return MistakeSchedule(state, pacedDueAt(m.id, isoTime(timestamp(completedAt) + (interval * DAY).toLong()), settings, mistakes, timestamp(completedAt)),
            interval, round(ease * 100) / 100, repetitions, lapses, state == ReviewState.REVIEW && interval >= 21)
    }
    fun recordMistakeReview(m: ExamTrackMistake, rating: ReviewRating, completedAt: String, reviewId: String, preferences: MistakeReviewPreferences = MistakeReviewPreferences(), mistakes: List<ExamTrackMistake> = emptyList(), confidence: String? = null, challengeWeek: String? = null, challengeSize: Int? = null): ExamTrackMistake {
        val next = previewMistakeReview(m, rating, completedAt, preferences, mistakes)
        val o = JSONObject(m.originalJson)
        val history = o.optJSONArray("reviewHistory") ?: JSONArray()
        history.put(JSONObject().put("id", reviewId).put("completedAt", completedAt).put("result", rating.wire)
            .put("intervalDays", next.intervalDays).put("easeFactor", next.easeFactor)
            .apply {
                confidence?.takeIf { it in listOf("low", "medium", "high") }?.let { put("confidence", it) }
                challengeWeek?.takeIf { runCatching { java.time.LocalDate.parse(it) }.isSuccess }?.let { put("challengeWeek", it) }
                challengeSize?.takeIf { it in 1..5 }?.let { put("challengeSize", it) }
            })
        o.put("reviewHistory", history).put("reviewState", next.state.wire).put("intervalDays", next.intervalDays)
            .put("easeFactor", next.easeFactor).put("repetitions", next.repetitions).put("lapses", next.lapses)
            .put("lastReviewedAt", completedAt).put("resolved", next.resolved).put("dueAt", next.dueAt).put("updatedAt", completedAt)
        return requireNotNull(ExamTrackMistakeCodec.decode(o.toString()))
    }
    private fun pacedDueAt(id: String, dueAt: String, settings: MistakeReviewPreferences,
        mistakes: List<ExamTrackMistake>, now: Long): String {
        val until = settings.lighterUntil?.let { timestamp(it) } ?: return dueAt
        val due = timestamp(dueAt)
        if (until <= now || due >= until) return dueAt
        val occupied = mistakes.filter { !it.suspended && it.id != id }.groupingBy {
            daySlot(timestamp(getMistakeSchedule(it).dueAt), now)
        }.eachCount()
        var slot = daySlot(due, now)
        if (settings.lighterDailyTarget == 0) slot = maxOf(slot, daySlot(until, now))
        val capacity = if (settings.lighterDailyTarget == 0) 10 else settings.lighterDailyTarget
        while ((occupied[slot] ?: 0) >= capacity) slot++
        return isoTime(maxOf(due, reviewDayStart(now, slot)))
    }

    /** Postpone only; overflow respects already-booked future dates and never adds reviews. */
    fun reduceReviews(mistakes: List<ExamTrackMistake>, days: Int, dailyTarget: Int,
        now: Long = System.currentTimeMillis()): Map<String, String> {
        require(days in 1..90 && dailyTarget in 0..100)
        val horizon = reviewDayStart(now, days.toLong())
        val active = mistakes.filterNot { it.suspended }
        val selected = active.filter { timestamp(getMistakeSchedule(it).dueAt) < horizon }
            .sortedWith(compareBy({ timestamp(getMistakeSchedule(it).dueAt) }, { it.id }))
        val occupied = mutableMapOf<Long, Int>()
        active.filter { timestamp(getMistakeSchedule(it).dueAt) >= horizon }.forEach {
            val day = daySlot(timestamp(getMistakeSchedule(it).dueAt), now)
            occupied[day] = (occupied[day] ?: 0) + 1
        }
        val changes = mutableMapOf<String, String>()
        var slot = 0L
        for (m in selected) {
            val due = timestamp(getMistakeSchedule(m).dueAt)
            slot = maxOf(slot, daySlot(due, now))
            // A zero target is a break; resume at a gentle ten cards per day.
            if (dailyTarget == 0) slot = maxOf(slot, days.toLong())
            val capacity = if (dailyTarget == 0) 10 else dailyTarget
            while ((occupied[slot] ?: 0) >= capacity) slot++
            occupied[slot] = (occupied[slot] ?: 0) + 1
            val next = if (slot == 0L) due else maxOf(due, reviewDayStart(now, slot))
            if (next > due) changes[m.id] = isoTime(next)
        }
        return changes
    }

    /** Calendar-day overdue status, matching Focal's schedule (not a rolling 24 hours). */
    fun overdueDays(dueAt: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val due = Instant.ofEpochMilli(timestamp(dueAt)).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(due, today).coerceAtLeast(0)
    }

    fun getOverdueMistakes(mistakes: List<ExamTrackMistake>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = mistakes
        .filter { !it.suspended && overdueDays(getMistakeSchedule(it).dueAt, now, zone) > 0 }
        .sortedBy { getMistakeSchedule(it).dueAt }

    fun getDueMistakes(mistakes: List<ExamTrackMistake>, now: Long = System.currentTimeMillis()) = mistakes
        .filter { !it.suspended && timestamp(getMistakeSchedule(it).dueAt) <= now }
        .sortedBy { getMistakeSchedule(it).dueAt }
}
