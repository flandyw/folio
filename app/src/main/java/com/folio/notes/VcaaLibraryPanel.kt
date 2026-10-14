package com.folio.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Official past papers, imported directly as PDF notebooks with their exam details filled in. */
@Composable
internal fun VcaaLibraryPanel(state: FolioState, model: FolioViewModel, folderId: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var loadAttempt by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    val exams by produceState<List<VcaaExam>?>(null, loadAttempt) {
        value = null
        loadError = false
        val result = withContext(Dispatchers.IO) {
            runCatching { VcaaLibrary.parse(context.assets.open("vcaa-exam-resources.json").bufferedReader().use { it.readText() }) }
        }
        loadError = result.isFailure
        value = result.getOrDefault(emptyList())
    }
    val all = exams.orEmpty()
    // Studies already in your library come first.
    val studies = remember(all, state.notes) {
        val yours = state.notes.map { it.exam.subjectLabel.lowercase() }.toSet()
        all.map { it.studyName }.distinct().sortedWith(compareBy<String> { name ->
            if (name.lowercase() in yours || VcaaLibrary.subjectFor(name)?.label?.lowercase() in yours) 0 else 1
        }.thenBy { it.lowercase() })
    }
    var study by rememberSaveable { mutableStateOf<String?>(null) }
    val current = study ?: studies.firstOrNull()
    var query by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("All years") }
    var paper by rememberSaveable { mutableStateOf("All papers") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val shown = remember(all, current, query, year, paper) {
        val needle = query.trim()
        all.filter { (needle.isNotEmpty() || it.studyName == current) &&
            "${it.studyName} ${it.year ?: ""} ${it.provider} ${it.label}".contains(needle, ignoreCase = true) &&
            (year == "All years" || it.year?.toString() == year) && (paper == "All papers" || it.type.label == paper) }
    }
    val canImport = !state.busy && !state.loading && !state.loadFailed
    val selected = all.filter { it.url in picked && VcaaLibrary.imported(it, state.notes) == null }
    fun import(papers: List<VcaaExam>) {
        if (!canImport || papers.isEmpty()) return
        model.importVcaaPapers(folderId, papers)
        onDismiss()
    }
    fun toggle(exam: VcaaExam) { picked = if (exam.url in picked) picked - exam.url else picked + exam.url }

    FolioPanel("VCAA exam library", onDismiss) {
        Column(Modifier.padding(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text("Import a paper and start writing in Folio. Subject, year and exam details are filled in automatically.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Search all subjects and years") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } })
            Box {
                var expanded by remember { mutableStateOf(false) }
                OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = studies.isNotEmpty(), shape = FolioShapes.large) {
                    Icon(Icons.Rounded.School, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Text(if (query.isNotBlank()) "All subjects · search results" else current ?: "Loading subjects…",
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ExpandMore, null)
                }
                FolioMenuPopover(expanded, { expanded = false }, title = "Study") {
                    studies.forEach { name -> FolioMenuItem({ Text(name) }, {
                        study = name; query = ""; year = "All years"; expanded = false
                    }, selected = name == current && query.isBlank()) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                VcaaFilter(year, listOf("All years") + all.mapNotNull { it.year }.distinct().sortedDescending().map { it.toString() }) { year = it }
                VcaaFilter(paper, listOf("All papers") + all.map { it.type.label }.distinct()) { paper = it }
                if (year != "All years" || paper != "All papers") AssistChip({ year = "All years"; paper = "All papers" }, { Text("Clear filters") })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} ${if (shown.size == 1) "paper" else "papers"} · newest first", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ selecting = !selecting; picked = emptyList() }, enabled = canImport) { Text(if (selecting) "Cancel selection" else "Select papers") }
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp8),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            when {
                exams == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                loadError -> item {
                    Column {
                        Text("Could not load the VCAA catalogue.", color = MaterialTheme.colorScheme.error)
                        TextButton({ loadAttempt++ }) { Text("Retry") }
                    }
                }
                shown.isEmpty() -> item { EmptyHint("No matching papers. Try another search or clear the filters.", Modifier.padding(vertical = FolioSpacing.dp16)) }
            }
            // Search results span studies; keep the newest papers at the top there too.
            items(shown.sortedByDescending { it.year ?: 0 }, key = { it.url }) { exam ->
                val owned = VcaaLibrary.imported(exam, state.notes)
                val checked = exam.url in picked
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        Row(Modifier.fillMaxWidth().then(if (selecting && owned == null)
                            Modifier.toggleable(checked, enabled = canImport, role = Role.Checkbox, onValueChange = { toggle(exam) }) else Modifier),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) {
                                Checkbox(checked && owned == null, null, enabled = canImport && owned == null)
                                Spacer(Modifier.width(FolioSpacing.dp8))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(listOfNotNull(exam.year?.toString(), exam.provider.takeIf { it != "VCAA" }, exam.type.label).joinToString(" · "),
                                    style = MaterialTheme.typography.titleSmall)
                                if (query.isNotBlank()) Text(exam.studyName, style = MaterialTheme.typography.bodyMedium)
                                Text(exam.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val details = listOfNotNull(exam.tags.marksTotal?.let { "$it marks" }, owned?.let { "In your library" }).joinToString(" · ")
                                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            exam.reports.firstOrNull()?.let { (label, url) ->
                                TextButton({
                                    runCatching { uriHandler.openUri(url) }.onFailure { linkError = "Could not open the report. Check that a browser is installed." }
                                }, Modifier.weight(1f, fill = false)) {
                                    Text(if (label.contains("assessment guide", true)) "Assessment guide" else "Examiner report", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (owned != null) TextButton({ onDismiss(); model.open(owned.id) }, enabled = canImport) { Text("Open") }
                            else if (!selecting) FilledTonalButton({ import(listOf(exam)) }, enabled = canImport) {
                                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(FolioSpacing.dp6))
                                Text("Import")
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(start = FolioSpacing.dp24, end = FolioSpacing.dp24, bottom = FolioSpacing.dp16),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            state.importProgress?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, style = MaterialTheme.typography.bodySmall) }
            linkError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (selecting) Button({ import(selected) }, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && canImport) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(if (selected.isEmpty()) "Choose papers to import" else "Import ${selected.size} ${if (selected.size == 1) "paper" else "papers"}")
            }
            Text("Downloads need an internet connection. Older papers may follow a different study design.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VcaaFilter(value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip({ expanded = true }, { Text(value) }, trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) })
        FolioMenuPopover(expanded, { expanded = false }, title = options.first()) {
            options.forEach { option -> FolioMenuItem({ Text(option) }, { onChange(option); expanded = false }, selected = value == option) }
        }
    }
}
