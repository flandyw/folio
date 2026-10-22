@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.app.NameDialog
import com.folio.notes.app.SidebarItem
import com.folio.notes.exam.BatchExamTagsPanel
import com.folio.notes.exam.ExamAttempt
import com.folio.notes.exam.ExamDetailsPanel
import com.folio.notes.exam.ExamFilter
import com.folio.notes.exam.ExamStatus
import com.folio.notes.exam.ScoreDialog
import com.folio.notes.exam.SubjectChip
import com.folio.notes.exam.VceSubject
import com.folio.notes.input.FolioTouch
import com.folio.notes.input.guardUiTouches
import com.folio.notes.input.longPressAction
import com.folio.notes.input.rememberLongPressGuard
import com.folio.notes.library.files.DeviceExplorer
import com.folio.notes.library.files.ExplorerBreadcrumbs
import com.folio.notes.library.files.NotebookTagsPanel
import com.folio.notes.model.Folder
import com.folio.notes.model.Notebook
import com.folio.notes.progress.ExamRecordReview
import com.folio.notes.planner.PlannerScreen
import com.folio.notes.progress.ProgressScreen
import com.folio.notes.progress.countsAsExam
import com.folio.notes.settings.rememberPref
import com.folio.notes.storage.LibraryFolders
import com.folio.notes.storage.NotebookTags
import com.folio.notes.ui.FolioActionPopover
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioExpand
import com.folio.notes.ui.FolioScreenHeading
import com.folio.notes.ui.FolioSectionHeading
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.FolioSwipeRow
import com.folio.notes.ui.LocalDestinationTopGap
import com.folio.notes.ui.PopoverRow
import com.folio.notes.ui.SwipeAction
import com.folio.notes.ui.folioDisclosure
import com.folio.notes.ui.folioSelected
import com.folio.notes.ui.folioSpring
import com.folio.notes.vcaa.VcaaScreen
import kotlinx.coroutines.launch

fun Modifier.semanticsLabel(label: String) = semantics { contentDescription = label }

/** Library-owned destinations alongside the other peer panes. Tablet files is a place inside the library. */
enum class LibrarySection { LIBRARY, FILES, PROGRESS, PLANNER, VCAA, TRASH }

