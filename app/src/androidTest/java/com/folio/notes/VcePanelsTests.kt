package com.folio.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VcePanelsTests {
    @get:Rule val compose = createComposeRule()

    @Test fun customTimerStartsOnceWithTheEnteredDuration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("preferences", 0)
        val readingMinutes = AppPrefs.timerReadingMinutes(
            prefs.getInt(AppPrefs.TIMER_READING_MIN, AppPrefs.DEFAULT_TIMER_READING_MIN)
                .takeIf { prefs.contains(AppPrefs.TIMER_READING_MIN) }
        )
        val started = mutableListOf<ExamTimerPreset>()
        compose.setContent {
            MaterialTheme {
                ExamTimerContent(
                    ExamTimerState(), onStart = { started += it }, onStop = {},
                    onAdjust = {}, onSkip = {}, onPauseResume = {},
                )
            }
        }
        compose.onNodeWithText("Writing minutes").performScrollTo().performTextReplacement("37")
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ExamTimerPreset("Custom · 37 min", 37 * 60, readingMinutes * 60)), started)
        }
    }
}
