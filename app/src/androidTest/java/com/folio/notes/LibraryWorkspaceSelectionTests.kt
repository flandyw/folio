package com.folio.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LibraryWorkspaceSelectionTests {
    @get:Rule val compose = createComposeRule()
    private val picked = mutableListOf<String>()
    private var cancelled = false

    private fun show(width: Int, mode: CompanionMode) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 900.dp))) {
                MaterialTheme {
                    val model: FolioViewModel = viewModel()
                    LibraryScreen(
                        FolioState(loading = false, activeId = "editor", notes = listOf(
                            Notebook(id = "editor", title = "Original editor"),
                            Notebook(id = "reference", title = "Chosen reference"),
                        )), model,
                        onNew = {}, onImport = {}, onImportArchive = {}, onFolder = {}, onSettings = {},
                        onOpenNotebook = { picked.add(it) },
                        selectionCaption = WorkspacePicker.libraryCaption(PickerPurpose.COMPANION, mode),
                        onCancelSelection = { cancelled = true },
                    )
                }
            }
        }
    }

    @Test fun landscapeLibraryRoutesTheClickedNotebookToThePendingSelection() {
        show(1000, CompanionMode.REFERENCE)
        compose.onNodeWithText("Choose a notebook for reference view").assertExists()
        compose.onNodeWithText("Mistakes").assertDoesNotExist()
        compose.onNodeWithText("Progress").assertDoesNotExist()
        compose.onNodeWithText("Select").assertDoesNotExist()
        compose.onNodeWithText("Chosen reference").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("reference"), picked)
            assertFalse(cancelled)
        }
    }

    @Test fun phoneLibraryAlsoRoutesPicksInsteadOfOpeningTheEditor() {
        show(400, CompanionMode.SPLIT)
        compose.onNodeWithText("Choose a notebook for split view").assertExists()
        compose.onNodeWithText("Chosen reference").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("reference"), picked) }
    }

    @Test fun examFilterSuggestionsRefreshWhenNotebookMetadataChanges() {
        var notes by mutableStateOf(listOf(Notebook(id = "tagged", title = "Paper", exam = ExamTags(
            subject = VceSubject.MATHS_METHODS, year = 2022, company = "VCAA"
        ))))
        compose.setContent {
            MaterialTheme {
                val model: FolioViewModel = viewModel()
                LibraryScreen(
                    // An empty folder keeps cover badges out of the suggestion assertions.
                    FolioState(loading = false, folderId = "empty-folder", notes = notes), model,
                    onNew = {}, onImport = {}, onImportArchive = {}, onFolder = {}, onSettings = {},
                )
            }
        }
        compose.onNodeWithText("Filters").performScrollTo().performClick()
        compose.onNodeWithText(VceSubject.MATHS_METHODS.label).assertExists()
        compose.onNodeWithText("2022").assertExists()
        compose.onNodeWithText("VCAA").assertExists()
        compose.runOnIdle {
            notes = listOf(notes.single().copy(exam = ExamTags(
                subject = VceSubject.PHYSICS, year = 2023, company = "NEAP"
            )))
        }
        compose.onNodeWithText(VceSubject.MATHS_METHODS.label).assertDoesNotExist()
        compose.onNodeWithText("2022").assertDoesNotExist()
        compose.onNodeWithText("VCAA").assertDoesNotExist()
        compose.onNodeWithText(VceSubject.PHYSICS.label).assertExists()
        compose.onNodeWithText("2023").assertExists()
        compose.onNodeWithText("NEAP").assertExists()
    }

    @Test fun cancelDoesNotSelectANotebook() {
        show(1000, CompanionMode.REFERENCE)
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertTrue(picked.isEmpty()) }
    }
}
