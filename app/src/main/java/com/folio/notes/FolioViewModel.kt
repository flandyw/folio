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
import kotlinx.coroutines.flow.update

class FolioApplication : Application() {
    val repository by lazy { NoteRepository(this) }
    val thumbnails by lazy { PageThumbnailCache(this, repository) }
    val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** App-scoped so a pen connection survives configuration changes but is still editor-bound. */
    val penHaptics by lazy { PenHapticsManager(this) }
}
data class FolioState(
    val notes: List<Notebook> = emptyList(), val folders: List<Folder> = emptyList(),
    val sets: List<ExamSet> = emptyList(),
    val examFilter: ExamFilter = ExamFilter(),
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
    /** Ink cut or copied from a lasso selection, kept so it can be pasted on any page. */
    val clipboard: List<Stroke> = emptyList()
) {
    val active get() = notes.find { it.id == activeId }
    val page get() = active?.pages?.getOrNull(pageIndex)
    /** Days until the nearest upcoming exam date across the library, or null when none is set. */
    val daysToExam: Int?
        get() = notes.mapNotNull { it.exam.examDate }
            .filter { it >= startOfDay() }
            .minOrNull()
            ?.let { date -> ((date - startOfDay()) / 86_400_000L).toInt() }

    private fun startOfDay(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0); calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}

/** One page's ink and text together, so undo restores whichever the last edit touched. */
private typealias PageContent = Pair<List<Stroke>, List<TextBox>>

class FolioViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("preferences", 0)
    val repository = (application as FolioApplication).repository
    val thumbnails = (application as FolioApplication).thumbnails
    private val _state = MutableStateFlow(FolioState(activeId = savedState["activeId"], pageIndex = savedState["pageIndex"] ?: 0, folderId = savedState["folderId"]))
    val state = _state.asStateFlow()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val undo = mutableMapOf<String, MutableList<PageContent>>()
    private val redo = mutableMapOf<String, MutableList<PageContent>>()
    private var ready = CompletableDeferred<Unit>()
    /** Offsets each paste a little further, so repeated pastes stack instead of hiding each other. */
    private var pasteGeneration = 0
    /** Pages whose content is being read right now, so a page is never fetched twice at once. */
    private val loadingPages = mutableSetOf<String>()
    var pendingExport: Pair<Notebook, Int>? = null
    init {
        (application as FolioApplication).storageScope.launch {
            for (write in writes) {
                try { write() } catch (e: Exception) { _state.update { it.copy(saveFailed = true, error = "Couldn't save changes: ${e.message}. Use Retry save before closing.") } }
                finally { _state.update { it.copy(pendingSaves = (it.pendingSaves - 1).coerceAtLeast(0)) } }
            }
        }
        loadLibrary()
        // A sitting that was running when the process died resumes where the clock says it should.
        ExamTimerState.resume(storedSitting(), prefs.getLong(TIMER_START_KEY, 0L))?.let { restored ->
            _state.update { it.copy(timer = restored) }
        }
        viewModelScope.launch { state.collect { savedState["activeId"] = it.activeId; savedState["pageIndex"] = it.pageIndex; savedState["folderId"] = it.folderId } }
    }
    fun loadLibrary() {
        if (ready.isCompleted) ready = CompletableDeferred()
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            try { val (notes, folders, sets) = repository.load(); _state.update { it.copy(notes = notes, folders = folders, sets = sets, loading = false) }; ready.complete(Unit) }
            catch (e: Exception) { _state.update { it.copy(loading = false, loadFailed = true, error = "Couldn't load your library: ${e.message}") }; ready.completeExceptionally(e) }
        }
    }
    private fun enqueue(block: suspend () -> Unit) { _state.update { it.copy(pendingSaves = it.pendingSaves + 1) }; writes.trySend(block) }
    /** Records a change to the notebook itself — title, folder, star, or the order and set of pages. */
    private fun updateNote(note: Notebook) {
        val updated = note.copy(updated = System.currentTimeMillis())
        _state.update { state -> state.copy(notes = state.notes.map { if (it.id == updated.id) updated else it }) }
        enqueue { repository.saveMeta(updated) }
    }
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
        _state.value.notes.filter { it.folderId == folder.id }.forEach { updateNote(it.copy(folderId = null)) }
        val folders = _state.value.folders.filterNot { it.id == folder.id }
        _state.update { it.copy(folders = folders, folderId = null) }; enqueue { repository.saveFolders(folders) }
    }
    fun create(title: String, cover: Int, paper: Paper, exam: ExamTags = ExamTags(), pageCount: Int = 1, setId: String? = null, infinite: Boolean = false) {
        if (title.isBlank() || _state.value.loading || _state.value.loadFailed) return
        val pages = List(if (infinite) 1 else pageCount.coerceIn(1, 40)) { NotePage(paper = paper, infinite = infinite) }
        val note = Notebook(title = title.trim(), folderId = _state.value.folderId, cover = cover, pages = pages, exam = exam, setId = setId)
        _state.update { it.copy(notes = it.notes + note, activeId = note.id, pageIndex = 0, canUndo = false, canRedo = false) }
        enqueue { repository.saveAll(note) }
    }
    fun open(id: String) {
        _state.update { it.copy(activeId = id, pageIndex = 0) }
        historyState()
        _state.value.page?.let { loadPage(it.id) }
    }

    /** Opens a notebook straight onto one of its pages, so a flagged question is one tap away. */
    fun openAt(id: String, index: Int) {
        val target = _state.value.notes.find { it.id == id } ?: return
        _state.update { it.copy(activeId = id, pageIndex = index.coerceIn(0, target.pages.lastIndex)) }
        historyState()
        _state.value.page?.let { loadPage(it.id) }
    }
    fun close() {
        _state.update { it.copy(activeId = null) }
        // The notebook's PDF renderer is no longer needed once the editor is put away.
        viewModelScope.launch { repository.closePdf() }
    }
    fun rename(note: Notebook, title: String) { if (title.isNotBlank()) updateNote(note.copy(title = title.trim())) }
    fun star(note: Notebook) = updateNote(note.copy(starred = !note.starred))
    fun move(note: Notebook, folderId: String?) = updateNote(note.copy(folderId = folderId))

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

    fun createExamSet(name: String, subject: VceSubject?, year: Int?, company: String) {
        if (subject == null || year == null || year !in 1000..9999 || company.isBlank() || _state.value.loadFailed) return
        val existing = _state.value.sets.find {
            it.subject == subject && it.year == year && it.company.trim().equals(company.trim(), ignoreCase = true)
        }
        val set = existing ?: ExamSet(name = name.trim(), subject = subject, year = year, company = company.trim())
        if (existing == null) {
            val sets = _state.value.sets + set
            _state.update { it.copy(sets = sets) }; enqueue { repository.saveSets(sets) }
        }
        val matching = _state.value.notes.filter { it.setId == null && set.matchesPaper(it) }.map { it.id }.toSet()
        assignToExamSet(matching, set.id)
    }

    fun updateExamSet(set: ExamSet) {
        val sets = _state.value.sets.map { if (it.id == set.id) set else it }
        _state.update { it.copy(sets = sets) }; enqueue { repository.saveSets(sets) }
    }

    /** Removes a set and unlinks its notebooks, which stay in the library untouched. */
    fun deleteExamSet(set: ExamSet) {
        _state.value.notes.filter { it.setId == set.id }.forEach { updateNote(it.copy(setId = null)) }
        val sets = _state.value.sets.filterNot { it.id == set.id }
        _state.update { it.copy(sets = sets) }; enqueue { repository.saveSets(sets) }
    }

    /** Adds or removes notebooks from an exam set in one step; null unfiles them from any set. */
    fun assignToExamSet(ids: Set<String>, setId: String?) {
        if (setId != null && _state.value.sets.none { it.id == setId }) return
        _state.value.notes.filter { it.id in ids && it.setId != setId }
            .forEach { updateNote(it.copy(setId = setId)) }
    }

    /** Flips the open page's redo flag — a question worth another attempt before the exam. */
    fun toggleRedoFlag() {
        val page = _state.value.page ?: return
        if (!page.loaded) return
        replacePage(page.copy(redoFlag = !page.redoFlag))
    }

    /** Records a marked sitting on a notebook, newest last; a first mark also marks it sat. */
    fun recordAttempt(noteId: String, attempt: ExamAttempt) {
        val note = _state.value.notes.find { it.id == noteId } ?: return
        val exam = if (note.exam.status == ExamStatus.TO_DO) note.exam.copy(status = ExamStatus.MARKED) else note.exam
        updateNote(note.withAttempt(attempt).copy(exam = exam))
        // A timed sitting is spent on the mark it belongs to, not offered to the next one.
        if (attempt.timed) _state.update { it.copy(lastTimedSeconds = null) }
    }

    /** Removes one recorded sitting from a notebook's history. */
    fun deleteAttempt(noteId: String, attemptId: String) {
        val note = _state.value.notes.find { it.id == noteId } ?: return
        updateNote(note.copy(attempts = note.attempts.filterNot { it.id == attemptId }))
    }
    fun moveNotebooks(ids: Set<String>, folderId: String?) {
        if (folderId != null && _state.value.folders.none { it.id == folderId }) return
        _state.value.notes.filter { it.id in ids && it.folderId != folderId }
            .forEach { updateNote(it.copy(folderId = folderId)) }
    }
    fun favoriteNotebooks(ids: Set<String>, starred: Boolean) {
        _state.value.notes.filter { it.id in ids && it.starred != starred }
            .forEach { updateNote(it.copy(starred = starred)) }
    }
    fun delete(note: Notebook) {
        _state.update { it.copy(notes = it.notes.filterNot { n -> n.id == note.id }, activeId = if (it.activeId == note.id) null else it.activeId) }
        thumbnails.clear(note.id)
        enqueue {
            try { repository.delete(note.id) }
            catch (e: Exception) { _state.update { it.copy(notes = it.notes + note) }; throw e }
        }
    }
    fun selectPage(index: Int) {
        val note = _state.value.active ?: return
        _state.update { it.copy(pageIndex = index.coerceIn(0, note.pages.lastIndex)) }
        historyState()
        _state.value.page?.let { loadPage(it.id) }
    }
    /**
     * Brings one page's ink and text into memory. The page is left untouched while the read is in
     * flight, and a page loaded or edited in the meantime wins, so a slow read of an older copy can
     * never overwrite what the user has since written.
     */
    fun loadPage(pageId: String) {
        val note = _state.value.active ?: return
        val summary = note.pages.find { it.id == pageId } ?: return
        if (summary.loaded || !loadingPages.add(pageId)) return
        viewModelScope.launch {
            try {
                val loaded = repository.loadPage(note.id, summary)
                _state.update { state ->
                    val current = state.active
                    val target = current?.pages?.find { it.id == pageId }
                    if (current == null || target == null || target.loaded) state
                    else state.copy(notes = state.notes.map { if (it.id == current.id) current.withPage(loaded) else it })
                }
            } catch (e: Exception) {
                reportError("Couldn't open this page: ${e.message.orEmpty()}")
            } finally { loadingPages.remove(pageId) }
        }
    }
    fun addPage(paper: Paper? = null) {
        val note = _state.value.active ?: return
        // Inherit the current page's paper so an exam set stays consistent — keeps practice flowing.
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
                if (at < 0) return@launch
                val duplicated = current.withPage(loaded).withDuplicatedPage(at).copy(updated = System.currentTimeMillis())
                _state.update { s -> s.copy(notes = s.notes.map { if (it.id == current.id) duplicated else it }, pageIndex = at + 1) }
                enqueue { repository.savePage(duplicated, duplicated.pages[at + 1]) }
            } catch (e: Exception) { reportError("Couldn't duplicate this page: ${e.message.orEmpty()}") }
        }
        return null
    }
    private fun duplicateLoaded(note: Notebook, index: Int): Int {
        val at = index + 1
        val duplicated = note.withDuplicatedPage(index).copy(updated = System.currentTimeMillis())
        _state.update { state -> state.copy(notes = state.notes.map { if (it.id == duplicated.id) duplicated else it }, pageIndex = at) }
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
        historyState()
    }
    fun setPaper(paper: Paper) { val p = _state.value.page ?: return; replacePage(p.copy(paper = paper)) }
    /** Inserts centred graph axes as editable LINE strokes so students can annotate immediately. */
    fun insertAxes() {
        val page = _state.value.page ?: return
        val axes = InkGeometry.mathAxes(page)
        strokes(page.id, page.strokes + axes)
    }
    /** Stores a page's new content, bumping its revision so caches and exports know it changed. */
    private fun replacePage(page: NotePage) {
        val note = _state.value.active ?: return
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
        val page = _state.value.active?.pages?.find { it.id == pageId } ?: return
        // Ink cannot be changed on a page whose own ink has not been read yet.
        if (!page.loaded || page.strokes == strokes) return
        record(page); replacePage(page.copy(strokes = strokes)); historyState()
    }
    fun texts(texts: List<TextBox>) {
        val page = _state.value.page ?: return
        texts(page.id, texts)
    }
    fun texts(pageId: String, texts: List<TextBox>) {
        val page = _state.value.active?.pages?.find { it.id == pageId } ?: return
        if (!page.loaded || page.texts == texts) return
        record(page); replacePage(page.copy(texts = texts)); historyState()
    }
    fun addText(box: TextBox) { val page = _state.value.page ?: return; texts(page.id, page.texts + box) }
    fun updateText(box: TextBox) { val page = _state.value.page ?: return; texts(page.id, page.texts.map { if (it.id == box.id) box else it }) }
    fun removeText(id: String) { val page = _state.value.page ?: return; texts(page.id, page.texts.filterNot { it.id == id }) }
    /** Wipes the open page's ink and text in one undoable step, leaving its paper or PDF in place. */
    fun clearPage() {
        val page = _state.value.page ?: return
        if (!page.loaded || (page.strokes.isEmpty() && page.texts.isEmpty())) return
        record(page); replacePage(page.copy(strokes = emptyList(), texts = emptyList())); historyState()
    }
    /** Remembers a lasso selection for pasting, on this page or another one. */
    fun copyToClipboard(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        pasteGeneration = 0
        _state.update { it.copy(clipboard = strokes) }
    }
    /** Copies the selection to the clipboard and takes it off the current page in one step. */
    fun cutSelection(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        copyToClipboard(strokes)
        val page = _state.value.page ?: return
        this.strokes(page.id, page.strokes.filterNot { it in strokes })
    }
    /** Appends the clipboard to the open page, nudged along so a paste never hides under its source. */
    fun pasteClipboard(): List<Stroke> {
        val page = _state.value.page ?: return emptyList()
        val clip = _state.value.clipboard
        if (clip.isEmpty()) return emptyList()
        val offset = PASTE_OFFSET * ++pasteGeneration
        val pasted = clip.map { InkGeometry.translate(it, offset, offset) }
        strokes(page.id, page.strokes + pasted)
        return pasted
    }
    /** Applies a new look to the selection as one undoable step; nulls leave those properties alone. */
    fun restyleSelection(strokes: List<Stroke>, color: Int?, widthScale: Float?, opacity: Float?) {
        if (strokes.isEmpty()) return
        val page = _state.value.page ?: return
        val restyled = InkGeometry.restyle(strokes, color, widthScale, opacity)
        if (restyled == strokes) return
        this.strokes(page.id, page.strokes.filterNot { it in strokes } + restyled)
    }
    fun undo() = history(undo, redo)
    fun redo() = history(redo, undo)

    // ---- Exam timer -----------------------------------------------------------------------

    /**
     * The running sitting is saved to preferences the moment it starts, so a process death — the
     * app swiped away, a crash, the system reclaiming memory — never ends an exam. Every timer
     * state is derived from the start moment, so restoring is only a matter of keeping the preset
     * and the start time; the record is cleared when the sitting stops.
     */
    private fun saveSitting(timer: ExamTimerState) {
        prefs.edit()
            .putLong(TIMER_START_KEY, timer.startedAt ?: 0L)
            .putInt(TIMER_WRITING_KEY, timer.preset.writingSeconds)
            .putInt(TIMER_READING_KEY, timer.preset.readingSeconds)
            .putString(TIMER_LABEL_KEY, timer.preset.label)
            .apply()
    }

    private fun clearSitting() {
        prefs.edit().remove(TIMER_START_KEY).remove(TIMER_WRITING_KEY)
            .remove(TIMER_READING_KEY).remove(TIMER_LABEL_KEY).apply()
    }

    /** The preset of a saved sitting, or null when none was running when the app last stopped. */
    private fun storedSitting(): ExamTimerPreset? {
        if (prefs.getLong(TIMER_START_KEY, 0L) <= 0L) return null
        return ExamTimerPreset(
            prefs.getString(TIMER_LABEL_KEY, "Exam") ?: "Exam",
            prefs.getInt(TIMER_WRITING_KEY, 90 * 60),
            prefs.getInt(TIMER_READING_KEY, 15 * 60)
        )
    }

    /** Advances the countdown by however long has passed; a stopped timer ignores ticks. */
    fun tickTimer() { _state.update { it.copy(timer = it.timer.tick()) } }
    fun startTimer(preset: ExamTimerPreset) {
        val started = ExamTimerState().start(preset)
        saveSitting(started)
        _state.update { it.copy(timer = started) }
    }
    fun adjustTimer(seconds: Int) {
        val adjusted = _state.value.timer.adjust(seconds)
        saveSitting(adjusted)
        _state.update { it.copy(timer = adjusted) }
    }
    fun skipTimerPhase() {
        val skipped = _state.value.timer.skip()
        saveSitting(skipped)
        _state.update { it.copy(timer = skipped) }
    }
    /** Stops the timer, keeping how long the writing phase ran for the attempt record. */
    fun stopTimer() {
        val current = _state.value.timer
        val spent = current.elapsedWriting().takeIf { current.startedAt != null && it > 0 }
        clearSitting()
        _state.update { it.copy(timer = current.stop(), lastTimedSeconds = spent ?: it.lastTimedSeconds) }
    }
    private fun record(page: NotePage) {
        undo.getOrPut(page.id) { mutableListOf() }.apply { add(page.strokes to page.texts); if (size > 60) removeAt(0) }
        redo.remove(page.id)
    }
    private fun history(from: MutableMap<String, MutableList<PageContent>>, to: MutableMap<String, MutableList<PageContent>>) {
        val page = _state.value.page ?: return
        if (!page.loaded) return
        val previous = from[page.id]?.removeLastOrNull() ?: return
        to.getOrPut(page.id) { mutableListOf() }.add(page.strokes to page.texts)
        replacePage(page.copy(strokes = previous.first, texts = previous.second)); historyState()
    }
    private fun historyState() { _state.update { it.copy(canUndo = !undo[it.page?.id].isNullOrEmpty(), canRedo = !redo[it.page?.id].isNullOrEmpty()) } }
    fun retrySave() {
        val snapshot = _state.value
        enqueue {
            snapshot.notes.forEach { repository.saveAll(it) }; repository.saveFolders(snapshot.folders)
            _state.update { it.copy(saveFailed = false) }
        }
    }
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
                    _state.update { it.copy(activeId = if (uris.size == 1) imported.single().id else null,
                        folderId = folderId, pageIndex = 0, canUndo = false, canRedo = false,
                        examFilter = ExamFilter()) }
                }
                reportError(buildString {
                    append("Imported ${imported.size} of ${uris.size} PDFs.")
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
                _state.update { it.copy(notes = it.notes + note, activeId = note.id, pageIndex = 0, canUndo = false, canRedo = false) }
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
    }
}
