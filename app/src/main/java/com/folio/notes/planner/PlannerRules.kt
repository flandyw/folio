package com.folio.notes.planner

import com.folio.notes.mistakes.isoTime
import com.folio.notes.progress.LoggedExam
import com.folio.notes.progress.number
import com.folio.notes.progress.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Focal's SAC list (`user_state:sacRecords`) and learning workspace (`user_state:learning`: stoplight
 * items, goals, planned tasks), edited as raw JSON so fields this build does not know ride along
 * untouched. Focal drops the *whole* list when one record fails its validation, so every write here
 * is checked against the same rules (`src/lib/sac.ts`, `learning-workspace.ts`).
 */
object Planner {
    const val EPOCH = "1970-01-01T00:00:00.000Z"
    val RATINGS = listOf("red", "amber", "green")
    private val TYPES = listOf("recall", "technique", "apply", "practical")
    const val UNGROUPED = "Ungrouped"
    private const val DAY_MS = 86_400_000L

    // ---- SACs ----

    fun isCompleted(sac: JSONObject) = sac.number("score") != null && sac.number("maxScore") != null
    fun percentage(sac: JSONObject): Double? =
        if (isCompleted(sac)) sac.getDouble("score") / sac.getDouble("maxScore") * 100 else null

    fun validateSac(s: JSONObject): String? {
        if (listOf("subject", "provider", "title").any { s.optString(it).isBlank() }) return "Subject, school and SAC title are required."
        if (s.optInt("unit") !in 1..4) return "Unit must be between 1 and 4."
        if (runCatching { LocalDate.parse(s.optString("scheduledAt").take(10)) }.isFailure) return "Enter the date as YYYY-MM-DD."
        if ((s.number("durationMinutes") ?: 0.0) <= 0) return "Duration must be greater than zero."
        val score = s.number("score"); val max = s.number("maxScore")
        if ((score == null) != (max == null)) return "Enter both the mark and maximum, or leave both blank."
        if (score != null && max != null) {
            if (max <= 0) return "Maximum mark must be greater than zero."
            if (score < 0) return "Mark must be zero or greater."
            if (score > max) return "Mark cannot exceed the maximum."
        }
        s.number("weighting")?.let { if (it <= 0 || it > 100) return "Weighting must be between 0 and 100%." }
        return null
    }

    fun newSac(subject: String, provider: String, title: String, unit: Int, date: String, minutes: Double,
        score: Double?, max: Double?, weighting: Double?, now: String = isoTime()): JSONObject = JSONObject()
        .put("id", UUID.randomUUID().toString()).put("subject", subject.trim()).put("provider", provider.trim())
        .put("title", title.trim()).put("unit", unit).put("scheduledAt", date).put("durationMinutes", minutes)
        .apply {
            if (score != null && max != null) { put("score", score); put("maxScore", max); put("completedAt", date) }
            if (weighting != null) put("weighting", weighting)
        }.put("createdAt", now).put("updatedAt", now)

    fun upcoming(sacs: List<JSONObject>, today: String = LocalDate.now().toString()) =
        sacs.filter { !isCompleted(it) && it.optString("scheduledAt") >= today }.sortedBy { it.optString("scheduledAt") }

    private fun weighted(done: List<JSONObject>): Double? {
        val total = done.sumOf { it.number("weighting") ?: 1.0 }
        return if (total > 0) done.sumOf { percentage(it)!! * (it.number("weighting") ?: 1.0) } / total else null
    }

    data class SacStats(val total: Int, val completed: Int, val upcoming: Int, val average: Double?, val best: Double?, val trend: Double?)
    fun stats(sacs: List<JSONObject>): SacStats {
        val done = sacs.filter(::isCompleted)
        val ordered = done.sortedBy { it.optString("completedAt", it.optString("scheduledAt")) }
        return SacStats(sacs.size, done.size, upcoming(sacs).size, weighted(done), done.maxOfOrNull { percentage(it)!! },
            if (ordered.size >= 2) percentage(ordered.last())!! - percentage(ordered.first())!! else null)
    }

