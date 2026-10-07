@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import com.folio.notes.EmptyHint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioPanel
import com.folio.notes.FolioSpacing
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

private const val FORM_CLOSED = -2
private const val FORM_ADD = -1

/** Editor for the exam progression: subject-grouped, with in-place edits, year ranges, undo and Focal JSON in and out. */
@Composable internal fun ProgressionDialog(initial: String?, catalog: ExamCatalog, logged: List<LoggedExam>, manager: ExamProgressManager,
    user: String?, onDismiss: () -> Unit) {
    val original = remember(initial) { initial?.let(::JSONObject) ?: JSONObject().put("version", 1).put("name", "My exam progression").put("exams", JSONArray()) }
    val originalPapers = remember(original) { original.optJSONArray("exams")?.toString() ?: "[]" }
    val owner = remember { user }
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by rememberSaveable { mutableStateOf(original.optString("name")) }
    var papersRaw by rememberSaveable { mutableStateOf(originalPapers) }
    val papers = remember(papersRaw) { JSONArray(papersRaw).objects() }
    val history = remember { mutableStateListOf<String>() }
    val collapsed = remember { mutableStateListOf<String>() }
    var importText by rememberSaveable { mutableStateOf("") }
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var replaceSubjects by rememberSaveable { mutableStateOf(false) }
    var formAt by rememberSaveable { mutableStateOf(FORM_CLOSED) }
    var subject by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("VCAA") }
    var years by rememberSaveable { mutableStateOf(java.time.LocalDate.now().year.toString()) }
    var paper by rememberSaveable { mutableStateOf("Exam 1") }
    var marks by rememberSaveable { mutableStateOf("") }
    var marksEdited by rememberSaveable { mutableStateOf(false) }
    var phase by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var subjectFilter by rememberSaveable { mutableStateOf(ALL_SUBJECTS) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val done = remember(logged) { logged.map { it.paperKey() }.toSet() }
    val knownSubjects = remember(catalog, papers) { (catalog.references.map { it.subject } + papers.map { it.optString("subject") }).distinct().sorted() }
    val knownPhases = remember(papers) { papers.map { it.optString("phase") }.filter(String::isNotBlank).distinct() }
    val dirty = name != original.optString("name") || papersRaw != originalPapers || importText.isNotBlank()
    fun dismiss() { if (!busy) { if (dirty) discard = true else onDismiss() } }
    /** Every change goes through here so Undo can step back one edit at a time. */
    fun commit(next: List<JSONObject>, message: String? = null) {
        history += papersRaw; if (history.size > 50) history.removeAt(0)
        papersRaw = JSONArray(next).toString(); error = null; notice = message
    }
    fun closeForm() { formAt = FORM_CLOSED; error = null }
    fun openForm(at: Int) {
        formAt = at; error = null; notice = null
        val exam = papers.getOrNull(at)
        if (exam != null) {
            subject = exam.optString("subject"); provider = exam.optString("provider"); years = exam.optInt("examYear").toString()
            paper = exam.optString("paper"); marks = exam.optDouble("marks").display(0); marksEdited = true; phase = exam.optString("phase")
        } else {
            // A new paper continues from the subject you were just looking at.
            if (subject.isBlank() && subjectFilter != ALL_SUBJECTS) subject = subjectFilter
            marksEdited = false; marks = ProgressionEdit.suggestedMarks(subject, paper)?.toString().orEmpty()
        }
    }
    fun suggest(newSubject: String = subject, newPaper: String = paper) {
        if (!marksEdited) ProgressionEdit.suggestedMarks(newSubject, newPaper)?.let { marks = it.toString() }
    }
    val parsedYears = remember(years, formAt) { if (formAt == FORM_ADD) runCatching { ProgressionEdit.parseYears(years) }.getOrNull() else null }
    val skipped = parsedYears?.count { y -> papers.any { paperKey(it) == paperKey(ProgressionEdit.paper(subject, provider, y, paper, 1.0, "")) } } ?: 0
    fun submitForm() {
        try {
            val m = requireNotNull(marks.trim().toDoubleOrNull()) { "Enter the marks available." }
            require(subject.isNotBlank()) { "Choose or type a subject." }
            if (formAt >= 0) {
                val y = requireNotNull(years.trim().toIntOrNull()) { "Enter a single year." }
                val next = papers.toMutableList().also { it[formAt] = JSONObject(it[formAt].toString()).put("subject", subject.trim())
                    .put("provider", provider.trim()).put("examYear", y).put("paper", paper.trim()).put("marks", m).put("phase", phase.trim()) }
                validatePlan(ProgressionEdit.encode(name, next).toString()); commit(next, "Paper updated."); closeForm()
            } else {
                val fresh = ProgressionEdit.parseYears(years).map { ProgressionEdit.paper(subject, provider, it, paper, m, phase) }
                    .filter { e -> papers.none { paperKey(it) == paperKey(e) } }
                require(fresh.isNotEmpty()) { "Those papers are already in the list." }
                val next = ProgressionEdit.insert(papers, fresh)
                validatePlan(ProgressionEdit.encode(name, next).toString())
                commit(next, "Added ${fresh.size} ${if (fresh.size == 1) "paper" else "papers"}${if (skipped > 0) " · skipped $skipped already listed" else ""}.")
                closeForm()
            }
        } catch (e: Exception) { error = e.message ?: "Check the paper details." }
    }
    val doneCount = papers.count { paperKey(it) in done }
    FolioPanel("Exam progression", ::dismiss, actions = {
        IconButton({ papersRaw = history.removeAt(history.lastIndex); notice = "Undid the last change."; error = null }, enabled = history.isNotEmpty() && !busy) {
            Icon(Icons.AutoMirrored.Rounded.Undo, "Undo last change")
        }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words), label = { Text("List name") }, singleLine = true)
            // At-a-glance state: how much of the plan the log already covers.
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Text(if (papers.isEmpty()) "No papers yet. Add a subject's papers by year, or import a Focal list."
                        else "${papers.size} ${if (papers.size == 1) "paper" else "papers"} · ${papers.map { comparisonName(it.optString("subject")) }.distinct().size} subjects · $doneCount done",
                        style = MaterialTheme.typography.bodyMedium)
                    if (papers.isNotEmpty()) LinearProgressIndicator({ doneCount / papers.size.toFloat() }, Modifier.fillMaxWidth(), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    Text("Each subject advances independently. Logged papers complete matching entries automatically.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                AssistChip({ if (formAt == FORM_ADD) closeForm() else openForm(FORM_ADD) }, { Text("Add papers") },
                    leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(AssistChipDefaults.IconSize)) })
                AssistChip({ importOpen = !importOpen }, { Text("Import") }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null, Modifier.size(AssistChipDefaults.IconSize)) })
                AssistChip({
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Focal progression", ProgressionEdit.encode(name, papers).toString(2)))
                    notice = "Copied the list as Focal JSON."
                }, { Text("Copy JSON") }, enabled = papers.isNotEmpty(), leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(AssistChipDefaults.IconSize)) })
                if (doneCount > 0) AssistChip({ commit(papers.filterNot { paperKey(it) in done }, "Removed $doneCount completed ${if (doneCount == 1) "paper" else "papers"}.") },
                    { Text("Clear completed") }, leadingIcon = { Icon(Icons.Rounded.TaskAlt, null, Modifier.size(AssistChipDefaults.IconSize)) })
            }
            notice?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }

            AnimatedVisibility(formAt != FORM_CLOSED) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Text(if (formAt >= 0) "Edit paper" else "Add papers", style = MaterialTheme.typography.titleMedium)
                        ChoiceField("Subject", subject, knownSubjects, { subject = it; suggest(newSubject = it) }, true)
                        Text("Provider", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                            defaultProviderOrder.forEach { p -> FilterChip(comparisonName(provider) == comparisonName(p), { provider = p }, { Text(p) }) }
                        }
                        OutlinedTextField(provider, { provider = it }, Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words), label = { Text("Provider name") }, singleLine = true)
                        Text("Paper", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                            listOf("Exam 1", "Exam 2", "Exam").forEach { p -> FilterChip(paper == p, { paper = p; suggest(newPaper = p) }, { Text(p) }) }
                        }
                        OutlinedTextField(paper, { paper = it; suggest(newPaper = it) }, Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words), label = { Text("Paper name") }, singleLine = true)
                        OutlinedTextField(years, { years = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(if (formAt >= 0) "Year" else "Year, years or range") },
                            supportingText = { if (formAt == FORM_ADD) Text(parsedYears?.let { y ->
                                "${y.size - skipped} to add" + if (skipped > 0) " · $skipped already listed" else "" } ?: "e.g. 2022 · 2019, 2021 · 2018-2023") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                        OutlinedTextField(marks, { marks = it; marksEdited = true }, Modifier.fillMaxWidth(), label = { Text("Marks available") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(phase, { phase = it }, Modifier.fillMaxWidth(), label = { Text("Phase (optional)") }, singleLine = true,
                            supportingText = { Text("Groups papers under a heading, e.g. Term 3 or Final revision.") })
                        if (knownPhases.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                            knownPhases.forEach { p -> FilterChip(phase == p, { phase = if (phase == p) "" else p }, { Text(p) }) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(::closeForm) { Text("Cancel") }
                            Button(::submitForm) { Text(if (formAt >= 0) "Save paper" else "Add to list") }
                        }
                    }
                }
            }
            AnimatedVisibility(importOpen) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Text("Import Focal progression", style = MaterialTheme.typography.titleMedium)
                        Text("Paste a Focal list with version, name, and exams. Each exam has subject, provider, examYear, paper, marks and optional phase.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(importText, { importText = it }, Modifier.fillMaxWidth(), label = { Text("Progression JSON") }, minLines = 4)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(replaceSubjects, { replaceSubjects = it }); Text("Replace listed subjects; keep other subjects", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton({ importOpen = false; importText = "" }) { Text("Cancel") }
                            Button({
                                try {
                                    val imported = validatePlan(importText); val incoming = imported.getJSONArray("exams").objects()
                                    val subjects = incoming.map { comparisonName(it.getString("subject")) }.toSet()
                                    val existing = if (replaceSubjects) papers.filterNot { comparisonName(it.getString("subject")) in subjects } else papers
                                    val combined = (existing + incoming).distinctBy(::paperKey)
                                    require(combined.size <= ProgressionEdit.MAX_PAPERS) { "The combined list exceeds 1,000 papers." }
                                    val wasEmpty = papers.isEmpty()
                                    commit(combined, "Imported ${combined.size - existing.size} new ${if (combined.size - existing.size == 1) "paper" else "papers"}.")
                                    if (wasEmpty) name = imported.getString("name")
                                    importText = ""; importOpen = false
                                } catch (e: Exception) { error = e.message ?: "Paste valid progression JSON." }
                            }, enabled = importText.isNotBlank()) { Text(if (replaceSubjects) "Replace subjects" else "Append papers") }
                        }
                    }
                }
            }
            if (papers.size > 6) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search this list") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } })
                val subjectsInPlan = papers.map { it.optString("subject") }.distinct()
                if (subjectsInPlan.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MenuChip("Subject", subjectFilter, listOf(ALL_SUBJECTS) + subjectsInPlan, { subjectFilter = it }, Icons.Rounded.School)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val indexed = papers.withIndex().filter { (_, e) ->
                (subjectFilter == ALL_SUBJECTS || e.optString("subject") == subjectFilter) &&
                    "${e.optString("subject")} ${e.optString("provider")} ${e.optInt("examYear")} ${e.optString("paper")} ${e.optString("phase")}".contains(query.trim(), true)
            }
            indexed.groupBy { it.value.optString("subject") }.forEach { (s, group) ->
                val all = papers.filter { it.optString("subject") == s }
                val finished = all.count { paperKey(it) in done }
                val open = s !in collapsed
                var menu by remember { mutableStateOf(false) }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp4)) {
                        Row(Modifier.fillMaxWidth().clickable { if (open) collapsed += s else collapsed -= s }.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4, top = FolioSpacing.dp4, bottom = FolioSpacing.dp4),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse $s" else "Expand $s")
                            Column(Modifier.weight(1f).padding(start = FolioSpacing.dp8)) {
                                Text(s, style = MaterialTheme.typography.titleSmall)
                                Text("$finished of ${all.size} done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More for $s") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("Sort oldest first") }, { menu = false; commit(ProgressionEdit.sortSubject(papers, s, true)) },
                                        leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) })
                                    DropdownMenuItem({ Text("Sort newest first") }, { menu = false; commit(ProgressionEdit.sortSubject(papers, s, false)) },
                                        leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) })
                                    DropdownMenuItem({ Text("Add a paper") }, { menu = false; subject = s; openForm(FORM_ADD); subject = s; suggest() },
                                        leadingIcon = { Icon(Icons.Rounded.Add, null) })
                                    DropdownMenuItem({ Text("Remove subject", color = MaterialTheme.colorScheme.error) },
                                        { menu = false; commit(ProgressionEdit.removeSubject(papers, s), "Removed $s.") },
                                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) })
                                }
                            }
                        }
                        AnimatedVisibility(open) {
                            Column {
                                group.forEach { (index, exam) ->
                                    val finishedPaper = paperKey(exam) in done
                                    var rowMenu by remember { mutableStateOf(false) }
                                    Row(Modifier.fillMaxWidth().clickable { openForm(index) }.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (finishedPaper) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (finishedPaper) "Completed" else "To do",
                                            Modifier.size(20.dp), tint = if (finishedPaper) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                                        Column(Modifier.weight(1f).padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8)) {
                                            Text("${exam.optString("provider")} ${exam.optInt("examYear")} · ${exam.optString("paper")}", style = MaterialTheme.typography.bodyMedium)
                                            Text(listOf("${exam.optDouble("marks").display(0)} marks", exam.optString("phase")).filter(String::isNotBlank).joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Box {
                                            IconButton({ rowMenu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${exam.optString("provider")} ${exam.optInt("examYear")} ${exam.optString("paper")}") }
                                            DropdownMenu(rowMenu, { rowMenu = false }) {
                                                DropdownMenuItem({ Text("Edit") }, { rowMenu = false; openForm(index) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                                                DropdownMenuItem({ Text("Move up") }, { rowMenu = false; commit(ProgressionEdit.move(papers, index, -1)) },
                                                    leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) })
                                                DropdownMenuItem({ Text("Move down") }, { rowMenu = false; commit(ProgressionEdit.move(papers, index, 1)) },
                                                    leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) })
                                                DropdownMenuItem({ Text("Duplicate to next year") }, { rowMenu = false
                                                    ProgressionEdit.duplicate(papers, index)?.let { commit(it) } ?: run { error = "No free year to duplicate into." } },
                                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                                                DropdownMenuItem({ Text("Remove", color = MaterialTheme.colorScheme.error) }, { rowMenu = false; commit(papers.filterIndexed { i, _ -> i != index }, "Removed a paper.") },
                                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (papers.isNotEmpty() && indexed.isEmpty()) EmptyHint("No papers match.")
            Spacer(Modifier.height(FolioSpacing.dp4))
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.End) {
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
