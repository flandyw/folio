@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.music

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.AppPrefs
import com.folio.notes.FolioExpand
import com.folio.notes.FolioPanel
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.NameDialog
import com.folio.notes.folioSelected
import com.folio.notes.guardUiTouches
import com.folio.notes.libraryLastEditedLabel
import com.folio.notes.semanticsLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shelf order; a set list always keeps its own running order instead. */
internal enum class MusicSort(val label: String) { TITLE("Title"), COMPOSER("Composer"), RECENT("Recently played") }

/**
 * Music as a peer pane of the Library shell: the same shelf rhythm (header, continue card, search,
 * chips, covers), with set lists in the place folders take for notebooks. Opening a score swaps
 * the shelf for the reader and asks the shell to hide its navigation ([onReaderMode]).
 */
@Composable internal fun MusicScreen(topGap: Dp = 0.dp, onReaderMode: (Boolean) -> Unit = {}, onBack: () -> Unit) {
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
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(MusicSort.TITLE) }
    var listView by rememberSaveable { mutableStateOf(prefs.getBoolean(AppPrefs.MUSIC_LIST, AppPrefs.DEFAULT_LIST_VIEW)) }
    LaunchedEffect(listView) { prefs.edit().putBoolean(AppPrefs.MUSIC_LIST, listView).apply() }
    var importMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var setMenu by remember { mutableStateOf<String?>(null) }
    // Scores waiting to join a set list that is still being named.
    var newSetWith by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var renameSet by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteSet by rememberSaveable { mutableStateOf<String?>(null) }
    var addScoresTo by rememberSaveable { mutableStateOf<String?>(null) }
    var setsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteScore by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    // Hoisted above the reader so closing a score returns to the same place on the shelf.
    val gridState = rememberLazyGridState()
    var shelfInputs by remember { mutableStateOf(listOf<Any?>(setId, favorites, query.trim(), sort)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::import)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = exportId
        if (uri != null && id != null) model.export(id, uri)
        exportId = null
    }
    val ready = !state.loading && !state.failed
    fun importPdfs() { if (ready && !state.busy) picker.launch(arrayOf("application/pdf")) }
    fun exportScore(score: MusicScore) { exportId = score.id; export.launch("${score.title}.pdf") }
    fun open(score: MusicScore, set: String?) { activeId = score.id; playingSet = set; model.opened(score.id) }
    fun clearScope() { query = ""; favorites = false; setId = null; focusManager.clearFocus() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    val active = library.scores.find { it.id == activeId }
    LaunchedEffect(active != null) { onReaderMode(active != null) }
    DisposableEffect(Unit) { onDispose { onReaderMode(false) } }
    BackHandler {
        when {
            activeId != null -> { activeId = null; playingSet = null }
            query.isNotEmpty() -> { query = ""; focusManager.clearFocus() }
            setId != null -> setId = null
            favorites -> favorites = false
            else -> onBack()
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (active != null) {
            val set = library.sets.find { it.id == playingSet }
            val position = set?.scores?.indexOf(active.id) ?: -1
            val next = set?.scores?.getOrNull(position + 1)?.let { id -> library.scores.find { it.id == id } }
            MusicReader(active, model,
                onBack = { activeId = null; playingSet = null },
                setLabel = set?.let { "${it.name} · ${position + 1} of ${it.scores.size}" },
                next = next, onNextScore = { next?.let { open(it, set.id) } },
                onDetails = { details = active.id }, onExport = { exportScore(active) },
                onExtract = { model.reviewExisting(active.id) })
        } else BoxWithConstraints(Modifier.fillMaxSize().guardUiTouches()) {
            val wide = maxWidth >= 840.dp
            val selectedSet = library.sets.find { it.id == setId }
            val trimmed = query.trim()
            val shown = remember(library, selectedSet, favorites, trimmed, sort) {
                val base = if (selectedSet != null) selectedSet.scores.mapNotNull { id -> library.scores.find { it.id == id } }
                    else library.scores.filter { !favorites || it.starred }.let { scores -> when (sort) {
                        MusicSort.TITLE -> scores.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        MusicSort.COMPOSER -> scores.sortedWith(compareBy<MusicScore, String>(String.CASE_INSENSITIVE_ORDER) { it.composer.ifBlank { "￿" } }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        MusicSort.RECENT -> scores.sortedByDescending { it.opened }
                    } }
                base.filter { score -> trimmed.isEmpty() || listOf(score.title, score.composer, score.part).any { it.contains(trimmed, true) } }
            }
            val scoped = trimmed.isNotEmpty() || favorites || selectedSet != null
            val starredCount = library.scores.count { it.starred }
            val rows = listView || selectedSet != null
            val inputs = listOf(setId, favorites, trimmed, sort)
            LaunchedEffect(inputs) {
                // Only a new scope starts at the top; returning from a score keeps the position.
                if (inputs != shelfInputs) { shelfInputs = inputs; gridState.scrollToItem(0) }
            }
            LazyVerticalGrid(
                columns = if (rows) GridCells.Fixed(1) else GridCells.Adaptive(144.dp),
                modifier = Modifier.fillMaxSize(), state = gridState,
                contentPadding = (if (wide) 20.dp else 12.dp).let { PaddingValues(start = it, top = it + topGap, end = it, bottom = it + FolioSpacing.dp24) },
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
                verticalArrangement = Arrangement.spacedBy(if (rows) FolioSpacing.dp8 else FolioSpacing.dp16),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Text("Music", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
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
                                DropdownMenu(importMenu, { importMenu = false }, modifier = Modifier.guardUiTouches()) {
                                    DropdownMenuItem({ Text("Import sheet music PDFs") }, { importMenu = false; importPdfs() },
                                        leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) }, enabled = !state.busy)
                                    DropdownMenuItem({ Text("New set list") }, { importMenu = false; newSetWith = emptyList() },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
                                }
                            }
                        }
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
                                        Text("Continue playing · page ${recent.page + 1} of ${recent.pages} · ${libraryLastEditedLabel(recent.opened)}",
                                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(recent.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(Icons.Rounded.PlayArrow, "Open the score you played last")
                                }
                            }
                        }
                        if (ready) {
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text("Find scores, composers or parts…") },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } },
                                singleLine = true, shape = FolioShapes.extraLarge,
                                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }))
                            // Set lists sit where folders do on the notebook shelf, menu and all.
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                FilterChip(selectedSet == null && !favorites, { setId = null; favorites = false }, { Text("All scores") },
                                    leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(16.dp)) })
                                FilterChip(selectedSet == null && favorites, { setId = null; favorites = !favorites },
                                    { Text(if (starredCount > 0) "Favorites · $starredCount" else "Favorites") },
                                    leadingIcon = { Icon(Icons.Rounded.StarOutline, null, Modifier.size(16.dp)) })
                                library.sets.forEach { set -> Box {
                                    FilterChip(set.id == setId, { setId = if (set.id == setId) null else set.id; favorites = false },
                                        { Text("${set.name} · ${set.scores.size}") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(16.dp)) },
                                        trailingIcon = {
                                            IconButton({ setMenu = set.id }, modifier = Modifier.size(24.dp), shapes = IconButtonDefaults.shapes()) {
                                                Icon(Icons.Rounded.MoreVert, "Options for ${set.name}", Modifier.size(14.dp))
                                            }
                                        })
                                    DropdownMenu(setMenu == set.id, { setMenu = null }, modifier = Modifier.guardUiTouches()) {
                                        DropdownMenuItem({ Text("Rename set list") }, { setMenu = null; renameSet = set.id }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                                        DropdownMenuItem({ Text("Delete set list") }, { setMenu = null; deleteSet = set.id }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                                    }
                                } }
                                AssistChip({ newSetWith = emptyList() }, { Text("New set list") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                            }
                            if (selectedSet != null) SetHeader(selectedSet, library,
                                onPlay = { selectedSet.scores.firstNotNullOfOrNull { id -> library.scores.find { it.id == id } }?.let { open(it, selectedSet.id) } },
                                onAdd = { addScoresTo = selectedSet.id }, onRename = { renameSet = selectedSet.id }, onDelete = { deleteSet = selectedSet.id })
                            else Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (trimmed.isNotEmpty()) "Search results" else if (favorites) "Favorites" else "Your scores", Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(FolioSpacing.dp8))
                                Surface(shape = FolioShapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.semanticsLabel("${shown.size} scores")) {
                                    Text("${shown.size}", Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4), style = MaterialTheme.typography.labelSmall)
                                }
                                Box {
                                    TextButton({ sortMenu = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.semanticsLabel("Sort: ${sort.label}")) {
                                        Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text(sort.label, maxLines = 1)
                                    }
                                    DropdownMenu(sortMenu, { sortMenu = false }, modifier = Modifier.guardUiTouches()) {
                                        MusicSort.entries.forEach { option ->
                                            DropdownMenuItem({ Text(option.label) }, { sort = option; sortMenu = false }, trailingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) })
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
                                    selectedSet != null -> "Build the running order"
                                    favorites -> "Keep your go-to pieces close"
                                    else -> "Your repertoire lives here."
                                }, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                                Text(when {
                                    trimmed.isNotEmpty() -> "Try a title, composer or instrument."
                                    selectedSet != null -> "Add scores in the order you'll play them."
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
                        SetScoreRow(index + 1, score, model, Modifier.animateItem(placementSpec = com.folio.notes.folioSpring()),
                            onOpen = { open(score, selectedSet.id) },
                            onUp = if (order && index > 0) ({ model.move(selectedSet.id, score.id, -1) }) else null,
                            onDown = if (order && index < shown.lastIndex) ({ model.move(selectedSet.id, score.id, 1) }) else null) {
                            ScoreMenu(score, state.busy, onDetails = { details = score.id }, onSets = { setsFor = score.id },
                                onExtract = { model.reviewExisting(score.id) }, onExport = { exportScore(score) }, onDelete = { deleteScore = score.id },
                                onRemoveFromSet = { model.set(selectedSet.id) { it.copy(scores = it.scores - score.id) } })
                        }
                    }
                    else -> items(shown, key = { it.id }) { score ->
                        val star = { model.score(score.id) { it.copy(starred = !it.starred) } }
                        val menu: @Composable () -> Unit = {
                            ScoreMenu(score, state.busy, onDetails = { details = score.id }, onSets = { setsFor = score.id },
                                onExtract = { model.reviewExisting(score.id) }, onExport = { exportScore(score) }, onDelete = { deleteScore = score.id })
                        }
                        val placement = if (wide) Modifier else Modifier.animateItem(placementSpec = com.folio.notes.folioSpring())
                        if (rows) ScoreRow(score, model, placement, { open(score, null) }, star, menu)
                        else ScoreCard(score, model, placement, { open(score, null) }, star, menu)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    state.reviews.firstOrNull()?.let { review -> key(review.source.id) { MusicImportPanel(review, model, state.busy, state.message) } }
    newSetWith?.let { scores ->
        NameDialog("New set list", "Name it after the concert, service or gig.", "", "Create set list", { newSetWith = null }) {
            model.newSet(it, scores); newSetWith = null
        }
    }
    library.sets.find { it.id == renameSet }?.let { set ->
        NameDialog("Rename set list", "Keep your concerts easy to find.", set.name, "Save", { renameSet = null }) { name ->
            model.set(set.id) { it.copy(name = name) }; renameSet = null
        }
    }
    library.sets.find { it.id == deleteSet }?.let { set ->
        ConfirmDelete("Delete “${set.name}”?", "The set list is removed. Its ${set.scores.size} ${if (set.scores.size == 1) "score stays" else "scores stay"} in Music.",
            "Delete set list", { deleteSet = null }) { model.deleteSet(set.id); if (setId == set.id) setId = null; deleteSet = null }
    }
    library.scores.find { it.id == deleteScore }?.let { score ->
        ConfirmDelete("Delete “${score.title}”?", "Removes Folio's copy of this score, its pencil marks, rehearsal marks and set list places. The original PDF you imported is not touched.",
            "Delete score", { deleteScore = null }) { model.delete(score.id); deleteScore = null }
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
@Composable private fun SetHeader(set: MusicSet, library: MusicLibrary, onPlay: () -> Unit, onAdd: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val scores = set.scores.mapNotNull { id -> library.scores.find { it.id == id } }
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
                    Text("${scores.size} ${if (scores.size == 1) "score" else "scores"} · ${scores.sumOf { it.pages }} pages",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Set list options") }
                    DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
                        DropdownMenuItem({ Text("Rename set list") }, { menu = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        DropdownMenuItem({ Text("Delete set list") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
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
            }
        }
    }
}

/** A score on the shelf: its first page as the cover, like a notebook's page cover. */
@Composable private fun ScoreCard(score: MusicScore, model: MusicViewModel, modifier: Modifier, open: () -> Unit, star: () -> Unit, menu: @Composable () -> Unit) {
    Column(modifier) {
        Box {
            ScoreFace(score, model, Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${score.title}", onClick = open))
            IconButton(star, modifier = Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp2), shapes = IconButtonDefaults.shapes()) {
                Icon(if (score.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (score.starred) "Remove from favorites" else "Add to favorites",
                    Modifier.size(21.dp).folioSelected(score.starred),
                    tint = if (score.starred) MaterialTheme.colorScheme.primary else Color(0xFF5F6368))
            }
        }
        Row(Modifier.padding(top = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = open)) {
                Text(score.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(scoreMeta(score), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            menu()
        }
    }
}

@Composable private fun ScoreRow(score: MusicScore, model: MusicViewModel, modifier: Modifier, open: () -> Unit, star: () -> Unit, menu: @Composable () -> Unit) {
    Surface(onClick = open, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Row(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            ScoreFace(score, model, Modifier.width(38.dp).height(50.dp), small = true)
            Column(Modifier.weight(1f)) {
                Text(score.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(scoreMeta(score), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(star, shapes = IconButtonDefaults.shapes()) {
                Icon(if (score.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (score.starred) "Remove from favorites" else "Add to favorites",
                    Modifier.folioSelected(score.starred))
            }
            menu()
        }
    }
}

/** A set list entry: its place in the running order, with the order controls beside it. */
@Composable private fun SetScoreRow(position: Int, score: MusicScore, model: MusicViewModel, modifier: Modifier,
    onOpen: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?, menu: @Composable () -> Unit) {
    Surface(onClick = onOpen, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4, top = FolioSpacing.dp8, bottom = FolioSpacing.dp8),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(30.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("$position", style = MaterialTheme.typography.labelLarge) }
            }
            ScoreFace(score, model, Modifier.width(38.dp).height(50.dp), small = true)
            Column(Modifier.weight(1f)) {
                Text(score.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(scoreMeta(score), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ onUp?.invoke() }, enabled = onUp != null, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ArrowUpward, "Play ${score.title} earlier") }
            IconButton({ onDown?.invoke() }, enabled = onDown != null, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ArrowDownward, "Play ${score.title} later") }
            menu()
        }
    }
}

@Composable private fun ScoreMenu(score: MusicScore, busy: Boolean, onDetails: () -> Unit, onSets: () -> Unit, onExtract: () -> Unit,
    onExport: () -> Unit, onDelete: () -> Unit, onRemoveFromSet: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for ${score.title}") }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
            DropdownMenuItem({ Text("Details & rehearsal notes") }, { menu = false; onDetails() }, leadingIcon = { Icon(Icons.Rounded.EditNote, null) })
            DropdownMenuItem({ Text("Add to set list") }, { menu = false; onSets() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) })
            if (onRemoveFromSet != null) DropdownMenuItem({ Text("Remove from this set list") }, { menu = false; onRemoveFromSet() },
                leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) })
            DropdownMenuItem({ Text("Extract instrument parts") }, { menu = false; onExtract() }, enabled = !busy, leadingIcon = { Icon(Icons.Rounded.ContentCut, null) })
            DropdownMenuItem({ Text("Export original PDF") }, { menu = false; onExport() }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) })
            DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
        }
    }
}

private fun scoreMeta(score: MusicScore) =
    (listOf(score.composer, score.part).filter { it.isNotBlank() } + "${score.pages} ${if (score.pages == 1) "page" else "pages"}").joinToString(" · ")

/**
 * The first page drawn small, on white like printed paper. Until it arrives (or if the PDF cannot
 * be drawn) a blank manuscript page holds the same box, so the shelf never jumps.
 */
@Composable internal fun ScoreFace(score: MusicScore, model: MusicViewModel, modifier: Modifier = Modifier, small: Boolean = false) {
    val width = with(LocalDensity.current) { (if (small) 48.dp else 220.dp).roundToPx() }
    val cover by produceState<Bitmap?>(null, score.id, width) {
        value = withContext(Dispatchers.IO) { runCatching { model.pageCache.cover(model.store.pdf(score.id), width) }.getOrNull() }
    }
    val image = remember(cover) { cover?.asImageBitmap() }
    val shape = if (small) FolioShapes.small else FolioShapes.medium
    Box(modifier.then(if (small) Modifier else Modifier.aspectRatio(.71f)).clip(shape).background(Color.White)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, "First page of ${score.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else ManuscriptPage(Modifier.fillMaxSize(), sparse = small)
    }
}

/** Blank staves: the placeholder for a score cover and the shelf's empty-state artwork. */
@Composable private fun ManuscriptPage(modifier: Modifier, sparse: Boolean = false) {
    val line = Color(0xFFB9BDC3)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (!sparse) drawRoundRect(line.copy(alpha = .55f), Offset(w * .3f, h * .07f), Size(w * .4f, h * .028f), CornerRadius(h * .014f))
        val systems = if (sparse) 4 else 6
        val gap = h * (if (sparse) .022f else .013f)
        val top = h * (if (sparse) .16f else .17f)
        val pitch = (h * .9f - top) / systems
        repeat(systems) { system ->
            val y = top + system * pitch
            repeat(5) { drawLine(line, Offset(w * .1f, y + it * gap), Offset(w * .9f, y + it * gap), strokeWidth = (h * .0022f).coerceAtLeast(1f)) }
        }
    }
}

/** Two sheets of manuscript, the front one stamped with a note: the empty shelf's cover. */
@Composable private fun ScoreStack(modifier: Modifier) {
    Box(modifier) {
        Box(Modifier.fillMaxSize(.86f).align(Alignment.TopStart).rotate(-6f).clip(FolioShapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer))
        Box(Modifier.fillMaxSize(.86f).align(Alignment.BottomEnd).rotate(3f).clip(FolioShapes.medium).background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FolioShapes.medium)) {
            ManuscriptPage(Modifier.fillMaxSize())
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).padding(FolioSpacing.dp8)) {
                Icon(Icons.Rounded.MusicNote, null, Modifier.padding(FolioSpacing.dp6).size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
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

@Composable private fun ConfirmDelete(title: String, body: String, action: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(),
        onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        dismissButton = { TextButton(dismiss, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
        confirmButton = { TextButton(confirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes()) { Text(action) } })
}

@Composable private fun ScoreDetailsPanel(score: MusicScore, dismiss: () -> Unit, save: (String, String, String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(score.title) }
    var composer by rememberSaveable { mutableStateOf(score.composer) }
    var part by rememberSaveable { mutableStateOf(score.part) }
    var notes by rememberSaveable { mutableStateOf(score.notes) }
    FolioPanel("Score details", dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Text("${score.pages} ${if (score.pages == 1) "page" else "pages"} · ${score.marks.size} rehearsal ${if (score.marks.size == 1) "mark" else "marks"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                OutlinedTextField(composer, { composer = it }, Modifier.weight(1f), label = { Text("Composer") }, singleLine = true)
                OutlinedTextField(part, { part = it }, Modifier.weight(1f), label = { Text("Instrument / part") }, singleLine = true)
            }
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Rehearsal notes") },
                placeholder = { Text("Bowings, cuts, who to watch for the cue…") }, minLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                TextButton(dismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button({ save(title.trim(), composer.trim(), part.trim(), notes.trim()) }, enabled = title.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Save") }
            }
        }
    }
}

/** Tick scores into a set list; new ones join at the end of the running order. */
@Composable private fun AddScoresPanel(set: MusicSet, scores: List<MusicScore>, model: MusicViewModel, dismiss: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = scores.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        .filter { s -> filter.isBlank() || listOf(s.title, s.composer, s.part).any { it.contains(filter.trim(), true) } }
    FolioPanel("Add to ${set.name}", dismiss) {
        Column(Modifier.padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text("${set.scores.size} in the set · new scores join at the end", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find a score…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = FolioShapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp12)) {
            shown.forEach { score ->
                val included = score.id in set.scores
                PickRow(included, score.title, scoreMeta(score), { ScoreFace(score, model, Modifier.width(30.dp).height(40.dp), small = true) }) {
                    model.set(set.id) { s -> s.copy(scores = if (included) s.scores - score.id else (s.scores + score.id).distinct()) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.End) {
            Button(dismiss, shapes = ButtonDefaults.shapes()) { Text("Done") }
        }
    }
}

/** From a score: which set lists it belongs to, or start a new one with it. */
@Composable private fun SetPickerPanel(score: MusicScore, sets: List<MusicSet>, model: MusicViewModel, onNewSet: () -> Unit, dismiss: () -> Unit) {
    FolioPanel("Add to set list", dismiss) {
        Text(score.title, Modifier.padding(horizontal = FolioSpacing.dp24), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(FolioSpacing.dp12)) {
            sets.forEach { set ->
                val included = score.id in set.scores
                PickRow(included, set.name, "${set.scores.size} ${if (set.scores.size == 1) "score" else "scores"}", {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { model.set(set.id) { s -> s.copy(scores = if (included) s.scores - score.id else (s.scores + score.id).distinct()) } }
            }
            Surface(onClick = onNewSet, shape = FolioShapes.large, color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Text("New set list with this score", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.End) {
            Button(dismiss, shapes = ButtonDefaults.shapes()) { Text("Done") }
        }
    }
}

@Composable private fun PickRow(checked: Boolean, title: String, subtitle: String, leading: @Composable () -> Unit, toggle: () -> Unit) {
    Surface(onClick = toggle, shape = FolioShapes.large, color = if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f) else Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp6),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Checkbox(checked, null)
        }
    }
}
