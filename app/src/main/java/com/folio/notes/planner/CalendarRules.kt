package com.folio.notes.planner

import com.folio.notes.mistakes.isoTime
import com.folio.notes.progress.number
import com.folio.notes.progress.text
import com.folio.notes.progress.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.UUID

/** Calendar events (`events` rows) and the class timetable (`timetable_config`), the shapes Focal and its desktop app share. */
object Calendar {
    val EVENT_TYPES = listOf("sac" to "SAC", "practice-sac" to "Practice SAC", "exam" to "Exam", "assignment" to "Assignment",
        "homework" to "Homework", "event" to "Event", "other" to "Other")
    fun typeLabel(type: String) = EVENT_TYPES.firstOrNull { it.first == type }?.second ?: "Event"
    const val TIMETABLE_ROW = "timetable_config"
    private val zone get() = ZoneId.systemDefault()

    /** Focal writes `Z` instants, but rows from other clients carry offsets or bare dates (read as UTC midnight, like JS). */
    private fun instant(iso: String): Instant? = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(iso).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
    private fun local(iso: String) = instant(iso)?.atZone(zone)?.toLocalDateTime()

    /** First and last local day an event touches; an end at exactly midnight belongs to the day before. */
    fun eventDays(event: JSONObject): Pair<LocalDate, LocalDate>? {
        val start = local(event.text("startTime")) ?: return null
        val end = local(event.text("endTime"))
        val last = if (end != null && end.isAfter(start)) end.minusMinutes(1) else start
        return start.toLocalDate() to last.toLocalDate()
    }

    fun eventsOn(events: List<JSONObject>, date: LocalDate) = events.filter { e ->
        e.text("deleted_at").isEmpty() && eventDays(e)?.let { (first, last) -> date >= first && date <= last } == true
    }.sortedBy { it.text("startTime") }

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

    // ---- Day plan (Focal's day-plan.ts) ----

    enum class Kind(val label: String) { TASK("Task"), EVENT("Event"), SAC("SAC"), SESSION("Study session"), LOGGED("Logged exam"), REVIEW("Review") }

    /** One line of a day. [counts] marks study work (it moves the day's minutes); events and the review queue are markers. */
    class Item(val kind: Kind, val id: String, val title: String, val detail: String, val minutes: Double, val time: String,
        val done: Boolean, val counts: Boolean, val fixed: Boolean = false, val skipped: Boolean = false, val live: Boolean = false)

    class Session(val id: String, val title: String, val detail: String, val blocks: List<Pair<Long, Long>>, val done: Boolean, val live: Boolean)
    class Logged(val id: String, val title: String, val detail: String, val date: LocalDate, val minutes: Double)
    class Sources(val tasks: List<JSONObject> = emptyList(), val sacs: List<JSONObject> = emptyList(), val events: List<JSONObject> = emptyList(),
        val sessions: List<Session> = emptyList(), val logged: List<Logged> = emptyList(), val dueMistakes: Map<LocalDate, Int> = emptyMap(),
        /** Focal files desktop rows under VCE subject ids ("mm", "chem"); show the names it shows. */
        val subjectNames: Map<String, String> = emptyMap()) {
        // Built once per source, not per day: a month asks 42 days, and parsing every event's instants for each was the planner's main cost.
        internal val tasksByDay by lazy { tasks.filter { !it.has("archivedAt") }.groupBy { it.text("plannedFor") } }
        internal val sacsByDay by lazy { sacs.groupBy { it.text("scheduledAt").take(10) } }
        internal val loggedByDay by lazy { logged.groupBy { it.date } }
        internal val spans by lazy { events.filter { it.text("deleted_at").isEmpty() }.mapNotNull { e -> eventDays(e)?.let { (first, last) ->
            val start = instant(e.text("startTime")); val end = instant(e.text("endTime"))
            Span(e, first, last, if (start != null && end != null) ((end.toEpochMilli() - start.toEpochMilli()) / 60_000.0).coerceAtLeast(0.0) else 0.0, timeLabel(e.text("startTime")))
        } } }
    }
    internal class Span(val event: JSONObject, val first: LocalDate, val last: LocalDate, val minutes: Double, val time: String)

    class Plan(val date: LocalDate, val items: List<Item>, val plannedMinutes: Int, val completedMinutes: Int, val completedCount: Int,
        val totalCount: Int, val dueMistakes: Int)
    class Day(val date: LocalDate, val inMonth: Boolean, val plan: Plan) { val minutes get() = plan.plannedMinutes + plan.completedMinutes }

    private val FIXED_TYPES = setOf("sac", "practice-sac", "exam")

