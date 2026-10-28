@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.music

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.folio.notes.library.libraryLastEditedLabel
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
    var setMenu by remember { mutableStateOf<String?>(null) }
    // Scores waiting to join a set list that is still being named.
    var newSetWith by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var renameSet by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteSet by rememberSaveable { mutableStateOf<String?>(null) }
    var addScoresTo by rememberSaveable { mutableStateOf<String?>(null) }
    var setsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
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
    fun open(score: MusicScore, set: String?) { activeId = score.id; playingSet = set; model.opened(score.id) }
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
            val position = set?.scores?.indexOf(active.id) ?: -1
            val next = set?.scores?.getOrNull(position + 1)?.let { id -> library.scores.find { it.id == id } }
            key(active.id) { MusicReader(active, model, folio, folioState, finger, haptics, shapeRecognition,
                onBack = { activeId = null; playingSet = null },
                setLabel = set?.let { "${it.name} · ${position + 1} of ${it.scores.size}" },
                next = next, onNextScore = { next?.let { open(it, set.id) } },
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
            Column(Modifier.fillMaxSize()) {
                    FolioScreenHeading("Music") {
                        Box {
                            // The same split button the notebook shelf uses for New: tap imports,
                            // the trailing half holds the other ways to add.
                            SplitButtonLayout(
                                leadingButton = { SplitButtonDefaults.LeadingButton(::importPdfs, enabled = ready && !state.busy) {
                                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Import")
                                } },
                                trailingButton = { SplitButtonDefaults.TrailingButton({ importMenu = true }, enabled = ready) {
                                    Icon(Icons.Rounded.ArrowDropDown, "More ways to add music")
                                } },
                            )
                            FolioMenuPopover(importMenu, { importMenu = false }, modifier = Modifier.guardUiTouches(), title = "Add to music") {
                                FolioMenuItem({ Text("Import sheet music PDFs") }, { importMenu = false; importPdfs() },
                                    leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) }, enabled = !state.busy)
                                FolioMenuItem({ Text("New folder") }, { importMenu = false; newFolderWith = emptyList() },
                                    leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) })
                                FolioMenuItem({ Text("New set list") }, { importMenu = false; newSetWith = emptyList() },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                            }
                        }
                    }
            LazyVerticalGrid(
                columns = if (rows) GridCells.Fixed(1) else GridCells.Adaptive(144.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(), state = gridState,
                contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, bottom = FolioDestinationInset),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
                verticalArrangement = Arrangement.spacedBy(if (rows) FolioSpacing.dp8 else FolioSpacing.dp16),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        FolioExpand(state.busy) {
                            Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                    LoadingIndicator(Modifier.size(28.dp))
                                    Text("Preparing your sheet music…", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                        if (ready && !scoped) library.scores.filter { it.opened > 0 }.maxByOrNull { it.opened }?.let { recent ->
                            Surface(onClick = { open(recent, null) }, shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                    ScoreFace(recent, model, Modifier.width(32.dp).height(42.dp), small = true)
                                    Column(Modifier.weight(1f)) {
                                        Text(listOf("Continue playing", "page ${recent.page + 1} of ${recent.pages}",
                                            recent.readingProgressLabel().takeIf { it.isNotEmpty() }, libraryLastEditedLabel(recent.opened))
                                            .filterNotNull().joinToString(" · "),
                                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(recent.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (recent.readingProgress() > 0f) LinearProgressIndicator(progress = { recent.readingProgress() },
                                            modifier = Modifier.fillMaxWidth().padding(top = FolioSpacing.dp6).height(4.dp))
                                    }
                                    Icon(Icons.Rounded.PlayArrow, "Open the score you played last")
                                }
                            }
                        }
                        if (ready) {
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text("Find scores, composers, notes or marks…") },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; focusManager.clearFocus() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } },
                                singleLine = true, shape = FolioShapes.extraLarge,
                                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }))
                            // Set lists sit where folders do on the notebook shelf, menu and all.
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                FilterChip(selectedSet == null && !favorites && selectedFolder == null, { setId = null; favorites = false; folderId = null }, { Text("All scores") },
                                    leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(16.dp)) })
                                FilterChip(selectedSet == null && favorites, { setId = null; folderId = null; favorites = !favorites },
                                    { Text(if (starredCount > 0) "Favorites · $starredCount" else "Favorites") },
                                    leadingIcon = { Icon(Icons.Rounded.StarOutline, null, Modifier.size(16.dp)) })
                                FilterChip(annotatedOnly, { annotatedOnly = !annotatedOnly; setId = null }, { Text("Annotated") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) })
                                FilterChip(unfinishedOnly, { unfinishedOnly = !unfinishedOnly; setId = null }, { Text("Unfinished") },
                                    leadingIcon = { Icon(Icons.Rounded.Pending, null, Modifier.size(16.dp)) })
                                library.folders.forEach { folder -> Box {
                                    FilterChip(folder.id == folderId && selectedSet == null, { setId = null; favorites = false; folderId = if (folder.id == folderId) null else folder.id },
                                        { Text("${folder.name} · ${library.scores.count { it.folder == folder.id }}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(if (folder.id == folderId) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null, Modifier.size(16.dp)) },
                                        trailingIcon = {
                                            IconButton({ folderMenu = folder.id }, modifier = Modifier.size(32.dp), shapes = IconButtonDefaults.shapes()) {
                                                Icon(Icons.Rounded.MoreVert, "Options for ${folder.name}", Modifier.size(18.dp))
                                            }
                                        })
                                    FolioMenuPopover(folderMenu == folder.id, { folderMenu = null }, modifier = Modifier.guardUiTouches(), title = "Folder") {
                                        FolioMenuItem({ Text("Rename folder") }, { folderMenu = null; renameFolder = folder.id }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                                        FolioMenuItem({ Text("Delete folder") }, { folderMenu = null; deleteFolder = folder.id }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, destructive = true)
                                    }
                                } }
                                library.sets.forEach { set -> Box {
                                    FilterChip(set.id == setId, { setId = if (set.id == setId) null else set.id; favorites = false; folderId = null },
                                        { Text("${set.name} · ${set.scores.size}") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(16.dp)) },
                                        trailingIcon = {
                                            IconButton({ setMenu = set.id }, modifier = Modifier.size(32.dp), shapes = IconButtonDefaults.shapes()) {
                                                Icon(Icons.Rounded.MoreVert, "Options for ${set.name}", Modifier.size(18.dp))
                                            }
                                        })
                                    FolioMenuPopover(setMenu == set.id, { setMenu = null }, modifier = Modifier.guardUiTouches(), title = "Set list") {
                                        FolioMenuItem({ Text("Rename set list") }, { setMenu = null; renameSet = set.id }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                                        FolioMenuItem({ Text("Duplicate set list") }, { setMenu = null; model.newSet("${set.name} copy", set.scores) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                                        FolioMenuItem({ Text("Delete set list") }, { setMenu = null; deleteSet = set.id }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, destructive = true)
                                    }
                                } }
                                AssistChip({ newFolderWith = emptyList() }, { Text("New folder") }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(16.dp)) })
                                AssistChip({ newSetWith = emptyList() }, { Text("New set list") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                                // Composers become their own filters once a shelf holds more than one voice.
                                if (composers.size > 1) composers.forEach { name ->
                                    FilterChip(composer.equals(name, true), { setId = null; composer = if (composer.equals(name, true)) null else name },
                                        { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(Icons.Rounded.Person, null, Modifier.size(16.dp)) })
                                }
                                if (filter.active) AssistChip(::clearScope, { Text("Clear filters") },
                                    leadingIcon = { Icon(Icons.Rounded.FilterAltOff, null, Modifier.size(16.dp)) })
                            }
                            if (selectedSet != null) SetHeader(selectedSet, library,
                                onPlay = { selectedSet.scoresIn(library).firstOrNull()?.let { open(it, selectedSet.id) } },
                                onAdd = { addScoresTo = selectedSet.id }, onRename = { renameSet = selectedSet.id }, onDelete = { deleteSet = selectedSet.id },
                                onReverse = { model.set(selectedSet.id) { it.reversedOrder() } },
                                onShuffle = { model.set(selectedSet.id) { it.copy(scores = shuffledOrder(it.scores)) } },
                                onShare = { shareSet(selectedSet) })
                            else Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (trimmed.isNotEmpty()) "Search results" else if (selectedFolder != null) selectedFolder.name else if (favorites) "Favorites" else "Your scores", Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(FolioSpacing.dp8))
                                Surface(shape = FolioShapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.semanticsLabel("${shown.size} scores, $shelfPages pages, $shelfMarks pencil marks")) {
                                    Text("${shown.size}", Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Box {
                                    TextButton({ sortMenu = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.semanticsLabel("Sort: ${sort.label}")) {
                                        Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text(sort.label, maxLines = 1)
                                    }
                                    FolioMenuPopover(sortMenu, { sortMenu = false }, modifier = Modifier.guardUiTouches(), title = "Sort scores") {
                                        MusicSort.entries.forEach { option ->
                                            FolioMenuItem({ Text(option.label) }, { sort = option; sortMenu = false }, selected = sort == option)
                                        }
                                    }
                                }
                                IconButton({ listView = !listView }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(if (listView) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList, if (listView) "Switch to covers" else "Switch to list")
                                }
                            }
                        }
                    }
                }
                when {
                    state.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp32), contentAlignment = Alignment.Center) {
                            ContainedLoadingIndicator(Modifier.semanticsLabel("Loading music"))
                        }
                    }
                    state.failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                        ShelfMessage(Icons.Rounded.ErrorOutline, "Music couldn't be opened", "Your scores and annotations have been kept. Retry to open them.") {
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
                                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add scores")
                                    }
                                    scoped -> TextButton(::clearScope, shapes = ButtonDefaults.shapes()) { Text("Show all scores") }
                                    else -> TextButton(::importPdfs, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                                        Text("Import sheet music"); Spacer(Modifier.width(FolioSpacing.dp8)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
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
        ScoreDetailsPanel(score, { details = null }) { title, composer, part, notes ->
            model.score(score.id) { it.copy(title = title, composer = composer, part = part, notes = notes) }; details = null
        }
    }
    library.sets.find { it.id == addScoresTo }?.let { set -> AddScoresPanel(set, library.scores, model) { addScoresTo = null } }
    library.scores.find { it.id == setsFor }?.let { score ->
        SetPickerPanel(score, library.sets, model, onNewSet = { setsFor = null; newSetWith = listOf(score.id) }) { setsFor = null }
    }
}

