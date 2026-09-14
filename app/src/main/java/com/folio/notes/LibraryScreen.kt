@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Modifier.semanticsLabel(label: String) = semantics { contentDescription = label }

@Composable fun LibraryScreen(state: FolioState, model: FolioViewModel, onNew: () -> Unit, onImport: () -> Unit, onImportArchive: () -> Unit, onFolder: () -> Unit, onSettings: () -> Unit) {
    var examDetails by remember { mutableStateOf<Notebook?>(null) }
    var setAssign by remember { mutableStateOf<Notebook?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var starred by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENT) }
    var kind by rememberSaveable { mutableStateOf(LibraryKind.ALL) }
    var unfiled by rememberSaveable { mutableStateOf(false) }
    var listView by rememberSaveable { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var setsExpanded by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var bulkMove by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Notebook?>(null) }
    var move by remember { mutableStateOf<Notebook?>(null) }
    var delete by remember { mutableStateOf<Notebook?>(null) }
    var folderMenu by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var deleteFolder by remember { mutableStateOf<Folder?>(null) }
    var setsPanel by remember { mutableStateOf(false) }
    var progressPanel by remember { mutableStateOf(false) }
    var redoPanel by remember { mutableStateOf(false) }
    var assignPanel by remember { mutableStateOf(false) }
    val examFilter = state.examFilter
    val notes = organizeNotebooks(state.notes, state.folderId, starred, unfiled, query, kind, sort)
        .filter { examFilter.matches(it) && (!examFilter.needsRedo || it.pages.any { page -> page.redoFlag }) }
    val visibleIds = notes.map { it.id }.toSet()
    val selection = selectedIds.filter { it in visibleIds }.toSet()
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.filter { it in visibleIds } }
    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    val filtersActive = kind != LibraryKind.ALL || unfiled || examFilter.isActive
    val folderName = state.folders.find { it.id == state.folderId }?.name
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(200.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Brand(); Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onNew, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("New notebook") }
                    Spacer(Modifier.height(12.dp))
                    NavItem("All notebooks", Icons.Rounded.GridView, !starred && !unfiled && state.folderId == null, state.notes.size) { starred = false; unfiled = false; model.folder(null) }
                    NavItem("Favorites", Icons.Rounded.StarOutline, starred, state.notes.count { it.starred }) { starred = true; unfiled = false; model.folder(null) }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("YOUR FOLDERS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onFolder, Modifier.size(48.dp)) { Icon(Icons.Rounded.CreateNewFolder, "New folder", Modifier.size(20.dp)) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.folders.forEach { folder -> NavItem(folder.name, Icons.Rounded.FolderOpen, state.folderId == folder.id, state.notes.count { it.folderId == folder.id }) { starred = false; unfiled = false; model.folder(folder.id) } }
                        if (state.folders.isEmpty()) Text("A place for every project.\nCreate your first folder.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(); NavItem("Settings", Icons.Rounded.Tune, false, null, onSettings)
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.OfflinePin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                        Text("On your device. Always yours.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LazyVerticalGrid(columns = if (listView) GridCells.Fixed(1) else GridCells.Adaptive(144.dp), modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(if (wide) 20.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(if (listView) 8.dp else 16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!wide) Brand() else Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            if (!wide) Spacer(Modifier.weight(1f))
                            if (!wide) FilledTonalIconButton(onNew, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "New notebook") }
                            Box {
                                var importMenu by remember { mutableStateOf(false) }
                                IconButton({ importMenu = true }) { Icon(Icons.Rounded.FileOpen, "Import PDF or Folio backup") }
                                DropdownMenu(importMenu, { importMenu = false }) {
                                    DropdownMenuItem({ Text("PDF document") }, { importMenu = false; onImport() }, leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) })
                                    DropdownMenuItem({ Text("Folio backup") }, { importMenu = false; onImportArchive() }, leadingIcon = { Icon(Icons.Rounded.FolderZip, null) })
                                }
                            }
                            if (!wide) IconButton(onSettings) { Icon(Icons.Rounded.Tune, "Settings") }
                        }
                        if (!wide) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(state.folderId == null && !starred && !unfiled, { model.folder(null); starred = false; unfiled = false }, { Text("All notebooks") }, leadingIcon = { Icon(Icons.Rounded.GridView, null, Modifier.size(16.dp)) })
                            FilterChip(starred, { model.folder(null); starred = !starred; unfiled = false }, { Text("Favorites") }, leadingIcon = { Icon(Icons.Rounded.StarOutline, null, Modifier.size(16.dp)) })
                            state.folders.forEach { folder -> FilterChip(state.folderId == folder.id, { starred = false; unfiled = false; model.folder(folder.id) }, { Text(folder.name) }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp)) }) }
                            AssistChip(onFolder, { Text("New folder") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                        }
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find a notebook…") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } }, singleLine = true, shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (query.isNotEmpty()) "Search results" else folderName ?: if (unfiled) "Unfiled" else if (starred) "Favorites" else "Your notebooks", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text("${notes.size}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall) }
                            Box {
                                IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort: ${sort.label}") }
                                DropdownMenu(sortMenu, { sortMenu = false }) {
                                    LibrarySort.entries.forEach { option ->
                                        DropdownMenuItem({ Text(option.label) }, { sort = option; sortMenu = false }, trailingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) })
                                    }
                                }
                            }
                            IconButton({ listView = !listView }) { Icon(if (listView) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList, if (listView) "Show covers" else "Show compact list") }
                            state.folders.find { it.id == state.folderId }?.let { folder -> Box {
                                IconButton({ folderMenu = true }) { Icon(Icons.Rounded.MoreVert, "Folder options") }
                                DropdownMenu(folderMenu, { folderMenu = false }) {
                                    DropdownMenuItem({ Text("Rename folder") }, { folderMenu = false; renameFolder = folder })
                                    DropdownMenuItem({ Text("Remove folder") }, { folderMenu = false; deleteFolder = folder })
                                }
                            } }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(filtersExpanded || filtersActive, { filtersExpanded = !filtersExpanded }, { Text(if (filtersActive) "Filters • Active" else "Filters") },
                                leadingIcon = { Icon(Icons.Rounded.FilterList, null, Modifier.size(18.dp)) },
                                trailingIcon = { Icon(if (filtersExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) })
                            TextButton({ selecting = !selecting; selectedIds = emptyList() }) { Text(if (selecting) "Done" else "Select") }
                            TextButton({ setsPanel = true }) { Text("Exam sets") }
                            TextButton({ progressPanel = true }) { Text("Progress") }
                            TextButton({ redoPanel = true }) { Text("Redo") }
                        }
                        if (filtersActive) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Filtered results · ${notes.size} notebooks", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ kind = LibraryKind.ALL; unfiled = false; model.setExamFilter(ExamFilter()) }) { Text("Reset filters") }
                        }
                        state.daysToExam?.let { days ->
                            Text(if (days == 0) "Exam today" else "Exam in $days day${if (days == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                        if (filtersExpanded) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(unfiled, { unfiled = !unfiled; model.folder(null) }, { Text("Unfiled") })
                                LibraryKind.entries.forEach { option -> FilterChip(kind == option, { kind = option }, { Text(option.label) }) }
                            }
                            // Exam filters: one chip per subject that is actually in use, then year,
                            // company and status, so the shelf narrows to "Methods · 2022 · VCAA".
                            val examNotes = state.notes.filter { it.exam.isTagged || it.setId != null }
                            if (examNotes.isNotEmpty()) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    VceSubject.entries.forEach { subject ->
                                        val count = examNotes.count { it.exam.subject == subject }
                                        if (count > 0) {
                                            SubjectChip(subject, examFilter.subject == subject, {
                                                model.setExamFilter(if (examFilter.subject == subject) examFilter.copy(subject = null) else examFilter.copy(subject = subject))
                                            })
                                        }
                                    }
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    examNotes.mapNotNull { it.exam.year }.distinct().sortedDescending().take(6).forEach { year ->
                                        FilterChip(examFilter.year == year, {
                                            model.setExamFilter(if (examFilter.year == year) examFilter.copy(year = null) else examFilter.copy(year = year))
                                        }, { Text("$year") })
                                    }
                                    examNotes.map { it.exam.company }.filter { it.isNotBlank() }.distinct().take(6).forEach { company ->
                                        FilterChip(examFilter.company == company, {
                                            model.setExamFilter(if (examFilter.company == company) examFilter.copy(company = null) else examFilter.copy(company = company))
                                        }, { Text(company) })
                                    }
                                    ExamStatus.entries.forEach { status ->
                                        FilterChip(examFilter.status == status, {
                                            model.setExamFilter(if (examFilter.status == status) examFilter.copy(status = null) else examFilter.copy(status = status))
                                        }, { Text(status.label) })
                                    }
                                    FilterChip(examFilter.belowShare != null, {
                                        model.setExamFilter(if (examFilter.belowShare != null) examFilter.copy(belowShare = null) else examFilter.copy(belowShare = 0.7f))
                                    }, { Text("Under 70%") })
                                }
                            }
                        }
                        if (selecting) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${selection.size} selected", style = MaterialTheme.typography.labelMedium)
                                TextButton({ selectedIds = if (selection.size == notes.size) emptyList() else notes.map { it.id } }) { Text(if (selection.size == notes.size && notes.isNotEmpty()) "Deselect all" else "Select all") }
                                TextButton({ bulkMove = true }, enabled = selection.isNotEmpty()) { Text("Move") }
                                TextButton({ assignPanel = true }, enabled = selection.isNotEmpty()) { Text("Exam set") }
                                val allStarred = selection.isNotEmpty() && notes.filter { it.id in selection }.all { it.starred }
                                TextButton({ model.favoriteNotebooks(selection, !allStarred) }, enabled = selection.isNotEmpty()) { Text(if (allStarred) "Unfavorite" else "Favorite") }
                        }
                        if (state.saveFailed) FilledTonalButton(model::retrySave) { Text("Changes need saving · Retry save") }
                    }
                }
                // Keep grouped papers available without pushing the notebook shelf off screen.
                val groups = groupExamSets(state.sets, state.notes)
                if (groups.isNotEmpty() && !selecting) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ setsExpanded = !setsExpanded }) {
                                Icon(if (setsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Exam sets (${groups.size})")
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton({ setsPanel = true }) { Text("Manage sets") }
                        }
                        if (setsExpanded) groups.forEach { group -> ExamSetCard(group, openSet = { setsPanel = true }, openNote = { model.open(it.id) }) }
                    }
                }
                if (notes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(124.dp, 140.dp)) { NotebookCover(Notebook(title = "Your next idea", cover = 1), Modifier.fillMaxSize()) }
                            Text(if (query.isNotEmpty() || filtersActive) "No notebooks found" else if (starred) "Keep the good ones close" else "Good things start here.", style = MaterialTheme.typography.headlineMedium)
                            Text(if (query.isNotEmpty() || filtersActive) "Try another search or clear your filters." else if (starred) "Tap the star on a notebook to find it here." else "Make space for your first idea. Create a notebook\nor bring a PDF along.", style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (query.isNotEmpty() || filtersActive) TextButton({ query = ""; kind = LibraryKind.ALL; unfiled = false; model.setExamFilter(ExamFilter()) }) { Text("Clear filters") }
                            if (query.isEmpty() && !starred && !filtersActive) TextButton(onNew) { Text("Start a notebook"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
                        }
                    }
                }
                items(notes, key = { it.id }) { note ->
                    val open = { if (selecting) toggleSelection(note.id) else model.open(note.id) }
                    val folder = state.folders.find { it.id == note.folderId }?.name
                    Column {
                        if (selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(note.id in selection, { toggleSelection(note.id) }, Modifier.semanticsLabel("Select ${note.title}"))
                            Text("Select notebook", style = MaterialTheme.typography.labelSmall)
                        }
                        if (listView) Surface(onClick = open, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NotebookListThumbnail(note, model.thumbnails, Modifier.width(38.dp).height(50.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text("${folder ?: "Unfiled"} · ${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (!selecting) {
                                    IconButton({ model.star(note) }) { Icon(if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (note.starred) "Remove from favorites" else "Add to favorites") }
                                    NotebookMenu({ rename = note }, { move = note }, { delete = note }, { examDetails = note }, { setAssign = note })
                                }
                            }
                        } else                        NotebookCard(note, model.thumbnails, folder, open, { model.star(note) }, { rename = note }, { move = note }, { delete = note }, { examDetails = note }, { setAssign = note }, selecting, note.pages.count { it.redoFlag })
                    }
                }

            }
        }
    }
    if (assignPanel) AlertDialog(onDismissRequest = { assignPanel = false }, title = { Text("Add ${selection.size} to an exam set") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton({ model.assignToExamSet(selection, null); assignPanel = false; selectedIds = emptyList() }, enabled = selection.isNotEmpty()) { Text("Remove from set") }
            state.sets.forEach { set -> TextButton({ model.assignToExamSet(selection, set.id); assignPanel = false; selectedIds = emptyList() }, enabled = selection.isNotEmpty()) { Text(set.autoName()) } }
            if (state.sets.isEmpty()) Text("No sets yet. Create one with Manage sets.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton({ assignPanel = false }) { Text("Cancel") } })
    examDetails?.let { note ->
        ExamDetailsPanel(
            note = note,
            onDismiss = { examDetails = null },
            onSave = { tags -> model.updateExamTags(note.id, tags); examDetails = null },
            onRecordMark = { attempt -> model.recordAttempt(note.id, attempt) },
            onDeleteAttempt = { attempt -> model.deleteAttempt(note.id, attempt.id) },
            suggestedSeconds = state.lastTimedSeconds
        )
    }
    setAssign?.let { note ->
        AlertDialog(onDismissRequest = { setAssign = null }, title = { Text("${note.title} — exam set") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton({ model.assignToExamSet(setOf(note.id), null); setAssign = null }) { Text("No set") }
                state.sets.forEach { set -> TextButton({ model.assignToExamSet(setOf(note.id), set.id); setAssign = null }) { Text(set.autoName()) } }
                if (state.sets.isEmpty()) Text("No sets yet. Create one with Manage sets.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton({ setAssign = null }) { Text("Cancel") } })
    }
    if (setsPanel) ExamSetsPanel(
        groups = groupExamSets(state.sets, state.notes),
        onDismiss = { setsPanel = false },
        onCreate = { name, subject, year, company -> model.createExamSet(name, subject, year, company) },
        onDelete = { model.deleteExamSet(it) },
        openNote = { setsPanel = false; model.open(it.id) }
    )
    if (progressPanel) ExamProgressPanel(state.notes) { progressPanel = false }
    if (redoPanel) RedoReviewPanel(state.notes, { redoPanel = false }, { id, index -> redoPanel = false; model.openAt(id, index) })
    if (bulkMove) AlertDialog(onDismissRequest = { bulkMove = false }, title = { Text("Move ${selection.size} notebooks") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton({ model.moveNotebooks(selection, null); bulkMove = false; selectedIds = emptyList() }, enabled = selection.isNotEmpty()) { Text("Unfiled") }
            state.folders.forEach { folder -> TextButton({ model.moveNotebooks(selection, folder.id); bulkMove = false; selectedIds = emptyList() }, enabled = selection.isNotEmpty()) { Text(folder.name) } }
            if (state.folders.isEmpty()) Text("Create a folder using New folder, then move notebooks here.")
        }
    }, confirmButton = { TextButton({ bulkMove = false }) { Text("Cancel") } })
    rename?.let { note -> NameDialog("Rename notebook", "A name that feels right.", note.title, "Save", { rename = null }) { model.rename(note, it); rename = null } }
    renameFolder?.let { folder -> NameDialog("Rename folder", "Keep your workspace organized.", folder.name, "Save", { renameFolder = null }) { model.renameFolder(folder, it); renameFolder = null } }
    deleteFolder?.let { folder -> AlertDialog(onDismissRequest = { deleteFolder = null }, title = { Text("Remove “${folder.name}”?") }, text = { Text("Your notebooks will stay in All notebooks. Only this folder is removed.") }, dismissButton = { TextButton({ deleteFolder = null }) { Text("Cancel") } }, confirmButton = { TextButton({ model.deleteFolder(folder); deleteFolder = null }) { Text("Remove folder") } }) }
    move?.let { note -> AlertDialog(onDismissRequest = { move = null }, title = { Text("Move notebook") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton({ model.move(note, null); move = null }) { Icon(Icons.Rounded.GridView, null); Spacer(Modifier.width(12.dp)); Text("No folder") }
            state.folders.forEach { folder -> TextButton({ model.move(note, folder.id); move = null }) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(12.dp)); Text(folder.name) } }
        }
    }, confirmButton = { TextButton({ move = null }) { Text("Cancel") } }) }
    delete?.let { note -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete “${note.title}”?") }, text = { Text("This removes the notebook and its pages from this device. Export a copy first if you want to keep it.") }, dismissButton = { TextButton({ delete = null }) { Text("Keep notebook") } }, confirmButton = { TextButton({ model.delete(note); delete = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } }) }
}

@Composable private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) { Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.padding(9.dp).size(23.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        Text("folio", fontFamily = FontFamily.Serif, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
    }
}
@Composable private fun NavItem(title: String, icon: ImageVector, selected: Boolean, count: Int?, action: () -> Unit) {
    Surface(onClick = action, shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp)); Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            count?.let { Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun NotebookCard(note: Notebook, thumbnails: PageThumbnailCache, folder: String?, open: () -> Unit, star: () -> Unit, rename: () -> Unit, move: () -> Unit, delete: () -> Unit, examDetails: () -> Unit = {}, assignSet: () -> Unit = {}, selecting: Boolean = false, redoCount: Int = 0) {
    Column {
        Box {
            NotebookFace(note, thumbnails, Modifier.fillMaxWidth().aspectRatio(1.1f).clickable(onClickLabel = "Open ${note.title}", onClick = open))
            if (!selecting) IconButton(star, Modifier.align(Alignment.TopEnd).padding(4.dp)) { Icon(if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (note.starred) "Remove from favorites" else "Add to favorites", tint = Color(0xFF343931), modifier = Modifier.size(21.dp)) }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = open)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"} · ${folder ?: SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(note.updated))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!selecting) NotebookMenu(rename, move, delete, examDetails, assignSet)
        }
        ExamBadges(note, redoCount, Modifier.padding(top = 4.dp))
    }
}

@Composable private fun NotebookMenu(rename: () -> Unit, move: () -> Unit, delete: () -> Unit, examDetails: () -> Unit = {}, assignSet: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Notebook options") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Rename") }, { menu = false; rename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem({ Text("Exam details") }, { menu = false; examDetails() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null) })
            DropdownMenuItem({ Text("Exam set") }, { menu = false; assignSet() }, leadingIcon = { Icon(Icons.Rounded.Workspaces, null) })
            DropdownMenuItem({ Text("Move to folder") }, { menu = false; move() }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
            DropdownMenuItem({ Text("Delete") }, { menu = false; delete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
        }
    }
}

/**
 * A notebook's face on the shelf: its first page as it really is, drawn from the same preview cache
 * the page browser uses and resting on the notebook's own cover colour. Until that page has been
 * drawn there is nothing to show, so a fresh notebook keeps its decorative cover and its title.
 */
@Composable fun NotebookFace(note: Notebook, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier) {
    val cover = CoverColors[note.cover.mod(CoverColors.size)]
    val preview = rememberNotebookPreview(note, thumbnails, with(LocalDensity.current) { 260.dp.roundToPx() })
    Box(modifier) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp, 20.dp, 20.dp, 8.dp)).background(cover)) {
            if (preview == null) NotebookCover(note, Modifier.fillMaxSize(), compact = true)
            // The page keeps its whole height and sits inside the cover colour, so nothing is cropped.
            else Box(Modifier.fillMaxSize().padding(start = 6.dp, top = 4.dp, end = 4.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

/** A compact list row's small preview, falling back to a type icon before one has been drawn. */
@Composable private fun NotebookListThumbnail(note: Notebook, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier) {
    val preview = rememberNotebookPreview(note, thumbnails, with(LocalDensity.current) { 44.dp.roundToPx() })
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        if (preview != null) Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(if (note.pages.any { it.pdfIndex != null }) Icons.Rounded.PictureAsPdf else Icons.AutoMirrored.Rounded.MenuBook, null)
    }
}

/** The first page's preview, drawn once per revision and served from the cache after that. */
@Composable private fun rememberNotebookPreview(note: Notebook, thumbnails: PageThumbnailCache, widthPx: Int): Bitmap? {
    val first = note.pages.firstOrNull()
    var preview by remember(note.id, first?.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(note.id, first?.id, first?.revision, widthPx) {
        preview = first?.let { thumbnails.thumbnail(note.id, it, widthPx) }
    }
    return preview
}

@Composable fun NotebookCover(note: Notebook, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = CoverColors[note.cover.mod(CoverColors.size)]
    Box(modifier.clip(RoundedCornerShape(6.dp, 18.dp, 18.dp, 6.dp)).background(color)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = .06f), size = size.copy(width = size.width * .065f))
            drawLine(Color.White.copy(alpha = .25f), Offset(size.width * .073f, 0f), Offset(size.width * .073f, size.height), 2f)
            val path = Path().apply {
                moveTo(size.width * .1f, size.height * .82f)
                cubicTo(size.width * .3f, size.height * .48f, size.width * .68f, size.height * 1.05f, size.width * .93f, size.height * .66f)
                cubicTo(size.width * 1.1f, size.height * .38f, size.width * .36f, size.height * .54f, size.width * .52f, size.height * .83f)
            }
            drawPath(path, Color(0xFF343931).copy(alpha = .28f), style = Stroke(width = 2.5f))
            drawCircle(Color.White.copy(alpha = .25f), size.width * .21f, Offset(size.width * .83f, size.height * .88f))
        }
        Column(Modifier.fillMaxSize().padding(start = if (compact) 14.dp else 24.dp, top = if (compact) 14.dp else 24.dp, end = if (compact) 14.dp else 24.dp, bottom = if (compact) 12.dp else 20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (note.pages.any { it.pdfIndex != null }) "DOCUMENT" else "NOTEBOOK", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, letterSpacing = 2.sp, color = Color(0xFF343931).copy(alpha = .7f))
                Text(note.title, fontFamily = FontFamily.Serif, fontSize = if (compact) 18.sp else 23.sp, lineHeight = if (compact) 22.sp else 28.sp, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis, color = Color(0xFF343931))
            }
            if (!compact) Text("f.", fontFamily = FontFamily.Serif, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 23.sp, color = Color(0xFF343931).copy(alpha = .7f))
        }
    }
}
