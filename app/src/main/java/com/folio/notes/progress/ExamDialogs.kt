@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioPanel
import com.folio.notes.mistakes.isoTime
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

@Composable internal fun ChoiceField(label: String, value: String, options: List<String>, onChange: (String) -> Unit,
    editable: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        if (editable) OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (options.isNotEmpty()) TextButton({ expanded = true }) { Text("Choose") } })
        else OutlinedButton({ expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("$label: ${value.ifBlank { "Choose…" }}") }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false }) }
        }
    }
}

private data class QuestionDraft(val id: String, val label: String, val awarded: String, val maximum: String,
    val topic: String, val criterion: String, val confidence: String, val note: String, val original: String = "{}") {
    fun encode(): JSONObject = JSONObject(original).put("id", id).put("label", label.trim())
        .put("marksAwarded", awarded.toDouble()).put("maxMarks", maximum.toDouble())
        .put("areaOfStudy", topic.trim()).put("criterion", criterion.trim()).put("confidence", confidence).put("examinerNote", note.trim())
    companion object {
        fun from(o: JSONObject) = QuestionDraft(o.optString("id", UUID.randomUUID().toString()), o.optString("label"),
            o.optString("marksAwarded", "0"), o.optString("maxMarks", "1"), o.optString("areaOfStudy"),
            o.optString("criterion"), o.optString("confidence", "medium"), o.optString("examinerNote"), o.toString())
    }
}

