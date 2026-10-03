@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.content.SharedPreferences
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Where writing-follow choices live in the app's preferences. */
object FollowPrefsStore {
    const val ENABLED = "writingFollow"
    const val HAND = "writingHand"
    const val DIRECTION = "follow.direction"
    const val MODE = "follow.mode"
    const val AUTO_RETURN = "follow.autoReturn"
    const val FEEL = AppPrefs.FOLLOW_FEEL
    const val HEIGHT = "follow.position"
    const val KEEP_HEIGHT = "follow.keepHeight"

    /** The earlier tuning sliders and per-page answer areas; everything they set is now measured. */
    private val RETIRED = setOf("follow.horizontal", "follow.spacing", "follow.returnDelayMs", "follow.glideMs",
        "follow.adaptiveTiming", "follow.adaptiveSpacing", "follow.horizontalFollow", "follow.verticalFollow",
        "follow.autoSwitchAreas", "follow.minimumZoom", "follow.edgeThreshold", "follow.verticalDeadBand",
        "follow.endMargin", "follow.autoDetectAnswerAreas")

    fun load(p: SharedPreferences): FollowPreferences {
        retire(p)
        return FollowPreferences(
            direction = runCatching { WritingDirection.valueOf(p.getString(DIRECTION, null)!!) }.getOrDefault(WritingDirection.LTR),
            mode = runCatching { FollowMode.valueOf(p.getString(MODE, null)!!) }.getOrDefault(FollowMode.TEXT),
            automaticReturn = p.getBoolean(AUTO_RETURN, false),
            feel = FollowPreferences.clampFeel(p.getFloat(FEEL, FollowPreferences.DEFAULT_FEEL)),
            height = FollowPreferences.clampHeight(p.getFloat(HEIGHT, FollowPreferences.DEFAULT_HEIGHT)),
            keepHeight = p.getBoolean(KEEP_HEIGHT, false),
            canvasLineScreens = AppPrefs.followCanvasScreens(p.getInt(AppPrefs.FOLLOW_CANVAS_SCREENS, AppPrefs.DEFAULT_FOLLOW_CANVAS_SCREENS)),
        )
    }

    fun save(p: SharedPreferences, value: FollowPreferences) {
        p.edit().putString(DIRECTION, value.direction.name).putString(MODE, value.mode.name)
            .putBoolean(AUTO_RETURN, value.automaticReturn).putFloat(FEEL, value.feel).putFloat(HEIGHT, value.height)
            .putBoolean(KEEP_HEIGHT, value.keepHeight)
            .putInt(AppPrefs.FOLLOW_CANVAS_SCREENS, AppPrefs.followCanvasScreens(value.canvasLineScreens)).apply()
    }

    fun hand(p: SharedPreferences): WritingHand =
        runCatching { WritingHand.valueOf(p.getString(HAND, null)!!) }.getOrDefault(WritingHand.RIGHT)

    fun setHand(p: SharedPreferences, hand: WritingHand) { p.edit().putString(HAND, hand.name).apply() }

    private fun retire(p: SharedPreferences) {
        val stale = p.all.keys.filter { it in RETIRED || it.startsWith("follow.region.") }
        if (stale.isEmpty()) return
        val edit = p.edit()
        // The old pause slider is the nearest thing to the new feel, so a tuned rhythm carries over.
        if (!p.contains(FEEL) && p.contains("follow.returnDelayMs")) {
            edit.putFloat(FEEL, FollowPreferences.clampFeel((1100f - p.getInt("follow.returnDelayMs", 650)) / 700f))
        }
        stale.forEach { edit.remove(it) }
        edit.apply()
    }
}

