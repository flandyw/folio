@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun MistakeReviewControls(preferences: MistakeReviewPreferences, mistakes: List<ExamTrackMistake>,
    busy: Boolean, onDismiss: () -> Unit, onSave: (MistakeReviewPreferences) -> Unit,
    onReduce: (Int, Int) -> Unit) {
    var spacing by remember(preferences) { mutableFloatStateOf(preferences.spacingMultiplier.toFloat()) }
    var retry by remember(preferences) { mutableFloatStateOf(preferences.retryHours.toFloat()) }
    var maximum by remember(preferences) { mutableFloatStateOf(preferences.maximumIntervalDays.toFloat()) }
    var days by remember { mutableFloatStateOf(7f) }
    var target by remember { mutableFloatStateOf(5f) }
    var routineDays by remember(preferences) { mutableStateOf(preferences.practiceDays) }
    var routineTime by remember(preferences) { mutableStateOf(preferences.practiceTime) }
    var routineTarget by remember(preferences) { mutableFloatStateOf(preferences.practiceTarget.toFloat()) }
    var effects by remember(preferences) { mutableStateOf(preferences.practiceEffects) }
    var sound by remember(preferences) { mutableStateOf(preferences.practiceSound) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val changes = remember(mistakes, days, target) { MistakeScheduler.reduceReviews(mistakes, days.roundToInt(), target.roundToInt(), now) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Practice routine & pacing") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your practice routine", style = MaterialTheme.typography.titleMedium)
                Text("A shared routine shown inside Folio and Focal. Choose a time around your classes; card due dates keep their own spacing.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, label ->
                        FilterChip(selected = i + 1 in routineDays, onClick = { routineDays = if (i + 1 in routineDays) routineDays - (i + 1) else routineDays + (i + 1) }, enabled = !busy, label = { Text(label) })
                    }
                }
                OutlinedButton({
                    // A malformed stored time opens the picker at the default rather than crashing.
                    val parts = routineTime.split(":").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: listOf(17, 0)
                    android.app.TimePickerDialog(context, { _, hour, minute -> routineTime = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute) },
                        parts[0], parts[1], android.text.format.DateFormat.is24HourFormat(context)).show()
                }, enabled = !busy) { Text("Practice time · $routineTime") }
                if (routineDays.isEmpty()) Text("No practice days chosen: Today will just say practise whenever it suits you.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${routineTarget.roundToInt()} cards per session")
                Slider(routineTarget, { routineTarget = it }, enabled = !busy, valueRange = 1f..20f, steps = 18, modifier = Modifier.semantics { contentDescription = "Cards per practice session" })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Subtle effects & confetti", Modifier.weight(1f)); Switch(effects, { effects = it }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Subtle effects and confetti" }) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Quiet sound effects", Modifier.weight(1f)); Switch(sound, { sound = it }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Quiet sound effects" }) }
                HorizontalDivider()
                Text("Due dates are suggestions. You can practise any saved question from the Library whenever you need to.")
                Text("Successful review spacing · ${"%.1f".format(spacing)}×")
                Slider(spacing, { spacing = it }, enabled = !busy, valueRange = .5f..3f, steps = 24, modifier = Modifier.semantics { contentDescription = "Successful review spacing" })
                Text("Retry after a miss · ${retryLabel(retry)}")
                Slider(retry, { retry = it }, enabled = !busy, valueRange = (1f / 6)..168f, modifier = Modifier.semantics { contentDescription = "Retry hours after a miss" })
                Text("Longest interval · ${maximum.roundToInt()} days")
                Slider(maximum, { maximum = it }, enabled = !busy, valueRange = 7f..365f, modifier = Modifier.semantics { contentDescription = "Longest interval in days" })
                TextButton({ spacing = 1.5f; retry = 24f; maximum = 180f }, enabled = !busy) { Text("Use relaxed defaults") }
                TextButton({ spacing = 1f; retry = 1f / 6; maximum = 365f }, enabled = !busy) { Text("Use frequent review intervals") }
                HorizontalDivider()
                if (preferences.lighterUntil?.let { timestamp(it) > now } == true) {
                    Text("Lighter schedule until ${localDateLabel(preferences.lighterUntil)}")
                    TextButton({ onSave(preferences.copy(lighterUntil = null)) }, enabled = !busy) { Text("End lighter period") }
                }
                Text("Reduce upcoming reviews", style = MaterialTheme.typography.titleSmall)
                Text("Next ${days.roundToInt()} days")
                Slider(days, { days = it }, enabled = !busy, valueRange = 1f..90f, steps = 88, modifier = Modifier.semantics { contentDescription = "Lighter period in days" })
                Text(if (target.roundToInt() == 0) "Take a break" else "Up to ${target.roundToInt()} scheduled questions per day")
                Slider(target, { target = it }, enabled = !busy, valueRange = 0f..50f, steps = 49, modifier = Modifier.semantics { contentDescription = "Daily scheduled review target, zero takes a break" })
                Text(if (changes.isEmpty()) "Nothing in this period is over the target, so no dates need to move."
                    else "${countLabel(changes.size, "question")} will move to later dates. Overflow is spread beyond this period around existing reviews. A break resumes at up to 10 questions a day. Review history stays intact; you can still practise these questions early. New reviews also respect this target during the period.")
                OutlinedButton({ onReduce(days.roundToInt(), target.roundToInt()) }, enabled = !busy && changes.isNotEmpty()) {
                    Text(if (changes.isEmpty()) "Nothing to move" else "Start lighter period · move ${countLabel(changes.size, "date")}")
                }
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Close") } },
        confirmButton = {
            val edited = preferences.copy(spacingMultiplier = spacing.toDouble(), retryHours = retry.toDouble(), maximumIntervalDays = maximum.roundToInt(), practiceDays = routineDays, practiceTime = routineTime, practiceTarget = routineTarget.roundToInt(), practiceSnoozedUntil = null, practiceEffects = effects, practiceSound = sound)
            // Saving an unchanged dialog would only clear a skipped window; offer it once something changed.
            // Compared in the sliders' own float units, so an untouched slider never counts as a change.
            val changed = spacing != preferences.spacingMultiplier.toFloat() || retry != preferences.retryHours.toFloat() ||
                maximum.roundToInt() != preferences.maximumIntervalDays || routineDays.toSet() != preferences.practiceDays.toSet() ||
                routineTime != preferences.practiceTime || routineTarget.roundToInt() != preferences.practiceTarget ||
                effects != preferences.practiceEffects || sound != preferences.practiceSound
            TextButton({ onSave(edited) }, enabled = !busy && changed) { Text(if (changed) "Save routine & pacing" else "No changes") }
        },
    )
}

/** The retry delay in the unit people think in: minutes, hours, then days. */
internal fun retryLabel(hours: Float): String = when {
    hours < 1 -> "${(hours * 60).roundToInt()} minutes"
    hours < 48 -> countLabel(hours.roundToInt(), "hour")
    else -> {
        val days = hours / 24
        if (kotlin.math.abs(days - days.roundToInt()) < .05f) countLabel(days.roundToInt(), "day") else "${"%.1f".format(days)} days"
    }
}
