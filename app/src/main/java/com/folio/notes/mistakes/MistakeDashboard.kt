@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.guardUiTouches
import com.folio.notes.longPressAction

@Composable internal fun ReviewDashboard(
    due: Int, total: Int, limit: Int, onLimit: (Int) -> Unit, shuffle: Boolean,
    onShuffle: () -> Unit, working: Boolean, onReview: () -> Unit, onBrowse: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth >= 700.dp
        @Composable fun Introduction(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("YOUR NEXT STEP", style = MaterialTheme.typography.labelMedium)
                Text(when { total == 0 -> "Turn mistakes into understanding."; due == 0 -> "You're caught up."; else -> "$due questions. A fresh start." },
                    style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                Text(when { total == 0 -> "Log a mistake in ExamTrack, then sync to bring your questions here."; due == 0 -> "Revisit a question at your own pace, or return when your next review is due."; else -> "Read, work it out, then compare. Your handwriting is saved as you go." }, style = MaterialTheme.typography.bodyLarge)
            }
        }
        @Composable fun SessionControls(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (due > 0) {
                    Text("Choose your session", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, Int.MAX_VALUE).forEach { count ->
                            FilterChip(limit == count, { onLimit(count) }, { Text(if (count == Int.MAX_VALUE) "All due" else "$count questions") })
                        }
                        FilterChip(shuffle, onShuffle, { Text("Shuffle") }, leadingIcon = { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)) })
                    }
                    Button(onReview, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) {
                        if (working) LoadingIndicator(Modifier.size(20.dp))
                        else Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp)); Text(if (working) "Opening your page…" else "Start ${minOf(due, limit)} questions")
                    }
                    Text(if (shuffle) "Random order" else "Oldest due first", style = MaterialTheme.typography.bodySmall)
                } else OutlinedButton(onBrowse, shapes = ButtonDefaults.shapes()) { Text("Explore your library"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            if (horizontal) Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Introduction(Modifier.weight(1f))
                SessionControls(Modifier.weight(1f))
            } else Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Introduction(Modifier.fillMaxWidth())
                SessionControls(Modifier.fillMaxWidth())
            }
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
            TextButton(onBrowse, shapes = ButtonDefaults.shapes()) { Text("Back to your library") }
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
    attempts: Int, onOpen: () -> Unit, onPractice: () -> Unit, working: Boolean, selected: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    // Long-pressing a card opens its context menu; the tap still opens the details.
    var menu by remember { mutableStateOf(false) }
    Box {
    OutlinedCard(onClick = onOpen, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.longPressAction { menu = true }) {
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
                if (!mistake.suspended) FilledTonalButton(onPractice, enabled = !working, shapes = ButtonDefaults.shapes()) { Text(if (resume) "Continue" else "Practise") }
            }
        }
    }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
            DropdownMenuItem({ Text("Open details") }, { menu = false; onOpen() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) })
            if (!mistake.suspended) DropdownMenuItem(
                { Text(if (resume) "Continue handwritten review" else "Practise this question") },
                { menu = false; onPractice() },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) }
            )
            if (onDelete != null) DropdownMenuItem({ Text("Delete card") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
        }
    }
}
