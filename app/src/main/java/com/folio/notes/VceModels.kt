package com.folio.notes

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
    EXAM_1("Exam 1"), EXAM_2("Exam 2"), SAC("SAC"), TOPIC_TEST("Topic test"), NOTES("Notes");

    companion object { fun safeValueOf(name: String?): ExamType? = name?.let { runCatching { valueOf(it) }.getOrNull() } }
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

/** A group of notebooks that belong to one paper: attempts, solutions, a summary of corrections. */
data class ExamSet(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val subject: VceSubject? = null,
    val year: Int? = null,
    val company: String = "",
    /** Legacy paper metadata retained when reading existing sets. New sets span both papers. */
    val type: ExamType? = null,
    /** Legacy duration retained for backup compatibility. */
    val durationSeconds: Int? = null
) {
    /** Short cover line, matching the notebook tags' format. */
    fun summaryLine(): String = listOfNotNull(
        subject?.label, company.ifBlank { null }, year?.toString()
    ).joinToString(" · ")

    /** Only pair papers with a complete, matching subject/year/company identity. */
    fun matchesPaper(note: Notebook): Boolean =
        subject != null && year != null && company.isNotBlank() &&
            note.exam.subject == subject && note.exam.year == year &&
            note.exam.company.trim().equals(company.trim(), ignoreCase = true) &&
            note.exam.type in listOf(ExamType.EXAM_1, ExamType.EXAM_2)
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

    fun encodeSets(sets: List<ExamSet>): JSONArray = JSONArray().apply {
        sets.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("name", s.name)
            s.subject?.let { put("subject", it.name) }
            s.year?.let { put("year", it) }
            if (s.company.isNotBlank()) put("company", s.company)
            s.type?.let { put("type", it.name) }
            s.durationSeconds?.let { put("durationSeconds", it) }
        }) }
    }

    fun decodeSets(array: JSONArray?): List<ExamSet> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val s = array.getJSONObject(i)
            ExamSet(
                id = s.getString("id"),
                name = s.getString("name"),
                subject = if (s.has("subject") && !s.isNull("subject")) VceSubject.safeValueOf(s.optString("subject")) else null,
                year = if (s.has("year") && !s.isNull("year")) s.optInt("year") else null,
                company = s.optString("company", ""),
                type = if (s.has("type") && !s.isNull("type")) ExamType.safeValueOf(s.optString("type")) else null,
                durationSeconds = if (s.has("durationSeconds") && !s.isNull("durationSeconds")) s.optInt("durationSeconds") else null
            )
        }
    }
}

// ---- Exam sets -----------------------------------------------------------------------------------

/** Pairs a set with the notebooks that link to it, which is everything the set screens need. */
data class ExamSetGroup(val set: ExamSet, val notes: List<Notebook>) {
    // Computed once per group instance; papers()/bestShare() are called repeatedly per card.
    private val byType: Map<ExamType?, List<Notebook>> by lazy { notes.groupBy { it.exam.type } }
    fun papers(type: ExamType): List<Notebook> = byType[type].orEmpty()
    fun bestShare(type: ExamType): Float? = papers(type).asSequence().mapNotNull { it.bestScore }.maxOrNull()
    val pairedPaperCount: Int get() = listOf(ExamType.EXAM_1, ExamType.EXAM_2).count { papers(it).isNotEmpty() }
    /** Number of distinct sitting records across the whole set. */
    val attemptCount: Int get() = notes.sumOf { it.attempts.size }
}

fun groupExamSets(sets: List<ExamSet>, notes: List<Notebook>): List<ExamSetGroup> =
    sets.map { set -> ExamSetGroup(set, notes.filter { it.setId == set.id }) }

/** Builds a set's name from its tags when the student has not typed one, e.g. "Maths Methods · VCAA · 2022". */
fun ExamSet.autoName(): String =
    name.ifBlank { summaryLine().ifBlank { "Exam set" } }

// ---- Progress ------------------------------------------------------------------------------------

