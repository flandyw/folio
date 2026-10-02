package com.folio.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FolioMotionTests {
    @get:Rule val compose = createComposeRule()

    @Test fun panelCloseWaitsForExitAndDismissesOnlyOnce() {
        var open by mutableStateOf(true)
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                if (open) FolioPanel("Motion panel", {
                    dismissals++
                    open = false
                }) { Text("Panel content") }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Close Motion panel").performClick()
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithText("Panel content").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("Panel content").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun sheetCanBeClosedDuringItsEntrance() {
        var open by mutableStateOf(true)
        var dismissals = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                if (open) FolioSideSheet("Motion sheet", {
                    dismissals++
                    open = false
                }) { Text("Sheet content") }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        // Invoke the semantics action: the close target is still moving onto the screen.
        compose.onNodeWithContentDescription("Close Motion sheet")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("Sheet content").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun expansionCanReverseWithoutDroppingItsContent() {
        var expanded by mutableStateOf(true)
        compose.setContent { MaterialTheme { FolioExpand(expanded) { Text("Expandable content") } } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Expandable content").assertIsDisplayed()
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Expandable content").assertDoesNotExist()
    }
}
