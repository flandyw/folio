@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private enum class ExplorerSource { NOTEBOOKS, DEVICE }
private enum class ExplorerCollection(val label: String) { FOLDERS("Folders"), ALL("All notebooks"), FAVORITES("Favorites") }

/** A peer of the shelf: same notebooks and create/import actions, with a spatial folder view. */
@Composable internal fun ExplorerScreen(
    state: FolioState, model: FolioViewModel, onShelf: () -> Unit, onNew: () -> Unit,
    onImport: () -> Unit, onImportArchive: () -> Unit, onOpen: (String) -> Unit,
    onExamDetails: (Notebook) -> Unit, onRecordMark: (Notebook) -> Unit, onChangeCover: (Notebook) -> Unit,
    modifier: Modifier = Modifier
) {
    var source by rememberSaveable { mutableStateOf(ExplorerSource.NOTEBOOKS) }
    Column(modifier.fillMaxSize().guardUiTouches()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onShelf, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("Explorer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text("A place for everything", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FolioButtonGroup(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            toggleableItem(checked = source == ExplorerSource.NOTEBOOKS, onCheckedChange = { source = ExplorerSource.NOTEBOOKS }, label = "Notebooks",
                icon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) })
            toggleableItem(checked = source == ExplorerSource.DEVICE, onCheckedChange = { source = ExplorerSource.DEVICE }, label = "Tablet files",
                icon = { Icon(Icons.Rounded.FolderOpen, null) })
        }
        Spacer(Modifier.height(8.dp))
        // Save each source’s navigation, scroll and filters when switching tabs.
        val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        Box(Modifier.weight(1f)) {
            when (source) {
                ExplorerSource.NOTEBOOKS -> holder.SaveableStateProvider("notebooks") {
                    NotebookExplorer(state, model, onShelf, onNew, onOpen, onExamDetails, onRecordMark, onChangeCover)
                }
                ExplorerSource.DEVICE -> holder.SaveableStateProvider("device") {
                    DeviceExplorer(state, model, onImport, onImportArchive, onShelf)
                }
            }
        }
    }
}

