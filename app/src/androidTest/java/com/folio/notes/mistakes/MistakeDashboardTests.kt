package com.folio.notes.mistakes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MistakeDashboardTests {
    @get:Rule val compose = createComposeRule()

    private fun mistake(id: String, stem: String) = requireNotNull(ExamTrackMistakeCodec.decode(
        JSONObject().put("id", id).put("attemptId", "exam").put("question", "Question $id")
            .put("questionText", stem).put("category", "Reasoning").put("marksLost", 1)
            .put("createdAt", "2026-01-01T00:00:00.000Z")
            .put("updatedAt", "2026-01-01T00:00:00.000Z").toString()
    ))

    @Test fun multiBlockMathPreviewHasSameHeightAndFooterAsShortQuestion() = checkCards(1f)
    @Test fun equalCardHeightsAlsoSupportLargerText() = checkCards(1.5f)

    private fun checkCards(fontScale: Float) {
        val short = mistake("1", "Find x.")
        val long = mistake("18", "Consider functions f and g.\n\n" +
            List(12) { "\$\$f(x) = x^2 + 1\$\$\n\nA long paragraph describing the functions and their domains." }.joinToString("\n\n"))
        var opened = false
        var practised = false
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    Row {
                        Box(Modifier.weight(1f)) {
                            MistakeLibraryRow(short, null, null, false, 0, {}, {}, false)
                        }
                        Box(Modifier.weight(1f)) {
                            MistakeLibraryRow(long, null, null, false, 0, { opened = true }, { practised = true }, false)
                        }
                    }
                }
            }
        }
        val first = compose.onNodeWithText("Question 1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Question 18").fetchSemanticsNode().boundsInRoot
        assertEquals(first.height, second.height, 1f)
        val actions = compose.onAllNodesWithText("Practise").fetchSemanticsNodes()
        assertEquals(2, actions.size)
        assertEquals(actions[0].boundsInRoot.top, actions[1].boundsInRoot.top, 1f)
        compose.onNodeWithText("Open question for details").assertDoesNotExist()
        compose.onAllNodesWithText("−1 mark", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Question 18").performClick()
        compose.runOnIdle { assertTrue(opened); assertFalse(practised) }
        compose.onAllNodesWithText("Practise")[1].performClick()
        compose.runOnIdle { assertTrue(practised) }
    }
}
