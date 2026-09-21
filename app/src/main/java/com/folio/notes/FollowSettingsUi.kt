@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Human-friendly writing-follow settings. Page units are hidden: line spacing is
 * shown in millimetres (A4 pages are 4 units per mm), screen positions as percent,
 * and every control has a live preview plus a concrete example.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FollowSettingsDialog(
    preferences: FollowPreferences,
    writingHand: WritingHand,
    onPreferences: (FollowPreferences) -> Unit,
    onHand: (WritingHand) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Writing follow") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "The page stays still while the pen is down and glides only after you lift it. " +
                        "Use Next line whenever you finish a short line early.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FollowPreview(preferences)

                // 1 · What are you writing?
                FollowSectionTitle("What are you writing?")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = preferences.mode == FollowMode.TEXT,
                        onClick = { onPreferences(preferences.copy(mode = FollowMode.TEXT)) },
                        label = { Text("Text") },
                    )
                    FilterChip(
                        selected = preferences.mode == FollowMode.MATH,
                        onClick = { onPreferences(preferences.copy(mode = FollowMode.MATH)) },
                        label = { Text("Maths") },
                    )
                }
                Text(
                    if (preferences.mode == FollowMode.TEXT)
                        "Example: finish “Dear Sir…” near the right edge → the page slides so the next line starts at the left. " +
                            "Tall fractions and long underlines never trigger a return."
                    else
                        "Example: a column of working slides straight down. Sideways drift is off, " +
                            "so long equations and diagrams stay where you put them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // 2 · Reading direction + pen hand (kept together: both confuse users when split).
                FollowSectionTitle("Reading direction")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = preferences.direction == WritingDirection.LTR,
                        onClick = { onPreferences(preferences.copy(direction = WritingDirection.LTR)) },
                        label = { Text("Left → right") },
                    )
                    FilterChip(
                        selected = preferences.direction == WritingDirection.RTL,
                        onClick = { onPreferences(preferences.copy(direction = WritingDirection.RTL)) },
                        label = { Text("Right → left") },
                    )
                }
                Text(
                    if (preferences.direction == WritingDirection.LTR) "Example: start at A ⎯⎯⎯▶ finish at Z, then a new line begins at A."
                    else "Example: start at Z ◀⎯⎯⎯ finish at A, then a new line begins at Z. For Arabic, Hebrew and Urdu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FollowSectionTitle("Hand holding the pen")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = writingHand == WritingHand.RIGHT,
                        onClick = { onHand(WritingHand.RIGHT) },
                        label = { Text("Right hand") },
                    )
                    FilterChip(
                        selected = writingHand == WritingHand.LEFT,
                        onClick = { onHand(WritingHand.LEFT) },
                        label = { Text("Left hand") },
                    )
                }
                Text(
                    "Need this, not direction: it keeps the fresh line clear of your palm. " +
                        "Right-handed keeps a wider margin on the right, left-handed on the left.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // 3 · Where writing sits.
                FollowSectionTitle("Where writing sits on screen")
                FollowSliderRow(
                    label = "Writing height",
                    valueText = "${preferences.positionPercent}% down · ${FollowPreferences.positionLabel(preferences.position)}",
                    hint = "Example: 55% keeps the line just below the middle, so you can see the question above and your hand below.",
                    value = preferences.position,
                    onValueChange = { onPreferences(preferences.copy(position = it)) },
                    valueRange = .35f..0.70f,
                )
                if (preferences.mode == FollowMode.TEXT) {
                    FollowSliderRow(
                        label = "Writing column",
                        valueText = "${preferences.horizontalPercent}% across · ${FollowPreferences.horizontalLabel(preferences.horizontalPosition)}",
                        hint = "Example: 50% centres each new line. Move left if your sleeve covers the start of lines.",
                        value = preferences.horizontalPosition,
                        onValueChange = { onPreferences(preferences.copy(horizontalPosition = it)) },
                        valueRange = .35f..0.65f,
                    )
                } else {
                    Text(
                        "Writing column is hidden in Maths mode — follow only moves down, never sideways.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // 4 · Line spacing in mm with presets and a scaled example.
                FollowSectionTitle("Blank-page line spacing")
                Text(
                    FollowPreferences.spacingLabel(preferences.spacing),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = preferences.spacingMm.coerceIn(4f, 24f),
                    onValueChange = { onPreferences(preferences.copy(spacing = FollowPreferences.fromMm(it))) },
                    valueRange = 4f..24f,
                    steps = 19,
                )
                Text(
                    "Millimetres on a printed A4 page. Example: 7 mm matches ruled paper, 8 mm is the relaxed default. " +
                        "Only used where the page has no printed lines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FollowPreferences.spacingPresets.forEach { (name, units) ->
                        FilterChip(
                            selected = preferences.spacing == units,
                            onClick = { onPreferences(preferences.copy(spacing = units)) },
                            label = { Text(name) },
                        )
                    }
                }
                SpacingExample(preferences.spacing)

                HorizontalDivider()

                // 5 · Automatic return + glide: the two new customisations.
                FollowSectionTitle("Line returns")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic line return", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When on: finishing near the edge shows “Next line…” and glides by itself. " +
                                "Touch the pen down quickly to cancel. When off: tap Next line yourself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preferences.automaticReturn,
                        onCheckedChange = { onPreferences(preferences.copy(automaticReturn = it)) },
                    )
                }
                if (preferences.automaticReturn) {
                    FollowSliderRow(
                        label = "Wait before gliding",
                        valueText = FollowPreferences.returnDelayLabel(preferences.returnDelayMs),
                        hint = "Example: 0.7 s is enough to dot an “i” and cancel the glide by touching down.",
                        value = preferences.returnDelayMs / 1000f,
                        onValueChange = {
                            onPreferences(
                                preferences.copy(returnDelayMs = (it * 1000).roundToInt().coerceIn(300, 2000)),
                            )
                        },
                        valueRange = 0.3f..2.0f,
                    )
                }
                FollowSliderRow(
                    label = "Glide smoothness",
                    valueText = "${FollowPreferences.glideLabel(preferences.glideDurationMs)} · ${preferences.glideDurationMs} ms",
                    hint = "Snappy jumps at once, Smooth eases over, Gentle floats. Try Smooth first.",
                    value = preferences.glideDurationMs.toFloat(),
                    onValueChange = {
                        onPreferences(preferences.copy(glideDurationMs = it.roundToInt().coerceIn(120, 800)))
                    },
                    valueRange = 120f..800f,
                )
            }
        },
        confirmButton = { TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Done") } },
        dismissButton = {
            TextButton({
                onPreferences(FollowPreferences())
                onHand(WritingHand.RIGHT)
            },
                shapes = ButtonDefaults.shapes()) { Text("Reset") }
        },
    )
}