/** Aggregate scores for one subject, or for everything when no subject is given. */
data class SubjectProgress(
    val subject: VceSubject?,
    val paperCount: Int,
    val markedCount: Int,
    /** Mean of every attempt's share, 0..1, or null when nothing has been marked. */
    val averageShare: Float?,
    /** Best attempt share in the group, or null. */
    val bestShare: Float?,
    /** Mean share of the most recent attempt per paper — the trend students actually care about. */
    val recentShare: Float?
)

/**
 * Rolls exam attempts up per subject. Attempts without a known total are skipped for percentages
 * but still counted as marked, so a paper marked "done" without a score never vanishes.
 */
fun subjectProgress(notes: List<Notebook>): List<SubjectProgress> {
    val tagged = notes.filter { it.exam.isTagged }
    // Papers without a study roll up into one "Other" row at the end, not a null-keyed group.
    val subjects = tagged.filter { it.exam.subject != null }.groupBy { it.exam.subject }
    val rows = subjects.map { (subject, papers) ->
        val allAttempts = papers.flatMap { it.attempts }
        val shares = allAttempts.mapNotNull { it.share }
        val latest = papers.mapNotNull { paper -> paper.attempts.maxByOrNull { it.date }?.share }
        SubjectProgress(
            subject = subject,
            paperCount = papers.size,
            markedCount = allAttempts.size,
            averageShare = shares.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            bestShare = shares.maxOrNull(),
            recentShare = latest.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        )
    }.sortedByDescending { it.paperCount }
    val untaggedPapers = tagged.filter { it.exam.subject == null }
    val otherAttempts = untaggedPapers.flatMap { it.attempts }
    val otherShares = otherAttempts.mapNotNull { it.share }
    val otherLatest = untaggedPapers.mapNotNull { it.attempts.maxByOrNull { a -> a.date }?.share }
    val other = SubjectProgress(
        subject = null,
        paperCount = untaggedPapers.size,
        markedCount = otherAttempts.size,
        averageShare = otherShares.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
        bestShare = otherShares.maxOrNull(),
        recentShare = otherLatest.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    )
    return if (other.paperCount > 0) rows + other else rows
}

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
    val needsRedo: Boolean = false
) {
    val isActive: Boolean get() = subject != null || year != null || !company.isNullOrBlank() ||
        type != null || unit != null || status != null || belowShare != null || needsRedo
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
        return true
    }
}

/**
 * Extends the library filter with exam metadata, so "Methods · 2022 · VCAA" narrows the shelf
 * without leaving the search line behind: the query still matches titles and, now, tag text.
 */