/** The selected set list as a hero card: what it is, how long it runs, and the way to start it. */
@Composable private fun SetHeader(set: MusicSet, library: MusicLibrary, onPlay: () -> Unit, onAdd: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onReverse: () -> Unit, onShuffle: () -> Unit, onShare: () -> Unit) {
    val scores = set.scoresIn(library)
    var menu by remember { mutableStateOf(false) }
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.padding(FolioSpacing.dp10).size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Set list", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(set.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf("${scores.size} ${if (scores.size == 1) "score" else "scores"}", "${scores.sumOf { it.pages }} pages",
                        if (scores.isEmpty()) null else "≈${set.estimatedMinutes(library)} min").filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Set list options") }
                    FolioMenuPopover(menu, { menu = false }, modifier = Modifier.guardUiTouches(), title = "Set list") {
                        FolioMenuItem({ Text("Rename set list") }, { menu = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        FolioMenuItem({ Text("Delete set list") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, destructive = true)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Button(onPlay, enabled = scores.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Play set")
                }
                OutlinedButton(onAdd, enabled = library.scores.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add scores")
                }
                // Reordering the running order without dragging cards one at a time.
                OutlinedButton(onReverse, enabled = scores.size > 1, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.SwapVert, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Reverse")
                }
                OutlinedButton(onShuffle, enabled = scores.size > 1, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Shuffle")
                }
                OutlinedButton(onShare, enabled = scores.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.IosShare, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Share")
                }
            }
        }
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
