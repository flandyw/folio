package com.folio.notes.vcaa

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.exam.ExamType
import com.folio.notes.exam.VceSubject
import com.folio.notes.exam.subjectColor
import com.folio.notes.library.semanticsLabel
import com.folio.notes.model.Notebook
import com.folio.notes.model.Paper
import com.folio.notes.ui.EmptyHint
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioScreenHeading
import com.folio.notes.ui.FolioSectionHeading
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The catalogue is parsed and indexed once per process; opening the screen again is instant. */
internal object VcaaCatalog {
    @Volatile private var cached: VcaaIndex? = null

    suspend fun load(context: android.content.Context): VcaaIndex = cached ?: withContext(Dispatchers.Default) {
        synchronized(this@VcaaCatalog) {
            cached ?: VcaaIndex(VcaaLibrary.parse(context.applicationContext.assets.open("vcaa-exam-resources.json")
                .bufferedReader().use { it.readText() })).also { cached = it }
        }
    }
}

private sealed interface VcaaRow {
    data class Year(val year: Int?, val count: Int) : VcaaRow
    data class Paper(val exam: VcaaExam) : VcaaRow
}

/**
 * Official past papers, imported directly as PDF notebooks with their exam details filled in. A
 * sidebar destination of its own: subjects down the side (a chip row on a phone), one search box
 * over every paper, and papers grouped by year. Searching runs off the main thread over an index
 * built once ([VcaaIndex]), so typing never waits on it.
 */
