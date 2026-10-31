package com.folio.notes.workspace

import com.folio.notes.exam.ExamTags
import com.folio.notes.exam.VceSubject
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import org.junit.Assert.*
import org.junit.Test

class SplitChooserSearchTest {
    private val note = Notebook(title = "Revision", tags = listOf("Unit 3"),
        exam = ExamTags(subject = VceSubject.entries.first(), year = 2025, company = "VCAA"),
        pages = listOf(NotePage(title = "Integration worked examples")))

    @Test fun chooserFindsSubjectsAndPageNamesLikeTheLibrary() {
        assertTrue(SplitChooser.matches(note, "integration 2025"))
        assertTrue(SplitChooser.matches(note, "VCAA unit 3"))
        assertTrue(SplitChooser.matches(note, note.exam.subjectLabel))
        assertFalse(SplitChooser.matches(note, "integration 2024"))
    }

    @Test fun hiddenScoresAreStillNotOfferedEvenWhenSearchMatches() {
        val score = note.copy(musicScoreId = "score")
        assertTrue(SplitChooser.choices(listOf(score), emptyList(), null, "integration").isEmpty)
    }
}
