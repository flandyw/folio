@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioPanel
import com.folio.notes.FolioSpacing
import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.MistakeScheduler
import com.folio.notes.mistakes.ReviewRating
import com.folio.notes.mistakes.isoTime
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal fun LazyGridScope.insightsItems(exams: List<LoggedExam>, allMistakes: List<ExamTrackMistake>, references: List<ExamReference>,
    settings: JSONObject?, onMistakes: () -> Unit) {
    if (exams.isEmpty()) {
        wide("insights-empty") { EmptyState(Icons.Rounded.Insights, "Start building evidence",
            "Log completed papers, mark questions and label topics to unlock trends, outlooks and focus areas.") }
        return
    }
    val examIds = exams.map { it.id }.toSet(); val mistakes = allMistakes.filter { it.attemptId in examIds }
    val outlooks = subjectOutlooks(exams, settings)
    val focus = focusPriorities(exams, mistakes)
    val questions = markedQuestions(exams)
    wide("signals") {
        val primary = outlooks.maxByOrNull { it.attempts }
        val reviews = mistakes.flatMap { it.reviewHistory }.filter { runCatching { Instant.parse(it.completedAt).isAfter(Instant.now().minusSeconds(30L * 86400)) }.getOrDefault(false) }
        val recall = if (reviews.isEmpty()) null else reviews.count { it.result == ReviewRating.GOOD || it.result == ReviewRating.EASY } * 100.0 / reviews.size
        StatTiles(listOf(
            StatTileData(trendIcon(primary?.momentum), primary?.momentum?.let { "${signed(it)} pts" } ?: "—", "Momentum${primary?.let { " · ${it.subject}" } ?: ""}"),
            StatTileData(Icons.Rounded.Straighten, primary?.spread?.let { "±${it.display()}" } ?: "—", "Consistency (pts spread)"),
            StatTileData(Icons.Rounded.Psychology, recall?.let { "${it.display(0)}%" } ?: "—", "30-day recall${if (reviews.isNotEmpty()) " · ${reviews.size} reviews" else ""}"),
            StatTileData(Icons.Rounded.PriorityHigh, focus.firstOrNull()?.priority?.display(0) ?: "—", focus.firstOrNull()?.let { "Top priority · ${it.topic}" } ?: "Top priority"),
        ))
    }

    wide("h-outlook") { SectionHeader("Outlook", "Focal's recency-weighted trend model. Ranges are planning estimates.") }
    item("outlook") { ProgressCard("Next result", icon = Icons.Rounded.Insights) {
        outlooks.forEach { OutlookRange(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            ChartKey(MaterialTheme.colorScheme.primary.copy(alpha = .35f), "Likely range"); ChartKey(MaterialTheme.colorScheme.tertiary, "Recent form")
        }
    } }
    val benchmarks = exams.groupBy { it.subject }.mapValues { (_, attempts) ->
        attempts.mapNotNull { exam -> referenceFor(exam, references)?.let { ref -> exam.percentage - ref.stats.mean / ref.maxScore * 100 } } }.filterValues { it.isNotEmpty() }
    if (benchmarks.isNotEmpty()) item("benchmark") { ProgressCard("Against the cohort", "Matched to official VCAA distributions. Estimates use grade-band midpoints.", Icons.Rounded.Groups) {
        benchmarks.forEach { (subject, values) ->
            val difference = values.average()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(subject, style = MaterialTheme.typography.titleSmall)
                    Text("${values.size} ${if (values.size == 1) "result" else "results"} matched", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill("${signed(difference)} pts", trendIcon(difference), strong = difference >= 0)
            }
        }
    } }

    wide("h-subjects") { SectionHeader("By subject", "Tap a chart to inspect a result") }
    exams.groupBy { it.subject }.forEach { (subject, attempts) ->
        val ordered = attempts.sortedBy { it.completedAt }
        item("trend:$subject") { ScoreTrend(subject, ordered.map { "${it.completedAt.take(10)} · ${it.paper}" to it.percentage },
            "${ordered.size} ${if (ordered.size == 1) "result" else "results"} · provider alignment is an estimate", ordered.map { performance(it, settings).aligned },
            Icons.AutoMirrored.Rounded.ShowChart) }
        val linked = ordered.mapNotNull { exam -> referenceFor(exam, references)?.let { ref ->
            analyseExam(exam, ref).percentile?.let { percentile -> exam.completedAt.take(10) to percentile } } }
        if (linked.isNotEmpty()) item("percentile:$subject") { ScoreTrend("$subject · VCAA percentile", linked,
            "Estimated against each attempt's exam-year official distribution.", icon = Icons.Rounded.Leaderboard) }
        val papers = attempts.groupBy { it.paper }
        if (papers.size > 1) item("papers:$subject") { MetricBars("$subject · by paper", papers.map { (paper, group) -> paper to group.map { it.percentage }.average() },
            maximum = 100.0, subtitle = "Raw averages; compare papers independently.", icon = Icons.Rounded.Description) }
    }

    wide("h-topics") { SectionHeader("Topics", "Where marks are being won and lost") }
    item("focus") { MetricBars("Focus priorities", focus.map { "${it.subject} · ${it.topic}" to it.priority }, suffix = "/100", maximum = 100.0,
        subtitle = "60% mark risk, 25% confidence risk, 15% unresolved mistakes and lapses.", icon = Icons.Rounded.PriorityHigh) }
    item("mastery") {
        var all by remember { mutableStateOf(false) }
        ProgressCard("Topic mastery", "Marked questions and mistakes by area of study", Icons.Rounded.School) {
            if (focus.isEmpty()) Text("Add areas of study to your question marks or mistakes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            (if (all) focus else focus.take(6)).forEach { value ->
                MeterRow("${value.subject} · ${value.topic}", value.mastery?.let { "${it.display(0)}%" } ?: "—", ((value.mastery ?: 0.0) / 100).toFloat(),
                    color = MaterialTheme.colorScheme.tertiary,
                    supporting = "${value.lost.display()} marks lost · ${value.questions} questions · ${value.unresolved} unresolved · ${value.lapses} lapses")
            }
            if (focus.size > 6) TextButton({ all = !all }) { Text(if (all) "Show fewer" else "Show all ${focus.size}") }
        }
    }
    val paperTopics = questions.filter { it.topic.isNotBlank() }.groupBy { Triple(it.exam.subject, it.topic, it.exam.paper) }
    item("weak") { MetricBars("Weakest by paper", paperTopics.entries.sortedBy { (_, q) -> q.sumOf { it.earned } / q.sumOf { it.maximum } }
        .map { (key, q) -> "${key.first} · ${key.third} · ${key.second}" to q.sumOf { it.earned } / q.sumOf { it.maximum } * 100 },
        maximum = 100.0, subtitle = "Mastery per topic within each paper, lowest first.", icon = Icons.AutoMirrored.Rounded.TrendingDown) }
    item("lost") { MetricBars("Lost marks", questions.filter { it.topic.isNotBlank() }.groupBy { "${it.exam.subject} · ${it.topic}" }
        .map { (topic, q) -> topic to q.sumOf { it.lost } }.sortedByDescending { it.second }, suffix = " marks",
        subtitle = "Marked questions only, so linked mistake cards never double-count a mark.", icon = Icons.Rounded.RemoveCircleOutline) }
    item("calibration") { MetricBars("Confidence calibration", listOf("low", "medium", "high").mapNotNull { confidence -> val q = questions.filter { it.confidence == confidence }
        if (q.isEmpty()) null else "${confidence.replaceFirstChar(Char::uppercase)} confidence · ${q.size} questions" to q.sumOf { it.earned } / q.sumOf { it.maximum } * 100 }, maximum = 100.0,
        subtitle = "Marks earned at each confidence. High-confidence errors reveal blind spots.", icon = Icons.Rounded.Tune) }

    wide("h-habits") { SectionHeader("Habits", "How you sit papers") }
    val context = contextInsights(exams)
    item("context") { ProgressCard("Performance context", "Associations, not causes.", Icons.Rounded.Bedtime) {
        if (context.isEmpty()) Text("Record sleep, energy, focus, stress, confidence or preparation on at least three exams, with varied ratings.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        context.forEach { factor ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Column(Modifier.weight(1f)) {
                    Text(factor.label, style = MaterialTheme.typography.titleSmall)
                    Text("With ${factor.condition} · r ${factor.correlation.display(2)} · ${factor.count} results", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill("${signed(factor.change)} pts", trendIcon(factor.change), strong = factor.change > 0)
            }
        }
    } }
    val timing = exams.mapNotNull { e -> e.json.optJSONObject("timing")?.let { e to it } }
    item("timing") { ProgressCard("Timing and pacing", icon = Icons.Rounded.Timer) {
        if (timing.isEmpty()) Text("Record writing time or use a timed notebook sitting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        timing.take(8).forEach { (exam, t) ->
            val overtime = t.optDouble("overtimeSeconds") / 60
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Column(Modifier.weight(1f)) {
                    Text("${exam.subject} · ${exam.paper}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("${friendlyDate(exam.completedAt)} · ${(t.optDouble("actualWritingSeconds") / 60).display(0)} min writing · ${exam.percentage.display()}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (overtime > 0) StatusPill("+${overtime.display(0)} min", Icons.Rounded.MoreTime)
            }
        }
    } }

    wide("h-review") { SectionHeader("Review", "Mistake cards linked to these results") }
    val today = LocalDate.now()
    val forecast = IntArray(14)
    mistakes.filterNot { it.suspended }.forEach { mistake ->
        val due = examDate(MistakeScheduler.getMistakeSchedule(mistake).dueAt)
        if (due != null) { val index = ChronoUnit.DAYS.between(today, due).coerceAtLeast(0).toInt(); if (index < forecast.size) forecast[index]++ }
    }
    item("forecast") { ProgressCard("Review forecast", "Next 14 days; today includes overdue cards. Suspended cards are excluded.", Icons.Rounded.CalendarMonth,
        action = { TextButton(onMistakes) { Text("Review") } }) {
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp24)) {
            listOf("${forecast[0]}" to "due today", "${forecast.take(7).sum()}" to "this week", "${mistakes.size}" to "linked cards").forEach { (value, label) ->
                Column { Text(value, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        ColumnChart(forecast.mapIndexed { i, count -> (if (i == 0) "Today" else "${today.plusDays(i.toLong()).dayOfMonth}") to count.toDouble() },
            "Review forecast: ${forecast.mapIndexed { i, c -> "${today.plusDays(i.toLong())}: $c" }.joinToString()}", labelEvery = 2)
    } }
    item("revision") { MetricBars("Revision priorities", mistakes.filterNot { it.suspended || MistakeScheduler.getMistakeSchedule(it).resolved }
        .groupBy { it.areaOfStudy?.takeIf(String::isNotBlank) ?: it.category }.map { (topic, group) -> topic to group.size.toDouble() }.sortedByDescending { it.second }, suffix = " cards",
        subtitle = "Unresolved mistakes grouped by topic or category.", icon = Icons.Rounded.Replay) }
}

@Composable internal fun ExamDetailDialog(exam: LoggedExam, catalog: ExamCatalog, mistakes: List<ExamTrackMistake>, onDismiss: () -> Unit,
    onEdit: () -> Unit, onAddMistake: () -> Unit, onMistakes: () -> Unit, onOpenNotebook: () -> Unit, hasNotebook: Boolean) {
    val references = remember(exam, catalog) { catalog.references.map { it.year }.distinct().sortedDescending().mapNotNull { referenceFor(exam, catalog.references, it) } }
    var year by rememberSaveable(exam.id) { mutableIntStateOf(references.firstOrNull { it.year == exam.examYear }?.year ?: references.firstOrNull()?.year ?: exam.examYear) }
    val reference = references.find { it.year == year }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    FolioPanel("Exam result", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Text(exam.title, style = MaterialTheme.typography.titleLarge)
            Text("${exam.paper} · ${exam.completedAt.take(10)} · ${exam.rawScore.display()}/${exam.rawMax.display()} (${exam.percentage.display()}%)")
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
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
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Text("Harder providers go above VCAA, easier below. This is a conservative planning adjustment, capped at 8 percentage points.")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, { enabled = it }); Text("Apply alignment", Modifier.padding(start = FolioSpacing.dp12)) }
            ChoiceField("Strength", strength, listOf("light", "balanced", "strong"), { strength = it })
            Text("Light: 1 point/rank · Balanced: 1.5 · Strong: 2", style = MaterialTheme.typography.bodySmall)
            providers.forEachIndexed { index, provider -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider, Modifier.weight(1f))
                IconButton({ providers = providers.toMutableList().apply { java.util.Collections.swap(this, index, index - 1) } }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "Move $provider harder") }
                IconButton({ providers = providers.toMutableList().apply { java.util.Collections.swap(this, index, index + 1) } }, enabled = index < providers.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move $provider easier") }
                IconButton({ providers = providers.filterIndexed { i, _ -> i != index } }, enabled = comparisonName(provider) != "vcaa") { Icon(Icons.Rounded.DeleteOutline, "Remove $provider") }
            } }
            OutlinedTextField(newProvider, { newProvider = it }, Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words), label = { Text("Add provider") })
            TextButton({ val p = newProvider.trim(); if (p.isNotEmpty() && providers.none { comparisonName(it) == comparisonName(p) }) { providers = providers + p; newProvider = "" } }) { Text("Add provider") }
            TextButton({ providers = defaultProviderOrder; enabled = true; strength = "balanced" }) { Text("Reset defaults") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button({ busy = true; scope.launch {
            val settings = JSONObject(initial?.toString() ?: "{}").put("enabled", enabled).put("strength", strength)
                .put("providerOrder", JSONArray(providers)).put("updatedAt", isoTime())
            if (manager.saveValue("examDifficulty", settings, owner)) onDismiss() else error = manager.state.value.error
            busy = false
        } }, enabled = !busy, modifier = Modifier.align(Alignment.End).padding(FolioSpacing.dp16)) { Text(if (busy) "Saving…" else "Save") }
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
