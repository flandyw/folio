package com.folio.notes

import com.folio.notes.exam.ExamAiClassification
import com.folio.notes.exam.ExamClassifier
import com.folio.notes.exam.ExamDecisionClassification
import com.folio.notes.exam.ExamEvidenceCodec
import com.folio.notes.exam.ExamDetectionResult
import com.folio.notes.exam.ExamDocumentEvidence
import com.folio.notes.exam.ExamEvidenceCollector
import com.folio.notes.exam.ExamEvidenceSource
import com.folio.notes.exam.ExamField
import com.folio.notes.exam.ExamReclassify
import com.folio.notes.exam.ExamTags
import com.folio.notes.exam.ExamType
import com.folio.notes.exam.VceSubject
import com.folio.notes.exam.detectImportedExam
import com.folio.notes.exam.smartImportedNotebookName
import com.folio.notes.pdf.PdfPageText
import kotlinx.coroutines.CancellationException

private var checks = 0
private fun scenario(name: String, block: () -> Unit) {
    try { block(); checks++; println("PASS $name") }
    catch (e: Throwable) { throw AssertionError(name, e) }
}

private fun detect(filename: String, cover: String = "", metadata: List<String> = emptyList(), scan: String = ""): ExamDetectionResult =
    ExamClassifier.classify(ExamEvidenceCollector.collect(filename,
        ExamDocumentEvidence(if (cover.isEmpty()) emptyList() else listOf(PdfPageText(0, cover)), metadata,
            if (scan.isEmpty()) emptyList() else listOf(PdfPageText(0, scan)))))