    /** Overlapping blocks count once, like Focal's merged minutes. */
    private fun mergedMinutes(blocks: List<Pair<Long, Long>>): Double {
        var total = 0L; var start = 0L; var end = Long.MIN_VALUE
        for ((s, e) in blocks.filter { it.second > it.first }.sortedBy { it.first }) {
            if (s > end) { if (end > start) total += end - start; start = s; end = e } else end = maxOf(end, e)
        }
        if (end > start) total += end - start
        return total / 60_000.0
    }

    fun dayPlan(date: LocalDate, src: Sources): Plan {
        val items = mutableListOf<Item>()
        val sessionTitles = mutableSetOf<String>()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli(); val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        for (session in src.sessions) {
            val clipped = session.blocks.mapNotNull { (s, e) -> (maxOf(s, dayStart) to minOf(e, dayEnd)).takeIf { it.second > it.first } }
            if (clipped.isEmpty()) continue
            sessionTitles += session.title
            items += Item(Kind.SESSION, session.id, session.title, session.detail, mergedMinutes(clipped),
                Instant.ofEpochMilli(clipped.minOf { it.first }).atZone(zone).toLocalTime().withNano(0).toString().take(5), session.done, true, live = session.live)
        }
        for (task in src.tasksByDay[date.toString()].orEmpty()) {
            val status = task.text("status"); val covered = task.text("title") in sessionTitles
            // A sitting the timer ran from this task carries its title: the sitting's minutes win, the task reads as done.
            items += Item(Kind.TASK, task.text("id"), task.text("title"), task.text("subject"), task.optDouble("durationMinutes", 0.0), "",
                status == "completed" || covered, status != "skipped" && !covered, skipped = status == "skipped")
        }
        for (sac in src.sacsByDay[date.toString()].orEmpty()) {
            items += Item(Kind.SAC, sac.text("id"), sac.text("title"), listOf(sac.text("subject"), sac.opt("unit")?.let { "Unit $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                sac.optDouble("durationMinutes", 0.0).takeIf { it.isFinite() } ?: 0.0, "", Planner.isCompleted(sac), true, fixed = true)
        }
        for (span in src.spans) {
            if (date < span.first || date > span.last) continue
            val event = span.event; val first = span.first
            val multi = first != span.last
            val minutes = if (multi) 0.0 else span.minutes
            items += Item(Kind.EVENT, event.text("id"), event.text("title"),
                listOf(typeLabel(event.text("eventType")), event.text("subjectId").let { src.subjectNames[it] ?: it }, event.text("location")).filter { it.isNotBlank() }.joinToString(" · "),
                minutes, if (multi && date != first) "" else span.time, event.optBoolean("isFinished"), false, fixed = event.text("eventType") in FIXED_TYPES)
        }
        for (exam in src.loggedByDay[date].orEmpty()) items += Item(Kind.LOGGED, exam.id, exam.title, exam.detail, exam.minutes, "", true, true)
        val due = src.dueMistakes[date] ?: 0
        if (due > 0) items += Item(Kind.REVIEW, "mistakes:$date", if (due == 1) "1 mistake due for review" else "$due mistakes due for review",
            "From your spaced revision queue", 0.0, "", false, false)
        val work = items.filter { it.counts }
        val order = mapOf(Kind.TASK to 0, Kind.SAC to 1, Kind.EVENT to 2, Kind.SESSION to 3, Kind.LOGGED to 4, Kind.REVIEW to 5)
        return Plan(date, items.sortedWith(compareBy<Item> { order.getValue(it.kind) }.thenBy { it.time.ifEmpty { "~" } }.thenByDescending { it.minutes }),
            work.filter { !it.done }.sumOf { it.minutes }.let(Math::round).toInt(), work.filter { it.done }.sumOf { it.minutes }.let(Math::round).toInt(),
            work.count { it.done }, work.size, due)
    }

    /** Monday-first weeks covering [month]; trailing weeks wholly in the next month are dropped. */
    fun month(month: YearMonth, src: Sources): List<List<Day>> {
        val first = month.atDay(1); val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        return (0 until 6).map { w -> (0 until 7).map { d -> start.plusDays(w * 7L + d).let { Day(it, YearMonth.from(it) == month, dayPlan(it, src)) } } }
            .filter { week -> week.any { it.inMonth } }
    }

    /** Planned tasks left behind before [before], oldest first. */
    fun overdue(tasks: List<JSONObject>, before: LocalDate) = tasks.filter { !it.has("archivedAt") && it.text("status") == "planned" && it.text("plannedFor").isNotEmpty() && it.text("plannedFor") < before.toString() }
        .sortedWith(compareBy({ it.text("plannedFor") }, { it.text("createdAt") }))

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
            .map { Period(it.text("period"), it.text("subject"), it.text("location"), it.text("startTime"), it.text("endTime")) }
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
                val fields = listOf("period", "subject", "location", "startTime", "endTime").associateWith { p.text(it).trim() }
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
