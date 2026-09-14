package com.folio.notes

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
import androidx.compose.foundation.text.KeyboardOptions
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun compactDate(time: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(time))

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
        contentColor = if (selected) Color.White else color
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
fun ExamBadges(note: Notebook, redoCount: Int = 0, modifier: Modifier = Modifier) {
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
    suggestedSeconds: Int? = null,
    /** Timing record of that sitting, attached when the next timed mark is recorded. */
    suggestedTelemetry: ExamTelemetry? = null
) {
    val exam = note.exam
    var subject by remember { mutableStateOf(exam.subject) }
    var subjectText by rememberSaveable { mutableStateOf(exam.subjectText) }
    var year by rememberSaveable { mutableStateOf(exam.year?.toString() ?: "") }
    var company by rememberSaveable { mutableStateOf(exam.company) }
    var type by remember { mutableStateOf(exam.type) }
    var unit by rememberSaveable { mutableStateOf(exam.unit?.toString() ?: "") }
    var difficulty by remember { mutableStateOf(exam.difficulty) }
    var marksTotal by rememberSaveable { mutableStateOf(exam.marksTotal?.toString() ?: "") }
    var status by remember { mutableStateOf(exam.status) }
    var tags by remember { mutableStateOf(exam.tags) }
    var examDate by remember { mutableStateOf(exam.examDate) }
    var scoreDialog by remember { mutableStateOf(false) }
    var timingReport by remember { mutableStateOf<ExamAttempt?>(null) }

    FolioPanel(title = "Exam details", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Tag this notebook so the library can group and filter it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Subject", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SubjectChip(subject, selected = subject == null, onClick = { subject = null })
                VceSubject.entries.forEach { option -> SubjectChip(option, selected = subject == option, onClick = { subject = option }) }
            }
            if (subject == null) {
                OutlinedTextField(
                    subjectText, { subjectText = it },
                    Modifier.fillMaxWidth(), label = { Text("Other subject") },
                    placeholder = { Text("e.g. Indonesian, Data Analytics") }, singleLine = true
                )
            }
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
            OutlinedTextField(
                company, { company = it.take(40) },
                Modifier.fillMaxWidth(), label = { Text("Company / source") },
                placeholder = { Text("VCAA, NEAP, TSSM, Insight…") }, singleLine = true
            )
            Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExamType.entries.forEach { option ->
                    FilterChip(type == option, { type = if (type == option) null else option }, { Text(option.label) })
                }
            }
            Text("Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExamStatus.entries.forEach { option ->
                    FilterChip(status == option, { status = option }, { Text(option.label) })
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
                    FilterChip(option in tags, { tags = if (option in tags) tags - option else tags + option }, { Text(option.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Exam date", style = MaterialTheme.typography.titleSmall)
                    Text(
                        examDate?.let { compactDate(it) } ?: "Count down to the real sitting",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                examDate?.let { TextButton({ examDate = null }) { Text("Clear") } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Attempts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (note.attempts.isEmpty()) "No marks recorded yet" else "${note.attempts.size} recorded",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton({ scoreDialog = true }) { Text("Record a mark") }
            }
            note.attempts.sortedBy { it.date }.forEach { attempt ->
                val share = attempt.share
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
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
                        if (attempt.timed || attempt.telemetry != null) {
                            IconButton({ timingReport = attempt }) {
                                Icon(Icons.Rounded.History, "Timing report for this sitting", Modifier.size(18.dp))
                            }
                        }
                        IconButton({ onDeleteAttempt(attempt) }) {
                            Icon(Icons.Rounded.Close, "Delete this attempt", Modifier.size(16.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onDismiss) { Text("Cancel") }
                Button({
                    onSave(
                        ExamTags(
                            subject = subject, subjectText = subjectText.trim(),
                            year = year.toIntOrNull(), company = company.trim(), type = type,
                            unit = unit.toIntOrNull()?.coerceIn(1, 4), difficulty = difficulty,
                            marksTotal = marksTotal.toIntOrNull(), status = status, tags = tags, examDate = examDate
                        )
                    )
                    onDismiss()
                }) { Text("Save") }
            }
        }
    }
    if (scoreDialog) {
        ScoreDialog(
            total = marksTotal.toIntOrNull(),
            defaultSeconds = suggestedSeconds,
            telemetry = suggestedTelemetry,
            onDismiss = { scoreDialog = false },
            onRecord = { score, total, seconds, timed, telemetry ->
                // The score dialog always asks for a total, so the attempt knows its own share.
                onRecordMark(ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed, telemetry = telemetry))
                scoreDialog = false
            }
        )
    }
    timingReport?.let { attempt ->
        SittingReportPanel(note = note, attempt = attempt, onDismiss = { timingReport = null })
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
    var newSubject by remember { mutableStateOf<VceSubject?>(null) }
    var customSubject by rememberSaveable { mutableStateOf("") }
    var changeYear by rememberSaveable { mutableStateOf(false) }
    var newYear by rememberSaveable { mutableStateOf("") }
    var changeCompany by rememberSaveable { mutableStateOf(false) }
    var newCompany by rememberSaveable { mutableStateOf("") }
    var changeType by rememberSaveable { mutableStateOf(false) }
    var newType by remember { mutableStateOf<ExamType?>(null) }
    var changeStatus by rememberSaveable { mutableStateOf(false) }
    var newStatus by remember { mutableStateOf(ExamStatus.TO_DO) }
    val canApply = changeSubject || changeYear || changeCompany || changeType || changeStatus

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
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
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
                    SubjectChip(null, selected = newSubject == null, onClick = { newSubject = null })
                    VceSubject.entries.forEach { option ->
                        SubjectChip(option, selected = newSubject == option, onClick = { newSubject = option })
                    }
                }
                if (newSubject == null) {
                    OutlinedTextField(
                        customSubject, { customSubject = it.take(60) },
                        Modifier.fillMaxWidth(), label = { Text("Other subject (empty clears)") },
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
            }
            BatchSection(
                checked = changeType,
                onChecked = { changeType = it },
                title = "Type",
                summary = if (!changeType) "Unchanged" else newType?.label ?: "Cleared"
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(newType == null, { newType = null }, { Text("None") })
                    ExamType.entries.forEach { option ->
                        FilterChip(newType == option, { newType = if (newType == option) null else option }, { Text(option.label) })
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
                        FilterChip(newStatus == option, { newStatus = option }, { Text(option.label) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onDismiss) { Text("Cancel") }
                Button({ onApply(buildTransform()) }, enabled = canApply) { Text("Apply to $count") }
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
    onRecord: (score: Int, total: Int?, seconds: Int?, timed: Boolean, telemetry: ExamTelemetry?) -> Unit,
    telemetry: ExamTelemetry? = null
) {
    var score by rememberSaveable { mutableStateOf("") }
    var totalText by rememberSaveable { mutableStateOf(total?.toString() ?: "") }
    var minutes by rememberSaveable { mutableStateOf(defaultSeconds?.let { it / 60 }?.toString() ?: "") }
    var timed by rememberSaveable { mutableStateOf(defaultSeconds != null) }
    val parsedScore = score.toIntOrNull()
    val parsedTotal = totalText.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.Grading, null) },
        title = { Text("Record a mark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        score, { score = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Score") }, placeholder = { Text("32") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        totalText, { totalText = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f), label = { Text("Out of") }, placeholder = { Text("40") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Timed sitting", style = MaterialTheme.typography.titleSmall)
                        Text("Records the time taken against this mark.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(timed, { timed = it })
                }
                if (timed) {
                    OutlinedTextField(
                        minutes, { minutes = it.filter(Char::isDigit).take(3) },
                        Modifier.fillMaxWidth(), label = { Text("Minutes taken") }, placeholder = { Text("82") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                if (parsedScore != null && parsedTotal != null && parsedTotal > 0) {
                    Text(
                        "${(parsedScore.toFloat() / parsedTotal * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                { onRecord(parsedScore ?: 0, parsedTotal, minutes.toIntOrNull()?.times(60), timed, if (timed) telemetry else null) },
                enabled = parsedScore != null && parsedTotal != null && parsedTotal > 0 && parsedScore in 0..parsedTotal
            ) { Text("Record") }
        }
    )
}

// ---- Exam sets ---------------------------------------------------------------------------------------

/** A subject/year/company pair with separate paper links and scores. */
@Composable
fun ExamSetCard(group: ExamSetGroup, openSet: () -> Unit, openNote: (Notebook) -> Unit) {
    Surface(onClick = openSet, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.set.autoName(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            group.set.summaryLine().ifBlank { null },
                            "${group.notes.size} notebook${if (group.notes.size == 1) "" else "s"}",
                            group.attemptCount.takeIf { it > 0 }?.let { "${group.attemptCount} attempt${if (it == 1) "" else "s"}" }
                        ).joinToString(" · ").ifBlank { "No notebooks yet" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("${group.pairedPaperCount}/2 papers", style = MaterialTheme.typography.labelLarge)
            }
            listOf(ExamType.EXAM_1, ExamType.EXAM_2).forEach { type ->
                val papers = group.papers(type)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(type.label, style = MaterialTheme.typography.labelLarge)
                        group.bestShare(type)?.let { share ->
                            Text("Best ${(share * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (papers.isEmpty()) {
                        Text("Not added yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        ExamSetMembers(papers, openNote)
                    }
                }
            }
            val supporting = group.notes.filter { it.exam.type !in listOf(ExamType.EXAM_1, ExamType.EXAM_2) }
            if (supporting.isNotEmpty()) {
                Text("Other notebooks", style = MaterialTheme.typography.labelMedium)
                ExamSetMembers(supporting, openNote)
            }
        }
    }
}

@Composable
private fun ExamSetMembers(notes: List<Notebook>, openNote: (Notebook) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.forEach { note ->
            Surface(onClick = { openNote(note) }, shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(note.title, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/**
 * Create a paired set from shared tags, delete it, or open its papers.
 * Additional notebooks can be assigned from the library selection actions.
 */
@Composable
fun ExamSetsPanel(
    groups: List<ExamSetGroup>,
    onDismiss: () -> Unit,
    onCreate: (String, VceSubject?, Int?, String) -> Unit,
    onDelete: (ExamSet) -> Unit,
    openNote: (Notebook) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var subject by remember { mutableStateOf<VceSubject?>(null) }
    var year by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<ExamSet?>(null) }

    FolioPanel(title = "Exam sets", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Keep Exam 1 and Exam 2 together for the same subject, year and company. Matching ungrouped papers are added when you create the set.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            groups.forEach { group ->
                ExamSetCard(group, openSet = {}, openNote = { openNote(it) })
                if (group.notes.isEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        Text("Select notebooks, then Exam set → this one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ confirmDelete = group.set }) { Text("Delete set") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton({ confirmDelete = group.set }) { Text("Delete set") }
                    }
                }
            }
            if (groups.isEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text("No sets yet. Create one below, or select notebooks on the shelf and choose Exam set.",
                        Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Text("New set", style = MaterialTheme.typography.titleMedium)
            Text("Choose a subject, four-digit year and company. If a set already matches, its ungrouped papers will be added to it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Set name (optional)") }, placeholder = { Text("VCAA 2022 Methods") }, singleLine = true)
            Text("Subject", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SubjectChip(subject, selected = subject == null, onClick = { subject = null })
                VceSubject.entries.forEach { option -> SubjectChip(option, selected = subject == option, onClick = { subject = option }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    year, { year = it.filter(Char::isDigit).take(4) },
                    Modifier.weight(1f), label = { Text("Year") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(company, { company = it.take(40) }, Modifier.weight(1f), label = { Text("Company") }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Button(
                    { onCreate(name, subject, year.toIntOrNull(), company); name = ""; year = ""; company = "" },
                    enabled = subject != null && (year.toIntOrNull() ?: 0) in 1000..9999 && company.isNotBlank()
                ) { Text("Create set") }
            }
        }
    }
    confirmDelete?.let { set ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${set.autoName()}\"?") },
            text = { Text("The notebooks in this set stay in your library; only the grouping is removed.") },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
            confirmButton = { TextButton({ onDelete(set); confirmDelete = null }) { Text("Delete set") } }
        )
    }
}

// ---- Progress ---------------------------------------------------------------------------------------

/** Per-subject score roll-up, or the empty state when nothing has been marked. */
@Composable
fun ExamProgressPanel(notes: List<Notebook>, onDismiss: () -> Unit) {
    val progress = remember(notes) { subjectProgress(notes) }
    FolioPanel(title = "Progress", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
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
    val flagged = notes.flatMap { note -> note.pages.mapIndexed { index, page -> Triple(note, index, page) }.filter { it.third.redoFlag } }
    FolioPanel(title = "Redo list", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Pages you marked to try again, across every notebook. Clear a flag from the page's own menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (flagged.isEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        "Nothing to redo. Flag a page from the editor's page menu when a question goes badly.",
                        Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            flagged.groupBy { it.first }.forEach { (note, pages) ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        pages.forEach { (target, index, page) ->
                            Surface(
                                onClick = { onOpen(target.id, index) },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Page ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    if (page.paper == Paper.MC_SHEET) {
                                        Icon(Icons.AutoMirrored.Rounded.FactCheck, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open page ${index + 1}", Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Timer ---------------------------------------------------------------------------------------------

/** The exam-condition timer: a preset, a live countdown with reading and writing phases, and stop. */
@Composable
fun ExamTimerPanel(timer: ExamTimerState, onDismiss: () -> Unit, onStart: (ExamTimerPreset) -> Unit, onStop: (Int?) -> Unit, onAdjust: (Int) -> Unit, onSkip: () -> Unit) {
    var customMinutes by rememberSaveable { mutableStateOf("90") }
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
                                    val minutes = (customMinutes.toIntOrNull() ?: 90).coerceIn(1, 480)
                                    customPreset = ExamTimerPreset("Custom · $minutes min", minutes * 60, 15 * 60)
                                    onStart(customPreset)
                                }) { Text("Start") }
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
                                if (active) "The clock is running" else "No pens yet — read the paper",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text("Adjust ${if (active) "writing" else "reading"} time", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(-5, -1, 1, 5).forEach { minutes ->
                            OutlinedButton({ onAdjust(minutes * 60) }, Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                                Text("${if (minutes > 0) "+" else "−"}${kotlin.math.abs(minutes)} min")
                            }
                        }
                    }
                    FilledTonalButton(onSkip, Modifier.fillMaxWidth()) {
                        Text(if (active) "Finish writing now" else "Skip reading · start writing")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton({ onStop(null) }) { Text("Stop timer") }
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
                        TextButton({ onStop(timer.elapsedWriting()) }) { Text("Record the sitting") }
                    }
                }
            }
        }
    }
}

// ---- Sitting timing report + replay ------------------------------------------------------------------

private fun msLabel(ms: Long): String {
    if (ms <= 0L) return "—"
    val seconds = (ms / 1000L).toInt()
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "$seconds s"
    }
}

/** Offset into a sitting as "m:ss" (or "h:mm:ss"), for replay and idle ranges. */
private fun offsetLabel(ms: Long): String {
    val total = (ms / 1000L).toInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds)
}

/**
 * How a timed sitting went: writing and dwell time per page, idle stretches, the final rush,
 * and a replay scrubber that steps through the paper as it was written. Strokes are timestamped
 * as they land and page visits while the timer runs, so older sittings may show writing time
 * without dwell.
 */
@Composable
fun SittingReportPanel(note: Notebook, attempt: ExamAttempt, onDismiss: () -> Unit) {
    val analysis = remember(note, attempt) { analyzeSitting(note, attempt) }
    FolioPanel(title = "Timing report", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (analysis == null) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        "No timing data for this sitting. Strokes are timestamped while you write and pages while the timer runs, so future timed sittings will report here.",
                        Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }
            Text(
                "Recorded ${compactDate(attempt.date)} · ${msLabel(analysis.durationMs)} sitting",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ReportCard("Sitting") {
                ReportRow("Strokes written", analysis.totalStrokes.toString())
                ReportRow("Active writing", msLabel(analysis.activeMs))
                ReportRow(
                    "Idle",
                    if (analysis.idleGaps.isEmpty()) "No gaps over a minute" else "${msLabel(analysis.idleMs)} across ${analysis.idleGaps.size}"
                )
            }
            ReportCard("Time per page") {
                if (analysis.pages.isEmpty()) {
                    Text("Nothing was written during this sitting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val peak = analysis.pages.maxOfOrNull { it.activeMs }?.coerceAtLeast(1L) ?: 1L
                analysis.pages.forEach { page ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row {
                            Text("Page ${page.pageIndex + 1}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${msLabel(page.activeMs)} writing · ${page.strokes} strokes",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (page.activeMs.toFloat() / peak).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        if (page.dwellMs > 0L) {
                            Text(
                                "On page ${msLabel(page.dwellMs)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if ((attempt.telemetry?.visits.orEmpty()).isEmpty()) {
                    Text(
                        "Page dwell needs the visit log — sittings from before this update show writing time only.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            ReportCard("Idle periods") {
                if (analysis.idleGaps.isEmpty()) {
                    Text("Steady writing — no gap over a minute.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                analysis.idleGaps.forEach { gap ->
                    val from = gap.startMs - analysis.windowStartMs
                    val to = gap.endMs - analysis.windowStartMs
                    ReportRow("${offsetLabel(from)}–${offsetLabel(to)}", msLabel(gap.durationMs))
                }
            }
            ReportCard("Final 10 minutes") {
                val share = analysis.rushShare
                if (share == null) {
                    Text("Nothing was written during this sitting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ProgressRow("Rush share", share)
                    Text(
                        "${analysis.rushStrokes} of ${analysis.totalStrokes} strokes landed in the last 10 minutes.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            ReportCard("Replay") {
                SittingReplay(analysis)
            }
        }
    }
}

@Composable
private fun ReportCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Steps through the sitting as it was written: play runs at 60×, the slider scrubs, and the
 * chart shows strokes per minute with the playhead's minute highlighted.
 */
@Composable
private fun SittingReplay(analysis: SittingAnalysis) {
    val duration = analysis.durationMs.coerceAtLeast(1L)
    var replayMs by remember(analysis) { mutableLongStateOf(0L) }
    var playing by remember(analysis) { mutableStateOf(false) }
    LaunchedEffect(playing, analysis) {
        while (playing) {
            kotlinx.coroutines.delay(250)
            val next = replayMs + 250L * REPLAY_SPEED
            if (next >= duration) {
                replayMs = duration
                playing = false
            } else replayMs = next
        }
    }
    val cutoff = analysis.windowStartMs + replayMs
    val done = analysis.timeline.count { it.atMs <= cutoff }
    val currentPage = analysis.timeline.lastOrNull { it.atMs <= cutoff }?.pageIndex?.plus(1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton({
            playing = if (replayMs >= duration) {
                replayMs = 0L
                true
            } else !playing
        }) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause replay" else "Play replay at 60× speed")
        }
        Slider(
            value = replayMs.toFloat(),
            onValueChange = { replayMs = it.toLong().coerceIn(0L, duration); playing = false },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.weight(1f)
        )
    }
    Text(
        "${offsetLabel(replayMs)} of ${offsetLabel(duration)} · " +
            (currentPage?.let { "page $it · " } ?: "") +
            "$done of ${analysis.totalStrokes} strokes",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val peak = analysis.perMinute.maxOrNull()?.coerceAtLeast(1) ?: 1
    val bar = MaterialTheme.colorScheme.primary
    val playhead = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val currentBucket = ((replayMs / 60_000L).toInt()).coerceIn(0, analysis.perMinute.size - 1)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val n = analysis.perMinute.size
        if (n == 0) return@Canvas
        val gap = 2.dp.toPx()
        val slot = size.width / n
        val barWidth = (slot - gap).coerceAtLeast(1f)
        analysis.perMinute.forEachIndexed { index, count ->
            val height = if (peak == 0) 0f else size.height * count / peak
            drawRect(
                color = if (index == currentBucket) playhead else if (count > 0) bar else track,
                topLeft = androidx.compose.ui.geometry.Offset(index * slot + gap / 2f, size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height.coerceAtLeast(if (count > 0) 3.dp.toPx() else 1.dp.toPx()))
            )
        }
    }
    Text(
        "Strokes per minute across the sitting, played back at 60×.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Replay speed: one real second steps through a minute of the sitting. */
private const val REPLAY_SPEED = 60L
