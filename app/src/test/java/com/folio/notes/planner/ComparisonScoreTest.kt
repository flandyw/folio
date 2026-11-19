package com.folio.notes.planner

import org.junit.Assert.*
import org.junit.Test

class ComparisonScoreTest {
    @Test fun rejectsEmptyAndMalformedScores() {
        listOf("", "   ", "garbage", "87.5.1", "NaN", "Infinity", "-Infinity", "1e999").forEach {
            assertNull(it, parseComparisonScore(it))
        }
    }

    @Test fun rejectsOutOfRangeScores() {
        listOf("-1", "-0.1", "100.1", "101").forEach {
            assertNull(it, parseComparisonScore(it))
        }
    }

    @Test fun acceptsBoundariesAndDecimals() {
        assertEquals(0.0, parseComparisonScore("0")!!, 0.0)
        assertEquals(100.0, parseComparisonScore("100")!!, 0.0)
        assertEquals(87.5, parseComparisonScore("87.5")!!, 0.0)
        assertEquals(87.5, parseComparisonScore(" 87.5 ")!!, 0.0)
    }
}
