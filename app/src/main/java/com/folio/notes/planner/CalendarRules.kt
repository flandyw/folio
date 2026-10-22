package com.folio.notes.planner

import com.folio.notes.mistakes.isoTime
import com.folio.notes.progress.number
import com.folio.notes.progress.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Calendar events (`events` rows) and the class timetable (`timetable_config`), the shapes Focal and its desktop app share. */
object Calendar {
    val EVENT_TYPES = listOf("sac" to "SAC", "practice-sac" to "Practice SAC", "exam" to "Exam", "assignment" to "Assignment",
        "homework" to "Homework", "event" to "Event", "other" to "Other")
    fun typeLabel(type: String) = EVENT_TYPES.firstOrNull { it.first == type }?.second ?: "Event"
    const val TIMETABLE_ROW = "timetable_config"
    private val zone get() = ZoneId.systemDefault()

    private fun local(iso: String) = runCatching { Instant.parse(iso).atZone(zone).toLocalDateTime() }.getOrNull()

    /** First and last local day an event touches; an end at exactly midnight belongs to the day before. */
    fun eventDays(event: JSONObject): Pair<LocalDate, LocalDate>? {
        val start = local(event.optString("startTime")) ?: return null
        val end = event.optString("endTime").takeIf { it.isNotEmpty() }?.let(::local)
        val last = if (end != null && end.isAfter(start)) end.minusMinutes(1) else start
        return start.toLocalDate() to last.toLocalDate()
    }

    fun eventsOn(events: List<JSONObject>, date: LocalDate) = events.filter { e ->
        e.optString("deleted_at").isEmpty() && eventDays(e)?.let { (first, last) -> date >= first && date <= last } == true
    }.sortedBy { it.optString("startTime") }

    fun timeLabel(iso: String) = local(iso)?.toLocalTime()?.toString().orEmpty()

    fun toIso(date: LocalDate, time: String): String = LocalDateTime.of(date, LocalTime.parse(time)).atZone(zone).toInstant().toString()

    /** [endDate] may differ from [date] for a multi-day event; null for a plain start/end on [date]. */
    fun newEvent(title: String, type: String, date: LocalDate, start: String, end: String, endDate: LocalDate?, location: String, now: String = isoTime()): JSONObject =
        JSONObject().put("id", UUID.randomUUID().toString()).put("title", title.trim()).put("eventType", type)
            .put("startTime", toIso(date, start)).put("endTime", toIso(endDate ?: date, end))
            .apply { if (location.isNotBlank()) put("location", location.trim()) }.put("created_at", now).put("updated_at", now)

    fun validateEvent(title: String, date: String, start: String, end: String, endDate: String): String? {
        if (title.isBlank()) return "Add a title."
        val first = runCatching { LocalDate.parse(date) }.getOrNull() ?: return "Enter the date as YYYY-MM-DD."
        val s = runCatching { LocalTime.parse(start) }.getOrNull(); val e = runCatching { LocalTime.parse(end) }.getOrNull()
        if (s == null || e == null) return "Enter times as HH:mm."
        val finish = if (endDate.isBlank()) first else runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return "Enter the end date as YYYY-MM-DD."
        if (finish.isBefore(first) || (finish == first && !e.isAfter(s))) return "The event must end after it starts."
        return null
    }

    // ---- Timetable ----

    class Period(val name: String, val subject: String, val location: String, val start: String, val end: String)

