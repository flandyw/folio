@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioPanel
import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.MistakeScheduler
import com.folio.notes.mistakes.ReviewRating
import com.folio.notes.mistakes.isoTime
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

@Composable internal fun OverviewMetrics(exams: List<LoggedExam>, mistakes: List<ExamTrackMistake>, settings: JSONObject?) {
    ProgressCard("At a glance") {
        val due = MistakeScheduler.getDueMistakes(mistakes).size
        val mature = mistakes.count { MistakeScheduler.getMistakeSchedule(it).resolved }
        val metrics = listOf("Practice exams" to exams.size.toString(), "Aligned average" to if (exams.isEmpty()) "—" else "${performanceAverage(exams, settings).display()}%",
            "Best mark" to (exams.maxOfOrNull { it.percentage }?.let { "${it.display()}%" } ?: "—"), "Mistakes due" to "$due / ${mistakes.size}")
        BoxWithConstraints {
            val columns = if (maxWidth >= 600.dp) 4 else 2
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                metrics.chunked(columns).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(value, style = MaterialTheme.typography.headlineSmall)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                } }
            }
        }
        Text("${exams.map { it.subject }.distinct().size} subjects · $mature mature mistake cards", style = MaterialTheme.typography.bodySmall)
        Text("Aligned averages use your provider difficulty settings and are planning estimates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ActivityCard(exams: List<LoggedExam>) {
    val today = LocalDate.now(); val start = today.minusWeeks(11).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    MetricBars("Exam activity", (0..11).map { i -> val from = start.plusWeeks(i.toLong()); val until = from.plusWeeks(1)
        "${from.dayOfMonth} ${from.month.name.take(3).lowercase()}" to exams.count { e -> e.date?.let { !it.isBefore(from) && it.isBefore(until) } == true }.toDouble()
    }, suffix = " papers", subtitle = "Practice completed each week over the past 12 weeks")
}

