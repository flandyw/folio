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
{"cycleLength":2,"entries":[{"dayLabel":1,"periods":[{"period":"P2","subject":"Maths","location":"","startTime":"10:00","endTime":"11:00"}]},
{"dayLabel":1,"periods":[{"period":"P1","subject":"English","location":"R1","startTime":"09:00","endTime":"10:00"}]}]}
```"""
        val parsed = Calendar.parseTimetable(raw, JSONObject().put("holidays", JSONArray().put(JSONObject().put("name", "x"))), "2026-02-02")
        assertEquals(1, parsed.getJSONArray("entries").length())
        assertEquals(2, parsed.getJSONArray("entries").getJSONObject(0).getJSONArray("periods").length())
        assertEquals("P1", parsed.getJSONArray("entries").getJSONObject(0).getJSONArray("periods").getJSONObject(0).getString("period"))
        assertEquals(1, parsed.getJSONArray("holidays").length())
        assertThrows(IllegalArgumentException::class.java) { Calendar.parseTimetable(raw.replace("11:00", "09:30"), null, "2026-02-02") }
        assertThrows(IllegalArgumentException::class.java) { Calendar.parseTimetable(raw.replace("\"dayLabel\":1", "\"dayLabel\":3"), null, "2026-02-02") }
    }

    @Test fun importRejectsMalformedArrayMembers() {
        val period = JSONObject().put("period", "P1").put("subject", "Maths").put("location", "")
            .put("startTime", "09:00").put("endTime", "10:00")
        val day = JSONObject().put("dayLabel", 1).put("periods", JSONArray().put(period))
        val current = config(10, false, true).put("entries", JSONArray().put(day))
        val original = current.toString()
        for (bad in listOf(JSONObject.NULL, "unreadable", 2, JSONArray())) {
            val badDay = JSONObject().put("cycleLength", 2).put("entries", JSONArray().put(day).put(bad))
            val badPeriod = JSONObject().put("cycleLength", 2).put("entries", JSONArray().put(
                JSONObject(day.toString()).put("periods", JSONArray().put(period).put(bad))))
            for (raw in listOf(badDay, badPeriod)) {
                assertThrows(raw.toString(), IllegalArgumentException::class.java) {
                    Calendar.parseTimetable(raw.toString(), current, "2026-02-02")
                }
                assertEquals(original, current.toString())
            }
        }
    }

    @Test fun importRequiresWholeNumericCycleAndDayLabels() {
        val valid = """{"cycleLength":2,"entries":[{"dayLabel":1,"periods":[{"period":"P1","subject":"Maths","location":"","startTime":"09:00","endTime":"10:00"}]}]}"""
        val wholeNumbers = JSONObject(valid).put("cycleLength", 2.0).apply { getJSONArray("entries").getJSONObject(0).put("dayLabel", 1.0) }
        assertEquals(2, Calendar.parseTimetable(wholeNumbers.toString(), null, "2026-02-02").getInt("cycleLength"))
        for (bad in listOf(1.5, "1", true, JSONObject.NULL, 0, 61, 4_294_967_297L)) {
            val badCycle = JSONObject(valid).put("cycleLength", bad)
            val badLabel = JSONObject(valid).apply { getJSONArray("entries").getJSONObject(0).put("dayLabel", bad) }
            for (raw in listOf(badCycle, badLabel)) assertThrows(raw.toString(), IllegalArgumentException::class.java) {
                Calendar.parseTimetable(raw.toString(), null, "2026-02-02")
            }
        }
    }

    @Test fun eventSubjectRoundTripAndDisplay() {
        val day = LocalDate.parse("2026-10-22")
        val event = Calendar.saveEvent(null, "Revision", "event", day, "09:00", "10:00", null, "", "mm")
        val loaded = JSONObject(event.toString())
        val edited = Calendar.saveEvent(loaded, "Edited", "exam", day, "10:00", "11:00", null, "R1", loaded.getString("subjectId"))
        assertEquals("mm", JSONObject(edited.toString()).getString("subjectId"))
        assertEquals("Exam · Mathematical Methods · R1", Calendar.dayPlan(day,
            Calendar.Sources(events = listOf(edited), subjectNames = mapOf("mm" to "Mathematical Methods"))).items.single().detail)
        assertEquals("Exam · mm · R1", Calendar.dayPlan(day, Calendar.Sources(events = listOf(edited))).items.single().detail)
    }

    @Test fun eventWithoutSubjectAndClearingSubject() {
        val day = LocalDate.parse("2026-10-22")
        val event = Calendar.saveEvent(null, "Meeting", "event", day, "09:00", "10:00", null, "", null)
        assertFalse(JSONObject(event.toString()).has("subjectId"))
        assertEquals("Event", Calendar.dayPlan(day, Calendar.Sources(events = listOf(event))).items.single().detail)
        for (subject in listOf("mm", JSONObject.NULL)) {
            val source = JSONObject(event.toString()).put("subjectId", subject)
            val edited = Calendar.saveEvent(source, "Meeting", "event", day, "09:00", "10:00", null, "", "")
            assertFalse(JSONObject(edited.toString()).has("subjectId"))
            assertTrue(source.has("subjectId"))
        }
    }

    @Test fun eventEditPreservesUnknownFocalFieldsWithoutMutatingSource() {
        val day = LocalDate.parse("2026-10-22")
        val source = Calendar.newEvent("Original", "event", day, "09:00", "10:00", null, "Room")
            .put("subjectId", "custom-subject").put("isFinished", true).put("finishedAt", "stamp")
            .put("future", JSONObject().put("nested", JSONArray().put(1).put(JSONObject.NULL).put(JSONObject().put("flag", true))))
            .put("futureNull", JSONObject.NULL).put("futureNumber", 42).put("futureText", "keep")
        val original = source.toString()
        val edited = JSONObject(Calendar.saveEvent(source, "Edited", "exam", day, "11:00", "12:00", null, "", source.getString("subjectId"), "updated").toString())
        for (key in listOf("id", "created_at", "subjectId", "isFinished", "finishedAt", "futureNull", "futureNumber", "futureText"))
            assertEquals(key, source.get(key), edited.get(key))
        assertEquals(source.getJSONObject("future").toString(), edited.getJSONObject("future").toString())
        edited.getJSONObject("future").put("changed", true)
        assertEquals(original, source.toString())
        assertEquals("Edited", edited.getString("title"))
        assertEquals("updated", edited.getString("updated_at"))
        assertFalse(edited.has("location"))
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

    @Test fun eventDaysExcludeOnlyTheExactMidnightBoundary() {
        val first = LocalDate.parse("2026-10-22")
        val next = first.plusDays(1)
        for (end in listOf("00:00", "00:00:00.001", "00:00:30")) {
            val event = Calendar.newEvent("Late event", "event", first, "23:00", end, next, "")
            val touchesNextDay = end != "00:00"
            assertEquals(end, first to if (touchesNextDay) next else first, Calendar.eventDays(event))
            assertEquals(end, if (touchesNextDay) 1 else 0, Calendar.eventsOn(listOf(event), next).size)
            assertEquals(end, if (touchesNextDay) 1 else 0, Calendar.dayPlan(next, Calendar.Sources(events = listOf(event))).items.size)
        }
    }

    @Test fun eventEditRetainsMidnightEndDate() {
        val first = LocalDate.parse("2026-10-22")
        for (finish in listOf(first.plusDays(1), first.plusDays(2))) {
            val event = Calendar.newEvent("Overnight", "event", first, "09:00", "00:00", finish, "")
            val endIso = event.getString("endTime")
            val endDate = Calendar.dateLabel(endIso)
            assertEquals(finish.minusDays(1), Calendar.eventDays(event)!!.second)
            assertEquals(finish.toString(), endDate)
            assertNull(Calendar.validateEvent("Edited title", first.toString(), "09:00", Calendar.timeLabel(endIso), endDate))
            assertEquals(endIso, Calendar.toIso(LocalDate.parse(endDate), Calendar.timeLabel(endIso)))
        }
        assertEquals("", Calendar.dateLabel(""))
        assertEquals("", Calendar.dateLabel("invalid"))
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

    @Test fun moveOneOverdueTaskKeepsOtherTasksAndUnknownFocalFields() {
        val learning = JSONObject("""{
            "updatedAt":"old","futureLearningField":{"value":1},
            "tasks":[
                {"id":"one","status":"planned","plannedFor":"2026-10-20","updatedAt":"old","futureTaskField":{"tags":["revision"]}},
                {"id":"two","status":"planned","plannedFor":"2026-10-21","updatedAt":"old"}
            ]
        }""")
        val original = learning.toString()
        val today = LocalDate.parse("2026-10-22")
        val now = "2026-10-22T12:34:56Z"

        val moved = requireNotNull(Calendar.moveOverdueToToday(learning, today, taskId = "one", now = now))
        val expected = JSONObject(original).put("updatedAt", now)
        expected.getJSONArray("tasks").getJSONObject(0).put("plannedFor", today.toString()).put("updatedAt", now)
        assertEquals(expected.toString(), moved.toString())
        assertEquals(original, learning.toString())
    }

    @Test fun moveAllOverdueTasksLeavesOthersUntouched() {
        val today = LocalDate.parse("2026-10-22")
        val now = "2026-10-22T12:34:56Z"
        fun task(id: String, date: String, status: String = "planned") =
            JSONObject().put("id", id).put("status", status).put("plannedFor", date).put("updatedAt", "old")

        val learning = JSONObject().put("updatedAt", "old").put("tasks", JSONArray(listOf(
            task("one", "2026-10-20"),
            task("two", "2026-10-21"),
            task("archived", "2026-10-20").put("archivedAt", "2026-10-21T00:00:00Z"),
            task("completed", "2026-10-20", "completed"),
            task("skipped", "2026-10-20", "skipped"),
            task("today", "2026-10-22"),
            task("future", "2026-10-23"),
            task("unscheduled", ""),
            task("invalid", "2026-02-30")
        )))

        assertNull(Calendar.moveOverdueToToday(learning, today, taskId = "completed", now = now))
        assertNull(Calendar.moveOverdueToToday(learning, today, taskId = "absent", now = now))
        val moved = requireNotNull(Calendar.moveOverdueToToday(learning, today, now = now))
        val expected = JSONObject(learning.toString()).put("updatedAt", now)
        repeat(2) { expected.getJSONArray("tasks").getJSONObject(it).put("plannedFor", today.toString()).put("updatedAt", now) }
        assertEquals(expected.toString(), moved.toString())
        assertNull(Calendar.moveOverdueToToday(moved, today, now = now))
    }
}
