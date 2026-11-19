package com.folio.notes.music

import org.junit.Assert.*
import org.junit.Test

class MusicDurationTest {
    private val score = MusicScore("00000000-0000-0000-0000-000000000001", "Concerto", 4, durationSeconds = 197)
    private val untimed = MusicScore("00000000-0000-0000-0000-000000000002", "Aria", 1)

    @Test fun setTotalsUseTheDurationInsteadOfThePageEstimateAndLabelAnyFallback() {
        val library = MusicLibrary(scores = listOf(score, untimed))
        val set = MusicSet("set", "Recital", listOf(score.id, untimed.id, "missing"))
        assertEquals("3:47 partially estimated (30s/page)", set.scoresIn(library).setDurationText())
        assertEquals("3:17", listOf(score).setDurationText())
        assertEquals("0:30 estimated (30s/page)", listOf(untimed).setDurationText())
        assertNull(set.scoresIn(MusicLibrary()).setDurationText())
    }

    @Test fun optionalDurationRoundTripsWithoutChangingTheIndexVersion() {
        val encoded = MusicCodec.encode(MusicLibrary(scores = listOf(score, untimed)))
        assertEquals(listOf(score, untimed), MusicCodec.decode(encoded).scores)
        val json = org.json.JSONObject(encoded)
        assertEquals(1, json.getInt("version"))
        assertFalse(json.getJSONArray("scores").getJSONObject(1).has("durationSeconds"))
        val timed = json.getJSONArray("scores").getJSONObject(0)
        timed.put("durationSeconds", -1)
        assertNull(MusicCodec.decode(json.toString()).scores.first().durationSeconds)
    }

    @Test fun durationInputSupportsSecondsAndClearingButRejectsInvalidValues() {
        assertEquals(197, parseMusicDuration(" 3:17 "))
        assertEquals(15, parseMusicDuration("0:15"))
        for (invalid in listOf("", "0:00", "3", "3:7", "3:60", "-1:30", "1:2:03", "35791394:08")) {
            assertNull(invalid, parseMusicDuration(invalid))
        }
        assertFalse(score.detailsEdited(score.title, "", "", "", "03:17"))
        assertTrue(score.detailsEdited(score.title, "", "", "", ""))
        assertTrue(untimed.detailsEdited(untimed.title, "", "", "", "3:17"))
        assertTrue(untimed.detailsEdited(untimed.title, "", "", "", "3:60"))
    }

    @Test fun shortenedPartDoesNotInheritTheFullScoreDuration() {
        assertNull(MusicParts.extracted(score, "part", MusicPartRequest("Part", "", listOf(1, 2))).durationSeconds)
        assertEquals(score.durationSeconds, MusicParts.extracted(
            score, "part", MusicPartRequest("Part", "", (0 until score.pages).toList())).durationSeconds)
    }
}
