@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.mistakes

import com.folio.notes.FolioMenuPopover
import com.folio.notes.FolioMenuItem

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.guardUiTouches
import com.folio.notes.longPressAction
import com.folio.notes.rememberLongPressGuard

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
    onDelete: (() -> Unit)? = null, now: Long = System.currentTimeMillis(),
) {
    // Long-pressing a card opens its context menu; the tap still opens the details.
    var menu by remember { mutableStateOf(false) }
    val hold = rememberLongPressGuard()
    val previewStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val dueText = if (mistake.suspended) "Suspended" else schedule?.let { dueLabel(it.dueAt, now) } ?: "New question"
    val overdue = !mistake.suspended && dueText.contains("overdue")
    Box {
    OutlinedCard(onClick = hold.click(onOpen), shape = FolioShapes.extraLarge,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().longPressAction(hold) { menu = true }) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text(listOfNotNull(context?.subject, context?.paper).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { if (mistake.attemptId.isEmpty()) "Uncategorised" else "Focal question" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mistake.question, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            RichText(mistake.questionText.orEmpty(), style = previewStyle,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(FolioSpacing.dp8))
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
        FolioMenuPopover(menu, { menu = false }, modifier = Modifier.guardUiTouches(), title = "Review card") {
            FolioMenuItem({ Text("Open details") }, { menu = false; onOpen() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) })
            if (!mistake.suspended) FolioMenuItem(
                { Text(if (resume) "Continue handwritten review" else "Practise this question") },
                { menu = false; onPractice() },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) }
            )
            if (onDelete != null) FolioMenuItem({ Text("Delete card") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, destructive = true)
        }
    }
}
