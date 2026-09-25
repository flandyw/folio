package com.folio.notes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExamDetectionTests {
    @Test fun smartImportNameUsesYearCompanySubject() {
        assertEquals("2024 VCAA Maths Methods", smartImportedNotebookName(
            ExamTags(year = 2024, company = "VCAA", subject = VceSubject.MATHS_METHODS), "scan"))
        assertEquals("scan", smartImportedNotebookName(ExamTags(), "scan"))
    }

    private fun detect(name: String, vararg pages: String) = detectImportedExam(name) {
        ExamDocumentEvidence(pages.mapIndexed { index, text -> PdfPageText(index, text) })
    }

    @Test fun cleanFilename() {
        val result = detect("2024_VCAA_Mathematical_Methods_Exam_1.pdf")
        assertEquals(VceSubject.MATHS_METHODS, result.subject)
        assertEquals(2024, result.year)
        assertEquals("VCAA", result.company)
        assertEquals(ExamType.EXAM_1, result.type)
        assertTrue(result.confidence < ExamClassifier.HIGH_CONFIDENCE)
        assertNull(result.marksTotal)
    }

    @Test fun abbreviatedAndMessyFilenames() {
        listOf("MM34_E1_2024_VCAA.pdf", "methods-24-exam1-final(2).pdf", "2024mm1.pdf",
            "vcaa_methods_exam_1_2024_scan(2).pdf").forEach { name ->
            val result = detect(name)
            assertEquals(name, VceSubject.MATHS_METHODS, result.subject)
            assertEquals(name, 2024, result.year)
            assertEquals(name, ExamType.EXAM_1, result.type)
        }
    }

    @Test fun textIdentifiesUselessFilenameAndSuppliesReasons() {
        val result = detect("scan283728.pdf", """
            Victorian Certificate of Education
            2024
            MATHEMATICAL METHODS
            Examination 1
            Victorian Curriculum and Assessment Authority
            Total marks: 40
        """.trimIndent())
        assertEquals(VceSubject.MATHS_METHODS, result.subject)
        assertEquals(2024, result.year)
        assertEquals("VCAA", result.company)
        assertEquals(ExamType.EXAM_1, result.type)
        assertEquals(40, result.marksTotal)
        assertTrue(result.confidence >= ExamClassifier.HIGH_CONFIDENCE)
        assertTrue(result.evidence.any { it.reason.contains("page 1") && it.field == ExamField.SUBJECT })
        assertEquals(40, result.toExamTags().marksTotal)
    }

    @Test fun contentOverridesAllConflictingFilenameFields() {
        val result = detect("2023_heff_chemistry_exam2.pdf",
            "2024\nMATHEMATICAL METHODS\nExamination 1\nVCAA")
        assertEquals(2024, result.year)
        assertEquals(VceSubject.MATHS_METHODS, result.subject)
        assertEquals(ExamType.EXAM_1, result.type)
        assertEquals("VCAA", result.company)
    }

    @Test fun companyAliasesAndNht() {
        val heff = detect("heff_23_meths_e2.pdf")
        assertEquals("Heffernan", heff.company)
        assertEquals(2023, heff.year)
        assertEquals(VceSubject.MATHS_METHODS, heff.subject)
        assertEquals(ExamType.EXAM_2, heff.type)
        assertEquals("VCAA NHT", detect("vcaa_nht_methods_2024_exam1.pdf").company)
        assertEquals("VCAA NHT", detect("scan.pdf", "VCAA\nNorthern Hemisphere Timetable").company)
        listOf("NEAP", "TSSM", "Insight", "MAV", "Kilbaha", "Heffernan Associates").forEach {
            assertNotNull(detect("scan.pdf", it).company)
        }
    }

    @Test fun commercialPublisherWinsOverVcaaReference() {
        assertEquals("NEAP", detect("vcaa.pdf", "NEAP\nThis paper follows the VCAA study design.").company)
    }

    @Test fun physicalEducationAndTokenBoundaries() {
        assertEquals(VceSubject.PHYSICAL_EDUCATION, detect("scan.pdf", "PHYSICAL EDUCATION").subject)
        assertEquals(VceSubject.PHYSICAL_EDUCATION, detect("pe_2024_e1.pdf").subject)
        assertNull(detect("special_people_paper.pdf").subject)
        assertNull(detect("scan.pdf", "We hope these methods give insight into peoples lives.").subject)
        assertNull(detect("scan.pdf", "We hope these methods give insight into peoples lives.").company)
        assertNull(detect("pe.pdf").subject)
    }

    @Test fun allRequiredCanonicalSubjects() {
        mapOf("General Mathematics" to VceSubject.GENERAL_MATHS,
            "Mathematical Methods" to VceSubject.MATHS_METHODS,
            "Specialist Mathematics" to VceSubject.SPECIALIST_MATHS,
            "Physics" to VceSubject.PHYSICS, "Chemistry" to VceSubject.CHEMISTRY,
            "Biology" to VceSubject.BIOLOGY, "Physical Education" to VceSubject.PHYSICAL_EDUCATION,
            "Psychology" to VceSubject.PSYCHOLOGY, "English" to VceSubject.ENGLISH,
            "Literature" to VceSubject.LITERATURE, "Legal Studies" to VceSubject.LEGAL_STUDIES,
            "Economics" to VceSubject.ECONOMICS, "Accounting" to VceSubject.ACCOUNTING).forEach { (text, subject) ->
            assertEquals(subject, detect("random.pdf", text).subject)
        }
    }

    @Test fun unknownAndPartialStayIncomplete() {
        val unknown = detect("randomfile3928.pdf", "A shopping list\n24 apples")
        assertEquals(ExamTags(), unknown.toExamTags())
        assertEquals(0.0, unknown.confidence, 0.001)
        val partial = detect("random.pdf", "MATHEMATICAL METHODS")
        assertEquals(VceSubject.MATHS_METHODS, partial.subject)
        assertNull(partial.year)
        assertNull(partial.company)
        assertNull(partial.type)
        assertTrue(partial.confidence < ExamClassifier.HIGH_CONFIDENCE)
    }

    @Test fun twoDigitYearsRequireSubjectAndExamContext() {
        assertNull(detect("24.pdf").year)
        assertNull(detect("methods_24.pdf").year)
        assertNull(detect("exam1_24.pdf").year)
        assertEquals(2018, detect("methods_18_e1.pdf").year)
        assertEquals(2026, detect("methods_26_e1.pdf").year)
        assertNull(detect("methods_34_e1.pdf").year)
    }

    @Test fun competingTitlesOrYearsAreNotGuessed() {
        val result = detect("random.pdf", "MATHEMATICAL METHODS\nCHEMISTRY\nExamination 1\n2023\n2024")
        assertNull(result.subject)
        assertNull(result.year)
        assertEquals(ExamType.EXAM_1, result.type)
        assertNull(detect("methods_exam1_exam2.pdf").type)
    }

    @Test fun copyrightAndStudyDesignYearsDoNotBecomeExamYear() {
        assertNull(detect("scan.pdf", "MATHEMATICAL METHODS\nExamination 1\nStudy design 2023–2027\nCopyright 2022").year)
    }

    @Test fun flattenedTitleAndWrappedSubjectAreRecognized() {
        val result = detect("scan.pdf", "2024 MATHEMATICAL\nMETHODS Examination 1")
        assertEquals(2024, result.year)
        assertEquals(VceSubject.MATHS_METHODS, result.subject)
    }

    @Test fun frontMatterInstructionsDoNotCompeteWithSubject() {
        val result = detect("scan.pdf", "MATHEMATICAL\nMETHODS\nExamination 1\nWrite responses in English.\nReading time")
        assertEquals(VceSubject.MATHS_METHODS, result.subject)
        assertEquals(VceSubject.CHEMISTRY, detect("scan.pdf", "2024 Chemistry Examination 1").subject)
    }

    @Test fun marksMustBeExplicitAndNotASectionOrQuestionScore() {
        assertNull(detect("methods_e1_2024_vcaa.pdf").marksTotal)
        assertNull(detect("scan.pdf", "Examination 1\nQuestion 1 (4 marks)\nSection A total: 20 marks").marksTotal)
        assertEquals(80, detect("scan.pdf", "Total marks: 80").marksTotal)
        assertNull(detect("scan.pdf", "Total marks: 40\nTotal marks: 80").marksTotal)
        assertNull(detect("scan.pdf", "A total of 40 people; total 20 apples").marksTotal)
    }

    @Test fun weakEvidenceAndRepetitionCannotManufactureConfidence() {
        val weak = ExamEvidence(ExamField.SUBJECT, VceSubject.CHEMISTRY, 0.4,
            ExamEvidenceSource.FILENAME, "Weak alias")
        assertNull(ExamClassifier.classify(List(100) { weak }).subject)
        assertEquals(VceSubject.CHEMISTRY, ExamClassifier.classify(listOf(weak.copy(strength = 0.60))).subject)
        assertNull(ExamClassifier.classify(listOf(weak.copy(source = ExamEvidenceSource.STRUCTURE))).subject)
    }

    @Test fun metadataIsWeakAndCannotOverrideDocumentTitle() {
        val result = detectImportedExam("scan.pdf") {
            ExamDocumentEvidence(listOf(PdfPageText(0, "CHEMISTRY")), listOf("Mathematical Methods"))
        }
        assertEquals(VceSubject.CHEMISTRY, result.subject)
        assertNull(detectImportedExam("scan.pdf") { ExamDocumentEvidence(metadata = listOf("Chemistry")) }.subject)
    }

    @Test fun onlyEarlyPagesAreConsidered() {
        assertNull(detect("random.pdf", "", "", "", "MATHEMATICAL METHODS").subject)
        assertEquals(VceSubject.CHEMISTRY, detect("scan.pdf", "", "CHEMISTRY").subject)
    }

    @Test fun extractionFailureFallsBackAndDoesNotAbortBatch() = runBlocking {
        val results = mutableListOf<ExamDetectionResult>()
        val progress = mutableListOf<Int>()
        importBatch(listOf("methods_24_e1.pdf", "chemistry.pdf", "unknown.pdf"),
            importItem = { name -> detectImportedExam(name) { error("Unsupported PDF text") } },
            onSuccess = { results += it }, onFailure = { _, _ -> fail("Recognition must not fail import") },
            onProgress = { index, _ -> progress += index })
        assertEquals(listOf(1, 2, 3), progress)
        assertEquals(3, results.size)
        assertEquals(VceSubject.MATHS_METHODS, results[0].subject)
        assertEquals(VceSubject.CHEMISTRY, results[1].subject)
        assertNull(results[2].subject)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsPreserved() {
        detectImportedExam("methods.pdf") { throw CancellationException() }
    }
}
