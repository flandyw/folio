package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

private fun pen(at: Long, page: String = "p1") = Stroke(
    Tool.PEN, 0xFF000000.toInt(), 2f, listOf(InkPoint(1f, 2f), InkPoint(3f, 4f)), createdAt = at
)

private fun sitting(vararg strokes: Pair<String, Long>, visits: List<PageVisit> = emptyList(), start: Long = 1_000_000L, end: Long = 1_000_000L + 3_600_000L): Pair<Notebook, ExamAttempt> {
    val byPage = strokes.groupBy({ it.first }, { it.second })
    val pages = listOf("p1", "p2", "p3").map { id ->
        NotePage(id = id, strokes = (byPage[id] ?: emptyList()).map { pen(it) })
    }
    val note = Notebook(title = "Exam", pages = pages)
    val attempt = ExamAttempt(
        id = "a1", score = 30, total = 40, date = end,
        secondsTaken = ((end - start) / 1000L).toInt(), timed = true,
        telemetry = ExamTelemetry(startedAt = start, endedAt = end, visits = visits)
    )
    return note to attempt
}

class StrokeTimestampTests {
    @Test fun timestampsRoundTripThroughEveryCodec() {
        val stroke = pen(at = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, stroke.createdAt)
        val decoded = InkCodec.decodeStrokes(InkCodec.encodeStrokes(listOf(stroke))).single()
        assertEquals(stroke, decoded)
        val note = Notebook(title = "Timed", pages = listOf(NotePage(strokes = listOf(stroke))))
        assertEquals(note, NoteCodec.decode(NoteCodec.encode(note)))
        assertEquals(
            listOf(stroke),
            NotePageCodec.decode(NotePageCodec.encode(note.pages.single()), note.pages.single().asSummary()).strokes
        )
    }

    @Test fun inkFromBeforeTimestampsDecodesAsUnknown() {
        val legacy = InkCodec.decodeStrokes(InkCodec.encodeStrokes(listOf(pen(at = 0L)))).single()
        assertEquals(0L, legacy.createdAt)
        val json = InkCodec.encodeStrokes(listOf(pen(at = 0L))).getJSONObject(0)
        assertFalse(json.has("createdAt"))
        val bare = org.json.JSONObject("{\"tool\":\"PEN\",\"color\":0,\"width\":2.0,\"points\":[]}")
        assertEquals(0L, InkCodec.decodeStrokes(org.json.JSONArray().put(bare)).single().createdAt)
    }

    @Test fun geometryCopiesKeepTheTimestamp() {
        val stroke = pen(at = 5_000L)
        assertEquals(5_000L, InkGeometry.translate(stroke, 1f, 1f).createdAt)
        assertEquals(5_000L, InkGeometry.restyle(listOf(stroke), opacity = 0.5f).single().createdAt)
        assertEquals(5_000L, InkGeometry.scale(listOf(stroke), InkPoint(0f, 0f), 2f).single().createdAt)
    }
}

class TelemetryCodecTests {
    @Test fun telemetryRoundTripsOnAttempts() {
        val telemetry = ExamTelemetry(
            startedAt = 1_000L, endedAt = 2_000L,
            visits = listOf(PageVisit("p1", 1_000L, 1_500L), PageVisit("p2", 1_500L))
        )
        val attempt = ExamAttempt(id = "a", score = 20, total = 40, timed = true, telemetry = telemetry)
        assertEquals(listOf(attempt), ExamTagsCodec.decodeAttempts(ExamTagsCodec.encodeAttempts(listOf(attempt))))
    }

    @Test fun attemptsWithoutTelemetryStayWithout() {
        val attempt = ExamAttempt(id = "a", score = 20, total = 40)
        assertNull(ExamTagsCodec.decodeAttempts(ExamTagsCodec.encodeAttempts(listOf(attempt))).single().telemetry)
        assertNull(ExamTelemetryCodec.decode(null))
        assertNull(ExamTelemetryCodec.decode(org.json.JSONObject("{}")))
    }

