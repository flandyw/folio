package com.folio.notes.library

import com.folio.notes.exam.ExamTags
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {
    @Test fun quotedWordsMatchAnInOrderPhraseWithinOneField() {
        val note = Notebook(title = "Unit notes 3", tags = listOf("Unit", "3"))
        assertTrue(matchesQuery(note, "unit\u00a03"))
        assertFalse(matchesQuery(note, "\"unit 3\""))
        assertTrue(matchesQuery(note.copy(exam = ExamTags(unit = 3)), "\"UNIT 3\""))
        assertFalse(matchesQuery(note.copy(exam = ExamTags(unit = 3)), "\"3 unit\""))
    }

    @Test fun exclusionsWorkWithoutPositiveTermsAndCheckOtherFields() {
        val note = Notebook(title = "Practice", pages = listOf(NotePage(title = "Trial exam")))
        assertFalse(matchesQuery(note, "-TRIAL"))
        assertTrue(matchesQuery(note, "-official"))
        assertFalse(matchesQuery(note, "-\"trial exam\""))
        assertTrue(matchesQuery(note, "-\"official exam\""))
    }

    @Test fun mixedQueriesRequireEveryInclusionAndRejectAnyExclusion() {
        val note = Notebook(title = "Unit 3 practice", tags = listOf("Methods"))
        val query = "\"unit 3\" methods -trial -draft"
        assertTrue(matchesQuery(note, query))
        assertFalse(matchesQuery(note.copy(tags = emptyList()), query))
        assertFalse(matchesQuery(note.copy(title = "Unit 3 trial"), query))
        assertFalse(matchesQuery(note.copy(pages = listOf(NotePage(title = "Draft"))), query))
    }

    @Test fun blankAndOperatorOnlyQueriesDoNotFilter() {
        val note = Notebook(title = "Practice")
        for (query in listOf("", " \t\u00a0", "-", "-  -", "\"\" -\" \"")) {
            assertTrue(query, matchesQuery(note, query))
        }
    }

    @Test fun anUnclosedPhraseMatchesAsTheUserTypes() {
        assertTrue(matchesQuery(Notebook(title = "Unit 3 notes"), "\"unit 3"))
        assertFalse(matchesQuery(Notebook(title = "Unit notes 3"), "\"unit 3"))
    }

    @Test fun hyphensInsideWordsAndAtTheStartOfAQuotedPhraseAreLiteral() {
        val note = Notebook(title = "Self-marking - draft")
        assertTrue(matchesQuery(note, "self-marking \"- draft\""))
        assertFalse(matchesQuery(note, "-\"- draft\""))
    }
}