@Composable
internal fun VcaaScreen(state: FolioState, model: FolioViewModel, folderId: String?, folderName: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var loadAttempt by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    val index by produceState<VcaaIndex?>(null, loadAttempt) {
        loadError = false
        value = runCatching { VcaaCatalog.load(context) }.onFailure { loadError = true }.getOrNull()
    }
    var study by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var type by rememberSaveable { mutableStateOf<ExamType?>(null) }
    var availability by rememberSaveable { mutableStateOf(VcaaAvailability.ALL) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var reportsOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(VcaaSort.NEWEST) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var previewUrl by rememberSaveable { mutableStateOf<String?>(null) }

    val studies = remember(index, state.notes) { index?.let { VcaaIndex.yoursFirst(it.studies, state.notes) }.orEmpty() }
    val owned = remember(index, state.notes) { index?.owned(state.notes).orEmpty() }
    // Kept (not reset) while a new search runs, so the list never flashes empty between keystrokes.
    val rows by produceState<List<VcaaRow>?>(null, index, query, study, year, type, provider, reportsOnly, availability, sort, owned) {
        val ready = index ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val found = ready.search(query, study, year, type, provider, reportsOnly, availability, owned, sort)
            // Ranked results (several subjects, typed words) are not in year order, so no year headings there.
            if (sort == VcaaSort.SUBJECT || (sort == VcaaSort.NEWEST && study == null && query.isNotBlank())) found.map { VcaaRow.Paper(it) } else buildList {
                var last: Int? = -1
                found.groupBy { it.year }.forEach { (y, papers) ->
                    if (y != last) add(VcaaRow.Year(y, papers.size))
                    last = y
                    papers.forEach { add(VcaaRow.Paper(it)) }
                }
            }
        }
    }
    val paperCount = rows?.count { it is VcaaRow.Paper } ?: 0
    val canImport = !state.busy && !state.loading && !state.loadFailed
    val pickedSet = remember(picked) { picked.toSet() }
    val selectable = remember(rows, owned, index) {
        rows.orEmpty().mapNotNull { (it as? VcaaRow.Paper)?.exam }.filter { index?.ownedBy(it, owned) == null }
    }
    // Selection spans filters and subjects; imported papers drop out as ownership changes.
    val selected = remember(index, owned, pickedSet) {
        index?.exams.orEmpty().filter { it.url in pickedSet && index?.ownedBy(it, owned) == null }
    }
    val allVisiblePicked = selectable.isNotEmpty() && selectable.all { it.url in pickedSet }
    fun import(papers: List<VcaaExam>) {
        if (!canImport || papers.isEmpty()) return
        model.importVcaaPapers(folderId, papers)
        selecting = false; picked = emptyList()
    }
    fun resetFilters() {
        study = null; year = null; type = null; provider = null
        availability = VcaaAvailability.ALL; reportsOnly = false; query = ""; sort = VcaaSort.NEWEST
    }
    fun openResource(url: String) {
        linkError = null
        runCatching { uriHandler.openUri(url) }.onFailure {
            linkError = "Could not open the document. Check that a browser is installed."
        }
    }

    Column(modifier.fillMaxSize()) {
        FolioScreenHeading("VCAA exams",
            subtitle = {
                Text(
                    listOfNotNull(index?.let { "${it.exams.size} official papers" }, "importing into ${folderName ?: "Library"}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
            if (index != null) TextButton({ selecting = !selecting; picked = emptyList() }, enabled = canImport || selecting) {
                Icon(if (selecting) Icons.Rounded.Close else Icons.Rounded.Checklist, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(if (selecting) "Done" else "Select")
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize()) {
                if (wide && index != null) {
                    SubjectList(studies, index?.studyCounts.orEmpty(), index?.exams?.size ?: 0, study, { study = it },
                        Modifier.width(248.dp).fillMaxHeight())
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.padding(horizontal = FolioDestinationInset), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = FolioShapes.large,
                            placeholder = { Text(if (study != null) "Search $study" else "Search every subject, year and paper") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
                        if (!wide && index != null) LazyRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            item { FilterChip(study == null, { study = null }, { Text("All subjects") }) }
                            items(studies, key = { it }) { name -> FilterChip(study == name, { study = if (study == name) null else name }, { Text(name) }) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            YearFilter(year, index?.years.orEmpty()) { year = it }
                            index?.types.orEmpty().forEach { option ->
                                FilterChip(type == option, { type = if (type == option) null else option }, { Text(option.label) })
                            }
                            ChoiceFilter(provider ?: "All sessions", provider != null, listOf<String?>(null) + index?.providers.orEmpty(),
                                { it ?: "All sessions" }, { it == provider }) { provider = it }
                            ChoiceFilter(availability.label, availability != VcaaAvailability.ALL, VcaaAvailability.entries,
                                { it.label }, { it == availability }) { availability = it }
                            FilterChip(reportsOnly, { reportsOnly = !reportsOnly }, { Text("With reports or guides") })
                            ChoiceFilter(sort.label, sort != VcaaSort.NEWEST, VcaaSort.entries,
                                { it.label }, { it == sort }) { sort = it }
                            if (study != null || query.isNotBlank() || year != null || type != null || provider != null ||
                                availability != VcaaAvailability.ALL || reportsOnly || sort != VcaaSort.NEWEST)
                                AssistChip({ resetFilters() }, { Text("Clear all") })
                        }
                        linkError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if (index != null) Text(
                            "$paperCount ${if (paperCount == 1) "paper" else "papers"} · ${sort.label.lowercase()}",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        loadError -> Column(Modifier.padding(FolioDestinationInset)) {
                            Text("Could not load the VCAA catalogue.", color = MaterialTheme.colorScheme.error)
                            TextButton({ loadAttempt++ }) { Text("Retry") }
                        }
                        rows == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(FolioDestinationInset))
                        rows.orEmpty().isEmpty() -> Column(Modifier.padding(FolioDestinationInset), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            EmptyHint("No matching papers. Try another search or clear the filters.", Modifier.padding(top = FolioSpacing.dp16))
                            TextButton({ resetFilters() }) { Text("Clear search and filters") }
                        }
                        else -> LazyVerticalGrid(
                            GridCells.Adaptive(if (wide) 300.dp else 280.dp), Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, top = FolioSpacing.dp8,
                                bottom = FolioSpacing.dp24),
                            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
                        ) {
                            items(rows.orEmpty(), key = { row -> when (row) { is VcaaRow.Year -> "y${row.year}"; is VcaaRow.Paper -> row.exam.url } },
                                span = { row -> if (row is VcaaRow.Year) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                                contentType = { it::class }) { row ->
                                when (row) {
                                    is VcaaRow.Year -> FolioSectionHeading(row.year?.toString() ?: "Undated", Modifier.padding(top = FolioSpacing.dp8)) {
                                        Text("${row.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    is VcaaRow.Paper -> {
                                        val exam = row.exam
                                        val notebook = index?.ownedBy(exam, owned)
                                        PaperCard(exam, notebook, showStudy = study == null, selecting = selecting, checked = exam.url in pickedSet,
                                            enabled = canImport,
                                            onToggle = { picked = if (exam.url in pickedSet) picked - exam.url else picked + exam.url },
                                            onPreview = { previewUrl = exam.url },
                                            onImport = { import(listOf(exam)) }, onOpen = { notebook?.let { model.open(it.id) } },
                                            onReport = ::openResource)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        previewUrl?.let { url ->
            index?.exams?.firstOrNull { it.url == url }?.let { exam ->
                VcaaPreview(exam, index?.ownedBy(exam, owned), canImport, onImport = { import(listOf(exam)) },
                    onOpen = { index?.ownedBy(exam, owned)?.let { model.open(it.id) } }, onDismiss = { previewUrl = null })
            } ?: run { previewUrl = null }
        }
        if (selecting) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({
                        val visible = selectable.map { it.url }.toSet()
                        picked = if (allVisiblePicked) picked.filterNot { it in visible } else (picked + visible).distinct()
                    }, enabled = selectable.isNotEmpty() && canImport) {
                        Text(if (allVisiblePicked) "Deselect visible" else "Select ${selectable.size} visible")
                    }
                    Spacer(Modifier.weight(1f))
                    if (picked.isNotEmpty()) TextButton({ picked = emptyList() }) { Text("Clear selection") }
                }
                Button({ import(selected) }, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && canImport) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Text(if (selected.isEmpty()) "Choose papers" else "Import ${selected.size} selected papers")
                }
            }
        }
    }
}

@Composable
private fun SubjectList(studies: List<String>, counts: Map<String, Int>, total: Int, current: String?, onPick: (String?) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioSpacing.dp8, bottom = FolioSpacing.dp24),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
        item(key = "all") { SubjectRow("All subjects", total, null, current == null) { onPick(null) } }
        items(studies, key = { it }) { name -> SubjectRow(name, counts[name] ?: 0, VcaaLibrary.subjectFor(name), current == name) { onPick(name) } }
    }
}

@Composable
private fun SubjectRow(name: String, count: Int, subject: VceSubject?, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().semanticsLabel(if (selected) "$name, selected" else name),
        shape = FolioShapes.medium, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (subject != null) subjectColor(subject) else MaterialTheme.colorScheme.outlineVariant))
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else null)
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaperCard(
    exam: VcaaExam, owned: Notebook?, showStudy: Boolean, selecting: Boolean, checked: Boolean, enabled: Boolean,
    onToggle: () -> Unit, onPreview: () -> Unit, onImport: () -> Unit, onOpen: () -> Unit, onReport: (String) -> Unit,
) {
    val pickable = selecting && owned == null
    Surface(
        Modifier.fillMaxWidth().then(if (pickable) Modifier.toggleable(checked, enabled, Role.Checkbox, onValueChange = { onToggle() }) else Modifier),
        shape = FolioShapes.large,
        color = if (pickable && checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (pickable && checked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(subjectColor(exam.tags.subject)))
            Column(Modifier.weight(1f).padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    if (selecting) Checkbox(checked && owned == null, null, enabled = enabled && owned == null)
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(exam.year?.toString(), exam.type.label).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                        if (showStudy) Text(exam.studyName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (exam.provider != "VCAA") Text(exam.provider.removePrefix("VCAA ").ifBlank { exam.provider }, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary)
                }
                Text(exam.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    exam.tags.marksTotal?.let {
                        Text("$it marks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(FolioSpacing.dp8))
                    }
                    if (owned != null) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(FolioSpacing.dp4))
                        Text("In your library", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    var resourcesExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton({ resourcesExpanded = true }) {
                            Icon(Icons.Rounded.Description, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(FolioSpacing.dp6))
                            Text("Documents")
                        }
                        FolioMenuPopover(resourcesExpanded, { resourcesExpanded = false }, title = "Official documents") {
                            FolioMenuItem({ Text("Original exam PDF") }, {
                                resourcesExpanded = false; onReport(exam.url)
                            })
                            exam.reports.forEach { (label, url) ->
                                FolioMenuItem({ Text(label) }, { resourcesExpanded = false; onReport(url) })
                            }
                        }
                    }
                    TextButton(onPreview) {
                        Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp6))
                        Text("Preview")
                    }
                    Spacer(Modifier.weight(1f))
                    if (!selecting) {
                        if (owned != null) TextButton(onOpen, enabled = enabled) { Text("Open") }
                        else FilledTonalButton(onImport, enabled = enabled) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(FolioSpacing.dp6))
                            Text("Import")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YearFilter(year: Int?, years: List<Int>, onChange: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(year != null, { expanded = true }, { Text(year?.toString() ?: "Any year") },
            trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) })
        FolioMenuPopover(expanded, { expanded = false }, title = "Year") {
            FolioMenuItem({ Text("Any year") }, { onChange(null); expanded = false }, selected = year == null)
            years.forEach { option -> FolioMenuItem({ Text(option.toString()) }, { onChange(option); expanded = false }, selected = year == option) }
        }
    }
}

/** Shared compact menu for catalogue filters, with the current choice announced and marked. */
@Composable
private fun <T> ChoiceFilter(
    label: String, active: Boolean, options: List<T>, optionLabel: (T) -> String,
    isSelected: (T) -> Boolean, onChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(active, { expanded = true }, { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) })
        FolioMenuPopover(expanded, { expanded = false }, title = label) {
            options.forEach { option ->
                FolioMenuItem({ Text(optionLabel(option)) }, { onChange(option); expanded = false }, selected = isSelected(option))
            }
        }
    }
}
