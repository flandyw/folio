@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.progress

import com.folio.notes.EmptyHint
import com.folio.notes.ExamAttempt
import com.folio.notes.Notebook
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.FolioPanel
import com.folio.notes.FolioSpacing
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

/** One notebook result waiting to be confirmed; every field is editable text until it is saved. */
private data class ResultDraft(val id: String, val source: String, val notebookId: String?, val include: Boolean,
    val title: String, val subject: String, val provider: String, val year: String, val paper: String,
    val score: String, val max: String, val date: String) {
    val facts get() = ResultFacts(subject.trim(), provider.trim(), title.trim(), year.trim().toIntOrNull(), paper.trim(),
        score.trim().toDoubleOrNull(), max.trim().toDoubleOrNull(), runCatching { LocalDate.parse(date.trim()) }.getOrNull(), notebookId)

    fun problem(): String? {
        val earned = score.trim().toDoubleOrNull(); val total = max.trim().toDoubleOrNull(); val y = year.trim().toIntOrNull()
        return when {
            title.isBlank() -> "Enter a title."
            subject.isBlank() || paper.isBlank() -> "Enter a subject and paper."
            y == null || y !in 1990..2100 -> "Enter a year from 1990 to 2100."
            facts.date == null -> "Enter the date as YYYY-MM-DD."
            earned == null || total == null || !earned.isFinite() || !total.isFinite() || total <= 0 || earned !in 0.0..total ->
                "Marks must be between 0 and a positive total."
            notebookId != null && (earned % 1 != 0.0 || total % 1 != 0.0 || total > Int.MAX_VALUE) -> "Notebook marks use whole numbers."
            else -> null
        }
    }

    fun encode(): JSONObject {
        val original = JSONObject(source)
        // Always a plain YYYY-MM-DD: Focal's web app cannot read a full timestamp here.
        return original.put("title", title.trim()).put("subject", subject.trim()).put("provider", provider.trim())
            .put("examYear", year.trim().toInt()).put("paper", paper.trim())
            .put("completedAt", date.trim())
            .put("rawScore", score.trim().toDouble()).put("rawMax", max.trim().toDouble())
    }

    companion object {
        fun from(e: LoggedExam, include: Boolean) = ResultDraft(e.id, e.originalJson, e.notebookId, include, e.title, e.subject, e.provider,
            e.examYear.toString(), e.paper, plain(e.rawScore), plain(e.rawMax), e.completedAt.take(10))
        private fun plain(v: Double) = if (v % 1 == 0.0) v.toLong().toString() else v.toString()
    }
}

/**
 * Review step before notebook marks join the log. Each result can be unticked or corrected, and every one is
 * checked against what is already logged so a result entered by hand is not added a second time. A result
 * that is already in the log (or is not wanted) can be dismissed, which hides it via [onDismissResult].
 * [singleResult] is the review opened straight after recording a mark: same form, worded for one save to Focal.
 */