    data class SubjectStats(val subject: String, val completed: Int, val upcoming: Int, val average: Double?)
    fun bySubject(sacs: List<JSONObject>) = sacs.groupBy { it.optString("subject") }.map { (subject, list) ->
        SubjectStats(subject, list.count(::isCompleted), upcoming(list).size, weighted(list.filter(::isCompleted)))
    }.sortedWith(compareByDescending<SubjectStats> { it.completed }.thenBy { it.subject })

    // ---- Learning workspace ----

    fun learning(value: Any?): JSONObject = (value as? JSONObject)?.let { JSONObject(it.toString()) } ?: JSONObject()
        .put("tasks", JSONArray()).put("curriculumAreas", JSONArray()).put("goals", JSONArray())
        .put("preferences", JSONObject().put("dailyMinutes", 60).put("studyDays", JSONArray(listOf(1, 2, 3, 4, 5, 6))))
        .put("preferencesUpdatedAt", EPOCH).put("updatedAt", EPOCH)

    fun items(l: JSONObject) = l.optJSONArray("curriculumAreas").orEmpty().objects().filter { !it.has("archivedAt") }
    fun goals(l: JSONObject, archived: Boolean) = l.optJSONArray("goals").orEmpty().objects().filter { it.has("archivedAt") == archived }
    private fun JSONArray?.orEmpty() = this ?: JSONArray()

    /** Rewrites the entry [id] of [key] with [change]; the workspace's own stamp moves with it. */
    fun edit(l: JSONObject, key: String, id: String, now: String = isoTime(), change: (JSONObject) -> Unit): JSONObject {
        val copy = JSONObject(l.toString())
        copy.getJSONArray(key).objects().first { it.optString("id") == id }.also(change).put("updatedAt", now)
        return copy.put("updatedAt", now)
    }

    fun append(l: JSONObject, key: String, entries: List<JSONObject>, now: String = isoTime()): JSONObject {
        val copy = JSONObject(l.toString())
        entries.forEach { copy.getJSONArray(key).put(it) }
        return copy.put("updatedAt", now)
    }

    // ---- Stoplight ----

    fun setRating(l: JSONObject, id: String, rating: String, now: String = isoTime()) =
        edit(l, "curriculumAreas", id, now) { it.put("rating", rating).put("ratedAt", now) }

    fun group(item: JSONObject) = item.optString("group").trim().ifEmpty { UNGROUPED }

    /** Greens fade after three weeks, ambers after two. */
    fun recheckDue(item: JSONObject, now: Instant = Instant.now()): Boolean {
        val days = when (item.optString("rating")) { "green" -> 21; "amber" -> 14; else -> return false }
        val rated = runCatching { Instant.parse(item.optString("ratedAt")) }.getOrDefault(Instant.EPOCH)
        return now.toEpochMilli() - rated.toEpochMilli() > days * DAY_MS
    }

    data class Imported(val group: String, val name: String, val check: String?, val type: String?)

    /** Accepts Focal's `{"groups":[{"group","items":[…]}]}`, a flat `{"items":[…]}` or a bare array, with or without a code fence. */
    fun parseImport(raw: String): List<Imported> {
        val start = raw.indexOfFirst { it == '{' || it == '[' }; val end = raw.indexOfLast { it == '}' || it == ']' }
        require(start in 0 until end) { "Paste the chatbot's JSON reply." }
        val parsed = JSONObject("{\"v\":${raw.substring(start, end + 1)}}").get("v")
        fun text(o: JSONObject, key: String, max: Int) = o.optString(key).trim().take(max)
        fun item(group: String, o: Any?): Imported? {
            val fields = o as? JSONObject ?: JSONObject().put("name", o as? String ?: "")
            val name = text(fields, "name", 200).ifEmpty { text(fields, "title", 200) }
            return if (name.isEmpty()) null else Imported(group.trim().take(200).ifEmpty { "General" }, name,
                text(fields, "check", 200).ifEmpty { null }, text(fields, "type", 20).lowercase().takeIf { it in TYPES })
        }
        val found = mutableListOf<Imported?>()
        fun flat(list: JSONArray) = (0 until list.length()).forEach { i ->
            val o = list.opt(i); found += item((o as? JSONObject)?.optString("group").orEmpty(), o) }
        when (parsed) {
            is JSONArray -> flat(parsed)
            is JSONObject -> {
                parsed.optJSONArray("groups")?.objects()?.forEach { g ->
                    val name = g.optString("group").ifEmpty { g.optString("name") }
                    g.optJSONArray("items")?.let { items -> (0 until items.length()).forEach { found += item(name, items.opt(it)) } }
                }
                parsed.optJSONArray("items")?.let(::flat)
            }
        }
        val seen = mutableSetOf<String>()
        return found.filterNotNull().filter { seen.add("${it.group}|${it.name}".lowercase()) }
    }

