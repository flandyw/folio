@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.music

import com.folio.notes.ui.FolioIcons
import com.folio.notes.model.countOf
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.app.NameDialog
import com.folio.notes.input.guardUiTouches
import com.folio.notes.library.FolderTile
import com.folio.notes.library.LibraryGroupLabel
import com.folio.notes.library.libraryLastEditedLabel
import com.folio.notes.ui.FolioActionPopover
import com.folio.notes.ui.PopoverRow
import androidx.compose.ui.graphics.Color
import com.folio.notes.library.semanticsLabel
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioExpand
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioScreenHeading
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

/**
 * Music as a peer pane of the Library shell: the same shelf rhythm (header, continue card, search,
 * chips, covers), with set lists in the place folders take for notebooks. Opening a score swaps
 * the shelf for the reader and asks the shell to hide its navigation ([onReaderMode]).
 */
@Composable internal fun MusicScreen(folio: FolioViewModel, folioState: FolioState, finger: Boolean, haptics: Boolean, shapeRecognition: Boolean,
    onReaderMode: (Boolean) -> Unit = {}, onSettings: () -> Unit = {}, onBack: () -> Unit) {
    val model: MusicViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val library = state.library
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    var readerStart by rememberSaveable { mutableIntStateOf(0) }
    var performing by rememberSaveable { mutableStateOf(false) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var playingSet by rememberSaveable { mutableStateOf<String?>(null) }
    var setId by rememberSaveable { mutableStateOf<String?>(null) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    // Extra shelf filters: marked-up scores, ones still in progress, and a single composer.
    var annotatedOnly by rememberSaveable { mutableStateOf(false) }
    var unfinishedOnly by rememberSaveable { mutableStateOf(false) }
    var composer by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(musicSort(prefs.getString(AppPrefs.MUSIC_SORT, null))) }
    var listView by rememberSaveable { mutableStateOf(prefs.getBoolean(AppPrefs.MUSIC_LIST, AppPrefs.DEFAULT_LIST_VIEW)) }
    LaunchedEffect(listView) { prefs.edit().putBoolean(AppPrefs.MUSIC_LIST, listView).apply() }
    LaunchedEffect(sort) { prefs.edit().putString(AppPrefs.MUSIC_SORT, sort.name).apply() }
    var importMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var composerMenu by remember { mutableStateOf(false) }
    // Scores waiting to join a set list that is still being named.
    var newSetWith by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var renameSet by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteSet by rememberSaveable { mutableStateOf<String?>(null) }
    var addScoresTo by rememberSaveable { mutableStateOf<String?>(null) }
    var setsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    // Scores waiting to be filed into a folder that is still being named.
    var newFolderWith by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var renameFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var moveFor by rememberSaveable { mutableStateOf<String?>(null) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteScore by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    // Hoisted above the reader so closing a score returns to the same place on the shelf.
    val gridState = rememberLazyGridState()
    var shelfInputs by remember { mutableStateOf(listOf<Any?>(setId, folderId, favorites, query.trim(), sort, composer, annotatedOnly, unfinishedOnly)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::import)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = exportId
        if (uri != null && id != null) model.export(id, uri)
        exportId = null
    }
    val ready = !state.loading && !state.failed
    fun importPdfs() { if (ready && !state.busy) picker.launch(arrayOf("application/pdf")) }
    fun exportScore(score: MusicScore) { exportId = score.id; export.launch("${score.title}.pdf") }
    /** The running order as plain text, so a set list can be sent to the band in one tap. */
    fun shareSet(set: MusicSet) {
        val scores = set.scoresIn(library)
        if (scores.isEmpty()) return
        val order = scores.mapIndexed { index, score ->
            buildString {
                append(index + 1).append(". ").append(score.displayTitle())
                if (score.composer.isNotBlank()) append(" — ").append(score.composer)
            }
        }.joinToString("\n")
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, set.name)
            putExtra(android.content.Intent.EXTRA_TEXT, "${set.name}\n\n$order")
        }
        context.startActivity(android.content.Intent.createChooser(share, "Share running order"))
    }
    fun open(score: MusicScore, set: String?, fromStart: Boolean = false) {
        readerStart = if (fromStart) 0 else score.page
        if (fromStart) model.score(score.id) { it.copy(page = 0) }
        activeId = score.id; playingSet = set; model.opened(score.id)
    }
    // Only the filter chips; the search and the open folder or set stay put.
    fun clearFilters() { favorites = false; annotatedOnly = false; unfinishedOnly = false; composer = null }
    fun clearScope() { query = ""; favorites = false; annotatedOnly = false; unfinishedOnly = false; composer = null; setId = null; folderId = null; focusManager.clearFocus() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    val active = library.scores.find { it.id == activeId }
    LaunchedEffect(active != null) { onReaderMode(active != null) }
    DisposableEffect(Unit) { onDispose { onReaderMode(false) } }
    BackHandler {
        when {
            activeId != null -> { activeId = null; playingSet = null }
            query.isNotEmpty() -> { query = ""; focusManager.clearFocus() }
            setId != null -> setId = null
            folderId != null -> folderId = null
            favorites -> favorites = false
            composer != null -> composer = null
            annotatedOnly -> annotatedOnly = false
            unfinishedOnly -> unfinishedOnly = false
            else -> onBack()
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (active != null) {
            val set = library.sets.find { it.id == playingSet }
            val order = set?.scoresIn(library).orEmpty()
            val position = order.indexOfFirst { it.id == active.id }
            val next = order.getOrNull(position + 1).takeIf { position >= 0 }
            key(active.id) { MusicReader(active, model, folio, folioState, finger, haptics, shapeRecognition,
                initialPage = readerStart, performance = performing, onPerformance = { performing = it },
                onBack = { activeId = null; playingSet = null; performing = false },
                setLabel = set?.let { "${it.name} · ${position + 1} of ${order.size}" },
                runningOrder = order, onChooseScore = { open(it, set?.id, fromStart = true) },
                next = next, onNextScore = { next?.let { open(it, set?.id, fromStart = true) } },
                onDetails = { details = active.id }, onExport = { exportScore(active) },
                onExtract = { model.reviewExisting(active.id) }, onSettings = onSettings) }
        } else BoxWithConstraints(Modifier.fillMaxSize().guardUiTouches()) {
            val wide = maxWidth >= 840.dp
            val selectedSet = library.sets.find { it.id == setId }
            val trimmed = query.trim()
            val filter = MusicFilter(favoritesOnly = favorites, annotatedOnly = annotatedOnly, unfinishedOnly = unfinishedOnly, composer = composer.orEmpty())
            val shown = remember(library, selectedSet, trimmed, sort, filter, folderId) {
                // A set list keeps its running order; a plain shelf obeys the chosen sort and filters.
                if (selectedSet != null) selectedSet.scoresIn(library).filter { it.matchesQuery(trimmed) }
                else organizeScores(library.scores, trimmed, sort, filter, folderId)
            }
            val selectedFolder = library.folders.find { it.id == folderId }
            val scoped = trimmed.isNotEmpty() || filter.active || selectedSet != null || selectedFolder != null
            val starredCount = library.scores.count { it.starred }
            val shelfPages = shown.sumOf { it.pages }
            val shelfMarks = shown.sumOf { it.annotationCount() }
            val composers = composerNames(library.scores)
            val rows = listView || selectedSet != null
            val inputs = listOf(setId, folderId, favorites, trimmed, sort, composer, annotatedOnly, unfinishedOnly)
            LaunchedEffect(inputs) {
                // Only a new scope starts at the top; returning from a score keeps the position.
                if (inputs != shelfInputs) { shelfInputs = inputs; gridState.scrollToItem(0) }
            }
            // Like the notebook shelf: the title names the place, search and view controls sit beside it,
            // and set lists and folders are tiles above the scores instead of chips.
            val roomy = maxWidth >= 720.dp
            val coverWidth = if (maxWidth >= 600.dp) 168.dp else 140.dp
            val home = selectedSet == null && selectedFolder == null && trimmed.isEmpty() && !filter.active
            @Composable fun SearchBox(modifier: Modifier) {
                OutlinedTextField(query, { query = it }, modifier.semanticsLabel("Search scores"),
                    placeholder = { Text(selectedSet?.let { "Search ${it.name}" } ?: selectedFolder?.let { "Search ${it.name}" } ?: "Search scores, composers, marks",
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(FolioIcons.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; focusManager.clearFocus() }, shapes = IconButtonDefaults.shapes()) { Icon(FolioIcons.Close, "Clear search") } },
                    singleLine = true, shape = FolioShapes.panel,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }))
            }
            Column(Modifier.fillMaxSize()) {
                FolioScreenHeading(selectedSet?.name ?: selectedFolder?.name ?: if (favorites) "Favorites" else "Music",
                    leading = if (selectedSet != null || selectedFolder != null) { {
                        IconButton({ setId = null; folderId = null }, shapes = IconButtonDefaults.shapes()) { Icon(FolioIcons.Back, "Back to all music") }
                    } } else null) {
                    if (ready) {
                        if (roomy) SearchBox(Modifier.width(300.dp))
                        // A set list keeps its running order, so sort and covers only apply elsewhere.
                        if (selectedSet == null) {
                            Box {
                                if (roomy) TextButton({ sortMenu = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.semanticsLabel("Sort: ${sort.label}")) {
                                    Icon(FolioIcons.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text(sort.label, maxLines = 1)
                                } else IconButton({ sortMenu = true }, shapes = IconButtonDefaults.shapes()) { Icon(FolioIcons.Sort, "Sort: ${sort.label}") }
                                if (sortMenu) FolioActionPopover("Sort scores", { sortMenu = false }) {
                                    MusicSort.entries.forEach { option ->
                                        PopoverRow(if (sort == option) FolioIcons.Check else null, option.label, selected = sort == option) { sort = option; sortMenu = false }
                                    }
                                }
                            }
                            IconButton({ listView = !listView }, shapes = IconButtonDefaults.shapes()) {
                                Icon(if (listView) FolioIcons.Pages else FolioIcons.List, if (listView) "Switch to covers" else "Switch to list")
                            }
                        }
                    }
                    Box {
                        // The same split button the notebook shelf uses for New: tap imports,
                        // the trailing half holds the other ways to add.
                        SplitButtonLayout(
                            leadingButton = { SplitButtonDefaults.LeadingButton(::importPdfs, Modifier.semanticsLabel("Import sheet music PDFs"), enabled = ready && !state.busy) {
                                // The label above names it in both layouts; the short visible word would repeat it.
                                Icon(FolioIcons.Plus, null); if (roomy) { Spacer(Modifier.width(FolioSpacing.dp8)); Text("Import", Modifier.clearAndSetSemantics {}) }
                            } },
                            trailingButton = { SplitButtonDefaults.TrailingButton({ importMenu = true }, enabled = ready) {
                                Icon(FolioIcons.DropDown, "More ways to add music")
                            } },
                        )
                        FolioMenuPopover(importMenu, { importMenu = false }, modifier = Modifier.guardUiTouches(), title = "Add to music") {
                            FolioMenuItem({ Text("Import sheet music PDFs") }, { importMenu = false; importPdfs() },
                                leadingIcon = { Icon(FolioIcons.Pdf, null) }, enabled = !state.busy)
                            FolioMenuItem({ Text("New folder") }, { importMenu = false; newFolderWith = emptyList() },
                                leadingIcon = { Icon(FolioIcons.FolderAdd, null) })
                            FolioMenuItem({ Text("New set list") }, { importMenu = false; newSetWith = emptyList() },
                                leadingIcon = { Icon(FolioIcons.ListAdd, null) })
                        }
                    }
                }
            LazyVerticalGrid(
                columns = if (rows) GridCells.Fixed(1) else GridCells.Adaptive(coverWidth),
                modifier = Modifier.weight(1f).fillMaxWidth(), state = gridState,
                contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, bottom = FolioDestinationInset),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16),
                verticalArrangement = Arrangement.spacedBy(if (rows) FolioSpacing.dp8 else FolioSpacing.dp24),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        FolioExpand(state.busy) {
                            Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                    LoadingIndicator(Modifier.size(28.dp))
                                    Text("Preparing your sheet music…", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                        if (ready) {
                            if (!roomy) SearchBox(Modifier.fillMaxWidth())
                            // Quick filters only; places (set lists, folders) are tiles below.
                            if (selectedSet == null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                FilterChip(!filter.active, ::clearFilters,
                                    { Text(if (selectedFolder == null) "All · ${library.scores.size}" else "All") },
                                    leadingIcon = { Icon(FolioIcons.MusicLibrary, null, Modifier.size(16.dp)) })
                                FilterChip(favorites, { favorites = !favorites },
                                    { Text(if (starredCount > 0) "Favorites · $starredCount" else "Favorites") },
                                    leadingIcon = { Icon(FolioIcons.Star, null, Modifier.size(16.dp)) })
                                FilterChip(annotatedOnly, { annotatedOnly = !annotatedOnly }, { Text("Annotated") },
                                    leadingIcon = { Icon(FolioIcons.Pen, null, Modifier.size(16.dp)) })
                                FilterChip(unfinishedOnly, { unfinishedOnly = !unfinishedOnly }, { Text("Unfinished") },
                                    leadingIcon = { Icon(FolioIcons.Pending, null, Modifier.size(16.dp)) })
                                // One chip for every composer, so a large shelf doesn't grow an endless row.
                                if (composers.size > 1) Box {
                                    FilterChip(composer != null, { composerMenu = true }, { Text(composer ?: "Composer", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(FolioIcons.Person, null, Modifier.size(16.dp)) },
                                        trailingIcon = { Icon(FolioIcons.DropDown, null, Modifier.size(16.dp)) })
                                    if (composerMenu) FolioActionPopover("Composer", { composerMenu = false }) {
                                        if (composer != null) PopoverRow(FolioIcons.FilterOff, "Any composer") { composer = null; composerMenu = false }
                                        composers.forEach { name ->
                                            val picked = composer.equals(name, true)
                                            PopoverRow(if (picked) FolioIcons.Check else null, name, selected = picked) { composer = if (picked) null else name; composerMenu = false }
                                        }
                                    }
                                }
                            }
                            if (home) library.scores.filter { it.opened > 0 }.maxByOrNull { it.opened }?.let { recent ->
                                Surface(onClick = { open(recent, null) }, shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                                        ScoreFace(recent, model, Modifier.width(56.dp).height(74.dp), small = true)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                                            Text("Continue playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(recent.displayTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(listOf("Page ${recent.page + 1} of ${recent.pages}", recent.readingProgressLabel().takeIf { it.isNotEmpty() },
                                                libraryLastEditedLabel(recent.opened)).filterNotNull().joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (recent.readingProgress() > 0f) LinearProgressIndicator(progress = { recent.readingProgress() },
                                                modifier = Modifier.fillMaxWidth().padding(top = FolioSpacing.dp6).height(4.dp))
                                        }
                                        FilledIconButton({ open(recent, null) }, shapes = IconButtonDefaults.shapes()) { Icon(FolioIcons.Play, "Open the score you played last") }
                                    }
                                }
                            }
                            if (selectedSet != null) SetHeader(selectedSet, library,
                                onPlay = { selectedSet.scoresIn(library).firstOrNull()?.let { open(it, selectedSet.id) } },
                                onAdd = { addScoresTo = selectedSet.id }, onRename = { renameSet = selectedSet.id }, onDelete = { deleteSet = selectedSet.id },
                                onDuplicate = { model.duplicateSet(selectedSet.id) },
                                onReverse = { model.set(selectedSet.id) { it.reversedOrder() } },
                                onShuffle = { model.set(selectedSet.id) { it.copy(scores = shuffledOrder(it.scores)) } },
                                onShare = { shareSet(selectedSet) })
                        }
                    }
                }
                // Tiles span two cover columns on a wide shelf, the whole row otherwise.
                fun tileSpan(maxLineSpan: Int) = GridItemSpan(if (rows || maxLineSpan < 4) maxLineSpan else 2)
                if (ready && home && (library.scores.isNotEmpty() || library.sets.isNotEmpty())) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "sets-label") {
                        ShelfGroupLabel("Set lists") { TextButton({ newSetWith = emptyList() }, shapes = ButtonDefaults.shapes()) {
                            Icon(FolioIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("New set list")
                        } }
                    }
                    items(library.sets, key = { "set:${it.id}" }, span = { tileSpan(maxLineSpan) }) { set ->
                        FolderTile(set.name, icon = FolioIcons.QueueMusic, onClick = { setId = set.id },
                            detail = listOfNotNull(set.scoresIn(library).setDurationText(), countOf(set.scores.size, "score")).joinToString(" · "),
                            menu = { close ->
                                PopoverRow(FolioIcons.Play, "Play set", enabled = set.scores.isNotEmpty()) { close(); set.scoresIn(library).firstOrNull()?.let { open(it, set.id) } }
                                PopoverRow(FolioIcons.Pen, "Rename set list") { close(); renameSet = set.id }
                                PopoverRow(FolioIcons.Copy, "Duplicate set list") { close(); model.duplicateSet(set.id) }
                                PopoverRow(FolioIcons.Delete, "Delete set list", destructive = true) { close(); deleteSet = set.id }
                            })
                    }
                    if (library.folders.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "folders-label") {
                            ShelfGroupLabel("Folders") { TextButton({ newFolderWith = emptyList() }, shapes = ButtonDefaults.shapes()) {
                                Icon(FolioIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("New folder")
                            } }
                        }
                        items(library.folders, key = { "folder:${it.id}" }, span = { tileSpan(maxLineSpan) }) { folder ->
                            FolderTile(folder.name, detail = countOf(library.scores.count { it.folder == folder.id }, "score"), onClick = { folderId = folder.id },
                                menu = { close ->
                                    PopoverRow(FolioIcons.Pen, "Rename folder") { close(); renameFolder = folder.id }
                                    PopoverRow(FolioIcons.Delete, "Delete folder", destructive = true) { close(); deleteFolder = folder.id }
                                })
                        }
                    }
                }
                if (ready && selectedSet == null && shown.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "scores-label") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semanticsLabel("${countOf(shown.size, "score")}, ${countOf(shelfPages, "page")}, ${countOf(shelfMarks, "pencil mark")}")) {
                        LibraryGroupLabel(if (trimmed.isNotEmpty()) "Search results · ${shown.size}" else "Scores · ${shown.size}")
                    }
                }
                when {
                    state.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp32), contentAlignment = Alignment.Center) {
                            ContainedLoadingIndicator(Modifier.semanticsLabel("Loading music"))
                        }
                    }
                    state.failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                        ShelfMessage(FolioIcons.Alert, "Music couldn't be opened", "Your scores and annotations have been kept. Retry to open them.") {
                            Button(model::reload, shapes = ButtonDefaults.shapes()) { Text("Retry") }
                        }
                    }
                    shown.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(shape = FolioShapes.panel, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp32),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                                ScoreStack(Modifier.size(124.dp, 140.dp))
                                Text(when {
                                    trimmed.isNotEmpty() -> "No scores match “$trimmed”"
                                    filter.active -> "No scores match these filters"
                                    selectedSet != null -> "Build the running order"
                                    selectedFolder != null -> "This folder is empty"
                                    favorites -> "Keep your go-to pieces close"
                                    else -> "Your repertoire lives here."
                                }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                Text(when {
                                    trimmed.isNotEmpty() -> "Try a title, composer or instrument."
                                    filter.active -> "Clear the filters to see your whole shelf."
                                    selectedSet != null -> "Add scores in the order you'll play them."
                                    selectedFolder != null -> "Use a score's menu and choose Move to folder."
                                    favorites -> "Tap the star on a score to find it here."
                                    else -> "Bring in sheet music PDFs. Folio keeps its own copy,\nso every score is ready offline."
                                }, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                when {
                                    selectedSet != null && trimmed.isEmpty() -> TextButton({ addScoresTo = selectedSet.id }, enabled = library.scores.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                                        Icon(FolioIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add scores")
                                    }
                                    scoped -> TextButton(::clearScope, shapes = ButtonDefaults.shapes()) { Text("Show all scores") }
                                    else -> TextButton(::importPdfs, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                                        Text("Import sheet music"); Spacer(Modifier.width(FolioSpacing.dp8)); Icon(FolioIcons.Forward, null, Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    selectedSet != null -> itemsIndexed(shown, key = { _, score -> score.id }) { index, score ->
                        // Reordering a filtered list would move scores the user cannot see.
                        val order = trimmed.isEmpty()
                        SetScoreRow(index + 1, score, model, Modifier.animateItem(placementSpec = com.folio.notes.ui.folioSpring()),
                            onOpen = { open(score, selectedSet.id) },
                            onUp = if (order && index > 0) ({ model.move(selectedSet.id, score.id, -1) }) else null,
                            onDown = if (order && index < shown.lastIndex) ({ model.move(selectedSet.id, score.id, 1) }) else null) {
                            ScoreMenu(score, state.busy, onDetails = { details = score.id }, onSets = { setsFor = score.id }, onMove = { moveFor = score.id },
                                onExtract = { model.reviewExisting(score.id) }, onExport = { exportScore(score) }, onDelete = { deleteScore = score.id },
                                onDuplicate = { model.duplicate(score.id) },
                                onRemoveFromSet = { model.set(selectedSet.id) { it.copy(scores = it.scores - score.id) } })
                        }
                    }
                    else -> items(shown, key = { it.id }) { score ->
                        val star = { model.score(score.id) { it.copy(starred = !it.starred) } }
                        val menu: @Composable () -> Unit = {
                            ScoreMenu(score, state.busy, onDetails = { details = score.id }, onSets = { setsFor = score.id }, onMove = { moveFor = score.id },
                                onExtract = { model.reviewExisting(score.id) }, onExport = { exportScore(score) }, onDelete = { deleteScore = score.id },
                                onDuplicate = { model.duplicate(score.id) })
                        }
                        val placement = if (wide) Modifier else Modifier.animateItem(placementSpec = com.folio.notes.ui.folioSpring())
                        if (rows) ScoreRow(score, model, placement, { open(score, null) }, star, { deleteScore = score.id }, menu)
                        else ScoreCard(score, model, placement, { open(score, null) }, star, menu)
                    }
                }
            }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    state.reviews.firstOrNull()?.let { review -> key(review.source.id) { MusicImportPanel(review, model, state.busy, state.message) } }
    newSetWith?.let { scores ->
        NameDialog("New set list", "", "", "Create set list", { newSetWith = null }) {
            model.newSet(it, scores); newSetWith = null
        }
    }
    newFolderWith?.let { scores ->
        NameDialog("New folder", "", "", "Create folder", { newFolderWith = null }) {
            model.newFolder(it, scores); newFolderWith = null
        }
    }
    library.folders.find { it.id == renameFolder }?.let { folder ->
        NameDialog("Rename folder", "", folder.name, "Save", { renameFolder = null }) { name ->
            model.renameFolder(folder.id, name); renameFolder = null
        }
    }
    library.folders.find { it.id == deleteFolder }?.let { folder ->
        val inside = library.scores.count { it.folder == folder.id }
        ConfirmDelete("Delete “${folder.name}”?", "The folder is removed. Its $inside ${if (inside == 1) "score stays" else "scores stay"} in Music, unfiled.",
            "Delete folder", { deleteFolder = null }) { model.deleteFolder(folder.id); if (folderId == folder.id) folderId = null; deleteFolder = null }
    }
    library.scores.find { it.id == moveFor }?.let { score ->
        FolderPickerPanel(score, library.folders, model, onNewFolder = { moveFor = null; newFolderWith = listOf(score.id) }) { moveFor = null }
    }
    library.sets.find { it.id == renameSet }?.let { set ->
        NameDialog("Rename set list", "", set.name, "Save", { renameSet = null }) { name ->
            model.set(set.id) { it.copy(name = name) }; renameSet = null
        }
    }
    library.sets.find { it.id == deleteSet }?.let { set ->
        ConfirmDelete("Delete “${set.name}”?", "The set list is removed. Its ${set.scores.size} ${if (set.scores.size == 1) "score stays" else "scores stay"} in Music.",
            "Delete set list", { deleteSet = null }) { model.deleteSet(set.id); if (setId == set.id) setId = null; deleteSet = null }
    }
    library.scores.find { it.id == deleteScore }?.let { score ->
        ConfirmDelete("Delete “${score.title}”?", "Removes Folio's copy of this score, its pencil marks, rehearsal marks and set list places. The original PDF you imported is not touched.",
            "Delete score", { deleteScore = null }) { model.delete(score.id); folio.deleteMusicScore(score.id); deleteScore = null }
    }
    library.scores.find { it.id == details }?.let { score ->
        ScoreDetailsPanel(score, { details = null }) { title, composer, part, notes, durationSeconds ->
            model.score(score.id) { it.copy(title = title, composer = composer, part = part, notes = notes, durationSeconds = durationSeconds) }; details = null
        }
    }
    library.sets.find { it.id == addScoresTo }?.let { set -> AddScoresPanel(set, library.scores, model) { addScoresTo = null } }
    library.scores.find { it.id == setsFor }?.let { score ->
        SetPickerPanel(score, library.sets, model, onNewSet = { setsFor = null; newSetWith = listOf(score.id) }) { setsFor = null }
    }
}

/** The selected set list as a hero card: what it is, how long it runs, and the way to start it. */
@Composable private fun SetHeader(set: MusicSet, library: MusicLibrary, onPlay: () -> Unit, onAdd: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onDuplicate: () -> Unit, onReverse: () -> Unit, onShuffle: () -> Unit, onShare: () -> Unit) {
    val scores = set.scoresIn(library)
    var menu by remember { mutableStateOf(false) }
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Icon(FolioIcons.QueueMusic, null, Modifier.padding(FolioSpacing.dp10).size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Set list", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(set.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${countOf(scores.size, "score")} · ${countOf(scores.sumOf { it.pages }, "page")}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    scores.setDurationText()?.let {
                        Text("Duration: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(FolioIcons.MoreVertical, "Set list options") }
                    FolioMenuPopover(menu, { menu = false }, modifier = Modifier.guardUiTouches(), title = "Set list") {
                        FolioMenuItem({ Text("Rename set list") }, { menu = false; onRename() }, leadingIcon = { Icon(FolioIcons.Pen, null) })
                        FolioMenuItem({ Text("Duplicate set list") }, { menu = false; onDuplicate() }, leadingIcon = { Icon(FolioIcons.Copy, null) })
                        FolioMenuItem({ Text("Delete set list") }, { menu = false; onDelete() }, leadingIcon = { Icon(FolioIcons.Delete, null) }, destructive = true)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Button(onPlay, enabled = scores.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                    Icon(FolioIcons.Play, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Play set")
                }
                OutlinedButton(onAdd, enabled = library.scores.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                    Icon(FolioIcons.Plus, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add scores")
                }
                // Reordering the running order without dragging cards one at a time.
                OutlinedButton(onReverse, enabled = scores.size > 1, shapes = ButtonDefaults.shapes()) {
                    Icon(FolioIcons.SwapVertical, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Reverse")
                }
                OutlinedButton(onShuffle, enabled = scores.size > 1, shapes = ButtonDefaults.shapes()) {
                    Icon(FolioIcons.Shuffle, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Shuffle")
                }
                OutlinedButton(onShare, enabled = scores.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                    Icon(FolioIcons.Share, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Share")
                }
            }
        }
    }
}

/** A shelf section's label, with its one create action at the end of the line. */
@Composable private fun ShelfGroupLabel(text: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { LibraryGroupLabel(text) }
        action()
    }
}

@Composable private fun ShelfMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: @Composable () -> Unit) {
    Surface(shape = FolioShapes.panel, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp32),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.error)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}
