package com.folio.notes.exam

import com.folio.notes.model.Notebook
import com.folio.notes.model.Paper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The VCE studies this app is aimed at. Each study carries a colour band and the paper a fresh
 * notebook starts on, so maths subjects open on the maths grid while report subjects open ruled.
 * The list is a suggestion, not a boundary: any subject can also be typed as text.
 */
enum class VceSubject(
    val label: String,
    /** Default paper for a new notebook of this subject. */
    val paper: Paper,
    /** Colour band shown on covers and filter chips, as 0xFFRRGGBB. */
    val color: Long
) {
    GENERAL_MATHS("General Maths", Paper.MATH_GRID, 0xFF2F6F4E),
    MATHS_METHODS("Maths Methods", Paper.MATH_GRID, 0xFF33638C),
    SPECIALIST_MATHS("Specialist Maths", Paper.MATH_GRID, 0xFF6A4C93),
    PHYSICS("Physics", Paper.GRAPH, 0xFF8C5A2B),
    CHEMISTRY("Chemistry", Paper.GRID, 0xFF2B7A78),
    BIOLOGY("Biology", Paper.DOTS, 0xFF5A7D2A),
    PHYSICAL_EDUCATION("Physical Education", Paper.DOTS, 0xFF5A7D2A),
    PSYCHOLOGY("Psychology", Paper.DOTS, 0xFF8C3A5B),
    ENGLISH("English", Paper.RULED, 0xFF8A5A44),
    LITERATURE("Literature", Paper.RULED, 0xFF7A4E62),
    LEGAL_STUDIES("Legal Studies", Paper.RULED, 0xFF55606E),
    ECONOMICS("Economics", Paper.RULED, 0xFF8C7326),
    ACCOUNTING("Accounting", Paper.GRID, 0xFF4E6E58),
    OTHER("Other", Paper.DOTS, 0xFF606A60);

    companion object {
        fun safeValueOf(name: String?): VceSubject? =
            name?.let { value -> entries.find { it.name == value } }

        // Lowercased labels/words computed once so typed-subject matching never allocates per call.
        private val lowerLabels: Map<VceSubject, String> by lazy { entries.associateWith { it.label.lowercase() } }
        private val lowerWords: Map<VceSubject, List<String>> by lazy {
            entries.associateWith { lowerLabels.getValue(it).split(' ') }
        }

        /**
         * Matches a typed subject to a study, so "methods" and "Maths Methods" agree and a
         * fragment like "chem" finds Chemistry. Exact name and label wins before fragments do.
         */
        fun match(text: String): VceSubject? {
            val query = text.trim().lowercase()
            if (query.isEmpty()) return null
            entries.find { it.name.equals(query, true) || lowerLabels.getValue(it) == query }?.let { return it }
            return entries.find { subject ->
                lowerWords.getValue(subject).any { word ->
                    (word.length >= 4 && query.contains(word)) || (query.length >= 4 && word.contains(query))
                }
            }
        }
    }
}

/** The kind of assessment a notebook holds, shown on the cover and used in the score roll-up. */
enum class ExamType(val label: String) {
    EXAM_1("Exam 1"), EXAM_2("Exam 2"), EXAM("Exam"), SAC("SAC"), TOPIC_TEST("Topic test"), NOTES("Notes");

    /** Only real papers feed the exam log, results and upcoming-exam lists; SACs, topic tests and notes do not. */
    val isExam: Boolean get() = this == EXAM_1 || this == EXAM_2 || this == EXAM

    companion object { fun safeValueOf(name: String?): ExamType? = name?.let { runCatching { valueOf(it) }.getOrNull() } }
}

/** Marks on the official VCAA paper, used to prefill when a document does not state its total. */
object VcaaMarks {
    fun total(subject: VceSubject?, type: ExamType?): Int? = when (subject) {
        VceSubject.MATHS_METHODS, VceSubject.SPECIALIST_MATHS -> when (type) {
            ExamType.EXAM_1 -> 40
            ExamType.EXAM_2 -> 80
            else -> null
        }
        else -> null
    }
}

/** Free quick labels a student pins onto an exam paper. */
enum class ExamTagType(val label: String) {
    HARD("Hard"), REDO("Redo"), DONE("Done"), BOUND_REFERENCE("Bound ref")
}

/**
 * Exam metadata on a notebook, kept deliberately loose: subject can be any text, so a study that is
 * not in [VceSubject] still works, and every field defaults to blank rather than forcing a choice.
 */