@Composable internal fun LogExamDialog(initial: String, catalog: ExamCatalog, user: String?, manager: ExamProgressManager,
    onDismiss: () -> Unit, onSaved: (String, Boolean) -> Unit) {
    val source = remember(initial) { JSONObject(initial) }
    val owner = remember(initial) { user }
    val editing = source.has("id")
    var subject by rememberSaveable(initial) { mutableStateOf(source.optString("subject")) }
    var provider by rememberSaveable(initial) { mutableStateOf(source.optString("provider", "VCAA")) }
    var year by rememberSaveable(initial) { mutableStateOf(source.optString("examYear", LocalDate.now().year.toString())) }
    var paper by rememberSaveable(initial) { mutableStateOf(source.optString("paper", "Exam")) }
    var date by rememberSaveable(initial) { mutableStateOf(source.optString("completedAt", LocalDate.now().toString()).take(10)) }
    var score by rememberSaveable(initial) { mutableStateOf(source.optString("rawScore", "")) }
    var max by rememberSaveable(initial) { mutableStateOf(source.optString("rawMax", "100")) }
    var comment by rememberSaveable(initial) { mutableStateOf(source.optString("comment")) }
    var questionsRaw by rememberSaveable(initial) { mutableStateOf(source.optJSONArray("questionResults")?.toString() ?: "[]") }
    val questions = remember(questionsRaw) { JSONArray(questionsRaw).objects().map(QuestionDraft::from) }
    var showQuestions by rememberSaveable { mutableStateOf(questions.isNotEmpty()) }
    var showContext by rememberSaveable { mutableStateOf(false) }
    var contextRaw by rememberSaveable(initial) { mutableStateOf(source.optJSONObject("performanceContext")?.toString() ?: "{}") }
    val context = remember(contextRaw) { JSONObject(contextRaw) }
    var timingRaw by rememberSaveable(initial) { mutableStateOf(source.optJSONObject("timing")?.toString() ?: "{}") }
    val timing = remember(timingRaw) { JSONObject(timingRaw) }
    var showTiming by rememberSaveable { mutableStateOf(source.has("timing")) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = subject != source.optString("subject") || provider != source.optString("provider", "VCAA") ||
        year != source.optString("examYear", LocalDate.now().year.toString()) || paper != source.optString("paper", "Exam") ||
        date != source.optString("completedAt", LocalDate.now().toString()).take(10) || score != source.optString("rawScore", "") ||
        max != source.optString("rawMax", "100") || comment != source.optString("comment") ||
        questionsRaw != (source.optJSONArray("questionResults")?.toString() ?: "[]") ||
        contextRaw != (source.optJSONObject("performanceContext")?.toString() ?: "{}") || timingRaw != (source.optJSONObject("timing")?.toString() ?: "{}")
    fun dismiss() { if (!busy) { if (dirty) discard = true else onDismiss() } }
    fun updateQuestion(index: Int, next: QuestionDraft) {
        // Preserve unfinished numeric input as strings until submit; JSON on disk uses numbers.
        val array = JSONArray(questionsRaw)
        array.put(index, JSONObject(next.original).put("id", next.id).put("label", next.label)
            .put("marksAwarded", next.awarded).put("maxMarks", next.maximum).put("areaOfStudy", next.topic)
            .put("criterion", next.criterion).put("confidence", next.confidence).put("examinerNote", next.note))
        questionsRaw = array.toString()
    }
    fun submit(addMistake: Boolean) {
        val earned = score.toDoubleOrNull(); val total = max.toDoubleOrNull(); val y = year.toIntOrNull()
        error = when {
            subject.isBlank() || paper.isBlank() -> "Enter a subject and paper."
            y == null || y !in 1990..2100 -> "Enter a year from 1990 to 2100."
            runCatching { LocalDate.parse(date) }.isFailure -> "Enter the date as YYYY-MM-DD."
            earned == null || !earned.isFinite() || total == null || !total.isFinite() || total <= 0 || earned !in 0.0..total -> "Marks must be between 0 and a positive total."
            source.has("folioNotebookId") && (earned % 1 != 0.0 || total % 1 != 0.0 || total > Int.MAX_VALUE) -> "Linked notebook marks use whole numbers. Enter a whole mark and total."
            questions.any { q -> val a = q.awarded.toDoubleOrNull(); val m = q.maximum.toDoubleOrNull()
                q.label.isBlank() || a == null || !a.isFinite() || m == null || !m.isFinite() || m <= 0 || a !in 0.0..m } -> "Each question needs a label and valid marks."
            context.keys().asSequence().any { key -> val n = context.number(key)
                n == null || if (key == "sleepHours") n !in 0.0..24.0 else n !in 1.0..5.0 || n % 1 != 0.0 } -> "Sleep must be 0–24 hours; context ratings must be whole numbers from 1 to 5."
            timing.keys().asSequence().any { timing.number(it)?.let { n -> n < 0 || n > 86400 || n % 1 != 0.0 } != false } -> "Timing values must be non-negative whole numbers, up to 86,400."
            else -> null
        }
        if (error != null) return
        val id = source.optString("id").ifBlank { UUID.randomUUID().toString() }
        val raw = JSONObject(source.toString()).put("id", id).put("subject", subject.trim())
            .put("provider", provider.trim().ifBlank { "Other" }).put("examYear", y).put("paper", paper.trim())
            .put("title", "${provider.trim().ifBlank { "Other" }} $y ${subject.trim()}")
            .put("completedAt", if (date == source.optString("completedAt").take(10)) source.getString("completedAt") else date)
            .put("rawScore", earned).put("rawMax", total).put("comment", comment.trim())
            .put("questionResults", JSONArray(questions.map { it.encode() })).put("updatedAt", isoTime())
            .put("createdAt", source.optString("createdAt", isoTime())).put("referenceId", source.opt("referenceId") ?: JSONObject.NULL)
        if (context.length() > 0) raw.put("performanceContext", context) else raw.remove("performanceContext")
        if (timing.length() > 0) {
            listOf("plannedReadingMinutes", "plannedWritingMinutes", "actualWritingSeconds", "overtimeSeconds", "pausedSeconds")
                .forEach { if (!timing.has(it)) timing.put(it, 0) }
            raw.put("timing", timing)
        } else raw.remove("timing")
        busy = true
        scope.launch { if (manager.save("attempts", id, raw, owner)) onSaved(id, addMistake)
            else error = manager.state.value.error; busy = false }
    }
    FolioPanel(if (editing) "Edit exam" else "Log exam", ::dismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceField("Subject", subject, (catalog.references.map { it.subject } + catalog.studies.map { it.subject }).distinct().sorted(), { subject = it }, true)
            ChoiceField("Provider", provider, (listOf("VCAA", "VCAA NHT", "NEAP", "Insight", "TSSM", "MAV", "iTute", "Kilbaha", "Heffernan", "Other")), { provider = it }, true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(year, { year = it }, Modifier.weight(1f), label = { Text("Exam year") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(date, { date = it }, Modifier.weight(2f), label = { Text("Date · YYYY-MM-DD") }, singleLine = true)
            }
            ChoiceField("Paper", paper, catalog.references.filter { comparisonName(it.subject) == comparisonName(subject) }.map { it.paper.replace("WRITTEN EXAMINATION", "Exam", true).replace("EXAMINATION", "Exam", true) }.distinct(), { paper = it }, true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(score, { score = it }, Modifier.weight(1f), label = { Text("Marks awarded") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(max, { max = it }, Modifier.weight(1f), label = { Text("Out of") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            val preview = remember(subject, paper, year, score, max, catalog) {
                val a = score.toDoubleOrNull(); val m = max.toDoubleOrNull()
                if (a != null && m != null && a.isFinite() && m.isFinite() && m > 0 && a in 0.0..m) {
                    val reference = catalog.references.filter { comparisonName(it.subject) == comparisonName(subject) && it.year == year.toIntOrNull() }
                        .let { list -> list.find { comparisonName(it.paper) == comparisonName(paper) } ?: list.singleOrNull() }
                    reference?.let { analyseScore(a / m * it.maxScore, it) }
                } else null
            }
            preview?.let { Text("Estimated grade ${it.grade ?: "—"} · ${it.percentile?.display() ?: "—"} percentile", color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(comment, { comment = it }, Modifier.fillMaxWidth(), label = { Text("Comments and reflection") }, minLines = 2)
            TextButton({ showQuestions = !showQuestions }) { Text("Question marking (${questions.size})") }
            if (showQuestions) {
                questions.forEachIndexed { index, question ->
                    ProgressCard("Question ${index + 1}") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(question.label, { updateQuestion(index, question.copy(label = it)) }, Modifier.weight(1f), label = { Text("Question or section") }, singleLine = true)
                            IconButton({ questionsRaw = JSONArray(JSONArray(questionsRaw).objects().filterIndexed { i, _ -> i != index }).toString() }) { Icon(Icons.Rounded.DeleteOutline, "Remove question ${index + 1}") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(question.awarded, { updateQuestion(index, question.copy(awarded = it)) }, Modifier.weight(1f), label = { Text("Awarded") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                            OutlinedTextField(question.maximum, { updateQuestion(index, question.copy(maximum = it)) }, Modifier.weight(1f), label = { Text("Maximum") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        }
                        OutlinedTextField(question.topic, { updateQuestion(index, question.copy(topic = it)) }, Modifier.fillMaxWidth(), label = { Text("Area of study") })
                        OutlinedTextField(question.criterion, { updateQuestion(index, question.copy(criterion = it)) }, Modifier.fillMaxWidth(), label = { Text("Skill or criterion") })
                        ChoiceField("Confidence", question.confidence, listOf("low", "medium", "high"), { updateQuestion(index, question.copy(confidence = it)) })
                        OutlinedTextField(question.note, { updateQuestion(index, question.copy(note = it)) }, Modifier.fillMaxWidth(), label = { Text("Examiner note") })
                    }
                }
                OutlinedButton({ questionsRaw = JSONArray(questionsRaw).put(JSONObject().put("id", UUID.randomUUID().toString())
                    .put("label", "Q${questions.size + 1}").put("marksAwarded", "0").put("maxMarks", "1").put("confidence", "medium")).toString() }) { Icon(Icons.Rounded.Add, null); Text("Add question") }
                TextButton({
                    val values = questions.mapNotNull { q -> val a = q.awarded.toDoubleOrNull(); val m = q.maximum.toDoubleOrNull()
                        if (a != null && m != null && a.isFinite() && m.isFinite() && m > 0 && a in 0.0..m) a to m else null }
                    if (values.size == questions.size && values.isNotEmpty()) { score = values.sumOf { it.first }.toString(); max = values.sumOf { it.second }.toString() }
                    else error = "Enter valid question marks first."
                }) { Text("Use question totals as exam mark") }
            }
            TextButton({ showContext = !showContext }) { Text("Performance context") }
            if (showContext) for ((key, label) in listOf("sleepHours" to "Sleep hours", "energy" to "Energy", "focus" to "Focus", "stress" to "Stress", "confidence" to "Confidence", "preparedness" to "Preparedness")) {
                OutlinedTextField(context.optString(key), { value -> contextRaw = JSONObject(contextRaw).apply {
                    if (value.isBlank()) remove(key) else put(key, value.toDoubleOrNull() ?: value)
                }.toString() }, Modifier.fillMaxWidth(), label = { Text(if (key == "sleepHours") label else "$label · 1–5") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            TextButton({ showTiming = !showTiming }) { Text("Timing") }
            if (showTiming) for ((key, label) in listOf("plannedReadingMinutes" to "Reading minutes", "plannedWritingMinutes" to "Writing minutes",
                "actualWritingSeconds" to "Actual writing seconds", "overtimeSeconds" to "Overtime seconds", "pausedSeconds" to "Paused seconds")) {
                OutlinedTextField(timing.optString(key), { value -> timingRaw = JSONObject(timingRaw).apply {
                    if (value.isBlank()) remove(key) else put(key, value.toDoubleOrNull() ?: value)
                }.toString() }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
        }
        FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(::dismiss, enabled = !busy) { Text("Cancel") }
            OutlinedButton({ submit(true) }, enabled = !busy) { Text("Save + mistake") }
            Button({ submit(false) }, enabled = !busy) { Text(if (busy) "Saving…" else "Save") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard exam changes?") },
        text = { Text("Your changes have not been saved.") }, confirmButton = { TextButton(onDismiss) { Text("Discard") } },
        dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
}

@Composable internal fun LogMistakeDialog(exam: LoggedExam, user: String?, manager: ExamProgressManager, onDismiss: () -> Unit) {
    val owner = remember(exam.id) { user }
    var question by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Concept") }
    var explanation by rememberSaveable { mutableStateOf("") }
    var correction by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    var lost by rememberSaveable { mutableStateOf("") }
    var total by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun dismiss() { if (!busy) { if (question.isNotBlank() || explanation.isNotBlank() || correction.isNotBlank() || topic.isNotBlank() || lost.isNotBlank() || total.isNotBlank()) discard = true else onDismiss() } }
    FolioPanel("Log mistake · ${exam.paper}", ::dismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(exam.title)
            OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("Question") })
            ChoiceField("Category", category, listOf("Concept", "Knowledge recall", "Reasoning", "Evidence and analysis", "Written expression", "Process or technique", "Accuracy", "Interpretation", "Time management", "Algebra", "Arithmetic", "Calculator", "Other"), { category = it })
            OutlinedTextField(explanation, { explanation = it }, Modifier.fillMaxWidth(), label = { Text("What went wrong?") }, minLines = 2)
            OutlinedTextField(correction, { correction = it }, Modifier.fillMaxWidth(), label = { Text("Correction") }, minLines = 2)
            OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth(), label = { Text("Area of study") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lost, { lost = it }, Modifier.weight(1f), label = { Text("Marks lost") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(total, { total = it }, Modifier.weight(1f), label = { Text("Total marks") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button({
            val l = lost.toDoubleOrNull(); val t = total.toDoubleOrNull()
            if (question.isBlank() || explanation.isBlank() || correction.isBlank()) error = "Enter the question, explanation and correction."
            else if ((lost.isNotBlank() && (l == null || !l.isFinite() || l < 0)) ||
                (total.isNotBlank() && (t == null || !t.isFinite() || t <= 0)) || (l != null && t != null && l > t)) error = "Enter valid question marks."
            else {
                val id = UUID.randomUUID().toString(); val now = isoTime()
                val raw = JSONObject().put("id", id).put("attemptId", exam.id).put("question", question.trim())
                    .put("category", category).put("explanation", explanation.trim()).put("correction", correction.trim())
                    .put("resolved", false).put("createdAt", now).put("updatedAt", now).put("reviewHistory", JSONArray())
                if (topic.isNotBlank()) raw.put("areaOfStudy", topic.trim())
                if (l != null) raw.put("marksLost", l)
                if (t != null) raw.put("totalMarks", t)
                busy = true
                scope.launch { if (manager.save("mistakes", id, raw, owner)) onDismiss() else error = manager.state.value.error; busy = false }
            }
        }, enabled = !busy, modifier = Modifier.align(Alignment.End).padding(16.dp)) { Text(if (busy) "Saving…" else "Save mistake") }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard mistake changes?") },
        confirmButton = { TextButton(onDismiss) { Text("Discard") } }, dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
}
