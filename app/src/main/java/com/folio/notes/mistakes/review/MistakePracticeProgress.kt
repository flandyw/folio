package com.folio.notes.mistakes.review

import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.MistakeReview
import com.folio.notes.mistakes.MistakeScheduler
import com.folio.notes.mistakes.ReviewRating
import com.folio.notes.mistakes.timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

internal data class PracticeQuest(val id: String, val label: String, val detail: String, val done: Int, val target: Int)
internal data class PracticeFinish(val id: String, val label: String, val xp: Int, val color: Long)
internal data class PracticeProgress(val xp: Int, val todayCount: Int, val quests: List<PracticeQuest>, val week: String,
    val challenge: List<ExamTrackMistake>, val weeklyCount: Int, val weeklyTarget: Int, val weeklyComplete: Boolean,
    val calibration: Int?, val predictions: Int, val established: Int) {
    val level get() = xp / 100 + 1
}

/** Same rules as Focal's mistake-practice.ts, using the existing shared review ledger. */
internal object MistakePracticeProgress {
    val finishes = listOf(PracticeFinish("graphite", "Graphite", 0, 0xff687582), PracticeFinish("moss", "Moss", 100, 0xff59846c),
        PracticeFinish("cobalt", "Cobalt", 300, 0xff668bd0), PracticeFinish("amber", "Amber", 600, 0xffc89a49))
    private fun day(at: String) = Instant.parse(at).atZone(ZoneId.systemDefault()).toLocalDate()
    fun weekOf(date: LocalDate) = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
    fun history(m: ExamTrackMistake) = m.reviewHistory.distinctBy { it.id }.sortedWith(compareBy<MistakeReview> { timestamp(it.completedAt) }.thenBy { it.id })
    private fun recalled(rating: ReviewRating) = rating == ReviewRating.GOOD || rating == ReviewRating.EASY
    private fun shaky(rating: ReviewRating?) = rating == ReviewRating.AGAIN || rating == ReviewRating.HARD
    fun rematch(m: ExamTrackMistake) = shaky(history(m).lastOrNull()?.result)
    fun strength(m: ExamTrackMistake) = history(m).filter { recalled(it.result) }.map { day(it.completedAt) }.distinct().size.coerceAtMost(3)
    fun missionCards(mistakes: List<ExamTrackMistake>, mission: String, now: Long): List<ExamTrackMistake> {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return mistakes.filter { m ->
            val history = history(m)
            !m.suspended && (mission != "rematch" || rematch(m)) && history.withIndex().none { (i, r) ->
                day(r.completedAt) == today && when (mission) {
                    "confidence" -> r.confidence != null
                    "rematch" -> i > 0 && shaky(history[i - 1].result)
                    else -> true
                }
            }
        }.sortedWith(compareBy<ExamTrackMistake> { timestamp(MistakeScheduler.getMistakeSchedule(it).dueAt) }.thenBy { it.id })
    }
    fun feedback(m: ExamTrackMistake, rating: ReviewRating, confidence: String?): String = when {
        recalled(rating) && confidence == "low" -> "Recalled it, even with low confidence."
        recalled(rating) && rematch(m) -> "Rematch recovered."
        confidence == "high" && shaky(rating) -> "Blind spot spotted. Added to your rematches."
        recalled(rating) -> "Recall strengthened."
        else -> "Attempt saved. A rematch is ready when you are."
    }
    private class Daily {
        val cards = mutableSetOf<String>(); val rematches = mutableSetOf<String>(); val predictions = mutableSetOf<String>()
        val xp get() = cards.size * 10 + (if (cards.size >= 5) 20 else 0) + (if (rematches.size >= 2) 20 else 0) + (if (predictions.size >= 3) 20 else 0)
    }
    private class Weekly { val cards = mutableSetOf<String>(); var target = 0 }
    fun get(mistakes: List<ExamTrackMistake>, now: Long = System.currentTimeMillis()): PracticeProgress {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val week = weekOf(today)
        val days = mutableMapOf<LocalDate, Daily>()
        val weeks = mutableMapOf<String, Weekly>()
        var calibrated = 0; var predictions = 0
        for (m in mistakes) {
            var previous: ReviewRating? = null
            for (r in history(m)) {
                if (timestamp(r.completedAt) > now) continue
                val entry = days.getOrPut(day(r.completedAt)) { Daily() }
                entry.cards += m.id
                if (shaky(previous)) entry.rematches += m.id
                if (r.confidence != null) {
                    entry.predictions += m.id
                    predictions++
                    if (r.confidence == "high" && recalled(r.result) || r.confidence == "medium" && r.result == ReviewRating.HARD || r.confidence == "low" && r.result == ReviewRating.AGAIN) calibrated++
                }
                if (r.challengeWeek != null && r.challengeSize != null && weekOf(day(r.completedAt)) == r.challengeWeek) {
                    val challenge = weeks.getOrPut(r.challengeWeek) { Weekly() }
                    challenge.cards += m.id
                    challenge.target = maxOf(challenge.target, r.challengeSize)
                }
                previous = r.result
            }
        }
        // ponytail: derive XP instead of syncing a second counter; retries cannot award it twice.
        val xp = days.values.sumOf { it.xp } + weeks.values.count { it.cards.size >= it.target } * 75
        val current = days[today]
        val weekly = weeks[week]
        val active = mistakes.filterNot { it.suspended }
        // ponytail: deterministic IDs match Focal; upgrade to topic balancing if the collection grows.
        val pool = active.filter { m -> history(m).any { day(it.completedAt).toString() < week } }.sortedBy { it.id }
        val offset = if (pool.isEmpty()) 0 else (LocalDate.parse(week).toEpochDay() / 7 % pool.size).toInt()
        val challenge = (pool.drop(offset) + pool.take(offset)).take(5)
        return PracticeProgress(xp, current?.cards?.size ?: 0, listOf(
            PracticeQuest("daily", "Put in five", "Review five different mistakes", current?.cards?.size ?: 0, 5),
            PracticeQuest("rematch", "Second look", "Revisit two difficult mistakes", current?.rematches?.size ?: 0, 2),
            PracticeQuest("confidence", "Call your confidence", "Predict your recall on three cards", current?.predictions?.size ?: 0, 3)),
            week, challenge, weekly?.cards?.size ?: 0, weekly?.target ?: challenge.size,
            weekly != null && weekly.cards.size >= weekly.target, if (predictions > 0) (calibrated.toDouble() / predictions * 100).roundToInt() else null,
            predictions, active.count { strength(it) == 3 && !rematch(it) })
    }
    fun nextWindow(preferences: MistakeReviewPreferences, now: Long): java.time.ZonedDateTime? {
        val settings = preferences.normalized()
        val current = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        for (offset in 0L..7L) {
            val slot = current.toLocalDate().plusDays(offset).atTime(LocalTime.parse(settings.practiceTime)).atZone(current.zone)
            if (slot.dayOfWeek.value in settings.practiceDays && slot.plusHours(1).toInstant().toEpochMilli() > now &&
                slot.toInstant().toEpochMilli() >= (settings.practiceSnoozedUntil?.let { timestamp(it) } ?: 0)) return slot
        }
        return null
    }
}
