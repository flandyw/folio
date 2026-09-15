package com.folio.notes.mistakes

import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

enum class ReviewRating { AGAIN, HARD, GOOD, EASY;
    val wire get() = name.lowercase()
    companion object {
        fun parse(value: String): ReviewRating? = when (value) {
            "incorrect" -> AGAIN; "assisted" -> HARD; "correct" -> GOOD
            else -> entries.find { it.wire == value }
        }
    }
}
enum class ReviewState { NEW, LEARNING, REVIEW, RELEARNING;
    val wire get() = name.lowercase()
}
data class MistakeReview(val id: String, val completedAt: String, val result: ReviewRating,
    val intervalDays: Double?, val easeFactor: Double?)
data class MistakeAttachment(val id: String, val name: String, val type: String, val size: Long, val storagePath: String)
/** Immutable original JSON is the write base; parsing never drops forward-compatible fields. */
data class ExamTrackMistake(
    val id: String, val attemptId: String, val question: String, val questionText: String?,
    val category: String, val explanation: String, val correction: String,
    val totalMarks: Double?, val marksLost: Double?, val areaOfStudy: String?, val criterion: String?,
    val attachments: List<MistakeAttachment>, val dueAt: String?, val reviewHistory: List<MistakeReview>,
    val reviewState: ReviewState?, val intervalDays: Double?, val easeFactor: Double?,
    val repetitions: Int?, val lapses: Int?, val lastReviewedAt: String?, val suspended: Boolean,
    val resolved: Boolean, val createdAt: String, val updatedAt: String, val originalJson: String
)
data class MistakeSchedule(val state: ReviewState, val dueAt: String, val intervalDays: Double,
    val easeFactor: Double, val repetitions: Int, val lapses: Int, val resolved: Boolean)
/** Local only, also embedded in the practice notebook's backup. Never sent to Supabase. */
data class LocalMistakeReviewAttempt(val userId: String, val mistakeId: String, val reviewId: String,
    val practiceNotebookId: String, val practicePageId: String, val completedAt: String? = null,
    val rating: String? = null) {
    fun encode() = JSONObject().put("userId", userId).put("mistakeId", mistakeId).put("reviewId", reviewId)
        .put("practiceNotebookId", practiceNotebookId).put("practicePageId", practicePageId)
        .put("completedAt", completedAt).put("rating", rating)
    companion object {
        fun decode(o: JSONObject) = LocalMistakeReviewAttempt(o.getString("userId"), o.getString("mistakeId"),
            o.getString("reviewId"), o.getString("practiceNotebookId"), o.getString("practicePageId"),
            o.opt("completedAt") as? String, o.opt("rating") as? String)
    }
}
internal val isoFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
fun isoTime(millis: Long = System.currentTimeMillis()): String = isoFormatter.format(Instant.ofEpochMilli(millis))
internal fun timestamp(value: String): Long = Instant.parse(value).toEpochMilli()
