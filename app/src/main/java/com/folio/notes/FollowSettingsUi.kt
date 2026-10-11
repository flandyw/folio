@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Miniature screen mock: the solid cross shows where the fresh line lands,
 * the faint lines show blank-page spacing, the arrow shows reading direction.
 */
@Composable
internal fun FollowPreview(preferences: FollowPreferences, hand: WritingHand, proseOnly: Boolean = false) {
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
                    val trigger = preferences.horizontalTrigger(hand)
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
                if (proseOnly) "Shaded edges trigger following after a pause. The marked response column stays fixed when you zoom or pan."
                else if (preferences.mode == FollowMode.TEXT)
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
internal fun SpacingExample(spacing: Float) {
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

/** Fixed time scale makes timing changes visible without a distracting looping animation. */
@Composable
internal fun FollowTimingPreview(preferences: FollowPreferences) {
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
        Text("Follow pause ${FollowPreferences.returnDelayLabel(preferences.glideDelayMs)} · return pause at least ${FollowPreferences.returnDelayLabel(preferences.returnDelayMs)} · glide ${preferences.glideDurationMs} ms.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The same movement boundary, palm offset and direction used by the native writing surface. */
@Composable internal fun FollowEdgeStripPreview(preferences: FollowPreferences, hand: WritingHand) {
    val accent = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val line = MaterialTheme.colorScheme.outlineVariant
    val width = preferences.edgeStripWidth(hand)
    val opacity = AppPrefs.followEdgeStripOpacity(preferences.edgeStripOpacity)
    val ltr = preferences.direction == WritingDirection.LTR
    val active = preferences.mode == FollowMode.TEXT && preferences.horizontalFollow
    val description = if (!active) "Sideways following off" else if (opacity == 0f) "Edge strip hidden · trigger ${(width * 100).roundToInt()}% from the edge"
        else "Edge strip ${(width * 100).roundToInt()}% wide on the ${if (ltr) "right" else "left"}, ${(opacity * 100).roundToInt()}% opacity"
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Text("Live preview · $description", style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = description }) {
            drawRoundRect(paper)
            repeat(2) { i ->
                val y = size.height * (.36f + i * .4f)
                drawLine(line, Offset(size.width * .06f, y), Offset(size.width * .94f, y), strokeWidth = 1.dp.toPx())
            }
            val strip = size.width * width
            if (active) drawRect(accent.copy(alpha = opacity), Offset(if (ltr) size.width - strip else 0f, 0f), Size(strip, size.height))
            drawRoundRect(line, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Text(if (active) "Write into the strip, then lift and pause for ${FollowPreferences.returnDelayLabel(preferences.glideDelayMs)} to reveal more space. Room before moving changes its width; visibility only changes the tint."
            else "Enable sideways following in Text mode to use the edge strip.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