data class ExamTags(
    val subject: VceSubject? = null,
    /** Textual subject when none of the built-in studies fit; ignored while [subject] is set. */
    val subjectText: String = "",
    val year: Int? = null,
    /** Company or source: VCAA, NEAP, TSSM, Insight, Heffernan, Edrolo… */
    val company: String = "",
    val type: ExamType? = null,
    /** Unit 1–4; null when the paper is not tied to a unit. */
    val unit: Int? = null,
    /** 1–3 stars of self-rated difficulty; null when unrated. */
    val difficulty: Int? = null,
    /** Marks available, e.g. 40 for a Methods Exam 1. */
    val marksTotal: Int? = null,
    /** Status of this paper: to do, in progress, marked, or redone. */
    val status: ExamStatus = ExamStatus.TO_DO,
    /** Quick labels such as Hard or Redo. */
    val tags: Set<ExamTagType> = emptySet(),
    /** The real exam date, when this notebook is practice for a sitting that is coming up. */
    val examDate: Long? = null
) {
    /** The subject as it is displayed, preferring a built-in study over typed text. */
    val subjectLabel: String get() = subject?.label ?: subjectText
    /** True when any exam field has been filled in, so an untagged notebook keeps a plain cover. */
    val isTagged: Boolean get() = subject != null || subjectText.isNotBlank() || year != null ||
        company.isNotBlank() || type != null || unit != null || difficulty != null ||
        marksTotal != null || status != ExamStatus.TO_DO || tags.isNotEmpty() || examDate != null
    /** Short cover line, e.g. "VCAA 2022 · Exam 1" or "Methods SAC". */
    fun summaryLine(): String = listOfNotNull(
        company.ifBlank { null }, year?.toString(), type?.label
    ).joinToString(" · ").ifBlank { subjectLabel }
}

/** How far a paper has got: waiting, being worked, marked, or worked through a second time. */
enum class ExamStatus(val label: String) {
    TO_DO("To do"), IN_PROGRESS("In progress"), MARKED("Marked"), REDONE("Redone");

    companion object { fun safeValueOf(name: String?): ExamStatus? = name?.let { runCatching { valueOf(it) }.getOrNull() } }
}

/** One marked sitting of a paper: what it scored and, when timed, how long it took. */
data class ExamAttempt(
    val id: String = UUID.randomUUID().toString(),
    /** Marks awarded, in the same scale as [ExamTags.marksTotal]. */
    val score: Int,
    /** Marks available when the attempt was sat, so a paper's total can change later. */
    val total: Int? = null,
    /** When the attempt was sat or marked. */
    val date: Long = System.currentTimeMillis(),
    /** Seconds spent, when sat under the exam timer. */
    val secondsTaken: Int? = null,
    /** True when the sitting was timed, so redo-after-timing comparisons stay honest. */
    val timed: Boolean = false
) {
    /** Share of the paper's total marks, 0..1, or null while the total is unknown. */
    val share: Float? get() = total?.takeIf { it > 0 }?.let { (score.toFloat() / it).coerceIn(0f, 1f) }
}

// ---- Codec ---------------------------------------------------------------------------------------

/** JSON round-trips for exam metadata, shared by the index codec and the portable codec. */
object ExamTagsCodec {
    fun encode(tags: ExamTags): JSONObject = JSONObject().apply {
        tags.subject?.let { put("subject", it.name) }
        if (tags.subjectText.isNotBlank()) put("subjectText", tags.subjectText)
        tags.year?.let { put("year", it) }
        if (tags.company.isNotBlank()) put("company", tags.company)
        tags.type?.let { put("type", it.name) }
        tags.unit?.let { put("unit", it) }
        tags.difficulty?.let { put("difficulty", it) }
        tags.marksTotal?.let { put("marksTotal", it) }
        if (tags.status != ExamStatus.TO_DO) put("status", tags.status.name)
        if (tags.tags.isNotEmpty()) put("tags", JSONArray().apply { tags.tags.forEach { put(it.name) } })
        tags.examDate?.let { put("examDate", it) }
    }

