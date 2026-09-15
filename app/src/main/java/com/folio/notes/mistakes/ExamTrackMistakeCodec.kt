package com.folio.notes.mistakes

import org.json.JSONArray
import org.json.JSONObject

object ExamTrackMistakeCodec {
    fun decode(payload: String, rowId: String? = null): ExamTrackMistake? = runCatching {
        val o = JSONObject(payload)
        fun required(key: String) = (o.get(key) as String)
        fun optional(key: String) = o.opt(key) as? String
        fun date(key: String) = optional(key)?.takeIf { runCatching { timestamp(it) }.isSuccess }
        fun number(key: String) = (o.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        fun count(key: String) = number(key)?.takeIf { it % 1.0 == 0.0 && it <= Int.MAX_VALUE }?.toInt()
        val id = required("id").also { require(it.isNotBlank() && (rowId == null || it == rowId)) }
        val created = requireNotNull(date("createdAt"))
        val updated = date("updatedAt") ?: created
        val reviews = o.optJSONArray("reviewHistory").objects().map { r ->
            val result = requireNotNull(ReviewRating.parse(r.getString("result")))
            val completed = r.getString("completedAt").also { timestamp(it) }
            MistakeReview(r.getString("id"), completed, result, r.finite("intervalDays"), r.finite("easeFactor"))
        }
        val attachments = o.optJSONArray("attachments").objects().map { a ->
            MistakeAttachment(a.getString("id"), a.getString("name"), a.getString("type"),
                a.getLong("size").also { require(it >= 0) }, a.getString("storagePath"))
        }
        ExamTrackMistake(id, required("attemptId"), required("question"), optional("questionText"),
            required("category"), required("explanation"), required("correction"), number("totalMarks"),
            number("marksLost"), optional("areaOfStudy"), optional("criterion"), attachments, date("dueAt"), reviews,
            ReviewState.entries.find { it.wire == optional("reviewState") }, number("intervalDays"), number("easeFactor"),
            count("repetitions"), count("lapses"), date("lastReviewedAt"), o.optBoolean("suspended", false),
            o.optBoolean("resolved", false), created, updated, payload)
    }.getOrNull()

    fun encode(mistake: ExamTrackMistake): String = mistake.originalJson
    internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.finite(key: String) = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
}
