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

    /** Focal rows carry `"deleted_at": null`; Android's optString reads that as "null", which once hid every event. */
    @Test fun nullDeletedAtStillShows() {
        val event = Calendar.newEvent("SAC", "sac", LocalDate.parse("2026-10-22"), "09:00", "10:00", null, "").put("deleted_at", JSONObject.NULL).put("location", JSONObject.NULL)
        assertEquals(1, Calendar.eventsOn(listOf(event), LocalDate.parse("2026-10-22")).size)
        val item = Calendar.dayPlan(LocalDate.parse("2026-10-22"), Calendar.Sources(events = listOf(event))).items.single()
        assertEquals("SAC", item.detail); assertTrue(item.fixed)
        assertEquals(0, Calendar.eventsOn(listOf(JSONObject(event.toString()).put("deleted_at", "2026-10-01T00:00:00Z")), LocalDate.parse("2026-10-22")).size)
        val offset = JSONObject(event.toString()).put("startTime", "2026-10-22T09:00:00+11:00").put("endTime", "2026-10-22T10:00:00+11:00")
        assertNotNull(Calendar.eventDays(offset))
    }

    @Test fun dayPlanTotalsLikeFocal() {
        val day = LocalDate.parse("2026-10-22")
        val zone = java.time.ZoneId.systemDefault()
        fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val tasks = listOf(
            JSONObject().put("id", "a").put("title", "Methods revision").put("durationMinutes", 45).put("plannedFor", "2026-10-22").put("status", "planned"),
            JSONObject().put("id", "b").put("title", "Chem notes").put("durationMinutes", 30).put("plannedFor", "2026-10-22").put("status", "completed"),
            JSONObject().put("id", "c").put("title", "Skip me").put("durationMinutes", 20).put("plannedFor", "2026-10-22").put("status", "skipped"),
            JSONObject().put("id", "d").put("title", "Old").put("durationMinutes", 20).put("plannedFor", "2026-10-20").put("status", "planned"))
        // Overlapping blocks count once (09:00-10:30), and the session covers the task of the same title.
        val sessions = listOf(Calendar.Session("s", "Chem notes", "", listOf(at(9) to at(10), at(9, 30) to at(10, 30)), done = true, live = false))
        val plan = Calendar.dayPlan(day, Calendar.Sources(tasks = tasks, sessions = sessions, dueMistakes = mapOf(day to 3)))
        assertEquals(45, plan.plannedMinutes); assertEquals(90, plan.completedMinutes)
        assertEquals(2, plan.totalCount); assertEquals(1, plan.completedCount); assertEquals(3, plan.dueMistakes)
        assertEquals(listOf("d"), Calendar.overdue(tasks, day).map { it.getString("id") })
        val weeks = Calendar.month(java.time.YearMonth.of(2026, 10), Calendar.Sources())
        assertEquals(5, weeks.size); assertEquals(LocalDate.parse("2026-09-28"), weeks[0][0].date)
    }
}
