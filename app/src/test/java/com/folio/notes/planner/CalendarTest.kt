package com.folio.notes.planner

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarTest {
    private fun config(cycle: Int, weekends: Boolean, holidays: Boolean) = JSONObject().put("day1Starts", "2026-02-02").put("cycleLength", cycle)
        .put("weekendTimetables", weekends).put("holidays", JSONArray().apply {
            if (holidays) put(JSONObject().put("name", "Easter").put("startDate", "2026-04-03").put("endDate", "2026-04-17")) })

    /** Expected labels come from Focal's `getDayLabelForDate` on the same config. */
    @Test fun dayLabelMatchesFocal() {
        val c = config(10, false, true)
        val expected = mapOf("2026-02-01" to null, "2026-02-02" to 1, "2026-02-06" to 5, "2026-02-09" to 6, "2026-02-14" to null, "2026-02-16" to 1,
            "2026-04-02" to 4, "2026-04-10" to null, "2026-04-20" to 5, "2026-06-30" to 6)
        expected.forEach { (date, label) -> assertEquals(date, label, Calendar.dayLabel(LocalDate.parse(date), c)) }
        val weekend = config(7, true, false)
        assertEquals(6, Calendar.dayLabel(LocalDate.parse("2026-02-07"), weekend)); assertEquals(1, Calendar.dayLabel(LocalDate.parse("2026-02-09"), weekend))
    }

    @Test fun importValidatesAndMergesDays() {
        val raw = """```json
{"cycleLength":2,"entries":[{"dayLabel":1,"periods":[{"period":"P2","subject":"Maths","location":"","startTime":"10:00","endTime":"11:00"},
{"period":"P1","subject":"English","location":"R1","startTime":"09:00","endTime":"10:00"}]}]}
```"""
        val parsed = Calendar.parseTimetable(raw, JSONObject().put("holidays", JSONArray().put(JSONObject().put("name", "x"))), "2026-02-02")
        assertEquals(1, parsed.getJSONArray("entries").length())
        assertEquals("P1", parsed.getJSONArray("entries").getJSONObject(0).getJSONArray("periods").getJSONObject(0).getString("period"))
        assertEquals(1, parsed.getJSONArray("holidays").length())
        assertThrows(IllegalArgumentException::class.java) { Calendar.parseTimetable(raw.replace("11:00", "09:30"), null, "2026-02-02") }
        assertThrows(IllegalArgumentException::class.java) { Calendar.parseTimetable(raw.replace("\"dayLabel\":1", "\"dayLabel\":3"), null, "2026-02-02") }
    }

    @Test fun eventValidationAndDays() {
        assertNull(Calendar.validateEvent("Test", "2026-10-22", "09:00", "10:00", ""))
        assertNotNull(Calendar.validateEvent("Test", "2026-10-22", "10:00", "09:00", ""))
        assertNotNull(Calendar.validateEvent("", "2026-10-22", "09:00", "10:00", ""))
        val multi = Calendar.newEvent("Camp", "event", LocalDate.parse("2026-10-22"), "09:00", "10:00", LocalDate.parse("2026-10-24"), "")
        assertEquals(LocalDate.parse("2026-10-24"), Calendar.eventDays(multi)!!.second)
        assertEquals(1, Calendar.eventsOn(listOf(multi), LocalDate.parse("2026-10-23")).size)
        assertEquals(0, Calendar.eventsOn(listOf(multi), LocalDate.parse("2026-10-25")).size)
    }
}