    @Test fun visitsRoundTripOnTheirOwnForThePrefsBackup() {
        val visits = listOf(PageVisit("p1", 10L, 20L), PageVisit("p2", 20L))
        assertEquals(visits, ExamTelemetryCodec.decodeVisits(ExamTelemetryCodec.encodeVisits(visits)))
        assertTrue(ExamTelemetryCodec.decodeVisits(null).isEmpty())
    }

    @Test fun version4IndexesMigrateWithoutTelemetry() {
        val note = Notebook(title = "Old", attempts = listOf(ExamAttempt(id = "a", score = 10, total = 20, timed = true)))
        val v4 = org.json.JSONObject(NoteMetaCodec.encode(note)).apply { put("version", 4) }.toString()
        assertTrue(NoteMetaCodec.isVersion4(v4))
        assertFalse(NoteMetaCodec.isCurrent(v4))
        val migrated = NoteMetaCodec.decodeVersion4(v4)
        // An index restores page metadata; page contents are loaded separately on demand.
        assertEquals(note.copy(pages = note.pages.map { it.copy(loaded = false) }), migrated)
        assertNull(migrated.attempts.single().telemetry)
        assertTrue(NoteMetaCodec.isCurrent(NoteMetaCodec.encode(migrated)))
    }
}

class VisitRecordingTests {
    @Test fun repeatsOfTheOpenPageDoNotSplitAVisit() {
        val open = recordVisit(emptyList(), "p1", 1_000L)
        assertSame(open, recordVisit(open, "p1", 2_000L))
        assertEquals(listOf(PageVisit("p1", 1_000L)), open)
    }

    @Test fun movingPagesClosesTheOldVisitAndOpensANewOne() {
        val visits = recordVisit(recordVisit(emptyList(), "p1", 1_000L), "p2", 5_000L)
        assertEquals(listOf(PageVisit("p1", 1_000L, 5_000L), PageVisit("p2", 5_000L)), visits)
        assertEquals(listOf(PageVisit("p1", 1_000L, 5_000L), PageVisit("p2", 5_000L, 9_000L)),
            closeVisits(visits, 9_000L))
        // Closing produces a new list without changing the original open visit.
        assertNull(visits.last().exitedAt)
    }

    @Test fun closingWithNothingOpenChangesNothing() {
        assertTrue(closeVisits(emptyList(), 1_000L).isEmpty())
        val closed = listOf(PageVisit("p1", 1_000L, 2_000L))
        assertSame(closed, closeVisits(closed, 3_000L))
    }
}

class SittingAnalysisTests {
    @Test fun writingTimeIsAttributedPerPageWithCappedGaps() {
        // p1: strokes at +10s, +20s (owns 10s), +100s (owns 60s capped); p2 at +110s (owns 10s).
        val start = 1_000_000L
        val (note, attempt) = sitting(
            "p1" to start + 10_000L, "p1" to start + 20_000L, "p1" to start + 100_000L,
            "p2" to start + 110_000L,
            start = start, end = start + 200_000L
        )
        val analysis = analyzeSitting(note, attempt)!!
        assertEquals(4, analysis.totalStrokes)
        assertEquals(80_000L, analysis.activeMs)
        val p1 = analysis.pages.single { it.pageIndex == 0 }
        assertEquals(70_000L, p1.activeMs)
        assertEquals(3, p1.strokes)
        assertEquals(10_000L, analysis.pages.single { it.pageIndex == 1 }.activeMs)
    }