    /** Adds new items and refreshes check/type on existing ones; ratings and ids stay put so links survive a re-import. */
    fun merge(l: JSONObject, subject: String, imported: List<Imported>, now: String = isoTime()): Pair<JSONObject, Int> {
        val copy = JSONObject(l.toString()); val areas = copy.getJSONArray("curriculumAreas"); var added = 0
        fun key(s: String, g: String, n: String) = "$s|$g|$n".lowercase().replace(Regex("\\s+"), " ")
        val existing = areas.objects().associateBy { key(it.optString("subject"), group(it), it.optString("name")) }.toMutableMap()
        for (e in imported) {
            val found = existing[key(subject, e.group, e.name)]
            if (found != null) {
                found.remove("archivedAt"); e.check?.let { found.put("check", it) }; e.type?.let { found.put("type", it) }
                found.put("updatedAt", now)
            } else {
                added++
                val item = JSONObject().put("id", UUID.randomUUID().toString()).put("subject", subject).put("name", e.name)
                    .put("group", e.group).put("createdAt", now).put("updatedAt", now)
                e.check?.let { item.put("check", it) }; e.type?.let { item.put("type", it) }
                areas.put(item); existing[key(subject, e.group, e.name)] = item
            }
        }
        return copy.put("updatedAt", now) to added
    }

