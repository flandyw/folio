package com.folio.notes.mistakes.review

import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.LocalMistakeReviewAttempt
import com.folio.notes.mistakes.sync.ExamContext
import com.folio.notes.study.FocalStudyEntry
import com.folio.notes.study.FocalStudyInterval
import com.folio.notes.study.FocalSubject
import com.folio.notes.study.FocalSubjectSwitch
import com.folio.notes.study.focalSubjectSwitchesJson
import com.folio.notes.study.FocalSubjects
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One practice queue, with ordinary timer intervals and question details in matching order. */
internal class MistakeStudySession(private val userId: String) {
    private val id = UUID.randomUUID().toString()
    private val intervals = mutableListOf<FocalStudyInterval>()
    private val questions = mutableListOf<JSONObject>()

    fun resume(attempt: LocalMistakeReviewAttempt, card: ExamTrackMistake, context: ExamContext?,
               subjects: List<FocalSubject>, now: Long) {
        if (attempt.userId != userId || (intervals.isNotEmpty() && intervals.last().endAt == null)) return
        val source = context?.subject.orEmpty().trim()
        val subjectId = FocalSubjects.find(source, subjects)
        val subject = subjects.firstOrNull { it.id == subjectId }?.name ?: source
        val start = maxOf(now, intervals.lastOrNull()?.endAt ?: now)
        intervals += FocalStudyInterval(start, null)
        questions += JSONObject().put("mistakeId", card.id).put("reviewId", attempt.reviewId)
            .put("question", card.question).put("subject", subject).put("subjectId", subjectId)
            .put("exam", context?.title.orEmpty()).put("paper", context?.paper.orEmpty())
    }

    fun pause(now: Long) {
        val last = intervals.lastOrNull() ?: return
        if (last.endAt != null) return
        intervals[intervals.lastIndex] = last.copy(endAt = now.coerceAtLeast(last.startAt))
    }

    fun entry(now: Long, completed: Boolean = false): FocalStudyEntry? {
        if (intervals.isEmpty()) return null
        val subjects = questions.map { it.optString("subject").ifBlank { "Uncategorised" } }.distinct()
        // One session, one subject at a time: each question's interval is filed under its own subject.
        val switches = questions.indices.mapNotNull { index -> questions[index].optString("subjectId").takeIf(String::isNotBlank)
            ?.let { FocalSubjectSwitch(intervals[index].startAt, it) } }
            .fold(listOf<FocalSubjectSwitch>()) { list, item -> if (list.lastOrNull()?.subjectId == item.subjectId) list else list + item }
        val description = questions.distinctBy { it.getString("reviewId") }.joinToString("\n") { question ->
            listOf("subject", "exam", "paper", "question").map { question.optString(it) }
                .filter(String::isNotBlank).joinToString(" · ")
        }
        val details = JSONArray().also { array -> questions.forEachIndexed { index, question ->
            array.put(JSONObject(question.toString()).put("startAt", intervals[index].startAt)
                .put("endAt", intervals[index].endAt))
        } }
        return FocalStudyEntry(id = id, notebookId = null,
            title = "Mistake practice · ${subjects.joinToString(" & ")}".take(512),
            subjectId = switches.lastOrNull()?.subjectId, kind = "study", userId = userId,
            startedAt = intervals.first().startAt, endedAt = intervals.last().endAt ?: now,
            activeMillis = intervals.sumOf { ((it.endAt ?: now) - it.startAt).coerceAtLeast(0L) },
            intervals = intervals.toList(), completed = completed, paused = intervals.last().endAt != null,
            notes = description,
            metadata = JSONObject().put("mistakePractice", true).put("description", description).put("subjectSwitches", focalSubjectSwitchesJson(switches))
                .put("examtrack", JSONObject().put("questions", details)).toString())
    }
}

/** Start on a chosen card, then visit the rest once, preserving the queue's order and limit. */
internal fun practiceQueue(candidates: List<ExamTrackMistake>, limit: Int,
                           first: ExamTrackMistake? = null, shuffle: Boolean = false): List<ExamTrackMistake> {
    val remaining = candidates.distinctBy { it.id }.filterNot { it.id == first?.id }
        .let { if (shuffle) it.shuffled() else it }
    return (listOfNotNull(first) + remaining).take(limit.coerceAtLeast(1))
}
