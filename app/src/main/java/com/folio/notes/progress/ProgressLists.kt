@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioPanel
import com.folio.notes.mistakes.isoTime
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal fun paperKey(o: JSONObject): String = listOf(o.optString("subject"), o.optString("provider"),
    o.optString("examYear"), o.optString("paper")).joinToString("\u0000") { it.trim().lowercase().replace(Regex("\\s+"), " ") }
internal fun LoggedExam.paperKey() = paperKey(json)

internal fun validatePlan(raw: String): JSONObject {
    require(raw.length <= 500_000) { "Import up to 1,000 papers." }
    val plan = JSONObject(raw.trim().replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*```$"), ""))
    require(plan.getInt("version") == 1) { "The list must have version: 1." }
    require(plan.getString("name").trim().length in 1..200) { "Enter a list name (up to 200 characters)." }
    val array = plan.getJSONArray("exams"); require(array.length() <= 1000) { "Import up to 1,000 papers." }
    val keys = mutableSetOf<String>()
    for (exam in array.objects()) {
        for (field in listOf("subject", "provider", "paper")) require(exam.getString(field).trim().length in 1..200) { "Every paper needs a subject, provider and paper (up to 200 characters)." }
        val year = exam.getDouble("examYear"); require(year in 1990.0..2100.0 && year % 1 == 0.0) { "Years must be whole numbers from 1990 to 2100." }
        val marks = exam.getDouble("marks"); require(marks.isFinite() && marks > 0 && marks <= 500) { "Marks must be greater than 0 and at most 500." }
        require(exam.optString("phase").length <= 200) { "Phase labels must be at most 200 characters." }
        require(keys.add(paperKey(exam))) { "Remove duplicate subject/provider/year/paper combinations." }
    }
    require(array.objects().size == array.length()) { "Each paper must be an object." }
    return plan
}

@Composable internal fun ProgressionDialog(initial: String?, catalog: ExamCatalog, manager: ExamProgressManager,
    user: String?, onDismiss: () -> Unit) {
    val original = remember(initial) { initial?.let(::JSONObject) ?: JSONObject().put("version", 1).put("name", "My exam progression").put("exams", JSONArray()) }
    val owner = remember { user }
    var name by rememberSaveable { mutableStateOf(original.optString("name")) }
    var papersRaw by rememberSaveable { mutableStateOf(original.optJSONArray("exams")?.toString() ?: "[]") }
    val papers = remember(papersRaw) { JSONArray(papersRaw).objects() }
    var importText by rememberSaveable { mutableStateOf("") }
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var replaceSubjects by rememberSaveable { mutableStateOf(false) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var subject by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("VCAA") }
    var year by rememberSaveable { mutableStateOf(java.time.LocalDate.now().year.toString()) }
    var paper by rememberSaveable { mutableStateOf("Exam") }
    var marks by rememberSaveable { mutableStateOf("100") }
    var phase by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun dismiss() { if (!busy) { if (name != original.optString("name") || papersRaw != (original.optJSONArray("exams")?.toString() ?: "[]") || importText.isNotBlank() || addOpen) discard = true else onDismiss() } }
    FolioPanel("Exam progression", ::dismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Each subject advances independently. Logged papers complete matching entries automatically.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("List name") })
            papers.forEachIndexed { index, exam ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${exam.optString("subject")} · ${exam.optString("provider")} ${exam.optInt("examYear")} · ${exam.optString("paper")}")
                        Text("${exam.optDouble("marks").display(0)} marks · ${exam.optString("phase")}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            IconButton({ val next = papers.toMutableList(); java.util.Collections.swap(next, index, index - 1); papersRaw = JSONArray(next).toString() }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "Move paper up") }
                            IconButton({ val next = papers.toMutableList(); java.util.Collections.swap(next, index, index + 1); papersRaw = JSONArray(next).toString() }, enabled = index < papers.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move paper down") }
                            IconButton({ papersRaw = JSONArray(papers.filterIndexed { i, _ -> i != index }).toString() }) { Icon(Icons.Rounded.DeleteOutline, "Remove paper") }
                        }
                    }
                }
            }
            OutlinedButton({ addOpen = !addOpen }) { Text("Add paper") }
            if (addOpen) {
                ChoiceField("Subject", subject, catalog.references.map { it.subject }.distinct().sorted(), { subject = it }, true)
                ChoiceField("Provider", provider, defaultProviderOrder, { provider = it }, true)
                OutlinedTextField(year, { year = it }, label = { Text("Year") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(paper, { paper = it }, label = { Text("Paper") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(marks, { marks = it }, label = { Text("Marks") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phase, { phase = it }, label = { Text("Phase (optional)") }, modifier = Modifier.fillMaxWidth())
                TextButton({
                    try {
                        val exam = JSONObject().put("subject", subject.trim()).put("provider", provider.trim())
                            .put("examYear", year.toInt()).put("paper", paper.trim()).put("marks", marks.toDouble()).put("phase", phase.trim())
                        val next = JSONObject().put("version", 1).put("name", name).put("exams", JSONArray(papers + exam))
                        validatePlan(next.toString()); papersRaw = next.getJSONArray("exams").toString(); addOpen = false; error = null
                    } catch (e: Exception) { error = e.message ?: "Check the paper details." }
                }) { Text("Add to list") }
            }
            TextButton({ importOpen = !importOpen }) { Text("Import Focal progression JSON") }
            if (importOpen) {
                Text("Paste a Focal list with version, name, and exams. Each exam has subject, provider, examYear, paper, marks and optional phase.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(importText, { importText = it }, Modifier.fillMaxWidth(), label = { Text("Progression JSON") }, minLines = 4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(replaceSubjects, { replaceSubjects = it }); Text("Replace listed subjects; keep other subjects", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                TextButton({
                    try {
                        val imported = validatePlan(importText); val incoming = imported.getJSONArray("exams").objects()
                        val subjects = incoming.map { comparisonName(it.getString("subject")) }.toSet()
                        val existing = if (replaceSubjects) papers.filterNot { comparisonName(it.getString("subject")) in subjects } else papers
                        val combined = (existing + incoming).distinctBy(::paperKey)
                        require(combined.size <= 1000) { "The combined list exceeds 1,000 papers." }
                        papersRaw = JSONArray(combined).toString(); if (papers.isEmpty()) name = imported.getString("name")
                        importText = ""; importOpen = false; error = null
                    } catch (e: Exception) { error = e.message ?: "Paste valid progression JSON." }
                }) { Text(if (replaceSubjects) "Replace subjects" else "Append papers") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(::dismiss, enabled = !busy) { Text("Cancel") }
            Button({
                try {
                    val plan = JSONObject(original.toString()).put("version", 1).put("name", name.trim()).put("exams", JSONArray(papersRaw)).put("updatedAt", isoTime())
                    validatePlan(plan.toString()); busy = true
                    scope.launch { if (manager.saveValue("examProgression", plan, owner)) onDismiss() else error = manager.state.value.error; busy = false }
                } catch (e: Exception) { error = e.message ?: "Check the progression." }
            }, enabled = !busy) { Text(if (busy) "Saving…" else "Save list") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard list changes?") },
        confirmButton = { TextButton(onDismiss) { Text("Discard") } }, dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
}
