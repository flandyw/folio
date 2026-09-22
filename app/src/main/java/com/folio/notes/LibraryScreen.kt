@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Modifier.semanticsLabel(label: String) = semantics { contentDescription = label }

/** Top-level destinations in the Library sidebar: notebooks, study review, or score progress. */
enum class LibrarySection { LIBRARY, REVIEW, PROGRESS }

/** Review hub tabs: Redo + Bookmarks live here instead of the Library header. */
enum class ReviewTab { REDO, BOOKMARKS }

@Composable fun LibraryScreen(state: FolioState, model: FolioViewModel, onNew: () -> Unit, onImport: () -> Unit, onImportArchive: () -> Unit, onFolder: () -> Unit, onSettings: () -> Unit, onMistakes: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val libraryPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    var examDetails by remember { mutableStateOf<Notebook?>(null) }
    var pendingMark by remember { mutableStateOf<Notebook?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // Debounced query drives the O(N) filter so typing never blocks the text field.
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query == debouncedQuery) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        debouncedQuery = query
    }
    var starred by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(AppPrefs.librarySort(libraryPrefs.getString(AppPrefs.LIB_SORT, null))) }
    var kind by rememberSaveable { mutableStateOf(AppPrefs.libraryKind(libraryPrefs.getString(AppPrefs.LIB_KIND, null))) }
    var unfiled by rememberSaveable { mutableStateOf(false) }
    var listView by rememberSaveable { mutableStateOf(libraryPrefs.getBoolean(AppPrefs.LIB_LIST, AppPrefs.DEFAULT_LIST_VIEW)) }
    // Persist library defaults so the shelf reopens the way it was left.
    LaunchedEffect(sort) { libraryPrefs.edit().putString(AppPrefs.LIB_SORT, sort.name).apply() }
    LaunchedEffect(kind) { libraryPrefs.edit().putString(AppPrefs.LIB_KIND, kind.name).apply() }
    LaunchedEffect(listView) { libraryPrefs.edit().putBoolean(AppPrefs.LIB_LIST, listView).apply() }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(LibrarySection.LIBRARY) }
    var reviewTab by rememberSaveable { mutableStateOf(ReviewTab.REDO) }
    var sortMenu by remember { mutableStateOf(false) }
    var sidebarImportMenu by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var bulkMove by remember { mutableStateOf(false) }
    var bulkDelete by remember { mutableStateOf(false) }
    var bulkTags by remember { mutableStateOf(false) }
    var bulkCover by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Notebook?>(null) }
    var move by remember { mutableStateOf<Notebook?>(null) }
    var delete by remember { mutableStateOf<Notebook?>(null) }
    var folderMenu by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var deleteFolder by remember { mutableStateOf<Folder?>(null) }
    val examFilter = state.examFilter
    // Filtering + sorting runs once per input change, not on every recomposition (selection
    // ticks, thumbnail arrivals), so scrolling and multi-select stay smooth on large libraries.
    val notes = remember(state.notes, state.folderId, starred, unfiled, debouncedQuery, kind, sort, examFilter) {
        organizeNotebooks(state.notes, state.folderId, starred, unfiled, debouncedQuery, kind, sort)
            .filter { examFilter.matches(it) && (!examFilter.needsRedo || it.pages.any { page -> page.redoFlag }) }
    }
    val redoCount = remember(state.notes) { state.notes.sumOf { note -> note.pages.count { it.redoFlag } } }
    val bookmarkCount = remember(state.notes) { state.notes.sumOf { note -> note.pages.count { it.bookmarked } } }
    val reviewCount = redoCount + bookmarkCount
    val visibleIds = remember(notes) { notes.map { it.id }.toSet() }
    val selection = remember(selectedIds, visibleIds) { selectedIds.filter { it in visibleIds }.toSet() }
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.filter { it in visibleIds } }
    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    /** Holding a notebook drops straight into selection with that notebook ticked. */
    fun enterSelecting(id: String) {
        selecting = true
        if (id !in selectedIds) selectedIds = selectedIds + id
    }
    val filtersActive = kind != LibraryKind.ALL || unfiled || examFilter.isActive
    val folderName = state.folders.find { it.id == state.folderId }?.name
    BoxWithConstraints(Modifier.fillMaxSize().guardUiTouches()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(200.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Brand(); Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onNew, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("New notebook") }
                    Spacer(Modifier.height(12.dp))
                    NavItem("Library", Icons.Rounded.GridView, section == LibrarySection.LIBRARY && !starred && state.folderId == null, state.notes.size) { section = LibrarySection.LIBRARY; starred = false; unfiled = false; model.folder(null) }
                    NavItem("Mistakes", Icons.Rounded.School, false, null, onMistakes)
                    NavItem("Review", Icons.AutoMirrored.Rounded.FactCheck, section == LibrarySection.REVIEW, reviewCount.takeIf { it > 0 }) { section = LibrarySection.REVIEW }
                    if (section == LibrarySection.REVIEW) {
                        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            NavItem("Redo", Icons.Rounded.Refresh, reviewTab == ReviewTab.REDO, redoCount.takeIf { it > 0 }) { reviewTab = ReviewTab.REDO }
                            NavItem("Bookmarks", Icons.Rounded.Bookmark, reviewTab == ReviewTab.BOOKMARKS, bookmarkCount.takeIf { it > 0 }) { reviewTab = ReviewTab.BOOKMARKS }
                        }
                    }
                    NavItem("Progress", Icons.Rounded.Insights, section == LibrarySection.PROGRESS, null) { section = LibrarySection.PROGRESS }
                    NavItem("Favorites", Icons.Rounded.StarOutline, section == LibrarySection.LIBRARY && starred, state.notes.count { it.starred }) { section = LibrarySection.LIBRARY; starred = true; unfiled = false; model.folder(null) }
                    Box {
                        NavItem("Import", Icons.Rounded.FileOpen, false, null) { sidebarImportMenu = true }
                        DropdownMenu(sidebarImportMenu, { sidebarImportMenu = false }, modifier = Modifier.guardUiTouches()) {
                            DropdownMenuItem({ Text("PDF document") }, { sidebarImportMenu = false; onImport() }, leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) })
                            DropdownMenuItem({ Text("Folio backup") }, { sidebarImportMenu = false; onImportArchive() }, leadingIcon = { Icon(Icons.Rounded.FolderZip, null) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("YOUR FOLDERS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onFolder, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.CreateNewFolder, "New folder", Modifier.size(20.dp)) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.folders.forEach { folder -> NavItem(folder.name, Icons.Rounded.FolderOpen, section == LibrarySection.LIBRARY && state.folderId == folder.id, state.notes.count { it.folderId == folder.id }) { section = LibrarySection.LIBRARY; starred = false; unfiled = false; model.folder(folder.id) } }
                        if (state.folders.isEmpty()) Text("A place for every project.\nCreate your first folder.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(); NavItem("Settings", Icons.Rounded.Tune, false, null, onSettings)
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.OfflinePin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                        Text("On your device. Always yours.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (section == LibrarySection.LIBRARY) LazyVerticalGrid(columns = if (listView) GridCells.Fixed(1) else GridCells.Adaptive(144.dp), modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(if (wide) 20.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(if (listView) 8.dp else 16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!wide) Brand() else Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            if (!wide) Spacer(Modifier.weight(1f))
                            if (!wide) FilledTonalIconButton(onNew, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "New notebook") }
                            if (!wide) Box {
                                var importMenu by remember { mutableStateOf(false) }
                                IconButton({ importMenu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.FileOpen, "Import PDF or Folio backup") }
                                DropdownMenu(importMenu, { importMenu = false }, modifier = Modifier.guardUiTouches()) {
                                    DropdownMenuItem({ Text("PDF document") }, { importMenu = false; onImport() }, leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) })
                                    DropdownMenuItem({ Text("Folio backup") }, { importMenu = false; onImportArchive() }, leadingIcon = { Icon(Icons.Rounded.FolderZip, null) })
                                }
                            }
                            if (!wide) IconButton(onSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Settings") }
                        }
                        // Narrow devices have no sidebar: section tabs live here instead.
                        if (!wide) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(section == LibrarySection.LIBRARY, { section = LibrarySection.LIBRARY }, { Text("Library") }, leadingIcon = { Icon(Icons.Rounded.GridView, null, Modifier.size(16.dp)) })
                            FilterChip(false, onMistakes, { Text("Mistakes") }, leadingIcon = { Icon(Icons.Rounded.School, null, Modifier.size(16.dp)) })
                            FilterChip(section == LibrarySection.REVIEW, { section = LibrarySection.REVIEW }, { Text(if (reviewCount > 0) "Review · $reviewCount" else "Review") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null, Modifier.size(16.dp)) })
                            FilterChip(section == LibrarySection.PROGRESS, { section = LibrarySection.PROGRESS }, { Text("Progress") }, leadingIcon = { Icon(Icons.Rounded.Insights, null, Modifier.size(16.dp)) })
                        }
                        if (!wide) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(state.folderId == null && !starred && !unfiled, { model.folder(null); starred = false; unfiled = false }, { Text("All notebooks") }, leadingIcon = { Icon(Icons.Rounded.GridView, null, Modifier.size(16.dp)) })
                            FilterChip(starred, { model.folder(null); starred = !starred; unfiled = false }, { Text("Favorites") }, leadingIcon = { Icon(Icons.Rounded.StarOutline, null, Modifier.size(16.dp)) })
                            state.folders.forEach { folder -> FilterChip(state.folderId == folder.id, { starred = false; unfiled = false; model.folder(folder.id) }, { Text(folder.name) }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp)) }) }
                            AssistChip(onFolder, { Text("New folder") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                        }
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find notebooks, page names or exam tags…") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } }, singleLine = true, shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (query.isNotEmpty()) "Search results" else folderName ?: if (unfiled) "Unfiled" else if (starred) "Favorites" else "Your notebooks", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text("${notes.size}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall) }
                            Box {
                                IconButton({ sortMenu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort: ${sort.label}") }
                                DropdownMenu(sortMenu, { sortMenu = false }, modifier = Modifier.guardUiTouches()) {
                                    LibrarySort.entries.forEach { option ->
                                        DropdownMenuItem({ Text(option.label) }, { sort = option; sortMenu = false }, trailingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) })
                                    }
                                }
                            }
                            IconButton({ listView = !listView }, shapes = IconButtonDefaults.shapes()) { Icon(if (listView) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList, if (listView) "Show covers" else "Show compact list") }
                            state.folders.find { it.id == state.folderId }?.let { folder -> Box {
                                IconButton({ folderMenu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Folder options") }
                                DropdownMenu(folderMenu, { folderMenu = false }, modifier = Modifier.guardUiTouches()) {
                                    DropdownMenuItem({ Text("Rename folder") }, { folderMenu = false; renameFolder = folder })
                                    DropdownMenuItem({ Text("Remove folder") }, { folderMenu = false; deleteFolder = folder })
                                }
                            } }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(filtersExpanded || filtersActive, { filtersExpanded = !filtersExpanded }, { Text(if (filtersActive) "Filters • Active" else "Filters") },
                                leadingIcon = { Icon(Icons.Rounded.FilterList, null, Modifier.size(18.dp)) },
                                trailingIcon = { Icon(if (filtersExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) })
                            TextButton({ selecting = !selecting; selectedIds = emptyList() }, shapes = ButtonDefaults.shapes()) { Text(if (selecting) "Done" else "Select") }
                        }
                        if (filtersActive) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Filtered results · ${notes.size} notebooks", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ kind = LibraryKind.ALL; unfiled = false; model.setExamFilter(ExamFilter()) }, shapes = ButtonDefaults.shapes()) { Text("Reset filters") }
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
                            val examNotes = state.notes.filter { it.exam.isTagged }
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
                        if (selecting) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${selection.size} selected", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.weight(1f))
                                TextButton({ selectedIds = if (selection.size == notes.size) emptyList() else notes.map { it.id } }, shapes = ButtonDefaults.shapes()) { Text(if (selection.size == notes.size && notes.isNotEmpty()) "Deselect all" else "Select all") }
                                TextButton({ selecting = false; selectedIds = emptyList() }, shapes = ButtonDefaults.shapes()) { Text("Done") }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton({ bulkMove = true }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text("Move") }
                                TextButton({ bulkTags = true }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text("Exam details") }
                                TextButton({ bulkCover = true }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text("Cover") }
                                val allStarred = selection.isNotEmpty() && notes.filter { it.id in selection }.all { it.starred }
                                TextButton({ model.favoriteNotebooks(selection, !allStarred) }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text(if (allStarred) "Unfavorite" else "Favorite") }
                                TextButton(
                                    { bulkDelete = true },
                                    enabled = selection.isNotEmpty(),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    shapes = ButtonDefaults.shapes()) { Text("Delete") }
                            }
                        }
                        if (state.saveFailed) FilledTonalButton(model::retrySave, shapes = ButtonDefaults.shapes()) { Text("Changes need saving · Retry save") }
                    }
                }
                if (notes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(124.dp, 140.dp)) { NotebookCover(Notebook(title = "Your next idea", cover = 1), Modifier.fillMaxSize()) }
                            Text(if (query.isNotEmpty() || filtersActive) "No notebooks found" else if (starred) "Keep the good ones close" else "Good things start here.", style = MaterialTheme.typography.headlineMedium)
                            Text(if (query.isNotEmpty() || filtersActive) "Try another search or clear your filters." else if (starred) "Tap the star on a notebook to find it here." else "Make space for your first idea. Create a notebook\nor bring a PDF along.", style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (query.isNotEmpty() || filtersActive) TextButton({ query = ""; kind = LibraryKind.ALL; unfiled = false; model.setExamFilter(ExamFilter()) }, shapes = ButtonDefaults.shapes()) { Text("Clear filters") }
                            if (query.isEmpty() && !starred && !filtersActive) TextButton(onNew, shapes = ButtonDefaults.shapes()) { Text("Start a notebook"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
                        }
                    }
                }
                items(notes, key = { it.id }) { note ->
                    val open = { if (selecting) toggleSelection(note.id) else model.open(note.id) }
                    val longPress = { enterSelecting(note.id) }
                    val folder = state.folders.find { it.id == note.folderId }?.name
                    if (listView) Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (note.id in selection) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.combinedClickable(onClick = open, onLongClick = longPress)
                    ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (selecting) {
                                    Checkbox(note.id in selection, { toggleSelection(note.id) }, Modifier.semanticsLabel(if (note.id in selection) "Deselect ${note.title}" else "Select ${note.title}"))
                                }
                                NotebookListThumbnail(note, model.thumbnails, Modifier.width(38.dp).height(50.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text("${folder ?: "Unfiled"} · ${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"} · ${libraryLastEditedLabel(note.updated)}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (!selecting) {
                                    IconButton({ model.star(note) }, shapes = IconButtonDefaults.shapes()) { Icon(if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (note.starred) "Remove from favorites" else "Add to favorites") }
                                    NotebookMenu({ rename = note }, { move = note }, { delete = note }, { examDetails = note }, { pendingMark = note }, note.pageCover, { model.setPageCover(note, !note.pageCover) }, { model.duplicateNotebook(note) })
                                }
                            }
                        } else NotebookCard(
                            note, model.thumbnails, folder, open, { model.star(note) },
                            { rename = note }, { move = note }, { delete = note }, { examDetails = note }, { pendingMark = note },
                            selecting, note.pages.count { it.redoFlag },
                            selected = note.id in selection, onLongPress = longPress,
                            pageCover = note.pageCover, onCoverToggle = { model.setPageCover(note, !note.pageCover) },
                            duplicate = { model.duplicateNotebook(note) }
                        )
                }

            }
            if (section == LibrarySection.REVIEW) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(if (wide) 20.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!wide) Brand() else Text("Review", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    if (!wide) Spacer(Modifier.weight(1f))
                    if (!wide) FilledTonalIconButton(onNew, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "New notebook") }
                    if (!wide) IconButton(onSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Settings") }
                }
                if (!wide) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(false, { section = LibrarySection.LIBRARY }, { Text("Library") }, leadingIcon = { Icon(Icons.Rounded.GridView, null, Modifier.size(16.dp)) })
                    FilterChip(false, onMistakes, { Text("Mistakes") }, leadingIcon = { Icon(Icons.Rounded.School, null, Modifier.size(16.dp)) })
                    FilterChip(true, {}, { Text(if (reviewCount > 0) "Review · $reviewCount" else "Review") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null, Modifier.size(16.dp)) })
                    FilterChip(false, { section = LibrarySection.PROGRESS }, { Text("Progress") }, leadingIcon = { Icon(Icons.Rounded.Insights, null, Modifier.size(16.dp)) })
                }
                Text("Redo queue and bookmarks — kept here so the shelf stays a shelf.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(reviewTab == ReviewTab.REDO, { reviewTab = ReviewTab.REDO }, { Text(if (redoCount > 0) "Redo · $redoCount" else "Redo") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)) })
                    FilterChip(reviewTab == ReviewTab.BOOKMARKS, { reviewTab = ReviewTab.BOOKMARKS }, { Text(if (bookmarkCount > 0) "Bookmarks · $bookmarkCount" else "Bookmarks") }, leadingIcon = { Icon(Icons.Rounded.Bookmark, null, Modifier.size(16.dp)) })
                }
                when (reviewTab) {
                    ReviewTab.REDO -> {
                        Text("Redo queue", style = MaterialTheme.typography.titleMedium)
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            RedoReviewContent(state.notes, { id, index -> model.openAt(id, index) }, Modifier.fillMaxWidth(), scrollEnabled = false)
                        }
                    }
                    ReviewTab.BOOKMARKS -> {
                        Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            BookmarkListContent(state.notes, { id, index -> model.openAt(id, index) }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (section == LibrarySection.PROGRESS) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(if (wide) 20.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!wide) Brand() else Text("Progress", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    if (!wide) Spacer(Modifier.weight(1f))
                    if (!wide) FilledTonalIconButton(onNew, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "New notebook") }
                    if (!wide) IconButton(onSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Settings") }
                }
                if (!wide) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(false, { section = LibrarySection.LIBRARY }, { Text("Library") }, leadingIcon = { Icon(Icons.Rounded.GridView, null, Modifier.size(16.dp)) })
                    FilterChip(false, onMistakes, { Text("Mistakes") }, leadingIcon = { Icon(Icons.Rounded.School, null, Modifier.size(16.dp)) })
                    FilterChip(false, { section = LibrarySection.REVIEW }, { Text(if (reviewCount > 0) "Review · $reviewCount" else "Review") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null, Modifier.size(16.dp)) })
                    FilterChip(true, {}, { Text("Progress") }, leadingIcon = { Icon(Icons.Rounded.Insights, null, Modifier.size(16.dp)) })
                }
                Text("Averages come from every recorded attempt; recent attempts average each paper's latest mark.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ExamProgressContent(state.notes, Modifier.fillMaxWidth(), scrollEnabled = false)
                }
            }
        }
    }
    examDetails?.let { note ->
        // The dialog edits a snapshot: refresh the notebook from state so a mark recorded
        // elsewhere (e.g. the quick Record action) shows up while the panel is open.
        val live = state.notes.find { it.id == note.id } ?: note
        ExamDetailsPanel(
            note = live,
            onDismiss = { examDetails = null },
            onSave = { tags -> model.updateExamTags(note.id, tags); examDetails = null },
            onRecordMark = { attempt -> model.recordAttempt(note.id, attempt) },
            onDeleteAttempt = { attempt -> model.deleteAttempt(note.id, attempt.id) },
            suggestedSeconds = state.lastTimedSeconds
        )
    }
    pendingMark?.let { note ->
        val live = state.notes.find { it.id == note.id } ?: note
        ScoreDialog(
            total = live.exam.marksTotal,
            defaultSeconds = state.lastTimedSeconds,
            onDismiss = { pendingMark = null },
            onRecord = { score, total, seconds, timed ->
                model.recordAttempt(live.id, ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed))
                pendingMark = null
            }
        )
    }
    if (bulkMove) AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { bulkMove = false }, title = { Text("Move ${selection.size} notebooks") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton({ model.moveNotebooks(selection, null); bulkMove = false; selectedIds = emptyList() }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text("Unfiled") }
            state.folders.forEach { folder -> TextButton({ model.moveNotebooks(selection, folder.id); bulkMove = false; selectedIds = emptyList() }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text(folder.name) } }
            if (state.folders.isEmpty()) Text("Create a folder using New folder, then move notebooks here.")
        }
    }, confirmButton = { TextButton({ bulkMove = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } })
    if (bulkDelete) AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = { bulkDelete = false },
        title = { Text("Delete ${selection.size} notebook${if (selection.size == 1) "" else "s"}?") },
        text = { Text("This removes the selected notebooks and their pages from this device. Export a copy first if you want to keep them.") },
        dismissButton = { TextButton({ bulkDelete = false }, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
        confirmButton = {
            TextButton(
                { model.deleteNotebooks(selection); bulkDelete = false; selectedIds = emptyList() },
                enabled = selection.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes()) { Text("Delete") }
        }
    )
    if (bulkTags) BatchExamTagsPanel(
        count = selection.size,
        onDismiss = { bulkTags = false },
        onApply = { transform ->
            model.updateExamTagsBatch(selection, transform)
            bulkTags = false
            selectedIds = emptyList()
        }
    )
    if (bulkCover) AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = { bulkCover = false },
        title = { Text("Cover for ${selection.size} notebook${if (selection.size == 1) "" else "s"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Show the first page itself, or keep the decorative default cover.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(
                    { model.setPageCoverBatch(selection, true); bulkCover = false; selectedIds = emptyList() },
                    enabled = selection.isNotEmpty(),
                    shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(12.dp)); Text("First page as cover") }
                TextButton(
                    { model.setPageCoverBatch(selection, false); bulkCover = false; selectedIds = emptyList() },
                    enabled = selection.isNotEmpty(),
                    shapes = ButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.MenuBook, null); Spacer(Modifier.width(12.dp)); Text("Default cover") }
            }
        },
        confirmButton = { TextButton({ bulkCover = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
    rename?.let { note -> NameDialog("Rename notebook", "A name that feels right.", note.title, "Save", { rename = null }) { model.rename(note, it); rename = null } }
    renameFolder?.let { folder -> NameDialog("Rename folder", "Keep your workspace organized.", folder.name, "Save", { renameFolder = null }) { model.renameFolder(folder, it); renameFolder = null } }
    deleteFolder?.let { folder -> AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { deleteFolder = null }, title = { Text("Remove “${folder.name}”?") }, text = { Text("Your notebooks will stay in All notebooks. Only this folder is removed.") }, dismissButton = { TextButton({ deleteFolder = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }, confirmButton = { TextButton({ model.deleteFolder(folder); deleteFolder = null }, shapes = ButtonDefaults.shapes()) { Text("Remove folder") } }) }
    move?.let { note -> AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { move = null }, title = { Text("Move notebook") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton({ model.move(note, null); move = null }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.GridView, null); Spacer(Modifier.width(12.dp)); Text("No folder") }
            state.folders.forEach { folder -> TextButton({ model.move(note, folder.id); move = null }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(12.dp)); Text(folder.name) } }
        }
    }, confirmButton = { TextButton({ move = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }) }
    delete?.let { note -> AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { delete = null }, title = { Text("Delete “${note.title}”?") }, text = { Text("This removes the notebook and its pages from this device. Export a copy first if you want to keep it.") }, dismissButton = { TextButton({ delete = null }, shapes = ButtonDefaults.shapes()) { Text("Keep notebook") } }, confirmButton = { TextButton({ model.delete(note); delete = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes()) { Text("Delete") } }) }
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

@Composable private fun NotebookCard(note: Notebook, thumbnails: PageThumbnailCache, folder: String?, open: () -> Unit, star: () -> Unit, rename: () -> Unit, move: () -> Unit, delete: () -> Unit, examDetails: () -> Unit = {}, recordMark: () -> Unit = {}, selecting: Boolean = false, redoCount: Int = 0, selected: Boolean = false, onLongPress: () -> Unit = {}, pageCover: Boolean = true, onCoverToggle: () -> Unit = {}, duplicate: () -> Unit = {}) {
    Column {
        Box {
            NotebookFace(note, thumbnails, Modifier.fillMaxWidth().combinedClickable(onClickLabel = "Open ${note.title}", onClick = open, onLongClick = onLongPress))
            if (selecting) {
                val checked = selected
                Surface(
                    onClick = open,
                    shape = RoundedCornerShape(10.dp),
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .92f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(30.dp).semanticsLabel(if (checked) "Deselect ${note.title}" else "Select ${note.title}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            } else {
                IconButton(star, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp), shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        if (note.starred) "Remove from favorites" else "Add to favorites",
                        tint = if (note.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .85f),
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).combinedClickable(onClick = open, onLongClick = onLongPress)) {
                                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"} · ${folder ?: "Unfiled"} · ${libraryLastEditedLabel(note.updated)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
            if (!selecting) NotebookMenu(rename, move, delete, examDetails, recordMark, pageCover, onCoverToggle, duplicate)
        }
        ExamBadges(note, redoCount, Modifier.padding(top = 4.dp))
    }
}

@Composable private fun NotebookMenu(rename: () -> Unit, move: () -> Unit, delete: () -> Unit, examDetails: () -> Unit = {}, recordMark: () -> Unit = {}, pageCover: Boolean = true, onCoverToggle: () -> Unit = {}, duplicate: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Notebook options") }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
            DropdownMenuItem({ Text("Rename") }, { menu = false; rename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem({ Text("Duplicate") }, { menu = false; duplicate() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
            DropdownMenuItem({ Text("Exam details") }, { menu = false; examDetails() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null) })
            DropdownMenuItem({ Text("Record a mark") }, { menu = false; recordMark() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Grading, null) })
            DropdownMenuItem(
                { Text(if (pageCover) "Use default cover" else "Use first page as cover") },
                { menu = false; onCoverToggle() },
                leadingIcon = { Icon(if (pageCover) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.Image, null) }
            )
            DropdownMenuItem({ Text("Move to folder") }, { menu = false; move() }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
            DropdownMenuItem({ Text("Delete") }, { menu = false; delete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
        }
    }
}

/**
 * A notebook's face on the shelf: either the first page itself with no book decoration, or the
 * decorative default cover. The first-page cover is drawn from the same preview cache the page
 * browser uses; until that page has been drawn there is nothing to show, so a fresh notebook
 * keeps its decorative cover and its title either way.
 */
@Composable fun NotebookFace(note: Notebook, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier) {
    val preview = rememberNotebookPreview(note, thumbnails, with(LocalDensity.current) { 260.dp.roundToPx() })
    val first = note.pages.firstOrNull()
    // Reserve the page's own aspect up front so the card never jumps when the preview
    // arrives a frame later; the decorative cover simply fills the same box until then.
    val aspect = first?.let { page ->
        val ratio = if (page.height > 0) page.width / page.height else 0.7f
        ratio.coerceIn(0.4f, 1.5f)
    } ?: 0.71f
    val bitmap = preview
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
    if (note.pageCover && imageBitmap != null) {
        Box(
            modifier
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(imageBitmap, "First page preview of ${note.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    } else {
        NotebookCover(note, modifier.aspectRatio(aspect), compact = true)
    }
}

/** A compact list row's small preview, falling back to a type icon before one has been drawn. */
@Composable private fun NotebookListThumbnail(note: Notebook, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier) {
    val preview = rememberNotebookPreview(note, thumbnails, with(LocalDensity.current) { 44.dp.roundToPx() })
    val imageBitmap = remember(preview) { preview?.asImageBitmap() }
    val isPdf = note.pages.any { it.pdfIndex != null }
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        if (imageBitmap != null) Image(imageBitmap, "First page preview of ${note.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(if (isPdf) Icons.Rounded.PictureAsPdf else Icons.AutoMirrored.Rounded.MenuBook, if (isPdf) "PDF notebook ${note.title}" else "Notebook ${note.title}")
    }
}

/** The first page's preview, drawn once per revision and served from the cache after that. */
@Composable private fun rememberNotebookPreview(note: Notebook, thumbnails: PageThumbnailCache, widthPx: Int): Bitmap? {
    val first = note.pages.firstOrNull()
    var preview by remember(note.id, first?.id, first?.revision, widthPx) { mutableStateOf<Bitmap?>(null) }
    // Keep the previous preview until the new revision arrives so covers never flash blank.
    LaunchedEffect(note.id, first?.id, first?.revision, widthPx) {
        first?.let { preview = thumbnails.thumbnail(note.id, it, widthPx) }
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
