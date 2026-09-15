package com.folio.notes.mistakes

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max

/** Port of examtrack/src/lib/exam-data.ts; no Android dependencies. */
object MistakeScheduler {
    private const val DAY = 86_400_000L
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
    fun previewMistakeReview(m: ExamTrackMistake, rating: ReviewRating, completedAt: String): MistakeSchedule {
        val c = getMistakeSchedule(m)
        var ease = c.easeFactor
        var repetitions = c.repetitions
        var lapses = c.lapses
        val state: ReviewState
        val interval: Double
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
        return MistakeSchedule(state, isoTime(timestamp(completedAt) + if (rating == ReviewRating.AGAIN) 600_000L else (interval * DAY).toLong()),
            interval, round(ease * 100) / 100, repetitions, lapses, state == ReviewState.REVIEW && interval >= 21)
    }
    fun recordMistakeReview(m: ExamTrackMistake, rating: ReviewRating, completedAt: String, reviewId: String): ExamTrackMistake {
        val next = previewMistakeReview(m, rating, completedAt)
        val o = JSONObject(m.originalJson)
        val history = o.optJSONArray("reviewHistory") ?: JSONArray()
        history.put(JSONObject().put("id", reviewId).put("completedAt", completedAt).put("result", rating.wire)
            .put("intervalDays", next.intervalDays).put("easeFactor", next.easeFactor))
        o.put("reviewHistory", history).put("reviewState", next.state.wire).put("intervalDays", next.intervalDays)
            .put("easeFactor", next.easeFactor).put("repetitions", next.repetitions).put("lapses", next.lapses)
            .put("lastReviewedAt", completedAt).put("resolved", next.resolved).put("dueAt", next.dueAt).put("updatedAt", completedAt)
        return requireNotNull(ExamTrackMistakeCodec.decode(o.toString()))
    }
    fun getDueMistakes(mistakes: List<ExamTrackMistake>, now: Long = System.currentTimeMillis()) = mistakes
        .filter { !it.suspended && timestamp(getMistakeSchedule(it).dueAt) <= now }
        .sortedBy { getMistakeSchedule(it).dueAt }
}
