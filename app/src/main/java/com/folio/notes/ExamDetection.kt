package com.folio.notes

import kotlinx.coroutines.CancellationException

enum class ExamField { SUBJECT, YEAR, COMPANY, TYPE, MARKS_TOTAL }
enum class ExamEvidenceSource { FILENAME, PDF_TEXT, PDF_METADATA, STRUCTURE }

/** Scores are evidence strengths, not statistically calibrated probabilities. */
data class ExamEvidence(
    val field: ExamField,
    val value: Any,
    val strength: Double,
    val source: ExamEvidenceSource,
    val reason: String
)

data class ExamDetectionResult(
    val subject: VceSubject? = null,
    val year: Int? = null,
    val company: String? = null,
    val type: ExamType? = null,
    val marksTotal: Int? = null,
    val confidence: Double = 0.0,
    val fieldConfidence: Map<ExamField, Double> = emptyMap(),
    val evidence: List<ExamEvidence> = emptyList()
) {
    fun toExamTags() = ExamTags(subject = subject, year = year, company = company.orEmpty(),
        type = type, marksTotal = marksTotal)
}

/** Bounded, parser-independent input. An OCR provider can supply page text here in future. */
data class ExamDocumentEvidence(
    val pages: List<PdfPageText> = emptyList(),
    val metadata: List<String> = emptyList()
)

object ExamEvidenceCollector {
    const val MAX_PAGES = 3
    const val MAX_PAGE_CHARACTERS = 24_000

    private val subjects = mapOf(
        VceSubject.GENERAL_MATHS to listOf("general mathematics", "general maths", "further mathematics"),
        VceSubject.MATHS_METHODS to listOf("mathematical methods", "maths methods", "methods", "meths", "mm"),
        VceSubject.SPECIALIST_MATHS to listOf("specialist mathematics", "specialist maths", "specialist", "spesh"),
        VceSubject.PHYSICS to listOf("physics"),
        VceSubject.CHEMISTRY to listOf("chemistry", "chem"),
        VceSubject.BIOLOGY to listOf("biology", "bio"),
        VceSubject.PHYSICAL_EDUCATION to listOf("physical education", "phys ed", "pe"),
        VceSubject.PSYCHOLOGY to listOf("psychology", "psych"),
        VceSubject.ENGLISH to listOf("english"),
        VceSubject.LITERATURE to listOf("literature"),
        VceSubject.LEGAL_STUDIES to listOf("legal studies"),
        VceSubject.ECONOMICS to listOf("economics"),
        VceSubject.ACCOUNTING to listOf("accounting")
    )

    // Canonical values preserve Folio's existing free-text source names.
    private val companies = mapOf(
        "VCAA NHT" to listOf("northern hemisphere timetable", "northern hemisphere", "nht"),
        "VCAA" to listOf("victorian curriculum and assessment authority", "vcaa"),
        "Heffernan" to listOf("heffernan associates", "heffernan", "heff"),
        "NEAP" to listOf("neap"), "TSSM" to listOf("tssm"),
        "Insight" to listOf("insight publications", "insight"), "MAV" to listOf("mathematical association of victoria", "mav"),
        "Kilbaha" to listOf("kilbaha")
    )
    private val types = mapOf(
        ExamType.EXAM_1 to listOf("examination 1", "exam 1", "e 1"),
        ExamType.EXAM_2 to listOf("examination 2", "exam 2", "e 2"),
        ExamType.SAC to listOf("school assessed coursework", "sac"),
        ExamType.TOPIC_TEST to listOf("topic test")
    )

