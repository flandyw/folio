package com.folio.notes.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import kotlin.math.*
import java.util.Locale

internal fun Double.display(digits: Int = 1) = String.format(Locale.getDefault(), "%.$digits" + "f", this)

@Composable internal fun ProgressCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** Charts always include a readable data list; tap selects a plotted observation. */
@Composable internal fun ScoreTrend(title: String, points: List<Pair<String, Double>>, subtitle: String,
    secondary: List<Double>? = null) {
    ProgressCard(title, subtitle) {
        if (points.isEmpty()) Text("Log an exam to see this chart.") else {
            var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
            val primary = MaterialTheme.colorScheme.primary
            val muted = MaterialTheme.colorScheme.outlineVariant
            val tertiary = MaterialTheme.colorScheme.tertiary
            val summary = points.joinToString { "${it.first}: ${it.second.display()}%" }
            Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = "$title. $summary" }
                .pointerInput(points) { detectTapGestures { at -> selected =
                    ((at.x / size.width) * points.lastIndex).roundToInt().coerceIn(0, points.lastIndex) } }) {
                val inset = 10.dp.toPx(); val w = size.width - inset * 2; val h = size.height - inset * 2
                fun point(index: Int, value: Double) = Offset(inset + w * (if (points.size == 1) .5f else index.toFloat() / points.lastIndex),
                    inset + h * (1 - value.coerceIn(0.0, 100.0).toFloat() / 100))
                for (tick in 0..4) {
                    val y = inset + h * tick / 4
                    drawLine(muted, Offset(inset, y), Offset(size.width - inset, y))
                }
                fun line(values: List<Double>, color: androidx.compose.ui.graphics.Color) {
                    val path = Path()
                    values.forEachIndexed { i, value -> val p = point(i, value); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    drawPath(path, color, style = Stroke(2.dp.toPx()))
                }
                if (secondary?.size == points.size) line(secondary, tertiary)
                line(points.map { it.second }, primary)
                points.forEachIndexed { i, pair -> drawCircle(primary, if (i == selected) 6.dp.toPx() else 3.dp.toPx(), point(i, pair.second)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(points.first().first, style = MaterialTheme.typography.labelSmall)
                Text("0–100%", style = MaterialTheme.typography.labelSmall)
                Text(points.last().first, style = MaterialTheme.typography.labelSmall)
            }
            Text("${points[selected].first} · ${points[selected].second.display()}%", style = MaterialTheme.typography.bodyMedium)
            if (secondary != null) Text("Primary: raw mark · Secondary: VCAA-aligned estimate", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable internal fun MetricBars(title: String, values: List<Pair<String, Double>>, suffix: String = "%", subtitle: String? = null,
    maximum: Double? = null) {
    ProgressCard(title, subtitle) {
        if (values.isEmpty()) Text("No data yet.")
        val scale = maximum ?: max(1.0, values.maxOfOrNull { it.second } ?: 1.0)
        values.forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${value.display(if (suffix == "%") 1 else 0)}$suffix", style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(progress = { (value / scale).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable internal fun DistributionChart(exam: LoggedExam, reference: ExamReference) {
    val stats = reference.stats
    val analysis = analyseExam(exam, reference)
    var inspection by remember(exam.id, reference.id) { mutableStateOf<Double?>(null) }
    val colors = MaterialTheme.colorScheme
    ProgressCard("Normal distribution", "${reference.year} ${reference.subject} · ${reference.paper}") {
        if (stats.stdDev <= 0) Text("This distribution has no measurable spread.") else {
            val density: (Double) -> Double = { score -> exp(-.5 * ((score - stats.mean) / stats.stdDev).pow(2)) }
            Canvas(Modifier.fillMaxWidth().height(220.dp).semantics {
                contentDescription = "Approximate cohort distribution. Mean ${stats.mean.display()}, median ${stats.median.display()}, standard deviation ${stats.stdDev.display()}. Your score ${analysis.scaledScore.display()} out of ${reference.maxScore.display()}."
            }.pointerInput(reference.id) { detectTapGestures { at -> inspection = (at.x / size.width * reference.maxScore).coerceIn(0.0, reference.maxScore) } }) {
                val top = 12.dp.toPx(); val bottom = size.height - 12.dp.toPx(); val h = bottom - top
                fun x(score: Double) = (score / reference.maxScore * size.width).toFloat()
                fun y(score: Double) = bottom - density(score).toFloat() * h
                for (i in 0..4) drawLine(colors.outlineVariant, Offset(0f, top + h * i / 4), Offset(size.width, top + h * i / 4))
                val curve = Path(); val fill = Path().apply { moveTo(0f, bottom) }
                for (i in 0..200) {
                    val score = reference.maxScore * i / 200
                    if (i == 0) curve.moveTo(x(score), y(score)) else curve.lineTo(x(score), y(score))
                    fill.lineTo(x(score), y(score))
                }
                fill.lineTo(size.width, bottom); fill.close()
                drawPath(fill, colors.primary.copy(alpha = .15f)); drawPath(curve, colors.primary, style = Stroke(2.dp.toPx()))
                fun marker(score: Double, color: androidx.compose.ui.graphics.Color, dashed: Boolean) {
                    if (score in 0.0..reference.maxScore) drawLine(color, Offset(x(score), top), Offset(x(score), bottom),
                        1.5.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null)
                }
                for (i in -3..3) if (i != 0) marker(stats.mean + stats.stdDev * i, colors.outlineVariant, true)
                marker(stats.mean, colors.primary, false); marker(stats.median, colors.tertiary, true)
                marker(analysis.scaledScore, colors.secondary, true)
                drawCircle(colors.secondary, 5.dp.toPx(), Offset(x(analysis.scaledScore), y(analysis.scaledScore)))
                inspection?.let { marker(it, colors.onSurface, false) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0", style = MaterialTheme.typography.labelSmall)
                Text("Scaled marks · tap to inspect", style = MaterialTheme.typography.labelSmall)
                Text(reference.maxScore.display(0), style = MaterialTheme.typography.labelSmall)
            }
            Text("Your score ${analysis.scaledScore.display()} · Est. grade ${analysis.grade ?: "—"} · ${analysis.percentile?.display() ?: "—"} percentile")
            inspection?.let { score -> val value = analyseScore(score, reference)
                Text("Selected ${score.display()} · ${value.grade ?: "—"} · ${value.percentile?.display() ?: "—"} percentile · z = ${((score - stats.mean) / stats.stdDev).display(2)}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Mean ${stats.mean.display()} · Median ${stats.median.display()} · SD ${stats.stdDev.display(2)} · Variance ${stats.variance.display(2)}", style = MaterialTheme.typography.bodySmall)
            Text("Curve estimated from grade-band midpoints. Percentiles interpolate within official bands; these are planning estimates.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    MetricBars("Official grade bands", reference.bands.map { it.grade to (it.percentage ?: 0.0) },
        subtitle = "Share of the cohort in each band")
}
