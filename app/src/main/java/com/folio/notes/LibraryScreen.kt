@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import com.folio.notes.progress.ExamRecordReview
import com.folio.notes.progress.countsAsExam

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

fun Modifier.semanticsLabel(label: String) = semantics { contentDescription = label }

/** Library-owned destinations alongside the other peer panes. Tablet files is a place inside the library. */
enum class LibrarySection { LIBRARY, FILES, PROGRESS }

@Composable fun LibraryScreen(
    state: FolioState, model: FolioViewModel,
    onNew: () -> Unit, onImport: () -> Unit, onImportArchive: () -> Unit,
    onSettings: () -> Unit, onMistakes: () -> Unit = {},
    showMistakes: Boolean = false, onLibrary: () -> Unit = {},
    showStudy: Boolean = false, onStudy: () -> Unit = {},
    showMusic: Boolean = false, onMusic: () -> Unit = {},
    studyContent: @Composable () -> Unit = {},
    musicContent: @Composable (onReaderMode: (Boolean) -> Unit) -> Unit = {},
    onOpenNotebook: (String) -> Unit = model::open,
    selectionCaption: String? = null, onCancelSelection: () -> Unit = {},
    mistakesContent: @Composable (onReviewMode: (Boolean) -> Unit) -> Unit = {},
    // Extra top space for panes with no top bar of their own (full screen has no status bar inset).
    onQuickNote: () -> Unit = onNew,
    onQuickCanvas: () -> Unit = onNew,
) {
    val libraryDrag = rememberNotebookDrag(state, model)
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val libraryPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    var examDetails by remember { mutableStateOf<Notebook?>(null) }
    var pendingMark by remember { mutableStateOf<Notebook?>(null) }
    // A mark just recorded for Focal, awaiting confirmation in the save review.
    var focalReview by remember { mutableStateOf<Pair<Notebook, ExamAttempt>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // Debounced query drives the O(N) filter so typing never blocks the text field.
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query == debouncedQuery) return@LaunchedEffect
        if (query.isNotEmpty()) kotlinx.coroutines.delay(150)
        debouncedQuery = query
    }
    // Where the shelf looks: a place at the top level, or the open folder (state.folderId), or a tag.
    var place by rememberSaveable { mutableStateOf(LibraryPlace.ALL) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedFolders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var sort by rememberSaveable { mutableStateOf(AppPrefs.librarySort(libraryPrefs.getString(AppPrefs.LIB_SORT, null))) }
    var kind by rememberSaveable { mutableStateOf(AppPrefs.libraryKind(libraryPrefs.getString(AppPrefs.LIB_KIND, null))) }
    var listView by rememberSaveable { mutableStateOf(libraryPrefs.getBoolean(AppPrefs.LIB_LIST, AppPrefs.DEFAULT_LIST_VIEW)) }
    // Persist library defaults so the shelf reopens the way it was left.
    LaunchedEffect(sort) { libraryPrefs.edit().putString(AppPrefs.LIB_SORT, sort.name).apply() }
    LaunchedEffect(kind) { libraryPrefs.edit().putString(AppPrefs.LIB_KIND, kind.name).apply() }
    LaunchedEffect(listView) { libraryPrefs.edit().putBoolean(AppPrefs.LIB_LIST, listView).apply() }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(LibrarySection.LIBRARY) }
    val libraryGridState = rememberLazyGridState()
    val shelfScope = rememberCoroutineScope()
    // Scrolling the shelf is the natural way to dismiss the search keyboard.
    LaunchedEffect(libraryGridState) {
        snapshotFlow { libraryGridState.isScrollInProgress }.collect { if (it) focusManager.clearFocus() }
    }
    var reviewMode by remember { mutableStateOf(false) }
    var musicReading by remember { mutableStateOf(false) }
    val destinationState = rememberSaveableStateHolder()
    // Mistakes review and the music reader take the whole window, like the editor does.
    val showNavigation = (showStudy || !showMistakes || !reviewMode) && !(showMusic && musicReading)
    // Study, Mistakes and Music are peer panes of the shelf; any of them hides the notebook shelf.
    val otherPane = showMistakes || showStudy || showMusic
    val pickingNotebook = selectionCaption != null
    // Picking a notebook for the workspace browses folders but never reorganizes them.
    val drag = if (pickingNotebook) null else libraryDrag
    var sortMenu by remember { mutableStateOf(false) }
    var newButtonMenu by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var bulkMove by remember { mutableStateOf(false) }
    var bulkDelete by remember { mutableStateOf(false) }
    var bulkTags by remember { mutableStateOf(false) }
    var notebookTags by remember { mutableStateOf<Set<String>?>(null) }
    var bulkCover by remember { mutableStateOf(false) }
    var coverFor by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<Notebook?>(null) }
    var move by remember { mutableStateOf<Notebook?>(null) }
    var delete by remember { mutableStateOf<Notebook?>(null) }
    var folderMenu by remember { mutableStateOf(false) }
    val shelfHold = rememberLongPressGuard()
    var newFolder by remember { mutableStateOf(false) }
    var newFolderParent by remember { mutableStateOf<String?>(null) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var moveFolder by remember { mutableStateOf<Folder?>(null) }
    var colourFolder by remember { mutableStateOf<Folder?>(null) }
    var deleteFolder by remember { mutableStateOf<Folder?>(null) }
    val examFilter = state.examFilter
    val openFolder = state.folders.find { it.id == state.folderId }
    val path = remember(state.folders, state.folderId) { LibraryFolders.path(state.folders, state.folderId) }
    // Filtering + sorting runs once per input change, not on every recomposition (selection
    // ticks, thumbnail arrivals), so scrolling and multi-select stay smooth on large libraries.
    val notes = remember(state.notes, state.folders, place, state.folderId, tag, debouncedQuery, kind, sort, examFilter) {
        LibraryBrowse.notebooks(state.notes, state.folders, place, state.folderId, tag, debouncedQuery, kind, sort)
            .filter { examFilter.matches(it) && (!examFilter.needsRedo || it.pages.any { page -> page.redoFlag }) }
    }
    val folders = remember(state.folders, place, state.folderId, tag, debouncedQuery) {
        LibraryBrowse.folders(state.folders, place, state.folderId, tag, debouncedQuery)
    }
    val counts = remember(state.notes, state.folders) { LibraryCounts(state.notes, state.folders) }
    val allTags = remember(state.notes) { NotebookTags.normalize(state.notes.flatMap { it.tags }, Int.MAX_VALUE) }
    // A tag whose last notebook lost it has nothing left to show.
    LaunchedEffect(allTags) { if (tag != null && allTags.none { it.equals(tag, true) }) tag = null }
    // Opening a folder unfolds its branch in the sidebar tree; collapsing it afterwards sticks.
    LaunchedEffect(state.folderId) {
        val branch = LibraryFolders.path(state.folders, state.folderId).map { it.id }
        if (branch.any { it !in expandedFolders }) expandedFolders = (expandedFolders + branch).distinct()
    }
    val visibleIds = remember(notes) { notes.map { it.id }.toSet() }
    val selection = remember(selectedIds, visibleIds) { selectedIds.filter { it in visibleIds }.toSet() }
    LaunchedEffect(visibleIds, libraryDrag.active) { if (!libraryDrag.active) selectedIds = selectedIds.filter { it in visibleIds } }
    fun clearQuery() { query = ""; debouncedQuery = "" }
    fun showPlace(target: LibraryPlace) { section = LibrarySection.LIBRARY; place = target; tag = null; model.folder(null) }
    fun showTag(label: String) { tag = if (section == LibrarySection.LIBRARY && tag.equals(label, true)) null else label; section = LibrarySection.LIBRARY; model.folder(null) }
    fun browse(id: String?) { section = LibrarySection.LIBRARY; tag = null; clearQuery(); model.folder(id); focusManager.clearFocus() }
    fun toggleExpanded(id: String) { expandedFolders = if (id in expandedFolders) expandedFolders - id else expandedFolders + id }
    // Hovering a drag over a folder opens it; the top level opens spatially, as Unfiled plus folders.
    fun browseForDrag(id: String?) {
        if (id == null) place = LibraryPlace.UNFILED else if (id !in expandedFolders) expandedFolders = expandedFolders + id
        tag = null; clearQuery(); model.setExamFilter(ExamFilter()); model.folder(id)
    }
    fun finishDrag() { selecting = false; selectedIds = emptyList() }
    fun startNewFolder(parent: String?) { newFolderParent = parent; newFolder = true }
    fun toggleSelection(id: String) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    /** Holding a notebook drops straight into selection with that notebook ticked. */
    fun enterSelecting(id: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        selecting = true
        if (id !in selectedIds) selectedIds = selectedIds + id
    }
    val filtersActive = kind != LibraryKind.ALL || examFilter.isActive
    val searchActive = query.isNotBlank()
    val home = state.folderId == null && tag == null
    val located = !home || place != LibraryPlace.ALL
    val scoped = searchActive || filtersActive || located
    fun goHome() { place = LibraryPlace.ALL; tag = null; model.folder(null) }
    fun clearScope() {
        clearQuery()
        kind = LibraryKind.ALL
        goHome()
        model.setExamFilter(ExamFilter())
        focusManager.clearFocus()
    }
    BackHandler(enabled = !otherPane && !pickingNotebook && section == LibrarySection.LIBRARY && (libraryDrag.active || selecting || searchActive || filtersExpanded || located)) {
        when {
            libraryDrag.active -> libraryDrag.cancel()
            selecting -> { selecting = false; selectedIds = emptyList() }
            searchActive -> { clearQuery(); focusManager.clearFocus() }
            filtersExpanded -> filtersExpanded = false
            tag != null -> tag = null
            state.folderId != null -> model.folder(path.lastOrNull()?.parentId)
            else -> place = LibraryPlace.ALL
        }
    }
    val shelfInputs = listOf(state.folderId, place, tag, debouncedQuery, kind, sort, examFilter)
    var previousShelfInputs by remember { mutableStateOf(shelfInputs) }
    LaunchedEffect(shelfInputs) {
        // Preserve the restored scroll position when returning from a notebook.
        if (shelfInputs != previousShelfInputs) {
            previousShelfInputs = shelfInputs
            libraryGridState.scrollToItem(0)
        }
    }
    // The grid keeps its scroll by item key, so a notebook that sorts above the first visible one
    // (just created, or just edited) would sit off-screen above the viewport until something else
    // scrolled the shelf. Bring a new front-runner of "Last edited" into view.
    val newestId = notes.firstOrNull()?.id
    var previousNewestId by rememberSaveable { mutableStateOf(newestId) }
    LaunchedEffect(newestId) {
        if (newestId != previousNewestId) {
            previousNewestId = newestId
            if (sort == LibrarySort.RECENT && newestId != null) libraryGridState.scrollToItem(0)
        }
    }
    fun libraryHome() {
        if (!otherPane && section == LibrarySection.LIBRARY && !located) shelfScope.launch { libraryGridState.animateScrollToItem(0) }
        section = LibrarySection.LIBRARY; goHome(); onLibrary()
    }
    @Composable fun CreateMenuItems(close: () -> Unit) {
        PopoverRow(Icons.Rounded.Add, "New notebook") { close(); onNew() }
        PopoverRow(Icons.Rounded.EditNote, "Quick note") { close(); onQuickNote() }
        PopoverRow(Icons.Rounded.AllInclusive, "Infinite canvas") { close(); onQuickCanvas() }
        PopoverRow(Icons.Rounded.CreateNewFolder, if (openFolder != null) "New folder in ${openFolder.name}" else "New folder") { close(); startNewFolder(openFolder?.id) }
        HorizontalDivider(Modifier.padding(vertical = FolioSpacing.dp8))
        PopoverRow(Icons.Rounded.PictureAsPdf, "Import PDF document") { close(); onImport() }
        PopoverRow(Icons.Rounded.FolderZip, "Import Folio backup") { close(); onImportArchive() }
    }
    // Never put the library's palm guard above the native handwriting surface.
    BoxWithConstraints(Modifier.fillMaxSize().notebookDragHost(libraryDrag).then(if (!showMistakes && !showMusic) Modifier.guardUiTouches() else Modifier)) {
        val wide = maxWidth >= 840.dp
        val coverWidth = if (maxWidth >= 600.dp) 176.dp else 144.dp
        // A portrait tablet should not lose a third of its shelf to two navigation columns.
        val showSidebar = maxWidth >= 1200.dp
        // Sidebar/bottom-bar destinations are peer panes of one layout, so swapping them is a
        // repaint rather than a navigation: no entrance animation on any layout.
        val entrance = Modifier
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide && showNavigation) NavigationRail(
                    // Material 3.5's rail only enforces a *minimum* width (80dp), so a full-width
                    // child — the divider between the destinations and Import/Settings — stretches
                    // the rail over the whole screen and squeezes the shelf to nothing. Pin it to
                    // the rail's own width so the grid always keeps the rest of the screen.
                    modifier = Modifier.width(80.dp).guardUiTouches(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    header = {
                        Spacer(Modifier.height(LocalDestinationTopGap.current))
                        Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.primary) {
                            Icon(Icons.AutoMirrored.Rounded.MenuBook, "folio", Modifier.padding(FolioSpacing.dp10).size(23.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.height(FolioSpacing.dp12))
                        Box {
                            FloatingActionButton(onClick = { newButtonMenu = true }) {
                                Icon(Icons.Rounded.Add, "Create or import")
                            }
                            if (newButtonMenu) FolioActionPopover("Create or import", { newButtonMenu = false }) {
                                CreateMenuItems { newButtonMenu = false }
                            }
                        }
                    }
                ) {
                    RailItem("Library", Icons.Rounded.GridView, !otherPane && (pickingNotebook || section != LibrarySection.PROGRESS)) { libraryHome() }
                    if (!pickingNotebook) {
                        RailItem("Mistakes", Icons.Rounded.School, showMistakes) { onMistakes() }
                        RailItem("Study", Icons.Rounded.Timer, showStudy) { onStudy() }
                        RailItem("Progress", Icons.Rounded.Insights, !otherPane && section == LibrarySection.PROGRESS) { section = LibrarySection.PROGRESS; onLibrary() }
                        RailItem("Music", Icons.Rounded.MusicNote, showMusic) { onMusic() }
                    }
                    Spacer(Modifier.height(FolioSpacing.dp4))
                    HorizontalDivider(Modifier.padding(horizontal = FolioSpacing.dp8))
                    Spacer(Modifier.weight(1f))
                    RailItem("Import PDF", Icons.Rounded.PictureAsPdf, false, onImport)
                    RailItem("Settings", Icons.Rounded.Tune, false) { onSettings() }
                }
                if (showStudy) Box(Modifier.weight(1f).fillMaxHeight().then(entrance)) {
                    destinationState.SaveableStateProvider("study") { studyContent() }
                }
                if (showMusic && !showStudy && !showMistakes) Box(Modifier.weight(1f).fillMaxHeight().then(entrance)) {
                    destinationState.SaveableStateProvider("music") {
                        musicContent { musicReading = it }
                    }
                }
                if (showMistakes && !showStudy) Box(Modifier.weight(1f).fillMaxHeight().then(entrance)) {
                    destinationState.SaveableStateProvider("mistakes") {
                        mistakesContent { reviewMode = it }
                    }
                }
                if (!otherPane && (pickingNotebook || section != LibrarySection.PROGRESS)) Column(Modifier.weight(1f).fillMaxHeight().then(entrance)) {
                    FolioScreenHeading("Library") {
                        if (!wide) Box {
                            // M3e split button: tap creates a notebook, the trailing half opens
                            // the related folder and import actions.
                            SplitButtonLayout(
                                leadingButton = { SplitButtonDefaults.LeadingButton(onNew) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("New") } },
                                trailingButton = { SplitButtonDefaults.TrailingButton({ newButtonMenu = true }) { Icon(Icons.Rounded.ArrowDropDown, "More ways to create") } },
                            )
                            if (newButtonMenu) FolioActionPopover("Create or import", { newButtonMenu = false }) {
                                CreateMenuItems { newButtonMenu = false }
                            }
                        }
                        if (!showSidebar && !pickingNotebook) IconButton({ selecting = false; selectedIds = emptyList(); section = LibrarySection.FILES }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.TabletAndroid, "Tablet files")
                        }
                        if (!wide) IconButton(onSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Settings") }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (showSidebar) LibrarySidebar(state.folders, state.folderId, place, tag, allTags, expandedFolders.toSet(), counts, drag,
                            onPlace = { showPlace(it) }, onFolder = { browse(it) }, onExpand = { toggleExpanded(it) }, onTag = { showTag(it) },
                            onHoverOpen = { browseForDrag(it) }, onNewFolder = { startNewFolder(null) },
                            onFiles = if (pickingNotebook) null else ({ selecting = false; selectedIds = emptyList(); section = LibrarySection.FILES }),
                            onFinishDrag = ::finishDrag, filesActive = section == LibrarySection.FILES)
                        if (!pickingNotebook && section == LibrarySection.FILES) Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(Modifier.padding(horizontal = FolioDestinationInset), verticalAlignment = Alignment.CenterVertically) {
                                if (!showSidebar) IconButton({ section = LibrarySection.LIBRARY }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to notebooks")
                                }
                                Text("Tablet files", style = MaterialTheme.typography.titleLarge)
                            }
                            Box(Modifier.weight(1f)) {
                                destinationState.SaveableStateProvider("files") {
                                    DeviceExplorer(state, model, onImport, onImportArchive) { section = LibrarySection.LIBRARY }
                                }
                            }
                        } else {
                    // Dropping on the shelf's own background files into whatever it is showing.
                    val backgroundDrop = when {
                        searchActive -> null
                        tag != null -> NotebookDropDestination.Tag(tag!!)
                        openFolder != null -> NotebookDropDestination.Folder(openFolder.id, openFolder.name)
                        state.folderId == null -> place.drop()
                        else -> null
                    }
                LazyVerticalGrid(columns = if (listView) GridCells.Fixed(1) else GridCells.Adaptive(coverWidth), modifier = Modifier.weight(1f).fillMaxHeight().then(if (drag != null) Modifier.notebookDragScroll(drag) { libraryGridState.scrollBy(it) } else Modifier)
                        .notebookDropTarget(if (backgroundDrop != null) drag else null, backgroundDrop ?: NotebookDropDestination.Favorites),
                    state = libraryGridState,
                    contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, bottom = FolioDestinationInset), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(if (listView) 8.dp else 16.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            if (selectionCaption != null) Surface(
                                shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically) {
                                    Text(selectionCaption, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    TextButton(onCancelSelection, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                                }
                            }
                            // Folded sidebars put places and tags in one touch-sized chip row,
                            // and folders appear as tiles in the shelf itself.
                            if (!showSidebar) LibraryPlacesRow(place, home, tag, allTags, counts, drag,
                                onPlace = { showPlace(it) }, onTag = { showTag(it) }, onHoverOpen = { browseForDrag(it) })
                            if (path.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    ExplorerBreadcrumbs(listOf("Library" to { browse(null) }) + path.map { folder -> folder.name to { browse(folder.id) } },
                                        drag, listOf(null) + path.map { it.id }, ::browseForDrag)
                                }
                                if (openFolder != null && !pickingNotebook) Box {
                                    IconButton({ folderMenu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for ${openFolder.name}") }
                                    if (folderMenu) FolioActionPopover(openFolder.name, { folderMenu = false }) {
                                        FolderMenuItems({ folderMenu = false }, { startNewFolder(openFolder.id) }, { renameFolder = openFolder }, { colourFolder = openFolder }, { moveFolder = openFolder }, { deleteFolder = openFolder })
                                    }
                                }
                            }
                            if (!pickingNotebook && !selecting && !scoped) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    FilledTonalButton(onQuickNote, enabled = !state.loading, shapes = ButtonDefaults.shapes()) {
                                        Icon(Icons.Rounded.EditNote, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Quick note")
                                    }
                                    OutlinedButton(onQuickCanvas, enabled = !state.loading, shapes = ButtonDefaults.shapes()) {
                                        Icon(Icons.Rounded.AllInclusive, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Canvas")
                                    }
                                }
                                state.notes.maxByOrNull { it.updated }?.let { recent ->
                                    Surface(onClick = { onOpenNotebook(recent.id) }, shape = FolioShapes.large,
                                        color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                            NotebookListThumbnail(recent, model.thumbnails, Modifier.width(32.dp).height(42.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text("Continue writing · ${libraryLastEditedLabel(recent.updated)}", style = MaterialTheme.typography.labelMedium)
                                                Text(recent.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open most recently edited notebook")
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text(when { openFolder != null -> "Search ${openFolder.name} and its subfolders…"; tag != null -> "Search notebooks tagged $tag…"; place == LibraryPlace.FAVORITES -> "Search favorites…"; else -> "Find notebooks, folders, page names or tags…" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ clearQuery() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } }, singleLine = true, shape = FolioShapes.extraLarge, colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { debouncedQuery = query; focusManager.clearFocus() }))
                            FolioSectionHeading(title = when {
                                query.isNotEmpty() -> "Search results"
                                examFilter.incomplete -> "Incomplete notebooks"
                                tag != null -> "Tagged · $tag"
                                openFolder != null -> openFolder.name
                                place == LibraryPlace.ALL -> "Your notebooks"
                                else -> place.label
                            }) {
                                Surface(shape = FolioShapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.semanticsLabel("${notes.size} notebooks")) { Text("${notes.size}", Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4), style = MaterialTheme.typography.labelSmall) }
                                Box {
                                    TextButton({ sortMenu = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.semanticsLabel("Sort: ${sort.label}")) { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text(sort.label, maxLines = 1) }
                                    if (sortMenu) FolioActionPopover("Sort notebooks", { sortMenu = false }) {
                                        LibrarySort.entries.forEach { option ->
                                            PopoverRow(if (sort == option) Icons.Rounded.Check else null, option.label) { sort = option; sortMenu = false }
                                        }
                                    }
                                }
                                if (!pickingNotebook && !selecting && !scoped) IconButton({ startNewFolder(null) }, enabled = !state.loading, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.CreateNewFolder, "New folder")
                                }
                                IconButton({ listView = !listView }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(if (listView) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                                        if (listView) "Switch to covers" else "Switch to list")
                                }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                LibraryKind.entries.forEach { option ->
                                    FilterChip(kind == option, { kind = option }, { Text(option.label) }, modifier = Modifier.heightIn(min = FolioTouch.target))
                                }
                                FilterChip(filtersExpanded || examFilter.isActive, shelfHold.click { filtersExpanded = !filtersExpanded }, { Text(if (examFilter.isActive) "Filters • Active" else "Filters") },
                                    modifier = Modifier.heightIn(min = FolioTouch.target).longPressAction(shelfHold) { kind = LibraryKind.ALL; model.setExamFilter(ExamFilter()) },
                                    leadingIcon = { Icon(Icons.Rounded.FilterList, null, Modifier.size(18.dp)) },
                                    trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp).folioDisclosure(filtersExpanded)) })
                                state.daysToExam?.let { days ->
                                    Surface(shape = FolioShapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                                        Row(Modifier.padding(horizontal = FolioSpacing.dp10, vertical = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                            Icon(Icons.Rounded.Event, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                                            Text(if (days == 0) "Exam today" else "Exam in $days day${if (days == 1) "" else "s"}",
                                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                    }
                                }
                                if (!pickingNotebook) TextButton(shelfHold.click { selecting = !selecting; selectedIds = emptyList() },
                                    modifier = Modifier.longPressAction(shelfHold) { selecting = true; selectedIds = notes.map { it.id } },
                                    enabled = selecting || notes.isNotEmpty(),
                                    shapes = ButtonDefaults.shapes()) { Icon(if (selecting) Icons.Rounded.Check else Icons.Rounded.CheckCircleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text(if (selecting) "Done" else "Select") }
                                if (scoped) TextButton({ clearScope() }, shapes = ButtonDefaults.shapes()) { Text("Show all notebooks") }
                            }
                            FolioExpand(filtersActive) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Filtered results · ${notes.size} notebooks", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton({ kind = LibraryKind.ALL; model.setExamFilter(ExamFilter()) }, shapes = ButtonDefaults.shapes()) { Text("Reset filters") }
                                }
                            }
                            FolioExpand(filtersExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                    FilterChip(examFilter.incomplete, {
                                        model.setExamFilter(if (examFilter.incomplete) examFilter.copy(incomplete = false) else examFilter.copy(incomplete = true))
                                    }, { Text("Incomplete") })
                                }
                                // Exam filters: one chip per subject that is actually in use, then year,
                                // company and status, so the shelf narrows to "Methods · 2022 · VCAA".
                                val examNotes = remember(state.notes) { state.notes.filter { it.exam.isTagged } }
                                val subjects = remember(examNotes) { examNotes.mapNotNull { it.exam.subject }.toSet() }
                                if (examNotes.isNotEmpty()) {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                        VceSubject.entries.forEach { subject ->
                                            if (subject in subjects) {
                                                SubjectChip(subject, examFilter.subject == subject, {
                                                    model.setExamFilter(if (examFilter.subject == subject) examFilter.copy(subject = null) else examFilter.copy(subject = subject))
                                                })
                                            }
                                        }
                                    }
                                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                        remember(examNotes) { examNotes.mapNotNull { it.exam.year }.distinct().sortedDescending().take(6) }.forEach { year ->
                                            FilterChip(examFilter.year == year, {
                                                model.setExamFilter(if (examFilter.year == year) examFilter.copy(year = null) else examFilter.copy(year = year))
                                            }, { Text("$year") })
                                        }
                                        remember(examNotes) { examNotes.map { it.exam.company }.filter { it.isNotBlank() }.distinct().take(6) }.forEach { company ->
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
                            }
                            FolioExpand(state.saveFailed) {
                                FilledTonalButton(model::retrySave, shapes = ButtonDefaults.shapes(),
                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Changes need saving · Retry save")
                                }
                            }
                        }
                    }
                    // Folder tiles take over whenever the sidebar is folded away.
                    if (!showSidebar && folders.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "folders-label") { LibraryGroupLabel("Folders · ${folders.size}") }
                        items(folders, key = { "folder:${it.id}" }, span = { GridItemSpan(if (listView || maxLineSpan < 4) maxLineSpan else 2) }) { folder ->
                            FolderTile(folder.name, tint = FolderPalette.color(folder.color), detail =
                                if (searchActive) LibraryFolders.label(state.folders, folder.parentId).ifEmpty { "Library" } else counts.folderDetail(folder),
                                onClick = { browse(folder.id) },
                                modifier = Modifier.notebookDragSource(drag, ::finishDrag) { NotebookDragPayload.Folder(folder.id, folder.name) }
                                    .graphicsLayer { alpha = if ((drag?.payload as? NotebookDragPayload.Folder)?.id == folder.id) .45f else 1f }
                                    .notebookDropTarget(drag, NotebookDropDestination.Folder(folder.id, folder.name), onHoverOpen = { browseForDrag(folder.id) })
                                    .then(if (wide) Modifier else Modifier.animateItem(placementSpec = folioSpring())),
                                menu = if (pickingNotebook) null else { close ->
                                    FolderMenuItems(close, { startNewFolder(folder.id) }, { renameFolder = folder }, { colourFolder = folder }, { moveFolder = folder }, { deleteFolder = folder })
                                })
                        }
                        if (notes.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "notes-label") { LibraryGroupLabel("Notebooks · ${notes.size}") }
                    }
                    if (notes.isEmpty() && (showSidebar || folders.isEmpty())) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Surface(shape = FolioShapes.panel, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp32), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                                Box(Modifier.size(124.dp, 140.dp)) { NotebookCover(Notebook(title = "Your next idea", cover = 1), Modifier.fillMaxSize()) }
                                Text(when {
                                    searchActive -> "No notebooks match “${query.trim()}”"
                                    filtersActive -> "No notebooks found"
                                    tag != null -> "Nothing tagged $tag yet"
                                    openFolder != null -> "This folder is ready"
                                    place == LibraryPlace.FAVORITES -> "Keep the good ones close"
                                    place == LibraryPlace.UNFILED -> "Everything has a home"
                                    else -> "Good things start here."
                                }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                Text(when {
                                    searchActive || filtersActive -> if (openFolder != null) "Search covers this folder and its subfolders. Try another search or show all notebooks." else "Try another search or show all notebooks."
                                    tag != null -> "Drag notebooks onto the tag to label them."
                                    openFolder != null -> "Create a notebook or folder here, or drag notebooks in from the library."
                                    place == LibraryPlace.FAVORITES -> "Tap the star on a notebook, or drag it onto Favorites."
                                    place == LibraryPlace.UNFILED -> "Every notebook is in a folder."
                                    else -> "Make space for your first idea. Create a notebook\nor bring a PDF along."
                                }, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (scoped) TextButton({ clearScope() }, shapes = ButtonDefaults.shapes()) { Text("Show all notebooks") }
                                if (!searchActive && !filtersActive && tag == null && place != LibraryPlace.FAVORITES) Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    TextButton(onNew, shapes = ButtonDefaults.shapes()) { Text(if (openFolder != null) "New notebook here" else "Start a notebook"); Spacer(Modifier.width(FolioSpacing.dp8)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
                                    if (openFolder != null && !pickingNotebook) TextButton({ startNewFolder(openFolder.id) }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("New folder") }
                                }
                                if (!searchActive && !filtersActive && home && place == LibraryPlace.ALL && state.notes.isEmpty()) TextButton(onImport, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Import a PDF") }
                            }
                        }
                    }
                    // Inside a folder its name is already on screen; elsewhere each notebook says where it lives.
                    val showFolder = searchActive || (state.folderId == null && place != LibraryPlace.UNFILED)
                    items(notes, key = { it.id }) { note ->
                        val open = { if (pickingNotebook || !selecting) onOpenNotebook(note.id) else toggleSelection(note.id) }
                        val longPress = { if (!pickingNotebook) enterSelecting(note.id) }
                        val dragModifier = Modifier.notebookDragSource(drag, ::finishDrag) {
                            val ids = NotebookDropRules.selection(note.id, selection, notes)
                            NotebookDragPayload.Notes(ids, if (ids.size == 1) note.title else "${ids.size} notebooks")
                        }.graphicsLayer { alpha = if ((drag?.payload as? NotebookDragPayload.Notes)?.ids?.contains(note.id) == true) .45f else 1f }
                        val folder = if (showFolder) LibraryFolders.label(state.folders, note.folderId).ifEmpty { "Unfiled" } else null
                        val selectionColor by animateColorAsState(
                            if (note.id in selection) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            animationSpec = folioSpring(), label = "notebookSelection",
                        )
                        if (listView) FolioSwipeRow(
                            dragModifier.then(if (wide) Modifier else Modifier.animateItem(placementSpec = folioSpring())).semantics { if (selecting) this.selected = note.id in selection },
                            shape = FolioShapes.large,
                            startToEnd = if (selecting || pickingNotebook) null else SwipeAction(if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (note.starred) "Remove from favorites" else "Add to favorites") { model.star(note) },
                            endToStart = if (selecting || pickingNotebook) null else SwipeAction(Icons.Rounded.DeleteOutline, "Delete notebook", destructive = true) { delete = note },
                        ) { Surface(
                            shape = FolioShapes.large,
                            color = selectionColor,
                            modifier = Modifier.fillMaxWidth().combinedClickable(onClickLabel = if (selecting) "Toggle selection for ${note.title}" else "Open ${note.title}", onClick = open, onLongClick = longPress)
                        ) {
                                Row(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                    if (selecting) {
                                        Checkbox(note.id in selection, { toggleSelection(note.id) }, Modifier.semanticsLabel(if (note.id in selection) "Deselect ${note.title}" else "Select ${note.title}"))
                                    }
                                    NotebookListThumbnail(note, model.thumbnails, Modifier.width(38.dp).height(50.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                        Text(listOfNotNull(folder, "${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"}", libraryLastEditedLabel(note.updated)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (note.id in state.backupExcludedNotebookIds) Text("Excluded from library backups", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (!selecting) {
                                        IconButton({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.star(note) }, shapes = IconButtonDefaults.shapes()) { Icon(if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (note.starred) "Remove from favorites" else "Add to favorites", Modifier.folioSelected(note.starred)) }
                                        NotebookMenu({ rename = note }, { move = note }, { delete = note }, { examDetails = note }, { pendingMark = note }, note.pageCover, { model.setPageCover(note, !note.pageCover) }, { model.duplicateNotebook(note) }, note.id in state.backupExcludedNotebookIds, { model.setBackupExcluded(setOf(note.id), note.id !in state.backupExcludedNotebookIds) }, { coverFor = note.id }, onTags = { notebookTags = setOf(note.id) }, title = note.title)
                                    }
                                }
                            } } else NotebookCard(
                                note, model.thumbnails, folder, open, { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.star(note) },
                                { rename = note }, { move = note }, { delete = note }, { examDetails = note }, { pendingMark = note },
                                selecting, note.pages.count { it.redoFlag },
                                selected = note.id in selection, onLongPress = longPress,
                                pageCover = note.pageCover, onCoverToggle = { model.setPageCover(note, !note.pageCover) },
                                onChangeCover = { coverFor = note.id },
                                onTags = { notebookTags = setOf(note.id) },
                                duplicate = { model.duplicateNotebook(note) },
                                backupExcluded = note.id in state.backupExcludedNotebookIds,
                                onBackupToggle = { model.setBackupExcluded(setOf(note.id), note.id !in state.backupExcludedNotebookIds) },
                                modifier = dragModifier.then(if (wide) Modifier else Modifier.animateItem(placementSpec = folioSpring())).semantics { if (selecting) this.selected = note.id in selection }
                            )
                    }

                }
                    }
                }
                }
                if (!otherPane && !pickingNotebook && section == LibrarySection.PROGRESS) {
                    com.folio.notes.progress.ProgressScreen(state.notes, model, Modifier.weight(1f).fillMaxHeight().then(entrance),
                        onSettings, onMistakes, onStudy, onOpenNotebook)
                }
        }
        if (selecting && !libraryDrag.active && !otherPane && !pickingNotebook && section == LibrarySection.LIBRARY) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8)) {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            Text("${selection.size} of ${notes.size} selected", style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                            Spacer(Modifier.weight(1f))
                            TextButton({ selectedIds = if (selection.size == notes.size) emptyList() else notes.map { it.id } }, enabled = notes.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text(if (selection.size == notes.size && notes.isNotEmpty()) "Deselect all" else "Select all") }
                            TextButton({ selecting = false; selectedIds = emptyList() }, shapes = ButtonDefaults.shapes()) { Text("Done") }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            TextButton({ bulkMove = true }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("Move") }
                            TextButton({ notebookTags = selection }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Sell, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("Tags") }
                            TextButton({ bulkTags = true }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.FactCheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("Exam details") }
                            TextButton({ bulkCover = true }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Palette, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("Cover") }
                            val allExcluded = selection.isNotEmpty() && selection.all { it in state.backupExcludedNotebookIds }
                            TextButton({ model.setBackupExcluded(selection, !allExcluded) }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                                Text(if (allExcluded) "Include in backups" else "Exclude from backups")
                            }
                            val allStarred = selection.isNotEmpty() && notes.all { it.id !in selection || it.starred }
                            TextButton({ model.favoriteNotebooks(selection, !allStarred) }, enabled = selection.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Icon(if (allStarred) Icons.Rounded.StarOutline else Icons.Rounded.Star, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text(if (allStarred) "Unfavorite" else "Favorite") }
                            TextButton(
                                { bulkDelete = true },
                                enabled = selection.isNotEmpty(),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("Delete") }
                        }
                    }
                }
            }
        }
        // M3e short navigation bar: three to five destinations, equally weighted on a phone.
        // The wide layout keeps its navigation rail instead.
        if (!wide && showNavigation && !pickingNotebook && !selecting) ShortNavigationBar(modifier = Modifier.guardUiTouches()) {
            ShortNavigationBarItem(!otherPane && section != LibrarySection.PROGRESS, { libraryHome() },
                icon = { Icon(Icons.Rounded.GridView, null) }, label = { Text("Library") })
            ShortNavigationBarItem(showMistakes, onMistakes, icon = { Icon(Icons.Rounded.School, null) }, label = { Text("Mistakes") })
            ShortNavigationBarItem(showStudy, onStudy, icon = { Icon(Icons.Rounded.Timer, null) }, label = { Text("Study") })
            ShortNavigationBarItem(!otherPane && section == LibrarySection.PROGRESS, { section = LibrarySection.PROGRESS; onLibrary() },
                icon = { Icon(Icons.Rounded.Insights, null) }, label = { Text("Progress") })
            ShortNavigationBarItem(showMusic, onMusic, icon = { Icon(Icons.Rounded.MusicNote, null) }, label = { Text("Music") })
        }
        }
        NotebookDragFeedback(libraryDrag, Modifier.align(Alignment.BottomCenter).padding(horizontal = FolioSpacing.dp16, vertical = if (wide) 16.dp else 88.dp))
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
            suggestedSeconds = state.lastTimedSeconds,
            onRecordAndSave = if (!note.countsAsExam) null else { attempt -> model.recordAttempt(note.id, attempt); focalReview = live to attempt }
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
            },
            onRecordAndSave = if (!live.countsAsExam) null else { score, total, seconds, timed ->
                val attempt = ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed)
                model.recordAttempt(live.id, attempt)
                pendingMark = null
                focalReview = live to attempt
            }
        )
    }
    focalReview?.let { (reviewed, attempt) ->
        ExamRecordReview(reviewed, attempt) { focalReview = null }
    }
    notebookTags?.let { ids -> NotebookTagsPanel(state.notes.filter { it.id in ids }, NotebookTags.normalize(state.notes.flatMap { it.tags }, Int.MAX_VALUE), { notebookTags = null }) { add, remove -> model.updateNotebookTags(ids, add, remove); notebookTags = null } }
    if (bulkMove) LibraryMovePanel("Move ${selection.size} notebooks", state.folders,
        onMove = { model.moveNotebooks(selection, it); bulkMove = false; selectedIds = emptyList() },
        onCreateAndMove = { name -> model.createFolderAndMove(selection, name).also { if (it) selectedIds = emptyList() } },
        onDismiss = { bulkMove = false })
    if (bulkDelete) AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = { bulkDelete = false },
        title = { Text("Delete ${selection.size} notebook${if (selection.size == 1) "" else "s"}?") },
        text = { Text("This removes the selected notebooks and their ${state.notes.filter { it.id in selection }.sumOf { it.pages.size }} pages from this device. Export a copy first if you want to keep them.") },
        dismissButton = { TextButton({ bulkDelete = false }, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
        confirmButton = {
            TextButton(
                { model.deleteNotebooks(selection); bulkDelete = false; selectedIds = emptyList() },
                enabled = selection.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes()) { Text(if (selection.size > 1) "Delete ${selection.size}" else "Delete") }
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
    state.notes.find { it.id == coverFor }?.let { note ->
        AlertDialog(
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            modifier = Modifier.guardUiTouches(),
            onDismissRequest = { coverFor = null },
            title = { Text("Notebook cover") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    CoverPicker(note.cover, { model.setCover(note, it) }, note.title, document = note.pages.any { it.pdfIndex != null })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("First page as cover", style = MaterialTheme.typography.titleSmall)
                            Text(if (note.pageCover) "The shelf shows the first page itself" else "The shelf shows this cover",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(note.pageCover, { model.setPageCover(note, it) })
                    }
                }
            },
            confirmButton = { TextButton({ coverFor = null }, shapes = ButtonDefaults.shapes()) { Text("Done") } }
        )
    }
    if (bulkCover) AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = { bulkCover = false },
        title = { Text("Cover for ${selection.size} notebook${if (selection.size == 1) "" else "s"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Text("Show the first page itself, or keep the decorative default cover.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(
                    { model.setPageCoverBatch(selection, true); bulkCover = false; selectedIds = emptyList() },
                    enabled = selection.isNotEmpty(),
                    shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(FolioSpacing.dp12)); Text("First page as cover") }
                TextButton(
                    { model.setPageCoverBatch(selection, false); bulkCover = false; selectedIds = emptyList() },
                    enabled = selection.isNotEmpty(),
                    shapes = ButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.MenuBook, null); Spacer(Modifier.width(FolioSpacing.dp12)); Text("Default cover") }
            }
        },
        confirmButton = { TextButton({ bulkCover = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
    if (newFolder) NameDialog("New folder", "Inside ${state.folders.find { it.id == newFolderParent }?.name ?: "Library"}", "", "Create folder", { newFolder = false }) { name ->
        if (model.createFolder(name, newFolderParent)) {
            newFolder = false
            newFolderParent?.let { if (it !in expandedFolders) expandedFolders = expandedFolders + it }
        }
    }
    moveFolder?.let { folder -> LibraryMovePanel("Move ${folder.name}", state.folders, folder.parentId, single = true,
        onMove = { if (model.moveFolder(folder.id, it)) moveFolder = null }, onCreateAndMove = { false }, onDismiss = { moveFolder = null },
        rootLabel = "Library", allowCreate = false, excluded = LibraryFolders.descendants(state.folders, folder.id)) }
    rename?.let { note -> NameDialog("Rename notebook", "A name that feels right.", note.title, "Save", { rename = null }) { model.rename(note, it); rename = null } }
    colourFolder?.let { stale -> state.folders.find { it.id == stale.id }?.let { folder -> FolderColourDialog(folder, { model.recolorFolder(folder.id, it) }, { colourFolder = null }) } }
    renameFolder?.let { folder -> NameDialog("Rename folder", "Keep your workspace organized.", folder.name, "Save", { renameFolder = null }) { if (model.renameFolder(folder, it)) renameFolder = null } }
    deleteFolder?.let { folder -> AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { deleteFolder = null }, title = { Text("Remove “${folder.name}”?") }, text = { Text("Its notebooks and subfolders move to ${LibraryFolders.label(state.folders, folder.parentId).ifEmpty { "Library" }}. Your notebooks will be kept.") }, dismissButton = { TextButton({ deleteFolder = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }, confirmButton = { TextButton({ model.deleteFolder(folder); deleteFolder = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes()) { Text("Remove folder") } }) }
    move?.let { note -> LibraryMovePanel("Move ${note.title}", state.folders, note.folderId, single = true,
        onMove = { model.move(note, it); move = null },
        onCreateAndMove = { name -> model.createFolderAndMove(setOf(note.id), name) },
        onDismiss = { move = null }) }
    delete?.let { note -> AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = { delete = null }, title = { Text("Delete “${note.title}”?") }, text = { Text("This removes the notebook and its ${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"} from this device. Export a copy first if you want to keep it.") }, dismissButton = { TextButton({ delete = null }, shapes = ButtonDefaults.shapes()) { Text("Keep notebook") } }, confirmButton = { TextButton({ model.delete(note); delete = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes()) { Text("Delete notebook") } }) }
}

/** A quiet label between the folder tiles and the notebooks below them. */
@Composable private fun LibraryGroupLabel(text: String) {
    Text(text, Modifier.padding(top = FolioSpacing.dp4), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One destination in the library navigation rail: icon over a label, per MDC's rail spec. */
@Composable private fun RailItem(title: String, icon: ImageVector, selected: Boolean, action: () -> Unit) {
    NavigationRailItem(selected = selected, onClick = action, icon = { Icon(icon, null) },
        label = { Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        alwaysShowLabel = true)
}

@Composable internal fun NotebookCard(note: Notebook, thumbnails: PageThumbnailCache, folder: String?, open: () -> Unit, star: () -> Unit, rename: () -> Unit, move: () -> Unit, delete: () -> Unit, examDetails: () -> Unit = {}, recordMark: () -> Unit = {}, selecting: Boolean = false, redoCount: Int = 0, selected: Boolean = false, onLongPress: () -> Unit = {}, pageCover: Boolean = true, onCoverToggle: () -> Unit = {}, duplicate: () -> Unit = {}, backupExcluded: Boolean = false, onBackupToggle: () -> Unit = {}, onChangeCover: () -> Unit = {}, onTags: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box {
            NotebookFace(note, thumbnails, Modifier.fillMaxWidth().combinedClickable(onClickLabel = if (selecting) "Toggle selection for ${note.title}" else "Open ${note.title}", onClick = open, onLongClick = onLongPress))
            if (selecting) {
                val checked = selected
                Surface(
                    onClick = open,
                    shape = FolioShapes.medium,
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .92f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.align(Alignment.TopStart).padding(FolioSpacing.dp8).size(FolioTouch.target).folioSelected(checked).semanticsLabel(if (checked) "Deselect ${note.title}" else "Select ${note.title}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            } else {
                IconButton(star, modifier = Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp2), shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        if (note.starred) "Remove from favorites" else "Add to favorites",
                        tint = if (note.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .85f),
                        modifier = Modifier.size(21.dp).folioSelected(note.starred)
                    )
                }
            }
        }
        Row(Modifier.padding(top = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).combinedClickable(onClick = open, onLongClick = onLongPress)) {
                                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull("${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"}", folder, libraryLastEditedLabel(note.updated)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
            if (!selecting) NotebookMenu(rename, move, delete, examDetails, recordMark, pageCover, onCoverToggle, duplicate, backupExcluded, onBackupToggle, onChangeCover, onTags, title = note.title)
        }
        if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (backupExcluded) Text("Excluded from library backups", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExamBadges(note, modifier = Modifier.padding(top = FolioSpacing.dp4), redoCount = redoCount)
    }
}

@Composable private fun NotebookMenu(rename: () -> Unit, move: () -> Unit, delete: () -> Unit, examDetails: () -> Unit = {}, recordMark: () -> Unit = {}, pageCover: Boolean = true, onCoverToggle: () -> Unit = {}, duplicate: () -> Unit = {}, backupExcluded: Boolean = false, onBackupToggle: () -> Unit = {}, onChangeCover: () -> Unit = {}, onTags: () -> Unit = {}, title: String = "Notebook options") {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for $title") }
        if (menu) FolioActionPopover(title, { menu = false }) {
            val run: (() -> Unit) -> Unit = { menu = false; it() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                PopoverTile(Icons.Rounded.Edit, "Rename", Modifier.weight(1f)) { run(rename) }
                PopoverTile(Icons.Rounded.FolderOpen, "Move", Modifier.weight(1f)) { run(move) }
                PopoverTile(Icons.Rounded.ContentCopy, "Duplicate", Modifier.weight(1f)) { run(duplicate) }
            }
            PopoverRow(Icons.Rounded.Sell, "Tags") { run(onTags) }
            PopoverRow(Icons.AutoMirrored.Rounded.FactCheck, "Exam details") { run(examDetails) }
            PopoverRow(Icons.AutoMirrored.Rounded.Grading, "Record a mark") { run(recordMark) }
            PopoverRow(Icons.Rounded.Palette, "Change cover") { run(onChangeCover) }
            PopoverRow(if (pageCover) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.Image,
                if (pageCover) "Use default cover" else "Use first page as cover") { run(onCoverToggle) }
            PopoverRow(if (backupExcluded) Icons.Rounded.Backup else Icons.Rounded.CloudOff,
                if (backupExcluded) "Include in library backups" else "Exclude from library backups") { run(onBackupToggle) }
            HorizontalDivider(Modifier.padding(vertical = FolioSpacing.dp8))
            PopoverRow(Icons.Rounded.DeleteOutline, "Delete notebook", destructive = true) { run(delete) }
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
                .clip(FolioShapes.medium)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FolioShapes.medium),
            contentAlignment = Alignment.Center
        ) {
            Image(imageBitmap, "First page preview of ${note.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    } else {
        NotebookCover(note, modifier.aspectRatio(aspect), compact = true)
    }
}

/** A compact list row's small preview, falling back to a type icon before one has been drawn. */
@Composable internal fun NotebookListThumbnail(note: Notebook, thumbnails: PageThumbnailCache, modifier: Modifier = Modifier) {
    val preview = rememberNotebookPreview(note, thumbnails, with(LocalDensity.current) { 44.dp.roundToPx() })
    val imageBitmap = remember(preview) { preview?.asImageBitmap() }
    val isPdf = note.pages.any { it.pdfIndex != null }
    Box(modifier.clip(FolioShapes.small).background(Color.White), contentAlignment = Alignment.Center) {
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
    CoverFace(note.title, note.pages.any { it.pdfIndex != null }, note.cover, modifier, compact)
}