    private fun minutes(value: String): Int? = Regex("^(\\d{2}):(\\d{2})$").find(value)?.let { m ->
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); if (h > 23 || mi > 59) null else h * 60 + mi }

    fun periodError(name: String, start: String, end: String): String? {
        if (name.isBlank()) return "Add a period name."
        val s = minutes(start); val e = minutes(end)
        if (s == null || e == null) return "Use a valid start and end time."
        return if (e <= s) "End time must be after start time." else null
    }

    private fun inHoliday(date: LocalDate, holidays: List<JSONObject>) =
        holidays.any { date.toString() >= it.optString("startDate") && date.toString() <= it.optString("endDate") }

    /** The cycle day (1..cycleLength) a date falls on, or null on weekends, holidays and before Day 1. */
    fun dayLabel(date: LocalDate, config: JSONObject): Int? {
        val holidays = config.optJSONArray("holidays")?.objects().orEmpty()
        if (inHoliday(date, holidays)) return null
        val start = runCatching { LocalDate.parse(config.optString("day1Starts")) }.getOrNull() ?: return null
        if (date.isBefore(start)) return null
        val weekends = config.optBoolean("weekendTimetables", false)
        if (!weekends && (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)) return null
        var count = 0; var cursor = start
        // shortcut: day-by-day scan like Focal's; fine for a school year, merge holiday ranges if spans grow.
        while (cursor.isBefore(date)) {
            cursor = cursor.plusDays(1)
            val weekend = cursor.dayOfWeek == DayOfWeek.SATURDAY || cursor.dayOfWeek == DayOfWeek.SUNDAY
            if ((weekends || !weekend) && !inHoliday(cursor, holidays)) count++
        }
        val length = config.optInt("cycleLength", 10).takeIf { it >= 1 } ?: 10
        return count % length + 1
    }

    fun periodsOn(date: LocalDate, config: JSONObject): Pair<Int, List<Period>>? {
        val label = dayLabel(date, config) ?: return null
        val periods = config.optJSONArray("entries")?.objects().orEmpty().filter { it.optInt("dayLabel") == label }
            .flatMap { it.optJSONArray("periods")?.objects().orEmpty() }
            .map { Period(it.optString("period"), it.optString("subject"), it.optString("location"), it.optString("startTime"), it.optString("endTime")) }
            .sortedBy { minutes(it.start) ?: Int.MAX_VALUE }
        return label to periods
    }

    const val SCREENSHOT_PROMPT = """I have attached a screenshot of my school timetable. Convert it into a Focal timetable import file.

Return only valid JSON with no markdown fences, commentary, or citations. Use exactly this shape:
{
  "cycleLength": 10,
  "entries": [
    {
      "dayLabel": 1,
      "periods": [
        {
          "period": "Period 1",
          "subject": "Mathematical Methods",
          "location": "Room 12",
          "startTime": "09:00",
          "endTime": "10:00"
        }
      ]
    }
  ]
}

Requirements:
- Read every visible timetable day and put each day in one entries item.
- dayLabel must be a whole number starting at 1. Preserve numbered cycle days if shown; otherwise number the visible days from left to right.
- cycleLength must equal the timetable cycle length. If the screenshot only shows one five-day school week, use 5. If it shows a two-week Day 1–10 cycle, use 10.
- Use 24-hour HH:mm times with leading zeroes. Copy the displayed times precisely.
- Include classes, study periods, homeroom/form, assembly, recess, lunch, and other visible fixed periods.
- period is the row or period label. subject is the subject exactly as shown. location is the room exactly as shown, or an empty string when none is visible.
- Do not invent unreadable subjects, rooms, days, or times. Use an empty string for an unreadable subject or location. If a start or end time is unreadable, stop and tell me which time needs clarification instead of producing JSON.
- Do not include dates, holidays, colours, teachers, notes, or any keys not shown in the required shape.

Before answering, silently check that every endTime is later than its startTime and that every dayLabel is between 1 and cycleLength. Your final answer must be only the raw JSON so I can save it as focal-timetable.json and import it."""

    /** Validates Focal's import JSON and returns the config with [current]'s holidays and view settings kept. */
    fun parseTimetable(raw: String, current: JSONObject?, day1Starts: String): JSONObject {
        fun fail(message: String): Nothing = throw IllegalArgumentException("Could not import timetable: $message")
        val text = raw.trim().replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*```$"), "")
        val root = try { JSONObject(text) } catch (_: Exception) { fail("the JSON is malformed.") }
        val cycle = root.optInt("cycleLength", 0)
        if (cycle !in 1..60) fail("cycleLength must be a whole number from 1 to 60.")
        val entries = root.optJSONArray("entries")?.objects()?.takeIf { it.isNotEmpty() } ?: fail("entries must contain at least one day.")
        val merged = (1..cycle).mapNotNull { label ->
            val days = entries.filter { it.optInt("dayLabel", -1) == label }
            val periods = days.flatMap { day ->
                day.optJSONArray("periods")?.objects()?.takeIf { it.isNotEmpty() } ?: fail("Day $label must contain at least one period.") }
            if (periods.isEmpty()) null else JSONObject().put("dayLabel", label).put("periods", JSONArray(periods.map { p ->
                val fields = listOf("period", "subject", "location", "startTime", "endTime").associateWith { p.optString(it).trim() }
                periodError(fields.getValue("period"), fields.getValue("startTime"), fields.getValue("endTime"))?.let { fail("Day $label: $it") }
                JSONObject(fields)
            }.sortedBy { minutes(it.getString("startTime")) }))
        }
        if (entries.any { it.optInt("dayLabel", -1) !in 1..cycle }) fail("a dayLabel is outside 1-$cycle.")
        return JSONObject(current?.toString() ?: "{}").put("enabled", true).put("cycleLength", cycle).put("day1Starts", day1Starts)
            .apply { if (!has("holidays")) put("holidays", JSONArray()); remove("dayToWeekday"); put("currentDayOverride", JSONObject.NULL) }
            .put("entries", JSONArray(merged))
    }
}
