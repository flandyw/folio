@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.ui.FolioIcons
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

@Composable
internal fun HistoryCard(entry: FocalStudyEntry, subject: String?, color: Color, dedicated: Boolean,
                        onShow: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    Surface(shape = FolioShapes.large, color = color, onClick = onShow ?: {}, enabled = onShow != null) {
        Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp16, top = FolioSpacing.dp12, bottom = FolioSpacing.dp12,
            end = if (onDelete != null) FolioSpacing.dp4 else FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Box(Modifier.size(10.dp).background(FocalSubjects.color(entry.subjectId)?.let { Color(it) }
                ?: MaterialTheme.colorScheme.outline, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOf(entry.sittingLabel, subject ?: "No subject",
                    entry.notebookTitle, "${time.format(Date(entry.startedAt))} – ${time.format(Date(entry.endedAt))}")
                    .filterNot { it.isNullOrBlank() }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dedicated) {
                    if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    entry.confidence?.let { Text("Confidence $it/5 · ${CONFIDENCE_LABELS[it - 1]}", style = MaterialTheme.typography.labelSmall) }
                    if (!entry.synced) Text(if (entry.userId == null) "Saved on this device" else "Saved locally · awaiting sync",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(formatStudyMinutes(focalHistoryMinutes(entry)), style = MaterialTheme.typography.titleMedium)
            onDelete?.let { IconButton(it) { Icon(FolioIcons.Delete,
                "Delete session ${entry.title.ifBlank { "Untitled session" }}, ${time.format(Date(entry.startedAt))}", tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
internal fun PlannedStudyCard(entry: FocalStudyEntry, subjects: List<FocalSubject>, color: Color, now: Long,
                             enabled: Boolean, onStart: () -> Unit, onEdit: (() -> Unit)? = null) {
    val slots = remember(entry.remotePayload, entry.planDraft, entry.pendingBlocks) { focalPlannedSlots(entry) }
    val details = remember(entry.remotePayload, entry.planDraft) { focalPlanDetails(entry) }
    val zone = remember { ZoneId.systemDefault() }
    val scheme = MaterialTheme.colorScheme
    val conflicted = entry.synced && entry.planDraft != null
    val whenLabel = focalPlanWhen(slots, now, zone)
    val overdue = focalPlanOverdue(slots, now)
    val current = slots.any { it.startAt <= now && (it.endAt ?: it.startAt) > now }
    Surface(shape = FolioShapes.large, color = color) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                    Text(entry.title.ifBlank { "Untitled plan" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(focalSubjectNames(entry, subjects),
                        slots.takeIf { it.isNotEmpty() }?.let { formatStudyMinutes(studyRoundedMinutes(it.sumOf { s -> requireNotNull(s.endAt) - s.startAt })) },
                        if (slots.size > 1) "${slots.size} blocks" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                whenLabel?.let { label ->
                    Surface(shape = FolioShapes.small, color = when {
                        overdue -> scheme.errorContainer
                        current -> scheme.primaryContainer
                        else -> scheme.secondaryContainer
                    }) {
                        Text(label, Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, color = when {
                                overdue -> scheme.onErrorContainer
                                current -> scheme.onPrimaryContainer
                                else -> scheme.onSecondaryContainer
                            })
                    }
                }
            }
            if (conflicted) Text("Draft needs review · edit and save to resolve changes from Focal.",
                color = scheme.error, style = MaterialTheme.typography.bodySmall)
            slots.take(3).forEach { slot ->
                Text(focalSlotLabel(slot, zone), style = MaterialTheme.typography.bodySmall)
            }
            if (slots.size > 3) Text("+${slots.size - 3} more blocks", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (details.first.isNotBlank()) Text(details.first, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (details.second.isNotEmpty()) Text("Topics: ${details.second.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                onEdit?.let { OutlinedButton(it, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (conflicted) "Review" else "Edit") } }
                Button(onStart, enabled = enabled, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(FolioIcons.Play, null)
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Text("Start session")
                }
            }
        }
    }
}
