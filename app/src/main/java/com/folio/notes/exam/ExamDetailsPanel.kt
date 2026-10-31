@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.exam

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * The tags behind one notebook: subject, year, company, type, unit, difficulty, marks, status,
 * quick labels and the date of the real exam. Everything is optional; Save writes what is set.
 * Attempts live on the model, not in this panel: recording and deleting go through the callbacks.
 */
@Composable
fun ExamDetailsPanel(
    note: Notebook, onDismiss: () -> Unit, onSave: (ExamTags) -> Unit,
    onRecordMark: (ExamAttempt) -> Unit, onDeleteAttempt: (ExamAttempt) -> Unit,
    /** Seconds a just-stopped timed sitting ran for, offered as the default on the next mark. */
    suggestedSeconds: Int? = null,
    /** Records the mark and opens the Focal save review; null hides the option. */
    onRecordAndSave: ((ExamAttempt) -> Unit)? = null
) {
    val exam = note.exam
    var subject by rememberSaveable(note.id) { mutableStateOf(exam.subject?.name) }
    val subjectValue: VceSubject? = subject?.let { VceSubject.safeValueOf(it) }
    var subjectText by rememberSaveable(note.id) { mutableStateOf(exam.subjectText) }
    var year by rememberSaveable(note.id) { mutableStateOf(exam.year?.toString() ?: "") }
    var company by rememberSaveable(note.id) { mutableStateOf(exam.company) }
    var type by rememberSaveable(note.id) { mutableStateOf(exam.type?.name) }
    val typeValue: ExamType? = type?.let { ExamType.safeValueOf(it) }
    var unit by rememberSaveable(note.id) { mutableStateOf(exam.unit?.toString() ?: "") }
    var difficulty by rememberSaveable(note.id) { mutableStateOf(exam.difficulty) }
    var marksTotal by rememberSaveable(note.id) { mutableStateOf(exam.marksTotal?.toString() ?: "") }
    var status by rememberSaveable(note.id) { mutableStateOf(exam.status.name) }
    val statusValue: ExamStatus = ExamStatus.safeValueOf(status) ?: ExamStatus.TO_DO
    var tags by rememberSaveable(note.id) { mutableStateOf(exam.tags.map { it.name }.sorted()) }
    val tagsValue: Set<ExamTagType> = tags.mapNotNull { name -> ExamTagType.entries.find { it.name == name } }.toSet()
    var examDate by rememberSaveable(note.id) { mutableStateOf(exam.examDate) }
    var scoreDialog by rememberSaveable { mutableStateOf(false) }
    var datePicker by rememberSaveable { mutableStateOf(false) }

    var deleteAttemptId by rememberSaveable(note.id) { mutableStateOf<String?>(null) }
    var discard by rememberSaveable(note.id) { mutableStateOf(false) }
    val validYear = validExamYear(year)
    val validUnit = validExamUnit(unit)
    val validMarks = validExamTotal(marksTotal)
    val canSave = validYear && validUnit && validMarks

    fun currentTags(): ExamTags = ExamTags(
        subject = subjectValue, subjectText = subjectText.trim(),
        year = year.toIntOrNull(), company = company.trim(), type = typeValue,
        unit = unit.toIntOrNull(), difficulty = difficulty,
        marksTotal = marksTotal.toIntOrNull(), status = statusValue, tags = tagsValue, examDate = examDate
    )
    fun saveAndDismiss() {
        if (!canSave) return
        onSave(currentTags())
        onDismiss()
    }

    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val yearSuggestions = remember(thisYear) { listOf(thisYear, thisYear - 1, thisYear - 2).map { it.toString() } }
    val companySuggestions = remember { listOf("VCAA", "NEAP", "TSSM", "Insight", "Heffernan", "Edrolo") }

    FolioPanel(title = "Exam details", onDismissRequest = onDismiss,
        canDismiss = { if (!canSave || currentTags() != exam) { discard = true; false } else true }) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = FolioSpacing.dp24).padding(top = FolioSpacing.dp4, bottom = FolioSpacing.dp12),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)
            ) {
                Text("Tag this notebook so the library can group and filter it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Subject", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    FilterChip(
                        selected = subject == null,
                        onClick = { subject = null },
                        label = { Text("Custom") }
                    )
                    VceSubject.entries.forEach { option ->
                        SubjectChip(option, selected = subjectValue == option, onClick = { subject = option.name })
                    }
                }
                if (subject == null) {
                    OutlinedTextField(
                        subjectText, { subjectText = it.take(60) },
                        Modifier.fillMaxWidth(), label = { Text("Custom subject") },
                        placeholder = { Text("e.g. Indonesian, Data Analytics") },
                        supportingText = { Text("Leave blank for no subject.") },
                        singleLine = true
                    )
                }
                Text("Paper", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                    OutlinedTextField(
                        year, { year = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Year") }, placeholder = { Text("2022") },
                        isError = !validYear, supportingText = { if (!validYear) Text("Use a four-digit year.") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        unit, { unit = it.filter(Char::isDigit).take(1) },
                        Modifier.weight(1f), label = { Text("Unit") }, placeholder = { Text("3") },
                        isError = !validUnit, supportingText = { if (!validUnit) Text("Use 1–4 or leave blank.") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        marksTotal, { marksTotal = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Marks") }, placeholder = { Text("40") },
                        isError = !validMarks, supportingText = { if (!validMarks) Text("Use 1–9999 or leave blank.") },
                        keyboardActions = KeyboardActions(onDone = { saveAndDismiss() }),
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    yearSuggestions.forEach { suggestion ->
                        FilterChip(year == suggestion, { year = if (year == suggestion) "" else suggestion }, { Text(suggestion) })
                    }
                }
                OutlinedTextField(
                    company, { company = it.take(40) },
                    Modifier.fillMaxWidth(), label = { Text("Company / source") },
                    placeholder = { Text("VCAA, NEAP, TSSM, Insight…") }, singleLine = true
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    companySuggestions.forEach { suggestion ->
                        FilterChip(
                            selected = company.trim().equals(suggestion, ignoreCase = true),
                            onClick = { company = if (company.trim().equals(suggestion, ignoreCase = true)) "" else suggestion },
                            label = { Text(suggestion) }
                        )
                    }
                }
                Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    ExamType.entries.forEach { option ->
                        FilterChip(typeValue == option, { type = if (typeValue == option) null else option.name }, { Text(option.label) })
                    }
                }
                Text("Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    ExamStatus.entries.forEach { option ->
                        FilterChip(statusValue == option, { status = option.name }, { Text(option.label) })
                    }
                }
                Text("Difficulty", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    (1..3).forEach { stars ->
                        FilterChip(
                            selected = difficulty == stars,
                            onClick = { difficulty = if (difficulty == stars) null else stars },
                            label = { Text("★".repeat(stars)) }
                        )
                    }
                }
                Text("Labels", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    ExamTagType.entries.forEach { option ->
                        val selected = option.name in tags
                        FilterChip(
                            selected,
                            {
                                tags = if (selected) tags - option.name else tags + option.name
                            },
                            { Text(option.label) }
                        )
                    }
                }
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Exam date", style = MaterialTheme.typography.titleSmall)
                            Text(
                                examDate?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) } ?: "Count down to the real sitting",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (examDate != null) TextButton({ examDate = null }, shapes = ButtonDefaults.shapes()) { Text("Clear") }
                        FilledTonalButton({ datePicker = true }, shapes = ButtonDefaults.shapes()) {
                            Text(if (examDate == null) "Set date" else "Change")
                        }
                    }
                }
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Marks", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (note.attempts.isEmpty()) "No marks recorded yet" else "${note.attempts.size} recorded" +
                                        (note.bestScore?.let { " · best ${(it * 100).roundToInt()}%" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton({ scoreDialog = true }, enabled = validMarks, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Rounded.Grading, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(FolioSpacing.dp8))
                                Text("Record a mark")
                            }
                        }
                        val sortedAttempts = remember(note.attempts) { note.attempts.sortedByDescending { it.date } }
                        sortedAttempts.forEach { attempt ->
                            val share = attempt.share
                            Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            listOfNotNull(
                                                attempt.score.toString() + (attempt.total?.let { "/$it" } ?: ""),
                                                share?.let { "${(it * 100).roundToInt()}%" },
                                                attempt.takeIf { it.timed }?.let { durationLabel(it.secondsTaken) },
                                                compactDate(attempt.date)
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    IconButton({ deleteAttemptId = attempt.id }, shapes = IconButtonDefaults.shapes()) {
                                        Icon(Icons.Rounded.Close, "Delete this attempt", Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button(::saveAndDismiss, enabled = canSave, shapes = ButtonDefaults.shapes()) { Text("Save") }
            }
        }
    }
    deleteAttemptId?.let { id ->
        AlertDialog(onDismissRequest = { deleteAttemptId = null }, title = { Text("Delete recorded mark?") },
            text = { Text("This removes the mark from your history. Notebook pages are kept.") },
            confirmButton = { TextButton({
                note.attempts.find { it.id == id }?.let(onDeleteAttempt)
                deleteAttemptId = null
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleteAttemptId = null }) { Text("Keep") } })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard exam detail changes?") },
        text = { Text("Your unsaved details will be lost. Recorded marks are saved separately.") },
        confirmButton = { TextButton(onDismiss) { Text("Discard") } },
        dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
    if (scoreDialog) {
        ScoreDialog(
            total = marksTotal.toIntOrNull(),
            defaultSeconds = suggestedSeconds,
            onDismiss = { scoreDialog = false },
            onRecord = { score, total, seconds, timed ->
                // The score dialog always asks for a total, so the attempt knows its own share.
                onRecordMark(ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed))
                scoreDialog = false
            },
            onRecordAndSave = onRecordAndSave?.let { save ->
                { score: Int, total: Int?, seconds: Int?, timed: Boolean ->
                    save(ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed))
                    scoreDialog = false
                }
            }
        )
    }
    if (datePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = examDate ?: Calendar.getInstance().timeInMillis
        )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton({
                    pickerState.selectedDateMillis?.let { examDate = it }
                    datePicker = false
                },
                    shapes = ButtonDefaults.shapes()) { Text("Set date") }
            },
            dismissButton = { TextButton({ datePicker = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}
