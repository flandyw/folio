package com.folio.notes

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

import com.folio.notes.FolioRuntime.Companion.MAX_UNDO
import com.folio.notes.FolioRuntime.Companion.MAX_STRUCTURE_UNDO
import com.folio.notes.DocumentWriteOp as WriteOp
import com.folio.notes.FolioRuntime.StructureStep
import com.folio.notes.FolioRuntime.PageMeta

/** Application-owned DocumentEngine commands over the single shared document runtime. */
internal class DocumentEngine(private val runtime: FolioRuntime) {

    internal val undo = mutableMapOf<String, MutableList<PageEdit>>()
    internal val redo = mutableMapOf<String, MutableList<PageEdit>>()
    internal val publishedHistory = java.util.concurrent.ConcurrentHashMap<String, PageJournal.History>()
    internal val structureUndo = ArrayList<StructureStep>()
    internal val structureRedo = ArrayList<StructureStep>()
    internal var actionCounter = System.currentTimeMillis()
    internal var documentHistoryBusy = false
    internal val inkSeq = HashMap<String, Long>()
    internal val inkRedoSeq = HashMap<String, Long>()

    internal val loadingPages = mutableSetOf<String>()
    private val historyAvailability = DocumentHistoryAvailability()

    internal fun recordStructure(noteId: String, label: String = "Page organisation change", undo: () -> Unit, redo: () -> Unit) {
        with(runtime) {
            structureUndo.add(StructureStep(noteId, ++actionCounter, label, undo, redo))
            while (structureUndo.size > MAX_STRUCTURE_UNDO) structureUndo.removeAt(0)
            structureRedo.removeAll { it.noteId == noteId }
            historyState()
        }
    }

    internal fun findPage(pageId: String): Notebook? = runtime._state.notebookForPage(pageId)
    internal fun findPageContent(pageId: String): NotePage? = runtime._state.page(pageId)

    private fun installHistory(pageId: String, history: PageJournal.History) {
        undo[pageId] = history.undo.toMutableList()
        redo[pageId] = history.redo.toMutableList()
        publishedHistory[pageId] = history
        actionCounter = maxOf(actionCounter,
            history.undo.maxOfOrNull { it.actionSequence } ?: 0L,
            history.redo.maxOfOrNull { it.actionSequence } ?: 0L)
    }

