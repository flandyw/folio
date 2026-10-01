package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class WorkspacePickerTests {
    private val methods = Notebook(
        id = "methods", title = "Methods Exam 1", folderId = "year12",
        pages = listOf(NotePage(), NotePage(pdfIndex = 3)),
        exam = ExamTags(subject = VceSubject.MATHS_METHODS, year = 2023, company = "VCAA")
    )
    private val solutions = Notebook(id = "solutions", title = "Solutions", folderId = "year12", pages = listOf(NotePage()))
    private val weekly = Notebook(
        id = "weekly", title = "Weekly homework",
        exam = ExamTags(subjectText = "Chemistry", tags = setOf(ExamTagType.HARD))
    )
    private val all = listOf(methods, solutions, weekly)
    private val folders = mapOf("year12" to "Year 12")

    @Test fun matchingCoversTitleExamSubjectTagsAndFolder() {
        assertTrue(WorkspacePicker.matches(methods, ""))
        assertTrue(WorkspacePicker.matches(methods, "  METHODS "))
        assertTrue(WorkspacePicker.matches(methods, "vcaa"))
        assertTrue(WorkspacePicker.matches(weekly, "chemistry"))
        assertTrue(WorkspacePicker.matches(weekly, "hard"))
        assertTrue(WorkspacePicker.matches(methods, "year 12", folderName = "Year 12"))
        assertFalse(WorkspacePicker.matches(methods, "physics"))
        assertFalse(WorkspacePicker.matches(methods, "methods exam 2"))
    }

    @Test fun openDocumentsComeFirstThenFoldersThenTheUnfiled() {
        val sections = WorkspacePicker.sections(all, "", openIds = setOf("solutions"), folders = folders)
        assertEquals(listOf(WorkspacePicker.OPEN_LABEL, "Year 12", WorkspacePicker.UNFILED_LABEL), sections.map { it.label })
        assertEquals(listOf("solutions"), sections[0].notes.map { it.id })
        // Open documents are listed once, at the top: solutions is not repeated under its folder.
        assertEquals(listOf("methods"), sections[1].notes.map { it.id })
        assertEquals(listOf("weekly"), sections[2].notes.map { it.id })
    }

    @Test fun aFolderWithNothingInItNeverBecomesAHeading() {
        val sections = WorkspacePicker.sections(all, "", openIds = emptySet(), folders = folders + ("year11" to "Year 11"))
        assertEquals(listOf("Year 12", WorkspacePicker.UNFILED_LABEL), sections.map { it.label })
    }

    @Test fun foldersKeepTheLibrarysOwnOrderAndStrangersFallInWithTheUnfiled() {
        val orphan = Notebook(id = "orphan", title = "Orphan", folderId = "deleted")
        val sections = WorkspacePicker.sections(all + orphan, "", emptySet(), folders)
        assertEquals(listOf("Year 12", WorkspacePicker.UNFILED_LABEL), sections.map { it.label })
        assertTrue(sections.last().notes.any { it.id == "orphan" })
    }

    @Test fun aSearchTrimsEverySectionAtOnce() {
        // Only the one match survives, and it keeps the section its folder puts it in: an open
        // document that does not match drops out of the open section entirely.
        val sections = WorkspacePicker.sections(all, "sol", openIds = setOf("weekly"), folders = folders)
        assertEquals(listOf("Year 12"), sections.map { it.label })
        assertTrue(sections.all { section -> section.notes.all { it.id == "solutions" } })
        val opened = WorkspacePicker.sections(all, "sol", openIds = setOf("solutions"), folders = folders)
        assertEquals(listOf(WorkspacePicker.OPEN_LABEL), opened.map { it.label })
        assertEquals(listOf("solutions"), opened.single().notes.map { it.id })
        assertTrue(WorkspacePicker.sections(all, "nothing here", emptySet(), folders).isEmpty())
    }

    @Test fun aBadgeSaysWhatTheDocumentAlreadyIsInTheWorkspace() {
        assertEquals("In this pane", WorkspacePicker.badge("methods", "solutions", "methods", setOf("methods", "solutions")))
        assertEquals("Editing", WorkspacePicker.badge("solutions", "solutions", "methods", setOf("methods", "solutions")))
        assertEquals("Open", WorkspacePicker.badge("solutions", "methods", "methods", setOf("methods", "solutions")))
        assertNull(WorkspacePicker.badge("weekly", "methods", "methods", setOf("methods")))
    }

    @Test fun subtitlesCountPagesAndSayWhenAPdfIsBehind() {
        assertEquals("2 pages · PDF · VCAA · 2023", WorkspacePicker.subtitle(methods))
        assertEquals("1 page", WorkspacePicker.subtitle(solutions))
        // A notebook titled after its own summary does not repeat itself underneath.
        assertEquals("1 page", WorkspacePicker.subtitle(Notebook(title = "Methods Exam 1", exam = ExamTags(subjectText = "Methods Exam 1"))))
    }

    @Test fun everyPurposeHasItsOwnTitleCaptionAndModeHint() {
        assertEquals("Open beside the editor", WorkspacePicker.title(PickerPurpose.COMPANION))
        assertEquals("Open a document", WorkspacePicker.title(PickerPurpose.OPEN))
        assertEquals("Open documents", WorkspacePicker.title(PickerPurpose.TABS))
        assertTrue(WorkspacePicker.modeCaption(CompanionMode.SPLIT).contains("editable"))
        assertTrue(WorkspacePicker.modeCaption(CompanionMode.REFERENCE).contains("read only"))
        assertTrue(WorkspacePicker.emptyCaption(PickerPurpose.OPEN, loading = true, query = "", libraryCount = 4).contains("Opening"))
        assertTrue(WorkspacePicker.emptyCaption(PickerPurpose.COMPANION, loading = false, query = "", libraryCount = 0).contains("No documents"))
        assertTrue(WorkspacePicker.emptyCaption(PickerPurpose.OPEN, loading = false, query = "", libraryCount = 0).contains("empty"))
        assertTrue(WorkspacePicker.emptyCaption(PickerPurpose.TABS, loading = false, query = "phy", libraryCount = 4).contains("phy"))
    }
}
