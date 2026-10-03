package com.folio.notes.progress

import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.MistakeScheduler
import org.json.JSONObject
import kotlin.math.*

data class SubjectOutlook(val subject: String, val attempts: Int, val current: Double, val next: Double,
    val low: Double, val high: Double, val momentum: Double, val spread: Double, val confidence: String)

/** Focal's relevance- and recency-weighted least-squares estimate. */
fun subjectOutlooks(exams: List<LoggedExam>, settings: JSONObject?): List<SubjectOutlook> = exams.groupBy { it.subject }.map { (subject, attempts) ->
    val values = attempts.sortedBy { it.completedAt }.map { performance(it, settings) }
    val scores = values.map { it.aligned }
    val recent = values.takeLast(3); val prior = values.dropLast(3).takeLast(3)
    fun weighted(list: List<ExamPerformance>) = list.sumOf { it.aligned * it.weight } / list.sumOf { it.weight }
    val current = weighted(recent)
    val momentum = if (prior.isNotEmpty()) current - weighted(prior) else if (scores.size >= 2) scores.last() - scores[scores.lastIndex - 1] else 0.0
    val latest = scores.takeLast(5); val spread = sqrt(latest.map { (it - latest.average()).pow(2) }.average())
    if (scores.size == 1) SubjectOutlook(subject, 1, current, current, (current - 10).coerceIn(0.0, 100.0),
        (current + 10).coerceIn(0.0, 100.0), momentum, spread, "low")
    else {
        val weights = values.mapIndexed { i, v -> .78.pow(scores.size - i - 1) * v.weight }
        val total = weights.sum(); val meanX = scores.indices.sumOf { it * weights[it] } / total
        val meanY = scores.indices.sumOf { scores[it] * weights[it] } / total
        val denominator = scores.indices.sumOf { weights[it] * (it - meanX).pow(2) }
        val slope = if (denominator > 0) scores.indices.sumOf { weights[it] * (it - meanX) * (scores[it] - meanY) } / denominator else 0.0
        val intercept = meanY - slope * meanX; val predicted = intercept + slope * scores.size
        val error = sqrt(scores.indices.sumOf { weights[it] * (scores[it] - (intercept + slope * it)).pow(2) } / total)
        val uncertainty = maxOf(3.0, error * 1.65, if (scores.size < 4) 7.0 else 0.0)
        SubjectOutlook(subject, scores.size, current, predicted.coerceIn(0.0, 100.0), (predicted - uncertainty).coerceIn(0.0, 100.0),
            (predicted + uncertainty).coerceIn(0.0, 100.0), momentum, spread, if (scores.size >= 6) "high" else if (scores.size >= 3) "medium" else "low")
    }
}.sortedBy { it.next }

data class MarkedQuestion(val exam: LoggedExam, val label: String, val earned: Double, val maximum: Double,
    val topic: String, val criterion: String, val confidence: String, val note: String) {
    val lost get() = maximum - earned
}
fun markedQuestions(exams: List<LoggedExam>) = exams.flatMap { exam -> exam.json.optJSONArray("questionResults")?.objects().orEmpty().mapNotNull { q ->
    val earned = q.number("marksAwarded"); val max = q.number("maxMarks")
    if (earned == null || max == null || max <= 0 || earned !in 0.0..max) null
    else MarkedQuestion(exam, q.optString("label"), earned, max, q.optString("areaOfStudy"), q.optString("criterion"),
        q.optString("confidence", "medium"), q.optString("examinerNote"))
} }

data class FocusPriority(val subject: String, val topic: String, val priority: Double, val mastery: Double?, val lost: Double,
    val available: Double, val questions: Int, val confidenceRisk: Double, val unresolved: Int, val lapses: Int)
fun focusPriorities(exams: List<LoggedExam>, mistakes: List<ExamTrackMistake>): List<FocusPriority> {
    val questions = markedQuestions(exams).filter { it.topic.isNotBlank() }.groupBy { it.exam.subject to it.topic.trim() }
    val byId = exams.associateBy { it.id }
    val cards = mistakes.filter { !it.suspended && !it.areaOfStudy.isNullOrBlank() && it.attemptId in byId }
        .groupBy { byId.getValue(it.attemptId).subject to it.areaOfStudy!!.trim() }
    return (questions.keys + cards.keys).map { (subject, topic) ->
        val q = questions[subject to topic].orEmpty(); val schedules = cards[subject to topic].orEmpty().map { MistakeScheduler.getMistakeSchedule(it) }
        val available = q.sumOf { it.maximum }; val earned = q.sumOf { it.earned }; val lost = q.sumOf { it.lost }
        val mastery = if (available > 0) earned / available * 100 else null
        val risk = if (q.isNotEmpty()) (q.count { it.confidence == "low" } + q.count { it.confidence == "medium" } * .5) / q.size * 100 else 0.0
        val unresolved = schedules.count { !it.resolved }; val lapses = schedules.sumOf { it.lapses }
        val markRisk = mastery?.let { 100 - it } ?: if (unresolved > 0) 50.0 else 0.0
        val reviewRisk = min(100.0, unresolved * 25.0 + lapses * 10.0)
        FocusPriority(subject, topic, (markRisk * .6 + risk * .25 + reviewRisk * .15).coerceIn(0.0, 100.0), mastery,
            lost, available, q.size, risk, unresolved, lapses)
    }.sortedWith(compareByDescending<FocusPriority> { it.priority }.thenByDescending { it.lost })
}

data class ContextInsight(val label: String, val count: Int, val correlation: Double, val change: Double, val condition: String)
fun contextInsights(exams: List<LoggedExam>): List<ContextInsight> {
    val means = exams.groupBy { it.subject }.mapValues { (_, group) -> group.map { it.percentage }.average() }
    val factors = listOf(Triple("sleepHours", "Sleep", 1.0), Triple("energy", "Energy", 2.0), Triple("focus", "Focus", 2.0),
        Triple("stress", "Stress", -2.0), Triple("confidence", "Confidence", 2.0), Triple("preparedness", "Preparedness", 2.0))
    return factors.mapNotNull { (key, label, step) ->
        val pairs = exams.mapNotNull { exam -> exam.json.optJSONObject("performanceContext")?.number(key)?.let { it to exam.percentage - means.getValue(exam.subject) } }
        if (pairs.size < 3 || pairs.map { it.first }.distinct().size < 2) return@mapNotNull null
        val xMean = pairs.map { it.first }.average(); val yMean = pairs.map { it.second }.average()
        val numerator = pairs.sumOf { (it.first - xMean) * (it.second - yMean) }
        val xSpread = pairs.sumOf { (it.first - xMean).pow(2) }; val ySpread = pairs.sumOf { (it.second - yMean).pow(2) }
        val correlation = if (xSpread > 0 && ySpread > 0) numerator / sqrt(xSpread * ySpread) else 0.0
        ContextInsight(label, pairs.size, correlation, if (xSpread > 0) numerator / xSpread * step else 0.0,
            when (key) { "sleepHours" -> "an extra hour of sleep"; "stress" -> "2 points less stress"; else -> "2 points more ${label.lowercase()}" })
    }.sortedByDescending { abs(it.change) }
}