@Composable private fun NotebookExplorer(
    state: FolioState, model: FolioViewModel, onShelf: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit,
    onExamDetails: (Notebook) -> Unit, onRecordMark: (Notebook) -> Unit, onChangeCover: (Notebook) -> Unit
) {
    val prefs = LocalContext.current.getSharedPreferences("preferences", 0)
    var collection by rememberSaveable { mutableStateOf(ExplorerCollection.FOLDERS) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(AppPrefs.librarySort(prefs.getString(AppPrefs.LIB_SORT, null))) }
    var list by rememberSaveable { mutableStateOf(prefs.getBoolean(AppPrefs.LIB_LIST, AppPrefs.DEFAULT_LIST_VIEW)) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var moveFolder by remember { mutableStateOf<Folder?>(null) }
    var removeFolder by remember { mutableStateOf<Folder?>(null) }
    var renameNote by remember { mutableStateOf<Notebook?>(null) }
    var moveIds by remember { mutableStateOf<Set<String>?>(null) }
    var tagIds by remember { mutableStateOf<Set<String>?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    val path = remember(state.folders, state.folderId) { LibraryFolders.path(state.folders, state.folderId) }
    val allTags = remember(state.notes) { NotebookTags.normalize(state.notes.flatMap { it.tags }, Int.MAX_VALUE) }
    val searching = query.isNotBlank()
    val folderScope = collection == ExplorerCollection.FOLDERS && tag == null
    val folderIds = remember(state.folders, state.folderId) {
        state.folderId?.let { LibraryFolders.descendants(state.folders, it) }
    }
    val notes = remember(state.notes, folderScope, folderIds, state.folderId, query, tag, collection, sort) {
        organizeNotebooks(state.notes, query = query, sort = sort, starred = collection == ExplorerCollection.FAVORITES)
            .filter { note -> (!folderScope || if (searching) folderIds == null || note.folderId in folderIds else note.folderId == state.folderId) &&
                (tag == null || note.tags.any { it.equals(tag, true) }) }
    }
    val folders = remember(state.folders, state.folderId, query, folderScope) {
        if (!folderScope) emptyList() else state.folders.filter {
            if (searching) (folderIds == null || (it.id in folderIds && it.id != state.folderId)) && it.name.contains(query.trim(), true)
            else it.parentId == state.folderId
        }.sortedBy { it.name.lowercase() }
    }
    val counts = remember(state.notes) { state.notes.groupingBy { it.folderId }.eachCount() }
    val selection = selected.filter { id -> notes.any { it.id == id } }.toSet()
    LaunchedEffect(notes) { selected = selected.filter { id -> notes.any { it.id == id } } }
    LaunchedEffect(sort) { prefs.edit().putString(AppPrefs.LIB_SORT, sort.name).apply() }
    LaunchedEffect(list) { prefs.edit().putBoolean(AppPrefs.LIB_LIST, list).apply() }
    fun browse(id: String?) { model.folder(id); collection = ExplorerCollection.FOLDERS; tag = null; query = ""; selecting = false; selected = emptyList() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun back() {
        when {
            selecting -> { selecting = false; selected = emptyList() }
            searching -> query = ""
            tag != null -> tag = null
            collection != ExplorerCollection.FOLDERS -> collection = ExplorerCollection.FOLDERS
            state.folderId != null -> browse(path.lastOrNull()?.parentId)
            else -> onShelf()
        }
    }
    BackHandler { back() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(224.dp).fillMaxHeight().padding(start = 16.dp, bottom = 12.dp),
                shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { Text("Your library", Modifier.padding(8.dp), style = MaterialTheme.typography.titleSmall) }
                    items(ExplorerCollection.entries) { item -> ExplorerPlace(item.label,
                        when (item) { ExplorerCollection.FOLDERS -> Icons.Rounded.FolderOpen; ExplorerCollection.ALL -> Icons.Rounded.GridView; ExplorerCollection.FAVORITES -> Icons.Rounded.StarOutline },
                        collection == item && tag == null) { collection = item; tag = null; query = ""; model.folder(null) } }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("Tags", Modifier.padding(8.dp), style = MaterialTheme.typography.titleSmall) }
                    if (allTags.isEmpty()) item { Text("Add labels from a notebook’s menu, or tag a selection together.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(allTags, key = { it }) { label -> ExplorerPlace(label, Icons.Rounded.Sell, tag == label) { tag = label; query = ""; model.folder(null) } }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!wide) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExplorerCollection.entries.forEach { item -> FilterChip(collection == item && tag == null,
                            { collection = item; tag = null; query = ""; model.folder(null) }, { Text(item.label) }) }
                        allTags.forEach { label -> FilterChip(tag == label, { tag = if (tag == label) null else label; query = ""; model.folder(null) }, { Text(label) }, leadingIcon = { Icon(Icons.Rounded.Sell, null, Modifier.size(18.dp)) }) }
                    }
                    if (folderScope) ExplorerBreadcrumbs(listOf("Library" to { browse(null) }) + path.map { f -> f.name to { browse(f.id) } })
                    else Text(tag?.let { "Tagged · $it" } ?: collection.label, style = MaterialTheme.typography.titleMedium)
                    ExplorerSearch(query, { query = it }, if (folderScope && state.folderId != null) "Search this folder and subfolders" else "Search notebooks and tags")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!selecting) {
                            SplitButtonLayout(leadingButton = { SplitButtonDefaults.LeadingButton({ if (!folderScope) model.folder(null); onNew() }, enabled = !state.loading && !state.loadFailed) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Notebook") } },
                                trailingButton = { SplitButtonDefaults.TrailingButton({ newFolder = true }, enabled = !state.loading && !state.loadFailed) { Icon(Icons.Rounded.CreateNewFolder, "New folder") } })
                        }
                        TextButton({ selecting = !selecting; selected = emptyList() }, enabled = selecting || notes.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text(if (selecting) "Done" else "Select") }
                        ExplorerSort(sort, { sort = it })
                        IconButton({ list = !list }, shapes = IconButtonDefaults.shapes()) { Icon(if (list) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList, if (list) "Show covers" else "Show list") }
                    }
                }
                val grid = rememberLazyGridState()
                LaunchedEffect(state.folderId, collection, tag, query, sort) { grid.scrollToItem(0) }
                LazyVerticalGrid(if (list) GridCells.Fixed(1) else GridCells.Adaptive(164.dp), state = grid,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                    items(folders, key = { "folder:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                        var menu by remember { mutableStateOf(false) }
                        ExplorerRow(folder.name,
                            if (searching) LibraryFolders.label(state.folders, folder.parentId).ifEmpty { "Library" }
                            else "${state.folders.count { it.parentId == folder.id }} folders · ${counts[folder.id] ?: 0} notebooks",
                            Icons.Rounded.Folder, onClick = { browse(folder.id) }, trailing = {
                                Box {
                                    IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for ${folder.name}") }
                                    DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
                                        DropdownMenuItem({ Text("Rename") }, { menu = false; renameFolder = folder })
                                        DropdownMenuItem({ Text("Move folder") }, { menu = false; moveFolder = folder })
                                        DropdownMenuItem({ Text("Remove folder") }, { menu = false; removeFolder = folder })
                                    }
                                }
                            })
                    }
                    if (!state.loading && notes.isEmpty() && folders.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        ExplorerEmpty(Icons.Rounded.FolderOpen,
                            if (state.loadFailed) "Library unavailable" else if (searching) "No matches here" else if (tag != null) "No notebooks with this tag" else "Room for your next idea",
                            if (state.loadFailed) "Return to the library to retry loading." else if (searching) "Try a notebook name or tag. Search includes subfolders." else "Create a notebook or folder here, or move notebooks in from the library.")
                    }
                    items(notes, key = { it.id }) { note ->
                        val open = { if (selecting) toggle(note.id) else onOpen(note.id) }
                        val hold = { selecting = true; toggle(note.id) }
                        if (list) {
                            var menu by remember { mutableStateOf(false) }
                            Surface(shape = FolioShapes.large, color = if (note.id in selection) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.semantics { if (selecting) this.selected = note.id in selection }.combinedClickable(onClick = open, onLongClick = hold, onClickLabel = "Open ${note.title}")) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (selecting) Checkbox(note.id in selection, { toggle(note.id) }, Modifier.semanticsLabel("Select ${note.title}"))
                                    NotebookListThumbnail(note, model.thumbnails, Modifier.size(38.dp, 50.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${note.pages.size} pages · ${libraryLastEditedLabel(note.updated)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!folderScope) Text(LibraryFolders.label(state.folders, note.folderId).ifEmpty { "Unfiled" }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (note.starred) Icon(Icons.Rounded.Star, "Favorite", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    if (!selecting) Box {
                                        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for ${note.title}") }
                                        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
                                            DropdownMenuItem({ Text("Tags") }, { menu = false; tagIds = setOf(note.id) }, leadingIcon = { Icon(Icons.Rounded.Sell, null) })
                                            DropdownMenuItem({ Text("Move") }, { menu = false; moveIds = setOf(note.id) })
                                            DropdownMenuItem({ Text("Rename") }, { menu = false; renameNote = note })
                                            DropdownMenuItem({ Text(if (note.starred) "Unfavorite" else "Favorite") }, { menu = false; model.star(note) })
                                            DropdownMenuItem({ Text("Duplicate") }, { menu = false; model.duplicateNotebook(note) })
                                            DropdownMenuItem({ Text("Delete") }, { menu = false; deleteIds = setOf(note.id) })
                                        }
                                    }
                                }
                            }
                        } else Column {
                            NotebookCard(note, model.thumbnails, LibraryFolders.label(state.folders, note.folderId).ifEmpty { null }, open,
                                { model.star(note) }, { renameNote = note }, { moveIds = setOf(note.id) }, { deleteIds = setOf(note.id) },
                                examDetails = { onExamDetails(note) }, recordMark = { onRecordMark(note) }, onChangeCover = { onChangeCover(note) },
                                selecting = selecting, selected = note.id in selection, onLongPress = hold,
                                pageCover = note.pageCover, onCoverToggle = { model.setPageCover(note, !note.pageCover) },
                                duplicate = { model.duplicateNotebook(note) }, backupExcluded = note.id in state.backupExcludedNotebookIds,
                                onBackupToggle = { model.setBackupExcluded(setOf(note.id), note.id !in state.backupExcludedNotebookIds) },
                                onTags = { tagIds = setOf(note.id) })
                        }
                    }
                }
                if (selecting) ExplorerSelectionBar(selection.size, notes.size,
                    { selected = if (selection.size == notes.size) emptyList() else notes.map { it.id } }, { selecting = false; selected = emptyList() }) {
                    TextButton({ moveIds = selection }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null); Spacer(Modifier.width(4.dp)); Text("Move") }
                    TextButton({ tagIds = selection }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Sell, null); Spacer(Modifier.width(4.dp)); Text("Tags") }
                    val favorite = notes.filter { it.id in selection }.all { it.starred }
                    TextButton({ model.favoriteNotebooks(selection, !favorite) }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text(if (favorite) "Unfavorite" else "Favorite") }
                    TextButton({ deleteIds = selection }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                }
            }
        }
    }
    if (newFolder) NameDialog("New folder", "Inside ${if (folderScope) path.lastOrNull()?.name ?: "Library" else "Library"}", "", "Create", { newFolder = false }) {
        if (model.createFolder(it, if (folderScope) state.folderId else null)) newFolder = false
    }
    renameFolder?.let { f -> NameDialog("Rename folder", "Give this folder a name", f.name, "Save", { renameFolder = null }) { if (model.renameFolder(f, it)) renameFolder = null } }
    renameNote?.let { n -> NameDialog("Rename notebook", "Give this notebook a name", n.title, "Save", { renameNote = null }) { model.rename(n, it); renameNote = null } }
    moveFolder?.let { f -> LibraryMovePanel("Move ${f.name}", state.folders, f.parentId, single = true,
        onMove = { if (model.moveFolder(f.id, it)) moveFolder = null }, onCreateAndMove = { false }, onDismiss = { moveFolder = null },
        rootLabel = "Library", allowCreate = false, excluded = LibraryFolders.descendants(state.folders, f.id)) }
    removeFolder?.let { f -> ExplorerConfirm("Remove “${f.name}”?", "Its notebooks and subfolders will move to ${LibraryFolders.label(state.folders, f.parentId).ifEmpty { "Library" }}. Your notebooks will be kept.", "Remove folder", { removeFolder = null }) { model.deleteFolder(f); removeFolder = null } }
    moveIds?.let { ids -> LibraryMovePanel("Move ${ids.size} notebook${if (ids.size == 1) "" else "s"}", state.folders,
        onMove = { model.moveNotebooks(ids, it); moveIds = null; selected = emptyList() },
        onCreateAndMove = { model.createFolderAndMove(ids, it).also { moved -> if (moved) { moveIds = null; selected = emptyList() } } }, onDismiss = { moveIds = null }) }
    tagIds?.let { ids -> NotebookTagsPanel(state.notes.filter { it.id in ids }, allTags, { tagIds = null }) { add, remove -> model.updateNotebookTags(ids, add, remove); tagIds = null } }
    deleteIds?.let { ids -> ExplorerConfirm("Delete ${ids.size} notebook${if (ids.size == 1) "" else "s"}?", "This removes the notebooks and their pages from this device. Export a copy from the editor first if you want to keep them.", "Delete", { deleteIds = null }) { model.deleteNotebooks(ids); deleteIds = null; selected = emptyList() } }
}

