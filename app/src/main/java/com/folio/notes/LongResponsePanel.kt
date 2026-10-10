@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable internal fun LongResponseSetupPanel(
    initial: LongResponse? = null, initialTitle: String = "", initialSubject: String = "",
    onDismiss: () -> Unit, onSave: (String, String, LongResponse, ResponseMode) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var subject by rememberSaveable { mutableStateOf(initialSubject) }
    var prompt by rememberSaveable { mutableStateOf(initial?.prompt.orEmpty()) }
    var topic by rememberSaveable { mutableStateOf(initial?.topic.orEmpty()) }
    var marks by rememberSaveable { mutableStateOf(initial?.marks?.toString().orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(initial?.targetMinutes?.toString().orEmpty()) }
    var mode by rememberSaveable { mutableStateOf(ResponseMode.FULL) }
    val validMarks = marks.isBlank() || marks.toIntOrNull()?.let { it in 1..1000 } == true
    val validTime = minutes.isBlank() || minutes.toIntOrNull()?.let { it in 1..1440 } == true
    FolioPanel(title = if (initial == null) "Long response" else "Question details", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            if (initial == null) OutlinedTextField(title, { title = it.take(120) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = androidx.compose.ui.text.input.ImeAction.Next), label = { Text("Notebook name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = it.take(120) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = androidx.compose.ui.text.input.ImeAction.Next), label = { Text("Subject") }, placeholder = { Text("e.g. English, Legal Studies") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(topic, { topic = it.take(240) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = androidx.compose.ui.text.input.ImeAction.Next), label = { Text("Text or topic") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prompt, { prompt = it.take(8000) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences), label = { Text("Question or prompt") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                OutlinedTextField(marks, { marks = it.take(4) }, label = { Text("Marks (optional)") }, singleLine = true,
                    isError = !validMarks, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.weight(1f))
                OutlinedTextField(minutes, { minutes = it.take(4) }, label = { Text("Minutes (optional)") }, singleLine = true,
                    isError = !validTime, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.weight(1f))
            }
            if (!validMarks || !validTime) Text("Use 1–1000 marks and 1–1440 minutes, or leave blank.", color = MaterialTheme.colorScheme.error)
            if (initial == null) {
                Text("Start with", style = MaterialTheme.typography.labelLarge)
                ResponseModeChoices(mode) { mode = it }
                Text("Your question stays visible while you write. Plans, drafts, marked copies and retries stay together.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                TextButton(onDismiss) { Text("Cancel") }
                Button({ onSave(title.trim(), subject.trim(), LongResponse(prompt.trim(), topic.trim(), marks.toIntOrNull(), minutes.toIntOrNull()), mode) },
                    enabled = (initial != null || title.isNotBlank()) && prompt.isNotBlank() && validMarks && validTime) {
                    Text(if (initial == null) "Create response" else "Save details")
                }
            }
        }
    }
}

@Composable private fun ResponseModeChoices(mode: ResponseMode, onMode: (ResponseMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        ResponseMode.entries.forEach { item -> FilterChip(mode == item, { onMode(item) }, { Text(item.label) }) }
    }
}

/** A small writing shelf outside the ink surface, shared by every page of a response. */
@Composable internal fun LongResponseBar(note: Notebook, page: NotePage, model: FolioViewModel, onAttempts: () -> Unit) {
    val response = note.longResponse ?: return
    val attempt = response.attemptFor(page.id)
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    var planning by rememberSaveable(note.id, attempt?.id) { mutableStateOf(attempt?.mode == ResponseMode.PLAN) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp4)) {
            TextButton({ expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(response.prompt, maxLines = if (expanded) 8 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(if (expanded) "Less" else "More", Modifier.padding(start = FolioSpacing.dp8), style = MaterialTheme.typography.labelSmall)
            }
            attempt?.let {
                Text(listOfNotNull(it.title, response.marks?.let { marks -> "$marks marks" }, response.targetMinutes?.let { minutes -> "$minutes min target" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onAttempts) { Text("Attempts · ${response.attempts.size}") }
                if (attempt != null) TextButton({ planning = !planning }) { Text(if (planning) "Hide plan" else "Plan") }
                if (attempt != null) TextButton({ model.addPage(Paper.RULED); model.openAt(note.id, model.state.value.pageIndex) }) { Text("Continue on new page") }
            }
            if (planning && attempt != null) key(note.id, attempt.id) {
                ResponsePlanEditor(note.id, attempt, model)
            }
        }
    }
}

/** Debounce typing, but flush when hiding the plan, switching attempts or leaving the editor. */
@Composable private fun ResponsePlanEditor(noteId: String, attempt: ResponseAttempt, model: FolioViewModel) {
    var plan by rememberSaveable { mutableStateOf(attempt.plan) }
    val latestPlan by rememberUpdatedState(plan)
    LaunchedEffect(plan) {
        kotlinx.coroutines.delay(400)
        model.updateResponsePlan(noteId, attempt.id, plan)
    }
    DisposableEffect(Unit) {
        onDispose { model.updateResponsePlan(noteId, attempt.id, latestPlan) }
    }
    OutlinedTextField(plan, { plan = it.take(12000) }, label = { Text("Plan · contention, evidence, paragraph order") },
        minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
}

@Composable internal fun LongResponseAttemptsPanel(note: Notebook, currentPageId: String?, model: FolioViewModel, onDismiss: () -> Unit) {
    val response = note.longResponse ?: return
    val current = response.attemptFor(currentPageId)
    var mode by rememberSaveable { mutableStateOf(ResponseMode.FULL) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    if (editing) {
        LongResponseSetupPanel(response, note.title, note.exam.subjectLabel, { editing = false }) { _, subject, details, _ ->
            model.configureResponse(note.id, details, subject); editing = false
        }
        return
    }
    FolioPanel(title = "Response attempts", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Text(response.prompt, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(note.exam.subjectLabel.takeIf { it.isNotBlank() }, response.topic.takeIf { it.isNotBlank() },
                response.marks?.let { "$it marks" }, response.targetMinutes?.let { "$it minute target" }).joinToString(" · "))
            TextButton({ editing = true }) { Text("Edit question details") }
            Text("New attempt", style = MaterialTheme.typography.titleSmall)
            ResponseModeChoices(mode) { mode = it }
            Button({ model.newResponseAttempt(note.id, mode, current?.id); onDismiss() }) { Text("Start fresh attempt") }
            response.targetMinutes?.let { minutes ->
                val timer = model.state.collectAsState().value.timer
                FilledTonalButton({ model.startTimer(ExamTimerPreset("Long response", minutes * 60, 0)); onDismiss() },
                    enabled = timer.phase == ExamTimerPhase.IDLE || timer.phase == ExamTimerPhase.DONE) { Text("Start $minutes minute timer") }
            }
            Text("Open an attempt to write; compare keeps another attempt read-only beside it. Copy for marking preserves the original pages.", style = MaterialTheme.typography.bodySmall)
            response.attempts.forEach { attempt ->
                val existingPages = note.pages.count { it.id in attempt.pageIds }
                val parent = response.attempts.find { it.id == attempt.parentId }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        Text(attempt.title + if (attempt.id == current?.id) " · Current" else "", style = MaterialTheme.typography.titleSmall)
                        Text("${attempt.mode.label} · $existingPages pages · ${DateFormat.getDateInstance().format(Date(attempt.created))}", style = MaterialTheme.typography.bodySmall)
                        parent?.let { Text("From ${it.title}", style = MaterialTheme.typography.bodySmall) }
                        note.attempts.find { it.id == attempt.resultId }?.let { mark ->
                            Text("${mark.score}${mark.total?.let { " / $it" }.orEmpty()} marks", style = MaterialTheme.typography.labelLarge)
                        }
                        if (existingPages < attempt.pageIds.size) Text("Some pages have been deleted.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            TextButton({ model.openResponseAttempt(note.id, attempt.id); onDismiss() }, enabled = existingPages > 0) { Text("Open") }
                            TextButton({ model.openResponseAttempt(note.id, attempt.id, beside = true); onDismiss() }, enabled = existingPages > 0 && attempt.id != current?.id) { Text("Compare") }
                            TextButton({ model.newResponseAttempt(note.id, attempt.mode, attempt.id, copyForMarking = true); onDismiss() }, enabled = existingPages > 0 && existingPages == attempt.pageIds.size) { Text("Copy for marking") }
                            TextButton({ renameId = attempt.id; name = attempt.title }) { Text("Rename") }
                        }
                    }
                }
            }
        }
    }
    if (renameId != null) AlertDialog(onDismissRequest = { renameId = null }, title = { Text("Attempt name") },
        text = {
            OutlinedTextField(name, { name = it.take(120) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    if (name.isNotBlank()) { model.renameResponseAttempt(note.id, renameId!!, name); renameId = null }
                }))
        },
        confirmButton = { TextButton({ model.renameResponseAttempt(note.id, renameId!!, name); renameId = null }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton({ renameId = null }) { Text("Cancel") } })
}

/** Available from marking, a response, or the library; ordinary worksheets can have actions too. */
@Composable internal fun FeedbackActionsPanel(notes: List<Notebook>, model: FolioViewModel, onDismiss: () -> Unit,
    sourceNote: Notebook? = null, sourcePage: NotePage? = null) {
    var subject by rememberSaveable { mutableStateOf(sourceNote?.exam?.subjectLabel.orEmpty()) }
    var allSubjects by rememberSaveable { mutableStateOf(sourceNote == null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var sourceTextId by rememberSaveable { mutableStateOf<String?>(null) }
    var practice by rememberSaveable { mutableStateOf(FeedbackPractice.PARAGRAPH) }
    var removeNote by rememberSaveable { mutableStateOf<String?>(null) }
    var removeAction by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = notes.filter { allSubjects || it.exam.subjectLabel == subject }.flatMap { note ->
        note.feedbackActions.filter { showCompleted || !it.done }.map { note to it }
    }
    FolioPanel(title = "Feedback actions", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            if (sourceNote != null && sourcePage != null) {
                Text("New action · ${sourcePage.title.ifBlank { "Page ${sourceNote.pages.indexOfFirst { it.id == sourcePage.id } + 1}" }}", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(text, { text = it.take(2000); sourceTextId = null }, label = { Text("What should you improve?") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                val comments = sourcePage.texts.filter { it.text.isNotBlank() && Marking.markValue(it.text) == null }
                if (comments.isNotEmpty()) {
                    var chooseComment by rememberSaveable { mutableStateOf(false) }
                    TextButton({ chooseComment = !chooseComment }) { Text(if (chooseComment) "Hide page comments" else "Use a comment from this page") }
                    if (chooseComment) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        comments.forEach { comment -> TextButton({ text = comment.text.take(2000); sourceTextId = comment.id; chooseComment = false }) {
                            Text(comment.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    FeedbackPractice.entries.forEach { item -> FilterChip(practice == item, { practice = item }, { Text(item.label) }) }
                }
                Button({ model.addFeedbackAction(sourceNote.id, sourcePage.id, text, practice, sourceTextId); text = ""; sourceTextId = null }, enabled = text.isNotBlank()) { Text("Add action") }
                HorizontalDivider()
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                FilterChip(allSubjects, { allSubjects = true }, { Text("All subjects") })
                notes.map { it.exam.subjectLabel }.distinct().sorted().forEach { label ->
                    FilterChip(!allSubjects && subject == label, { allSubjects = false; subject = label }, { Text(label.ifBlank { "No subject" }) })
                }
                FilterChip(showCompleted, { showCompleted = !showCompleted }, { Text("Include completed") })
            }
            if (rows.isEmpty()) EmptyHint("No ${if (showCompleted) "" else "open "}actions here. Turn a marking comment into a focused practice task.")
            rows.forEach { (note, action) ->
                val pageIndex = note.pages.indexOfFirst { it.id == action.pageId }
                val attempt = note.longResponse?.attempts?.find { it.id == action.practiceAttemptId }
                val practiceAvailable = attempt?.pageIds?.any { id -> note.pages.any { it.id == id } } == true
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        Text(note.title, style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(action.done, { model.completeFeedbackAction(note.id, action.id, it) })
                            Text(action.text, Modifier.weight(1f))
                        }
                        Text(action.practice.label, style = MaterialTheme.typography.bodySmall)
                        if (pageIndex < 0) Text("The original page was deleted; feedback is retained.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            TextButton({ model.openAt(note.id, pageIndex); onDismiss() }, enabled = pageIndex >= 0) { Text("Source page") }
                            TextButton({
                                if (practiceAvailable) model.openResponseAttempt(note.id, attempt.id)
                                else model.newResponseAttempt(note.id, action.practice.mode, actionId = action.id)
                                onDismiss()
                            }) { Text(if (practiceAvailable) "Resume practice" else "Practise") }
                            TextButton({ removeNote = note.id; removeAction = action.id }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
    if (removeAction != null) AlertDialog(onDismissRequest = { removeAction = null }, title = { Text("Remove feedback action?") },
        text = { Text("The original comment and any practice pages are kept.") },
        confirmButton = { TextButton({ model.removeFeedbackAction(removeNote!!, removeAction!!); removeAction = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") } },
        dismissButton = { TextButton({ removeAction = null }) { Text("Cancel") } })
}
