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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
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
    var advanced by rememberSaveable { mutableStateOf(false) }
    FolioPanel(title = "Writing follow", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp4),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
            ) {
                FollowSectionTitle("Choose how following feels")
                Text("Write naturally. The page moves after a pause, holds still for corrections, and learns your rhythm. Use Pause in the editor to keep the view still.",
                    style = MaterialTheme.typography.bodyMedium)
                val presets = listOf("Relaxed" to 0f, "Balanced" to .5f, "Responsive" to 1f)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    presets.forEach { (label, value) ->
                        FilterChip(selected = FollowComfort.matches(preferences, value),
                            onClick = { onPreferences(FollowComfort.apply(preferences, value)) },
                            label = { Text(label) })
                    }
                }
                val feel = FollowComfort.value(preferences)
                val presetName = presets.firstOrNull { FollowComfort.matches(preferences, it.second) }?.first ?: "Custom"
                FollowSliderRow("Following feel", presetName,
                    "Relaxed waits longer and leaves more room before moving. Responsive follows sooner with a quicker glide. Changes timing and movement margins together.",
                    feel, { onPreferences(FollowComfort.apply(preferences, it)) }, 0f..1f)
                FollowPreview(preferences, writingHand)
                FollowTimingPreview(preferences)
                Text("Presets keep your hand, reading direction, writing position and automatic-return choice.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FollowToggle("Automatic line return", preferences.automaticReturn, enabled = preferences.mode == FollowMode.TEXT) {
                    onPreferences(preferences.copy(automaticReturn = it))
                }
                Text(if (preferences.mode == FollowMode.TEXT)
                    "Returns after writing across a line and pausing near its edge. Touch down to stop. On an infinite canvas, the visible writing lane sets the line length; you can also select an answer area."
                    else "Maths moves down as your working grows. Tap Next line when you want a new row.",
                    style = MaterialTheme.typography.bodySmall)

                // 1 · What are you writing?
                FollowSectionTitle("What are you writing?")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
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
                        "Follow across a line, then use Next line or enable automatic return. " +
                            "Descenders and joined-up words stay on the same line; tall working and long underlines hold the view."
                    else
                        "Fractions, radicals and long equations reveal room below your working after a pause. The horizontal position stays fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // 2 · Reading direction + pen hand (kept together: both confuse users when split).
                FollowSectionTitle("Writing direction")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
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
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
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
                    "Offsets the writing position slightly to allow room for your palm. " +
                        "Right-handed keeps a wider margin on the right, left-handed on the left.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Hide advanced settings" else "Advanced settings · position, spacing and tracking")
                }
                if (advanced) {
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
                            hint = "Where the end of your writing lands after a sideways glide. Next line returns to the printed margin or where you began writing.",
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
                    FollowToggle("Learn my line spacing", preferences.adaptiveSpacing, enabled = preferences.mode == FollowMode.TEXT) {
                        onPreferences(preferences.copy(adaptiveSpacing = it))
                    }
                    Text(if (preferences.mode == FollowMode.MATH)
                        "In Maths, Next line uses the spacing below where there are no printed lines."
                        else if (preferences.adaptiveSpacing)
                        "Starts with the spacing below, then learns from two confirmed line breaks. Printed lines always take priority."
                        else "Next line uses the spacing below where there are no printed lines.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
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

                    FollowSectionTitle("Tracking controls")
                    FollowToggle("Learn my writing rhythm", preferences.adaptiveTiming) { onPreferences(preferences.copy(adaptiveTiming = it)) }
                    FollowToggle("Follow horizontally in text mode", preferences.horizontalFollow) { onPreferences(preferences.copy(horizontalFollow = it)) }
                    FollowToggle("Follow vertically", preferences.verticalFollow) { onPreferences(preferences.copy(verticalFollow = it)) }
                    FollowToggle("Switch areas when I write in them", preferences.autoSwitchAreas) { onPreferences(preferences.copy(autoSwitchAreas = it)) }
                    Text("Turn area switching off to keep the selected answer area locked. Movement switches affect following within a line; Next line still moves to the next line.", style = MaterialTheme.typography.bodySmall)
                    FollowSliderRow("Minimum zoom", "${"%.1f".format(preferences.minimumZoom)}×", "Automatic movement starts at this zoom level.", preferences.minimumZoom,
                        { onPreferences(preferences.copy(minimumZoom = it)) }, 1f..3f)
                    FollowSliderRow("Horizontal trigger", "${(preferences.edgeThreshold * 100).roundToInt()}%", "Distance across the view before following; mirrored for right-to-left writing.", preferences.edgeThreshold,
                        { onPreferences(preferences.copy(edgeThreshold = it)) }, 0.55f..0.95f)
                    FollowSliderRow("Vertical tolerance", "${(preferences.verticalDeadBand * 100).roundToInt()}%", "Space below your preferred writing height before the view moves.", preferences.verticalDeadBand,
                        { onPreferences(preferences.copy(verticalDeadBand = it)) }, 0.05f..0.3f)
                    FollowSliderRow("Line-end margin", "${(preferences.endMargin * 100).roundToInt()}%", "How close to the answer area's edge a stroke must finish to offer a return.", preferences.endMargin,
                        { onPreferences(preferences.copy(endMargin = it)) }, 0.02f..0.2f)
                    HorizontalDivider()

                    FollowSectionTitle("Timing details")
                    run {
                        FollowSliderRow(
                            label = "Pause before line return",
                            valueText = FollowPreferences.returnDelayLabel(preferences.returnDelayMs),
                            hint = "Automatic return waits this long, allowing for your usual word gaps. Sideways glides use a shorter pause and respond sooner near the visible edge.",
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
                        hint = "Each glide accelerates and settles smoothly. Snappy finishes sooner; Gentle takes longer. Touch down to stop.",
                        value = preferences.glideDurationMs.toFloat(),
                        onValueChange = {
                            onPreferences(preferences.copy(glideDurationMs = it.roundToInt().coerceIn(120, 800)))
                        },
                        valueRange = 120f..800f,
                    )
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End),
            ) {
                TextButton({
                    onPreferences(FollowPreferences())
                    onHand(WritingHand.RIGHT)
                }, shapes = ButtonDefaults.shapes()) { Text("Reset") }
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Done") }
            }
        }
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
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
private fun FollowPreview(preferences: FollowPreferences, hand: WritingHand) {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Text("Live preview · movement zones", style = MaterialTheme.typography.labelMedium)
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
                // Match the actual trigger, including the palm offset and dead band.
                if (preferences.horizontalFollow && preferences.mode == FollowMode.TEXT) {
                    val target = preferences.horizontalPosition + if (hand == WritingHand.RIGHT) -.02f else .02f
                    val trigger = if (preferences.direction == WritingDirection.LTR)
                        maxOf(preferences.edgeThreshold, target + .08f).coerceAtMost(.95f)
                    else minOf(1f - preferences.edgeThreshold, target - .08f).coerceAtLeast(.05f)
                    val left = if (preferences.direction == WritingDirection.LTR) trigger * w else 0f
                    val zoneWidth = if (preferences.direction == WritingDirection.LTR) w - left else trigger * w
                    drawRect(trackColor.copy(alpha = .15f), Offset(left, 0f), androidx.compose.ui.geometry.Size(zoneWidth, h))
                }
                if (preferences.verticalFollow) {
                    val triggerY = h * (preferences.position + preferences.verticalDeadBand).coerceAtMost(1f)
                    drawRect(trackColor.copy(alpha = .15f), Offset(0f, triggerY), androidx.compose.ui.geometry.Size(w, h - triggerY))
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
                    "Shaded edges trigger following after a pause. Dot and dashed lines show the landing position. Active from ${"%.1f".format(preferences.minimumZoom)}× zoom."
                else "Shaded area triggers vertical following after a pause. Maths keeps the horizontal position fixed.",
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
    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
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

@Composable
private fun FollowToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/** Fixed time scale makes timing changes visible without a distracting looping animation. */
@Composable
private fun FollowTimingPreview(preferences: FollowPreferences) {
    val pauseColor = MaterialTheme.colorScheme.secondary
    val glideColor = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
        Text("Line return: pen lifts → pause → glide", style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            val pause = size.width * preferences.returnDelayMs / 2800f
            val glide = size.width * preferences.glideDurationMs / 2800f
            drawRect(pauseColor, size = androidx.compose.ui.geometry.Size(pause, size.height))
            drawRect(glideColor, topLeft = Offset(pause, 0f), size = androidx.compose.ui.geometry.Size(glide, size.height))
        }
        Text("Pause ${FollowPreferences.returnDelayLabel(preferences.returnDelayMs)} · glide ${preferences.glideDurationMs} ms" +
            if (preferences.adaptiveTiming) ". Learns your rhythm; sideways following starts sooner." else ". Sideways following uses half the pause.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