@Composable
private fun FollowSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun FollowSliderRow(
    label: String,
    valueText: String,
    hint: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
            )
        }
        Slider(value = value.coerceIn(valueRange.start, valueRange.endInclusive), onValueChange = onValueChange, valueRange = valueRange)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Miniature screen mock: the solid cross shows where the fresh line lands,
 * the faint lines show blank-page spacing, the arrow shows reading direction.
 */
@Composable
private fun FollowPreview(preferences: FollowPreferences) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Preview · where the next line lands", style = MaterialTheme.typography.labelMedium)
            val trackColor = MaterialTheme.colorScheme.primary
            val faint = MaterialTheme.colorScheme.outlineVariant
            val ink = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.fillMaxWidth().height(132.dp)) {
                val w = size.width
                val h = size.height
                // Screen frame.
                drawRoundRect(color = faint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                // Faint blank-page lines, gap scaled from spacing (16..96 → ~8..26 px here).
                val gap = (6 + (preferences.spacing - 16f) / (96f - 16f) * 18f).dp.toPx()
                var y = h * 0.18f
                while (y < h * 0.92f) {
                    drawLine(faint, Offset(w * 0.12f, y), Offset(w * 0.88f, y), strokeWidth = 1.dp.toPx())
                    y += gap
                }
                // Writing height: horizontal writing line.
                val wy = h * preferences.position.coerceIn(.35f, .7f)
                drawLine(
                    trackColor, Offset(w * 0.10f, wy), Offset(w * 0.90f, wy),
                    strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
                // Writing column, text mode only.
                if (preferences.mode == FollowMode.TEXT) {
                    val wx = w * preferences.horizontalPosition.coerceIn(.35f, .65f)
                    drawLine(
                        trackColor, Offset(wx, h * 0.08f), Offset(wx, h * 0.94f),
                        strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                    drawCircle(trackColor, radius = 7.dp.toPx(), center = Offset(wx, wy))
                    // Direction arrow along the writing line.
                    val ltr = preferences.direction == WritingDirection.LTR
                    val x0 = if (ltr) w * 0.2f else w * 0.8f
                    val x1 = if (ltr) w * 0.8f else w * 0.2f
                    drawLine(ink, Offset(x0, wy - 12.dp.toPx()), Offset(x1, wy - 12.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    val tip = 6.dp.toPx()
                    val dir = if (ltr) 1f else -1f
                    drawLine(ink, Offset(x1, wy - 12.dp.toPx()), Offset(x1 - dir * tip, wy - 12.dp.toPx() - tip * 0.7f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(ink, Offset(x1, wy - 12.dp.toPx()), Offset(x1 - dir * tip, wy - 12.dp.toPx() + tip * 0.7f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                } else {
                    // Maths: vertical-only arrow.
                    drawLine(ink, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.82f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    val tip = 6.dp.toPx()
                    drawLine(ink, Offset(w * 0.5f, h * 0.82f), Offset(w * 0.5f - tip * 0.7f, h * 0.82f - tip), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.5f, h * 0.82f), Offset(w * 0.5f + tip * 0.7f, h * 0.82f - tip), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Text(
                if (preferences.mode == FollowMode.TEXT)
                    "Dot = where your pen lands next. Dashed lines = writing height + column."
                else "Maths preview: the page only ever glides straight down.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Three scaled lines so millimetres become visible: “this far apart on paper”. */
@Composable
private fun SpacingExample(spacing: Float) {
    val faint = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("On paper", style = MaterialTheme.typography.labelMedium)
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val gap = (10 + (spacing - 16f) / (96f - 16f) * 30f).dp.toPx().coerceIn(10.dp.toPx(), 40.dp.toPx())
                val cx = size.width / 2f
                var y = size.height / 2f - gap
                repeat(3) {
                    drawLine(faint, Offset(cx - 110.dp.toPx(), y), Offset(cx + 110.dp.toPx(), y), strokeWidth = 1.5.dp.toPx())
                    y += gap
                }
                // Handwriting-ish squiggle on the middle line.
                drawLine(ink, Offset(cx - 70.dp.toPx(), y - gap * 2 + 2.dp.toPx()), Offset(cx + 40.dp.toPx(), y - gap * 2 + 2.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
