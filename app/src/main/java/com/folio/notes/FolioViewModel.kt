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
    val activeId: String? = null, val pageIndex: Int = 0, val folderId: String? = null,
    val loading: Boolean = true, val busy: Boolean = false, val exporting: Boolean = false, val pendingSaves: Int = 0,
    val saveFailed: Boolean = false, val loadFailed: Boolean = false, val error: String? = null,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    /** Ink cut or copied from a lasso selection, kept so it can be pasted on any page. */
    val clipboard: List<Stroke> = emptyList()
) {
    val active get() = notes.find { it.id == activeId }
    val page get() = active?.pages?.getOrNull(pageIndex)
}

/** One page's ink and text together, so undo restores whichever the last edit touched. */
private typealias PageContent = Pair<List<Stroke>, List<TextBox>>

class FolioViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
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
        viewModelScope.launch { state.collect { savedState["activeId"] = it.activeId; savedState["pageIndex"] = it.pageIndex; savedState["folderId"] = it.folderId } }
    }
    fun loadLibrary() {
        if (ready.isCompleted) ready = CompletableDeferred()
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            try { val (notes, folders) = repository.load(); _state.update { it.copy(notes = notes, folders = folders, loading = false) }; ready.complete(Unit) }
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
    fun create(title: String, cover: Int, paper: Paper) {
        if (title.isBlank() || _state.value.loading || _state.value.loadFailed) return
        val note = Notebook(title = title.trim(), folderId = _state.value.folderId, cover = cover, pages = listOf(NotePage(paper = paper)))
        _state.update { it.copy(notes = it.notes + note, activeId = note.id, pageIndex = 0, canUndo = false, canRedo = false) }
        enqueue { repository.saveAll(note) }
    }
    fun open(id: String) {
        _state.update { it.copy(activeId = id, pageIndex = 0) }
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
        updateNote(note.copy(pages = note.pages + NotePage(paper = chosen))); selectPage(note.pages.size)
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
        val updated = note.withInsertedPage(at, NotePage(paper = chosen))
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
    fun importPdf(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                ready.await()
                val note = repository.importPdf(uri, _state.value.folderId)
                _state.update { it.copy(notes = it.notes + note, activeId = note.id, pageIndex = 0, canUndo = false, canRedo = false) }
            } catch (e: Exception) { reportError("Couldn't import PDF. It may be protected or damaged. ${e.message.orEmpty()}") }
            finally { _state.update { it.copy(busy = false) } }
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
    }
}
