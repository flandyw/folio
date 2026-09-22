package com.folio.notes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import java.util.UUID

class FolioApplication : Application() {
    val repository by lazy { NoteRepository(this) }
    val thumbnails by lazy { PageThumbnailCache(this, repository) }
    val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** App-scoped so a pen connection survives configuration changes but is still editor-bound. */
    val penHaptics by lazy { PenHapticsManager(this) }
}
/** Text search over the open notebook's imported PDF: what was asked and what matched. */
data class PdfSearchState(
    val query: String = "",
    val searching: Boolean = false,
    val searched: Boolean = false,
    val results: List<PdfSearchHit> = emptyList()
)

data class FolioState(
    val notes: List<Notebook> = emptyList(), val folders: List<Folder> = emptyList(),
    val examFilter: ExamFilter = ExamFilter(),
    val navigationRequest: Int = 0,
    val editorOnRight: Boolean = false,
    val tabs: List<EditorTab> = emptyList(),
    val companion: EditorTab? = null,
    val companionMode: CompanionMode = CompanionMode.SPLIT,
    /** Share of the split given to the editor pane (0.2..0.8); the companion takes the rest. */
    val splitFraction: Float = 0.5f,
    /** When true, turning the editor's page also turns the companion (linked reference). */
    val companionLinked: Boolean = false,
    val activeId: String? = null, val pageIndex: Int = 0, val folderId: String? = null,
    val loading: Boolean = true, val busy: Boolean = false, val exporting: Boolean = false, val pendingSaves: Int = 0,
    val saveFailed: Boolean = false, val loadFailed: Boolean = false, val error: String? = null,
    val pendingPdfImports: List<Uri> = emptyList(),
    val importProgress: String? = null,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    /** Exam-condition timer for the open notebook, driven by [FolioViewModel.tickTimer]. */
    val timer: ExamTimerState = ExamTimerState(),
    /** Seconds the last stopped timed sitting ran for, offered when recording the mark. */
    val lastTimedSeconds: Int? = null,
    /** Ink, text and pictures cut or copied from a lasso selection, kept so they paste on any page. */
    val clipboard: CanvasSelection = CanvasSelection(),
    /** Text search over the open notebook's imported PDF, driven by [FolioViewModel.searchPdf]. */
    val pdfSearch: PdfSearchState = PdfSearchState()
) {
    val active get() = notes.find { it.id == activeId }
    val page get() = active?.pages?.getOrNull(pageIndex)
    /** Days until the nearest upcoming exam date across the library, or null when none is set. */
    val daysToExam: Int?
        get() {
            val start = startOfDay()
            return notes.asSequence().mapNotNull { it.exam.examDate }
                .filter { it >= start }
                .minOrNull()
                ?.let { date -> ((date - start) / 86_400_000L).toInt() }
        }

    private fun startOfDay(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0); calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}

/** One page's ink, text and placed pictures together, so undo restores whichever the last edit touched. */
private typealias PageContent = Triple<List<Stroke>, List<TextBox>, List<PageImage>>

class FolioViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("preferences", 0)
    private val positionPrefs = application.getSharedPreferences("notebook_positions", 0)
    val repository = (application as FolioApplication).repository
    val thumbnails = (application as FolioApplication).thumbnails
    private val restoredTabs = WorkspaceSessionCodec.decode(savedState["workspaceTabs"])
    private val _state = MutableStateFlow(FolioState(tabs = restoredTabs,
        pdfSearch = restoredTabs.find { it.notebookId == savedState.get<String>("activeId") }?.search ?: PdfSearchState(),
        companion = WorkspaceSessionCodec.decode(savedState["workspaceCompanion"]).firstOrNull(),
        companionMode = runCatching { CompanionMode.valueOf(savedState.get<String>("workspaceMode") ?: "SPLIT") }.getOrDefault(CompanionMode.SPLIT),
        splitFraction = (savedState.get<Float>("splitFraction")
            ?: prefs.getFloat(AppPrefs.SPLIT_FRACTION, AppPrefs.DEFAULT_SPLIT).takeIf { prefs.contains(AppPrefs.SPLIT_FRACTION) }
            ?: AppPrefs.DEFAULT_SPLIT).let { AppPrefs.splitFraction(it) },
        companionLinked = savedState.get<Boolean>("companionLinked") ?: false,
        editorOnRight = savedState["editorOnRight"] ?: false, activeId = savedState["activeId"], pageIndex = savedState["pageIndex"] ?: 0, folderId = savedState["folderId"]))
    val state = _state.asStateFlow()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val undo = mutableMapOf<String, MutableList<PageContent>>()
    private val redo = mutableMapOf<String, MutableList<PageContent>>()
    private var ready = CompletableDeferred<Unit>()
    /** Offsets each paste a little further, so repeated pastes stack instead of hiding each other. */
    private var pasteGeneration = 0
    /** Pages whose content is being read right now, so a page is never fetched twice at once. */
    private val loadingPages = mutableSetOf<String>()
    private var timerNotebookId: String? = _state.value.activeId
    private val notebookSittings = NotebookSittings()
    /** Last moment the running timer's heartbeat was written to preferences. */
    private var lastSeenPersistedAt: Long = 0L

    private fun timerKey(key: String): String = "$key.notebook.${requireNotNull(timerNotebookId)}"

    /** The old global sitting can be assigned only when its page log identifies one notebook. */
    private fun migrateLegacyTimer(notes: List<Notebook>) {
        if (!prefs.contains(TIMER_START_KEY)) return
        val visits = try {
            org.json.JSONArray(prefs.getString(TIMER_VISITS_KEY, "[]")).let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("pageId") }
            }
        } catch (_: Exception) { emptyList() }
        val pageIds = visits.toSet()
        val owner = notes.filter { note -> note.pages.any { it.id in pageIds } }.singleOrNull() ?: return
        val keys = listOf(TIMER_START_KEY, TIMER_PAUSED_AT_KEY, TIMER_PAUSED_MILLIS_KEY,
            TIMER_WRITING_KEY, TIMER_READING_KEY, TIMER_LABEL_KEY)
        val editor = prefs.edit()
        if (!prefs.contains("$TIMER_START_KEY.notebook.${owner.id}")) {
            keys.forEach { key ->
                val destination = "$key.notebook.${owner.id}"
                when (val value = prefs.all[key]) {
                    is Long -> editor.putLong(destination, value)
                    is Int -> editor.putInt(destination, value)
                    is String -> editor.putString(destination, value)
                }
            }
        }
        keys.forEach { editor.remove(it) }
        editor.remove(TIMER_VISITS_KEY)
        editor.apply()
        if (timerNotebookId == owner.id) restoreNotebookTimer()
    }

    private fun restoreNotebookTimer(now: Long = System.currentTimeMillis()) {
        val id = timerNotebookId ?: run {
            _state.update { it.copy(timer = ExamTimerState(), lastTimedSeconds = null) }
            return
        }
        val cached = notebookSittings.restore(id)
        val timer = cached?.timer
            ?: ExamTimerState.resume(storedSitting(), prefs.getLong(timerKey(TIMER_START_KEY), 0L),
                pausedAt = prefs.getLong(timerKey(TIMER_PAUSED_AT_KEY), 0L).takeIf { it > 0L },
                pausedMillis = prefs.getLong(timerKey(TIMER_PAUSED_MILLIS_KEY), 0L))
            ?: ExamTimerState()
        // A crash or kill while foregrounded leaves a running sitting with no recorded pause;
        // anything past the last confirmed-visible moment never counts.
        val clamped = timer.clampUnseenGap(prefs.getLong(timerKey(TIMER_LAST_SEEN_KEY), 0L).takeIf { it > 0L }, now)
        if (clamped != timer) saveSitting(clamped, clamped.pausedAt ?: now)
        _state.update { it.copy(timer = clamped, lastTimedSeconds = cached?.seconds) }
    }

    /** Leaving a notebook pauses its clock, so time away never counts; returning stays paused until resumed. */
    private fun selectNotebookTimer(id: String?, now: Long = System.currentTimeMillis()) {
        if (id == timerNotebookId) return
        timerNotebookId?.let { previous ->
            val current = _state.value.timer
            val paused = if (current.running) current.pause(now) else current
            if (paused != current) saveSitting(paused)
            val state = _state.value
            notebookSittings.save(previous, paused, state.lastTimedSeconds)
        }
        timerNotebookId = id
        restoreNotebookTimer()
    }

    var pendingExport: PageExportRequest? = null
    init {
        (application as FolioApplication).storageScope.launch {
            for (write in writes) {
                try { write() } catch (e: Exception) { _state.update { it.copy(saveFailed = true, error = "Couldn't save changes: ${e.message}. Use Retry save before closing.") } }
                finally { _state.update { it.copy(pendingSaves = (it.pendingSaves - 1).coerceAtLeast(0)) } }
            }
        }
        loadLibrary()
        restoreNotebookTimer()
        viewModelScope.launch {
            state
                .distinctUntilChanged { a, b ->
                    a.activeId == b.activeId && a.pageIndex == b.pageIndex && a.folderId == b.folderId &&
                        a.tabs == b.tabs && a.companion == b.companion && a.companionMode == b.companionMode &&
                        a.splitFraction == b.splitFraction && a.companionLinked == b.companionLinked &&
                        a.editorOnRight == b.editorOnRight && a.pdfSearch == b.pdfSearch
                }
                .collect { savedState["activeId"] = it.activeId; savedState["pageIndex"] = it.pageIndex; savedState["folderId"] = it.folderId
            savedState["workspaceTabs"] = WorkspaceSessionCodec.encode(it.tabs.map { tab ->
                if (tab.notebookId == it.activeId) tab.copy(currentPageId = it.page?.id ?: tab.currentPageId, search = it.pdfSearch) else tab
            })
            savedState["workspaceCompanion"] = WorkspaceSessionCodec.encode(listOfNotNull(it.companion))
            savedState["workspaceMode"] = it.companionMode.name
            savedState["splitFraction"] = it.splitFraction
            savedState["companionLinked"] = it.companionLinked
            savedState["editorOnRight"] = it.editorOnRight
        } }
    }
    fun loadLibrary() {
        if (ready.isCompleted) ready = CompletableDeferred()
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            try {
                val (notes, folders) = repository.load()
                migrateLegacyTimer(notes)
                prunePositions(notes)
                _state.update { state -> state.copy(notes = notes, folders = folders, loading = false,
                    activeId = state.activeId?.takeIf { id -> notes.any { it.id == id } },
                    tabs = state.tabs.filter { tab -> notes.any { it.id == tab.notebookId } },
                    companion = state.companion?.takeIf { tab -> notes.any { it.id == tab.notebookId } }) }
                captureTab()
                ready.complete(Unit)
                // Empty practice pages from older versions never reach the shelf again.
                viewModelScope.launch {
                    try { purgeEmptyMistakeNotebooks() } catch (_: Exception) { }
                }
                val search = _state.value.pdfSearch
                if (search.query.isNotBlank() && !search.searched) searchPdf(search.query)
            }
            catch (e: Exception) { _state.update { it.copy(loading = false, loadFailed = true, error = "Couldn't load your library: ${e.message}") }; ready.completeExceptionally(e) }
        }
    }
    private fun enqueue(block: suspend () -> Unit) { _state.update { it.copy(pendingSaves = it.pendingSaves + 1) }; writes.trySend(block) }
    /** Records a change to the notebook itself — title, folder, star, or the order of pages. */
    private fun updateNote(note: Notebook) {
        val updated = note.copy(updated = System.currentTimeMillis())
        _state.update { state -> state.copy(notes = state.notes.map { if (it.id == updated.id) updated else it }) }
        enqueue { repository.saveMeta(updated) }
    }

    /**
     * Rewrites many notebooks in one state pass plus one save batch, so bulk actions stay O(N)
     * instead of O(N²) with one write per item.
     */
    private fun updateNotes(ids: Set<String>, transform: (Notebook) -> Notebook) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        var changed: List<Notebook> = emptyList()
        _state.update { state ->
            val updated = state.notes.map { note ->
                if (note.id !in ids) note
                else {
                    val next = transform(note).copy(updated = now)
                    if (next == note) note else next
                }
            }
            changed = updated.filter { it.id in ids && state.notes.find { n -> n.id == it.id } != it }
            state.copy(notes = updated)
        }
        if (changed.isEmpty()) return
        enqueue {
            changed.forEach { repository.saveMeta(it) }
        }
    }

    /** Fast page lookup: the active notebook first (the hot path), then the rest of the library. */
    private fun findPage(pageId: String): Notebook? {
        val current = _state.value
        current.active?.let { active ->
            if (active.pages.any { it.id == pageId }) return active
        }
        return current.notes.firstOrNull { note -> note.pages.any { it.id == pageId } }
    }

    private fun findPageContent(pageId: String): NotePage? {
        val current = _state.value
        current.active?.pages?.find { it.id == pageId }?.let { return it }
        return current.notes.firstNotNullOfOrNull { note -> note.pages.find { it.id == pageId } }
    }

    /** Identity-based stroke membership: avoids deep equals over every InkPoint per frame. */
    private fun identitySet(strokes: List<Stroke>): Set<Stroke> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(strokes) }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun reportError(message: String) { _state.update { it.copy(error = message) } }
    fun export(block: suspend () -> Unit) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) { reportError("Export failed: ${e.message}") }
            finally { _state.update { it.copy(exporting = false) } }
        }
    }
    fun folder(id: String?) { _state.update { it.copy(folderId = id) } }
    fun createFolder(name: String) {
        if (name.isBlank() || _state.value.loadFailed) return
        val folders = _state.value.folders + Folder(name = name.trim())
        _state.update { it.copy(folders = folders) }; enqueue { repository.saveFolders(folders) }
    }
    fun renameFolder(folder: Folder, name: String) {
        if (name.isBlank()) return
        val folders = _state.value.folders.map { if (it.id == folder.id) it.copy(name = name.trim()) else it }
        _state.update { it.copy(folders = folders) }; enqueue { repository.saveFolders(folders) }
    }
    fun deleteFolder(folder: Folder) {
        val affected = _state.value.notes.filter { it.folderId == folder.id }.map { it.id }.toSet()
        if (affected.isNotEmpty()) updateNotes(affected) { it.copy(folderId = null) }
        val folders = _state.value.folders.filterNot { it.id == folder.id }
        _state.update { it.copy(folders = folders, folderId = null) }; enqueue { repository.saveFolders(folders) }
    }
    fun create(title: String, cover: Int, paper: Paper, exam: ExamTags = ExamTags(), pageCount: Int = 1, infinite: Boolean = false, pageCover: Boolean = true) {
        if (title.isBlank() || _state.value.loading || _state.value.loadFailed) return
        val pages = List(if (infinite) 1 else pageCount.coerceIn(1, 40)) { NotePage(paper = paper, infinite = infinite) }
        val note = Notebook(title = title.trim(), folderId = _state.value.folderId, cover = cover, pages = pages, exam = exam, pageCover = pageCover)
        captureTab()
        selectNotebookTimer(note.id)
        _state.update { it.copy(notes = it.notes + note, activeId = note.id, pageIndex = 0, canUndo = false, canRedo = false, pdfSearch = PdfSearchState()) }
        captureTab()
        enqueue { repository.saveAll(note) }
    }
    /**
     * Starts a practice notebook in memory only. The first stroke, text or picture
     * persists it through the normal page save; an untouched page never reaches disk,
     * so abandoned reviews leave no empty notebook behind. No ink is kept in the cloud cache.
     */
    suspend fun createMistakePractice(user: String, mistake: com.folio.notes.mistakes.ExamTrackMistake, openWhenReady: Boolean = true): com.folio.notes.mistakes.LocalMistakeReviewAttempt {
        ready.await()
        val page = NotePage(paper = Paper.MATH_GRID, infinite = true, title = mistake.question)
        val noteId = UUID.randomUUID().toString()
        val attempt = com.folio.notes.mistakes.LocalMistakeReviewAttempt(user, mistake.id, UUID.randomUUID().toString(), noteId, page.id)
        val note = Notebook(id = noteId, title = "${mistake.question} · Mistake practice", pages = listOf(page),
            mistakePractice = true, mistakeReviews = listOf(attempt))
        _state.update { it.copy(notes = it.notes + note) }
        if (openWhenReady) open(note.id)
        return attempt
    }

    fun completeMistakePractice(attempt: com.folio.notes.mistakes.LocalMistakeReviewAttempt) {
        val note = _state.value.notes.find { it.id == attempt.practiceNotebookId } ?: return
        val updated = note.copy(mistakeReviews = note.mistakeReviews.filterNot { it.reviewId == attempt.reviewId } + attempt)
        // Rating an untouched page must not materialise an empty notebook on disk.
        if (updated.pages.all { it.strokes.isEmpty() && it.images.isEmpty() && it.texts.all { t -> t.text.isBlank() } }) {
            delete(updated)
            return
        }
        updateNote(updated)
    }

    /** Bytes used on device per notebook id; missing notebooks report 0. */
    suspend fun practiceSizes(noteIds: Collection<String>): Map<String, Long> {
        if (noteIds.isEmpty()) return emptyMap()
        ready.await()
        return repository.notebookSizes(noteIds)
    }

    /**
     * Deletes every mistake-practice notebook with no ink, text or pictures, whether it
     * lives only in memory or already reached disk. Returns how many were removed.
     * Unreadable pages count as non-empty so cleanup never deletes work it could not inspect.
     */
    suspend fun purgeEmptyMistakeNotebooks(): Int {
        ready.await()
        val candidates = _state.value.notes.filter { it.mistakePractice }
        if (candidates.isEmpty()) return 0
        val emptyIds = mutableSetOf<String>()
        for (note in candidates) {
            val empty = try { repository.isNotebookEmpty(note) } catch (_: Exception) { false }
            if (empty) emptyIds += note.id
        }
        if (emptyIds.isEmpty()) return 0
        deleteNotebooks(emptyIds)
        return emptyIds.size
    }

    private fun captureTab() {
        val snapshot = _state.value
        val note = snapshot.active ?: return
        if (snapshot.page == null) return
        _state.update { state -> captureTabInto(state) }
        persistPosition(note.id)
    }

    /**
     * [next] with the open notebook's tab pointing at the page and search [next] already carries.
     * Pure, so a caller mid-update can fold the capture into the same state pass as the change that
     * caused it. A state whose tab did not move compares equal and is never emitted.
     */
    private fun captureTabInto(next: FolioState): FolioState {
        val note = next.active ?: return next
        val page = next.page ?: return next
        val old = next.tabs.find { it.notebookId == note.id }
            ?: EditorTab(note.id, note.id, page.id, note.title)
        val tab = old.copy(currentPageId = page.id, title = note.title, search = next.pdfSearch)
        return next.copy(tabs = next.tabs.withTab(tab))
    }

    /**
     * Writes one notebook's reading position — page, viewport, tool and PDF query — so
     * reopening it later lands where the user left off, even after its tab is gone.
     * The live page and query win for the open notebook; closed tabs persist as stored.
     */
    private fun persistPosition(notebookId: String) {
        val snapshot = _state.value
        val tab = snapshot.tabs.find { it.notebookId == notebookId } ?: return
        val livePageId = if (notebookId == snapshot.activeId) snapshot.page?.id ?: tab.currentPageId else tab.currentPageId
        val liveQuery = if (notebookId == snapshot.activeId) snapshot.pdfSearch.query else tab.search.query
        val position = NotebookPosition(livePageId, tab.viewport, tab.tool, liveQuery)
        positionPrefs.edit().putString(notebookId, NotebookPositionCodec.encode(position)).apply()
    }

    /** The last saved position for [note], or null when it was never opened or is stale. */
    private fun rememberedTab(note: Notebook): EditorTab? {
        val position = NotebookPositionCodec.decode(positionPrefs.getString(note.id, null))
            ?: return null
        val pageId = position.pageId.takeIf { id -> note.pages.any { it.id == id } }
            ?: note.pages.firstOrNull()?.id ?: return null
        return position.copy(pageId = pageId).toTab(note.id, note.title)
    }

    /** Drops saved positions for notebooks that no longer exist, so the store never grows stale. */
    private fun prunePositions(notes: List<Notebook>) {
        val alive = notes.map { it.id }.toSet()
        val stale = positionPrefs.all.keys.filter { it !in alive }
        if (stale.isEmpty()) return
        positionPrefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    fun updateTabViewport(id: String, viewport: WorkspaceViewport, tool: Tool) {
        _state.update { state -> state.copy(tabs = state.tabs.map {
            if (it.id == id) it.copy(viewport = viewport, tool = tool) else it
        }) }
        _state.value.tabs.find { it.id == id }?.let { persistPosition(it.notebookId) }
    }

    fun open(id: String) {
        val target = _state.value.notes.find { it.id == id } ?: return
        captureTab()
        val tab = _state.value.tabs.find { it.notebookId == id }
            ?: rememberedTab(target)
            ?: EditorTab(id, id, target.pages.first().id, target.title)
        selectNotebookTimer(id)
        _state.update { it.copy(activeId = id,
            tabs = it.tabs.withTab(tab),
            pageIndex = target.pages.indexOfFirst { p -> p.id == tab.currentPageId }.coerceAtLeast(0),
            pdfSearch = tab.search) }
        historyState()
        _state.value.page?.let { loadPage(it.id) }
        if (tab.search.query.isNotBlank() && (tab.search.searching || !tab.search.searched)) searchPdf(tab.search.query)
    }

    /** All deep links share the same tab-opening path. */
    fun openAt(id: String, index: Int) {
        open(id)
        if (_state.value.activeId == id) {
            selectPage(index)
            captureTab()
            _state.update { state -> state.copy(tabs = state.tabs.map { if (it.notebookId == id) it.copy(viewport = it.viewport.copy(scrollOffset = 0)) else it }) }
            persistPosition(id)
            _state.update { it.copy(navigationRequest = it.navigationRequest + 1) }
        }
    }

    /** Return to the Library without discarding open documents. */
    fun close() {
        captureTab()
        selectNotebookTimer(null)
        _state.update { it.copy(activeId = null, pdfSearch = PdfSearchState()) }
    }

    fun closeTab(id: String) {
        captureTab()
        // A barrier in the existing writer drains every pending save before removing the tab.
        enqueue {
            withContext(Dispatchers.Main.immediate) {
                if (_state.value.saveFailed) {
                    reportError("Couldn't close the document. Retry save first.")
                } else {
                    val state = _state.value
                    val adjacent = state.tabs.adjacentAfterClosing(id)
                    val remaining = state.tabs.filterNot { it.id == id }
                    _state.update { it.copy(tabs = remaining,
                        companion = it.companion?.takeUnless { pane -> pane.notebookId == id }) }
                    if (state.activeId == id) {
                        selectNotebookTimer(null)
                        _state.update { it.copy(activeId = null) }
                        adjacent?.let { open(it.notebookId) }
                    }
                }
            }
        }
    }

    fun showCompanion(id: String, mode: CompanionMode) {
        captureTab()
        val note = _state.value.notes.find { it.id == id } ?: return
        val source = _state.value.tabs.find { it.notebookId == id }
            ?: rememberedTab(note)
            ?: EditorTab(id, id, note.pages.first().id, note.title)
        _state.update { it.copy(companion = source.copy(id = "companion"), companionMode = mode, editorOnRight = false,
            tabs = if (mode == CompanionMode.SPLIT) it.tabs.withTab(source) else it.tabs) }
        loadPage(source.currentPageId)
    }

    fun dismissCompanion() { _state.update { it.copy(companion = null, editorOnRight = false) } }

    /** Dragged divider position, as the editor's share; snapped by the caller on release. */
    fun setSplitFraction(fraction: Float) {
        val next = SplitPanes.coerce(fraction)
        prefs.edit().putFloat(AppPrefs.SPLIT_FRACTION, next).apply()
        _state.update { if (it.splitFraction == next) it else it.copy(splitFraction = next) }
    }

    /** Linked reference: turning the editor's page also turns the companion. */
    fun setCompanionLinked(linked: Boolean) {
        _state.update { if (it.companionLinked == linked) it else it.copy(companionLinked = linked) }
    }

    /** Switches the companion between an editable split and a read-only reference. */
    fun setCompanionMode(mode: CompanionMode) {
        val current = _state.value
        if (current.companionMode == mode) return
        captureTab()
        _state.update { it.copy(companionMode = mode,
            tabs = if (mode == CompanionMode.SPLIT && it.companion != null) it.tabs.withTab(it.companion.copy(id = it.companion.notebookId)) else it.tabs) }
    }

    /** Swaps which side the editor sits on, keeping both documents where they are. */
    fun swapPaneSides() {
        if (_state.value.companion == null) return
        _state.update { it.copy(editorOnRight = !it.editorOnRight) }
    }

    fun companionPage(index: Int) {
        val pane = _state.value.companion ?: return
        val note = _state.value.notes.find { it.id == pane.notebookId } ?: return
        val page = note.pages.getOrNull(index) ?: return
        _state.update { it.copy(companion = pane.copy(currentPageId = page.id, viewport = WorkspaceViewport())) }
        loadPage(page.id)
    }

    fun updateCompanionViewport(viewport: WorkspaceViewport) {
        _state.update { it.copy(companion = it.companion?.copy(viewport = viewport)) }
    }

    fun swapCompanion() {
        captureTab()
        val pane = _state.value.companion ?: return
        val active = _state.value.tabs.find { it.notebookId == _state.value.activeId } ?: return
        _state.update { it.copy(companion = active.copy(id = "companion"),
            editorOnRight = !it.editorOnRight,
            tabs = it.tabs.withTab(pane.copy(id = pane.notebookId))) }
        // Avoid capturing the old active page over the destination (same-notebook split).
        selectNotebookTimer(null)
        _state.update { it.copy(activeId = null) }
        open(pane.notebookId)
        _state.update { it.copy(navigationRequest = it.navigationRequest + 1) }
    }
    fun rename(note: Notebook, title: String) { if (title.isNotBlank()) updateNote(note.copy(title = title.trim())) }
    fun star(note: Notebook) = updateNote(note.copy(starred = !note.starred))
    fun move(note: Notebook, folderId: String?) = updateNote(note.copy(folderId = folderId))
    /** Switches one notebook between its first page and the decorative default cover. */
    fun setPageCover(note: Notebook, pageCover: Boolean) {
        if (note.pageCover == pageCover) return
        updateNote(note.copy(pageCover = pageCover))
    }

    // ---- Exam metadata --------------------------------------------------------------------

    /** Rewrites one notebook's exam tags, keeping the rest of the notebook untouched. */
    fun updateExamTags(noteId: String, tags: ExamTags) {
        val note = _state.value.notes.find { it.id == noteId } ?: return
        updateNote(note.copy(exam = tags))
    }

    fun toggleExamTag(noteId: String, tag: ExamTagType) {
        val note = _state.value.notes.find { it.id == noteId } ?: return
        val tags = note.exam
        val updated = if (tag in tags.tags) tags.copy(tags = tags.tags - tag) else tags.copy(tags = tags.tags + tag)
        updateNote(note.copy(exam = updated))
    }

    fun setExamFilter(filter: ExamFilter) { _state.update { it.copy(examFilter = filter) } }

    /** Flips the open page's redo flag — a question worth another attempt before the exam. */
    fun toggleRedoFlag() {
        val page = _state.value.page ?: return
        if (!page.loaded) return
        replacePage(page.copy(redoFlag = !page.redoFlag))
    }

    /**
     * Records a marked sitting on a notebook, newest last; a first mark also marks it sat.
     * The paper's total is filled in from the attempt when it is still unknown, so the next
     * mark dialog opens pre-filled instead of asking for the total again.
     */
    fun recordAttempt(noteId: String, attempt: ExamAttempt) {
        val note = _state.value.notes.find { it.id == noteId } ?: return
        var exam = if (note.exam.status == ExamStatus.TO_DO) note.exam.copy(status = ExamStatus.MARKED) else note.exam
        if (exam.marksTotal == null && attempt.total != null && attempt.total > 0) {
            exam = exam.copy(marksTotal = attempt.total)
        }
        updateNote(note.withAttempt(attempt).copy(exam = exam))
        // A timed sitting is spent on the mark it belongs to, not offered to the next one.
        if (attempt.timed) {
            notebookSittings.consumeResult(noteId)
            if (_state.value.activeId == noteId) _state.update { it.copy(lastTimedSeconds = null) }
        }
    }

    /** Removes one recorded sitting from a notebook's history. */
    fun deleteAttempt(noteId: String, attemptId: String) {
        val note = _state.value.notes.find { it.id == noteId } ?: return
        updateNote(note.copy(attempts = note.attempts.filterNot { it.id == attemptId }))
    }
    fun moveNotebooks(ids: Set<String>, folderId: String?) {
        if (folderId != null && _state.value.folders.none { it.id == folderId }) return
        val affected = _state.value.notes.filter { it.id in ids && it.folderId != folderId }.map { it.id }.toSet()
        if (affected.isNotEmpty()) updateNotes(affected) { it.copy(folderId = folderId) }
    }
    fun favoriteNotebooks(ids: Set<String>, starred: Boolean) {
        val affected = _state.value.notes.filter { it.id in ids && it.starred != starred }.map { it.id }.toSet()
        if (affected.isNotEmpty()) updateNotes(affected) { it.copy(starred = starred) }
    }
    /** Shows the same cover on every selected notebook: first page or the default cover. */
    fun setPageCoverBatch(ids: Set<String>, pageCover: Boolean) {
        val affected = _state.value.notes.filter { it.id in ids && it.pageCover != pageCover }.map { it.id }.toSet()
        if (affected.isNotEmpty()) updateNotes(affected) { it.copy(pageCover = pageCover) }
    }
    /**
     * Rewrites the exam tags of every selected notebook through [transform], keeping each
     * notebook's other fields untouched. A notebook whose tags come back unchanged is left alone,
     * so batch-assigning the year does not rewrite notebooks that already carry it.
     */
    fun updateExamTagsBatch(ids: Set<String>, transform: (ExamTags) -> ExamTags) {
        if (ids.isEmpty()) return
        // Single state pass; only notebooks whose tags actually change are rewritten + saved.
        val now = System.currentTimeMillis()
        var changed: List<Notebook> = emptyList()
        _state.update { state ->
            val updated = state.notes.map { note ->
                if (note.id !in ids) note
                else {
                    val tags = transform(note.exam)
                    if (tags == note.exam) note else note.copy(exam = tags, updated = now)
                }
            }
            changed = updated.filter { next ->
                next.id in ids && state.notes.find { it.id == next.id } != next
            }
            state.copy(notes = updated)
        }
        if (changed.isEmpty()) return
        enqueue { changed.forEach { repository.saveMeta(it) } }
    }
    /**
     * Copies a notebook beside itself ("Copy of X") with its pages, pictures and imported
     * PDF. The copy starts unstarred and unmarked so progress is never double-counted.
     */
    fun duplicateNotebook(note: Notebook) {
        viewModelScope.launch {
            try {
                ready.await()
                val live = _state.value.notes.find { it.id == note.id } ?: return@launch
                val title = duplicateNotebookTitle(live.title, _state.value.notes.map { it.title }.toSet())
                val copy = repository.duplicateNotebook(live, title)
                _state.update { it.copy(notes = it.notes + copy) }
            } catch (e: Exception) {
                reportError("Couldn't duplicate notebook: ${e.message}")
            }
        }
    }
    fun delete(note: Notebook) {
        if (_state.value.activeId == note.id) selectNotebookTimer(null)
        _state.update {
            val closing = it.activeId == note.id
            it.copy(
                notes = it.notes.filterNot { n -> n.id == note.id },
                tabs = it.tabs.filterNot { t -> t.notebookId == note.id },
                companion = it.companion?.takeUnless { p -> p.notebookId == note.id },
                activeId = if (closing) null else it.activeId,
                pdfSearch = if (closing) PdfSearchState() else it.pdfSearch
            )
        }
        positionPrefs.edit().remove(note.id).apply()
        thumbnails.clear(note.id)
        enqueue {
            try { repository.delete(note.id) }
            catch (e: Exception) { _state.update { it.copy(notes = it.notes + note) }; throw e }
        }
    }
    /** Deletes every selected notebook in one step; the open notebook closes when it is removed. */
    fun deleteNotebooks(ids: Set<String>) {
        if (ids.isEmpty()) return
        val removed = _state.value.notes.filter { it.id in ids }
        if (removed.isEmpty()) return
        if (_state.value.activeId in ids) selectNotebookTimer(null)
        _state.update { state ->
            val closing = state.activeId in ids
            state.copy(
                notes = state.notes.filterNot { it.id in ids },
                tabs = state.tabs.filterNot { it.notebookId in ids },
                companion = state.companion?.takeUnless { it.notebookId in ids },
                activeId = state.activeId?.takeIf { it !in ids },
                pdfSearch = if (closing) PdfSearchState() else state.pdfSearch
            )
        }
        removed.forEach { thumbnails.clear(it.id) }
        positionPrefs.edit().apply { removed.forEach { remove(it.id) } }.apply()
        enqueue {
            val failed = mutableListOf<Notebook>()
            removed.forEach {
                try { repository.delete(it.id) }
                catch (_: Exception) { failed += it }
            }
            if (failed.isNotEmpty()) {
                _state.update { state -> state.copy(notes = state.notes + failed) }
                throw IllegalStateException("Couldn't delete ${failed.size} notebook${if (failed.size == 1) "" else "s"}")
            }
        }
    }
    fun selectPage(index: Int) {
        val note = _state.value.active ?: return
        val target = index.coerceIn(0, note.pages.lastIndex)
        // The page turn and the tab capture that follows it share one state pass. As two updates
        // they recomposed the whole editor twice per page, and a fling pays that on every page it
        // crosses. An unchanged state is not emitted at all, so a repeat selection costs nothing.
        _state.update { state ->
            var next = captureTabInto(state.copy(pageIndex = target))
            // Linked reference: the companion follows the editor's page turn.
            val pane = next.companion
            if (next.companionLinked && pane != null) {
                val companionNote = next.notes.find { it.id == pane.notebookId }
                val pageId = if (companionNote != null) linkedCompanionTarget(note, companionNote, target) else null
                if (pageId != null && pageId != pane.currentPageId) {
                    next = next.copy(companion = pane.copy(currentPageId = pageId, viewport = WorkspaceViewport()))
                }
            }
            next
        }
        persistPosition(note.id)
        historyState()
        _state.value.page?.let { loadPage(it.id) }
        _state.value.companion?.let { loadPage(it.currentPageId) }
    }
    /**
     * Brings one page's ink and text into memory. The page is left untouched while the read is in
     * flight, and a page loaded or edited in the meantime wins, so a slow read of an older copy can
     * never overwrite what the user has since written.
     */
    fun loadPage(pageId: String) {
        val note = _state.value.notes.find { note -> note.pages.any { it.id == pageId } } ?: return
        val summary = note.pages.find { it.id == pageId } ?: return
        if (summary.loaded || !loadingPages.add(pageId)) return
        viewModelScope.launch {
            try {
                val loaded = repository.loadPage(note.id, summary)
                _state.update { state ->
                    val current = state.notes.find { it.id == note.id }
                    val target = current?.pages?.find { it.id == pageId }
                    if (current == null || current.id != note.id || target == null || target.loaded) state
                    else state.copy(notes = state.notes.map { if (it.id == current.id) current.withPage(target.withLoadedContent(loaded)) else it })
                }
            } catch (e: Exception) {
                reportError("Couldn't open this page: ${e.message.orEmpty()}")
            } finally { loadingPages.remove(pageId) }
        }
    }
    fun addPage(paper: Paper? = null) {
        val note = _state.value.active ?: return
        // Inherit the current page's paper — keeps practice flowing.
        val chosen = paper ?: _state.value.page?.paper ?: Paper.MATH_GRID
        updateNote(note.copy(pages = note.pages + NotePage(paper = chosen, infinite = _state.value.page?.infinite == true))); selectPage(note.pages.size)
    }
    /**
     * Copies a page, reading its ink from disk first when only its summary is in memory. A copy of a
     * page that still had to be read finishes asynchronously and reports no index to scroll to.
     */
    fun duplicatePage(index: Int = _state.value.pageIndex): Int? {
        val state = _state.value
        val note = state.active ?: return null
        if (index !in note.pages.indices) return null
        val source = note.pages[index]
        if (source.loaded) return duplicateLoaded(note, index)
        viewModelScope.launch {
            try {
                val loaded = repository.loadPage(note.id, source)
                val current = _state.value.active ?: return@launch
                val at = current.pages.indexOfFirst { it.id == source.id }
                if (current.id != note.id || at < 0) return@launch
                val duplicated = current.withPage(if (current.pages[at].loaded) current.pages[at] else current.pages[at].withLoadedContent(loaded)).withDuplicatedPage(at).copy(updated = System.currentTimeMillis())
                _state.update { s -> s.copy(notes = s.notes.map { if (it.id == current.id) duplicated else it }, pageIndex = at + 1) }
                captureTab()
                enqueue { repository.savePage(duplicated, duplicated.pages[at + 1]) }
            } catch (e: Exception) { reportError("Couldn't duplicate this page: ${e.message.orEmpty()}") }
        }
        return null
    }
    private fun duplicateLoaded(note: Notebook, index: Int): Int {
        val at = index + 1
        val duplicated = note.withDuplicatedPage(index).copy(updated = System.currentTimeMillis())
        _state.update { state -> state.copy(notes = state.notes.map { if (it.id == duplicated.id) duplicated else it }, pageIndex = at) }
        captureTab()
        // The copy is a page of its own, so its content has to reach disk along with the index.
        enqueue { repository.savePage(duplicated, duplicated.pages[at]) }
        return at
    }
    /** Inserts a blank page at [index] and opens it, returning where it landed. */
    fun insertPage(index: Int, paper: Paper? = null): Int {
        val state = _state.value
        val note = state.active ?: return state.pageIndex
        val chosen = paper ?: state.page?.paper ?: Paper.MATH_GRID
        val at = index.coerceIn(0, note.pages.size)
        val updated = note.withInsertedPage(at, NotePage(paper = chosen, infinite = _state.value.page?.infinite == true))
        updateNote(updated)
        _state.update { it.copy(pageIndex = at) }
        captureTab()
        return at
    }
    /** Removes a page, keeping the open page in view and leaving at least one page behind. */
    fun deletePage(index: Int) {
        val state = _state.value
        val note = state.active ?: return
        if (index !in note.pages.indices) return
        val removed = note.pages[index]
        val updated = note.withDeletedPage(index)
        updateNote(updated)
        enqueue { repository.deletePage(note.id, removed.id) }
        val current = if (index < state.pageIndex) state.pageIndex - 1 else state.pageIndex
        _state.update { it.copy(pageIndex = current.coerceIn(0, updated.pages.lastIndex)) }
        captureTab()
        historyState()
    }
    /** Reorders a page, keeping the page you were reading on screen. */
    fun movePage(from: Int, to: Int) {
        val state = _state.value
        val note = state.active ?: return
        val updated = note.withMovedPage(from, to)
        if (updated == note) return
        updateNote(updated)
        _state.update { it.copy(pageIndex = movedPageIndex(state.pageIndex, from, to.coerceIn(0, note.pages.lastIndex))) }
        captureTab()
        historyState()
    }
    /** Organisation lives in the index, so even an unloaded page can be named or bookmarked. */
    fun renamePage(pageId: String, title: String) {
        val note = _state.value.active ?: return
        val page = note.pages.find { it.id == pageId } ?: return
        updateNote(note.withPage(page.copy(title = title.trim().take(120))))
    }

    fun setPeekAnchor(anchor: PeekAnchor?) {
        val note = _state.value.active ?: return
        updateNote(note.withSharedPeekAnchor(anchor))
    }

    fun togglePageBookmark(pageId: String) {
        val note = _state.value.active ?: return
        val page = note.pages.find { it.id == pageId } ?: return
        updateNote(note.withPage(page.copy(bookmarked = !page.bookmarked)))
    }

    fun setPaper(paper: Paper) { val p = _state.value.page ?: return; replacePage(p.copy(paper = paper)) }
    /** Stores a page's new content, bumping its revision so caches and exports know it changed. */
    private fun replacePage(page: NotePage) {
        val note = _state.value.notes.find { note -> note.pages.any { it.id == page.id } } ?: return
        // A page still on disk is never rewritten from an empty in-memory copy.
        if (!page.loaded) return
        val revised = page.revised()
        val updated = note.copy(pages = note.pages.map { if (it.id == page.id) revised else it }, updated = System.currentTimeMillis())
        _state.update { state -> state.copy(notes = state.notes.map { if (it.id == updated.id) updated else it }) }
        enqueue { repository.savePage(updated, revised) }
    }
    fun strokes(strokes: List<Stroke>) {
        val page = _state.value.page ?: return
        strokes(page.id, strokes)
    }
    fun strokes(pageId: String, strokes: List<Stroke>) {
        val page = findPageContent(pageId) ?: return
        // Ink cannot be changed on a page whose own ink has not been read yet.
        // Reference check first avoids a deep walk over every InkPoint for identical lists.
        if (!page.loaded || page.strokes === strokes || page.strokes == strokes) return
        record(page); replacePage(page.copy(strokes = strokes)); historyState()
    }
    fun texts(texts: List<TextBox>) {
        val page = _state.value.page ?: return
        texts(page.id, texts)
    }
    fun texts(pageId: String, texts: List<TextBox>) {
        val page = findPageContent(pageId) ?: return
        if (!page.loaded || page.texts === texts || page.texts == texts) return
        record(page); replacePage(page.copy(texts = texts)); historyState()
    }
    fun addText(box: TextBox) { val page = _state.value.page ?: return; texts(page.id, page.texts + box) }
    fun updateText(box: TextBox) { val page = _state.value.page ?: return; texts(page.id, page.texts.map { if (it.id == box.id) box else it }) }
    fun removeText(id: String) { val page = _state.value.page ?: return; texts(page.id, page.texts.filterNot { it.id == id }) }
    /** Wipes the open page's ink, text and pictures in one undoable step, leaving its paper or PDF in place. */
    fun clearPage() {
        val page = _state.value.page ?: return
        if (!page.loaded || (page.strokes.isEmpty() && page.texts.isEmpty() && page.images.isEmpty())) return
        record(page); replacePage(page.copy(strokes = emptyList(), texts = emptyList(), images = emptyList())); historyState()
    }

    // ---- Placed images ------------------------------------------------------------------

    fun images(pageId: String, images: List<PageImage>) {
        val page = findPageContent(pageId) ?: return
        // Pictures cannot be changed on a page whose own content has not been read yet.
        if (!page.loaded || page.images === images || page.images == images) return
        record(page); replacePage(page.copy(images = images)); historyState()
    }

    /** Adds a picture whose bytes are already on disk, or stores [bytes] first when provided. */
    fun addImage(image: PageImage, bytes: ByteArray? = null, pageId: String? = _state.value.page?.id) {
        val state = _state.value
        val note = state.notes.find { note -> note.pages.any { it.id == pageId } } ?: return
        val page = note.pages.find { it.id == pageId } ?: return
        if (!page.loaded) return
        record(page)
        val updated = note.copy(
            pages = note.pages.map { if (it.id == page.id) it.revised().copy(images = it.images + image) else it },
            updated = System.currentTimeMillis()
        )
        val revised = updated.pages.first { it.id == page.id }
        _state.update { current ->
            current.copy(notes = current.notes.map { if (it.id == updated.id) updated else it })
        }
        historyState()
        enqueue {
            if (bytes != null) repository.saveImage(note.id, image.id, bytes)
            repository.savePage(updated, revised)
        }
    }

    fun updateImage(image: PageImage) {
        val page = _state.value.page ?: return
        images(page.id, page.images.map { if (it.id == image.id) image else it })
    }

    fun removeImage(id: String) {
        val page = _state.value.page ?: return
        images(page.id, page.images.filterNot { it.id == id })
    }

    /** Lifts a picture above the others without touching its place or size. */
    fun bringImageToFront(id: String) {
        val page = _state.value.page ?: return
        val target = page.images.find { it.id == id } ?: return
        images(page.id, page.images.filterNot { it.id == id } + target)
    }

    /** Drops a picture behind the others without touching its place or size. */
    fun sendImageToBack(id: String) {
        val page = _state.value.page ?: return
        val target = page.images.find { it.id == id } ?: return
        images(page.id, listOf(target) + page.images.filterNot { it.id == id })
    }
    /**
     * Stores one page's new ink, text and pictures together, bumping its revision so caches and
     * exports know it changed. A single record covers all three, so a lasso drag that moved ink,
     * text and pictures undoes in one step.
     */
    fun updateContent(pageId: String, strokes: List<Stroke>, texts: List<TextBox>, images: List<PageImage>) {
        val page = findPageContent(pageId) ?: return
        if (!page.loaded) return
        if (page.strokes === strokes && page.texts === texts && page.images === images) return
        if (page.strokes == strokes && page.texts == texts && page.images == images) return
        record(page); replacePage(page.copy(strokes = strokes, texts = texts, images = images)); historyState()
    }
    /** Remembers a lasso selection for pasting, on this page or another one. */
    fun copyToClipboard(selection: CanvasSelection) {
        if (selection.isEmpty()) return
        pasteGeneration = 0
        _state.update { it.copy(clipboard = selection) }
    }
    /** Copies the selection to the clipboard and takes it off the current page in one step. */
    fun cutSelection(selection: CanvasSelection) {
        if (selection.isEmpty()) return
        copyToClipboard(selection)
        val page = _state.value.page ?: return
        val doomed = identitySet(selection.strokes)
        updateContent(
            page.id,
            page.strokes.filterNot { it in doomed || it in selection.strokes },
            page.texts.filterNot { box -> selection.texts.any { it.id == box.id } },
            page.images.filterNot { image -> selection.images.any { it.id == image.id } }
        )
    }
    /** Deletes a lasso selection — ink, text and pictures — in one undoable step. */
    fun deleteSelection(selection: CanvasSelection) {
        if (selection.isEmpty()) return
        val page = _state.value.page ?: return
        val doomed = identitySet(selection.strokes)
        // Fast path: reference check first, structural fallback for reloaded pages.
        val remaining = page.strokes.filterNot { it in doomed || it in selection.strokes }
        updateContent(
            page.id,
            remaining,
            page.texts.filterNot { box -> selection.texts.any { it.id == box.id } },
            page.images.filterNot { image -> selection.images.any { it.id == image.id } }
        )
    }
    /**
     * Duplicates a lasso selection in place: the originals stay where they are and editable
     * copies land nudged along so they never hide under their source. Texts get fresh ids;
     * pictures get fresh ids with their bytes copied, since two placements on one page must
     * never share an id. One undoable step.
     */
    fun duplicateSelection(selection: CanvasSelection) {
        if (selection.isEmpty()) return
        val note = _state.value.active ?: return
        val page = _state.value.page ?: return
        if (!page.loaded) return
        copyToClipboard(selection)
        val offset = 18f
        val movedStrokes = selection.strokes.map { InkGeometry.translate(it, offset, offset) }
        val movedTexts = selection.texts.map { it.copy(id = UUID.randomUUID().toString()).moved(offset, offset) }
        viewModelScope.launch {
            val movedImages = selection.images.map { image ->
                val placed = image.moved(offset, offset)
                val freshId = UUID.randomUUID().toString()
                try {
                    repository.loadImageBytes(note.id, image.id)?.let { repository.saveImage(note.id, freshId, it) }
                } catch (_: Exception) { }
                placed.copy(id = freshId)
            }
            updateContent(
                page.id,
                page.strokes + movedStrokes,
                page.texts + movedTexts,
                page.images + movedImages
            )
        }
    }
    /**
     * Appends the clipboard to the open page, nudged along so a paste never hides under its
     * source. Texts paste with fresh ids; pictures paste with fresh ids (and copied bytes) when
     * the page already uses their id, otherwise they share the notebook-scoped image file like a
     * duplicated page does. One undoable step.
     */
    fun pasteClipboard() {
        val note = _state.value.active ?: return
        val page = _state.value.page ?: return
        if (!page.loaded) return
        val clip = _state.value.clipboard
        if (clip.isEmpty()) return
        val offset = PASTE_OFFSET * ++pasteGeneration
        val pastedStrokes = clip.strokes.map { InkGeometry.translate(it, offset, offset) }
        val pastedTexts = clip.texts.map { it.copy(id = UUID.randomUUID().toString()).moved(offset, offset) }
        viewModelScope.launch {
            val existingIds = page.images.map { it.id }.toSet()
            val pastedImages = clip.images.map { image ->
                val placed = image.moved(offset, offset)
                if (placed.id !in existingIds) return@map placed
                val freshId = UUID.randomUUID().toString()
                try {
                    repository.loadImageBytes(note.id, image.id)?.let { repository.saveImage(note.id, freshId, it) }
                } catch (_: Exception) { }
                placed.copy(id = freshId)
            }
            updateContent(
                page.id,
                page.strokes + pastedStrokes,
                page.texts + pastedTexts,
                page.images + pastedImages
            )
        }
    }
    /** Applies a new look to the selection as one undoable step; nulls leave those properties alone. */
    fun restyleSelection(strokes: List<Stroke>, color: Int?, widthScale: Float?, opacity: Float?, style: StrokeStyle? = null) {
        if (strokes.isEmpty()) return
        val page = _state.value.page ?: return
        val restyled = InkGeometry.restyle(strokes, color, widthScale, opacity, style)
        if (restyled == strokes) return
        val doomed = identitySet(strokes)
        this.strokes(page.id, page.strokes.filterNot { it in doomed || it in strokes } + restyled)
    }

    /**
     * Inserts a reusable diagram element (arrow, star, checkbox…) as ordinary editable ink,
     * centred on the open page like GoodNotes' Elements. One undoable step.
     */
    fun insertStamp(kind: InkStamps.Kind, color: Int? = null, width: Float? = null) {
        val page = _state.value.page ?: return
        if (!page.loaded) return
        val cx = if (page.infinite) 0f else page.width / 2f
        val cy = if (page.infinite) 0f else page.height / 2f
        val inkColor = color ?: 0xFF303431.toInt()
        val inkWidth = width ?: 2.2f
        val stamp = InkStamps.make(kind, cx, cy, color = inkColor, width = inkWidth)
        // Nudge stamps stacked on the same centre so repeated inserts never hide under each other.
        val offset = (page.strokes.size % 5) * 14f
        val placed = if (offset == 0f) stamp else stamp.map { InkGeometry.translate(it, offset, offset) }
        strokes(page.id, page.strokes + placed)
    }
    fun undo() = history(undo, redo)
    fun redo() = history(redo, undo)

    // ---- Exam timer -----------------------------------------------------------------------

    /**
     * The running sitting is saved to preferences the moment it starts, so a process death — the
     * app swiped away, a crash, the system reclaiming memory — never ends an exam. Every timer
     * state is derived from the start moment, so restoring is only a matter of keeping the preset
     * and the start time; the record is cleared when the sitting stops. [lastSeen] is the last
     * moment the user was confirmed to be looking at the pages; the editor refreshes it while the
     * timer runs, so a death with no recorded pause can still cut the unseen gap instead of
     * counting it.
     */
    private fun saveSitting(timer: ExamTimerState, lastSeen: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(timerKey(TIMER_START_KEY), timer.startedAt ?: 0L)
            .putLong(timerKey(TIMER_PAUSED_AT_KEY), timer.pausedAt ?: 0L)
            .putLong(timerKey(TIMER_PAUSED_MILLIS_KEY), timer.pausedMillis)
            .putLong(timerKey(TIMER_LAST_SEEN_KEY), lastSeen)
            .putInt(timerKey(TIMER_WRITING_KEY), timer.preset.writingSeconds)
            .putInt(timerKey(TIMER_READING_KEY), timer.preset.readingSeconds)
            .putString(timerKey(TIMER_LABEL_KEY), timer.preset.label)
            .apply()
    }

    private fun clearSitting() {
        prefs.edit().remove(timerKey(TIMER_START_KEY)).remove(timerKey(TIMER_WRITING_KEY))
            .remove(timerKey(TIMER_PAUSED_AT_KEY)).remove(timerKey(TIMER_PAUSED_MILLIS_KEY))
            .remove(timerKey(TIMER_LAST_SEEN_KEY))
            .remove(timerKey(TIMER_READING_KEY)).remove(timerKey(TIMER_LABEL_KEY))
            .remove(timerKey(TIMER_VISITS_KEY)).apply()
    }

    /** The preset of a saved sitting, or null when none was running when the app last stopped. */
    private fun storedSitting(): ExamTimerPreset? {
        if (prefs.getLong(timerKey(TIMER_START_KEY), 0L) <= 0L) return null
        return ExamTimerPreset(
            prefs.getString(timerKey(TIMER_LABEL_KEY), "Exam") ?: "Exam",
            prefs.getInt(timerKey(TIMER_WRITING_KEY), 90 * 60),
            prefs.getInt(timerKey(TIMER_READING_KEY), 15 * 60)
        )
    }

    /**
     * Advances the countdown by however long has passed. The editor ticks once a second, so a
     * no-op tick must not emit a new state: otherwise every open editor recomposes every second
     * even with no timer running. While the timer runs, the tick also refreshes the last-seen
     * heartbeat (throttled — disk writes stay far below the tick rate) so a crash with no
     * recorded pause restores parked at the last visible moment instead of counting the gap.
     */
    fun tickTimer(now: Long = System.currentTimeMillis()) {
        val current = _state.value.timer
        val next = current.tick(now)
        if (next != current) _state.update { it.copy(timer = next) }
        if (next.running && now - lastSeenPersistedAt >= LAST_SEEN_THROTTLE_MS) {
            lastSeenPersistedAt = now
            saveSitting(next, lastSeen = now)
        }
    }
    /**
     * Parks the clock the moment the user stops looking at the pages — app backgrounded, screen
     * off, or the editor left for the library or mistakes. Parked time never counts, the parked
     * state is saved immediately so a kill still restores it parked, and it stays parked until
     * the user explicitly resumes it. A timer that is not running is untouched.
     */
    fun autoPauseTimer(now: Long = System.currentTimeMillis()) {
        if (_state.value.active == null) return
        val current = _state.value.timer
        if (!current.running) return
        val parked = current.pause(now)
        if (parked == current) return
        saveSitting(parked, lastSeen = now)
        _state.update { it.copy(timer = parked) }
    }
    fun startTimer(preset: ExamTimerPreset) {
        if (_state.value.active == null) return
        val started = ExamTimerState().start(preset)
        saveSitting(started)
        _state.update { it.copy(timer = started) }
    }
    fun adjustTimer(seconds: Int) {
        if (_state.value.active == null) return
        val adjusted = _state.value.timer.adjust(seconds)
        saveSitting(adjusted)
        _state.update { it.copy(timer = adjusted) }
    }
    fun toggleTimerPause() {
        if (_state.value.active == null) return
        val current = _state.value.timer
        val now = System.currentTimeMillis()
        val updated = if (current.paused) current.unpause(now) else current.pause(now)
        if (updated == current) return
        saveSitting(updated)
        _state.update { it.copy(timer = updated) }
    }
    fun skipTimerPhase() {
        if (_state.value.active == null) return
        val skipped = _state.value.timer.skip()
        saveSitting(skipped)
        _state.update { it.copy(timer = skipped) }
    }
    /** Stops the timer, keeping how long the writing phase ran for the attempt record. */
    fun stopTimer() {
        if (_state.value.active == null) return
        val current = _state.value.timer
        val now = System.currentTimeMillis()
        val spent = current.elapsedWriting(now).takeIf { current.startedAt != null && it > 0 }
        clearSitting()
        _state.update { it.copy(timer = current.stop(), lastTimedSeconds = spent ?: it.lastTimedSeconds) }
    }
    private fun record(page: NotePage) {
        undo.getOrPut(page.id) { mutableListOf() }.apply { add(Triple(page.strokes, page.texts, page.images)); if (size > 60) removeAt(0) }
        redo.remove(page.id)
    }
    private fun history(from: MutableMap<String, MutableList<PageContent>>, to: MutableMap<String, MutableList<PageContent>>) {
        val page = _state.value.page ?: return
        if (!page.loaded) return
        val previous = from[page.id]?.removeLastOrNull() ?: return
        to.getOrPut(page.id) { mutableListOf() }.add(Triple(page.strokes, page.texts, page.images))
        replacePage(page.copy(strokes = previous.first, texts = previous.second, images = previous.third)); historyState()
    }
    private fun historyState() { _state.update { it.copy(canUndo = !undo[it.page?.id].isNullOrEmpty(), canRedo = !redo[it.page?.id].isNullOrEmpty()) } }
    fun retrySave() {
        val snapshot = _state.value
        enqueue {
            snapshot.notes.forEach { repository.saveAll(it) }; repository.saveFolders(snapshot.folders)
            _state.update { it.copy(saveFailed = false) }
        }
    }
    /** Searches the open notebook's imported PDF text; a blank query clears the results. */
    fun searchPdf(query: String) {
        val note = _state.value.active ?: return
        if (query.isBlank()) { _state.update { it.copy(pdfSearch = PdfSearchState()) }; captureTab(); return }
        val noteId = note.id
        _state.update { it.copy(pdfSearch = PdfSearchState(query = query, searching = true, searched = true)) }
        captureTab()
        viewModelScope.launch {
            val pages = try { repository.pdfPageTexts(noteId) } catch (_: Exception) { emptyList() }
            // Matching is CPU-bound; keep it off the main thread.
            val hits = withContext(Dispatchers.Default) { PdfSearch.search(pages, query) }
            // A search finishing after its notebook closed belongs nowhere.
            if (_state.value.activeId != noteId || _state.value.pdfSearch.query != query) return@launch
            _state.update { it.copy(pdfSearch = PdfSearchState(query = query, searched = true, results = hits)) }
            captureTab()
        }
    }
    fun clearPdfSearch() { _state.update { it.copy(pdfSearch = PdfSearchState()) }; captureTab() }
    fun preparePdfImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(pendingPdfImports = (it.pendingPdfImports + uris).distinct()) }
    }
    fun cancelPdfImport() { _state.update { it.copy(pendingPdfImports = emptyList()) } }

    fun importPdfs(folderId: String?) {
        val request = _state.value
        if (request.busy || request.loading || request.loadFailed || request.pendingPdfImports.isEmpty()) return
        if (folderId != null && request.folders.none { it.id == folderId }) return
        val uris = request.pendingPdfImports
        _state.update { it.copy(busy = true, pendingPdfImports = emptyList()) }
        viewModelScope.launch {
            val imported = mutableListOf<Notebook>()
            val failures = mutableListOf<String>()
            try {
                ready.await()
                importBatch(uris,
                    importItem = { repository.importPdf(it, folderId) },
                    onSuccess = { note ->
                        imported += note
                        _state.update { it.copy(notes = it.notes + note) }
                    },
                    onFailure = { index, error ->
                        failures += "PDF $index: ${error.message ?: "Protected, damaged, or unavailable file"}"
                    },
                    onProgress = { current, total ->
                        _state.update { it.copy(importProgress = "Importing PDF $current of $total…") }
                    })
                if (imported.isNotEmpty()) {
                    captureTab()
                    selectNotebookTimer(if (uris.size == 1) imported.single().id else null)
                    _state.update { it.copy(activeId = if (uris.size == 1) imported.single().id else null,
                        folderId = folderId, pageIndex = 0, canUndo = false, canRedo = false,
                        examFilter = ExamFilter(), pdfSearch = PdfSearchState()) }
                }
                captureTab()
                reportError(buildString {
                    append("Imported ${imported.size} of ${uris.size} PDFs.")
                    if (imported.any { it.exam.isTagged })
                        append("\nDetected exam metadata has been filled in. Review it in Exam details.")
                    if (failures.isNotEmpty()) append("\n" + failures.joinToString("\n"))
                })
            } finally { _state.update { it.copy(busy = false, importProgress = null) } }
        }
    }
    /** Opens a `.folio` backup as a new notebook beside the ones already on the device. */
    fun importArchive(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                ready.await()
                val note = repository.importArchive(uri, _state.value.folderId)
                captureTab()
                selectNotebookTimer(note.id)
                _state.update { it.copy(notes = it.notes + note, activeId = note.id, pageIndex = 0, canUndo = false, canRedo = false, pdfSearch = PdfSearchState()) }
                captureTab()
            } catch (e: Exception) { reportError("Couldn't open this backup. It may be damaged. ${e.message.orEmpty()}") }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    /** Writes a self-contained `.folio` backup that another device can open again. */
    fun exportArchive(note: Notebook, uri: Uri) {
        export {
            repository.exportArchive(note, uri)
            reportError("Notebook saved as a Folio backup")
        }
    }
    override fun onCleared() { writes.close(); super.onCleared() }

    private companion object {
        /** How far each paste is nudged from the last, in page units. */
        const val PASTE_OFFSET = 22f
        const val TIMER_START_KEY = "examTimer.startedAt"
        const val TIMER_WRITING_KEY = "examTimer.writingSeconds"
        const val TIMER_READING_KEY = "examTimer.readingSeconds"
        const val TIMER_LABEL_KEY = "examTimer.label"
        const val TIMER_PAUSED_AT_KEY = "examTimer.pausedAt"
        const val TIMER_PAUSED_MILLIS_KEY = "examTimer.pausedMillis"
        const val TIMER_LAST_SEEN_KEY = "examTimer.lastSeen"
        const val TIMER_VISITS_KEY = "examTimer.visits"
        /** The running timer's visible-heartbeat is written at most this often. */
        const val LAST_SEEN_THROTTLE_MS = 5_000L
    }
}
