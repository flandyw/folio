package com.folio.notes

import kotlinx.coroutines.CancellationException

enum class ExamField { SUBJECT, YEAR, COMPANY, TYPE, MARKS_TOTAL }
enum class ExamEvidenceSource { FILENAME, PDF_TEXT, PDF_METADATA, OCR, STRUCTURE }

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

    /** Missing conflicting fields and tentative prefills are both worth reviewing. */
    val reviewFields: Set<ExamField> get() = evidence.filter { it.strength >= ExamClassifier.PREFILL_THRESHOLD }
        .map { it.field }.filter { (fieldConfidence[it] ?: 0.0) < ExamClassifier.HIGH_CONFIDENCE }.toSet()
}

/** Bounded, parser-independent input; scanned front matter can supply offline OCR text. */
data class ExamDocumentEvidence(
    val pages: List<PdfPageText> = emptyList(),
    val metadata: List<String> = emptyList(),
    val ocrPages: List<PdfPageText> = emptyList()
)

object ExamEvidenceCollector {
    const val MAX_PAGES = 3
    const val MAX_PAGE_CHARACTERS = 24_000

    private val subjects = mapOf(
        VceSubject.GENERAL_MATHS to listOf("general mathematics", "general maths", "further mathematics", "further maths", "gm", "fm"),
        VceSubject.MATHS_METHODS to listOf("mathematical methods", "maths methods", "methods", "meths", "mm"),
        VceSubject.SPECIALIST_MATHS to listOf("specialist mathematics", "specialist maths", "specialist", "spesh", "sm"),
        VceSubject.PHYSICS to listOf("physics", "phys"),
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
        "Kilbaha" to listOf("kilbaha"), "Edrolo" to listOf("edrolo"),
        "QATs" to listOf("qats", "quality assessment tasks"),
        "A+" to listOf("a plus publishing", "a plus")
    )
    private val types = mapOf(
        ExamType.EXAM_1 to listOf("examination 1", "exam 1", "paper 1", "exam i", "examination i", "e 1"),
        ExamType.EXAM_2 to listOf("examination 2", "exam 2", "paper 2", "exam ii", "examination ii", "e 2"),
        ExamType.SAC to listOf("school assessed coursework", "sac"),
        ExamType.TOPIC_TEST to listOf("topic test")
    )

