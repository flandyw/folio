@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folio.notes.app.FolioViewModel
import com.folio.notes.exam.ExamField
import com.folio.notes.exam.ExamReclassify
import com.folio.notes.input.guardUiTouches
import com.folio.notes.model.hiddenFromLibrary
import com.folio.notes.ui.FolioIcons

private fun ExamField.title() = when (this) {
    ExamField.SUBJECT -> "Subject"; ExamField.YEAR -> "Year"; ExamField.COMPANY -> "Source"
    ExamField.TYPE -> "Type"; ExamField.MARKS_TOTAL -> "Marks"
}

/**
 * Settings › Library › Reclassify notebooks: pick notebooks, run the classifier (optionally with ChatGPT) in the
 * background, then review every suggested field before anything is written.
 */
@Composable
internal fun ReclassifyDialog(model: FolioViewModel, onManageAi: () -> Unit, onDismiss: () -> Unit) {
    val reclassifier = model.reclassifier
    val run by reclassifier.state.collectAsState()
    val state by model.state.collectAsState()
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 880.dp).fillMaxWidth().height(maxHeight.coerceAtMost(800.dp)).guardUiTouches(),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reclassify notebooks", style = MaterialTheme.typography.headlineSmall)
                            Text(when {
                                run.running -> "Keeps going if you close this"
                                run.ready -> "Review the suggestions. Nothing changes until you apply them."
                                else -> "Choose the notebooks to check"
                            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onDismiss, shapes = IconButtonDefaults.shapes()) { Icon(FolioIcons.Close, "Close") }
                    }
                    HorizontalDivider()
                    when {
                        run.running -> Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            ContainedLoadingIndicator(Modifier.size(64.dp))
                            Text("Checked ${run.done} of ${run.total}…", style = MaterialTheme.typography.titleMedium)
                            Text("${run.proposals.size} with suggested changes so far", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            com.folio.notes.chatgpt.ChatGptStreamText(model.chatGpt, Modifier.widthIn(max = 480.dp))
                            OutlinedButton(reclassifier::cancel, shapes = ButtonDefaults.shapes()) { Text("Stop and review what's found") }
                        }
                        run.ready -> Review(run, reclassifier, Modifier.weight(1f))
                        else -> Choose(model, onManageAi, state.notes.filter { !it.hiddenFromLibrary }, run.applied, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Choose(model: FolioViewModel, onManageAi: () -> Unit, notes: List<com.folio.notes.model.Notebook>, applied: String?, modifier: Modifier) {
    var selected by rememberSaveable { mutableStateOf(notes.filter { note -> ExamReclassify.fields.any { !ExamReclassify.has(note.exam, it) } }.map { it.id }.toSet()) }
    var query by rememberSaveable { mutableStateOf("") }
    val ai by model.chatGpt.state.collectAsState()
    // Read from state so switching provider here updates the switch and model list at once.
    val available = ai.accounts.any { it.client == ai.active && it.connected && it.plan }
    var useAi by rememberSaveable { mutableStateOf(available) }
    val shown = notes.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }.sortedBy { it.title.lowercase() }
    Column(modifier) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            applied?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.large,
                placeholder = { Text("Search notebooks") }, leadingIcon = { Icon(FolioIcons.Search, null) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(false, { selected = notes.map { it.id }.toSet() }, { Text("All") })
                FilterChip(false, { selected = notes.filter { note -> ExamReclassify.fields.any { !ExamReclassify.has(note.exam, it) } }.map { it.id }.toSet() }, { Text("Missing details") })
                FilterChip(false, { selected = notes.filter { note -> note.pages.any { it.pdfIndex != null } }.map { it.id }.toSet() }, { Text("PDFs") })
                FilterChip(false, { selected = emptySet() }, { Text("None") })
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(shown, key = { it.id }) { note ->
                val on = note.id in selected
                ListItem(headlineContent = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(note.exam.summaryLine().ifBlank { "No exam details" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Checkbox(on, null) },
                    modifier = Modifier.toggleable(on, role = Role.Checkbox) { selected = if (it) selected + note.id else selected - note.id })
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ai.accounts.isNotEmpty()) com.folio.notes.chatgpt.ChatGptProviderPicker(ai, model.chatGpt, onManageAi)
            if (available) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask ${model.chatGpt.name} when unsure", style = MaterialTheme.typography.titleSmall)
                    Text("Sends the cover text of uncertain notebooks. Uses your plan or key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(useAi, { useAi = it })
            }
            if (available && useAi) com.folio.notes.chatgpt.ClassifierModelControls(model.chatGpt, Modifier.fillMaxWidth())
            if (!available) TextButton(onManageAi, shapes = ButtonDefaults.shapes()) { Text("Set up AI to double-check uncertain notebooks") }
            Button({ model.reclassifier.start(selected, useAi && available) }, modifier = Modifier.align(Alignment.End), enabled = selected.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                Icon(FolioIcons.Sparkle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Check ${selected.size} notebook${if (selected.size == 1) "" else "s"}")
            }
        }
    }
}

@Composable
private fun Review(run: ReclassifyState, reclassifier: LibraryReclassifier, modifier: Modifier) {
    val chosen = run.proposals.count { it.chosen.isNotEmpty() }
    Column(modifier) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(buildString {
                append("${run.proposals.size} of ${run.done} checked notebooks have suggestions.")
                if (run.aiFailures > 0) append(" AI couldn't check ${run.aiFailures}; device results are shown for those.")
            }, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(false, reclassifier::chooseGaps, { Text("Only fill empty fields") })
                FilterChip(false, { reclassifier.chooseAll(null, true) }, { Text("Replace existing too") })
                FilterChip(false, { reclassifier.chooseAll(null, false) }, { Text("Clear") })
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (run.proposals.isEmpty()) item { Text("Everything already matches what Folio would suggest.", Modifier.padding(8.dp)) }
            items(run.proposals, key = { it.noteId }) { proposal ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        val all = proposal.chosen.containsAll(proposal.changed)
                        Row(Modifier.fillMaxWidth().toggleable(all, role = Role.Checkbox) { reclassifier.chooseAll(proposal.noteId, it) }.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(all, null)
                            Text(proposal.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (proposal.byAi) "AI" else "On device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ExamReclassify.fields.filter { it in proposal.changed }.forEach { field ->
                            val on = field in proposal.chosen
                            val before = ExamReclassify.label(proposal.current, field)
                            Row(Modifier.fillMaxWidth().toggleable(on, role = Role.Checkbox) { reclassifier.choose(proposal.noteId, field, it) }.padding(start = 36.dp, end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(on, null)
                                Text(field.title(), Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
                                if (before.isNotBlank()) Text(before, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = if (on) TextDecoration.LineThrough else null)
                                Text(if (before.isBlank()) "add ${ExamReclassify.label(proposal.suggested, field)}" else "→ ${ExamReclassify.label(proposal.suggested, field)}",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(reclassifier::discard, shapes = ButtonDefaults.shapes()) { Text("Discard") }
            Button(reclassifier::apply, enabled = chosen > 0, shapes = ButtonDefaults.shapes()) {
                Text("Apply to $chosen notebook${if (chosen == 1) "" else "s"}")
            }
        }
    }
}