@Composable internal fun NotebookTagsPanel(notes: List<Notebook>, suggestions: List<String>, onDismiss: () -> Unit, onApply: (List<String>, List<String>) -> Unit) {
    val existing = remember(notes) { NotebookTags.normalize(notes.flatMap { it.tags }, Int.MAX_VALUE) }
    var added by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var input by rememberSaveable { mutableStateOf("") }
    val labels = NotebookTags.normalize(existing + suggestions + added, Int.MAX_VALUE)
    val pending = NotebookTags.normalize(added + listOf(input), Int.MAX_VALUE)
    val tooMany = notes.any { note -> NotebookTags.normalize(note.tags.filterNot { label -> removed.any { it.equals(label, true) } } + pending, Int.MAX_VALUE).size > NotebookTags.MAX_TAGS }
    FolioPanel(if (notes.size == 1) "Notebook tags" else "Tag ${notes.size} notebooks", onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (notes.size == 1) "Labels make notebooks easier to find." else "Mixed tags stay on their original notebooks until you change them.", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it.take(NotebookTags.MAX_LENGTH) }, Modifier.weight(1f), label = { Text("New tag") }, singleLine = true)
                FilledTonalIconButton({ val label = input.trim(); if (label.isNotEmpty()) { added = NotebookTags.normalize(added + label, Int.MAX_VALUE); removed = removed.filterNot { it.equals(label, true) }; input = "" } }, enabled = input.isNotBlank(), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Add tag") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEach { label ->
                    val count = notes.count { n -> n.tags.any { it.equals(label, true) } }
                    val active = label in added || (count > 0 && label !in removed)
                    FilterChip(active, {
                        when {
                            label in added -> { added = added - label; if (count > 0) removed = removed + label }
                            label in removed -> { removed = removed - label; added = added + label }
                            count == notes.size -> removed = removed + label
                            else -> added = added + label
                        }
                    }, { Text(if (count in 1 until notes.size && label !in added && label !in removed) "$label · Mixed" else label) },
                        leadingIcon = { Icon(if (active) Icons.Rounded.Check else Icons.Rounded.Sell, null, Modifier.size(18.dp)) })
                }
            }
            Text(if (tooMany) "A notebook has too many tags. Remove a tag before saving." else "Up to ${NotebookTags.MAX_TAGS} tags per notebook", style = MaterialTheme.typography.bodySmall, color = if (tooMany) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            Button({ onApply(pending, removed) }, enabled = !tooMany, shapes = ButtonDefaults.shapes()) { Text("Save tags") }
        }
    }
}