    private fun normalize(text: String): String = text.lowercase(java.util.Locale.ROOT)
        .replace(Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun contains(text: String, phrase: String) = " $phrase " in " $text "

    fun collect(filename: String, document: ExamDocumentEvidence = ExamDocumentEvidence()): List<ExamEvidence> {
        val result = mutableListOf<ExamEvidence>()
        fun inspect(raw: String, source: ExamEvidenceSource, page: Int? = null) {
            val text = normalize(raw)
            val lines = raw.lineSequence().map(::normalize).toSet()
            val filenameSource = source == ExamEvidenceSource.FILENAME
            val pdf = source == ExamEvidenceSource.PDF_TEXT
            val location = if (pdf) "page ${page!! + 1}" else if (filenameSource) "filename" else "PDF metadata"
            val examContext = Regex("\\b(exam|examination|e) [12]\\b").containsMatchIn(text) ||
                contains(text, "victorian certificate of education") || contains(text, "reading time")
            fun score(phrase: String, short: Boolean = false): Double = when {
                filenameSource -> if (short) (if (examContext) 0.61 else 0.44) else 0.68
                !pdf -> if (short) 0.40 else 0.58
                short -> if (examContext && phrase in lines) 0.72 else 0.40
                phrase in lines && page == 0 -> 0.98
                phrase in lines -> 0.91
                page == 0 -> 0.86
                else -> 0.77
            }
            fun add(field: ExamField, value: Any, phrase: String, strength: Double) {
                result += ExamEvidence(field, value, strength, source, "\"$phrase\" found in $location")
            }
            subjects.forEach { (subject, aliases) ->
                aliases.firstOrNull { contains(text, it) }?.let { phrase ->
                    val short = phrase.length <= 3
                    // Everyday words in prose are not document titles (e.g. 'methods used').
                    val titleLine = lines.any { line ->
                        Regex("^(?:(?:vce|20[0-9]{2}) )*${Regex.escape(phrase)} (?:.* )?(?:exam|examination)(?: [12])?$")
                            .matches(line)
                    }
                    val weakProse = pdf && phrase !in lines && !titleLine && !phrase.contains(' ')
                    add(ExamField.SUBJECT, subject, phrase, if (weakProse) 0.40 else score(phrase, short))
                }
            }
            val matchedCompanies = companies.mapNotNull { (company, aliases) ->
                aliases.firstOrNull { contains(text, it) }?.let { company to it }
            }
            matchedCompanies.forEach { (company, phrase) ->
                // Commercial papers often cite VCAA; that citation is not their publisher.
                val vcaaReference = company == "VCAA" && matchedCompanies.any { it.first != "VCAA" }
                val ordinaryWord = phrase == "insight" && pdf && phrase !in lines
                add(ExamField.COMPANY, company, phrase,
                    if (vcaaReference || ordinaryWord) 0.35 else score(phrase))
            }
            types.forEach { (type, aliases) ->
                aliases.firstOrNull { contains(text, it) }?.let { add(ExamField.TYPE, type, it, score(it)) }
            }
            // Compact official-style names such as 2024mm1 carry an exam number next to MM.
            if (filenameSource) Regex("\\b(20\\d{2})mm([12])\\b").find(raw.lowercase())?.let {
                add(ExamField.TYPE, if (it.groupValues[2] == "1") ExamType.EXAM_1 else ExamType.EXAM_2,
                    it.value, 0.61)
                add(ExamField.SUBJECT, VceSubject.MATHS_METHODS, it.value, 0.61)
            }
            Regex("\\b20[0-9]{2}\\b").findAll(text).forEach { match ->
                val year = match.value.toInt()
                val line = raw.lineSequence().firstOrNull { contains(normalize(it), match.value) }.orEmpty()
                val historical = Regex("(?i)copyright|©|study design|accreditation|past papers|from ").containsMatchIn(line)
                val explicit = match.value in lines || Regex("\\b${match.value} (exam|examination)\\b|\\b(exam|examination) ${match.value}\\b").containsMatchIn(text)
                val strength = when {
                    filenameSource -> 0.66
                    historical -> 0.35
                    pdf && explicit && examContext -> if (page == 0) 0.96 else 0.86
                    pdf && examContext && match.range.first < 300 -> if (page == 0) 0.88 else 0.78
                    else -> 0.40
                }
                add(ExamField.YEAR, year, match.value, strength)
            }
            if (filenameSource && examContext && subjects.values.flatten().any { contains(text, it) }) {
                Regex("\\b(1[8-9]|2[0-9])\\b").findAll(text).forEach {
                    add(ExamField.YEAR, 2000 + it.value.toInt(), it.value, 0.61)
                }
            }
            if (pdf) {
                listOf(Regex("\\btotal marks(?: for (?:the )?(?:examination|exam|paper))?\\s*[:=–-]?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
                    Regex("\\btotal\\s*[:=–-]?\\s*(\\d{1,3})\\s+marks\\b", RegexOption.IGNORE_CASE),
                    Regex("\\b(\\d{1,3})\\s+marks\\s+in total\\b", RegexOption.IGNORE_CASE))
                    .forEach { pattern -> pattern.findAll(raw).forEach { match ->
                        val marks = match.groupValues[1].toInt()
                        val line = raw.lineSequence().firstOrNull { match.value in it }.orEmpty()
                        if (marks in 1..300 && !Regex("(?i)section|question|subtotal").containsMatchIn(line))
                            add(ExamField.MARKS_TOTAL, marks, match.value, if (page == 0) 0.92 else 0.79)
                    } }
                if (examContext && listOf("reading time", "writing time", "question and answer book").any { contains(text, it) }) {
                    // Structure can corroborate an explicit identification, never invent one.
                    result.filter { it.source == source && it.field == ExamField.TYPE && it.strength >= 0.6 }
                        .distinctBy { it.value }.forEach {
                            result += ExamEvidence(it.field, it.value, 0.03, ExamEvidenceSource.STRUCTURE,
                                "Exam front-matter structure on $location")
                        }
                }
            }
        }
        inspect(filename.replace(Regex("(?i)\\.pdf$"), ""), ExamEvidenceSource.FILENAME)
        document.metadata.take(3).forEach { inspect(it.take(MAX_PAGE_CHARACTERS), ExamEvidenceSource.PDF_METADATA) }
        document.pages.take(MAX_PAGES).forEach { inspect(it.text.take(MAX_PAGE_CHARACTERS), ExamEvidenceSource.PDF_TEXT, it.pageIndex) }
        return result
    }
}

object ExamClassifier {
    const val PREFILL_THRESHOLD = 0.60
    const val HIGH_CONFIDENCE = 0.85
    private const val MIN_MARGIN = 0.12

    fun classify(evidence: List<ExamEvidence>): ExamDetectionResult {
        val values = mutableMapOf<ExamField, Any>()
        val confidence = mutableMapOf<ExamField, Double>()
        ExamField.entries.forEach { field ->
            val ranked = evidence.filter { it.field == field }.groupBy { it.value }.map { (value, items) ->
                val direct = items.filter { it.source != ExamEvidenceSource.STRUCTURE }
                val best = direct.maxOfOrNull { it.strength } ?: 0.0
                // Repeated mentions cannot swamp a stronger title match. Independent agreement is capped.
                val corroboration = if (direct.filter { it.strength >= PREFILL_THRESHOLD }.map { it.source }.distinct().size > 1) 0.03 else 0.0
                val structure = if (best >= PREFILL_THRESHOLD && items.any { it.source == ExamEvidenceSource.STRUCTURE }) 0.03 else 0.0
                value to (best + corroboration + structure).coerceAtMost(0.99)
            }.sortedByDescending { it.second }
            val best = ranked.firstOrNull() ?: return@forEach
            val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
            if (best.second >= PREFILL_THRESHOLD && best.second - runnerUp + 1e-9 >= MIN_MARGIN) {
                values[field] = best.first
                confidence[field] = (best.second - if (runnerUp >= PREFILL_THRESHOLD) 0.03 else 0.0)
                    .coerceAtLeast(PREFILL_THRESHOLD)
            }
        }
        // Overall confidence also reflects completeness; consumers should prefill per field.
        val core = listOf(ExamField.SUBJECT, ExamField.YEAR, ExamField.COMPANY, ExamField.TYPE)
        return ExamDetectionResult(values[ExamField.SUBJECT] as? VceSubject, values[ExamField.YEAR] as? Int,
            values[ExamField.COMPANY] as? String, values[ExamField.TYPE] as? ExamType,
            values[ExamField.MARKS_TOTAL] as? Int, core.sumOf { confidence[it] ?: 0.0 } / core.size,
            confidence, evidence)
    }
}

/** Import's failure boundary: unsupported text extraction must not reject a valid PDF. */
internal fun detectImportedExam(filename: String, readDocument: () -> ExamDocumentEvidence): ExamDetectionResult {
    return try {
        val document = try { readDocument() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { ExamDocumentEvidence() }
        ExamClassifier.classify(ExamEvidenceCollector.collect(filename, document))
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { ExamDetectionResult() }
}