    @Test fun dwellComesFromVisitsClippedToTheWindow() {
        val start = 1_000_000L
        val (note, attempt) = sitting(
            "p1" to start + 10_000L,
            visits = listOf(
                PageVisit("p1", start - 60_000L, start + 30_000L),
                PageVisit("p2", start + 30_000L) // open visit runs to the sitting end
            ),
            start = start, end = start + 120_000L
        )
        val analysis = analyzeSitting(note, attempt)!!
        assertEquals(30_000L, analysis.pages.single { it.pageIndex == 0 }.dwellMs)
        assertEquals(90_000L, analysis.pages.single { it.pageIndex == 1 }.dwellMs)
    }

    @Test fun idleCoversSlowStartsLongGapsAndEarlyFinishes() {
        val start = 1_000_000L
        val (note, attempt) = sitting(
            "p1" to start + 120_000L, "p1" to start + 130_000L, "p1" to start + 400_000L,
            start = start, end = start + 600_000L
        )
        val analysis = analyzeSitting(note, attempt)!!
        assertEquals(3, analysis.idleGaps.size)
        // Slow start, the long middle gap, and the early finish.
        assertEquals(start, analysis.idleGaps[0].startMs)
        assertEquals(120_000L, analysis.idleGaps[0].durationMs)
        assertEquals(270_000L, analysis.idleGaps[1].durationMs)
        assertEquals(200_000L, analysis.idleGaps[2].durationMs)
        assertEquals(590_000L, analysis.idleMs)
    }

    @Test fun theRushCountsStrokesInTheFinalTenMinutes() {
        val start = 1_000_000L
        val end = start + 3_600_000L
        val (note, attempt) = sitting(
            "p1" to start + 100_000L, "p1" to end - 9 * 60_000L,
            "p2" to end - 60_000L, "p2" to end - 1_000L,
            start = start, end = end
        )
        val analysis = analyzeSitting(note, attempt)!!
        assertEquals(3, analysis.rushStrokes)
        assertEquals(0.75f, analysis.rushShare!!, 0.0001f)
        assertEquals(2, analysis.pages.single { it.pageIndex == 1 }.rushStrokes)
    }

    @Test fun theTimelineOrdersEveryStrokeForReplay() {
        val start = 1_000_000L
        val (note, attempt) = sitting(
            "p2" to start + 30_000L, "p1" to start + 10_000L, "p1" to start + 20_000L,
            start = start, end = start + 60_000L
        )
        val timeline = analyzeSitting(note, attempt)!!.timeline
        assertEquals(listOf(0, 0, 1), timeline.map { it.pageIndex })
        assertEquals(timeline.map { it.atMs }, timeline.map { it.atMs }.sorted())
    }

    @Test fun strokesOutsideTheWindowAreIgnored() {
        val start = 1_000_000L
        val (note, attempt) = sitting("p1" to start - 5_000L, "p1" to start + 10_000L, start = start, end = start + 60_000L)
        assertEquals(1, analyzeSitting(note, attempt)!!.totalStrokes)
    }

    @Test fun legacyTimedSittingsFallBackToTheRecordedSeconds() {
        val end = 2_000_000L
        val pages = listOf(NotePage(id = "p1", strokes = listOf(pen(at = end - 30_000L), pen(at = end - 10_000L))))
        val note = Notebook(title = "Old", pages = pages)
        val attempt = ExamAttempt(id = "a", score = 10, total = 20, date = end, secondsTaken = 60, timed = true)
        val analysis = analyzeSitting(note, attempt)!!
        assertEquals(end - 60_000L, analysis.windowStartMs)
        assertEquals(2, analysis.totalStrokes)
        assertTrue(analysis.pages.single().dwellMs == 0L)
    }

    @Test fun untimedAttemptsWithoutTelemetryHaveNoReport() {
        val (note, _) = sitting("p1" to 1_010_000L)
        assertNull(analyzeSitting(note, ExamAttempt(id = "a", score = 5, total = 10)))
        assertNull(analyzeSitting(note, ExamAttempt(id = "a", score = 5, total = 10, date = 0L, timed = true, secondsTaken = 60,
            telemetry = ExamTelemetry(startedAt = 100L, endedAt = 50L))))
    }
}
