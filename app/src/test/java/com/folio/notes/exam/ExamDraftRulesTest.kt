package com.folio.notes.exam

import org.junit.Assert.*
import org.junit.Test

class ExamDraftRulesTest {
    private fun batch(
        ticked: Boolean = false, subject: String? = null, custom: String = "", year: String = "",
        company: String = "", type: String? = null, status: String = ExamStatus.TO_DO.name
    ) = batchDraftEdited(ticked, subject, custom, year, company, type, status)

    @Test fun untouchedOrRevertedBatchClosesDirectly() {
        assertFalse(batch())
        assertFalse(batch(custom = "  ", company = " "))
    }

    @Test fun anyBatchChoiceNeedsConfirmation() {
        assertTrue(batch(ticked = true))
        assertTrue(batch(subject = VceSubject.entries.first().name))
        assertTrue(batch(type = ExamType.entries.first().name))
        assertTrue(batch(status = ExamStatus.MARKED.name))
    }

    @Test fun valuesTypedIntoUntickedSectionsStillCount() {
        assertTrue(batch(year = "20"))
        assertTrue(batch(company = "NEAP"))
        assertTrue(batch(custom = "Indonesian"))
    }

    private fun mark(
        score: String = "", autoScore: String = "", total: String = "", autoTotal: String = "",
        timed: Boolean = false, autoTimed: Boolean = false, minutes: String = "", autoMinutes: String = ""
    ) = markDraftEdited(score, autoScore, total, autoTotal, timed, autoTimed, minutes, autoMinutes)

    @Test fun untouchedMarkAndAutomaticPrefillsAreNotEdits() {
        assertFalse(mark())
        // Stamped score, exam or printed total and the measured timer all filled in by the dialog.
        assertFalse(mark(score = "31", autoScore = "31", total = "40", autoTotal = "40",
            timed = true, autoTimed = true, minutes = "90", autoMinutes = "90"))
    }

    @Test fun typedOrPartialMarksNeedConfirmation() {
        assertTrue(mark(score = "3"))
        assertTrue(mark(total = "4"))
        assertTrue(mark(score = "29", autoScore = "31", total = "40", autoTotal = "40"))
        assertTrue(mark(score = "", autoScore = "31"))
    }

    @Test fun timingChangesNeedConfirmation() {
        assertTrue(mark(timed = true))
        assertTrue(mark(timed = false, autoTimed = true, minutes = "90", autoMinutes = "90"))
        assertTrue(mark(timed = true, autoTimed = true, minutes = "60", autoMinutes = "90"))
    }

    @Test fun revertingClearsTheGuardAndHiddenMinutesDoNotCount() {
        assertFalse(mark(score = "31", autoScore = "31", total = "40", autoTotal = "40"))
        assertFalse(mark(minutes = "60"))
    }
}
