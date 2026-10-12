@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

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
    val saver = listSaver<List<FocalStudyInterval>, Long>(
        save = { slots -> slots.flatMap { listOf(it.startAt, requireNotNull(it.endAt)) } },
        restore = { values -> values.chunked(2).map { FocalStudyInterval(it[0], it[1]) } })
    var slots by rememberSaveable(entry.id, stateSaver = saver) { mutableStateOf(focalPlannedSlots(entry).ifEmpty {
        val start = now / 60_000L * 60_000L
        listOf(FocalStudyInterval(start, start + 30 * 60_000L))
    }) }
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
    FolioPanel(if (onDelete == null) "Plan study" else "Edit planned study", onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            OutlinedTextField(title, { title = it.take(512) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
            FocalSubjectPicker(subjects, subject) { subject = it }
            Text("${slots.sumOf { ((it.endAt ?: it.startAt) - it.startAt).coerceAtLeast(0L) } / 60_000L} minutes planned",
                style = MaterialTheme.typography.titleSmall)
            Text("Time blocks describe your plan. Only running the timer records time studied.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                TextButton({
                    val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                    val first = Calendar.getInstance().apply { timeInMillis = slots.minOf { it.startAt }; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                    val delta = day.timeInMillis - first.timeInMillis
                    slots = slots.map { FocalStudyInterval(it.startAt + delta, requireNotNull(it.endAt) + delta) }
                }) { Text("Move to today") }
                TextButton({
                    val delta = now / 60_000L * 60_000L - slots.minOf { it.startAt }
                    slots = slots.map { FocalStudyInterval(it.startAt + delta, requireNotNull(it.endAt) + delta) }
                }) { Text("Move to now") }
            }
            slots.forEachIndexed { index, slot ->
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                        Row {
                            Text("Block ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            if (slots.size > 1) TextButton({ slots = slots.filterIndexed { position, _ -> position != index } }) { Text("Remove") }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            listOf(15, 30, 45, 60).forEach { minutes ->
                                TextButton({ update(index, false, slot.startAt + minutes * 60_000L) }, Modifier.weight(1f)) { Text("${minutes}m") }
                            }
                        }
                    }
                }
            }
            OutlinedButton({
                val start = slots.maxOf { requireNotNull(it.endAt) } + 15 * 60_000L
                slots = slots + FocalStudyInterval(start, start + 30 * 60_000L)
            }, enabled = slots.size < 100, modifier = Modifier.fillMaxWidth()) { Text("Add block after a 15-minute break") }
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Plan notes") }, minLines = 2)
            OutlinedTextField(topics, { topics = it }, Modifier.fillMaxWidth(), label = { Text("Topics (one per line)") }, minLines = 2)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button({
                runCatching {
                    require(entry.revision == openedRevision) { "This plan changed while you were editing. Close and reopen it before saving." }
                    onSave(title, subject, description, topics.lines(), slots)
                }
                    .onSuccess { if (it) onDismiss() else error = "Could not save on this device. Check free storage and try again." }
                    .onFailure { error = it.message ?: "Could not save this plan." }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) { Text("Save plan") }
            if (onDelete != null || onDuplicate != null) Row {
                onDuplicate?.let { TextButton(it) { Text("Duplicate") } }
                onDelete?.let { TextButton({ confirmDelete = true }) { Text("Delete plan") } }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, modifier = Modifier.guardUiTouches(),
        title = { Text("Delete this plan?") }, text = { Text("This removes the planned session from Folio and Focal.") },
        confirmButton = { TextButton({ onDelete?.invoke(); confirmDelete = false; onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep plan") } })
}
