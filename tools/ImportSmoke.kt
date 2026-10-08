package com.folio.notes

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
    println("$checks import scenarios passed")
}
