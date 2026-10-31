@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.exam

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import java.util.Calendar

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
    val validYear = !changeYear || validExamYear(newYear)
    val canApply = validYear && (changeSubject || changeYear || changeCompany || changeType || changeStatus)
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
                    .padding(horizontal = FolioSpacing.dp24).padding(top = FolioSpacing.dp4, bottom = FolioSpacing.dp12),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
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
                    isError = !validYear,
                    supportingText = { if (!validYear) Text("Use a four-digit year or leave blank to clear.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    listOf(thisYear, (thisYear.toInt() - 1).toString(), (thisYear.toInt() - 2).toString()).forEach { suggestion ->
                        FilterChip(newYear == suggestion, { newYear = if (newYear == suggestion) "" else suggestion }, { Text(suggestion) })
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    companySuggestions.forEach { suggestion ->
                        FilterChip(newCompany.trim().equals(suggestion, ignoreCase = true), { newCompany = if (newCompany.trim().equals(suggestion, true)) "" else suggestion }, { Text(suggestion) })
                    }
                }
            }
            BatchSection(
                checked = changeType,
                onChecked = { changeType = it },
                title = "Type",
                summary = if (!changeType) "Unchanged" else newType?.label ?: "Cleared"
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    ExamStatus.entries.forEach { option ->
                        FilterChip(newStatus == option, { newStatusName = option.name }, { Text(option.label) })
                    }
                }
            }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)
            ) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button({ if (canApply) onApply(buildTransform()) }, enabled = canApply, shapes = ButtonDefaults.shapes()) { Text("Apply to $count") }
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
    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
            Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChecked), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, null)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (checked) content()
        }
    }
}
