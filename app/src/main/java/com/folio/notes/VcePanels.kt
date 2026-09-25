@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import java.util.Calendar
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val compactDateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
private fun compactDate(time: Long): String = compactDateFormat.get()!!.format(Date(time))

private fun durationLabel(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "—"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "$hours h ${minutes} min" else if (minutes > 0) "$minutes min" else "${seconds}s"
}

/** The subject's band colour, used by chips and cover badges. */
fun subjectColor(subject: VceSubject?): Color =
    subject?.let { Color(it.color) } ?: Color(0xFF606A60)

/** A small tinted label for a subject, used in filter rows and on cards. */
@Composable
fun SubjectChip(subject: VceSubject?, selected: Boolean, onClick: () -> Unit) {
    val color = subjectColor(subject)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color else color.copy(alpha = .12f),
        contentColor = if (selected) Color.White else color,
        border = if (selected) BorderStroke(2.dp, Color.White.copy(alpha = .6f)) else null
    ) {
        Text(
            subject?.label ?: "Other",
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Cover badges: subject, score, year and redo count, kept small enough for a shelf card. */
@Composable
fun ExamBadges(note: Notebook, modifier: Modifier = Modifier, redoCount: Int = 0) {
    val exam = note.exam
    if (!exam.isTagged && note.attempts.isEmpty() && redoCount == 0) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        exam.subject?.let { subject ->
            BadgePill(subject.label, subjectColor(subject), Color.White)
        }
        note.bestScore?.let { share ->
            val percent = (share * 100).roundToInt()
            val color = when {
                percent >= 80 -> Color(0xFF2F6F4E)
                percent >= 65 -> Color(0xFF8C7326)
                else -> Color(0xFF8C3A2B)
            }
            BadgePill("$percent%", color, Color.White)
        }
        exam.year?.let { BadgePill(it.toString(), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant) }
        exam.company.takeIf { it.isNotBlank() }?.let { BadgePill(it, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant) }
        if (redoCount > 0) BadgePill("$redoCount to redo", Color(0xFF8C3A2B).copy(alpha = .15f), Color(0xFF8C3A2B))
    }
}

@Composable
private fun BadgePill(text: String, background: Color, content: Color) {
    Surface(shape = RoundedCornerShape(7.dp), color = background, contentColor = content) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

// ---- Exam details editor ---------------------------------------------------------------------------

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
    suggestedSeconds: Int? = null
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

    fun currentTags(): ExamTags = ExamTags(
        subject = subjectValue, subjectText = subjectText.trim(),
        year = year.toIntOrNull(), company = company.trim(), type = typeValue,
        unit = unit.toIntOrNull()?.coerceIn(1, 4), difficulty = difficulty,
        marksTotal = marksTotal.toIntOrNull(), status = statusValue, tags = tagsValue, examDate = examDate
    )
    fun saveAndDismiss() {
        onSave(currentTags())
        onDismiss()
    }

    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val yearSuggestions = remember(thisYear) { listOf(thisYear, thisYear - 1, thisYear - 2).map { it.toString() } }
    val companySuggestions = remember { listOf("VCAA", "NEAP", "TSSM", "Insight", "Heffernan", "Edrolo") }

    FolioPanel(title = "Exam details", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Tag this notebook so the library can group and filter it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Subject", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        year, { year = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Year") }, placeholder = { Text("2022") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        unit, { unit = it.filter(Char::isDigit).take(1) },
                        Modifier.weight(1f), label = { Text("Unit") }, placeholder = { Text("3") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        marksTotal, { marksTotal = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Marks") }, placeholder = { Text("40") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    yearSuggestions.forEach { suggestion ->
                        FilterChip(year == suggestion, { year = if (year == suggestion) "" else suggestion }, { Text(suggestion) })
                    }
                }
                OutlinedTextField(
                    company, { company = it.take(40) },
                    Modifier.fillMaxWidth(), label = { Text("Company / source") },
                    placeholder = { Text("VCAA, NEAP, TSSM, Insight…") }, singleLine = true
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    companySuggestions.forEach { suggestion ->
                        FilterChip(
                            selected = company.trim().equals(suggestion, ignoreCase = true),
                            onClick = { company = if (company.trim().equals(suggestion, ignoreCase = true)) "" else suggestion },
                            label = { Text(suggestion) }
                        )
                    }
                }
                Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExamType.entries.forEach { option ->
                        FilterChip(typeValue == option, { type = if (typeValue == option) null else option.name }, { Text(option.label) })
                    }
                }
                Text("Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExamStatus.entries.forEach { option ->
                        FilterChip(statusValue == option, { status = option.name }, { Text(option.label) })
                    }
                }
                Text("Difficulty", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..3).forEach { stars ->
                        FilterChip(
                            selected = difficulty == stars,
                            onClick = { difficulty = if (difficulty == stars) null else stars },
                            label = { Text("★".repeat(stars)) }
                        )
                    }
                }
                Text("Labels", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Exam date", style = MaterialTheme.typography.titleSmall)
                            Text(
                                examDate?.let { compactDate(it) } ?: "Count down to the real sitting",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (examDate != null) TextButton({ examDate = null }, shapes = ButtonDefaults.shapes()) { Text("Clear") }
                        FilledTonalButton({ datePicker = true }, shapes = ButtonDefaults.shapes()) {
                            Text(if (examDate == null) "Set date" else "Change")
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Marks", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (note.attempts.isEmpty()) "No marks recorded yet" else "${note.attempts.size} recorded" +
                                        (note.bestScore?.let { " · best ${(it * 100).roundToInt()}%" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton({ scoreDialog = true }, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Rounded.Grading, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Record a mark")
                            }
                        }
                        val sortedAttempts = remember(note.attempts) { note.attempts.sortedBy { it.date } }
                        sortedAttempts.forEach { attempt ->
                            val share = attempt.share
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                    IconButton({ onDeleteAttempt(attempt) }, shapes = IconButtonDefaults.shapes()) {
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
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button(::saveAndDismiss, shapes = ButtonDefaults.shapes()) { Text("Save") }
            }
        }
    }
    if (scoreDialog) {
        ScoreDialog(
            total = marksTotal.toIntOrNull(),
            defaultSeconds = suggestedSeconds,
            onDismiss = { scoreDialog = false },
            onRecord = { score, total, seconds, timed ->
                // The score dialog always asks for a total, so the attempt knows its own share.
                onRecordMark(ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed))
                scoreDialog = false
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

/**
 * Assigns exam tags to many notebooks at once. Each section is opt-in: only ticked sections are
 * written, so subject can be assigned without touching the year, and the year without the company.
 * An empty value inside a ticked section clears that field, which is how a batch removes tags.
 */
@Composable
fun BatchExamTagsPanel(
    count: Int,
    onDismiss: () -> Unit,
    onApply: ((ExamTags) -> ExamTags) -> Unit
) {
    var changeSubject by rememberSaveable { mutableStateOf(false) }
    var newSubjectName by rememberSaveable { mutableStateOf<String?>(null) }
    val newSubject: VceSubject? = newSubjectName?.let { VceSubject.safeValueOf(it) }
    var customSubject by rememberSaveable { mutableStateOf("") }
    var changeYear by rememberSaveable { mutableStateOf(false) }
    var newYear by rememberSaveable { mutableStateOf("") }
    var changeCompany by rememberSaveable { mutableStateOf(false) }
    var newCompany by rememberSaveable { mutableStateOf("") }
    var changeType by rememberSaveable { mutableStateOf(false) }
    var newTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    val newType: ExamType? = newTypeName?.let { ExamType.safeValueOf(it) }
    var changeStatus by rememberSaveable { mutableStateOf(false) }
    var newStatusName by rememberSaveable { mutableStateOf(ExamStatus.TO_DO.name) }
    val newStatus: ExamStatus = ExamStatus.safeValueOf(newStatusName) ?: ExamStatus.TO_DO
    val canApply = changeSubject || changeYear || changeCompany || changeType || changeStatus
    val companySuggestions = remember { listOf("VCAA", "NEAP", "TSSM", "Insight", "Heffernan", "Edrolo") }
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR).toString() }

    fun buildTransform(): (ExamTags) -> ExamTags {
        val patch = ExamTagsBatch(
            changeSubject = changeSubject, subject = newSubject, subjectText = customSubject,
            changeYear = changeYear, year = newYear.toIntOrNull(),
            changeCompany = changeCompany, company = newCompany,
            changeType = changeType, type = newType,
            changeStatus = changeStatus, status = newStatus
        )
        return patch::applyTo
    }

    FolioPanel(title = "Assign exam details", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Apply to $count notebook${if (count == 1) "" else "s"}. Only ticked sections change; the rest stay as they are.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            BatchSection(
                checked = changeSubject,
                onChecked = { changeSubject = it },
                title = "Subject",
                summary = if (!changeSubject) "Unchanged" else newSubject?.label ?: customSubject.trim().ifBlank { "Cleared" }
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(newSubject == null, { newSubjectName = null }, { Text("Custom") })
                    VceSubject.entries.forEach { option ->
                        SubjectChip(option, selected = newSubject == option, onClick = { newSubjectName = option.name })
                    }
                }
                if (newSubject == null) {
                    OutlinedTextField(
                        customSubject, { customSubject = it.take(60) },
                        Modifier.fillMaxWidth(), label = { Text("Custom subject (empty clears)") },
                        placeholder = { Text("e.g. Indonesian") }, singleLine = true
                    )
                }
            }
            BatchSection(
                checked = changeYear,
                onChecked = { changeYear = it },
                title = "Year",
                summary = if (!changeYear) "Unchanged" else newYear.ifBlank { "Cleared" }
            ) {
                OutlinedTextField(
                    newYear, { newYear = it.filter(Char::isDigit).take(4) },
                    Modifier.fillMaxWidth(), label = { Text("Year (empty clears)") },
                    placeholder = { Text("2022") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(thisYear, "2022", "2021").distinct().forEach { suggestion ->
                        FilterChip(newYear == suggestion, { newYear = suggestion }, { Text(suggestion) })
                    }
                }
            }
            BatchSection(
                checked = changeCompany,
                onChecked = { changeCompany = it },
                title = "Company / source",
                summary = if (!changeCompany) "Unchanged" else newCompany.trim().ifBlank { "Cleared" }
            ) {
                OutlinedTextField(
                    newCompany, { newCompany = it.take(40) },
                    Modifier.fillMaxWidth(), label = { Text("Company (empty clears)") },
                    placeholder = { Text("VCAA, NEAP, TSSM, Insight…") }, singleLine = true
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    companySuggestions.forEach { suggestion ->
                        FilterChip(newCompany.trim().equals(suggestion, ignoreCase = true), { newCompany = suggestion }, { Text(suggestion) })
                    }
                }
            }
            BatchSection(
                checked = changeType,
                onChecked = { changeType = it },
                title = "Type",
                summary = if (!changeType) "Unchanged" else newType?.label ?: "Cleared"
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(newType == null, { newTypeName = null }, { Text("None") })
                    ExamType.entries.forEach { option ->
                        FilterChip(newType == option, { newTypeName = if (newType == option) null else option.name }, { Text(option.label) })
                    }
                }
            }
            BatchSection(
                checked = changeStatus,
                onChecked = { changeStatus = it },
                title = "Status",
                summary = if (!changeStatus) "Unchanged" else newStatus.label
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExamStatus.entries.forEach { option ->
                        FilterChip(newStatus == option, { newStatusName = option.name }, { Text(option.label) })
                    }
                }
            }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button({ onApply(buildTransform()) }, enabled = canApply, shapes = ButtonDefaults.shapes()) { Text("Apply to $count") }
            }
        }
    }
}

