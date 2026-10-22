package com.folio.notes.planner

import com.folio.notes.progress.ExamReference
import com.folio.notes.progress.LoggedExam
import com.folio.notes.progress.analyseExam
import com.folio.notes.progress.comparisonName
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Port of Focal's `study-score.ts`, `scaling.ts` and `atar.ts`; the maths is the same so both apps give the same estimate. */
object StudyScore {
    data class Evidence(val exam: LoggedExam, val percentile: Double, val weight: Double, val referenceYear: Int, val exact: Boolean)
    data class Component(val label: String, val percentile: Double, val weightPercent: Double, val evidenceCount: Int, val projected: Boolean)
    data class Prediction(val studyScore: Int, val low: Int, val high: Int, val combinedPercentile: Double, val examPercentile: Double,
        val sacPercentile: Double, val examWeightPercent: Double, val confidence: String, val evidence: List<Evidence>,
        val components: List<Component>, val approximated: Int, val excluded: Int)

    private fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))

    /** Peter J. Acklam's inverse-normal approximation. */
    private fun inverseNormalCdf(probability: Double): Double {
        val p = clamp(probability, 0.0001, 0.9999)
        val a = doubleArrayOf(-39.6968302866538, 220.946098424521, -275.928510446969, 138.357751867269, -30.6647980661472, 2.50662827745924)
        val b = doubleArrayOf(-54.4760987982241, 161.585836858041, -155.698979859887, 66.8013118877197, -13.2806815528857)
        val c = doubleArrayOf(-0.00778489400243029, -0.322396458041136, -2.40075827716184, -2.54973253934373, 4.37466414146497, 2.93816398269878)
        val d = doubleArrayOf(0.00778469570904146, 0.32246712907004, 2.445134137143, 3.75440866190742)
        val lower = 0.02425
        fun tail(q: Double) = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
        if (p < lower) return tail(sqrt(-2 * ln(p)))
        if (p > 1 - lower) return -tail(sqrt(-2 * ln(1 - p)))
        val q = p - 0.5; val r = q * q
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
    }

    private fun toRaw(percentile: Double) = clamp(30 + 7 * inverseNormalCdf(clamp(percentile, 0.1, 99.9) / 100), 0.0, 50.0)

    private val twoPaper = Regex("mathematical methods|specialist mathematics", RegexOption.IGNORE_CASE)
    fun defaultExamWeight(subject: String) = if (twoPaper.containsMatchIn(subject)) 60.0 else 50.0

    private val paperRegex = Regex("(?:exam(?:ination)?|paper)\\s*([12])\\b", RegexOption.IGNORE_CASE)
    private fun paperNumber(paper: String) = paperRegex.find(paper)?.groupValues?.get(1)?.toInt()

    private val aliases = mapOf("english eal" to "english as an additional language", "english esl" to "english as an additional language",
        "english sl" to "english", "further mathematics" to "general mathematics", "mathematical methods cas" to "mathematical methods")
    fun studyKey(value: String): String {
        val original = comparisonName(value)
        val name = original.replace(Regex("\\bfl\\b"), "first language").replace(Regex("\\bsl advanced\\b"), "second language advanced")
            .replace(Regex("\\bsl\\b"), "second language").replace(Regex("\\s+"), " ").trim()
        return aliases[original] ?: name
    }
    private fun sameStudy(a: String, b: String) = studyKey(a) == studyKey(b)

    private fun compatible(exam: LoggedExam, references: List<ExamReference>, year: Int?): List<ExamReference> {
        val available = references.filter { sameStudy(it.subject, exam.subject) && (year == null || it.year == year) }
        val paper = comparisonName(exam.paper)
        available.filter { comparisonName(it.paper) == paper }.takeIf { it.isNotEmpty() }?.let { return it }
        paperNumber(exam.paper)?.let { n -> available.filter { paperNumber(it.paper) == n }.takeIf { it.isNotEmpty() }?.let { return it } }
        references.find { it.id == exam.json.optString("referenceId") }?.let { return listOf(it) }
        val perYear = available.groupingBy { it.year }.eachCount()
        return available.filter { perYear[it.year] == 1 }
    }

    private fun referenceFor(exam: LoggedExam, references: List<ExamReference>, year: Int?) =
        compatible(exam, references, year).let { c -> c.find { it.year == exam.examYear }
            ?: c.sortedWith(compareBy<ExamReference> { kotlin.math.abs(it.year - exam.examYear) }.thenByDescending { it.year }).firstOrNull() }

    private fun compare() = compareBy<LoggedExam>({ it.completedAt }, { it.json.optString("createdAt") }, { it.id })

    private data class Raw(val exam: LoggedExam, val percentile: Double, val year: Int, val exact: Boolean)
    private fun weigh(items: List<Raw>) = items.sortedWith(compareBy(compare()) { it.exam }).mapIndexed { i, r ->
        Evidence(r.exam, r.percentile, 0.82.pow(items.size - i - 1), r.year, r.exact) }

    private class Part(val label: String, val weight: Double, val matched: List<Evidence>, val summary: Summary)
    private data class Summary(val percentile: Double, val variance: Double, val uncertainty: Double)
    private fun summarise(evidence: List<Evidence>): Summary {
        val total = evidence.sumOf { it.weight }
        val percentile = evidence.sumOf { it.percentile * it.weight } / total
        val variance = evidence.sumOf { it.weight * (it.percentile - percentile).pow(2) } / total
        val effective = total.pow(2) / evidence.sumOf { it.weight.pow(2) }
        return Summary(percentile, variance, clamp((12 + sqrt(variance)) / sqrt(effective), 3.0, 18.0))
    }

    fun predict(subject: String, exams: List<LoggedExam>, references: List<ExamReference>, sacPercentile: Double? = null,
        examWeightPercent: Double = defaultExamWeight(subject), distributionYear: Int? = null): Prediction? {
        val mine = exams.filter { sameStudy(it.subject, subject) }
        val linked = mine.mapNotNull { e -> referenceFor(e, references, distributionYear)?.let { r ->
            analyseExam(e, r).percentile?.let { Raw(e, it, r.year, r.year == e.examYear) } } }
        if (linked.isEmpty()) return null
        val all = weigh(linked); val overall = summarise(all)
        var evidence = all; var examPercentile = overall.percentile
        var weight = clamp(examWeightPercent, 0.0, 100.0)
        val contribution: Double; val uncertainty: Double; val components: List<Component>
        if (twoPaper.containsMatchIn(subject)) {
            val parts = listOf(Triple(1, "Exam 1", 20.0), Triple(2, "Exam 2", 40.0)).map { (n, label, w) ->
                val matched = weigh(linked.filter { paperNumber(it.exam.paper) == n })
                Part(label, w, matched, if (matched.isEmpty()) overall else summarise(matched))
            }
            evidence = parts.flatMap { it.matched }
            if (evidence.size < linked.size) evidence = all
            components = parts.map { Component(it.label, it.summary.percentile, it.weight, it.matched.size, it.matched.isEmpty()) }
            weight = 60.0
            contribution = parts.sumOf { it.summary.percentile * it.weight / 100 }
            examPercentile = contribution / 0.6
            uncertainty = sqrt(parts.sumOf { (it.summary.uncertainty * it.weight / 100).pow(2) })
        } else {
            components = listOf(Component("Final examination", overall.percentile, weight, linked.size, false))
            contribution = overall.percentile * weight / 100
            uncertainty = overall.uncertainty * weight / 100
        }
        val sac = if (sacPercentile == null) examPercentile else clamp(sacPercentile, 0.1, 99.9)
        val combined = contribution + sac * (100 - weight) / 100
        val low = clamp(combined - uncertainty, 0.1, 99.9); val high = clamp(combined + uncertainty, 0.1, 99.9)
        val confidence = if (linked.size >= 5 && sqrt(overall.variance) <= 15 && components.none { it.projected }) "High" else if (linked.size >= 2) "Medium" else "Low"
        // JS Math.round ties toward +infinity.
        return Prediction(floor(toRaw(combined) + 0.5).toInt(), max(0, floor(toRaw(low)).toInt()), min(50, ceil(toRaw(high)).toInt()), combined,
            examPercentile, sac, weight, confidence, evidence, components, evidence.count { !it.exact }, mine.size - linked.size)
    }

    // ---- Scaling ----

    class Scaling(val code: String, val studyName: String, val year: Int, val sourceUrl: String, val points: List<Pair<Double, Double>>)
    class AtarTable(val year: Int, val points: List<Pair<Double, Double>>)
    class VtacData(val scaling: List<Scaling>, val atar: List<AtarTable>) {
        companion object {
            fun decode(raw: String): VtacData {
                val o = JSONObject(raw)
                fun pairs(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } }
                val refs = o.getJSONArray("references"); val atar = o.getJSONArray("atar")
                return VtacData((0 until refs.length()).map { refs.getJSONObject(it).let { r -> Scaling(r.getString("code"), r.getString("studyName"),
                    r.getInt("year"), r.getString("sourceUrl"), pairs(r.getJSONArray("points"))) } },
                    (0 until atar.length()).map { atar.getJSONObject(it).let { t -> AtarTable(t.getInt("year"), pairs(t.getJSONArray("points"))) } })
            }
        }
    }

    fun interpolate(raw: Double, points: List<Pair<Double, Double>>): Double? {
        if (!raw.isFinite() || raw < 20 || raw > 50 || points.size < 2) return null
        val ordered = points.sortedBy { it.first }
        ordered.find { it.first == raw }?.let { return it.second }
        val upper = ordered.indexOfFirst { it.first > raw }
        if (upper <= 0) return null
        val lo = ordered[upper - 1]; val hi = ordered[upper]
        return lo.second + (raw - lo.first) / (hi.first - lo.first) * (hi.second - lo.second)
    }

    data class Scaled(val scaled: Double, val min: Double, val max: Double, val years: List<Pair<Int, Double>>)
    fun scaled(raw: Double, subject: String, data: VtacData, year: Int? = null): Scaled? {
        val key = studyKey(subject)
        val estimates = data.scaling.filter { studyKey(it.studyName) == key && (year == null || it.year == year) }
            .mapNotNull { r -> interpolate(raw, r.points)?.let { r.year to it } }.sortedBy { it.first }
        if (estimates.isEmpty()) return null
        val values = estimates.map { it.second }
        return Scaled(values.average(), values.min(), values.max(), estimates)
    }

    // ---- ATAR ----

    data class Result(val id: String, val code: String, val studyName: String, val raw: Double, val scaled: Double)
    data class Contribution(val result: Result, val contribution: Double, val role: String)
    data class AtarEstimate(val aggregate: Double, val atar: Double?, val label: String, val primary: List<Contribution>,
        val increments: List<Contribution>, val excluded: List<Result>)

    private val english = setOf("EN", "EF", "EG", "LI")
    private val groups = mapOf(
        "mathematics" to setOf("MA10", "NF", "NJ", "NS"), "history" to setOf("HI17", "HA", "HR"),
        "information-technology" to setOf("AL03", "IT02", "IT03", "IN60", "ET16"), "music" to setOf("MD", "MC06", "MC05", "MC04", "MI19", "MI30"),
        "contemporary-society" to setOf("SO03"), "english" to english,
        "languages" to setOf("AI", "AR", "AM", "AU", "BE", "LO50", "LO53", "CN", "LO57", "CK", "CL", "AG", "LO51", "CR", "DU", "FP", "FR", "GN", "MG",
            "HB", "HI", "HU", "IN", "IX", "IL", "JA", "JS", "LO55", "KH", "KO", "KS", "LA", "MA", "PN", "PO", "PG", "LO49", "RO", "RU", "SE", "SI", "SP",
            "SW", "TA", "TU", "LO54", "LO31", "LO52"))
    private val vet = mapOf("BU23" to "vet-business-services", "CT41" to "vet-community-services", "MU07" to "vet-creative-arts", "DN17" to "vet-creative-arts",
        "EG47" to "vet-engineering", "EQ08" to "vet-animal-care", "FN40" to "vet-furnishing", "HL08" to "vet-health", "HL06" to "vet-health",
        "HS63" to "vet-hospitality", "HS65" to "vet-hospitality", "LB26" to "vet-laboratory-operations", "SR80" to "vet-sport-fitness-recreation")

    /** Order matters like Focal's: English, mathematics, history, IT, music, contemporary society, then languages. */
    private fun areaGroup(code: String): String? {
        for (g in listOf("english", "mathematics", "history", "information-technology", "music", "contemporary-society", "languages"))
            if (code in groups.getValue(g)) return g
        return vet[code]
    }

    private fun equivalenceKey(r: Result): String {
        if (r.code == "EN" || r.code == "EF") return "english-or-eal"
        Regex("^(Chinese|Indonesian|Japanese|Korean|Vietnamese)\\b", RegexOption.IGNORE_CASE).find(r.studyName)?.let { return "language:${it.groupValues[1].lowercase()}" }
        return "study:${r.code}"
    }

    private fun <T> combinations(values: List<T>, size: Int): List<List<T>> {
        if (size == 0) return listOf(emptyList())
        if (values.size < size) return emptyList()
        return (0..values.size - size).flatMap { i -> combinations(values.drop(i + 1), size - 1).map { listOf(values[i]) + it } }
    }

    private fun countGroups(results: List<Result>) = results.mapNotNull { areaGroup(it.code) }.groupingBy { it }.eachCount()

    private fun toAtar(aggregate: Double, table: AtarTable): Pair<Double?, String> {
        val points = table.points.sortedBy { it.second }
        val lowest = points.firstOrNull() ?: return null to "—"; val highest = points.last()
        if (!aggregate.isFinite()) return null to "—"
        if (aggregate < lowest.second) return null to "<" + String.format(Locale.US, "%.2f", lowest.first)
        if (aggregate >= highest.second) return highest.first to String.format(Locale.US, "%.2f", highest.first)
        val upper = points.indexOfFirst { it.second > aggregate }
        val lo = points[upper - 1]; val hi = points[upper]
        val atar = floor((lo.first + (aggregate - lo.second) / (hi.second - lo.second) * (hi.first - lo.first)) * 20 + 0.5) / 20
        return atar to String.format(Locale.US, "%.2f", atar)
    }

    fun estimateAtar(results: List<Result>, year: Int, data: VtacData): AtarEstimate? {
        val table = data.atar.find { it.year == year } ?: return null
        val best = HashMap<String, Result>()
        for (r in results.filter { it.raw.isFinite() && it.scaled.isFinite() && it.raw in 0.0..50.0 && it.scaled >= 0 }) {
            val k = equivalenceKey(r); if (best[k] == null || r.scaled > best.getValue(k).scaled) best[k] = r
        }
        val valid = best.values.toList()
        if (valid.size < 4) return null
        val eng = valid.filter { it.code in english }.maxByOrNull { it.scaled } ?: return null
        var top: Triple<List<Result>, List<Result>, Double>? = null
        for (extra in combinations(valid.filter { it.id != eng.id }, 3)) {
            val primary = listOf(eng) + extra
            if (countGroups(primary).values.any { it > 2 }) continue
            val counts = countGroups(primary).toMutableMap(); val increments = mutableListOf<Result>()
            for (r in valid.filter { p -> primary.none { it.id == p.id } }.sortedByDescending { it.scaled }) {
                val g = areaGroup(r.code)
                if (g != null && (counts[g] ?: 0) >= 3) continue
                increments += r; if (g != null) counts[g] = (counts[g] ?: 0) + 1
                if (increments.size == 2) break
            }
            val aggregate = primary.sumOf { it.scaled } + increments.sumOf { it.scaled * 0.1 }
            if (top == null || aggregate > top.third) top = Triple(primary, increments, aggregate)
        }
        val (primary, increments, aggregate) = top ?: return null
        val primaryFour = primary.map { Contribution(it, it.scaled, if (it.id == eng.id) "English" else "Primary") }
            .sortedWith(compareBy<Contribution> { it.role != "English" }.thenByDescending { it.contribution })
        val picked = (primary + increments).map { it.id }.toSet()
        val (atar, label) = toAtar(aggregate, table)
        return AtarEstimate((aggregate * 100).roundToInt() / 100.0, atar, label, primaryFour,
            increments.map { Contribution(it, it.scaled * 0.1, "Increment") }, valid.filter { it.id !in picked })
    }
}