fun main() {
    scenario("Compact subject codes and numbered papers") {
        listOf("mm" to VceSubject.MATHS_METHODS, "sm" to VceSubject.SPECIALIST_MATHS,
            "gm" to VceSubject.GENERAL_MATHS, "fm" to VceSubject.GENERAL_MATHS).forEach { (code, subject) ->
            val result = detect("2024${code}2-w.pdf")
            check(result.subject == subject && result.type == ExamType.EXAM_2 && result.year == 2024) { result }
            check(result.reviewFields.contains(ExamField.TYPE))
        }
        check(detect("2023chem.pdf").subject == VceSubject.CHEMISTRY)
    }
    scenario("Strong front matter overrides a misleading filename") {
        val result = detect("2022 Methods Exam 1.pdf", """
            CHEMISTRY
            2024
            Written examination
            Reading time: 15 minutes
            Total marks: 120
            Victorian Curriculum and Assessment Authority
        """.trimIndent())
        check(result.subject == VceSubject.CHEMISTRY && result.year == 2024) { result }
        check(result.type == ExamType.EXAM && result.company == "VCAA" && result.marksTotal == 120) { result }
    }
    scenario("Publisher beats a VCAA reference") {
        val result = detect("paper.pdf", """
            Mathematical Methods
            EXAMINATION 2
            2025
            NEAP
            Reading time: 15 minutes
            Based on the VCAA study design 2023–2027.
        """.trimIndent())
        check(result.company == "NEAP" && result.year == 2025 && result.subject == VceSubject.MATHS_METHODS) { result }
    }
    scenario("Northern Hemisphere source is distinct") {
        val result = detect("2024 VCAA NHT Methods Exam 1.pdf")
        check(result.company == "VCAA NHT") { result }
    }
    scenario("Roman numerals and paper aliases") {
        check(detect("2024 NEAP Specialist Exam II.pdf").type == ExamType.EXAM_2)
        check(detect("2024 TSSM General Maths Paper 1.pdf").type == ExamType.EXAM_1)
        check(detect("Chemistry Unit 1 revision.pdf").type != ExamType.EXAM_1)
    }
    scenario("Generic exams and two-digit filename years") {
        val result = detect("NEAP Chem 24 Trial Exam.pdf")
        check(result.subject == VceSubject.CHEMISTRY && result.year == 2024 && result.type == ExamType.EXAM) { result }
        check(detect("Physics 2009 Exam.pdf").year == 2009)
        check(detect("Chem 20 marks Exam 1.pdf").year == null)
    }
    scenario("Generic cover preserves a compatible numbered filename") {
        val result = detect("2024 Methods Exam 1.pdf", "MATHEMATICAL METHODS\n2024\nWritten examination\nReading time")
        check(result.type == ExamType.EXAM_1) { result }
    }
    scenario("A copyright mention cannot hide the same year in the title") {
        val result = detect("document.pdf", "Copyright © 2024\nCHEMISTRY\n2024\nWritten examination\nReading time")
        check(result.year == 2024) { result }
    }
    scenario("A+ publisher symbol is normalized before matching") {
        check(detect("A+ 2024 Methods Exam 1.pdf").company == "A+")
    }
    scenario("Study-design ranges and copyright do not become sitting years") {
        val result = detect("document.pdf", "Mathematical Methods\nEXAM 1\nReading time\nStudy design 2023–2027\nCopyright © 2022")
        check(result.year == null) { result }
    }
    scenario("Conflicting strong headings remain blank") {
        val result = detect("paper.pdf", "CHEMISTRY\nPHYSICS\n2024\nEXAM 1\nReading time")
        check(result.subject == null && ExamField.SUBJECT in result.reviewFields) { result }
    }
    scenario("References and prose cannot swamp a title") {
        val evidence = ExamEvidenceCollector.collect("document.pdf", ExamDocumentEvidence(listOf(
            PdfPageText(0, "PHYSICS\nEXAM 1\nReading time"),
            PdfPageText(1, "The methods used in this question. ".repeat(80)))))
        check(ExamClassifier.classify(evidence).subject == VceSubject.PHYSICS)
    }
    scenario("Whole-paper marks exclude section and question totals") {
        check(detect("paper.pdf", "CHEMISTRY\n2024\nEXAM\nReading time\nTotal marks: 120").marksTotal == 120)
        check(detect("paper.pdf", "Section A total marks: 30\nQuestion 1 total marks: 5").marksTotal == null)
        check(detect("paper.pdf", "Number of marks\nSection A 20\nSection B 60\nTotal 80").marksTotal == 80)
    }
    scenario("Scanned cover uses the same conservative classifier") {
        val result = detect("scan.pdf", scan = "SPECIALIST MATHEMATICS\n2024\nEXAMINATION 2\nReading time\nNEAP\nTotal marks: 80")
        check(result.subject == VceSubject.SPECIALIST_MATHS && result.year == 2024 && result.company == "NEAP" && result.type == ExamType.EXAM_2) { result }
        check(result.evidence.any { it.source == ExamEvidenceSource.OCR })
    }
    scenario("Scanned-text failures cannot fabricate total marks") {
        val result = detect("scan.pdf", scan = "CHEMISTRY\nEXAM\nReading time\nSection A total marks 30\nTotal marks 120")
        check(result.marksTotal == 120) { result }
    }
    scenario("Scanned and embedded copies are not independent corroboration") {
        val text = "PHYSICS\nEXAM\nReading time"
        val evidence = ExamEvidenceCollector.collect("document.pdf", ExamDocumentEvidence(listOf(PdfPageText(1, text)),
            ocrPages = listOf(PdfPageText(1, text))))
        check(ExamClassifier.classify(evidence).fieldConfidence[ExamField.SUBJECT] == 0.91)
    }
    scenario("Repeated metadata cannot break an ambiguous tie") {
        val result = detect("Methods Physics Exam 1.pdf", metadata = List(3) { "Mathematical Methods" })
        check(result.subject == null) { result }
    }
    scenario("Ordinary documents and partial matches retain their names") {
        check(smartImportedNotebookName(ExamTags(), "Lecture handout") == "Lecture handout")
        check(smartImportedNotebookName(ExamTags(subject = VceSubject.CHEMISTRY), "Chemistry chapter 4") == "Chemistry chapter 4")
        val tags = ExamTags(VceSubject.MATHS_METHODS, year = 2024, company = "NEAP", type = ExamType.EXAM_1)
        check(smartImportedNotebookName(tags, "paper").endsWith("Exam 1"))
        check(smartImportedNotebookName(tags, "paper") != smartImportedNotebookName(tags.copy(type = ExamType.EXAM_2), "paper"))
        check(smartImportedNotebookName(tags, "2024 Methods Exam 1 Solutions") == "2024 Methods Exam 1 Solutions")
    }
    scenario("Filename fallback survives text-extraction failure") {
        val result = detectImportedExam("2024 NEAP Methods Exam 1.pdf") { error("Unsupported font") }
        check(result.subject == VceSubject.MATHS_METHODS && result.year == 2024 && result.type == ExamType.EXAM_1)
    }
    scenario("Cancellation is never swallowed by detection") {
        var cancelled = false
        try { detectImportedExam("paper.pdf") { throw CancellationException() } }
        catch (_: CancellationException) { cancelled = true }
        check(cancelled)
    }
    scenario("A known VCAA file name settles every field") {
        val known = ExamTags(subject = VceSubject.CHEMISTRY, year = 2023, company = "VCAA", type = ExamType.EXAM)
        val result = ExamClassifier.classify(ExamEvidenceCollector.collect("2023chem-w.pdf", known = known))
        check(result.subject == VceSubject.CHEMISTRY && result.year == 2023 && result.company == "VCAA" && result.type == ExamType.EXAM) { result }
        check(!result.uncertain && result.reviewFields.isEmpty()) { result }
        check(detect("notes.pdf").uncertain)
    }
    scenario("ChatGPT replies are validated locally") {
        val tags = ExamAiClassification.parse("""{"subject":"MATHS_METHODS","subject_other":null,"year":2024,"company":"NEAP","type":"EXAM_2","marks_total":80}""")
        check(tags.subject == VceSubject.MATHS_METHODS && tags.year == 2024 && tags.company == "NEAP" && tags.type == ExamType.EXAM_2 && tags.marksTotal == 80) { tags }
        val wild = ExamAiClassification.parse("""{"subject":"OTHER","subject_other":"Dance","year":3024,"company":null,"type":"POEM","marks_total":0}""")
        check(wild.subject == null && wild.subjectText == "Dance" && wild.year == null && wild.company == "" && wild.type == null && wild.marksTotal == null) { wild }
        check(ExamAiClassification.format().getJSONObject("schema").getJSONArray("required").length() == 6)
        check(ExamAiClassification.prompt("x.pdf", ExamDocumentEvidence(listOf(PdfPageText(0, "a".repeat(9000))))).length < 4_100)
    }
    scenario("Decision-model answers need confidence and stay in range") {
        val body = ExamDecisionClassification.body("Clef", "methods.pdf", ExamDocumentEvidence(listOf(PdfPageText(0, "Total 40 marks. Question 1 (3 marks)"))), 2026)
        val questions = body.getJSONObject("questions")
        check(body.getString("model") == "Clef" && body.getString("state").contains("methods.pdf"))
        check(questions.getJSONObject("year").getJSONObject("criteria").has("2027") && questions.getJSONObject("year").getJSONObject("criteria").has("unknown"))
        check(questions.getJSONObject("marks_total").getJSONObject("criteria").keySet() == setOf("40", "3", "unknown"))
        check(!ExamDecisionClassification.body("Clef", "x.pdf", ExamDocumentEvidence(), 2026).getJSONObject("questions").has("marks_total"))
        check(questions.getJSONObject("subject").getJSONObject("criteria").length() <= 255)
        fun answer(choice: String, confidence: Double) = org.json.JSONObject().put("choice", choice).put("confidence", confidence)
        val tags = ExamDecisionClassification.parse(org.json.JSONObject().put("answers", org.json.JSONObject()
            .put("subject", answer("MATHS_METHODS", 0.9)).put("type", answer("EXAM_1", 0.8)).put("year", answer("2024", 0.6))
            .put("company", answer("other", 0.95)).put("marks_total", answer("40", 0.9))))
        check(tags.subject == VceSubject.MATHS_METHODS && tags.type == ExamType.EXAM_1 && tags.year == null && tags.company == "" && tags.marksTotal == 40) { tags }
        val unsure = ExamDecisionClassification.parse(org.json.JSONObject().put("answers", org.json.JSONObject()
            .put("subject", answer("unknown", 0.99)).put("type", answer("EXAM", 0.3)).put("year", org.json.JSONObject().put("choice", "2023")
                .put("probabilities", org.json.JSONObject().put("2023", 0.8)))))
        check(unsure.subject == null && unsure.type == null && unsure.year == 2023) { unsure }
    }
    scenario("ChatGPT never overrides confident on-device fields") {
        val detection = detect("paper.pdf", "CHEMISTRY\n2024\nEXAM\nReading time\nTotal marks: 120")
        val merged = ExamAiClassification.merge(detection, ExamTags(subject = VceSubject.PHYSICS, year = 2019, company = "NEAP", marksTotal = 99))
        check(merged.subject == VceSubject.CHEMISTRY && merged.year == 2024 && merged.marksTotal == 120 && merged.company == "NEAP") { merged }
        val filled = ExamAiClassification.fillGaps(ExamTags(year = 2022), ExamTags(subject = VceSubject.BIOLOGY, year = 2020, type = ExamType.SAC))
        check(filled.subject == VceSubject.BIOLOGY && filled.year == 2022 && filled.type == ExamType.SAC) { filled }
        val guess = ExamTags(subject = VceSubject.PHYSICS, year = 2019)
        val ai = ExamTags(subject = VceSubject.BIOLOGY, year = 2020, type = ExamType.SAC)
        check(ExamAiClassification.afterImport(guess, guess, untouched = true, ai) == ai) { "untouched guess takes the suggestion" }
        val edited = guess.copy(year = 2021)
        check(ExamAiClassification.afterImport(edited, guess, untouched = true, ai) == ExamAiClassification.fillGaps(edited, ai)) { "edited after import only fills gaps" }
        check(ExamAiClassification.afterImport(guess, guess, untouched = false, ai) == ExamAiClassification.fillGaps(guess, ai)) { "edited in review only fills gaps" }
    }
    scenario("Reclassify review fills gaps by default and commits only chosen fields") {
        val current = ExamTags(subject = VceSubject.CHEMISTRY, year = 2023, company = "")
        val suggested = ExamTags(subject = VceSubject.PHYSICS, year = 2023, company = "NEAP", type = ExamType.EXAM_1)
        check(ExamReclassify.changed(current, suggested) == setOf(ExamField.SUBJECT, ExamField.COMPANY, ExamField.TYPE))
        check(ExamReclassify.gaps(current, suggested) == setOf(ExamField.COMPANY, ExamField.TYPE))
        val applied = ExamReclassify.apply(current, suggested, ExamReclassify.gaps(current, suggested))
        check(applied.subject == VceSubject.CHEMISTRY && applied.company == "NEAP" && applied.type == ExamType.EXAM_1) { applied }
        check(ExamReclassify.changed(current, ExamTags()).isEmpty()) // a blank suggestion never clears anything
        check(ExamReclassify.apply(current, suggested, setOf(ExamField.SUBJECT)).subject == VceSubject.PHYSICS)
    }
    scenario("Prompts carry only the first two pages, bounded") {
        val doc = ExamDocumentEvidence((0..2).map { PdfPageText(it, "p$it " + "x".repeat(5000)) }, ocrPages = listOf(PdfPageText(2, "late scan")))
        val prompt = ExamAiClassification.prompt("x.pdf", doc)
        check("Page 1" in prompt && "Page 2" in prompt && "Page 3" !in prompt && "late scan" !in prompt && prompt.length < 5_200) { prompt.length }
    }
    scenario("Batch replies keep only asked-about ids") {
        val reply = """{"results":[{"id":"a","subject":"CHEMISTRY","subject_other":null,"year":2022,"company":null,"type":null,"marks_total":null},
            {"id":"zz","subject":"PHYSICS","subject_other":null,"year":null,"company":null,"type":null,"marks_total":null},
            {"id":"a","subject":"BIOLOGY","subject_other":null,"year":null,"company":null,"type":null,"marks_total":null}]}"""
        val parsed = ExamAiClassification.parseBatch(reply, setOf("a", "b"))
        check(parsed.keys == setOf("a") && parsed.getValue("a").subject == VceSubject.CHEMISTRY && parsed.getValue("a").year == 2022) { parsed }
        check("=== Document id: n1 ===" in ExamAiClassification.batchPrompt(listOf(Triple("n1", "a.pdf", ExamDocumentEvidence()))))
        check(ExamAiClassification.batchFormat().getJSONObject("schema").getJSONObject("properties").has("results"))
    }
    scenario("Evidence cache round-trips and misses on a changed PDF") {
        val doc = ExamDocumentEvidence(listOf(PdfPageText(0, "Cover \"quoted\"")), listOf("Title"), listOf(PdfPageText(1, "scan")))
        val text = ExamEvidenceCodec.encode(doc, true, "10:20")
        check(ExamEvidenceCodec.decode(text, "10:20") == (doc to true))
        check(ExamEvidenceCodec.decode(text, "11:20") == null && ExamEvidenceCodec.decode("{broken", "10:20") == null)
    }
    println("$checks import scenarios passed")
}
