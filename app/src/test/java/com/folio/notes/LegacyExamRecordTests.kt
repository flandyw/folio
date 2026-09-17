package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyExamRecordTests {
    @Test fun oldTimingReportsDoNotPreventScoreAndDurationRecovery() {
        val legacy = JSONObject("""{"id":"attempt","score":32,"total":40,"date":100000,"seconds":3600,"timed":true,"telemetry":{"startedAt":1000,"endedAt":100000,"visits":[{"pageId":"page","enteredAt":1000}]}}""")
        val attempt = ExamTagsCodec.decodeAttempts(JSONArray().put(legacy)).single()
        assertEquals(ExamAttempt(id = "attempt", score = 32, total = 40, date = 100000,
            secondsTaken = 3600, timed = true), attempt)
        val saved = ExamTagsCodec.encodeAttempts(listOf(attempt))
        assertFalse(saved.getJSONObject(0).has("telemetry"))
        assertEquals(attempt, ExamTagsCodec.decodeAttempts(saved).single())
    }

    @Test fun oldTimestampedInkRetainsItsGeometryAndStyle() {
        val stroke = Stroke(Tool.LINE, 123, 2f, listOf(InkPoint(1f, 2f), InkPoint(3f, 4f)),
            opacity = 0.5f, style = StrokeStyle.DASHED)
        val legacy = InkCodec.encodeStrokes(listOf(stroke))
        legacy.getJSONObject(0).put("createdAt", 123456L)
        val restored = InkCodec.decodeStrokes(legacy)
        assertEquals(listOf(stroke), restored)
        assertFalse(InkCodec.encodeStrokes(restored).getJSONObject(0).has("createdAt"))
    }
}
