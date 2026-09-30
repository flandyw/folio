@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioButtonGroup
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.guardUiTouches
import com.folio.notes.longPressAction
import com.folio.notes.rememberLongPressGuard

@Composable internal fun ReviewDashboard(
    due: Int, total: Int, limit: Int, onLimit: (Int) -> Unit, shuffle: Boolean,
    onShuffle: () -> Unit, working: Boolean, onReview: () -> Unit, onBrowse: () -> Unit,
    orderLabel: String = "Oldest due first",
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth >= 700.dp
        @Composable fun Introduction(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Text(when { total == 0 -> "Turn mistakes into understanding."; due == 0 -> "You're caught up."; due == 1 -> "1 question due"; else -> "$due questions due" },
                    style = MaterialTheme.typography.titleLarge)
                Text(when { total == 0 -> "Log a mistake in Focal, then sync your questions here."; due == 0 -> "Explore your library, or return when your next review is due."; else -> "Read → work it out → compare" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        @Composable fun SessionChoices(modifier: Modifier = Modifier) {
            // M3e connected button group: one choice for the session size, plus the shuffle toggle.
            FolioButtonGroup(modifier) {
                listOf(5, 10, Int.MAX_VALUE).forEach { count ->
                    toggleableItem(limit == count, if (count == Int.MAX_VALUE) "All due" else "$count", { onLimit(count) })
                }
                toggleableItem(shuffle, "Shuffle", { onShuffle() },
                    icon = { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)) })
            }
        }
        @Composable fun SessionControls(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                if (due > 0) {
                    SessionChoices()
                    Button(onReview, enabled = !working, modifier = Modifier.widthIn(min = 220.dp, max = 280.dp), shapes = ButtonDefaults.shapes()) {
                        if (working) LoadingIndicator(Modifier.size(20.dp))
                        else Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (working) "Opening your page…" else "Start ${minOf(due, limit)} questions")
                    }
                    Text(if (shuffle) "Random order" else orderLabel, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else OutlinedButton(onBrowse, shapes = ButtonDefaults.shapes()) { Text("Explore your library"); Spacer(Modifier.width(FolioSpacing.dp8)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
            }
        }
        Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Introduction(Modifier.fillMaxWidth())
                if (horizontal && due > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    SessionChoices(Modifier.weight(1f))
                    Button(onReview, enabled = !working, modifier = Modifier.widthIn(min = 220.dp, max = 280.dp), shapes = ButtonDefaults.shapes()) {
                        if (working) LoadingIndicator(Modifier.size(20.dp)) else Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text(if (working) "Opening…" else "Start ${minOf(due, limit)} questions")
                    }
                } else SessionControls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable internal fun SessionSummary(completed: Int, pending: Int, onBrowse: () -> Unit) {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
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
    val hold = rememberLongPressGuard()
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val previewStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val previewHeight = with(LocalDensity.current) { (previewStyle.lineHeight * 4).toDp() }
    val dueText = if (mistake.suspended) "Suspended" else schedule?.let { dueLabel(it.dueAt) } ?: "New question"
    val overdue = !mistake.suspended && dueText.contains("overdue")
    Box {
    OutlinedCard(onClick = hold.click(onOpen), shape = FolioShapes.extraLarge,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().height(316.dp * fontScale).longPressAction(hold) { menu = true }) {
        Column(Modifier.fillMaxSize().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text(listOfNotNull(context?.subject, context?.paper).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Focal question" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mistake.question, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            RichTextPreview(mistake.questionText.orEmpty(), style = previewStyle,
                modifier = Modifier.fillMaxWidth().height(previewHeight))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = FolioShapes.small,
                    color = if (overdue) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (overdue) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface) {
                    Text(dueText, Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(mistake.category, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(buildList {
                    mistake.marksLost?.let { add("−${trimMark(it)} ${if (it == 1.0) "mark" else "marks"}") }
                    if (attempts > 0) add("$attempts ${if (attempts == 1) "attempt" else "attempts"}")
                    if (mistake.attachments.isNotEmpty()) add("${mistake.attachments.size} ${if (mistake.attachments.size == 1) "attachment" else "attachments"}")
                }.joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!mistake.suspended) TextButton(hold.click(onPractice), enabled = !working, shapes = ButtonDefaults.shapes()) {
                    Text(if (resume) "Continue" else "Practise")
                    Spacer(Modifier.width(FolioSpacing.dp4))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                }
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
