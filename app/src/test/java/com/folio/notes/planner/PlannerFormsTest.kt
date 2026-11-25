package com.folio.notes.planner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlannerFormsTest {
    @Test fun aSecondTapWhileSavingIsIgnoredAndBusyAlwaysClears() = runBlocking {
        val states = mutableListOf<Boolean>()
        val flight = SingleFlight { states += it }
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) { flight.run { writes++; gate.await() } }
        assertTrue(flight.busy)
        assertFalse(flight.run { writes++ })
        gate.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, writes)
        assertFalse(flight.busy)
        // A failing write still frees the guard for a retry.
        launch { runCatching { flight.run { error("disk full") } } }.join()
        assertFalse(flight.busy)
        assertTrue(flight.run { writes++ })
        assertEquals(listOf(true, false, true, false, true, false), states)
    }

    @Test fun blankPercentileIsOptionalButBadTextIsAnError() {
        val blank = scoreInputs("Biology", "  ", "50")
        assertTrue(blank.valid); assertNull(blank.sacPercentile); assertEquals(50.0, blank.examWeight!!, 0.0)
        assertEquals(72.5, scoreInputs("Biology", "72,5", "50").sacPercentile!!, 0.0)
        for (bad in listOf("abc", "120", "-1", "NaN", "Infinity")) {
            val inputs = scoreInputs("Biology", bad, "50")
            assertFalse(bad, inputs.valid); assertNotNull(bad, inputs.sacError); assertNull(inputs.weightError)
        }
        for (bad in listOf("", "x", "150", "Infinity")) assertNotNull(bad, scoreInputs("Biology", "", bad).weightError)
    }

    @Test fun splitExamsFixTheWeightWhateverIsTyped() {
        for (subject in listOf("Mathematical Methods", "Specialist Mathematics", "mathematical methods CAS", "General Mathematics")) {
            val inputs = scoreInputs(subject, "", "nonsense")
            assertTrue(subject, inputs.fixedWeight); assertTrue(subject, inputs.valid); assertEquals(60.0, inputs.examWeight!!, 0.0)
        }
        assertFalse(scoreInputs("Biology", "", "50").fixedWeight)
    }

    private val english = StudyScore.Scaling("EN", "English", 2025, "", listOf(20.0 to 22.0, 50.0 to 50.0))
    private fun row(raw: String, code: String = "EN", usePrediction: Boolean = false) =
        JSONObject().put("id", "r").put("code", code).put("raw", raw).put("usePrediction", usePrediction)

    @Test fun atarRowsSayWhyTheyAreLeftOut() {
        val ok = checkAtarRow(row("35"), 2025, listOf(english), emptyMap())
        assertNull(ok.problem); assertEquals(35.0, ok.result!!.raw, 0.0); assertEquals(36.0, ok.result!!.scaled, 1e-9)
        val blank = checkAtarRow(row(" "), 2025, listOf(english), emptyMap())
        assertNull(blank.result); assertTrue(blank.incomplete)
        // Asking for a prediction that does not exist still needs a typed score.
        assertTrue(checkAtarRow(row("", usePrediction = true), 2025, listOf(english), emptyMap()).incomplete)
        for (bad in listOf("abc", "Infinity", "NaN", "55", "19.5")) {
            val c = checkAtarRow(row(bad), 2025, listOf(english), emptyMap())
            assertNull(bad, c.result); assertNotNull(bad, c.problem); assertFalse(bad, c.incomplete)
        }
        val missing = checkAtarRow(row("35", code = "ZZ"), 2025, listOf(english), emptyMap())
        assertNull(missing.result); assertTrue(missing.problem!!.contains("2025"))
    }

    @Test fun predictionRowsNeedNoTypedScore() {
        val used = checkAtarRow(row("", usePrediction = true), 2025, listOf(english), mapOf(StudyScore.studyKey("English") to 30.0))
        assertNull(used.problem); assertEquals(30.0, used.result!!.raw, 0.0)
        val low = checkAtarRow(row("40", usePrediction = true), 2025, listOf(english), mapOf(StudyScore.studyKey("English") to 15.0))
        assertNull(low.result); assertTrue(low.problem!!.contains("predicted score 15"))
    }
}
