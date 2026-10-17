package com.folio.notes.mistakes

import org.json.JSONObject

/** Shared user_state/mistakeReviewPreferences; defaults are practical, spaced suggestions. */
data class MistakeReviewPreferences(
    val spacingMultiplier: Double = 1.5,
    val retryHours: Double = 24.0,
    val maximumIntervalDays: Int = 180,
    val lighterUntil: String? = null,
    val lighterDailyTarget: Int = 5,
    val updatedAt: String = "1970-01-01T00:00:00.000Z",
    val practiceDays: List<Int> = listOf(1, 2, 3, 4, 5),
    val practiceTime: String = "17:00",
    val practiceTarget: Int = 5,
    val practiceSnoozedUntil: String? = null,
    val practiceEffects: Boolean = true,
    val practiceSound: Boolean = false,
    val practiceFinish: String = "graphite",
) {
    fun normalized() = copy(
        spacingMultiplier = spacingMultiplier.takeIf { it.isFinite() }?.coerceIn(0.5, 3.0) ?: 1.5,
        retryHours = retryHours.takeIf { it.isFinite() }?.coerceIn(1.0 / 6, 168.0) ?: 24.0,
        maximumIntervalDays = maximumIntervalDays.coerceIn(7, 365),
        lighterUntil = lighterUntil?.takeIf { runCatching { timestamp(it) }.isSuccess },
        lighterDailyTarget = lighterDailyTarget.coerceIn(0, 100),
        practiceDays = practiceDays.filter { it in 1..7 }.distinct().sorted(),
        practiceTime = practiceTime.takeIf { Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(it) } ?: "17:00",
        practiceTarget = practiceTarget.coerceIn(1, 20),
        practiceSnoozedUntil = practiceSnoozedUntil?.takeIf { runCatching { timestamp(it) }.isSuccess },
        practiceFinish = practiceFinish.takeIf { it in listOf("graphite", "moss", "cobalt", "amber") } ?: "graphite",
    )
    fun encode(): JSONObject = normalized().let { JSONObject().put("spacingMultiplier", it.spacingMultiplier)
        .put("retryHours", it.retryHours).put("maximumIntervalDays", it.maximumIntervalDays).put("updatedAt", it.updatedAt).put("lighterUntil", it.lighterUntil).put("lighterDailyTarget", it.lighterDailyTarget)
        .put("practiceDays", org.json.JSONArray(it.practiceDays)).put("practiceTime", it.practiceTime).put("practiceTarget", it.practiceTarget)
        .put("practiceSnoozedUntil", it.practiceSnoozedUntil).put("practiceEffects", it.practiceEffects).put("practiceSound", it.practiceSound).put("practiceFinish", it.practiceFinish) }
    companion object {
        const val ROW_ID = "mistakeReviewPreferences"
        fun decode(o: JSONObject?) = if (o == null) MistakeReviewPreferences() else MistakeReviewPreferences(
            o.optDouble("spacingMultiplier", 1.5), o.optDouble("retryHours", 24.0),
            o.optInt("maximumIntervalDays", 180), o.opt("lighterUntil") as? String, o.optInt("lighterDailyTarget", 5),
            o.optString("updatedAt", "1970-01-01T00:00:00.000Z"),
            o.optJSONArray("practiceDays")?.let { days -> (0 until days.length()).mapNotNull { i -> (days.opt(i) as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..7.0 }?.toInt() } } ?: listOf(1, 2, 3, 4, 5),
            o.optString("practiceTime", "17:00"), o.optInt("practiceTarget", 5), o.opt("practiceSnoozedUntil") as? String,
            o.optBoolean("practiceEffects", true), o.optBoolean("practiceSound", false), o.optString("practiceFinish", "graphite"),
        ).normalized()
    }
}
