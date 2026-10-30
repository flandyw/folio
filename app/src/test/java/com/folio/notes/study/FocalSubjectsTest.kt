package com.folio.notes.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocalSubjectsTest {
    @Test fun lookupKeepsStableIdsAndPrefersTheLongestSubjectName() {
        assertNull(FocalSubjects.find("  "))
        assertNull(FocalSubjects.find("unrecognised subject"))
        assertEquals("eng-lang", FocalSubjects.find(" ENG-LANG "))
        assertEquals("eng-lang", FocalSubjects.find("2026 English-Language exam"))
        assertEquals("mm", FocalSubjects.find("Maths Methods"))
        val subjects = FocalSubjects.builtIn + FocalSubject("custom-law", "Legal Studies")
        assertEquals("custom-law", FocalSubjects.find("Legal Studies", subjects))
        assertEquals("custom-law", FocalSubjects.find("2026 Legal Studies SAC", subjects))
    }
}
