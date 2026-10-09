package com.folio.notes.progress

import com.folio.notes.Notebook
import com.folio.notes.VceSubject
import com.folio.notes.mistakes.isoTime
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*

/** Focal's wire format. Keep the original document when editing, including future fields. */
data class LoggedExam(val id: String, val subject: String, val provider: String, val title: String,
    val examYear: Int, val paper: String, val completedAt: String, val rawScore: Double,
    val rawMax: Double, val originalJson: String, val notebookId: String? = null) {
    val percentage get() = rawScore / rawMax * 100
    val json get() = JSONObject(originalJson)
    val date get() = examDate(completedAt)
    companion object {
        fun decode(raw: String): LoggedExam? = runCatching {
            val o = JSONObject(raw)
            val score = o.getDouble("rawScore"); val max = o.getDouble("rawMax")
            require(score.isFinite() && max.isFinite() && max > 0 && score in 0.0..max)
            val date = o.getString("completedAt"); requireNotNull(examDate(date))
            LoggedExam(o.getString("id").also { require(it.isNotBlank()) },
                o.getString("subject"), o.optString("provider"), o.getString("title"),
                o.getInt("examYear"), o.getString("paper"), date, score, max, raw,
                o.opt("folioNotebookId") as? String)
        }.getOrNull()
    }
}

fun examDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
    ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

fun comparisonName(value: String): String = value.lowercase().replace(Regex("\\bwritten\\b"), "")
    .replace(Regex("\\b(examination|paper)\\b"), "exam").replace(Regex("[^a-z0-9]+"), " ").trim()

data class GradeBand(val grade: String, val min: Double?, val max: Double?, val percentage: Double?, val count: Int?)
data class ExamReference(val id: String, val subject: String, val year: Int, val paper: String,
    val maxScore: Double, val sourceUrl: String, val bands: List<GradeBand>) {
    val stats: DistributionStats by lazy { distributionStats(this) }
}
data class DistributionStats(val mean: Double, val median: Double, val variance: Double) {
    val stdDev get() = sqrt(variance)
}
data class ExamAnalysis(val scaledScore: Double, val grade: String?, val percentile: Double?)

fun analyseExam(exam: LoggedExam, reference: ExamReference): ExamAnalysis = analyseScore(exam.percentage / 100 * reference.maxScore, reference)
fun analyseScore(scaledScore: Double, reference: ExamReference): ExamAnalysis {
    // Match JavaScript Math.round (ties toward positive infinity).
    val score = floor(scaledScore + .5)
    val ordered = reference.bands.sortedBy { it.min ?: 0.0 }
    val band = ordered.find { it.min != null && it.max != null && score >= it.min && score <= it.max }
        ?: return ExamAnalysis(scaledScore, null, null)
    val lower = ordered.takeWhile { it !== band }.sumOf { it.percentage ?: 0.0 }
    val range = band.max!! - band.min!!
    val position = if (range > 0) ((scaledScore - band.min) / range).coerceIn(0.0, 1.0) else .5
    return ExamAnalysis(scaledScore, band.grade, band.percentage?.let { (lower + position * it).coerceIn(0.0, 100.0) })
}

/** Same midpoint estimate as Focal; a normal curve is an approximation to grade-band data. */
fun distributionStats(reference: ExamReference): DistributionStats {
    val bands = reference.bands.sortedBy { it.min ?: 0.0 }
    fun midpoint(b: GradeBand) = ((b.min ?: b.max ?: 0.0) + (b.max ?: b.min ?: 0.0)) / 2
    val mean = bands.sumOf { midpoint(it) * (it.percentage ?: 0.0) / 100 }
    val variance = bands.sumOf { (midpoint(it) - mean).pow(2) * (it.percentage ?: 0.0) / 100 }
    val target = bands.sumOf { it.percentage ?: 0.0 } / 2
    var cumulative = 0.0; var median = mean
    for (band in bands) {
        val percentage = band.percentage ?: 0.0
        if (percentage > 0 && cumulative + percentage >= target) {
            val min = band.min ?: band.max ?: 0.0; val max = band.max ?: band.min ?: 0.0
            median = min + (target - cumulative) / percentage * (max - min)
            break
        }
        cumulative += percentage
    }
    return DistributionStats(mean, median, variance)
}

fun referenceFor(exam: LoggedExam, references: List<ExamReference>, year: Int = exam.examYear): ExamReference? {
    val candidates = references.filter { it.year == year && comparisonName(it.subject) == comparisonName(exam.subject) }
    return candidates.find { comparisonName(it.paper) == comparisonName(exam.paper) }
        ?: candidates.singleOrNull()
        ?: references.find { it.id == exam.json.optString("referenceId") && it.year == year }
}

