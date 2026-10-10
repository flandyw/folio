package com.folio.notes.progress

import com.folio.notes.EmptyHint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/** The one card for Progress: a quiet tonal surface, an optional leading icon and a trailing action. */
@Composable internal fun ProgressCard(title: String, subtitle: String? = null, icon: ImageVector? = null,
    modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                if (icon != null) Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer) { Icon(icon, null, Modifier.padding(FolioSpacing.dp8).size(20.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                action?.invoke()
            }
            content()
        }
    }
}

/** Charts always include a readable data list; tap selects a plotted observation. */
@Composable internal fun ScoreTrend(title: String, points: List<Pair<String, Double>>, subtitle: String,
    secondary: List<Double>? = null, icon: ImageVector? = null) {
    ProgressCard(title, subtitle, icon) {
        if (points.isEmpty()) Text("Log an exam to see this chart.", color = MaterialTheme.colorScheme.onSurfaceVariant) else {
            var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
            val primary = MaterialTheme.colorScheme.primary
            val muted = MaterialTheme.colorScheme.outlineVariant
            val tertiary = MaterialTheme.colorScheme.tertiary
            val surface = MaterialTheme.colorScheme.surfaceContainerLow
            val summary = points.joinToString { "${it.first}: ${it.second.display()}%" }
            Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Row(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Text("${points[selected].second.display()}%", style = MaterialTheme.typography.titleLarge)
                    Text(points[selected].first, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().height(180.dp)) {
                Column(Modifier.fillMaxHeight().padding(end = FolioSpacing.dp4), verticalArrangement = Arrangement.SpaceBetween) {
                    listOf("100", "50", "0").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "$title. $summary" }
                    .pointerInput(points) { detectTapGestures { at -> selected =
                        ((at.x / size.width) * points.lastIndex).roundToInt().coerceIn(0, points.lastIndex) } }) {
                    val inset = 8.dp.toPx(); val w = size.width - inset * 2; val h = size.height - inset * 2
                    fun point(index: Int, value: Double) = Offset(inset + w * (if (points.size == 1) .5f else index.toFloat() / points.lastIndex),
                        inset + h * (1 - value.coerceIn(0.0, 100.0).toFloat() / 100))
                    for (tick in 0..4) {
                        val y = inset + h * tick / 4
                        drawLine(muted, Offset(inset, y), Offset(size.width - inset, y), pathEffect = if (tick % 2 == 1) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null)
                    }
                    val values = points.map { it.second }
                    if (values.size > 1) {
                        val area = Path().apply {
                            moveTo(point(0, values[0]).x, inset + h)
                            values.forEachIndexed { i, v -> point(i, v).let { lineTo(it.x, it.y) } }
                            lineTo(point(values.lastIndex, values.last()).x, inset + h); close()
                        }
                        drawPath(area, Brush.verticalGradient(listOf(primary.copy(alpha = .22f), primary.copy(alpha = 0f)), inset, inset + h))
                    }
                    fun line(series: List<Double>, color: Color, dashed: Boolean) {
                        val path = Path()
                        series.forEachIndexed { i, value -> val p = point(i, value); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                        drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null))
                    }
                    if (secondary?.size == points.size) line(secondary, tertiary, true)
                    line(values, primary, false)
                    val chosen = point(selected, values[selected])
                    drawLine(primary.copy(alpha = .4f), Offset(chosen.x, inset), Offset(chosen.x, inset + h), 1.dp.toPx())
                    values.forEachIndexed { i, value ->
                        val at = point(i, value)
                        if (i == selected) { drawCircle(primary, 7.dp.toPx(), at); drawCircle(surface, 3.dp.toPx(), at) }
                        else drawCircle(primary, 3.5.dp.toPx(), at)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(points.first().first.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tap to inspect", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(points.last().first.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (secondary != null) Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                ChartKey(primary, "Raw mark"); ChartKey(tertiary, "VCAA-aligned estimate")
            }
        }
    }
}

@Composable internal fun ChartKey(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        Box(Modifier.size(width = 14.dp, height = 4.dp).background(color, FolioShapes.bar))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A labelled horizontal bar, drawn as a rounded track so long lists stay scannable. */
@Composable internal fun MeterRow(label: String, value: String, fraction: Float, color: Color = MaterialTheme.colorScheme.primary,
    supporting: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

@Composable internal fun MetricBars(title: String, values: List<Pair<String, Double>>, suffix: String = "%", subtitle: String? = null,
    maximum: Double? = null, icon: ImageVector? = null, limit: Int = 6) {
    var expanded by remember(title) { mutableStateOf(false) }
    ProgressCard(title, subtitle, icon) {
        if (values.isEmpty()) EmptyHint("No data yet.")
        val scale = maximum ?: max(1.0, values.maxOfOrNull { it.second } ?: 1.0)
        (if (expanded) values else values.take(limit)).forEach { (label, value) ->
            MeterRow(label, "${value.display(if (suffix == "%") 1 else 0)}$suffix", (value / scale).toFloat())
        }
        if (values.size > limit) TextButton({ expanded = !expanded }) { Text(if (expanded) "Show fewer" else "Show all ${values.size}") }
    }
}

/** Vertical bars for a time series; the last bar is emphasised as "now". */
@Composable internal fun ColumnChart(values: List<Pair<String, Double>>, description: String, modifier: Modifier = Modifier,
    labelEvery: Int = 1) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.secondaryContainer
    val scale = max(1.0, values.maxOfOrNull { it.second } ?: 1.0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size; val bar = slot * .62f; val radius = CornerRadius(bar / 2, bar / 2)
            values.forEachIndexed { i, (_, value) ->
                val height = max(4.dp.toPx(), (value / scale).toFloat() * size.height)
                drawRoundRect(if (i == values.lastIndex) primary else if (value > 0) primary.copy(alpha = .55f) else muted,
                    Offset(slot * i + (slot - bar) / 2, size.height - height), Size(bar, height), radius)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            values.forEachIndexed { i, (label, _) ->
                Text(if (i % labelEvery == 0 || i == values.lastIndex) label else "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
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
        subtitle = "Share of the cohort in each band", limit = 20)
}
