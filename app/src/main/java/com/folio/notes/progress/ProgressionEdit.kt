package com.folio.notes.progress

import org.json.JSONArray
import org.json.JSONObject

/**
 * The editing rules behind the progression dialog. Pure: a plan is a list of paper objects in Focal's wire shape,
 * and every operation returns a new list, so the dialog can keep a cheap undo stack of snapshots.
 */
internal object ProgressionEdit {
    const val MAX_PAPERS = 1000
    private const val MAX_YEARS_AT_ONCE = 40

    /** "2019-2023", "2019, 2021" or "2018 2020-2022" → ascending distinct years; throws a readable message otherwise. */
    fun parseYears(spec: String): List<Int> {
        val years = sortedSetOf<Int>()
        for (part in spec.split(Regex("[,;\\s]+")).filter(String::isNotBlank)) {
            val range = Regex("^(\\d{4})\\s*[-–—]\\s*(\\d{4})$").matchEntire(part)
            if (range != null) {
                val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt()
                require(a <= b) { "A year range must run from the earlier year to the later one." }
                require(b - a < MAX_YEARS_AT_ONCE) { "Add up to $MAX_YEARS_AT_ONCE years at a time." }
                (a..b).forEach(years::add)
            } else years += requireNotNull(part.toIntOrNull()) { "Enter years like 2022, 2019, 2021 or 2018-2023." }
        }
        require(years.isNotEmpty()) { "Enter a year, a list of years, or a range like 2018-2023." }
        require(years.size <= MAX_YEARS_AT_ONCE) { "Add up to $MAX_YEARS_AT_ONCE years at a time." }
        require(years.all { it in 1990..2100 }) { "Years must be whole numbers from 1990 to 2100." }
        return years.toList()
    }

    fun paper(subject: String, provider: String, year: Int, paper: String, marks: Double, phase: String): JSONObject =
        JSONObject().put("subject", subject.trim()).put("provider", provider.trim()).put("examYear", year)
            .put("paper", paper.trim()).put("marks", marks).put("phase", phase.trim())

    /** New papers go after the last paper of their subject, so a subject stays together as the plan grows. */
    fun insert(plan: List<JSONObject>, added: List<JSONObject>): List<JSONObject> {
        val result = plan.toMutableList()
        for (exam in added) {
            val at = result.indexOfLast { comparisonName(it.optString("subject")) == comparisonName(exam.optString("subject")) }
            result.add(if (at < 0) result.size else at + 1, exam)
        }
        return result
    }

    /** Moves one paper among the papers of its own subject (the only order that is visible). */
    fun move(plan: List<JSONObject>, index: Int, delta: Int): List<JSONObject> {
        val subject = comparisonName(plan[index].optString("subject"))
        val sibling = if (delta < 0) (index - 1 downTo 0).firstOrNull { comparisonName(plan[it].optString("subject")) == subject }
            else (index + 1..plan.lastIndex).firstOrNull { comparisonName(plan[it].optString("subject")) == subject }
        if (sibling == null) return plan
        return plan.toMutableList().also { java.util.Collections.swap(it, index, sibling) }
    }

    /** Orders one subject's papers by year (then paper name) within the slots that subject already occupies. */
    fun sortSubject(plan: List<JSONObject>, subject: String, oldestFirst: Boolean): List<JSONObject> {
        val key = comparisonName(subject)
        val slots = plan.indices.filter { comparisonName(plan[it].optString("subject")) == key }
        val order = compareBy<JSONObject> { it.optInt("examYear") }.thenBy { it.optString("paper") }
        val sorted = slots.map(plan::get).sortedWith(if (oldestFirst) order else order.reversed())
        val result = plan.toMutableList()
        slots.forEachIndexed { i, slot -> result[slot] = sorted[i] }
        return result
    }

    fun removeSubject(plan: List<JSONObject>, subject: String) = plan.filterNot { comparisonName(it.optString("subject")) == comparisonName(subject) }

    fun duplicate(plan: List<JSONObject>, index: Int): List<JSONObject>? {
        val source = plan[index]; val taken = plan.map(::paperKey).toSet()
        var year = source.optInt("examYear") + 1
        // The copy is the same paper a year on; if that is taken it walks forward to the next free year.
        while (year <= 2100) {
            val copy = JSONObject(source.toString()).put("examYear", year)
            if (paperKey(copy) !in taken) return plan.toMutableList().also { it.add(index + 1, copy) }
            year++
        }
        return null
    }

    fun encode(name: String, plan: List<JSONObject>): JSONObject =
        JSONObject().put("version", 1).put("name", name.trim()).put("exams", JSONArray(plan))

    /** The marks a paper usually carries, or the previous value when the subject/paper is not known. */
    fun suggestedMarks(subject: String, paper: String): Int? = knownExamMarks(subject, paper)
}