    fun decode(o: JSONObject?): ExamTags {
        if (o == null) return ExamTags()
        return ExamTags(
            subject = if (o.has("subject") && !o.isNull("subject")) VceSubject.safeValueOf(o.optString("subject")) else null,
            subjectText = o.optString("subjectText", ""),
            year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
            company = o.optString("company", ""),
            type = if (o.has("type") && !o.isNull("type")) ExamType.safeValueOf(o.optString("type")) else null,
            unit = if (o.has("unit") && !o.isNull("unit")) o.optInt("unit") else null,
            difficulty = if (o.has("difficulty") && !o.isNull("difficulty")) o.optInt("difficulty") else null,
            marksTotal = if (o.has("marksTotal") && !o.isNull("marksTotal")) o.optInt("marksTotal") else null,
            status = if (o.has("status") && !o.isNull("status")) ExamStatus.safeValueOf(o.optString("status")) ?: ExamStatus.TO_DO else ExamStatus.TO_DO,
            tags = o.optJSONArray("tags")?.let { array ->
                (0 until array.length()).mapNotNull { ExamTagType.entries.find { t -> t.name == array.optString(it) } }.toSet()
            } ?: emptySet(),
            examDate = if (o.has("examDate") && !o.isNull("examDate")) o.optLong("examDate") else null
        )
    }

    fun encodeAttempts(attempts: List<ExamAttempt>): JSONArray = JSONArray().apply {
        attempts.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("score", a.score); put("date", a.date)
            a.total?.let { put("total", it) }
            a.secondsTaken?.let { put("seconds", it) }
            if (a.timed) put("timed", true)
        }) }
    }

    fun decodeAttempts(array: JSONArray?): List<ExamAttempt> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val a = array.getJSONObject(i)
            ExamAttempt(
                id = a.optString("id", UUID.randomUUID().toString()),
                score = a.optInt("score", 0),
                total = if (a.has("total") && !a.isNull("total")) a.optInt("total") else null,
                date = a.optLong("date", System.currentTimeMillis()),
                secondsTaken = if (a.has("seconds") && !a.isNull("seconds")) a.optInt("seconds") else null,
                timed = a.optBoolean("timed", false)
            )
        }
    }

}

// ---- Progress ------------------------------------------------------------------------------------

// ---- Library filtering ---------------------------------------------------------------------------

/** One exam-tag filter row: any notebook whose tags match all the set fields stays visible. */
data class ExamFilter(
    val subject: VceSubject? = null,
    val year: Int? = null,
    val company: String? = null,
    val type: ExamType? = null,
    val unit: Int? = null,
    val status: ExamStatus? = null,
    /** "Under 70%" and friends; a notebook counts when its best attempt share is below this. */
    val belowShare: Float? = null,
    val needsRedo: Boolean = false,
    /** Show notebooks that are still to do or in progress, not marked or redone. */
    val incomplete: Boolean = false
) {
    val isActive: Boolean get() = subject != null || year != null || !company.isNullOrBlank() ||
        type != null || unit != null || status != null || belowShare != null || needsRedo || incomplete
    fun matches(note: Notebook): Boolean {
        val tags = note.exam
        if (subject != null && tags.subject != subject) return false
        if (year != null && tags.year != year) return false
        if (!company.isNullOrBlank() && !tags.company.equals(company, true)) return false
        if (type != null && tags.type != type) return false
        if (unit != null && tags.unit != unit) return false
        if (status != null && tags.status != status) return false
        if (belowShare != null) {
            val share = note.bestScore ?: return false
            if (share >= belowShare) return false
        }
        if (needsRedo && note.pages.none { it.redoFlag }) return false
        if (incomplete && note.exam.status in setOf(ExamStatus.MARKED, ExamStatus.REDONE)) return false
        return true
    }
}

// ---- Batch editing ---------------------------------------------------------------------------------

/**
 * One batch assignment of exam tags: each section is opt-in, so untouched sections keep their
 * values. An empty value inside a ticked section clears that field.
 */
data class ExamTagsBatch(
    val changeSubject: Boolean = false,
    val subject: VceSubject? = null,
    val subjectText: String = "",
    val changeYear: Boolean = false,
    val year: Int? = null,
    val changeCompany: Boolean = false,
    val company: String = "",
    val changeType: Boolean = false,
    val type: ExamType? = null,
    val changeStatus: Boolean = false,
    val status: ExamStatus = ExamStatus.TO_DO
) {
    val isEmpty: Boolean get() = !changeSubject && !changeYear && !changeCompany && !changeType && !changeStatus

    /** The same assignment as a tag transform, for [FolioViewModel.updateExamTagsBatch]. */
    fun applyTo(tags: ExamTags): ExamTags {
        var out = tags
        if (changeSubject) {
            out = if (subject != null) out.copy(subject = subject, subjectText = "")
            else out.copy(subject = null, subjectText = subjectText.trim())
        }
        if (changeYear) out = out.copy(year = year)
        if (changeCompany) out = out.copy(company = company.trim())
        if (changeType) out = out.copy(type = type)
        if (changeStatus) out = out.copy(status = status)
        return out
    }
}