@Composable
private fun BatchSection(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, onChecked)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (checked) content()
        }
    }
}

/** One mark entry: score out of a total, optional time taken and a timed flag. */
@Composable
fun ScoreDialog(
    total: Int?, defaultSeconds: Int?, onDismiss: () -> Unit,
    onRecord: (score: Int, total: Int?, seconds: Int?, timed: Boolean) -> Unit
) {
    var score by rememberSaveable { mutableStateOf("") }
    var totalText by rememberSaveable(total) { mutableStateOf(total?.toString() ?: "") }
    var minutes by rememberSaveable { mutableStateOf(defaultSeconds?.let { (it / 60).coerceAtLeast(1) }?.toString() ?: "") }
    var timed by rememberSaveable { mutableStateOf(defaultSeconds != null) }
    val parsedScore = score.toIntOrNull()
    val parsedTotal = totalText.toIntOrNull()
    val scoreFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { scoreFocus.requestFocus() }
    val scoreTooHigh = parsedScore != null && parsedTotal != null && parsedTotal > 0 && parsedScore > parsedTotal
    val canRecord = parsedScore != null && parsedTotal != null && parsedTotal > 0 && parsedScore in 0..parsedTotal
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.Grading, null) },
        title = { Text("Record a mark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        score, { score = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f).focusRequester(scoreFocus),
                        label = { Text("Score") }, placeholder = { Text("32") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        isError = scoreTooHigh,
                        supportingText = {
                            if (scoreTooHigh) Text("Can't exceed the total.")
                            else if (parsedTotal != null && parsedTotal > 0) Text("0–$parsedTotal")
                        }
                    )
                    OutlinedTextField(
                        totalText, { totalText = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Out of") }, placeholder = { Text("40") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (canRecord) onRecord(parsedScore ?: 0, parsedTotal, minutes.toIntOrNull()?.times(60), timed)
                        }),
                        supportingText = { if (total != null) Text("From exam details") }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Timed sitting", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (defaultSeconds != null) "Timer measured ${durationLabel(defaultSeconds)}."
                            else "Records the time taken against this mark.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(timed, { timed = it })
                }
                if (timed) {
                    OutlinedTextField(
                        minutes, { minutes = it.filter(Char::isDigit).take(3) },
                        Modifier.fillMaxWidth(), label = { Text("Minutes taken") }, placeholder = { Text("82") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("60", "90", "120").forEach { suggestion ->
                            FilterChip(minutes == suggestion, { minutes = suggestion }, { Text("${suggestion}m") })
                        }
                    }
                }
                if (parsedScore != null && parsedTotal != null && parsedTotal > 0) {
                    Text(
                        "${(parsedScore.toFloat() / parsedTotal * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        dismissButton = { TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        confirmButton = {
            Button(
                { onRecord(parsedScore ?: 0, parsedTotal, minutes.toIntOrNull()?.times(60), timed) },
                enabled = canRecord,
                shapes = ButtonDefaults.shapes()
            ) { Text("Record") }
        }
    )
}

// ---- Progress ---------------------------------------------------------------------------------------

/** Per-subject score roll-up, or the empty state when nothing has been marked. */
@Composable
fun ExamProgressPanel(notes: List<Notebook>, onDismiss: () -> Unit) {
    FolioPanel(title = "Progress", onDismissRequest = onDismiss) {
        ExamProgressContent(notes, Modifier.fillMaxWidth())
    }
}

/**
 * The same roll-up without the dialog wrapper, so the Library's Progress
 * destination can show it inline instead of behind another button. The Library
 * page already scrolls, so its inline use must not add a second vertical
 * scroll — nested scrolling containers crash on measure.
 */
@Composable
fun ExamProgressContent(notes: List<Notebook>, modifier: Modifier = Modifier, scrollEnabled: Boolean = true) {
    val progress = remember(notes) { subjectProgress(notes) }
    Column(
        modifier.fillMaxWidth().then(if (scrollEnabled) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            if (progress.isEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        "Tag a notebook with a subject and record a mark to see progress here.",
                        Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            progress.forEach { row ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(subjectColor(row.subject), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(row.subject?.label ?: "Other subjects", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${row.paperCount} paper${if (row.paperCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (row.averageShare == null) {
                            Text("No marks recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            ProgressRow("Average", row.averageShare)
                            row.recentShare?.let { ProgressRow("Recent attempts", it) }
                            row.bestShare?.let { ProgressRow("Best", it) }
                        }
                    }
                }
            }
            Text("Averages come from every recorded attempt; recent attempts average each paper's latest mark.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProgressRow(label: String, share: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${(share * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { share.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

// ---- Redo review ---------------------------------------------------------------------------------------

/** Every flagged page across the library, grouped by notebook, one tap from the page itself. */
@Composable
fun RedoReviewPanel(notes: List<Notebook>, onDismiss: () -> Unit, onOpen: (String, Int) -> Unit) {
    FolioPanel(title = "Redo list", onDismissRequest = onDismiss) {
        RedoReviewContent(notes, onOpen, Modifier.fillMaxWidth())
    }
}

/** The same redo queue without the dialog wrapper, for the Library's Review destination. */
@Composable
fun RedoReviewContent(notes: List<Notebook>, onOpen: (String, Int) -> Unit, modifier: Modifier = Modifier, scrollEnabled: Boolean = true, onClearFlag: ((String, String) -> Unit)? = null) {
    // O(totalPages) scan memoized: recomputing per recomposition janked the redo list.
    val flagged = remember(notes) {
        notes.flatMap { note -> note.pages.mapIndexed { index, page -> Triple(note, index, page) }.filter { it.third.redoFlag } }
    }
    val grouped = remember(flagged) { flagged.groupBy { it.first } }
    Column(
        modifier.fillMaxWidth().then(if (scrollEnabled) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            Text("Pages you marked to try again, across every notebook. Long-press a page to clear its flag, or use the page's own menu in the editor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (flagged.isEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        "Nothing to redo. Flag a page from the editor's page menu when a question goes badly.",
                        Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            grouped.forEach { (note, pages) ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        pages.forEach { (target, index, page) ->
                            var menu by remember { mutableStateOf(false) }
                            val hold = rememberLongPressGuard()
                            Box {
                                Surface(
                                    onClick = hold.click { onOpen(target.id, index) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = if (onClearFlag != null) Modifier.longPressAction(hold) { menu = true } else Modifier
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Page ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        if (page.paper == Paper.MC_SHEET) {
                                            Icon(Icons.AutoMirrored.Rounded.FactCheck, "Multiple-choice answer sheet", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open page ${index + 1}", Modifier.size(16.dp))
                                    }
                                }
                                DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
                                    DropdownMenuItem({ Text("Open page") }, { menu = false; onOpen(target.id, index) })
                                    if (onClearFlag != null) DropdownMenuItem(
                                        { Text("Clear redo flag") },
                                        { menu = false; onClearFlag(target.id, page.id) },
                                        leadingIcon = { Icon(Icons.Rounded.OutlinedFlag, null) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
}

// ---- Stopwatch -----------------------------------------------------------------------------------------

/**
 * The simple count-up stopwatch: start, pause/resume and reset. Runs independently of
 * the exam countdown timer and keeps counting only while the pages are on screen —
 * leaving the editor parks it, and the parked time never counts.
 */
@Composable
fun StopwatchPanel(
    stopwatch: StopwatchState, onDismiss: () -> Unit,
    onStart: () -> Unit, onPauseResume: () -> Unit, onReset: () -> Unit
) {
    FolioPanel(title = "Stopwatch", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (stopwatch.idle) {
                Text(
                    "Time how long the work takes: the clock counts up from zero and runs beside the exam timer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onStart, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start stopwatch")
                }
            } else {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "ELAPSED",
                            style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            stopwatch.clockText(),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            when {
                                stopwatch.paused && stopwatch.autoParked -> "Stopped on its own — resume to carry on"
                                stopwatch.paused -> "Paused"
                                else -> "The stopwatch is running"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Button(onPauseResume, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                    Icon(if (stopwatch.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (stopwatch.paused) "Resume stopwatch" else "Pause stopwatch")
                }
                OutlinedButton(onReset, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.RestartAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reset to 0:00")
                }
            }
        }
    }
}

// ---- Timer ---------------------------------------------------------------------------------------------

/** The exam-condition timer: a preset, a live countdown with reading and writing phases, and stop. */
@Composable
fun ExamTimerPanel(timer: ExamTimerState, onDismiss: () -> Unit, onStart: (ExamTimerPreset) -> Unit, onStop: (Int?) -> Unit, onAdjust: (Int) -> Unit, onSkip: () -> Unit, onPauseResume: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val timerPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    val defaultCustom = AppPrefs.timerCustomMinutes(timerPrefs.getInt(AppPrefs.TIMER_CUSTOM_MIN, AppPrefs.DEFAULT_TIMER_CUSTOM_MIN).takeIf { timerPrefs.contains(AppPrefs.TIMER_CUSTOM_MIN) })
    val defaultReading = AppPrefs.timerReadingMinutes(timerPrefs.getInt(AppPrefs.TIMER_READING_MIN, AppPrefs.DEFAULT_TIMER_READING_MIN).takeIf { timerPrefs.contains(AppPrefs.TIMER_READING_MIN) })
    val idleMinutes = AppPrefs.timerIdleMinutes(timerPrefs.getInt(AppPrefs.TIMER_IDLE_MIN, AppPrefs.DEFAULT_TIMER_IDLE_MIN).takeIf { timerPrefs.contains(AppPrefs.TIMER_IDLE_MIN) })
    var customMinutes by rememberSaveable { mutableStateOf("$defaultCustom") }
    var customPreset by remember { mutableStateOf(ExamTimerPreset.CUSTOM) }
    LaunchedEffect(timer.phase) { if (timer.phase == ExamTimerPhase.DONE) kotlinx.coroutines.delay(2500) }
    FolioPanel(title = "Exam timer", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (timer.phase) {
                ExamTimerPhase.IDLE -> {
                    Text("Sit the paper under exam conditions: reading time first, then writing time, counted down live.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ExamTimerPreset.PRESETS.filter { it != ExamTimerPreset.CUSTOM }.forEach { preset ->
                        Surface(
                            onClick = { onStart(preset) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(preset.label, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${preset.readingSeconds / 60} min reading + ${preset.writingSeconds / 60} min writing",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Rounded.PlayArrow, "Start ${preset.label}")
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Custom", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    customMinutes, { customMinutes = it.filter(Char::isDigit).take(3) },
                                    Modifier.weight(1f), label = { Text("Writing minutes") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                Button({
                                    val minutes = AppPrefs.timerCustomMinutes(customMinutes.toIntOrNull())
                                    customPreset = ExamTimerPreset("Custom · $minutes min", minutes * 60, defaultReading * 60)
                                    onStart(customPreset)
                                }, shapes = ButtonDefaults.shapes()) { Text("Start") }
                            }
                        }
                    }
                }
                ExamTimerPhase.READING, ExamTimerPhase.WRITING -> {
                    val active = timer.phase == ExamTimerPhase.WRITING
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                if (active) "WRITING TIME" else "READING TIME",
                                style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                timer.clockText(),
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                when {
                                    timer.paused && timer.autoParked -> "Stopped on its own — resume, or just keep writing"
                                    timer.paused -> "Paused — resume, or just keep writing"
                                    active -> "The clock is running"
                                    else -> "No pens yet — read the paper"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (!timer.paused && idleMinutes > 0) Text(
                                "Stops on its own after $idleMinutes min without writing",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Button(onPauseResume, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                        Icon(if (timer.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (timer.paused) "Resume timer" else "Pause timer")
                    }
                    Text("Adjust ${if (active) "writing" else "reading"} time", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(-5, -1, 1, 5).forEach { minutes ->
                            OutlinedButton({ onAdjust(minutes * 60) }, modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                shapes = ButtonDefaults.shapes()) {
                                Text("${if (minutes > 0) "+" else "−"}${kotlin.math.abs(minutes)} min")
                            }
                        }
                    }
                    FilledTonalButton(onSkip, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                        Text(if (active) "Finish writing now" else "Skip reading · start writing")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton({ onStop(null) }, shapes = ButtonDefaults.shapes()) { Text("Stop timer") }
                    }
                }
                ExamTimerPhase.DONE -> {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PENS DOWN", style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Time is up.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton({ onStop(timer.elapsedWriting()) }, shapes = ButtonDefaults.shapes()) { Text("Record the sitting") }
                    }
                }
            }
        }
    }
}