@Composable internal fun CoverageCard(exams: List<LoggedExam>) {
    ProgressCard("Coverage", "Distinct practice papers, providers and years") {
        if (exams.isEmpty()) Text("Log papers to build your coverage.")
        exams.groupBy { it.subject }.forEach { (subject, attempts) ->
            Text(subject, style = MaterialTheme.typography.titleSmall)
            Text("${attempts.distinctBy { it.paperKey() }.size} papers · ${attempts.map { it.provider }.distinct().size} providers · ${attempts.map { it.examYear }.distinct().size} years · ${attempts.size - attempts.distinctBy { it.paperKey() }.size} repeat attempts", style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun LazyGridScope.insightsItems(exams: List<LoggedExam>, allMistakes: List<ExamTrackMistake>, references: List<ExamReference>,
    settings: JSONObject?, onDifficulty: () -> Unit, onMistakes: () -> Unit) {
    val examIds = exams.map { it.id }.toSet(); val mistakes = allMistakes.filter { it.attemptId in examIds }
    val outlooks = subjectOutlooks(exams, settings)
    val focus = focusPriorities(exams, mistakes)
    val questions = markedQuestions(exams)
    item(span = { GridItemSpan(maxLineSpan) }) { ProgressCard("Performance signals", "Evidence from practice results and mistake review") {
        val primary = outlooks.maxByOrNull { it.attempts }
        val reviews = mistakes.flatMap { it.reviewHistory }.filter { runCatching { Instant.parse(it.completedAt).isAfter(Instant.now().minusSeconds(30L * 86400)) }.getOrDefault(false) }
        val recall = if (reviews.isEmpty()) null else reviews.count { it.result == ReviewRating.GOOD || it.result == ReviewRating.EASY } * 100.0 / reviews.size
        Text("Momentum ${primary?.momentum?.let { "${if (it >= 0) "+" else ""}${it.display()} pts" } ?: "—"} · Consistency ${primary?.spread?.let { "±${it.display()} pts" } ?: "—"}")
        Text("30-day review recall ${recall?.let { "${it.display(0)}% from ${reviews.size} reviews" } ?: "—"}")
        focus.firstOrNull()?.let { Text("Highest priority: ${it.topic} · ${it.subject} · ${it.priority.display(0)}/100") }
        TextButton(onDifficulty) { Text("Provider difficulty settings") }
    } }
    if (exams.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { ProgressCard("Start building evidence") { Text("Log completed papers, mark questions and label topics to unlock performance insights.") } }
    exams.groupBy { it.subject }.forEach { (subject, attempts) ->
        val ordered = attempts.sortedBy { it.completedAt }
        item { ScoreTrend("$subject · score trend", ordered.map { "${it.completedAt.take(10)} · ${it.paper}" to it.percentage },
            "Tap a point to inspect a result. Provider alignment is an estimate.", ordered.map { performance(it, settings).aligned }) }
        val linked = ordered.mapNotNull { exam -> referenceFor(exam, references)?.let { ref ->
            analyseExam(exam, ref).percentile?.let { percentile -> exam.completedAt.take(10) to percentile } } }
        item { ScoreTrend("$subject · VCAA percentile", linked, "Estimated against each attempt's exam-year official distribution.") }
        item { MetricBars("$subject · paper performance", attempts.groupBy { it.paper }.map { (paper, group) -> paper to group.map { it.percentage }.average() }, maximum = 100.0,
            subtitle = "Raw averages by paper; compare Exam 1 and Exam 2 independently.") }
        val benchmarks = attempts.mapNotNull { exam -> referenceFor(exam, references)?.let { ref -> exam.percentage - ref.stats.mean / ref.maxScore * 100 } }
        if (benchmarks.isNotEmpty()) item { ProgressCard("$subject · cohort benchmark") {
            val difference = benchmarks.average()
            Text("${if (difference >= 0) "+" else ""}${difference.display()} points vs cohort mean", style = MaterialTheme.typography.titleLarge)
            Text("${benchmarks.size} results matched to official VCAA distributions. Estimates use grade-band midpoints.", style = MaterialTheme.typography.bodySmall)
        } }
    }
    item { ProgressCard("Improvement outlook", "Focal's recency-weighted trend model. Ranges are planning estimates.") {
        if (outlooks.isEmpty()) Text("No results yet.")
        outlooks.forEach { value ->
            Text(value.subject, style = MaterialTheme.typography.titleSmall)
            Text("Next ${value.next.display()}% · ${value.low.display()}–${value.high.display()}% range")
            Text("Recent ${value.current.display()}% · momentum ${value.momentum.display()} pts · spread ±${value.spread.display()} · ${value.confidence} evidence (${value.attempts} attempts)", style = MaterialTheme.typography.bodySmall)
        }
    } }
    item { MetricBars("Focus priorities", focus.take(12).map { "${it.subject} · ${it.topic}" to it.priority }, suffix = "/100", maximum = 100.0,
        subtitle = "60% mark risk, 25% confidence risk, 15% unresolved mistakes and lapses.") }
    item { ProgressCard("Topic mastery", "Marked questions and mistakes by area of study") {
        if (focus.isEmpty()) Text("Add areas of study to your question marks or mistakes.")
        focus.take(16).forEach { value ->
            Text("${value.subject} · ${value.topic}", style = MaterialTheme.typography.titleSmall)
            Text("${value.mastery?.let { "${it.display()}% mastery" } ?: "No marked questions"} · ${value.lost.display()} marks lost · ${value.questions} questions · ${value.unresolved} unresolved · ${value.lapses} lapses", style = MaterialTheme.typography.bodySmall)
        }
    } }
    val paperTopics = questions.filter { it.topic.isNotBlank() }.groupBy { Triple(it.exam.subject, it.topic, it.exam.paper) }
    item { MetricBars("Paper and topic weaknesses", paperTopics.entries.sortedBy { (_, q) -> q.sumOf { it.earned } / q.sumOf { it.maximum } }
        .take(16).map { (key, q) -> "${key.first} · ${key.third} · ${key.second}" to q.sumOf { it.earned } / q.sumOf { it.maximum } * 100 },
        maximum = 100.0, subtitle = "Mastery per topic within each paper. Lowest mastery first.") }
    item { MetricBars("Lost marks by topic", questions.filter { it.topic.isNotBlank() }.groupBy { "${it.exam.subject} · ${it.topic}" }
        .map { (topic, q) -> topic to q.sumOf { it.lost } }.sortedByDescending { it.second }.take(12), suffix = " marks",
        subtitle = "Only marked questions are counted, so linked mistake cards never double-count the same lost mark.") }
    item { MetricBars("Confidence calibration", listOf("low", "medium", "high").mapNotNull { confidence -> val q = questions.filter { it.confidence == confidence }
        if (q.isEmpty()) null else "$confidence confidence (${q.size} questions)" to q.sumOf { it.earned } / q.sumOf { it.maximum } * 100 }, maximum = 100.0,
        subtitle = "Compare confidence at marking with marks earned. High-confidence errors reveal blind spots.") }
    val context = contextInsights(exams)
    item { ProgressCard("Performance context", "Sleep, energy, focus, stress, confidence and preparation. Associations do not establish causation.") {
        if (context.isEmpty()) Text("Record context on at least three exams, with varied ratings, to measure associations.")
        context.forEach { factor -> Text("${factor.label}: ${factor.change.display()} pts with ${factor.condition} · r ${factor.correlation.display(2)} · ${factor.count} results", style = MaterialTheme.typography.bodySmall) }
    } }
    val timing = exams.mapNotNull { e -> e.json.optJSONObject("timing")?.let { e to it } }
    item { ProgressCard("Timing and pacing") {
        if (timing.isEmpty()) Text("Record writing time or use a timed notebook sitting.")
        timing.take(10).forEach { (exam, t) ->
            Text("${exam.subject} · ${exam.paper} · ${exam.completedAt.take(10)}", style = MaterialTheme.typography.titleSmall)
            Text("Writing ${(t.optDouble("actualWritingSeconds") / 60).display()} min · overtime ${(t.optDouble("overtimeSeconds") / 60).display()} min · ${exam.percentage.display()}%", style = MaterialTheme.typography.bodySmall)
        }
    } }
    val today = LocalDate.now()
    val forecast = IntArray(14)
    mistakes.filterNot { it.suspended }.forEach { mistake ->
        val due = examDate(MistakeScheduler.getMistakeSchedule(mistake).dueAt)
        if (due != null) { val index = ChronoUnit.DAYS.between(today, due).coerceAtLeast(0).toInt(); if (index < forecast.size) forecast[index]++ }
    }
    item { MetricBars("Review forecast", forecast.mapIndexed { i, count -> (if (i == 0) "Today (includes overdue)" else today.plusDays(i.toLong()).toString()) to count.toDouble() }, suffix = " cards",
        subtitle = "Next 14 calendar days; suspended cards are excluded.") }
    item { MetricBars("Revision priorities", mistakes.filterNot { it.suspended || MistakeScheduler.getMistakeSchedule(it).resolved }
        .groupBy { it.areaOfStudy?.takeIf(String::isNotBlank) ?: it.category }.map { (topic, group) -> topic to group.size.toDouble() }.sortedByDescending { it.second }, suffix = " cards",
        subtitle = "Unresolved mistakes grouped by topic or category.") }
    item { ProgressCard("Mistake review") { Text("${mistakes.size} linked mistake cards · ${MistakeScheduler.getDueMistakes(mistakes).size} due now")
        TextButton(onMistakes) { Text("Open mistake review") }
    } }
}

@Composable internal fun ExamDetailDialog(exam: LoggedExam, catalog: ExamCatalog, mistakes: List<ExamTrackMistake>, onDismiss: () -> Unit,
    onEdit: () -> Unit, onAddMistake: () -> Unit, onMistakes: () -> Unit, onOpenNotebook: () -> Unit, hasNotebook: Boolean) {
    val references = remember(exam, catalog) { catalog.references.map { it.year }.distinct().sortedDescending().mapNotNull { referenceFor(exam, catalog.references, it) } }
    var year by rememberSaveable(exam.id) { mutableIntStateOf(references.firstOrNull { it.year == exam.examYear }?.year ?: references.firstOrNull()?.year ?: exam.examYear) }
    val reference = references.find { it.year == year }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    FolioPanel("Exam result", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(exam.title, style = MaterialTheme.typography.titleLarge)
            Text("${exam.paper} · ${exam.completedAt.take(10)} · ${exam.rawScore.display()}/${exam.rawMax.display()} (${exam.percentage.display()}%)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onEdit) { Text("Edit") }; Button(onAddMistake) { Text("Add mistake") }
                if (hasNotebook) IconButton(onOpenNotebook) { Icon(Icons.AutoMirrored.Rounded.MenuBook, "Open linked notebook") }
            }
            if (exam.json.optString("comment").isNotBlank()) Text(exam.json.optString("comment"))
            if (references.isEmpty()) ProgressCard("No official distribution", "No matching VCAA subject/paper distribution is bundled for this result.") { Text("Your raw mark remains ${exam.percentage.display()}%.") }
            else {
                ChoiceField("Comparison year", year.toString(), references.map { it.year.toString() }, { year = it.toInt() })
                if (reference != null) {
                    DistributionChart(exam, reference)
                    TextButton({ try { uri.openUri(reference.sourceUrl) } catch (_: Exception) { linkError = true } }) { Text("Open official VCAA source") }
                    if (linkError) Text("Could not open the VCAA source.", color = MaterialTheme.colorScheme.error)
                }
                ProgressCard("Compare years", "The same raw percentage scaled to each year's official mark range.") {
                    references.forEach { ref -> val a = analyseExam(exam, ref)
                        Text("${ref.year} · Est. ${a.grade ?: "—"} · ${a.percentile?.display() ?: "—"} percentile · cohort mean ${(ref.stats.mean / ref.maxScore * 100).display()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val questions = remember(exam) { markedQuestions(listOf(exam)) }
            if (questions.isNotEmpty()) ProgressCard("Question results") { questions.forEach { q ->
                Text("${q.label} · ${q.earned.display()}/${q.maximum.display()} · ${q.confidence} confidence", style = MaterialTheme.typography.titleSmall)
                if (q.topic.isNotBlank() || q.criterion.isNotBlank()) Text(listOf(q.topic, q.criterion).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (q.note.isNotBlank()) Text(q.note, style = MaterialTheme.typography.bodySmall)
            } }
            exam.json.optJSONObject("performanceContext")?.let { c -> ProgressCard("Performance context") {
                c.keys().forEach { key -> Text("$key: ${c.opt(key)}", style = MaterialTheme.typography.bodySmall) }
            } }
            exam.json.optJSONObject("timing")?.let { t -> ProgressCard("Timing") {
                Text("Writing ${(t.optDouble("actualWritingSeconds") / 60).display()} min · overtime ${(t.optDouble("overtimeSeconds") / 60).display()} min · paused ${(t.optDouble("pausedSeconds") / 60).display()} min")
            } }
            ProgressCard("Mistakes (${mistakes.size})") {
                mistakes.forEach { m -> Text("${m.question} · ${m.category}", style = MaterialTheme.typography.titleSmall); Text(m.explanation, style = MaterialTheme.typography.bodySmall) }
                TextButton(onMistakes) { Text("Open mistake review") }
            }
        }
    }
}

@Composable internal fun DifficultyDialog(initial: JSONObject?, manager: ExamProgressManager, user: String?, onDismiss: () -> Unit) {
    val owner = remember { user }
    var enabled by rememberSaveable { mutableStateOf(initial?.optBoolean("enabled", true) ?: true) }
    var strength by rememberSaveable { mutableStateOf(initial?.optString("strength", "balanced") ?: "balanced") }
    var providers by rememberSaveable { mutableStateOf(initial?.optJSONArray("providerOrder")?.strings() ?: defaultProviderOrder) }
    var newProvider by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FolioPanel("Provider difficulty", { if (!busy) onDismiss() }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Harder providers go above VCAA, easier below. This is a conservative planning adjustment, capped at 8 percentage points.")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, { enabled = it }); Text("Apply alignment", Modifier.padding(start = 12.dp)) }
            ChoiceField("Strength", strength, listOf("light", "balanced", "strong"), { strength = it })
            Text("Light: 1 point/rank · Balanced: 1.5 · Strong: 2", style = MaterialTheme.typography.bodySmall)
            providers.forEachIndexed { index, provider -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider, Modifier.weight(1f))
                IconButton({ providers = providers.toMutableList().apply { java.util.Collections.swap(this, index, index - 1) } }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "Move $provider harder") }
                IconButton({ providers = providers.toMutableList().apply { java.util.Collections.swap(this, index, index + 1) } }, enabled = index < providers.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move $provider easier") }
                IconButton({ providers = providers.filterIndexed { i, _ -> i != index } }, enabled = comparisonName(provider) != "vcaa") { Icon(Icons.Rounded.DeleteOutline, "Remove $provider") }
            } }
            OutlinedTextField(newProvider, { newProvider = it }, Modifier.fillMaxWidth(), label = { Text("Add provider") })
            TextButton({ val p = newProvider.trim(); if (p.isNotEmpty() && providers.none { comparisonName(it) == comparisonName(p) }) { providers = providers + p; newProvider = "" } }) { Text("Add provider") }
            TextButton({ providers = defaultProviderOrder; enabled = true; strength = "balanced" }) { Text("Reset defaults") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button({ busy = true; scope.launch {
            val settings = JSONObject(initial?.toString() ?: "{}").put("enabled", enabled).put("strength", strength)
                .put("providerOrder", JSONArray(providers)).put("updatedAt", isoTime())
            if (manager.saveValue("examDifficulty", settings, owner)) onDismiss() else error = manager.state.value.error
            busy = false
        } }, enabled = !busy, modifier = Modifier.align(Alignment.End).padding(16.dp)) { Text(if (busy) "Saving…" else "Save") }
    }
}

/** Standalone HTML opens in a browser and can be printed or saved as a PDF by the recipient. */
internal fun progressReport(exams: List<LoggedExam>, references: List<ExamReference>, settings: JSONObject?): String {
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val rows = exams.sortedByDescending { it.completedAt }.joinToString("") { exam ->
        val analysis = referenceFor(exam, references)?.let { analyseExam(exam, it) }
        "<tr><td>${escape(exam.subject)}</td><td>${escape(exam.provider)} ${exam.examYear} · ${escape(exam.paper)}</td><td>${escape(exam.completedAt.take(10))}</td><td>${exam.rawScore.display()}/${exam.rawMax.display()} (${exam.percentage.display()}%)</td><td>${escape(analysis?.grade ?: "—")}</td><td>${analysis?.percentile?.display() ?: "—"}</td></tr>"
    }
    val topics = focusPriorities(exams, emptyList()).joinToString("") { "<tr><td>${escape(it.subject)}</td><td>${escape(it.topic)}</td><td>${it.mastery?.display() ?: "—"}%</td><td>${it.lost.display()}</td></tr>" }
    return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Folio progress report</title>
        <style>body{font:16px system-ui,sans-serif;max-width:1100px;margin:40px auto;padding:20px;color:#17212b}table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:10px;border-bottom:1px solid #ddd;text-align:left}h1{margin-bottom:8px}p{line-height:1.5}tr{break-inside:avoid}@media print{body{margin:0;padding:0}table{font-size:11px}}</style>
        <h1>Folio progress report</h1><p>Generated ${LocalDate.now()} · ${exams.size} exams · ${exams.map { it.subject }.distinct().size} subjects<br>VCAA-aligned average ${performanceAverage(exams, settings).display()}%. Difficulty alignment and grade-band percentile comparisons are planning estimates.</p>
        <h2>Exam results</h2><table><thead><tr><th>Subject</th><th>Paper</th><th>Completed</th><th>Mark</th><th>Est. grade</th><th>Est. percentile</th></tr></thead><tbody>$rows</tbody></table>
        <h2>Topic mastery</h2><table><thead><tr><th>Subject</th><th>Topic</th><th>Mastery</th><th>Marks lost</th></tr></thead><tbody>$topics</tbody></table></html>""".trimIndent()
}
