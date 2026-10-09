@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import com.folio.notes.FolioMenuPopover
import com.folio.notes.FolioMenuItem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.guardUiTouches
import kotlin.math.roundToInt

/** The gradient surface behind the Overview headline; the halo is decoration only. */
@Composable internal fun ProgressHero(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier, shape = FolioShapes.panel, color = Color.Transparent, contentColor = scheme.onPrimaryContainer) {
        Box(Modifier.background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 56.dp, y = (-64).dp).size(220.dp).background(scheme.primary.copy(alpha = .10f), CircleShape))
            Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), content = content)
        }
    }
}

internal data class StatTileData(val icon: ImageVector, val value: String, val label: String, val onClick: (() -> Unit)? = null)

/** Two tiles a row on phones, four on wider panes. */
@Composable internal fun StatTiles(tiles: List<StatTileData>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) tiles.size.coerceAtMost(4) else 2
        Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    row.forEach { StatTile(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable private fun StatTile(tile: StatTileData, modifier: Modifier) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(tile.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(tile.value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    if (tile.onClick != null) Surface(tile.onClick, modifier, shape = FolioShapes.extraLarge, color = color) { body() }
    else Surface(modifier, shape = FolioShapes.extraLarge, color = color) { body() }
}

/** A percentage as a ring, so a list of results reads at a glance before the numbers. */
@Composable internal fun ScoreRing(percent: Double, size: Dp = 48.dp, color: Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { (percent / 100).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxSize(),
            color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeWidth = if (size >= 64.dp) 6.dp else 4.dp,
            strokeCap = StrokeCap.Round)
        Text("${percent.roundToInt()}", style = if (size >= 64.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge)
    }
}

/** A small rounded label; `strong` uses the primary container for states worth noticing. */
@Composable internal fun StatusPill(text: String, icon: ImageVector? = null, strong: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = if (strong) scheme.primaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (strong) scheme.onPrimaryContainer else scheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** One logged result: ring, what was sat, and the official estimate when a distribution matches. */
@Composable internal fun ExamRow(exam: LoggedExam, references: List<ExamReference>, onClick: () -> Unit,
    note: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val analysis = remember(exam, references) { referenceFor(exam, references)?.let { analyseExam(exam, it) } }
    Surface(onClick, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp12, top = FolioSpacing.dp12, bottom = FolioSpacing.dp12, end = FolioSpacing.dp4),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            ScoreRing(exam.percentage)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                Text("${exam.subject} · ${exam.paper}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${exam.provider.ifBlank { "Practice" }} ${exam.examYear} · ${exam.rawScore.display()}/${exam.rawMax.display()} · ${friendlyDate(exam.completedAt)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (analysis?.grade != null) Text("Est. ${analysis.grade} · ${analysis.percentile?.display(0) ?: "—"} percentile",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
            trailing?.invoke() ?: Spacer(Modifier.width(FolioSpacing.dp8))
        }
    }
}

internal fun friendlyDate(value: String): String = examDate(value)?.let { date ->
    val today = java.time.LocalDate.now()
    when (val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)) {
        0L -> "Today"; 1L -> "Yesterday"
        in 2..6 -> "$days days ago"
        else -> "${date.dayOfMonth} ${date.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)}${if (date.year != today.year) " ${date.year}" else ""}"
    }
} ?: value.take(10)

/** A full-width heading between groups of cards. */
@Composable internal fun SectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = FolioSpacing.dp12, start = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

/** A filter chip that opens its options; it reads as selected whenever it narrows the list. */
@Composable internal fun MenuChip(label: String, value: String, options: List<String>, onChange: (String) -> Unit,
    icon: ImageVector? = null, neutral: String = options.firstOrNull().orEmpty()) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(value != neutral, { expanded = true }, { Text(if (value == neutral) label else value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            Modifier.widthIn(max = 220.dp),
            leadingIcon = icon?.let { { Icon(it, null, Modifier.size(FilterChipDefaults.IconSize)) } },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) })
        FolioMenuPopover(expanded, { expanded = false }, Modifier.heightIn(max = 360.dp).guardUiTouches(), title = label) {
            options.forEach { option ->
                FolioMenuItem({ Text(option) }, { onChange(option); expanded = false },
                    selected = option == value)
            }
        }
    }
}

/** Errors, conflicts and sync notices share one compact banner. */
@Composable internal fun ProgressBanner(icon: ImageVector, message: String, error: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = FolioShapes.large, color = if (error) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (error) scheme.onErrorContainer else scheme.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Icon(icon, null, Modifier.size(20.dp)); Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            if (actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End), content = actions)
        }
    }
}

/** An empty state with one way forward. */
@Composable internal fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp32, horizontal = FolioSpacing.dp24),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Icon(icon, null, Modifier.padding(FolioSpacing.dp16).size(32.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

/** A minimal line of recent results for the hero; values are percentages. */
@Composable internal fun Sparkline(values: List<Double>, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.semantics { contentDescription = "Recent results: ${values.joinToString { "${it.display(0)}%" }}" }) {
        if (values.size < 2) return@Canvas
        val low = (values.min() - 5).coerceAtLeast(0.0); val high = (values.max() + 5).coerceAtMost(100.0).coerceAtLeast(low + 1)
        val pad = 6.dp.toPx()
        fun at(i: Int) = Offset(pad + (size.width - pad * 2) * i / values.lastIndex,
            pad + (size.height - pad * 2) * (1 - ((values[i] - low) / (high - low)).toFloat()))
        val line = Path().apply { values.indices.forEach { i -> at(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
        val area = Path().apply { addPath(line); lineTo(at(values.lastIndex).x, size.height); lineTo(at(0).x, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .25f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 5.dp.toPx(), at(values.lastIndex))
    }
}

/** The outlook as a 0–100 track: the likely range shaded, the forecast dotted, recent form ticked. */
@Composable internal fun OutlookRange(outlook: SubjectOutlook) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(outlook.subject, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${outlook.next.display()}%", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
        }
        Canvas(Modifier.fillMaxWidth().height(18.dp).semantics {
            contentDescription = "${outlook.subject}: next ${outlook.next.display()} percent, range ${outlook.low.display()} to ${outlook.high.display()}, recent ${outlook.current.display()}"
        }) {
            val y = size.height / 2; val track = 6.dp.toPx()
            fun x(v: Double) = (v.coerceIn(0.0, 100.0) / 100 * size.width).toFloat()
            drawLine(scheme.surfaceContainerHighest, Offset(0f, y), Offset(size.width, y), track, StrokeCap.Round)
            drawLine(scheme.primary.copy(alpha = .35f), Offset(x(outlook.low), y), Offset(x(outlook.high), y), track, StrokeCap.Round)
            drawLine(scheme.tertiary, Offset(x(outlook.current), 0f), Offset(x(outlook.current), size.height), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(scheme.primary, 7.dp.toPx(), Offset(x(outlook.next), y))
        }
        Text("${outlook.low.display(0)}–${outlook.high.display(0)}% likely · recent ${outlook.current.display()}% · " +
            "momentum ${signed(outlook.momentum)} pts · ${outlook.confidence} evidence (${outlook.attempts})",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

internal fun signed(value: Double, digits: Int = 1) = "${if (value >= 0) "+" else ""}${value.display(digits)}"

internal fun trendIcon(delta: Double?): ImageVector = when {
    delta == null || kotlin.math.abs(delta) < 1 -> Icons.AutoMirrored.Rounded.TrendingFlat
    delta > 0 -> Icons.AutoMirrored.Rounded.TrendingUp
    else -> Icons.AutoMirrored.Rounded.TrendingDown
}