@Composable internal fun NotebookResultsDialog(candidates: List<LoggedExam>, logged: List<LoggedExam>, subjects: List<String>,
    manager: ExamProgressManager, user: String?, onDismiss: () -> Unit, onSaved: (List<String>) -> Unit,
    dismissed: Set<String> = emptySet(), onDismissResult: ((String) -> Unit)? = null, onRestoreResult: ((String) -> Unit)? = null,
    singleResult: Boolean = false) {
    val owner = remember { user }
    val drafts = remember {
        mutableStateListOf<ResultDraft>().apply {
            candidates.forEach { c ->
                add(ResultDraft.from(c, include = LoggedMatcher.best(ResultFacts.of(c), logged)?.level != MatchLevel.LIKELY))
            }
        }
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val savedIds = remember { mutableListOf<String>() }
    val scope = rememberCoroutineScope()
    // Dismissed rows stay in the draft list (so their edits survive a restore) but are kept out of the review.
    val shown = drafts.filter { it.id !in dismissed }
    val hidden = drafts.filter { it.id in dismissed }
    var showHidden by remember { mutableStateOf(false) }
    val matches = drafts.associate { it.id to LoggedMatcher.best(it.facts, logged) }
    val selected = shown.count { it.include }
    val likely = shown.count { matches[it.id]?.level == MatchLevel.LIKELY }
    val edited = drafts.any { d -> candidates.find { it.id == d.id }?.let { ResultDraft.from(it, d.include) != d } == true }
    fun dismiss() { if (!busy) { if (edited) discard = true else onDismiss() } }
    fun update(id: String, change: (ResultDraft) -> ResultDraft) {
        val i = drafts.indexOfFirst { it.id == id }; if (i >= 0) drafts[i] = change(drafts[i]); error = null
    }
    fun confirm() {
        val chosen = shown.filter { it.include }
        chosen.firstNotNullOfOrNull { d -> d.problem()?.let { d to it } }?.let { (d, why) ->
            expanded = d.id; error = "${d.title.ifBlank { "Result" }}: $why"; return
        }
        busy = true
        scope.launch {
            try {
                for (d in chosen) {
                    if (!manager.save("attempts", d.id, d.encode(), owner)) { error = manager.state.value.error; return@launch }
                    savedIds += d.id; drafts.removeAll { it.id == d.id }
                }
                onSaved(savedIds.toList()); onDismiss()
            } finally { busy = false }
        }
    }
    FolioPanel(if (singleResult) "Save mark to Focal" else "Add notebook results", ::dismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Text(if (singleResult) (if (user == null) "You're signed out of Focal, so this saves on this device until you sign in. Check the details first."
                else "Check the details, then save this mark to the exam log in Focal.")
                else "Choose what goes in the exam log and correct anything first. Results that look like ones you have already logged are unticked.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                StatusPill("$selected of ${shown.size} selected", strong = selected > 0)
                if (likely > 0) StatusPill("$likely likely logged", Icons.Rounded.ContentCopy)
                Spacer(Modifier.weight(1f))
                TextButton({ val all = selected < shown.size; shown.forEach { update(it.id) { d -> d.copy(include = all) } } }, enabled = !busy && shown.isNotEmpty()) {
                    Text(if (selected < shown.size) "Select all" else "Select none")
                }
            }
            shown.forEach { d ->
                val match = matches[d.id]
                val open = expanded == d.id
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(d.include, { c -> update(d.id) { it.copy(include = c) } }, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(d.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val f = d.facts
                                Text(listOfNotNull(d.provider.ifBlank { null }, d.year.ifBlank { null }, d.paper.ifBlank { null }).joinToString(" ") +
                                    (if (f.score != null && f.max != null && f.max > 0) " · ${f.score.display(0)}/${f.max.display(0)} (${(f.score / f.max * 100).roundToInt()}%)" else ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ expanded = if (open) null else d.id }) {
                                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.Edit, if (open) "Hide editor" else "Edit ${d.title}")
                            }
                            if (onDismissResult != null) IconButton({
                                if (expanded == d.id) expanded = null; error = null
                                onDismissResult(d.id)
                            }, enabled = !busy) {
                                Icon(Icons.Rounded.Close, "Dismiss ${d.title}")
                            }
                        }
                        if (match != null) MatchNotice(match)
                        AnimatedVisibility(open) {
                            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                OutlinedTextField(d.title, { v -> update(d.id) { it.copy(title = v) } }, Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = androidx.compose.ui.text.input.ImeAction.Next), label = { Text("Title") }, singleLine = true)
                                ChoiceField("Subject", d.subject, subjects, { v -> update(d.id) { it.copy(subject = v) } }, true)
                                ChoiceField("Provider", d.provider, defaultProviderOrder, { v -> update(d.id) { it.copy(provider = v) } }, true)
                                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    OutlinedTextField(d.year, { v -> update(d.id) { it.copy(year = v.filter(Char::isDigit).take(4)) } }, Modifier.weight(1f),
                                        label = { Text("Year") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next))
                                    OutlinedTextField(d.paper, { v -> update(d.id) { it.copy(paper = v) } }, Modifier.weight(1f), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = androidx.compose.ui.text.input.ImeAction.Next), label = { Text("Paper") }, singleLine = true)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    OutlinedTextField(d.score, { v -> update(d.id) { it.copy(score = v) } }, Modifier.weight(1f), label = { Text("Marks") },
                                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Next))
                                    OutlinedTextField(d.max, { v -> update(d.id) { it.copy(max = v) } }, Modifier.weight(1f), label = { Text("Out of") },
                                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Next))
                                }
                                OutlinedTextField(d.date, { v -> update(d.id) { it.copy(date = v) } }, Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                            }
                        }
                    }
                }
            }
            if (hidden.isNotEmpty() && onRestoreResult != null) {
                TextButton({ showHidden = !showHidden }) { Text(if (showHidden) "Hide dismissed" else "Show dismissed (${hidden.size})") }
                if (showHidden) hidden.forEach { d ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(d.provider.ifBlank { null }, d.year.ifBlank { null }, d.paper.ifBlank { null }).joinToString(" "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ error = null; onRestoreResult(d.id) }, enabled = !busy) {
                                Icon(Icons.Rounded.Restore, "Restore ${d.title}")
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) EmptyHint("Nothing left to add.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.End) {
            TextButton(::dismiss, enabled = !busy) { Text("Cancel") }
            Button(::confirm, enabled = !busy && selected > 0) {
                Text(if (busy) "Saving…" else if (singleResult) "Save to Focal" else "Add $selected to log")
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard your edits?") },
        confirmButton = { TextButton(onDismiss) { Text("Discard") } }, dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
}

/** The confidence badge plus what it matched and why, so a skipped result is never a mystery. */
@Composable private fun MatchNotice(match: LoggedMatch) {
    val likely = match.level == MatchLevel.LIKELY
    Surface(shape = MaterialTheme.shapes.medium,
        color = if (likely) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (likely) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                Icon(if (likely) Icons.Rounded.ContentCopy else Icons.Rounded.HelpOutline, null, Modifier.size(16.dp))
                Text("${if (likely) "Likely already logged" else "Possible match"} · ${(match.confidence * 100).roundToInt()}% sure",
                    style = MaterialTheme.typography.labelLarge)
            }
            Text("${match.existing.title} · ${friendlyDate(match.existing.completedAt)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (match.reasons.isNotEmpty()) Text(match.reasons.joinToString(", ").replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Confirm one mark just recorded in a notebook before it goes to Focal. The notebook keeps the mark either way;
 * closing this only skips the Focal save, which can be made later from Progress.
 */
@Composable internal fun ExamRecordReview(note: Notebook, attempt: ExamAttempt, onDismiss: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalProgress
    val state by manager.state.collectAsStateWithLifecycle()
    val exam = remember(attempt.id) { notebookExam(note, attempt) } ?: return
    val subjects = remember(state.catalog, exam.subject) {
        (state.catalog.exams.map { it.subject } + state.catalog.references.map { it.subject } + exam.subject).distinct().sorted()
    }
    NotebookResultsDialog(listOf(exam), state.cache.exams, subjects, manager, state.userId, onDismiss, onSaved = {}, singleResult = true)
}
