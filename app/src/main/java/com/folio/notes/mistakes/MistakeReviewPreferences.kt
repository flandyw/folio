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
) {
    fun normalized() = copy(
        spacingMultiplier = spacingMultiplier.takeIf { it.isFinite() }?.coerceIn(0.5, 3.0) ?: 1.5,
        retryHours = retryHours.takeIf { it.isFinite() }?.coerceIn(1.0 / 6, 168.0) ?: 24.0,
        maximumIntervalDays = maximumIntervalDays.coerceIn(7, 365),
        lighterUntil = lighterUntil?.takeIf { runCatching { timestamp(it) }.isSuccess },
        lighterDailyTarget = lighterDailyTarget.coerceIn(0, 100),
    )
    fun encode(): JSONObject = normalized().let { JSONObject().put("spacingMultiplier", it.spacingMultiplier)
        .put("retryHours", it.retryHours).put("maximumIntervalDays", it.maximumIntervalDays).put("updatedAt", it.updatedAt).put("lighterUntil", it.lighterUntil).put("lighterDailyTarget", it.lighterDailyTarget) }
    companion object {
        const val ROW_ID = "mistakeReviewPreferences"
        fun decode(o: JSONObject?) = if (o == null) MistakeReviewPreferences() else MistakeReviewPreferences(
            o.optDouble("spacingMultiplier", 1.5), o.optDouble("retryHours", 24.0),
            o.optInt("maximumIntervalDays", 180), o.opt("lighterUntil") as? String, o.optInt("lighterDailyTarget", 5),
            o.optString("updatedAt", "1970-01-01T00:00:00.000Z"),
        ).normalized()
    }
}