    fun loadPage(pageId: String) {
        with(runtime) {
            val note = findPage(pageId) ?: return
            val summary = note.pages.page(pageId) ?: return
            if (summary.loaded || !loadingPages.add(pageId)) return
            scope.launch {
                var retry = false
                try {
                    val checkpoint = repository.loadPageCheckpoint(note.id, summary)
                    val loaded = checkpoint.page; val history = checkpoint.history
                    val current = _state.value.notes.notebook(note.id)?.pages?.page(pageId)
                    if (current != null && !current.loaded) {
                        if (current.revision > loaded.revision) retry = true
                        else {
                            // Install history before the UI can write to the newly loaded page.
                            installHistory(pageId, history)
                            _state.update { state ->
                                val live = state.notes.notebook(note.id) ?: return@update state
                                state.copy(notes = state.notes.replaceNotebook(live.withPage(current.withLoadedContent(loaded))))
                            }
                            repository.requestCompaction(note.id, pageId) { currentCheckpoint(note.id, pageId) }
                            historyState()
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    reportError("Couldn't open this page: ${e.message.orEmpty()}")
                } finally { loadingPages.remove(pageId); if (retry) loadPage(pageId) }
            }
        }
    }

    fun addPage(paper: Paper? = null) {
        with(runtime) {
            val note = _state.value.active ?: return
            // Older notebooks inherit the current page until a default is chosen.
            val chosen = paper ?: note.defaultPaper ?: _state.value.page?.paper ?: Paper.MATH_GRID
            val page = NotePage(paper = chosen, infinite = _state.value.page?.infinite == true)
            updateNote(note.copy(pages = note.pages + page).linkResponsePage(page.id, _state.value.page?.id)); selectPage(note.pages.size)
            recordPageInserted(note.id, page.id)
        }
    }

    fun duplicatePage(index: Int = runtime._state.value.pageIndex): Int? {
        with(runtime) {
            val state = _state.value
            val note = state.active ?: return null
            if (index !in note.pages.indices) return null
            val source = note.pages[index]
            if (source.loaded) return duplicateLoaded(note, index)
            scope.launch {
                try {
                    val loaded = repository.loadPage(note.id, source)
                    val current = _state.value.active ?: return@launch
                    val at = current.pages.pageIndex(source.id)
                    if (current.id != note.id || at < 0) return@launch
                    val duplicated = current.withPage(if (current.pages[at].loaded) current.pages[at] else current.pages[at].withLoadedContent(loaded)).withDuplicatedPage(at).copy(updated = System.currentTimeMillis())
                    _state.update { s -> s.copy(notes = s.notes.replaceNotebook(duplicated), pageIndex = at + 1) }
                    captureTab()
                    enqueue { repository.savePage(duplicated, duplicated.pages[at + 1]) }
                    recordPageInserted(duplicated.id, duplicated.pages[at + 1].id)
                } catch (e: Exception) { reportError("Couldn't duplicate this page: ${e.message.orEmpty()}") }
            }
            return null
        }
    }

    internal fun duplicateLoaded(note: Notebook, index: Int): Int {
        with(runtime) {
            val at = index + 1
            val duplicated = note.withDuplicatedPage(index).copy(updated = System.currentTimeMillis())
            _state.update { state -> state.copy(notes = state.notes.replaceNotebook(duplicated), pageIndex = at) }
            captureTab()
            // The copy is a page of its own, so its content has to reach disk along with the index.
            enqueue { repository.savePage(duplicated, duplicated.pages[at]) }
            recordPageInserted(duplicated.id, duplicated.pages[at].id)
            return at
        }
    }

    fun insertPage(index: Int, paper: Paper? = null): Int {
        with(runtime) {
            val state = _state.value
            val note = state.active ?: return state.pageIndex
            val chosen = paper ?: note.defaultPaper ?: state.page?.paper ?: Paper.MATH_GRID
            val at = index.coerceIn(0, note.pages.size)
            val page = NotePage(paper = chosen, infinite = _state.value.page?.infinite == true)
            val updated = note.withInsertedPage(at, page).linkResponsePage(page.id, _state.value.page?.id)
            updateNote(updated)
            _state.update { it.copy(pageIndex = at) }
            captureTab()
            recordPageInserted(note.id, page.id)
            return at
        }
    }

    fun deletePage(index: Int) {
        with(runtime) {
            val note = _state.value.active ?: return
            val page = note.pages.getOrNull(index) ?: return
            if (page.loaded) { deleteLoadedPage(note.id, page.id); return }
            // An undoable delete has to hold the page's content, so a page still on disk is read first.
            scope.launch {
                awaitLoaded(page.id) ?: run { reportError("Couldn't load the page to delete it."); return@launch }
                deleteLoadedPage(note.id, page.id)
            }
        }
    }

    internal fun deleteLoadedPage(noteId: String, pageId: String) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val index = note.pages.pageIndex(pageId)
            val removed = note.pages.getOrNull(index)?.takeIf { it.loaded } ?: return
            val history = PageJournal.History(undo[pageId]?.toList().orEmpty(), redo[pageId]?.toList().orEmpty())
            val wasOnly = note.pages.size == 1
            removePageRaw(noteId, pageId)
            // Deleting the only page leaves a blank one behind; undoing the delete takes that filler away again.
            val filler = if (wasOnly) _state.value.notes.notebook(noteId)?.pages?.singleOrNull()?.id else null
            recordStructure(noteId, "Delete page ${index + 1}",
                undo = {
                    insertPageRaw(noteId, index, removed, history, select = true)
                    filler?.let { removePageRaw(noteId, it) }
                },
                redo = { removePageRaw(noteId, pageId) })
        }
    }

    internal fun removePageRaw(noteId: String, pageId: String): Pair<NotePage, PageJournal.History>? {
        with(runtime) {
            val state = _state.value
            val note = state.notes.notebook(noteId) ?: return null
            val index = note.pages.pageIndex(pageId)
            val removed = note.pages.getOrNull(index) ?: return null
            val history = PageJournal.History(undo[pageId]?.toList().orEmpty(), redo[pageId]?.toList().orEmpty())
            val updated = note.withDeletedPage(index)
            updateNote(updated)
            undo.remove(pageId); redo.remove(pageId); publishedHistory.remove(pageId); inkSeq.remove(pageId); inkRedoSeq.remove(pageId)
            enqueue { repository.deletePage(noteId, pageId) }
            if (state.activeId == noteId) {
                val current = if (index < state.pageIndex) state.pageIndex - 1 else state.pageIndex
                _state.update { it.copy(pageIndex = current.coerceIn(0, updated.pages.lastIndex)) }
                captureTab()
            }
            historyState()
            return removed to history
        }
    }