    private fun normalize(text: String): String = text.lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\ba\\s*\\+"), "a plus ")
        .replace(Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun contains(text: String, phrase: String) = " $phrase " in " $text "

    fun collect(filename: String, document: ExamDocumentEvidence = ExamDocumentEvidence()): List<ExamEvidence> {
        val result = mutableListOf<ExamEvidence>()
        fun inspect(raw: String, source: ExamEvidenceSource, page: Int? = null) {
            val text = normalize(raw)
            val lines = raw.lineSequence().map(::normalize).toSet()
            val filenameSource = source == ExamEvidenceSource.FILENAME
            val pdf = source == ExamEvidenceSource.PDF_TEXT || source == ExamEvidenceSource.OCR
            val location = if (pdf) "page ${page!! + 1}" else if (filenameSource) "filename" else "PDF metadata"
            val examContext = Regex("\\b(exam|examination|paper)(?: [12]| ii?)?\\b|\\be [12]\\b").containsMatchIn(text) ||
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
                result += ExamEvidence(field, value, if (source == ExamEvidenceSource.OCR) minOf(strength, 0.90) else strength,
                    source, "\"$phrase\" found in $location${if (source == ExamEvidenceSource.OCR) " (scanned text)" else ""}")
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
            // Official-style names join the year, study code and paper number (2024mm1, 2023sm2).
            if (filenameSource) Regex("\\b((?:19|20)\\d{2})[ _-]*(mm|sm|gm|fm|biol|chem|phys|psych|eng|lit|leg|econ|acc)([12])?\\b")
                .findAll(raw.lowercase(java.util.Locale.ROOT)).forEach { match ->
                val subject = when (match.groupValues[2]) {
                    "mm" -> VceSubject.MATHS_METHODS; "sm" -> VceSubject.SPECIALIST_MATHS
                    "gm", "fm" -> VceSubject.GENERAL_MATHS; "biol" -> VceSubject.BIOLOGY
                    "chem" -> VceSubject.CHEMISTRY; "phys" -> VceSubject.PHYSICS
                    "psych" -> VceSubject.PSYCHOLOGY; "eng" -> VceSubject.ENGLISH
                    "lit" -> VceSubject.LITERATURE; "leg" -> VceSubject.LEGAL_STUDIES
                    "econ" -> VceSubject.ECONOMICS; else -> VceSubject.ACCOUNTING
                }
                add(ExamField.SUBJECT, subject, match.value, 0.68)
                match.groupValues[3].takeIf { it.isNotEmpty() }?.let {
                    add(ExamField.TYPE, if (it == "1") ExamType.EXAM_1 else ExamType.EXAM_2, match.value, 0.68)
                }
            }
            if (result.none { it.field == ExamField.TYPE && it.source == source && it.strength >= 0.6 }) {
                val generic = listOf(ExamType.EXAM to listOf("examination", "exam", "trial paper", "practice paper"),
                    ExamType.NOTES to listOf("summary notes", "revision notes", "formula sheet", "bound reference"))
                generic.forEach { (type, aliases) -> aliases.firstOrNull { contains(text, it) }?.let { phrase ->
                    // Incidental mentions in a question are not a document's assessment type.
                    val heading = lines.any { contains(it, phrase) && it.length <= 100 }
                    if (filenameSource || (pdf && examContext && heading))
                        add(ExamField.TYPE, type, phrase, if (filenameSource) 0.64 else if (page == 0) 0.86 else 0.72)
                } }
            }
            var lineOffset = 0
            raw.lineSequence().forEach { line ->
                val normalizedLine = normalize(line)
                Regex("\\b(?:19|20)[0-9]{2}\\b").findAll(normalizedLine).forEach { match ->
                    val year = match.value.toInt()
                    val historical = Regex("(?i)copyright|©|study design|accreditation|past papers|from |adapted|based on|\\b(?:19|20)\\d{2}\\s*[–−-]\\s*(?:19|20)\\d{2}").containsMatchIn(line)
                    val explicit = normalizedLine == match.value || Regex("\\b${match.value} (exam|examination)\\b|\\b(exam|examination) ${match.value}\\b").containsMatchIn(normalizedLine)
                    val strength = when {
                        filenameSource -> 0.66
                        historical -> 0.35
                        pdf && explicit && examContext -> if (page == 0) 0.96 else 0.86
                        pdf && examContext && lineOffset + match.range.first < 300 -> if (page == 0) 0.88 else 0.78
                        else -> 0.40
                    }
                    add(ExamField.YEAR, year, match.value, strength)
                }
                lineOffset += line.length + 1
            }

            if (filenameSource && examContext && !Regex("\\b(?:19|20)\\d{2}\\b").containsMatchIn(text) && subjects.values.flatten().any { contains(text, it) }) {
                Regex("\\b(0[0-9]|1[0-9]|2[0-9])\\b").findAll(text).forEach {
                    val before = text.substring(0, it.range.first).trimEnd()
                    val after = text.substring(it.range.last + 1).trimStart()
                    if (!Regex("(?:unit|topic|chapter|question|section)$").containsMatchIn(before) &&
                        !Regex("^(?:marks|questions|minutes|pages)\\b").containsMatchIn(after))
                        add(ExamField.YEAR, 2000 + it.value.toInt(), it.value, 0.61)
                }
            }
            if (pdf) {
                listOf(Regex("\\btotal marks(?: for (?:the )?(?:examination|exam|paper))?\\s*[:=–-]?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
                    Regex("\\btotal(?: (?:number of )?marks)?\\s*[:=–-]?\\s*(\\d{1,3})(?:\\s+marks)?\\b", RegexOption.IGNORE_CASE)
                        .takeIf { contains(text, "number of marks") || contains(text, "total marks") },
                    Regex("\\btotal\\s*[:=–-]?\\s*(\\d{1,3})\\s+marks\\b", RegexOption.IGNORE_CASE),
                    Regex("\\b(\\d{1,3})\\s+marks\\s+in total\\b", RegexOption.IGNORE_CASE))
                    .filterNotNull().forEach { pattern -> pattern.findAll(raw).forEach { match ->
                        val marks = match.groupValues[1].toInt()
                        val line = raw.substring(0, match.range.first).substringAfterLast('\n') +
                            raw.substring(match.range.first).substringBefore('\n')
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
        document.ocrPages.take(2).forEach { inspect(it.text.take(MAX_PAGE_CHARACTERS), ExamEvidenceSource.OCR, it.pageIndex) }
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
            val fieldEvidence = evidence.filter { it.field == field }
            // A generic "written examination" heading corroborates a numbered paper; it does not
            // contradict its filename unless the cover identifies a different subject.
            val numbered = field == ExamField.TYPE && fieldEvidence.any {
                (it.value == ExamType.EXAM_1 || it.value == ExamType.EXAM_2) && it.strength >= PREFILL_THRESHOLD &&
                    (it.source != ExamEvidenceSource.FILENAME || evidence.any { subject ->
                        subject.field == ExamField.SUBJECT && subject.source == ExamEvidenceSource.FILENAME &&
                            subject.value == values[ExamField.SUBJECT] && subject.strength >= PREFILL_THRESHOLD
                    })
            }
            val ranked = fieldEvidence.filterNot { numbered && it.value == ExamType.EXAM }.groupBy { it.value }.map { (value, items) ->
                val direct = items.filter { it.source != ExamEvidenceSource.STRUCTURE }
                val best = direct.maxOfOrNull { it.strength } ?: 0.0
                // Repeated mentions cannot swamp a stronger title match. Independent agreement is capped.
                val corroboration = if (direct.filter { it.strength >= PREFILL_THRESHOLD }.map {
                    if (it.source == ExamEvidenceSource.OCR) ExamEvidenceSource.PDF_TEXT else it.source
                }.distinct().size > 1) 0.03 else 0.0
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
        // A printed total wins; otherwise fall back to the official VCAA mark count for the paper.
        if (ExamField.MARKS_TOTAL !in values) VcaaMarks.total(values[ExamField.SUBJECT] as? VceSubject,
            values[ExamField.TYPE] as? ExamType)?.let {
            values[ExamField.MARKS_TOTAL] = it
            confidence[ExamField.MARKS_TOTAL] = PREFILL_THRESHOLD
        }
        // Overall confidence also reflects completeness; consumers should prefill per field.
        val core = listOf(ExamField.SUBJECT, ExamField.YEAR, ExamField.COMPANY, ExamField.TYPE)
        return ExamDetectionResult(values[ExamField.SUBJECT] as? VceSubject, values[ExamField.YEAR] as? Int,
            values[ExamField.COMPANY] as? String, values[ExamField.TYPE] as? ExamType,
            values[ExamField.MARKS_TOTAL] as? Int, core.sumOf { confidence[it] ?: 0.0 } / core.size,
            confidence, evidence)
    }
}

/** Keep numbered papers distinct, and leave ordinary documents' names alone. */
fun smartImportedNotebookName(exam: ExamTags, fallback: String): String {
    if (exam.subjectLabel.isBlank() || exam.type == null ||
        Regex("(?i)\\b(solutions?|answers?|reports?)\\b|marking guide").containsMatchIn(fallback)) return fallback
    val name = listOfNotNull(exam.year?.toString(), exam.company.trim().takeIf(String::isNotEmpty),
        exam.subjectLabel.trim().takeIf(String::isNotEmpty), exam.type.label)
        .joinToString(" ")
    return name.ifBlank { fallback }
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