    /** Spreads the weakest items of [subject] over the next seven study days, skipping items that already have a task. */
    fun planWeek(l: JSONObject, subject: String, today: LocalDate = LocalDate.now(), now: String = isoTime()): Pair<JSONObject, Int> {
        val prefs = l.optJSONObject("preferences")
        val daily = prefs?.optInt("dailyMinutes", 60)?.takeIf { it > 0 } ?: 60
        val studyDays = prefs?.optJSONArray("studyDays")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: listOf(1, 2, 3, 4, 5, 6)
        val planned = l.optJSONArray("tasks").orEmpty().objects().filter { !it.has("archivedAt") && it.optString("status") == "planned" }
            .map { it.optString("sourceId") }.toSet()
        fun score(i: JSONObject) = when (i.optString("rating")) { "red" -> 30; "amber" -> 20; else -> 10 }
        val queue = items(l).filter { it.optString("subject") == subject && it.optString("id") !in planned &&
            (it.optString("rating") in listOf("red", "amber") || recheckDue(it)) }.sortedByDescending(::score)
        // java.time Sunday is 7; Focal's days are 0 = Sunday.
        val days = (0L until 14).map { today.plusDays(it) }.filter { it.dayOfWeek.value % 7 in studyDays }.take(7)
        val perDay = maxOf(1, daily / 25)
        val tasks = queue.take(days.size * perDay).mapIndexed { index, item ->
            JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "topic-practice").put("title", "Revise: ${item.optString("name")}")
                .put("subject", subject).put("detail", item.optString("check").let { if (it.isEmpty()) group(item) else "${group(item)} · Check: $it" })
                .put("durationMinutes", minOf(25, daily)).put("plannedFor", days[index / perDay].toString()).put("status", "planned")
                .put("sourceId", item.optString("id")).put("createdAt", now).put("updatedAt", now)
        }
        return if (tasks.isEmpty()) l to 0 else append(l, "tasks", tasks, now) to tasks.size
    }

    // shortcut: no per-subject guidance paragraphs (Focal adds maths/PE/science hints); copy them across if checklists come back too generic.
    fun importPrompt(subject: String) = """Turn the attached VCAA study design (or my teacher's course outline) for "$subject" into a revision checklist.

Reply with ONLY one JSON code block, no other text, in exactly this shape:
{"groups":[{"group":"Unit 3 AOS 2 – Differentiation","items":[{"name":"Product rule","type":"technique","check":"Differentiate x²·sin(3x) without notes in under 1 minute"}]}]}

Rules:
- One item per key knowledge or key skill dot point. Split compound dot points into separate items, each one testable idea.
- Group by Unit and Area of Study, in study design order. At most 12 items per group. Item names must be unique within the subject.
- type: "recall" (definitions, facts), "technique" (a procedure you perform), "apply" (use in an unfamiliar context) or "practical" (data, investigations, practical work).
- check: ONE sentence a student could pass or fail in under 3 minutes, unaided. It must be observable ("Differentiate…", "Explain … using the terms …"), never "understand X".
- Only include what is in my source. Do not invent content.
- If the reply would be very long, stop after a complete group and write CONTINUE? after the code block; I will ask for the rest.

Source:
"""

    // ---- Goals ----

    val GOAL_KINDS = listOf("exam-percentage" to "Exam average", "study-score" to "Raw study score", "atar" to "ATAR")
    fun kindLabel(kind: String) = GOAL_KINDS.firstOrNull { it.first == kind }?.second ?: kind

    fun validateGoal(kind: String, subject: String, target: Double?, deadline: String, today: LocalDate = LocalDate.now()): String? {
        val max = when (kind) { "study-score" -> 50.0; "atar" -> 99.95; else -> 100.0 }
        if (kind != "atar" && subject.isBlank()) return "Choose a subject."
        if (target == null || target <= 0 || target > max) return "Target must be between 1 and ${max.toString().removeSuffix(".0")}."
        val date = runCatching { LocalDate.parse(deadline) }.getOrNull() ?: return "Enter the deadline as YYYY-MM-DD."
        if (date.isBefore(today)) return "Choose today or a future deadline."
        return null
    }

    fun newGoal(kind: String, subject: String, target: Double, deadline: String, now: String = isoTime()): JSONObject =
        JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
            .apply { if (kind != "atar") put("subject", subject.trim()) }
            .put("target", target).put("deadline", deadline).put("createdAt", now).put("updatedAt", now)

    data class GoalProgress(val current: Double?, val progress: Double, val gap: Double?, val label: String, val evidence: String)

    /** [atarEstimates] is `user_state:atarEstimates`, newest first; study-score goals need Focal's predictor, which Folio does not have yet. */
    fun goalProgress(goal: JSONObject, exams: List<LoggedExam>, atarEstimates: JSONArray?): GoalProgress {
        val target = goal.getDouble("target"); val subject = goal.optString("subject")
        var current: Double? = null; var label = "Current"; var evidence = "No compatible evidence yet."
        when (goal.optString("kind")) {
            "exam-percentage" -> {
                val matched = exams.filter { subject.isEmpty() || it.subject.equals(subject, true) }
                label = "Average exam result"
                if (matched.isNotEmpty()) { current = matched.sumOf { it.percentage } / matched.size; evidence = "Based on ${matched.size} recorded paper(s)." }
            }
            "study-score" -> { label = "Predicted raw study score"; evidence = "Open this goal in Focal for a predicted study score." }
            "atar" -> {
                val saved = atarEstimates?.objects()?.firstOrNull()
                label = "Latest saved ATAR estimate"
                current = saved?.optString("atarLabel")?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
                evidence = if (current != null) "Saved ${saved?.optString("savedAt").orEmpty().take(10)}." else "Save an ATAR estimate in Focal to establish a baseline."
            }
        }
        return GoalProgress(current, current?.let { (it / target * 100).coerceIn(0.0, 100.0) } ?: 0.0,
            current?.let { maxOf(0.0, target - it) }, label, evidence)
    }
}
