@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun ReviewDashboard(
    due: Int, total: Int, limit: Int, onLimit: (Int) -> Unit, shuffle: Boolean,
    onShuffle: () -> Unit, working: Boolean, onReview: () -> Unit, onBrowse: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("YOUR NEXT STEP", style = MaterialTheme.typography.labelMedium)
            Text(when { total == 0 -> "Turn mistakes into understanding."; due == 0 -> "A clear desk.\nA little more confidence."; else -> "A fresh attempt.\nA stronger understanding." },
                style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
            Text(when { total == 0 -> "Log a mistake in ExamTrack, then sync to bring your questions here."; due == 0 -> "Nothing is due right now. Browse your library to revisit a question at your own pace."; else -> "$due questions ready to revisit. Read, work it out, then compare your answer." }, style = MaterialTheme.typography.bodyLarge)
            if (due > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .15f))
                Text("Make room for a short session", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, Int.MAX_VALUE).forEach { count ->
                        FilterChip(limit == count, { onLimit(count) }, { Text(if (count == Int.MAX_VALUE) "All due" else "$count questions") })
                    }
                    FilterChip(shuffle, onShuffle, { Text("Shuffle") }, leadingIcon = { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)) })
                }
                Button(onReview, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp)); Text(if (working) "Opening your page…" else "Start ${minOf(due, limit)} questions")
                }
                Text(if (shuffle) "Random order · your handwriting is saved as you go" else "Oldest due first · your handwriting is saved as you go", style = MaterialTheme.typography.bodySmall)
            } else OutlinedButton(onBrowse) { Text("Explore your library"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
        }
    }
}

@Composable internal fun SessionSummary(completed: Int, pending: Int, onBrowse: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CheckCircle, null)
            Text("Session complete", style = MaterialTheme.typography.headlineSmall)
            Text("$completed questions reviewed. Your handwriting and ratings are saved.")
            if (pending > 0) Text("$pending ratings waiting to sync. You can safely leave this screen.", style = MaterialTheme.typography.bodySmall)
            TextButton(onBrowse) { Text("Back to your library") }
        }
    }
}

@Composable internal fun MistakeFilterOptions(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    if (options.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected.isBlank(), { onSelect("") }, { Text("All") })
        options.forEach { option -> FilterChip(selected == option, { onSelect(if (selected == option) "" else option) }, { Text(option) }) }
    }
}

@Composable internal fun MistakeLibraryRow(
    mistake: ExamTrackMistake, context: ExamContext?, schedule: MistakeSchedule?, resume: Boolean,
    attempts: Int, onOpen: () -> Unit, onPractice: () -> Unit, working: Boolean,
) {
    OutlinedCard(onClick = onOpen, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(listOfNotNull(context?.subject, context?.paper).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "ExamTrack question" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(mistake.question, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!mistake.questionText.isNullOrBlank()) RichText(mistake.questionText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (mistake.suspended) "Suspended" else schedule?.let { dueLabel(it.dueAt) } ?: "New question", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (mistake.category.isNotBlank()) Text("· ${mistake.category}", style = MaterialTheme.typography.labelMedium)
                if (mistake.marksLost != null) Text("· ${trimMark(mistake.marksLost)} marks lost", style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(buildList {
                    if (attempts > 0) add("$attempts attempts")
                    if (mistake.attachments.isNotEmpty()) add("${mistake.attachments.size} attachments")
                    if (isEmpty()) add("Open question for details")
                }.joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!mistake.suspended) FilledTonalButton(onPractice, enabled = !working) { Text(if (resume) "Continue" else "Practise") }
            }
        }
    }
}
