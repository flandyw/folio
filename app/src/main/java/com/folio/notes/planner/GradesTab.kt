@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes.planner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folio.notes.input.guardUiTouches
import com.folio.notes.progress.ExamReference
import com.folio.notes.progress.analyseScore
import com.folio.notes.progress.ChoiceField
import com.folio.notes.progress.LoggedExam
import com.folio.notes.progress.comparisonName
import com.folio.notes.ui.EmptyHint
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioSpacing

/** One year of a paper's official grade bands, with where [score] (a percentage) would have landed. */
data class YearInsight(val reference: ExamReference, val cohort: Int?, val aPlus: Double?, val mean: Double, val median: Double,
    val grade: String?, val percentile: Double?)

fun yearInsights(references: List<ExamReference>, score: Double) = references.map { r ->
    val counts = r.bands.map { it.count }
    val a = analyseScore(score / 100 * r.maxScore, r)
    YearInsight(r, if (counts.any { it != null }) counts.sumOf { it ?: 0 } else null,
        r.bands.find { it.grade.trim().equals("A+", true) }?.min?.let { it / r.maxScore * 100 },
        if (r.maxScore > 0) r.stats.mean / r.maxScore * 100 else 0.0, if (r.maxScore > 0) r.stats.median / r.maxScore * 100 else 0.0,
        a.grade, a.percentile)
}.sortedBy { it.reference.year }

fun ordinal(value: Double): String {
    val n = Math.round(value).toInt(); val mod = Math.abs(n) % 100
    return "$n" + if (mod in 11..13) "th" else when (Math.abs(n) % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
}

private fun paperLabel(name: String) = name.lowercase().replace(Regex("\\b[a-z]")) { it.value.uppercase() }
    .replace(Regex("\\b(And|Or)\\b")) { it.value.lowercase() }.replace(Regex("^Written Examination"), "Exam").trim()

/** Focal's "VCAA data": the same mark compared across every year of a paper's published grade distribution. */
@Composable internal fun GradesTab(references: List<ExamReference>, exams: List<LoggedExam>) {
    val subjects = remember(references) { references.map { it.subject }.distinct().sorted() }
    val latest = remember(exams) { exams.maxByOrNull { it.completedAt } }
    var pickedSubject by rememberSaveable { mutableStateOf(latest?.subject.orEmpty()) }
    val subject = if (pickedSubject in subjects) pickedSubject else subjects.firstOrNull().orEmpty()
    val ofSubject = remember(references, subject) { references.filter { it.subject == subject } }
    val papers = remember(ofSubject) { ofSubject.associateBy({ comparisonName(it.paper) }, { paperLabel(it.paper) }) }
    var pickedPaper by rememberSaveable { mutableStateOf("") }
    val paper = if (pickedPaper in papers) pickedPaper else latest?.takeIf { it.subject == subject }?.let { comparisonName(it.paper) }?.takeIf { it in papers } ?: papers.keys.firstOrNull().orEmpty()
    var scoreText by rememberSaveable { mutableStateOf(latest?.let { "%.1f".format(it.percentage) } ?: "75") }
    val score = (scoreText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0)
    val insights = remember(ofSubject, paper, score) { yearInsights(ofSubject.filter { comparisonName(it.paper) == paper }, score) }
    val uri = LocalUriHandler.current
    fun pct(v: Double?) = v?.let { "%.1f%%".format(it) } ?: "—"
    LazyColumn(Modifier.fillMaxSize().guardUiTouches(), contentPadding = PaddingValues(FolioDestinationInset, FolioSpacing.dp12, FolioDestinationInset, 120.dp),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        if (references.isEmpty()) item { EmptyHint("VCAA grade data is unavailable.") } else {
            item { ChoiceField("Subject", subject, subjects, { pickedSubject = it; pickedPaper = "" }) }
            item { ChoiceField("Exam", papers[paper].orEmpty(), papers.values.toList(), { label -> pickedPaper = papers.entries.first { it.value == label }.key }) }
            item { OutlinedTextField(scoreText, { scoreText = it }, Modifier.fillMaxWidth(), label = { Text("Score to compare (%)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
            item { Text("A+ cutoffs are official. Mean, median and percentile are estimated from grouped grade bands.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (insights.isEmpty()) item { EmptyHint("No VCAA grade distributions are available for $subject.") }
            insights.filter { it.percentile != null }.takeIf { it.size >= 2 }?.let { list -> item(key = "percentiles") {
                com.folio.notes.progress.ProgressCard("Where ${"%.1f".format(score)}% lands each year", "Estimated percentile in each year's cohort") {
                    com.folio.notes.progress.ColumnChart(list.map { "${it.reference.year}".takeLast(2) to it.percentile!! }, "Percentile by year: " +
                        list.joinToString { "${it.reference.year} ${"%.0f".format(it.percentile)}" })
                }
            } }
            insights.mapNotNull { y -> y.aPlus?.let { "${y.reference.year}" to it } }.takeIf { it.size >= 2 }?.let { cut -> item(key = "aplus") {
                com.folio.notes.progress.ScoreTrend("A+ cutoff", cut, "The official A+ boundary as a percentage of the paper")
            } }
            items(insights.reversed(), key = { it.reference.id }) { y ->
                OutlinedCard(onClick = { uri.openUri(y.reference.sourceUrl) }) {
                    Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        Text("${y.reference.year} · ${y.grade ?: "ungraded"}${y.percentile?.let { " · ${ordinal(it)} percentile" } ?: ""}", style = MaterialTheme.typography.titleMedium)
                        Text("Cohort ${y.cohort?.toString() ?: "—"} · A+ from ${pct(y.aPlus)} · mean ${pct(y.mean)} · median ${pct(y.median)}", style = MaterialTheme.typography.bodySmall)
                        Text("Tap to open the VCAA report", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