@Composable internal fun ExplorerBreadcrumbs(crumbs: List<Pair<String, () -> Unit>>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, (label, action) ->
            if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(action, shapes = ButtonDefaults.shapes()) { Text(label, maxLines = 1) }
        }
    }
}

@Composable internal fun ExplorerSearch(query: String, onQuery: (String) -> Unit, hint: String) {
    val focus = LocalFocusManager.current
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text(hint) }, singleLine = true, shape = FolioShapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } })
}

@Composable private fun ExplorerSort(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton({ menu = true }, shapes = ButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(sort.label) }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) { LibrarySort.entries.forEach { option -> DropdownMenuItem({ Text(option.label) }, { onSort(option); menu = false }, trailingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) }) } }
    }
}

@Composable internal fun ExplorerPlace(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = FolioShapes.large, color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().semantics { selected = active }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun ExplorerRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Surface(onClick = onClick, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(icon, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) } },
            trailingContent = trailing, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}

@Composable internal fun ExplorerEmpty(icon: ImageVector, title: String, detail: String, action: @Composable () -> Unit = {}) {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(icon, null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            action()
        }
    }
}

@Composable internal fun ExplorerSelectionBar(count: Int, total: Int, onAll: () -> Unit, onDone: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count selected", Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = MaterialTheme.typography.titleSmall)
                TextButton(onAll, shapes = ButtonDefaults.shapes()) { Text(if (count == total && total > 0) "Deselect all" else "Select all") }
                TextButton(onDone, shapes = ButtonDefaults.shapes()) { Text("Done") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable internal fun ExplorerConfirm(title: String, detail: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.guardUiTouches(),
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), title = { Text(title) }, text = { Text(detail) },
        dismissButton = { TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        confirmButton = { TextButton(onConfirm, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(action) } })
}