@Composable fun LibraryScreen(
    state: FolioState, model: FolioViewModel,
    onNew: () -> Unit, onImport: () -> Unit, onImportArchive: () -> Unit,
    onSettings: () -> Unit, onMistakes: () -> Unit = {}, snackbar: SnackbarHostState? = null,
    showMistakes: Boolean = false, onLibrary: () -> Unit = {},
    showStudy: Boolean = false, onStudy: () -> Unit = {},
    showMusic: Boolean = false, onMusic: () -> Unit = {},
    studyContent: @Composable () -> Unit = {},
    musicContent: @Composable (onReaderMode: (Boolean) -> Unit) -> Unit = {},
    onOpenNotebook: (String) -> Unit = model::open,
    selectionCaption: String? = null, onCancelSelection: () -> Unit = {},
    selectionControls: @Composable ColumnScope.() -> Unit = {},
    mistakesContent: @Composable (onReviewMode: (Boolean) -> Unit) -> Unit = {},
    // Extra top space for panes with no top bar of their own (full screen has no status bar inset).
    onQuickNote: () -> Unit = onNew,
    onQuickCanvas: () -> Unit = onNew,
    onShareNotebook: (Notebook) -> Unit = {},
) {
    val libraryDrag = rememberNotebookDrag(state, model)
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val libraryPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    // Settings › Appearance › Sidebar: destinations switched off there leave the rail and bar (the open one stays).
    val hiddenNav by rememberPref(libraryPrefs, SidebarItem.PREF_KEY) { SidebarItem.hidden(it.getString(SidebarItem.PREF_KEY, null)) }
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
    // Progress, VCAA and Trash have their own panes; Library and Files share the shelf.
    val libraryPeer = section == LibrarySection.PROGRESS || section == LibrarySection.PLANNER || section == LibrarySection.VCAA || section == LibrarySection.TRASH
    fun navShown(item: SidebarItem) = SidebarItem.shown(item, hiddenNav, when (item) {
        SidebarItem.MISTAKES -> showMistakes
        SidebarItem.STUDY -> showStudy
        SidebarItem.MUSIC -> showMusic
        SidebarItem.PROGRESS -> !otherPane && section == LibrarySection.PROGRESS
        SidebarItem.PLANNER -> !otherPane && section == LibrarySection.PLANNER
        SidebarItem.VCAA -> !otherPane && section == LibrarySection.VCAA
    })
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
    var savingView by remember { mutableStateOf(false) }
    var undoTrash by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(undoTrash) {
        if (undoTrash.isEmpty()) return@LaunchedEffect
        val ids = undoTrash
        val result = snackbar?.showSnackbar("Moved ${ids.size} notebook${if (ids.size == 1) "" else "s"} to Trash", "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) model.restoreNotebooks(ids)
        undoTrash = emptySet()
    }
    LaunchedEffect(delete) { delete?.let { note -> model.delete(note); if (model.state.value.trash.any { it.notebook.id == note.id }) undoTrash = setOf(note.id); delete = null } }
    val swipeRight by rememberPref(libraryPrefs, AppPrefs.UX_SWIPE_RIGHT) { AppPrefs.uxSwipe(it.getString(AppPrefs.UX_SWIPE_RIGHT, null), "FAVOURITE") }
    val swipeLeft by rememberPref(libraryPrefs, AppPrefs.UX_SWIPE_LEFT) { AppPrefs.uxSwipe(it.getString(AppPrefs.UX_SWIPE_LEFT, null), "TRASH") }
    fun swipe(note: Notebook, action: String) = SwipeAction(
        when (action) { "TRASH" -> Icons.Rounded.DeleteOutline; "TAGS" -> Icons.Rounded.Sell; "MOVE" -> Icons.Rounded.FolderOpen; "SHARE" -> Icons.Rounded.IosShare; else -> Icons.Rounded.Star },
        when (action) { "TRASH" -> "Move to Trash"; "TAGS" -> "Edit tags"; "MOVE" -> "Move notebook"; "SHARE" -> "Share notebook PDF"; else -> if (note.starred) "Remove favourite" else "Add favourite" }, destructive = action == "TRASH") {
        when (action) { "TRASH" -> delete = note; "TAGS" -> notebookTags = setOf(note.id); "MOVE" -> move = note; "SHARE" -> onShareNotebook(note); else -> model.star(note) }
    }
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
    // Clearing the box also drops the keyboard, so the shelf is usable at once.
    fun clearQuery() { query = ""; debouncedQuery = ""; focusManager.clearFocus() }
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
    BackHandler(enabled = !otherPane && section == LibrarySection.LIBRARY && (libraryDrag.active || selecting || searchActive || filtersExpanded || located)) {
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
    fun showTrash() { selecting = false; selectedIds = emptyList(); section = LibrarySection.TRASH; onLibrary() }
    BackHandler(enabled = !otherPane && !pickingNotebook && section == LibrarySection.TRASH) { libraryHome() }
    @Composable fun CreateMenuItems(close: () -> Unit) {
        PopoverRow(Icons.Rounded.Add, "New notebook") { close(); onNew() }
        PopoverRow(Icons.Rounded.EditNote, "Quick note") { close(); onQuickNote() }
        PopoverRow(Icons.Rounded.AllInclusive, "Infinite canvas") { close(); onQuickCanvas() }
        PopoverRow(Icons.Rounded.CreateNewFolder, if (openFolder != null) "New folder in ${openFolder.name}" else "New folder") { close(); startNewFolder(openFolder?.id) }
        HorizontalDivider(Modifier.padding(vertical = FolioSpacing.dp8))
        PopoverRow(Icons.Rounded.PictureAsPdf, "Import PDF document") { close(); onImport() }
        PopoverRow(Icons.Rounded.LocalLibrary, "VCAA exam library") { close(); selecting = false; selectedIds = emptyList(); section = LibrarySection.VCAA; onLibrary() }
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
                    RailItem("Library", Icons.Rounded.GridView, !otherPane && (pickingNotebook || !libraryPeer)) { libraryHome() }
                    if (!pickingNotebook) {
                        if (navShown(SidebarItem.MISTAKES)) RailItem("Mistakes", Icons.Rounded.School, showMistakes) { onMistakes() }
                        if (navShown(SidebarItem.STUDY)) RailItem("Study", Icons.Rounded.Timer, showStudy) { onStudy() }
                        if (navShown(SidebarItem.PROGRESS)) RailItem("Progress", Icons.Rounded.Insights, !otherPane && section == LibrarySection.PROGRESS) { section = LibrarySection.PROGRESS; onLibrary() }
                        if (navShown(SidebarItem.PLANNER)) RailItem("Planner", Icons.Rounded.EventAvailable, !otherPane && section == LibrarySection.PLANNER) { section = LibrarySection.PLANNER; onLibrary() }
                        if (navShown(SidebarItem.VCAA)) RailItem("VCAA", Icons.Rounded.LocalLibrary, !otherPane && section == LibrarySection.VCAA) { section = LibrarySection.VCAA; onLibrary() }
                        if (navShown(SidebarItem.MUSIC)) RailItem("Music", Icons.Rounded.MusicNote, showMusic) { onMusic() }
                    }
                    Spacer(Modifier.height(FolioSpacing.dp4))
                    HorizontalDivider(Modifier.padding(horizontal = FolioSpacing.dp8))
                    Spacer(Modifier.weight(1f))
                    if (!pickingNotebook) RailItem("Trash", Icons.Rounded.DeleteOutline, !otherPane && section == LibrarySection.TRASH) { showTrash() }
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
                if (!otherPane && (pickingNotebook || !libraryPeer)) Column(Modifier.weight(1f).fillMaxHeight().then(entrance)) {
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
                        if (!pickingNotebook) IconButton({ showTrash() }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.DeleteOutline, "Trash")
                        }
                        if (!showSidebar && !pickingNotebook) IconButton({ selecting = false; selectedIds = emptyList(); section = LibrarySection.FILES }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.TabletAndroid, "Tablet files")
                        }
                        if (!wide) IconButton(onSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Settings") }
                    }
                    if (selectionCaption != null) Surface(
                        modifier = Modifier.padding(horizontal = FolioDestinationInset).padding(bottom = FolioSpacing.dp12),
                        shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(selectionCaption, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onCancelSelection, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                            }
                            selectionControls()
                        }
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
                LazyVerticalGrid(columns = if (listView) GridCells.Fixed(1) else GridCells.Adaptive(coverWidth), modifier = Modifier.weight(1f).fillMaxHeight().notebookRangeSelect(selecting && !pickingNotebook, libraryGridState, notes.map { it.id }, selectedIds) { selectedIds = it }.then(if (drag != null) Modifier.notebookDragScroll(drag) { libraryGridState.scrollBy(it) } else Modifier)
                        .notebookDropTarget(if (backgroundDrop != null) drag else null, backgroundDrop ?: NotebookDropDestination.Favorites),
                    state = libraryGridState,
                    contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, bottom = FolioDestinationInset), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(if (listView) FolioSpacing.dp8 else FolioSpacing.dp16)) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
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
                            }
                            if (!pickingNotebook && !selecting) { ContinueWorkingCard(state.notes, model.thumbnails, onOpenNotebook); LibraryShortcutHint() }
                            if (!pickingNotebook) SavedLibraryViews(org.json.JSONObject().put("query", query).put("place", place.name).put("folder", state.folderId.orEmpty()).put("tag", tag.orEmpty()).put("kind", kind.name).put("sort", sort.name).put("exam", examFilter.toUxJson()), savingView, { savingView = it }) { saved ->
                                query = saved.optString("query"); debouncedQuery = query
                                place = LibraryPlace.entries.find { it.name == saved.optString("place") } ?: LibraryPlace.ALL
                                model.folder(saved.optString("folder").takeIf { id -> state.folders.any { it.id == id } })
                                tag = saved.optString("tag").takeIf { it.isNotBlank() }
                                kind = AppPrefs.libraryKind(saved.optString("kind")); sort = AppPrefs.librarySort(saved.optString("sort"))
                                model.setExamFilter(uxExamFilter(saved.optJSONObject("exam") ?: org.json.JSONObject()))
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
                                            PopoverRow(if (sort == option) Icons.Rounded.Check else null, option.label, selected = sort == option) { sort = option; sortMenu = false }
                                        }
                                        if (!pickingNotebook) PopoverRow(Icons.Rounded.BookmarkAdd, "Save this view…") { sortMenu = false; savingView = true }
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
                                if (searchActive) TextButton({ clearQuery() }, shapes = ButtonDefaults.shapes()) { Text("Clear search") }
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
                                if (searchActive) TextButton({ clearQuery() }, shapes = ButtonDefaults.shapes()) { Text("Clear search") }
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
                        val pinScope = LibraryBrowse.pinScope(note, place, openFolder?.id, tag)
                        val pinned = LibraryBrowse.isPinned(note, place, openFolder?.id, tag)
                        val pin: (() -> Unit)? = if (pickingNotebook) null else pinScope?.let { scope ->
                            { model.pin(note, scope == LibraryPinScope.FOLDER) }
                        }
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
                            startToEnd = if (selecting || pickingNotebook) null else swipe(note, swipeRight),
                            endToStart = if (selecting || pickingNotebook) null else swipe(note, swipeLeft),
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
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                            if (pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                            if (note.starred) Icon(Icons.Rounded.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                            Text(note.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                        }
                                        Text(listOfNotNull(folder, "${note.pages.size} ${if (note.pages.size == 1) "page" else "pages"}", libraryLastEditedLabel(note.updated)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (note.id in state.backupExcludedNotebookIds) Text("Excluded from library backups", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (!selecting) {

                                        IconButton({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); model.star(note) }, shapes = IconButtonDefaults.shapes()) { Icon(if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (note.starred) "Remove from favorites" else "Add to favorites", Modifier.folioSelected(note.starred)) }
                                        NotebookMenu({ rename = note }, { move = note }, { delete = note }, { examDetails = note }, { pendingMark = note }, note.pageCover, { model.setPageCover(note, !note.pageCover) }, { model.duplicateNotebook(note) }, note.id in state.backupExcludedNotebookIds, { model.setBackupExcluded(setOf(note.id), note.id !in state.backupExcludedNotebookIds) }, { coverFor = note.id }, onTags = { notebookTags = setOf(note.id) }, title = note.title, pinned = pinned, pin = pin, createLike = { model.createLike(note) })
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
                                pinned = pinned, pin = pin,
                                duplicate = { model.duplicateNotebook(note) }, createLike = { model.createLike(note) },
                                backupExcluded = note.id in state.backupExcludedNotebookIds,
                                onBackupToggle = { model.setBackupExcluded(setOf(note.id), note.id !in state.backupExcludedNotebookIds) },
                                modifier = dragModifier.then(if (wide) Modifier else Modifier.animateItem(placementSpec = folioSpring())).semantics { if (selecting) this.selected = note.id in selection }
                            )
                    }

                }
                    }
                }
                }
                if (!otherPane && !pickingNotebook && section == LibrarySection.TRASH) {
                    TrashScreen(state, model, { libraryHome() }, Modifier.weight(1f).fillMaxHeight())
                }
                if (!otherPane && !pickingNotebook && section == LibrarySection.VCAA) {
                    VcaaScreen(state, model, openFolder?.id, openFolder?.name, Modifier.weight(1f).fillMaxHeight().then(entrance))
                }
                if (!otherPane && !pickingNotebook && section == LibrarySection.PLANNER) {
                    PlannerScreen(state.notes, Modifier.weight(1f).fillMaxHeight().then(entrance), onSettings, onMistakes, onStudy)
                }
                if (!otherPane && !pickingNotebook && section == LibrarySection.PROGRESS) {
                    ProgressScreen(state.notes, model, Modifier.weight(1f).fillMaxHeight().then(entrance),
                        onSettings, onMistakes, onStudy, onOpenNotebook)
                }
        }
        if (selecting && !libraryDrag.active && !otherPane && !pickingNotebook && section == LibrarySection.LIBRARY) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8)) {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            Text("${selection.size} of ${notes.size} selected · drag across cards to select a range", style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
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
                                shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp4)); Text("Trash") }
                        }
                    }
                }
            }
        }
        // M3e short navigation bar: three to five destinations, equally weighted on a phone.
        // The wide layout keeps its navigation rail instead.
        if (!wide && showNavigation && !pickingNotebook && !selecting && SidebarItem.entries.any(::navShown)) ShortNavigationBar(modifier = Modifier.guardUiTouches()) {
            ShortNavigationBarItem(!otherPane && !libraryPeer, { libraryHome() },
                icon = { Icon(Icons.Rounded.GridView, null) }, label = { Text("Library") })
            if (navShown(SidebarItem.MISTAKES)) ShortNavigationBarItem(showMistakes, onMistakes, icon = { Icon(Icons.Rounded.School, null) }, label = { Text("Mistakes") })
            if (navShown(SidebarItem.STUDY)) ShortNavigationBarItem(showStudy, onStudy, icon = { Icon(Icons.Rounded.Timer, null) }, label = { Text("Study") })
            if (navShown(SidebarItem.PROGRESS)) ShortNavigationBarItem(!otherPane && section == LibrarySection.PROGRESS, { section = LibrarySection.PROGRESS; onLibrary() },
                icon = { Icon(Icons.Rounded.Insights, null) }, label = { Text("Progress") })
            if (navShown(SidebarItem.PLANNER)) ShortNavigationBarItem(!otherPane && section == LibrarySection.PLANNER, { section = LibrarySection.PLANNER; onLibrary() },
                icon = { Icon(Icons.Rounded.EventAvailable, null) }, label = { Text("Planner") })
            if (navShown(SidebarItem.VCAA)) ShortNavigationBarItem(!otherPane && section == LibrarySection.VCAA, { section = LibrarySection.VCAA; onLibrary() },
                icon = { Icon(Icons.Rounded.LocalLibrary, null) }, label = { Text("VCAA") })
            if (navShown(SidebarItem.MUSIC)) ShortNavigationBarItem(showMusic, onMusic, icon = { Icon(Icons.Rounded.MusicNote, null) }, label = { Text("Music") })
        }
        }
        NotebookDragFeedback(libraryDrag, Modifier.align(Alignment.BottomCenter).padding(horizontal = FolioSpacing.dp16, vertical = if (wide) FolioSpacing.dp16 else 88.dp))
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
        title = { Text("Move ${selection.size} notebook${if (selection.size == 1) "" else "s"} to Trash?") },
        text = { Text("You can restore these notebooks and all their pages from Trash. They stay there until you delete them forever.") },
        dismissButton = { TextButton({ bulkDelete = false }, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
        confirmButton = {
            TextButton(
                { model.deleteNotebooks(selection); undoTrash = selection; bulkDelete = false; selectedIds = emptyList() },
                enabled = selection.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes()) { Text("Move to Trash") }
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