fun organizeExams(
    notes: List<Notebook>, exam: ExamFilter = ExamFilter(), query: String = "", setId: String? = null
): List<Notebook> = notes.filter { note ->
    exam.matches(note) && (setId == null || note.setId == setId) &&
        (query.isBlank() || matchesQuery(note, query))
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

/** Case-insensitive match over the title and every exam field, including attempts' history. */
fun matchesQuery(note: Notebook, rawQuery: String): Boolean {
    val query = rawQuery.trim().lowercase()
    if (query.isEmpty()) return true
    val terms = query.split(' ').filter { it.isNotBlank() }
    if (terms.isEmpty()) return true
    if (terms.size == 1) return haystackContains(note, terms[0])
    val haystack = buildHaystack(note)
    return terms.all { haystack.contains(it) }
}

private fun buildHaystack(note: Notebook): String = buildString {
    append(note.title.lowercase())
    note.pages.forEach { append(' '); append(it.title.lowercase()) }
    append(' '); append(note.exam.subjectLabel.lowercase())
    append(' '); append(note.exam.company.lowercase())
    note.exam.year?.let { append(' '); append(it) }
    note.exam.type?.let { append(' '); append(it.label.lowercase()) }
    note.exam.unit?.let { append(' '); append("unit "); append(it) }
    note.exam.tags.forEach { append(' '); append(it.label.lowercase()) }
}

private fun haystackContains(note: Notebook, term: String): Boolean {
    if (note.title.lowercase().contains(term)) return true
    if (note.exam.subjectLabel.lowercase().contains(term)) return true
    if (note.exam.company.lowercase().contains(term)) return true
    note.exam.type?.let { if (it.label.lowercase().contains(term)) return true }
    note.exam.tags.forEach { if (it.label.lowercase().contains(term)) return true }
    note.exam.year?.let { if (it.toString().contains(term)) return true }
    note.exam.unit?.let { if (("unit $it").contains(term)) return true }
    return note.pages.any { it.title.lowercase().contains(term) }
}

// ---- Templates -----------------------------------------------------------------------------------

/** What a template turns into: a fresh notebook plus the tags pre-filled on it. */
data class NotebookTemplate(
    val id: String,
    val title: String,
    val description: String,
    val pages: Int,
    val paper: Paper,
    /** Pre-filled tags the student adjusts after creating, e.g. company VCAA on a past exam. */
    val tags: (year: Int?, company: String) -> ExamTags = { _, _ -> ExamTags() }
) {
    companion object {
        /** A whole past paper: title page plus plenty of workings pages on the maths grid. */
        fun pastExam(paper: Paper = Paper.MATH_GRID) = NotebookTemplate(
            id = "past-exam", title = "Past exam paper", description = "A full paper to sit, with tags for year, company and type",
            pages = 12, paper = paper,
            tags = { year, company -> ExamTags(year = year, company = company, type = ExamType.EXAM_1, status = ExamStatus.TO_DO) }
        )
        /** A topic summary book that becomes part of the bound reference. */
        fun summary() = NotebookTemplate(
            id = "summary", title = "Topic summary", description = "One notebook per topic; exports straight into your bound reference",
            pages = 4, paper = Paper.MATH_GRID,
            tags = { _, _ -> ExamTags(type = ExamType.NOTES, tags = setOf(ExamTagType.BOUND_REFERENCE)) }
        )
        /** Ruled pages for English and the report subjects. */
        fun essays() = NotebookTemplate(
            id = "essays", title = "Essay practice", description = "Ruled pages for handwritten essays and annotations",
            pages = 6, paper = Paper.RULED,
            tags = { _, _ -> ExamTags(type = ExamType.TOPIC_TEST) }
        )
        /** An Exam 2 Section A answer sheet: A–E bubbles to shade in. */
        fun multipleChoice() = NotebookTemplate(
            id = "mc-sheet", title = "Multiple-choice sheet", description = "Exam 2 Section A answer sheet with A–E bubbles, 25 numbered",
            pages = 1, paper = Paper.MC_SHEET
        )
        /** One character per square on tian (田) grid paper, with a dashed cross in each box. */
        fun hanziTian() = NotebookTemplate(
            id = "hanzi-tian", title = "Hanzi · tian grid", description = "田字格 practice — one character per square, 10 pages",
            pages = 10, paper = Paper.TIAN_GRID,
            tags = { _, _ -> ExamTags(type = ExamType.NOTES) }
        )
        /** One character per square on mi (米) grid paper, with cross and diagonals in each box. */
        fun hanziMi() = NotebookTemplate(
            id = "hanzi-mi", title = "Hanzi · mi grid", description = "米字格 practice — cross plus diagonals per square, 10 pages",
            pages = 10, paper = Paper.MI_GRID,
            tags = { _, _ -> ExamTags(type = ExamType.NOTES) }
        )
        val ALL: List<NotebookTemplate> = listOf(pastExam(), summary(), essays(), multipleChoice(), hanziTian(), hanziMi())
        fun byId(id: String?): NotebookTemplate? = ALL.find { it.id == id }
    }
}

// ---- Timer ---------------------------------------------------------------------------------------

/**
 * Exam-condition timer presets. VCAA gives 15 minutes reading time before each exam, so the
 * countdown shows reading separately from writing time and beeps between them.
 */
data class ExamTimerPreset(val label: String, val writingSeconds: Int, val readingSeconds: Int) {
    companion object {
        val METHODS_EXAM_1 = ExamTimerPreset("Exam 1 · 90 min", 90 * 60, 15 * 60)
        val METHODS_EXAM_2 = ExamTimerPreset("Exam 2 · 120 min", 120 * 60, 15 * 60)
        val CUSTOM = ExamTimerPreset("Custom", 90 * 60, 15 * 60)
        val PRESETS = listOf(METHODS_EXAM_1, METHODS_EXAM_2, CUSTOM)
    }
}

/** Phases of a timed sitting; a timer that is not running is simply [IDLE]. */
enum class ExamTimerPhase { IDLE, READING, WRITING, DONE }

/**
 * The timer's pure state machine, so every transition is unit-testable without a clock.
 * [remaining] counts down in the current phase; when it reaches zero the phase advances and the
 * leftover seconds roll into the next one, which is what a real clock does.
 */
data class ExamTimerState(
    val phase: ExamTimerPhase = ExamTimerPhase.IDLE,
    /** Seconds left in [phase]. */
    val remaining: Int = 0,
    val preset: ExamTimerPreset = ExamTimerPreset.CUSTOM,
    val startedAt: Long? = null,
    val pausedAt: Long? = null,
    val pausedMillis: Long = 0L
) {
    val active: Boolean get() = phase == ExamTimerPhase.READING || phase == ExamTimerPhase.WRITING
    val paused: Boolean get() = pausedAt != null
    val running: Boolean get() = active && !paused
    private fun elapsedMillis(now: Long): Long =
        ((pausedAt ?: now) - (startedAt ?: now) - pausedMillis).coerceAtLeast(0L)

    /**
     * Seconds spent writing so far, recorded onto the attempt when the exam is stopped. Measured
     * from the sitting's own start, so a sitting that survives a restart is still timed correctly,
     * and it never grows past the paper's writing time no matter how late the stop comes.
     */
    fun elapsedWriting(now: Long = System.currentTimeMillis()): Int {
        if (startedAt == null) return 0
        val total = (elapsedMillis(now) / 1000).toInt()
        val plannedReading = if (preset.readingSeconds > 0 && phase != ExamTimerPhase.IDLE) preset.readingSeconds else 0
        return (total - plannedReading).coerceIn(0, preset.writingSeconds)
    }
    /** The state one second later, or this state when the timer is not running. */
    fun tick(now: Long = System.currentTimeMillis()): ExamTimerState {
        if (!active || startedAt == null) return this
        val elapsed = (elapsedMillis(now) / 1000).toInt()
        val reading = preset.readingSeconds
        val writing = preset.writingSeconds
        return when {
            reading > 0 && elapsed < reading ->
                copy(phase = ExamTimerPhase.READING, remaining = reading - elapsed)
            elapsed < reading + writing ->
                copy(phase = ExamTimerPhase.WRITING, remaining = reading + writing - elapsed)
            else -> copy(phase = ExamTimerPhase.DONE, remaining = 0, pausedAt = null)
        }
    }
    fun start(preset: ExamTimerPreset, now: Long = System.currentTimeMillis()): ExamTimerState =
        copy(preset = preset, startedAt = now, pausedAt = null, pausedMillis = 0L, phase = if (preset.readingSeconds > 0) ExamTimerPhase.READING else ExamTimerPhase.WRITING,
            remaining = if (preset.readingSeconds > 0) preset.readingSeconds else preset.writingSeconds)
    /** Adjust the active phase without changing time already spent writing. */
    fun adjust(seconds: Int, now: Long = System.currentTimeMillis()): ExamTimerState {
        val current = tick(now)
        if (!current.active || current.startedAt == null) return current
        val elapsed = (current.elapsedMillis(now) / 1000).toInt()
        val updated = if (current.phase == ExamTimerPhase.READING) {
            current.preset.copy(readingSeconds = (current.preset.readingSeconds.toLong() + seconds)
                .coerceIn(elapsed.toLong(), Int.MAX_VALUE.toLong() - current.preset.writingSeconds).toInt())
        } else {
            val spent = (elapsed - current.preset.readingSeconds).coerceAtLeast(0)
            current.preset.copy(writingSeconds = (current.preset.writingSeconds.toLong() + seconds)
                .coerceIn(spent.toLong(), Int.MAX_VALUE.toLong() - current.preset.readingSeconds).toInt())
        }
        return current.copy(preset = updated).tick(now)
    }

    fun skip(now: Long = System.currentTimeMillis()): ExamTimerState {
        val current = tick(now)
        return current.adjust(-current.remaining, now)
    }

    fun pause(now: Long = System.currentTimeMillis()): ExamTimerState {
        val current = tick(now)
        return if (current.running) current.copy(pausedAt = now) else current
    }

    fun unpause(now: Long = System.currentTimeMillis()): ExamTimerState {
        val pausedSince = pausedAt ?: return this
        return copy(pausedAt = null, pausedMillis = pausedMillis + (now - pausedSince).coerceAtLeast(0L)).tick(now)
    }

    fun stop(): ExamTimerState = copy(phase = ExamTimerPhase.IDLE, remaining = 0, startedAt = null, pausedAt = null, pausedMillis = 0L)
    /**
     * Parks a sitting that came back running after the user stopped looking at the pages — a
     * crash or kill while it was foregrounded, so no background pause was ever recorded. Time
     * after [lastSeen] never counts: the clock is frozen exactly there and stays parked until
     * the user resumes it. Returns this state when it is not running, when [lastSeen] is
     * unknown, or when the gap is within [graceMs] of continuous foreground use (a notebook
     * switch restoring moments later, or a heartbeat that simply has not been written yet).
     */
    fun clampUnseenGap(lastSeen: Long?, now: Long, graceMs: Long = UNSEEN_GAP_GRACE_MS): ExamTimerState {
        if (!running || lastSeen == null || lastSeen <= 0L) return this
        if (now - lastSeen <= graceMs) return this
        return pause(lastSeen.coerceAtLeast(startedAt ?: lastSeen))
    }
    /** "1:28:03" style, used by the countdown chip and the timer panel. */
    fun clockText(): String {
        val total = remaining.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds)
    }

    companion object {
        /** A stored sitting older than this is dropped rather than resurrected. */
        private const val MAX_RESUME_AGE_MS = 48L * 60 * 60 * 1000

        /**
         * A restored sitting still running this long after its last confirmed-visible moment is
         * treated as an unseen gap and parked, not caught up. Covers crashes and kills while
         * foregrounded; clean backgroundings are already recorded as pauses before this runs.
         */
        const val UNSEEN_GAP_GRACE_MS = 30_000L

        /**
         * Rebuilds a sitting that was already running when the app last stopped, from the preset and
         * start moment that were saved with it. The clock catches up on its own because every state
         * is derived from the start moment, and a deadline that passed while the app was closed
         * comes back as Pens down rather than being silently discarded. Callers clamp unseen gaps
         * first ([clampUnseenGap]), so this catch-up only ever covers moments the user was
         * confirmed to be looking. Returns null when there is nothing sane to restore: no preset,
         * a missing or future start moment, or a record older than [MAX_RESUME_AGE_MS].
         */
        fun resume(preset: ExamTimerPreset?, startedAt: Long?, now: Long = System.currentTimeMillis(),
                   pausedAt: Long? = null, pausedMillis: Long = 0L): ExamTimerState? {
            if (preset == null || startedAt == null || startedAt <= 0 || startedAt > now) return null
            if (pausedAt != null && (pausedAt < startedAt || pausedAt > now)) return null
            val elapsed = (pausedAt ?: now) - startedAt - pausedMillis
            if (pausedMillis < 0L || elapsed < 0L || elapsed > MAX_RESUME_AGE_MS) return null
            return ExamTimerState().start(preset, now = startedAt)
                .copy(pausedAt = pausedAt, pausedMillis = pausedMillis).tick(now)
        }
    }
}
