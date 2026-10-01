package com.folio.notes.mistakes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MistakeDashboardTests {
    @get:Rule val compose = createComposeRule()

    private fun mistake(id: String, stem: String) = requireNotNull(ExamTrackMistakeCodec.decode(
        JSONObject().put("id", id).put("attemptId", "exam").put("question", "Question $id")
            .put("questionText", stem).put("category", "Reasoning").put("marksLost", 1)
            .put("explanation", "").put("correction", "")
            .put("createdAt", "2026-01-01T00:00:00.000Z")
            .put("updatedAt", "2026-01-01T00:00:00.000Z").toString()
    ))

    @Test fun fullQuestionExpandsCardAndKeepsFooterBelowContent() = checkCards(1f)
    @Test fun fullQuestionAlsoSupportsLargerText() = checkCards(1.5f)

    private fun checkCards(fontScale: Float) {
        val short = mistake("1", "Find x.")
        val tail = "Explain why the graphs have exactly one point of intersection."
        val source = "Consider functions f and g.\n\n" +
            List(4) { "\$\$f(x) = x^2 + 1\$\$\n\nA paragraph describing the functions and their domains." }.joinToString("\n\n") + "\n\n$tail"
        val long = mistake("18", source)
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
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            MistakeLibraryRow(long, null, null, false, 0, { opened = true }, { practised = true }, false)
                        }
                    }
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(source, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(source, useUnmergedTree = true).assertExists()
        val content = compose.onNodeWithContentDescription(source, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val actions = compose.onAllNodesWithText("Practise", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, actions.size)
        // The footer follows the entire question, including the final instruction past 420 chars.
        assertTrue(actions[1].boundsInRoot.top >= content.bottom)
        assertTrue(actions[1].boundsInRoot.top > actions[0].boundsInRoot.top)
        compose.onAllNodesWithText("−1 mark", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Question 18").performClick()
        compose.runOnIdle { assertTrue(opened); assertFalse(practised) }
        compose.onAllNodesWithText("Practise")[1].performScrollTo().performClick()
        compose.runOnIdle { assertTrue(practised) }
    }

    @Test fun screenshotQuestionRendersAtCardAndDetailWidths() {
        val source = "Let \\(f : \\mathbb{R} \\to \\mathbb{R}\\) be defined by\n\n" +
            "\$\$f(x)=x^2e^{kx},\$\$\n\nwhere \\(k\\) is a positive real constant. It has been shown that\n\n" +
            "\$\$f'(x)=xe^{kx}(kx+2).\$\$\n\n" +
            "Find the value of \\(k\\) for which the graphs of \\(y=f(x)\\) and \\(y=f'(x)\\) have exactly one point of intersection. (2 marks)"
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(340.dp).verticalScroll(rememberScrollState())) {
                        MistakeLibraryRow(mistake("8b", source), null, null, false, 0, {}, {}, false, selected = true)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Question 8b", style = MaterialTheme.typography.headlineLarge)
                        RichText(source, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(source, useUnmergedTree = true).fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithContentDescription(source, useUnmergedTree = true).assertCountEquals(2)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = File(context.getExternalFilesDir(null), "katex-question-regression.png")
        screenshot.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
