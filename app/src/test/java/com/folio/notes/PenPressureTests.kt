package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class PenPressureTests {
    @Test fun defaultsPreserveExistingPressureResponse() {
        listOf(0f, .1f, .25f, .5f, 1f, 1.8f, 3f).forEach {
            assertEquals(it.coerceIn(.25f, 1.8f), PenPressure.sample(it), .00001f)
        }
    }

    @Test fun higherSensitivityMakesLightStrokesThicker() {
        val light = PenPressure.sample(.5f, sensitivity = .5f)
        val sensitive = PenPressure.sample(.5f, sensitivity = 2f)
        assertTrue(InkRenderer.penPressureScale(sensitive) > InkRenderer.penPressureScale(light))
    }

    @Test fun variationControlsWidthAroundSelectedWidth() {
        assertTrue(PenPressure.sample(.7f, variation = 2f) < PenPressure.sample(.7f))
        assertTrue(PenPressure.sample(1.2f, variation = 2f) > PenPressure.sample(1.2f))
        assertEquals(1f, PenPressure.sample(1f, variation = 2f), 0f)
    }

    @Test fun disabledPressureAndZeroVariationIgnorePressure() {
        listOf(0f, .25f, 1f, 1.8f, Float.NaN).forEach {
            assertEquals(1f, PenPressure.sample(it, enabled = false, sensitivity = 3f), 0f)
            assertEquals(1f, PenPressure.sample(it, variation = 0f), 0f)
        }
    }

    @Test fun responseIsMonotonicAndWithinStoredSampleLimits() {
        for (sensitivity in listOf(.25f, 1f, 3f)) for (variation in listOf(0f, 1f, 2f)) {
            val samples = (0..300).map { PenPressure.sample(it / 100f, sensitivity = sensitivity, variation = variation) }
            assertTrue(samples.all { it in .25f..1.8f })
            assertTrue(samples.zipWithNext().all { (a, b) -> a <= b })
        }
    }

    @Test fun invalidValuesCannotIntroduceNonFiniteSamples() {
        assertEquals(1f, PenPressure.sample(Float.NaN), 0f)
        assertEquals(.5f, PenPressure.sample(.5f, sensitivity = Float.NaN, variation = Float.POSITIVE_INFINITY), .00001f)
        assertTrue(PenPressure.sample(-10f, sensitivity = -5f, variation = 10f) in .25f..1.8f)
    }
}
