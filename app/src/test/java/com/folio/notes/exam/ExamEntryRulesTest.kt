package com.folio.notes.exam

import org.junit.Assert.*
import org.junit.Test

class ExamEntryRulesTest {
    @Test fun optionalYearRequiresFourDigitsWithoutRewritingIt() {
        listOf("", " ", "1990", "2026", "9999").forEach { assertTrue(it, validExamYear(it)) }
        listOf("0", "26", "999", "0000", "10000", "abcd", "2026x").forEach { assertFalse(it, validExamYear(it)) }
    }

    @Test fun unitsOutsideOneToFourAreRefusedRatherThanClamped() {
        listOf("", "1", "2", "3", "4").forEach { assertTrue(it, validExamUnit(it)) }
        listOf("0", "5", "9", "x").forEach { assertFalse(it, validExamUnit(it)) }
    }

    @Test fun optionalTotalRequiresAPositiveNumber() {
        listOf("", "1", "40", "9999").forEach { assertTrue(it, validExamTotal(it)) }
        listOf("0", "-1", "10000", "1.5", "x").forEach { assertFalse(it, validExamTotal(it)) }
    }

    @Test fun turningOffTimedNeverRecordsHiddenMinutes() {
        assertNull(recordedSeconds(false, "90", 5432, false))
        assertNull(recordedSeconds(false, "60", 5432, true))
    }

    @Test fun measuredSecondsSurviveTheRoundedMinuteDisplay() {
        assertEquals(5432, recordedSeconds(true, "90", 5432, false))
        assertEquals(17, recordedSeconds(true, "1", 17, false))
    }

    @Test fun editingMinutesOrPickingAPresetReplacesTheMeasurement() {
        assertEquals(3600, recordedSeconds(true, "60", 5432, true))
        assertEquals(5400, recordedSeconds(true, "90", null, true))
        assertEquals(59940, recordedSeconds(true, "999", null, true))
    }

    @Test fun timedEntriesCannotSilentlyRecordZeroOrInvalidTime() {
        listOf("", "0", "-1", "1000", "90x").forEach {
            assertNull(it, recordedSeconds(true, it, 5432, true))
        }
        assertNull(recordedSeconds(true, "0", 0, false))
    }

    @Test fun customTimerDoesNotSubstituteOrClampInvalidInput() {
        listOf("", "0", "-1", "481", "999", "1.5", "hello").forEach {
            assertNull(it, customTimerMinutes(it, 480))
        }
        assertEquals(1, customTimerMinutes("1", 480))
        assertEquals(480, customTimerMinutes("480", 480))
    }
}
