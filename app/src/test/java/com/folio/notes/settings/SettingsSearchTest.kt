package com.folio.notes.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchTest {
    @Test fun descriptiveResultsRevealTheRealControls() {
        mapOf(
            "Toolbar style" to "Toolbar",
            "Live shape measurements" to "Live measurements",
            "Pressure-sensitive eraser" to "Pressure-sensitive",
            "Resume timer on pen down" to "Resume on pen down",
            "Stop timer after inactivity" to "Stop after inactivity",
            "Quick note folder" to "Quick notes and canvases",
            "Typed text size and alignment" to "Typed text",
            "Default cover colour" to "Cover design and colour",
            "Reading direction" to "Writing direction",
        ).forEach { (result, target) -> assertEquals(result, target, settingsSearchTarget(result)) }
    }

    @Test fun conditionalControlsRevealTheirAlwaysVisibleGroup() {
        assertEquals("Palm rejection and gestures", settingsSearchTarget("Pan speed"))
        assertEquals("Pen feel", settingsSearchTarget("Low-latency ink"))
        assertEquals("Automatic backup", settingsSearchTarget("Back up now"))
        assertEquals("Exam timer", settingsSearchTarget("Custom timer, writing minutes"))
    }

    @Test fun directResultNamesStayUnchanged() {
        listOf("Pen smoothing", "Document tabs", "Respond by", "Save library backup").forEach {
            assertEquals(it, settingsSearchTarget(it))
        }
    }
}