data class ExamResource(val label: String, val url: String, val kind: String, val year: Int?)
data class OfficialStudy(val subject: String, val pageUrl: String, val resources: List<ExamResource>)
data class OfficialExam(val study: OfficialStudy, val resource: ExamResource) {
    val subject get() = if (Regex("\\bnht\\b", RegexOption.IGNORE_CASE).containsMatchIn(study.subject))
        Regex("^\\d{4}\\s+(?:VCE\\s+)?(.+?)\\s+(?:written\\s+)?exam(?:ination)?(?:\\s+[1-9])?$", RegexOption.IGNORE_CASE)
            .find(resource.label)?.groupValues?.get(1)?.trim() ?: study.subject
        else study.subject
    val year get() = resource.year!!
    val paper get() = Regex("\\b(?:exam(?:ination)?|paper)\\s*([1-9])\\b|\\b([1-9])\\s+(?:exam(?:ination)?|paper)\\b", RegexOption.IGNORE_CASE)
        .find(resource.label)?.let { match -> "Exam ${match.groupValues[1].ifBlank { match.groupValues[2] }}" } ?: "Exam"
    val provider get() = if (Regex("(?:^|[-/])nht(?:[-/]|$)", RegexOption.IGNORE_CASE).containsMatchIn("${study.pageUrl} ${resource.url}")) "VCAA NHT" else "VCAA"
    fun logged(attempts: List<LoggedExam>) = attempts.filter { it.examYear == year &&
        comparisonName(it.subject) == comparisonName(subject) && comparisonName(it.provider) == comparisonName(provider) &&
        (paper == "Exam" || comparisonName(it.paper) == comparisonName(paper)) }.maxByOrNull { it.completedAt }
    fun draft(): JSONObject = JSONObject().put("subject", subject).put("provider", provider)
        .put("examYear", year).put("paper", paper).put("rawMax", knownExamMarks(subject, paper) ?: 100)
    val companions get() = study.resources.filter { it.kind in listOf("report", "specification", "sample") &&
        (!Regex("\\bnht\\b", RegexOption.IGNORE_CASE).containsMatchIn(study.subject) || comparisonName(it.label).contains(comparisonName(subject))) &&
        (it.year == year || it.year == null || it.kind == "specification") &&
        (paper == "Exam" || Regex("\\b${paper.takeLast(1)}\\b").containsMatchIn(it.label) || !Regex("\\d\\s*(?:exam|paper)|(?:exam|paper)\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(it.label)) }
        .sortedWith(compareByDescending<ExamResource> { it.year == year }.thenByDescending { it.year ?: 0 })
        .distinctBy { it.kind }.take(3)
}

fun knownExamMarks(subject: String, paper: String): Int? {
    val n = Regex("\\d+").find(paper)?.value?.toIntOrNull()
    return when (comparisonName(subject)) {
        "mathematical methods", "mathematical methods cas", "specialist mathematics" -> when (n) { 1 -> 40; 2 -> 80; else -> null }
        "general mathematics" -> when (n) { 1 -> 40; 2 -> 60; else -> null }
        "english", "english as an additional language" -> 60
        "english language" -> 75
        "literature" -> 40
        "physical education" -> 110
        "biology", "chemistry", "physics", "psychology", "environmental science" -> 120
        "foundation mathematics" -> 80
        else -> null
    }
}

data class ExamCatalog(val references: List<ExamReference> = emptyList(), val studies: List<OfficialStudy> = emptyList(),
    val generatedAt: String = "") {
    val exams by lazy { studies.flatMap { study -> study.resources.filter { it.kind == "exam" && it.year != null && !it.label.contains("transcript", true) }
        .map { OfficialExam(study, it) } }.distinctBy { it.resource.url } }
    companion object {
        fun decode(grades: String, resources: String): ExamCatalog {
            val g = JSONObject(grades); val r = JSONObject(resources)
            val references = g.getJSONArray("assessments").objects().map { a ->
                ExamReference(a.getString("id"), a.getString("studyName"), a.getInt("year"),
                    a.getString("name"), a.getDouble("maxScore"), a.getString("sourceUrl"),
                    a.getJSONArray("gradeBands").objects().map { b -> GradeBand(b.getString("grade"),
                        b.number("minScore"), b.number("maxScore"), b.number("percentage"), b.number("count")?.toInt()) })
            }
            val studies = r.getJSONArray("studies").objects().map { s -> OfficialStudy(s.getString("studyName"),
                s.getString("pageUrl"), s.getJSONArray("resources").objects().map { e ->
                    ExamResource(e.getString("label"), e.getString("url"), e.getString("kind"), e.number("year")?.toInt()) }) }
            return ExamCatalog(references, studies, r.optString("generatedAt"))
        }
    }
}

val defaultProviderOrder = listOf("Kilbaha", "VCAA NHT", "VCAA", "NEAP", "Insight", "TSSM")
data class ExamPerformance(val aligned: Double, val weight: Double, val adjustment: Double)
fun performance(exam: LoggedExam, settings: JSONObject? = null): ExamPerformance {
    if (settings?.optBoolean("enabled", true) == false) return ExamPerformance(exam.percentage, 1.0, 0.0)
    val order = settings?.optJSONArray("providerOrder")?.strings()?.distinctBy { comparisonName(it) }
        ?.let { if (it.none { p -> comparisonName(p) == "vcaa" }) it + "VCAA" else it } ?: defaultProviderOrder
    val provider = if (Regex("\\b(nht|northern hemisphere)\\b", RegexOption.IGNORE_CASE).containsMatchIn("${exam.provider} ${exam.title} ${exam.paper}")) "VCAA NHT"
        else listOf("iTute" to "\\bitute\\b", "MAV" to "\\bmav\\b|mathematical association of victoria", "Kilbaha" to "\\bkilbaha\\b",
            "VCAA" to "\\bvcaa\\b|victorian curriculum and assessment authority", "NEAP" to "\\bneap\\b", "Insight" to "\\binsight\\b",
            "Heffernan" to "\\b(?:heffernan|hefferman)\\b", "TSSM" to "\\btssm\\b")
            .firstOrNull { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(exam.provider) }?.first
            ?: order.find { comparisonName(it) == comparisonName(exam.provider) }
    val rank = order.indexOfFirst { comparisonName(it) == provider?.let(::comparisonName) }; val baseline = order.indexOfFirst { comparisonName(it) == "vcaa" }
    if (rank < 0 || baseline < 0) return ExamPerformance(exam.percentage, .55, 0.0)
    val strength = when (settings?.optString("strength")) { "light" -> 1.0; "strong" -> 2.0; else -> 1.5 }
    val aligned = (exam.percentage + ((baseline - rank) * strength).coerceIn(-8.0, 8.0)).coerceIn(0.0, 100.0)
    val weight = when (provider?.let(::comparisonName)) { "vcaa" -> 1.0; "vcaa nht" -> .9; else -> max(.4, 1 - abs(baseline - rank) * .12) }
    return ExamPerformance(aligned, weight, aligned - exam.percentage)
}
fun performanceAverage(exams: List<LoggedExam>, settings: JSONObject? = null): Double {
    if (exams.isEmpty()) return 0.0
    val values = exams.map { performance(it, settings) }
    return values.sumOf { it.aligned * it.weight } / values.sumOf { it.weight }
}

fun focalNotebookSubject(note: Notebook): String = when (note.exam.subject) {
    VceSubject.GENERAL_MATHS -> "General Mathematics"
    VceSubject.MATHS_METHODS -> "Mathematical Methods"
    VceSubject.SPECIALIST_MATHS -> "Specialist Mathematics"
    else -> note.exam.subjectLabel.ifBlank { "Other" }
}

/** A notebook feeds the exam log unless it is labelled as something that is not a paper (SAC, topic test, notes). */
val Notebook.countsAsExam: Boolean get() = exam.type?.isExam != false

fun notebookExams(notes: List<Notebook>): List<LoggedExam> = notes.filter { it.countsAsExam }.flatMap { note -> note.attempts.mapNotNull { a ->
    val max = a.total ?: note.exam.marksTotal ?: return@mapNotNull null
    val subject = focalNotebookSubject(note)
    // Focal's web app reads completedAt as a plain YYYY-MM-DD (it appends T00:00:00 itself), so an instant here shows as "Invalid Date" there.
    val day = java.time.Instant.ofEpochMilli(a.date).atZone(ZoneId.systemDefault()).toLocalDate()
    val raw = JSONObject().put("id", a.id).put("subject", subject).put("provider", note.exam.company)
        .put("title", note.title).put("examYear", note.exam.year ?: day.year)
        .put("paper", note.exam.type?.label ?: "Exam").put("completedAt", day.toString())
        .put("rawScore", a.score).put("rawMax", max).put("createdAt", isoTime(a.date))
        .put("updatedAt", isoTime(a.date)).put("referenceId", JSONObject.NULL).put("folioNotebookId", note.id)
    a.secondsTaken?.let { raw.put("timing", JSONObject().put("actualWritingSeconds", it)
        .put("plannedReadingMinutes", 0).put("plannedWritingMinutes", 0).put("overtimeSeconds", 0).put("pausedSeconds", 0)) }
    LoggedExam.decode(raw.toString())
} }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }
internal fun JSONObject.number(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