    internal fun insertPageRaw(noteId: String, index: Int, page: NotePage, history: PageJournal.History?, select: Boolean) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val at = index.coerceIn(0, note.pages.size)
            val updated = note.withInsertedPage(at, page).copy(updated = System.currentTimeMillis())
            history?.let {
                undo[page.id] = it.undo.toMutableList(); redo[page.id] = it.redo.toMutableList()
                publishedHistory[page.id] = it
            }
            _state.update { state ->
                val moved = state.copy(notes = state.notes.replaceNotebook(updated))
                if (state.activeId != noteId) moved
                else moved.copy(pageIndex = if (select) at else if (at <= state.pageIndex) state.pageIndex + 1 else state.pageIndex)
            }
            captureTab()
            enqueue { repository.savePage(updated, page, history ?: PageJournal.History.EMPTY) }
            historyState()
        }
    }

    internal fun recordPageInserted(noteId: String, pageId: String) {
        with(runtime) {
            var held: Pair<NotePage, PageJournal.History>? = null
            var heldAt = 0
            recordStructure(noteId, "Insert page ${(_state.value.notes.notebook(noteId)?.pages?.pageIndex(pageId) ?: 0) + 1}",
                undo = {
                    heldAt = _state.value.notes.notebook(noteId)?.pages?.indexOfFirst { it.id == pageId } ?: 0
                    held = removePageRaw(noteId, pageId)
                },
                redo = { held?.let { (page, history) -> insertPageRaw(noteId, heldAt, page, history, select = true) } })
        }
    }

    fun movePages(noteId: String, ids: Set<String>, position: Int) = with(runtime) {
        val note = _state.value.active?.takeIf { it.id == noteId } ?: return
        val before = note.pages.map { it.id }
        val after = reorderedPageBlock(before, ids, position)
        if (before == after) return
        if (applyPageOrder(noteId, after)) recordStructure(noteId, "Move ${ids.size} pages", undo = { applyPageOrder(noteId, before) }, redo = { applyPageOrder(noteId, after) })
    }
    private fun applyPageOrder(noteId: String, order: List<String>): Boolean = with(runtime) {
        val note = _state.value.notes.notebook(noteId) ?: return false
        if (note.pages.size != order.size || note.pages.map { it.id }.toSet() != order.toSet()) { reportError("The page order changed. Undo intervening page changes first."); return false }
        val currentId = _state.value.takeIf { it.activeId == noteId }?.page?.id
        val byId = note.pages.associateBy { it.id }
        updateNote(note.copy(pages = order.map { byId.getValue(it) }, updated = System.currentTimeMillis()))
        if (currentId != null) { _state.update { it.copy(pageIndex = order.indexOf(currentId).coerceAtLeast(0)) }; captureTab() }
        return true
    }

    fun movePage(from: Int, to: Int) {
        with(runtime) {
            val note = _state.value.active ?: return
            val target = to.coerceIn(0, note.pages.lastIndex)
            if (from !in note.pages.indices || from == target) return
            movePageRaw(note.id, from, target)
            recordStructure(note.id, "Move page ${from + 1} to ${target + 1}", undo = { movePageRaw(note.id, target, from) }, redo = { movePageRaw(note.id, from, target) })
        }
    }

    internal fun movePageRaw(noteId: String, from: Int, to: Int) {
        with(runtime) {
            val state = _state.value
            val note = state.notes.notebook(noteId) ?: return
            val updated = note.withMovedPage(from, to)
            if (updated == note) return
            updateNote(updated)
            if (state.activeId == noteId) {
                _state.update { it.copy(pageIndex = movedPageIndex(state.pageIndex, from, to.coerceIn(0, note.pages.lastIndex))) }
                captureTab()
            }
            historyState()
        }
    }

    internal fun applyPageMeta(noteId: String, pageId: String, meta: PageMeta) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val page = note.pages.page(pageId) ?: return
            updateNote(note.withPage(page.copy(paper = meta.paper, redoFlag = meta.redoFlag, title = meta.title, bookmarked = meta.bookmarked).revised()))
        }
    }

    internal fun recordPageMeta(noteId: String, before: NotePage, after: NotePage) {
        with(runtime) {
            val was = before.meta(); val now = after.meta()
            if (was == now) return
            recordStructure(noteId, "Change properties · ${before.title.ifBlank { "page" }}", undo = { applyPageMeta(noteId, before.id, was) }, redo = { applyPageMeta(noteId, before.id, now) })
        }
    }

    fun renamePage(pageId: String, title: String) {
        with(runtime) {
            val note = _state.value.active ?: return
            val page = note.pages.page(pageId) ?: return
            val renamed = page.copy(title = title.trim().take(120))
            updateNote(note.withPage(renamed))
            recordPageMeta(note.id, page, renamed)
        }
    }

    fun setPeekAnchor(anchor: PeekAnchor?) {
        with(runtime) {
            val note = _state.value.active ?: return
            val page = anchor?.let { it.page(note.pages) ?: return }
            val next = page?.let(anchor::fittedTo)
            if (next == note.peekAnchor) return
            updateNote(note.copy(peekAnchor = next))
        }
    }

    fun togglePageBookmark(pageId: String) {
        with(runtime) {
            val note = _state.value.active ?: return
            val page = note.pages.page(pageId) ?: return
            val toggled = page.copy(bookmarked = !page.bookmarked)
            updateNote(note.withPage(toggled))
            recordPageMeta(note.id, page, toggled)
        }
    }

    fun setPageBookmarked(noteId: String, pageId: String, bookmarked: Boolean) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val page = note.pages.page(pageId) ?: return
            if (page.bookmarked == bookmarked) return
            updateNote(note.withPage(page.copy(bookmarked = bookmarked)))
        }
    }

    fun setPageRedoFlag(noteId: String, pageId: String, flagged: Boolean) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val page = note.pages.page(pageId) ?: return
            if (page.redoFlag == flagged) return
            updateNote(note.withPage(page.copy(redoFlag = flagged)))
        }
    }

    fun setDefaultPaper(paper: Paper) {
        with(runtime) {
            val note = _state.value.active ?: return
            updateNote(note.copy(defaultPaper = paper))
        }
    }

    fun setPaper(paper: Paper) {
        with(runtime) { val p = _state.value.page ?: return; replacePage(p.copy(paper = paper)) }
    }

    internal fun replacePage(page: NotePage) {
        with(runtime) {
            val note = findPage(page.id) ?: return
            // A page still on disk is never rewritten from an empty in-memory copy.
            if (!page.loaded) return
            val before = note.pages.page(page.id) ?: return
            commitEdit(note, before, page)
        }
    }

    internal fun commitEdit(
        note: Notebook, before: NotePage, after: NotePage,
        knownEdits: Pair<PageEdit, PageEdit>? = null, beforeSave: suspend () -> Unit = {}
    ) {
        with(runtime) {
            touchTimerActivity()
            val change = DocumentReducer.page(before, after, knownEdits)
            val forward = change.forward
            val revised = change.page
            val updated = note.copy(pages = note.pages.replacePage(revised), updated = System.currentTimeMillis(),
                documentHistory = if (forward == null || note.documentHistory.redo.isEmpty()) note.documentHistory
                    else note.documentHistory.copy(redo = emptyList()))
            _state.update { state -> state.copy(notes = state.notes.replaceNotebook(updated)) }
            if (forward == null) {
                enqueue { beforeSave(); repository.saveMeta(updated) }
                recordPageMeta(note.id, before, after)
                return
            }
            inkSeq[after.id] = ++actionCounter
            structureRedo.removeAll { it.noteId == note.id }
            val inverse = requireNotNull(change.inverse).let {
                if (note.documentHistory.enabled) it.copy(actionSequence = actionCounter) else it
            }
            undo.getOrPut(after.id) { mutableListOf() }.apply { add(inverse); if (size > MAX_UNDO) removeAt(0) }
            redo.remove(after.id)
            historyState()
            repository.requestCompaction(note.id, after.id) { currentCheckpoint(note.id, after.id) }
            queue(WriteOp.Page(note.id, after.id,
                PageTransaction(0, revised.revision, forward, undoPush = inverse, clearRedo = true), beforeSave))
            if (note.documentHistory.redo.isNotEmpty()) enqueue { repository.saveMeta(updated) }
        }
    }

    internal fun currentCheckpoint(noteId: String, pageId: String): PageCheckpoint? {
        with(runtime) {
            val page = _state.value.notes.notebook(noteId)?.pages?.page(pageId)
            if (page == null || !page.loaded) return null
            return PageCheckpoint(page, publishedHistory[pageId] ?: PageJournal.History.EMPTY)
        }
    }

    internal fun allPublishedHistory(): Map<String, PageJournal.History> = with(runtime) { publishedHistory.toMap() }

    fun strokes(strokes: List<Stroke>) {
        with(runtime) {
            val page = _state.value.page ?: return
            strokes(page.id, strokes)
        }
    }

    fun strokes(pageId: String, strokes: List<Stroke>) {
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            // Ink cannot be changed on a page whose own ink has not been read yet.
            // Reference check first avoids a deep walk over every InkPoint for identical lists.
            if (!page.loaded || page.strokes === strokes || page.strokes == strokes) return
            replacePage(page.copy(strokes = strokes))
        }
    }

    fun appendStroke(pageId: String, beforeStrokes: List<Stroke>, stroke: Stroke, afterStrokes: List<Stroke>) {
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            if (!page.loaded) return
            if (page.strokes !== beforeStrokes || afterStrokes.size != beforeStrokes.size + 1 ||
                afterStrokes.last() !== stroke) {
                strokes(pageId, afterStrokes)
                return
            }
            val note = findPage(pageId) ?: return
            commitEdit(note, page, page.copy(strokes = afterStrokes), knownEdits =
                PageEdit(StrokesEdit.Add(listOf(stroke))) to
                    PageEdit(StrokesEdit.Remove(listOf(beforeStrokes.size))))
        }
    }

    fun withdrawStroke(pageId: String, stroke: Stroke) {
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            if (!page.loaded || page.strokes.lastOrNull() !== stroke) return
            val stack = undo[pageId] ?: return
            val forward = PageEdit(StrokesEdit.Remove(listOf(page.strokes.lastIndex)))
            if (stack.lastOrNull() != forward) return
            val note = findPage(pageId) ?: return
            stack.removeAt(stack.lastIndex)
            val revised = page.copy(strokes = page.strokes.dropLast(1)).revised()
            val updated = note.copy(pages = note.pages.replacePage(revised), updated = System.currentTimeMillis())
            _state.update { state -> state.copy(notes = state.notes.replaceNotebook(updated)) }
            historyState()
            // The palm may have landed on a companion pane's page, which historyState does not publish.
            publishedHistory[pageId] = PageJournal.History(stack.toList(), redo[pageId]?.toList().orEmpty())
            repository.requestCompaction(note.id, pageId) { currentCheckpoint(note.id, pageId) }
            queue(WriteOp.Page(note.id, pageId, PageTransaction(0, revised.revision, forward, undoPop = true)))
        }
    }

    fun clearPage() {
        with(runtime) { _state.value.page?.let { clearPageContent(it.id) } }
    }

    fun clearPageContent(pageId: String) {
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            if (!page.loaded || (page.strokes.isEmpty() && page.texts.isEmpty() && page.images.isEmpty())) return
            replacePage(page.copy(strokes = emptyList(), texts = emptyList(), images = emptyList()))
        }
    }

    fun clearPagesInk(noteId: String, pageIds: List<String>) {
        with(runtime) {
            scope.launch {
                for (id in pageIds) {
                    loadPage(id)
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        state.first { s -> s.notes.notebook(noteId)?.pages?.find { it.id == id }?.loaded == true }
                    } ?: continue
                    clearPageContent(id)
                }
            }
        }
    }

    fun updateContent(pageId: String, strokes: List<Stroke>, texts: List<TextBox>, images: List<PageImage>) {
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            if (!page.loaded) return
            if (page.strokes === strokes && page.texts === texts && page.images === images) return
            if (page.strokes == strokes && page.texts == texts && page.images == images) return
            replacePage(page.copy(strokes = strokes, texts = texts, images = images))
        }
    }

    internal suspend fun awaitLoaded(pageId: String): NotePage? {
        with(runtime) {
            val noteId = findPage(pageId)?.id ?: return null
            loadPage(pageId)
            return withTimeoutOrNull(5_000) {
                _state.observePage(noteId, pageId).first { it == null || it.page.loaded }?.page
            }
        }
    }

    suspend fun pagesForSearch(notebookId: String): List<NotePage> {
        with(runtime) {
            val note = _state.value.notes.notebook(notebookId) ?: return emptyList()
            return note.pages.map { summary ->
                currentCoroutineContext().ensureActive()
                if (summary.loaded) summary else try {
                    summary.withLoadedContent(repository.loadPage(note.id, summary)).copy(strokes = emptyList(), images = emptyList())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { summary }
            }
        }
    }

    fun commitDocumentEdits(noteId: String, edits: Map<String, PageEdit>, expectedRevisions: Map<String, Int> = emptyMap()): Boolean {
        with(runtime) {
            val note = _state.value.active?.takeIf { it.id == noteId } ?: return false
            if (documentHistoryBusy) return false
            val id = java.util.UUID.randomUUID().toString()
            val sequence = actionCounter + 1
            val change = DocumentReducer.group(note, edits, expectedRevisions, id, sequence, System.currentTimeMillis()) { candidate ->
                val bounds = InkGeometry.contentBounds(candidate.strokes, candidate.texts, candidate.images, InkRenderer::textHeight, candidate.width, candidate.height)
                if (!candidate.infinite && candidate.pdfIndex == null && bounds.bottom > candidate.height) candidate.copy(height = bounds.bottom + 24f) else candidate
            } ?: return false
            actionCounter = sequence
            change.transactions.forEach { (pageId, transaction) ->
                undo.getOrPut(pageId) { mutableListOf() }.apply { add(requireNotNull(transaction.undoPush)); while (size > MAX_UNDO) removeAt(0) }
                redo[pageId]?.clear(); inkSeq[pageId] = sequence
                publishedHistory[pageId] = PageJournal.History(undo[pageId]?.toList().orEmpty(), emptyList())
                repository.requestCompaction(note.id, pageId) { currentCheckpoint(note.id, pageId) }
            }
            _state.update { state -> state.copy(notes = state.notes.replaceNotebook(change.notebook)) }
            structureRedo.removeAll { it.noteId == noteId }
            touchTimerActivity(); historyState()
            queue(WriteOp.Document(noteId, id, change.notebook.documentHistory, change.transactions, change.pages))
            return true
        }
    }

    fun appendDocumentStrokes(noteId: String, strokes: Map<String, List<Stroke>>, revisions: Map<String, Int>): Boolean = with(runtime) { commitDocumentEdits(noteId, strokes.filterValues { it.isNotEmpty() }.mapValues { PageEdit(StrokesEdit.Add(it.value)) }, revisions) }

    internal suspend fun loadDocumentHistoryPages(noteId: String, ids: List<String>) {
        with(runtime) {
            for (id in ids) {
                val note = _state.value.notes.notebook(noteId) ?: error("Notebook closed")
                val summary = note.pages.page(id) ?: error("Restore the deleted page before undoing this action")
                if (!summary.loaded) {
                    val checkpoint = repository.loadPageCheckpoint(noteId, summary)
                    val loaded = checkpoint.page; val history = checkpoint.history
                    val current = _state.value.notes.notebook(noteId)?.pages?.page(id)
                    if (current != null && !current.loaded && current.revision == summary.revision) {
                        installHistory(id, history)
                        _state.update { state ->
                            val live = state.notes.notebook(noteId) ?: return@update state
                            state.copy(notes = state.notes.replaceNotebook(live.withPage(current.withLoadedContent(loaded))))
                        }
                    }
                }
            }
        }
    }

    internal fun documentHistory(step: DocumentHistoryStep, wasUndo: Boolean) {
        with(runtime) {
            if (documentHistoryBusy) return
            val noteId = _state.value.activeId ?: return
            val generation = _state.value.editGeneration
            documentHistoryBusy = true
            _state.update { it.copy(documentHistoryBusy = true) }
            scope.launch {
                try {
                    loadDocumentHistoryPages(noteId, step.pages)
                    if (_state.value.editGeneration != generation || _state.value.activeId != noteId) return@launch
                    val note = _state.value.active ?: return@launch
                    val from = if (wasUndo) undo else redo; val to = if (wasUndo) redo else undo
                    val tops = step.pages.associateWith { from[it]?.lastOrNull() }
                    // A later local edit on any member must be undone first; never pop half a group.
                    val blocker = tops.entries.filter { it.value?.groupId != step.id }.maxByOrNull { it.value?.actionSequence ?: 0L }
                    if (blocker != null) {
                        if (blocker.value == null || blocker.value?.groupId != null) {
                            reportError("The complete grouped history is no longer available"); return@launch
                        }
                        val index = note.pages.pageIndex(blocker.key)
                        selectPage(index)
                        _state.update { it.copy(navigationRequest = it.navigationRequest + 1) }
                        if (wasUndo) inkRedoSeq[blocker.key] = ++actionCounter else inkSeq[blocker.key] = ++actionCounter
                        history(from, to)
                        return@launch
                    }
                    val sequence = actionCounter + 1
                    val operationId = UUID.randomUUID().toString()
                    val change = DocumentReducer.history(note, step, tops, wasUndo, sequence, operationId, System.currentTimeMillis())
                    if (change == null) {
                        reportError("The complete grouped history is no longer available"); return@launch
                    }
                    actionCounter = sequence
                    change.transactions.forEach { (pageId, transaction) ->
                        val inverse = requireNotNull(if (wasUndo) transaction.redoPush else transaction.undoPush)
                        from.getValue(pageId).removeAt(from.getValue(pageId).lastIndex)
                        to.getOrPut(pageId) { mutableListOf() }.apply { add(inverse); while (size > MAX_UNDO) removeAt(0) }
                        publishedHistory[pageId] = PageJournal.History(undo[pageId]?.toList().orEmpty(), redo[pageId]?.toList().orEmpty())
                        if (wasUndo) inkRedoSeq[pageId] = sequence else inkSeq[pageId] = sequence
                        repository.requestCompaction(noteId, pageId) { currentCheckpoint(noteId, pageId) }
                    }
                    _state.update { state -> state.copy(notes = state.notes.replaceNotebook(change.notebook)) }
                    queue(WriteOp.Document(noteId, operationId, change.notebook.documentHistory, change.transactions, change.pages))
                    touchTimerActivity(); historyState()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { reportError("Couldn't restore the grouped action: ${e.message.orEmpty()}") }
                finally { documentHistoryBusy = false; _state.update { it.copy(documentHistoryBusy = false) } }
            }
        }
    }

    internal fun undoDescriptions(): List<String> = with(runtime) {
        val note = _state.value.active ?: return emptyList()
        val page = _state.value.page
        fun pageName(id: String) = note.pages.indexOfFirst { it.id == id }.let { i -> if (i < 0) "removed page" else note.pages[i].displayTitle(i) }
        val ink = page?.let { p -> undo[p.id].orEmpty().filter { it.groupId == null }.mapIndexed { index, edit ->
            val channels = listOfNotNull("ink".takeIf { edit.strokes != null }, "text".takeIf { edit.texts != null }, "pictures".takeIf { edit.images != null }, "layers".takeIf { edit.layers != null }).joinToString(", ")
            (edit.actionSequence.takeIf { it > 0 } ?: ((inkSeq[p.id] ?: 0L) - undo[p.id].orEmpty().lastIndex + index)) to "$channels · ${pageName(p.id)}"
        } }.orEmpty()
        val structure = structureUndo.filter { it.noteId == note.id }.map { it.seq to it.label }
        val groups = note.documentHistory.undo.map { it.sequence to "Document edit · ${it.pages.joinToString { id -> pageName(id) }}" }
        (ink + structure + groups).sortedByDescending { it.first }.map { it.second }.take(60)
    }

    fun undo() {
        with(runtime) {
            if (documentHistoryBusy) return
            val state = _state.value
            val noteId = state.activeId ?: return
            val pageId = state.page?.id
            val step = structureUndo.lastOrNull { it.noteId == noteId }
            val active = state.active
            val group = historyAvailability.forNotebook(active).undo.lastOrNull()
            val inkTop = pageId?.takeUnless { undo[it].isNullOrEmpty() }?.let { maxOf(inkSeq[it] ?: 0L, undo[it]?.lastOrNull()?.actionSequence ?: 0L) }
            if (group != null && group.sequence >= (inkTop ?: 0L) && group.sequence >= (step?.seq ?: 0L)) {
                documentHistory(group, true); return
            }
            if (step != null && (inkTop == null || step.seq > inkTop)) {
                structureUndo.remove(step)
                touchTimerActivity()
                step.undo()
                step.seq = ++actionCounter
                structureRedo.add(step)
                historyState()
                return
            }
            undo[pageId]?.lastOrNull()?.groupId?.let { id ->
                val member = state.active?.documentHistory?.undo?.firstOrNull { it.id == id }
                if (member != null) documentHistory(member, true) else reportError("This grouped action is no longer in the notebook history")
                return
            }
            if (pageId != null) inkRedoSeq[pageId] = ++actionCounter
            history(undo, redo)
        }
    }

    fun redo() {
        with(runtime) {
            if (documentHistoryBusy) return
            val state = _state.value
            val noteId = state.activeId ?: return
            val pageId = state.page?.id
            val step = structureRedo.lastOrNull { it.noteId == noteId }
            val active = state.active
            val group = historyAvailability.forNotebook(active).redo.lastOrNull()
            val inkTop = pageId?.takeUnless { redo[it].isNullOrEmpty() }?.let { maxOf(inkRedoSeq[it] ?: 0L, redo[it]?.lastOrNull()?.actionSequence ?: 0L) }
            if (group != null && group.sequence >= (inkTop ?: 0L) && group.sequence >= (step?.seq ?: 0L)) {
                documentHistory(group, false); return
            }
            if (step != null && (inkTop == null || step.seq > inkTop)) {
                structureRedo.remove(step)
                touchTimerActivity()
                step.redo()
                step.seq = ++actionCounter
                structureUndo.add(step)
                historyState()
                return
            }
            redo[pageId]?.lastOrNull()?.groupId?.let { id ->
                val member = state.active?.documentHistory?.redo?.firstOrNull { it.id == id }
                if (member != null) documentHistory(member, false) else reportError("This grouped action is no longer in the notebook history")
                return
            }
            if (pageId != null) inkSeq[pageId] = ++actionCounter
            history(redo, undo)
        }
    }

    internal fun history(from: MutableMap<String, MutableList<PageEdit>>, to: MutableMap<String, MutableList<PageEdit>>) {
        with(runtime) {
            val page = _state.value.page ?: return
            if (!page.loaded) return
            if (from[page.id]?.lastOrNull()?.groupId != null) { reportError("Undo this action through document history"); return }
            val op = from[page.id]?.removeLastOrNull() ?: return
            touchTimerActivity()
            val note = findPage(page.id) ?: return
            val before = page.content()
            val afterContent = PageJournal.apply(before, op)
            val back = PageJournal.invert(op, before).let { if (note.documentHistory.enabled) it.copy(actionSequence = actionCounter) else it }
            // Bounded like the undo stack, and the same bound the stored log applies, so what survives a
            // restart is exactly what was on screen.
            to.getOrPut(page.id) { mutableListOf() }.apply {
                add(back); while (size > MAX_UNDO) removeAt(0)
            }
            val after = page.copy(strokes = afterContent.strokes, texts = afterContent.texts, images = afterContent.images, layers = afterContent.layers)
            val bounds = InkGeometry.contentBounds(after.strokes, after.texts, after.images, InkRenderer::textHeight, after.width, after.height)
            val revised = (if (!after.infinite && after.pdfIndex == null && bounds.bottom > after.height)
                after.copy(height = bounds.bottom + 24f) else after).revised()
            if (revised.height != page.height) enqueue { repository.saveMeta(note.withPage(revised)) }
            val updated = note.copy(pages = note.pages.replacePage(revised), updated = System.currentTimeMillis())
            _state.update { state -> state.copy(notes = state.notes.replaceNotebook(updated)) }
            // One record says both what the page now holds and which stack it came out of and went into.
            val wasUndo = from === undo
            repository.requestCompaction(note.id, page.id) { currentCheckpoint(note.id, page.id) }
            queue(WriteOp.Page(note.id, page.id, PageTransaction(0, revised.revision, op,
                undoPush = back.takeUnless { wasUndo }, undoPop = wasUndo,
                redoPush = back.takeIf { wasUndo }, redoPop = !wasUndo)))
            historyState()
        }
    }

    internal fun historyState() {
        with(runtime) {
            _state.update { state ->
                val noteId = state.activeId
                val note = state.active
                val available = historyAvailability.forNotebook(note)
                val groupsUndo = available.undo
                val groupsRedo = available.redo
                fun local(stack: List<PageEdit>?, groups: List<DocumentHistoryStep>): Boolean {
                    val top = stack?.lastOrNull() ?: return false
                    return top.groupId == null || groups.any { it.id == top.groupId }
                }
                state.copy(
                    canUndo = groupsUndo.isNotEmpty() || local(undo[state.page?.id], groupsUndo) || structureUndo.any { it.noteId == noteId },
                    canRedo = groupsRedo.isNotEmpty() || local(redo[state.page?.id], groupsRedo) || structureRedo.any { it.noteId == noteId })
            }
            // Every change to a page's stacks republishes them, so the copy the storage thread reads is
            // never the one being appended to.
            val pageId = _state.value.page?.id ?: return
            publishedHistory[pageId] = PageJournal.History(undo[pageId]?.toList().orEmpty(), redo[pageId]?.toList().orEmpty())
        }
    }
}