/** Writing-follow choices. Everything else (line height, spacing, line length, rhythm) is measured. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FollowSettingsDialog(
    preferences: FollowPreferences,
    writingHand: WritingHand,
    onPreferences: (FollowPreferences) -> Unit,
    onHand: (WritingHand) -> Unit,
    showAreas: Boolean,
    onShowAreas: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    FolioPanel(title = "Writing follow", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp4),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
            ) {
                Text("Write normally. After a short pause the page moves so your line stays in view. " +
                    "Touching the screen stops it, and corrections or writing somewhere new never move the page.",
                    style = MaterialTheme.typography.bodyMedium)

                SectionTitle("Feel")
                val presets = listOf("Relaxed" to 0f, "Balanced" to .5f, "Responsive" to 1f)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    presets.forEach { (label, value) ->
                        FilterChip(selected = kotlin.math.abs(preferences.feel - value) < .01f,
                            onClick = { onPreferences(preferences.copy(feel = value)) }, label = { Text(label) })
                    }
                }
                Slider(preferences.feel, { onPreferences(preferences.copy(feel = FollowPreferences.clampFeel(it))) })
                Hint("Relaxed waits ${FollowPreferences.seconds(FollowPreferences(feel = 0f).pauseMs)} and lets writing reach further across before moving. " +
                    "Responsive moves after ${FollowPreferences.seconds(FollowPreferences(feel = 1f).pauseMs)} with a quicker glide. " +
                    "Either way it learns how long you pause between words.")

                HorizontalDivider()
                SectionTitle("What you are writing")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    FilterChip(preferences.mode == FollowMode.TEXT, { onPreferences(preferences.copy(mode = FollowMode.TEXT)) }, { Text("Text") })
                    FilterChip(preferences.mode == FollowMode.MATH, { onPreferences(preferences.copy(mode = FollowMode.MATH)) }, { Text("Maths") })
                }
                Hint(if (preferences.mode == FollowMode.TEXT)
                    "Follows along each line, then Next line returns to where your lines start. Printed rules set the line spacing; on blank paper it is measured from your writing."
                    else "Fractions and growing working reveal room below after a pause. The page never moves sideways.")
                Toggle("Automatic line return", preferences.automaticReturn, enabled = preferences.mode == FollowMode.TEXT) {
                    onPreferences(preferences.copy(automaticReturn = it))
                }
                Hint("When a full line reaches its end and you pause, the page moves to the next line. Touch down to cancel. " +
                    "Printed answer areas end at their last rule. If the next line already has writing, use Next line to move there yourself.")
                Toggle("Outline detected answer areas", showAreas, onChange = onShowAreas)
                Hint("Draws a dashed outline around each answer area found on an exam page, so you can see where lines end and Next line stops.")
                if (preferences.mode == FollowMode.TEXT && preferences.automaticReturn) {
                    SectionTitle("Canvas line length")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        for (screens in 1..4) FilterChip(preferences.canvasLineScreens == screens,
                            { onPreferences(preferences.copy(canvasLineScreens = screens)) },
                            { Text("$screens ${if (screens == 1) "screen" else "screens"}") })
                    }
                    Hint("On an infinite canvas, each paragraph wraps after this many screen widths at its starting zoom. " +
                        "Without automatic return, sideways writing can continue as far as you like. Exam pages use their response lines.")
                }

                HorizontalDivider()
                SectionTitle("Direction and hand")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    FilterChip(preferences.direction == WritingDirection.LTR, { onPreferences(preferences.copy(direction = WritingDirection.LTR)) }, { Text("Left → right") })
                    FilterChip(preferences.direction == WritingDirection.RTL, { onPreferences(preferences.copy(direction = WritingDirection.RTL)) }, { Text("Right → left") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    FilterChip(writingHand == WritingHand.RIGHT, { onHand(WritingHand.RIGHT) }, { Text("Right hand") })
                    FilterChip(writingHand == WritingHand.LEFT, { onHand(WritingHand.LEFT) }, { Text("Left hand") })
                }
                Hint("Your hand decides how much just-written text stays visible after a sideways move, and which corner the controls sit in.")

                HorizontalDivider()
                SectionTitle("Writing height")
                Toggle("Keep my line at this height", preferences.keepHeight, enabled = preferences.mode == FollowMode.TEXT) {
                    onPreferences(preferences.copy(keepHeight = it))
                }
                Hint(if (preferences.mode == FollowMode.MATH) "Maths always moves down to keep your working in view."
                    else "Off: the page stays still vertically while you write along a line, and only moves down if you near the bottom edge. " +
                        "On: it also nudges the line back up to the height below. Next line always uses this height.")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Slider(preferences.height, { onPreferences(preferences.copy(height = FollowPreferences.clampHeight(it))) },
                        Modifier.weight(1f), valueRange = FollowPreferences.MIN_HEIGHT..FollowPreferences.MAX_HEIGHT)
                    Text("${(preferences.height * 100).roundToInt()}%", Modifier.padding(start = FolioSpacing.dp8),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                HeightPreview(preferences)
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End),
            ) {
                TextButton({ onPreferences(FollowPreferences()); onHand(WritingHand.RIGHT) }, shapes = ButtonDefaults.shapes()) { Text("Reset") }
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Done") }
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/**
 * A screen in miniature: the dashed line is where your writing lands after a move, the shaded band
 * below is how far it may sink first, and the shaded edge is how far across before a sideways move.
 */
@Composable
private fun HeightPreview(preferences: FollowPreferences) {
    val accent = MaterialTheme.colorScheme.primary
    val frame = MaterialTheme.colorScheme.outlineVariant
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val w = size.width; val h = size.height
                drawRoundRect(frame, style = Stroke(width = 2.dp.toPx()))
                val y = h * preferences.height
                val band = h * (preferences.height + preferences.verticalBand).coerceAtMost(1f)
                drawRect(accent.copy(alpha = .12f), Offset(0f, band), Size(w, h - band))
                if (preferences.mode == FollowMode.TEXT) {
                    val ltr = preferences.direction == WritingDirection.LTR
                    val edge = w * if (ltr) preferences.sidewaysTrigger else 1f - preferences.sidewaysTrigger
                    drawRect(accent.copy(alpha = .12f), Offset(if (ltr) edge else 0f, 0f), Size(if (ltr) w - edge else edge, h))
                }
                drawLine(accent, Offset(w * .08f, y), Offset(w * .92f, y), strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            Hint(if (preferences.mode == FollowMode.TEXT) "Shaded areas start a move after a pause; the dashed line is where your line lands."
                else "Writing that reaches the shaded band moves up to the dashed line after a pause.")
        }
    }
}
