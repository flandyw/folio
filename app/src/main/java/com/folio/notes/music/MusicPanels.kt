@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.music

import com.folio.notes.ui.FolioIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.input.guardUiTouches
import com.folio.notes.library.semanticsLabel
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

@Composable internal fun ConfirmDelete(title: String, body: String, action: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(),
        onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        dismissButton = { TextButton(dismiss, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
        confirmButton = { TextButton(confirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes()) { Text(action) } })
}

/** Whether the Score details draft would save something different; Save trims, so whitespace alone is no edit. */
internal fun MusicScore.detailsEdited(title: String, composer: String, part: String, notes: String) =
    title.trim() != this.title.trim() || composer.trim() != this.composer.trim() || part.trim() != this.part.trim() || notes.trim() != this.notes.trim()

@Composable internal fun ScoreDetailsPanel(score: MusicScore, dismiss: () -> Unit, save: (String, String, String, String) -> Unit) {
    var title by rememberSaveable(score.id) { mutableStateOf(score.title) }
    var composer by rememberSaveable(score.id) { mutableStateOf(score.composer) }
    var part by rememberSaveable(score.id) { mutableStateOf(score.part) }
    var notes by rememberSaveable(score.id) { mutableStateOf(score.notes) }
    val composerFocus = remember { FocusRequester() }
    val partFocus = remember { FocusRequester() }
    var discard by rememberSaveable(score.id) { mutableStateOf(false) }
    fun submit() { if (title.isNotBlank()) save(title.trim(), composer.trim(), part.trim(), notes.trim()) }
    fun canDismiss(): Boolean {
        val edited = score.detailsEdited(title, composer, part, notes)
        if (edited) discard = true
        return !edited
    }
    FolioPanel("Score details", dismiss, canDismiss = ::canDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = FolioSpacing.dp24, end = FolioSpacing.dp24, bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Text("${score.pages} ${if (score.pages == 1) "page" else "pages"} · ${score.marks.size} rehearsal ${if (score.marks.size == 1) "mark" else "marks"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true,
                isError = title.isBlank(), supportingText = { if (title.isBlank()) Text("Enter a title.") },
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { composerFocus.requestFocus() }))
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                OutlinedTextField(composer, { composer = it }, Modifier.weight(1f).focusRequester(composerFocus), label = { Text("Composer") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { partFocus.requestFocus() }))
                OutlinedTextField(part, { part = it }, Modifier.weight(1f).focusRequester(partFocus), label = { Text("Instrument / part") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
            }
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Rehearsal notes") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                placeholder = { Text("Bowings, cuts, who to watch for the cue…") }, minLines = 4)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                TextButton({ if (canDismiss()) dismiss() }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button(::submit, enabled = title.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Save") }
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("Discard changes to this score?") },
        text = { Text("Your edits to its details have not been saved.") },
        confirmButton = { TextButton(dismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Discard") } },
        dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
}

/** Tick scores into a set list; new ones join at the end of the running order. */
@Composable internal fun AddScoresPanel(set: MusicSet, scores: List<MusicScore>, model: MusicViewModel, dismiss: () -> Unit) {
    val filterFocus = LocalFocusManager.current
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = scores.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        .filter { it.matchesQuery(filter) }
    FolioPanel("Add to ${set.name}", dismiss) {
        Column(Modifier.padding(start = FolioSpacing.dp24, end = FolioSpacing.dp24, bottom = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text("${set.scores.size} in the set · new scores join at the end", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().semanticsLabel("Find a score"), placeholder = { Text("Find a score…") },
                leadingIcon = { Icon(FolioIcons.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton({ filter = "" }) { Icon(FolioIcons.Close, "Clear score search") } },
                singleLine = true, shape = FolioShapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { filterFocus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp12)) {
            if (shown.isEmpty()) Text(
                if (scores.isEmpty()) "Import a sheet music PDF in Music, then add it to this set list." else "No scores match “${filter.trim()}”.",
                Modifier.padding(FolioSpacing.dp12), color = MaterialTheme.colorScheme.onSurfaceVariant)
            shown.forEach { score ->
                val included = score.id in set.scores
                PickRow(included, score.title, scoreMeta(score), { ScoreFace(score, model, Modifier.width(30.dp).height(40.dp), small = true) }) {
                    model.set(set.id) { s -> s.copy(scores = if (score.id in s.scores) s.scores - score.id else (s.scores + score.id).distinct()) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.End) {
            Button(dismiss, shapes = ButtonDefaults.shapes()) { Text("Done") }
        }
    }
}

/** From a score: which set lists it belongs to, or start a new one with it. */
@Composable internal fun SetPickerPanel(score: MusicScore, sets: List<MusicSet>, model: MusicViewModel, onNewSet: () -> Unit, dismiss: () -> Unit) {
    FolioPanel("Add to set list", dismiss) {
        Text(score.title, Modifier.padding(horizontal = FolioSpacing.dp24), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(FolioSpacing.dp12)) {
            sets.forEach { set ->
                val included = score.id in set.scores
                PickRow(included, set.name, "${set.scores.size} ${if (set.scores.size == 1) "score" else "scores"}", {
                    Icon(FolioIcons.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { model.set(set.id) { s -> s.copy(scores = if (score.id in s.scores) s.scores - score.id else (s.scores + score.id).distinct()) } }
            }
            Surface(onClick = onNewSet, shape = FolioShapes.large, color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    Icon(FolioIcons.Plus, null, tint = MaterialTheme.colorScheme.primary)
                    Text("New set list with this score", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.End) {
            Button(dismiss, shapes = ButtonDefaults.shapes()) { Text("Done") }
        }
    }
}

@Composable internal fun FolderPickerPanel(score: MusicScore, folders: List<MusicFolder>, model: MusicViewModel, onNewFolder: () -> Unit, dismiss: () -> Unit) {
    FolioPanel("Move to folder", dismiss) {
        Text(score.title, Modifier.padding(horizontal = FolioSpacing.dp24), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(FolioSpacing.dp12)) {
            PickRow(score.folder == null, "No folder", "Keep it on the main shelf", single = true, leading = {
                Icon(FolioIcons.MusicLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }) { if (score.folder != null) model.moveToFolder(setOf(score.id), null); dismiss() }
            folders.forEach { folder ->
                PickRow(score.folder == folder.id, folder.name, "Folder", single = true, leading = {
                    Icon(FolioIcons.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { if (score.folder != folder.id) model.moveToFolder(setOf(score.id), folder.id); dismiss() }
            }
            Surface(onClick = onNewFolder, shape = FolioShapes.large, color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    Icon(FolioIcons.FolderAdd, null, tint = MaterialTheme.colorScheme.primary)
                    Text("New folder with this score", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.End) {
            TextButton(dismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        }
    }
}

/** One labelled selection target: a checkbox row for multi-select lists, a radio row where only one can be [single]. */
@Composable private fun PickRow(checked: Boolean, title: String, subtitle: String, leading: @Composable () -> Unit, single: Boolean = false, toggle: () -> Unit) {
    Surface(shape = FolioShapes.large, color = if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f) else Color.Transparent) {
        val select = if (single) Modifier.selectable(checked, role = Role.RadioButton, onClick = toggle)
            else Modifier.toggleable(checked, role = Role.Checkbox) { toggle() }
        Row(select.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp6),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (single) RadioButton(checked, null) else Checkbox(checked, null)
        }
    }
}
