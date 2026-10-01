package com.folio.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Rule
import org.junit.Test

/** Tests the real adaptive library shell with a lightweight mistakes destination. */
@OptIn(ExperimentalTestApi::class)
class LibraryMistakesNavigationTests {
    @get:Rule val compose = createComposeRule()
    private val reviewing = mutableStateOf(false)

    private fun show(width: Int) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 900.dp))) {
                MaterialTheme {
                    var mistakes by remember { mutableStateOf(true) }
                    val model: FolioViewModel = viewModel()
                    LibraryScreen(
                        FolioState(loading = false), model,
                        onNew = {}, onImport = {}, onImportArchive = {}, onFolder = {}, onSettings = {},
                        onMistakes = { mistakes = true }, showMistakes = mistakes,
                        onLibrary = { mistakes = false },
                    ) { reportReviewMode ->
                        SideEffect { reportReviewMode(reviewing.value) }
                        DisposableEffect(Unit) { onDispose { reportReviewMode(false) } }
                        var search by rememberSaveable { mutableStateOf("") }
                        Column {
                            Text("Mistakes content")
                            OutlinedTextField(search, { search = it }, label = { Text("Mistake search") })
                        }
                    }
                }
            }
        }
    }

    private fun destination(title: String) = compose.onNode(hasText(title) and hasClickAction())

    @Test fun wideRailSelectsMistakesAndCanSwitchToProgressAndFavorites() {
        show(1000)
        destination("Mistakes").assertIsSelected()
        destination("Library").assertIsNotSelected()
        destination("Settings").assertExists()
        destination("Import").assertExists()
        compose.onNodeWithText("Mistake search").performTextInput("algebra")
        destination("Progress").performClick()
        destination("Progress").assertIsSelected()
        compose.onNodeWithText("Mistakes content").assertDoesNotExist()
        destination("Mistakes").performClick()
        compose.onNodeWithText("Mistake search").assertTextContains("algebra")
        destination("Favorites").performClick()
        destination("Favorites").assertIsSelected()
        destination("Mistakes").assertIsNotSelected()
    }

    @Test fun phoneUsesSharedNavigationAndRestoresMistakesState() {
        show(400)
        destination("Mistakes").assertIsSelected()
        destination("Favorites").assertDoesNotExist()
        compose.onNodeWithText("Mistake search").performTextInput("geometry")
        destination("Library").performClick()
        destination("Library").assertIsSelected()
        destination("Mistakes").performClick()
        compose.onNodeWithText("Mistake search").assertTextContains("geometry")
        destination("Mistakes").assertIsSelected()
    }

    @Test fun reviewModeHidesSharedNavigationUntilTheReviewEnds() {
        show(1000)
        destination("Mistakes").assertExists()
        compose.runOnIdle { reviewing.value = true }
        destination("Mistakes").assertDoesNotExist()
        destination("Settings").assertDoesNotExist()
        compose.onNodeWithText("Mistakes content").assertExists()
        compose.runOnIdle { reviewing.value = false }
        destination("Mistakes").assertIsSelected()
        destination("Library").assertExists()
    }
}
