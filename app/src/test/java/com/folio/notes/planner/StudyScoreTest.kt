package com.folio.notes.planner

import com.folio.notes.planner.StudyScore.Result
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StudyScoreTest {
    private val data by lazy { StudyScore.VtacData.decode(File("src/main/assets/planner/vtac-scaling.json").readText()) }

    /** Expected values were produced by Focal's own `estimateAtar` (src/lib/atar.ts) on the same input. */
    @Test fun atarMatchesFocal() {
        val estimate = StudyScore.estimateAtar(listOf(
            Result("1", "EN", "English", 34.0, 35.2), Result("2", "MA10", "Mathematical Methods", 38.0, 43.1),
            Result("3", "SP", "Spanish", 40.0, 38.0), Result("4", "BI", "Biology", 36.0, 34.4),
            Result("5", "SI", "Specialist", 30.0, 33.0), Result("6", "CN", "Chinese First Language", 35.0, 31.0)), 2025, data)!!
        assertEquals(157.1, estimate.aggregate, 1e-9)
        assertEquals("90.75", estimate.label)
        assertEquals(listOf("1", "2", "3", "4"), estimate.primary.map { it.result.id })
        assertEquals(listOf("5", "6"), estimate.increments.map { it.result.id })
    }

    @Test fun atarNeedsEnglishAndFourStudies() {
        val three = listOf(Result("1", "EN", "English", 30.0, 30.0), Result("2", "BI", "Biology", 30.0, 30.0), Result("3", "CH", "Chemistry", 30.0, 30.0))
        assertNull(StudyScore.estimateAtar(three, 2025, data))
        // Four studies but no English-group study: not an ATAR.
        val noEnglish = three.drop(1) + listOf(Result("4", "PY", "Physics", 30.0, 30.0), Result("5", "AC", "Accounting", 30.0, 30.0))
        assertNull(StudyScore.estimateAtar(noEnglish, 2025, data))
    }

    @Test fun liveAtarKeepsManualScoresWhenPredictionEvidenceIsMissing() {
        val rows = listOf("EN", "BI", "CH", "PY").map { code ->
            org.json.JSONObject().put("id", code).put("code", code).put("raw", "34.5").put("usePrediction", code == "EN")
        }
        val scenario = org.json.JSONObject().put("year", 2025).put("rows", org.json.JSONArray(rows))
        val estimate = requireNotNull(StudyScore.liveAtar(scenario, data, emptyList(), emptyList()))
        assertEquals(listOf(34.5, 34.5, 34.5, 34.5), estimate.primary.map { it.result.raw })
    }

    @Test fun atarGoalExcludesAStudyWhenItsSelectedPredictionCannotBeScaled() {
        val rows = org.json.JSONArray(listOf("EN", "BI", "CH", "PY").map { code ->
            org.json.JSONObject().put("id", code).put("code", code).put("raw", "34.5").put("usePrediction", code == "BI")
        })
        val saved = org.json.JSONArray().put(org.json.JSONObject().put("year", 2025).put("rows", rows))
        val goal = org.json.JSONObject().put("kind", "atar").put("target", 90)
        val exams = listOf(exam("low", "Biology", "Exam", 2024, 0, 120, "2026-08-01"))
        val prediction = requireNotNull(StudyScore.predict("Biology", exams, references))
        assertTrue("Expected a prediction below the scaling range, got ${prediction.studyScore}", prediction.studyScore < 20)
        val baseline = Planner.goalProgress(goal, emptyList(), saved, references, data).current
        assertNotNull(baseline)
        assertNull(Planner.goalProgress(goal, exams, saved, references, data).current)
    }

    @Test fun scalingInterpolatesAndAveragesYears() {
        val s = StudyScore.scaled(35.0, "Accounting", data)!!
        assertEquals(36.0, s.scaled, 1.5)
        assertTrue(s.years.isNotEmpty())
        assertNull(StudyScore.interpolate(19.0, listOf(20.0 to 20.0, 50.0 to 50.0)))
    }

    private val references by lazy {
        com.folio.notes.progress.ExamCatalog.decode(File("src/main/assets/progress/vcaa-grade-distributions.json").readText(), """{"studies":[]}""").references
    }
    private fun exam(id: String, subject: String, paper: String, year: Int, score: Int, max: Int, date: String) = com.folio.notes.progress.LoggedExam.decode(
        org.json.JSONObject().put("id", id).put("subject", subject).put("provider", "VCAA").put("title", "t").put("examYear", year).put("paper", paper)
            .put("completedAt", date).put("rawScore", score).put("rawMax", max).put("createdAt", date + "T00:00:00Z").toString())!!

    /** Expected values come from Focal's `predictStudyScore` run on the same VCAA distributions. */
    @Test fun predictionMatchesFocal() {
        val methods = StudyScore.predict("Mathematical Methods", listOf(exam("a", "Mathematical Methods", "Exam 1", 2024, 30, 40, "2026-08-01"),
            exam("b", "Mathematical Methods", "Exam 2", 2024, 55, 80, "2026-08-10"), exam("c", "Mathematical Methods", "Exam 2", 2023, 60, 80, "2026-09-10")), references)!!
        assertEquals(37, methods.studyScore); assertEquals(35, methods.low); assertEquals(39, methods.high)
        assertEquals(84.2792108476319, methods.combinedPercentile, 1e-6); assertEquals("Medium", methods.confidence)
        val biology = StudyScore.predict("Biology", listOf(exam("a", "Biology", "Exam", 2024, 80, 120, "2026-08-01"), exam("b", "Biology", "Exam", 2023, 90, 120, "2026-09-01")),
            references, 70.0, 50.0)!!
        assertEquals(33, biology.studyScore); assertEquals(31, biology.low); assertEquals(35, biology.high)
        assertEquals(67.20211930926217, biology.combinedPercentile, 1e-6)
        // A split exam ignores the typed weight, which is why the Score tab shows it read-only.
        assertTrue(StudyScore.hasExamSplit("Mathematical Methods")); assertFalse(StudyScore.hasExamSplit("Biology"))
        val typed = StudyScore.predict("Mathematical Methods", listOf(exam("a", "Mathematical Methods", "Exam 1", 2024, 30, 40, "2026-08-01")), references, null, 10.0)!!
        val default = StudyScore.predict("Mathematical Methods", listOf(exam("a", "Mathematical Methods", "Exam 1", 2024, 30, 40, "2026-08-01")), references)!!
        assertEquals(60.0, typed.examWeightPercent, 0.0); assertEquals(default.combinedPercentile, typed.combinedPercentile, 0.0)
    }

    @Test fun explicitPaperTypeWinsAndCombinedMathsIsExcluded() {
        val misleading = exam("a", "Mathematical Methods", "Exam 1", 2024, 60, 80, "2026-08-01")
        fun withType(type: String) = misleading.copy(originalJson = misleading.json.put("paperType", type).toString())
        val prediction = StudyScore.predict("Mathematical Methods", listOf(withType("exam2")), references)!!
        assertEquals(listOf(0, 1), prediction.components.take(2).map { it.evidenceCount })
        assertEquals(null, StudyScore.predict("Mathematical Methods", listOf(withType("combined")), references))
    }

    @Test fun compactPaperNamesMatchTheNumberedMathsDistribution() {
        val compact = exam("a", "Mathematical Methods", "Exam2", 2024, 60, 80, "2026-08-01")
        val prediction = requireNotNull(StudyScore.predict("Mathematical Methods", listOf(compact), references))
        assertEquals(listOf(0, 1), prediction.components.take(2).map { it.evidenceCount })
        assertNull(StudyScore.predict("Mathematical Methods", listOf(compact.copy(paper = "Exam1 & Exam2")), references))
    }

    @Test fun generalMathematicsUsesTheTypedExamWeightWithNumberedPapers() {
        val compact = exam("general", "General Mathematics", "Exam2", 2024, 60, 120, "2026-08-01")
        val prediction = requireNotNull(StudyScore.predict("General Mathematics", listOf(compact), references, 70.0, 35.0))
        assertEquals(35.0, prediction.examWeightPercent, 0.0)
        assertEquals(1, prediction.components.single().evidenceCount)
    }
}
