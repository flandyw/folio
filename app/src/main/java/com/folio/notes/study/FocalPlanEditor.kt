@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.study

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.folio.notes.input.guardUiTouches
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private val BLOCK_LENGTHS = listOf(15, 30, 45, 60, 90, 120)

/** The plan uses the same canonical blocks and metadata as Focal; no notebook storage is involved. */
@Composable
internal fun FocalPlanEditor(entry: FocalStudyEntry, subjects: List<FocalSubject>, now: Long,
                             onDismiss: () -> Unit, onSave: (String, String?, String, List<String>, List<FocalStudyInterval>) -> Boolean,
                             onDelete: (() -> Unit)?, onDuplicate: (() -> Unit)?) {
    val context = LocalContext.current
    val openedRevision = rememberSaveable(entry.id) { entry.revision }
    val details = remember(entry.id) { focalPlanDetails(entry) }
    var title by rememberSaveable(entry.id) { mutableStateOf(entry.title) }
    var subject by rememberSaveable(entry.id) { mutableStateOf(entry.subjectId) }
    var description by rememberSaveable(entry.id) { mutableStateOf(details.first) }
    var topics by rememberSaveable(entry.id) { mutableStateOf(details.second.joinToString("\n")) }
    var error by rememberSaveable(entry.id) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(entry.id) { mutableStateOf(false) }
    var confirmClose by rememberSaveable(entry.id) { mutableStateOf(false) }
    val saver = listSaver<List<FocalStudyInterval>, Long>(
        save = { slots -> slots.flatMap { listOf(it.startAt, requireNotNull(it.endAt)) } },
        restore = { values -> values.chunked(2).map { FocalStudyInterval(it[0], it[1]) } })
    val initialSlots = remember(entry.id) { focalPlannedSlots(entry).ifEmpty {
        // A new plan starts at the next quarter hour rather than at an odd minute in the past.
        val quarter = 15 * 60_000L
        val start = (now + quarter - 1) / quarter * quarter
        listOf(FocalStudyInterval(start, start + 30 * 60_000L))
    } }
    var slots by rememberSaveable(entry.id, stateSaver = saver) { mutableStateOf(initialSlots) }
    val problems = remember(slots) { focalPlanSlotProblems(slots) }
    val subjectName = subjects.firstOrNull { it.id == subject }?.name
    val defaultTitle = "${subjectName ?: "Study"} study"
    val dirty = title != entry.title || subject != entry.subjectId || description != details.first ||
        topics != details.second.joinToString("\n") || slots != initialSlots
    val stale = entry.revision != openedRevision
    fun update(index: Int, start: Boolean, stamp: Long) {
        val previous = slots[index]
        slots = slots.mapIndexed { position, slot -> if (position != index) slot else if (start)
            FocalStudyInterval(stamp, stamp + requireNotNull(previous.endAt) - previous.startAt)
        else slot.copy(endAt = stamp) }
        error = null
    }
    fun pick(index: Int, start: Boolean, date: Boolean) {
        val value = slots[index].let { if (start) it.startAt else requireNotNull(it.endAt) }
        val calendar = Calendar.getInstance().apply { timeInMillis = value }
        if (date) DatePickerDialog(context, { _, year, month, day ->
            calendar.set(year, month, day)
            update(index, start, calendar.timeInMillis)
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        else TimePickerDialog(context, { _, hour, minute ->
            calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
            update(index, start, calendar.timeInMillis)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    fun shift(delta: Long) { slots = slots.map { FocalStudyInterval(it.startAt + delta, requireNotNull(it.endAt) + delta) }; error = null }
    fun save() {
        runCatching {
            require(!stale) { "This plan changed while you were editing. Close and reopen it before saving." }
            onSave(title.trim().ifBlank { defaultTitle }, subject, description, topics.lines(), slots.sortedBy { it.startAt })
        }
            .onSuccess { if (it) onDismiss() else error = "Could not save on this device. Check free storage and try again." }
            .onFailure { error = it.message ?: "Could not save this plan." }
    }
    val total = slots.sumOf { ((it.endAt ?: it.startAt) - it.startAt).coerceAtLeast(0L) }
    FolioPanel(if (onDelete == null) "Plan study" else "Edit planned study", onDismiss,
        canDismiss = { if (dirty) { confirmClose = true; false } else true }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            if (stale) Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Text("This plan was changed on another device while open here. Close and reopen it to edit the latest version.",
                    Modifier.padding(FolioSpacing.dp12), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(title, { title = it.take(512); error = null }, Modifier.fillMaxWidth(), label = { Text("Title") },
                placeholder = { Text(defaultTitle) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            FocalSubjectPicker(subjects, subject) { subject = it }
            Text("${formatStudyMinutes(studyRoundedMinutes(total))} planned" + if (slots.size > 1) " in ${slots.size} blocks" else "",
                style = MaterialTheme.typography.titleSmall)
            Text("Time blocks describe your plan. Only running the timer records time studied.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                TextButton({
                    val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                    val first = Calendar.getInstance().apply { timeInMillis = slots.minOf { it.startAt }; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                    shift(day.timeInMillis - first.timeInMillis)
                }) { Text("Move to today") }
                TextButton({
                    val first = Calendar.getInstance().apply { timeInMillis = slots.minOf { it.startAt } }
                    val next = (first.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                    shift(next.timeInMillis - first.timeInMillis)
                }) { Text("Next day") }
                TextButton({ shift(now / 60_000L * 60_000L - slots.minOf { it.startAt }) }) { Text("Move to now") }
            }
            slots.forEachIndexed { index, slot ->
                val problem = problems[index]
                val length = (requireNotNull(slot.endAt) - slot.startAt) / 60_000L
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Block ${index + 1}" + if (length > 0) " · ${formatStudyMinutes(length)}" else "",
                                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            if (slots.size > 1) IconButton({ slots = slots.filterIndexed { position, _ -> position != index }; error = null }) {
                                Icon(Icons.Rounded.Close, "Remove block ${index + 1}")
                            }
                        }
                        listOf(true, false).forEach { start ->
                            val stamp = if (start) slot.startAt else requireNotNull(slot.endAt)
                            Text(if (start) "Start" else "End", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                OutlinedButton({ pick(index, start, true) }, Modifier.weight(1f)) {
                                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(stamp)))
                                }
                                OutlinedButton({ pick(index, start, false) }) {
                                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(stamp)))
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            BLOCK_LENGTHS.forEach { minutes ->
                                FilterChip(length == minutes.toLong(), { update(index, false, slot.startAt + minutes * 60_000L) },
                                    { Text(formatStudyMinutes(minutes.toLong())) })
                            }
                        }
                        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            OutlinedButton({
                val last = slots.maxBy { requireNotNull(it.endAt) }
                val start = requireNotNull(last.endAt) + 15 * 60_000L
                slots = slots + FocalStudyInterval(start, start + (requireNotNull(last.endAt) - last.startAt).coerceIn(15 * 60_000L, 120 * 60_000L))
            }, enabled = slots.size < 100, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8))
                Text("Add block after a 15-minute break")
            }
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Plan notes") }, minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            OutlinedTextField(topics, { topics = it }, Modifier.fillMaxWidth(), label = { Text("Topics (one per line)") }, minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (problems.isNotEmpty()) Text("Fix the highlighted ${if (problems.size == 1) "block" else "blocks"} to save.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(::save, enabled = problems.isEmpty() && !stale,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) { Text("Save plan") }
            if (onDelete != null || onDuplicate != null) Row {
                onDuplicate?.let { TextButton(it) { Text("Duplicate") } }
                Spacer(Modifier.weight(1f))
                onDelete?.let { TextButton({ confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete plan") } }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, modifier = Modifier.guardUiTouches(),
        title = { Text("Delete “${entry.title.ifBlank { "this plan" }}”?") }, text = { Text("This removes the planned session from Folio and Focal.") },
        confirmButton = { TextButton({ onDelete?.invoke(); confirmDelete = false; onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep plan") } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, modifier = Modifier.guardUiTouches(),
        title = { Text("Discard your changes?") }, text = { Text("Your edits to this plan haven’t been saved.") },
        confirmButton = { TextButton({ confirmClose = false; onDismiss() }) { Text("Discard") } },
        dismissButton = { TextButton({ confirmClose = false }) { Text("Keep editing") } })
}
