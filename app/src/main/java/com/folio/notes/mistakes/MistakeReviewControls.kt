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
    val now = remember { System.currentTimeMillis() }
    val changes = remember(mistakes, days, target) { MistakeScheduler.reduceReviews(mistakes, days.roundToInt(), target.roundToInt(), now) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Review pacing") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Due dates are suggestions. You can practise any saved question from the Library whenever you need to.")
                Text("Successful review spacing · ${"%.1f".format(spacing)}×")
                Slider(spacing, { spacing = it }, enabled = !busy, valueRange = .5f..3f, steps = 24, modifier = Modifier.semantics { contentDescription = "Successful review spacing" })
                Text("Retry after a miss · ${if (retry < 1) "${(retry * 60).roundToInt()} minutes" else "${retry.roundToInt()} hours"}")
                Slider(retry, { retry = it }, enabled = !busy, valueRange = (1f / 6)..168f, modifier = Modifier.semantics { contentDescription = "Retry hours after a miss" })
                Text("Longest interval · ${maximum.roundToInt()} days")
                Slider(maximum, { maximum = it }, enabled = !busy, valueRange = 7f..365f, modifier = Modifier.semantics { contentDescription = "Longest interval in days" })
                TextButton({ spacing = 1.5f; retry = 24f; maximum = 180f }, enabled = !busy) { Text("Use relaxed defaults") }
                TextButton({ spacing = 1f; retry = 1f / 6; maximum = 365f }, enabled = !busy) { Text("Use frequent review intervals") }
                HorizontalDivider()
                if (preferences.lighterUntil?.let { timestamp(it) > now } == true) {
                    Text("Lighter schedule until ${preferences.lighterUntil.take(10)}")
                    TextButton({ onSave(preferences.copy(lighterUntil = null)) }, enabled = !busy) { Text("End lighter period") }
                }
                Text("Reduce upcoming reviews", style = MaterialTheme.typography.titleSmall)
                Text("Next ${days.roundToInt()} days")
                Slider(days, { days = it }, enabled = !busy, valueRange = 1f..90f, steps = 88, modifier = Modifier.semantics { contentDescription = "Lighter period in days" })
                Text(if (target.roundToInt() == 0) "Take a break" else "Up to ${target.roundToInt()} scheduled questions per day")
                Slider(target, { target = it }, enabled = !busy, valueRange = 0f..50f, steps = 49, modifier = Modifier.semantics { contentDescription = "Daily scheduled review target, zero takes a break" })
                Text("${changes.size} questions will move to later dates. Overflow is spread beyond this period around existing reviews. A break resumes at up to 10 questions a day. Review history stays intact; you can still practise these questions early. New reviews also respect this target during the period.")
                OutlinedButton({ onReduce(days.roundToInt(), target.roundToInt()) }, enabled = !busy) {
                    Text("Start lighter period · move ${changes.size} dates")
                }
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Close") } },
        confirmButton = { TextButton({ onSave(preferences.copy(spacingMultiplier = spacing.toDouble(), retryHours = retry.toDouble(), maximumIntervalDays = maximum.roundToInt())) }, enabled = !busy) { Text("Save pacing") } },
    )
}
