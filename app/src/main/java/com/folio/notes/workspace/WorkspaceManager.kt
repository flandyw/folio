package com.folio.notes.workspace

import androidx.lifecycle.SavedStateHandle
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioRuntime
import com.folio.notes.app.FolioState
import com.folio.notes.app.PdfSearchState
import com.folio.notes.model.Notebook
import com.folio.notes.model.Tool
import com.folio.notes.model.notebook
import com.folio.notes.model.page
import com.folio.notes.model.pageIndex
import com.folio.notes.pdf.PdfReference
import com.folio.notes.pdf.PdfSearch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Application-owned WorkspaceManager commands over the single shared document runtime. */
internal class WorkspaceManager(private val runtime: FolioRuntime) {
    internal val positionPrefs = runtime.application.getSharedPreferences("notebook_positions", 0)
    internal val companionPositionPrefs = runtime.application.getSharedPreferences("companion_positions", 0)
    internal fun captureTab() {
        with(runtime) {
            val snapshot = _state.value
            val note = snapshot.active ?: return
            if (snapshot.page == null) return
            _state.update { state -> captureTabInto(state) }
            persistPosition(note.id)
        }
    }

    internal fun captureTabInto(next: FolioState): FolioState {
        with(runtime) {
            val note = next.active ?: return next
            val page = next.page ?: return next
            val old = next.tabs.find { it.notebookId == note.id }
                ?: EditorTab(note.id, note.id, page.id, note.title)
            val tab = old.copy(currentPageId = page.id, title = note.title, search = next.pdfSearch)
            return next.copy(tabs = next.tabs.withTab(tab))
        }
    }

    internal fun persistPosition(notebookId: String) {
        with(runtime) {
            val snapshot = _state.value
            val tab = snapshot.tabs.find { it.notebookId == notebookId } ?: return
            val livePageId = if (notebookId == snapshot.activeId) snapshot.page?.id ?: tab.currentPageId else tab.currentPageId
            val liveQuery = if (notebookId == snapshot.activeId) snapshot.pdfSearch.query else tab.search.query
            val position = NotebookPosition(livePageId, tab.viewport, tab.tool, liveQuery)
            positionPrefs.edit().putString(notebookId, NotebookPositionCodec.encode(position)).apply()
        }
    }

    internal fun persistCompanionPosition(pane: EditorTab? = runtime._state.value.companion) {
        with(runtime) {
            pane ?: return
            val encoded = NotebookPositionCodec.encode(NotebookPosition(pane.currentPageId, pane.viewport, pane.tool, ""))
            if (companionPositionPrefs.getString(pane.notebookId, null) != encoded) {
                companionPositionPrefs.edit().putString(pane.notebookId, encoded).apply()
            }
        }
    }

    internal fun rememberedTab(note: Notebook, reference: Boolean = false): EditorTab? {
        with(runtime) {
            val store = if (reference) companionPositionPrefs else positionPrefs
            val position = NotebookPositionCodec.decode(store.getString(note.id, null))
                ?: return null
            val pageId = position.pageId.takeIf { id -> note.pages.page(id) != null }
                ?: note.pages.firstOrNull()?.id ?: return null
            return position.copy(pageId = pageId).toTab(note.id, note.title)
        }
    }

    internal fun prunePositions(notes: List<Notebook>) {
        with(runtime) {
            val alive = notes.map { it.id }.toSet()
            for (store in listOf(positionPrefs, companionPositionPrefs)) {
                val stale = store.all.keys.filter { it !in alive }
                if (stale.isNotEmpty()) store.edit().apply { stale.forEach { remove(it) } }.apply()
            }
        }
    }

    fun updateTabViewport(id: String, viewport: WorkspaceViewport, tool: Tool) {
        with(runtime) {
            _state.update { state -> state.copy(tabs = state.tabs.map {
                if (it.id == id) it.copy(viewport = viewport, tool = tool) else it
            }) }
            _state.value.tabs.find { it.id == id }?.let { persistPosition(it.notebookId) }
        }
    }

    fun open(id: String) {
        with(runtime) {
            val target = _state.value.notes.notebook(id) ?: return
            captureTab()
            val tab = _state.value.tabs.find { it.notebookId == id }
                ?: rememberedTab(target)
                ?: EditorTab(id, id, target.pages.first().id, target.title)
            selectNotebookTimer(id)
            val before = _state.value
            // A split belongs to the notebook it was opened beside (an unknown host, from an older
            // session, is the notebook that was showing). Opening anything else parks it, and coming
            // back to that notebook brings the other pane back as it was left.
            val host = before.companionHostId ?: before.activeId ?: id
            val keep = before.companion?.takeIf { host == id }
            if (before.companion != null && keep == null) {
                persistCompanionPosition()
                parked = ParkedSplit(host, before.companion)
            }
            val restored = keep ?: parked?.takeIf { it.hostId == id && it.pane.notebookId.let { pane -> before.notes.notebook(pane) != null } }?.pane
            if (restored != null && keep == null) parked = null
            _state.update { it.copy(activeId = id,
                companion = restored,
                companionHostId = if (restored != null) id else it.companionHostId,
                tabs = it.tabs.withTab(tab),
                pageIndex = target.pages.pageIndex(tab.currentPageId).coerceAtLeast(0),
                pdfSearch = tab.search) }
            historyState()
            _state.value.page?.let { loadPage(it.id) }
            _state.value.companion?.let { loadPage(it.currentPageId) }
            if (tab.search.query.isNotBlank() && (tab.search.searching || !tab.search.searched)) searchPdf(tab.search.query)
        }
    }

    fun openAt(id: String, index: Int) {
        with(runtime) {
            open(id)
            if (_state.value.activeId == id) {
                selectPage(index)
                captureTab()
                _state.update { state -> state.copy(tabs = state.tabs.map { if (it.notebookId == id) it.copy(viewport = it.viewport.copy(scrollOffset = 0, documentPageId = _state.value.page?.id, documentPageY = 0f)) else it }) }
                persistPosition(id)
                _state.update { it.copy(navigationRequest = it.navigationRequest + 1) }
            }
        }
    }

    fun close() {
        with(runtime) {
            captureTab()
            selectNotebookTimer(null)
            _state.update { it.copy(activeId = null, pdfSearch = PdfSearchState()) }
        }
    }

    /**
     * Closes a document's tab. In split view the other half carries on alone: closing the pane being
     * worked on leaves the other document as the editor, closing the other half just closes its pane,
     * and a split parked behind a closed tab is forgotten with it.
     */
    fun closeTab(id: String) {
        with(runtime) {
            val before = _state.value
            val pane = before.companion
            when {
                pane == null -> Unit
                // The other half (or both halves, when both panes show this notebook): the split goes.
                pane.notebookId == id -> dismissCompanion()
                before.activeId == id -> closeEditorPane()
            }
            parked?.let { if (it.hostId == id || it.pane.notebookId == id) parked = null }
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
                        _state.update { it.copy(tabs = remaining) }
                        if (state.activeId == id) {
                            selectNotebookTimer(null)
                            _state.update { it.copy(activeId = null) }
                            adjacent?.let { open(it.notebookId) }
                        }
                    }
                }
            }
        }
    }

    /** Puts the tabs named in [order] (notebook ids) in that order; tabs it leaves out keep their slots. */
    fun reorderTabs(order: List<String>) {
        with(runtime) {
            _state.update { state ->
                val named = order.toSet()
                val moving = ArrayDeque(order.mapNotNull { id -> state.tabs.find { it.notebookId == id } })
                if (moving.size != state.tabs.count { it.notebookId in named }) return@update state
                val next = state.tabs.map { if (it.notebookId in named) moving.removeFirst() else it }
                if (next == state.tabs) state else state.copy(tabs = next)
            }
        }
    }

    fun closeOtherTabs(keepId: String) {
        with(runtime) {
            captureTab()
            enqueue {
                withContext(Dispatchers.Main.immediate) {
                    if (_state.value.saveFailed) {
                        reportError("Couldn't close the documents. Retry save first.")
                    } else {
                        _state.update { state ->
                            state.copy(
                                tabs = state.tabs.filter { it.id == keepId || it.notebookId == keepId }
                            )
                        }
                    }
                }
            }
        }
    }

    /** A split put aside while another notebook is open, restored when its host opens again. */
    private class ParkedSplit(val hostId: String, val pane: EditorTab)
    private var parked: ParkedSplit? = null

    /**
     * Opens [id] in the second pane. The pane starts where it was last read, unless [pageId] asks for
     * a page; the editor's own notebook opens on the page being edited, so two pages of one notebook
     * are a single tap apart.
     */
    fun showCompanion(id: String, pageId: String? = null) {
        with(runtime) {
            captureTab()
            val note = _state.value.notes.notebook(id) ?: return
            persistCompanionPosition()
            val requested = pageId?.takeIf { note.pages.page(it) != null }
                ?: _state.value.page?.id?.takeIf { id == _state.value.activeId }
            val source = requested?.let { EditorTab(id, id, it, note.title) }
                ?: rememberedTab(note, reference = true)
                ?: _state.value.tabs.find { it.notebookId == id }
                ?: rememberedTab(note)
                ?: EditorTab(id, id, note.pages.first().id, note.title)
            parked = null
            // The side the editor sits on is the user's, not the pane's: opening a companion leaves
            // it wherever they last put it.
            // The other document gets a tab of its own, so closing the split leaves both open like a browser.
            _state.update { it.copy(companion = source.copy(id = "companion", search = PdfSearchState()), companionPdfSearch = PdfSearchState(),
                companionHostId = it.activeId,
                tabs = if (it.tabs.any { tab -> tab.notebookId == id }) it.tabs else it.tabs + source.copy(id = id, search = PdfSearchState())) }
            loadPage(source.currentPageId)
        }
    }

    fun dismissCompanion() {
        with(runtime) { persistCompanionPosition(); parked = null; _state.update { it.copy(companion = null, companionPdfSearch = PdfSearchState()) } }
    }

    /** The editor's pane was closed: the other document carries on alone, as the editor. */
    fun closeEditorPane() {
        if (runtime._state.value.companion == null) return
        swapCompanion()
        dismissCompanion()
    }

    fun workspaceSnapshot(name: String): org.json.JSONObject? = with(runtime) {
        captureTab()
        val snapshot = _state.value
        val note = snapshot.active ?: return null
        val companion = snapshot.companion ?: return null
        val editor = snapshot.tabs.firstOrNull { it.notebookId == note.id } ?: return null
        fun position(tab: EditorTab) = NotebookPositionCodec.encode(NotebookPosition(tab.currentPageId, tab.viewport, tab.tool, tab.search.query))
        val offsetKey = AppPrefs.UX_LINK_OFFSET + ".${note.id}.${companion.notebookId}"
        org.json.JSONObject().put("name", name).put("editor", note.id).put("companion", companion.notebookId)
            .put("editorPosition", position(editor)).put("companionPosition", position(companion))
            .put("fraction", snapshot.splitFraction.toDouble()).put("right", snapshot.editorOnRight).put("linked", snapshot.companionLinked).put("offset", prefs.getInt(offsetKey, 0))
    }
    fun restoreWorkspace(item: org.json.JSONObject): Boolean = with(runtime) {
        val editorId = item.optString("editor"); val companionId = item.optString("companion")
        if (_state.value.notes.none { it.id == editorId } || _state.value.notes.none { it.id == companionId }) { reportError("A saved document is unavailable"); return false }
        val editorPosition = NotebookPositionCodec.decode(item.optString("editorPosition")) ?: return false
        val companionPosition = NotebookPositionCodec.decode(item.optString("companionPosition")) ?: return false
        captureTab()
        open(editorId)
        showCompanion(companionId)
        val current = _state.value
        val editorNote = current.notes.first { it.id == editorId }
        val referenceNote = current.notes.first { it.id == companionId }
        val editorIndex = editorNote.pages.indexOfFirst { it.id == editorPosition.pageId }.coerceAtLeast(0)
        val referenceIndex = referenceNote.pages.indexOfFirst { it.id == companionPosition.pageId }.coerceAtLeast(0)
        val restoredEditor = editorPosition.copy(pageId = editorNote.pages[editorIndex].id).toTab(editorId, editorNote.title)
        _state.update { state -> state.copy(
            pageIndex = editorIndex,
            tabs = state.tabs.filterNot { it.notebookId == editorId } + restoredEditor,
            pdfSearch = restoredEditor.search,
            companion = state.companion?.copy(currentPageId = referenceNote.pages[referenceIndex].id, viewport = companionPosition.viewport),
            workspaceRestoreRequest = state.workspaceRestoreRequest + 1,
            navigationRequest = state.navigationRequest + 1)
        }
        setSplitFraction(item.optDouble("fraction", .5).toFloat()); setEditorOnRight(item.optBoolean("right")); setCompanionLinked(item.optBoolean("linked"))
        prefs.edit().putInt(AppPrefs.UX_LINK_OFFSET + ".$editorId.$companionId", AppPrefs.uxLinkOffset(item.optInt("offset"))).apply()
        persistPosition(editorId); persistCompanionPosition()
        loadPage(editorNote.pages[editorIndex].id); loadPage(referenceNote.pages[referenceIndex].id)
        true
    }

    fun setSplitFraction(fraction: Float) {
        with(runtime) {
            val next = SplitPanes.coerce(fraction)
            prefs.edit().putFloat(AppPrefs.SPLIT_FRACTION, next).apply()
            _state.update { if (it.splitFraction == next) it else it.copy(splitFraction = next) }
        }
    }

    fun setCompanionLinked(linked: Boolean) {
        with(runtime) {
            _state.update { if (it.companionLinked == linked) it else it.copy(companionLinked = linked) }
        }
    }

    fun swapPaneSides(): Unit = with(runtime) { setEditorOnRight(!_state.value.editorOnRight) }

    fun setEditorOnRight(right: Boolean) {
        with(runtime) {
            if (_state.value.companion == null || _state.value.editorOnRight == right) return
            prefs.edit().putBoolean(AppPrefs.EDITOR_ON_RIGHT, right).apply()
            _state.update { it.copy(editorOnRight = right) }
        }
    }

    fun companionPage(index: Int) {
        with(runtime) {
            val pane = _state.value.companion ?: return
            val note = _state.value.notes.notebook(pane.notebookId) ?: return
            val page = note.pages.getOrNull(index) ?: return
            // A deliberate zoom (fit width, say) survives the turn; an untouched pane resets as before.
            _state.update { it.copy(companion = pane.copy(currentPageId = page.id, viewport = PdfReference.carriedFrame(pane.viewport))) }
            persistCompanionPosition()
            loadPage(page.id)
        }
    }

    fun searchCompanionPdf(query: String) {
        with(runtime) {
            val noteId = _state.value.companion?.notebookId ?: return
            if (query.isBlank()) { _state.update { it.copy(companionPdfSearch = PdfSearchState()) }; return }
            _state.update { it.copy(companionPdfSearch = PdfSearchState(query = query, searching = true, searched = true)) }
            scope.launch {
                val pages = try { repository.pdfs.pdfPageTexts(noteId) } catch (_: Exception) { emptyList() }
                val hits = withContext(Dispatchers.Default) { PdfSearch.search(pages, query) }
                val current = _state.value
                // A search finishing after its pane closed, or after a newer query, belongs nowhere.
                if (current.companion?.notebookId != noteId || current.companionPdfSearch.query != query) return@launch
                _state.update { it.copy(companionPdfSearch = PdfSearchState(query = query, searched = true, results = hits)) }
            }
        }
    }

    fun clearCompanionPdfSearch() {
        with(runtime) { _state.update { it.copy(companionPdfSearch = PdfSearchState()) } }
    }

    fun updateCompanionViewport(viewport: WorkspaceViewport) {
        with(runtime) {
            _state.update { it.copy(companion = it.companion?.copy(viewport = viewport)) }
        }
    }

    /**
     * Moves the editor into the other pane. The documents stay where they are on screen: the editor
     * changes side and takes the other pane's share, so the divider does not move either. The tool
     * travels with the toolbar, so working in the other document never changes what the pen does.
     */
    fun swapCompanion() {
        with(runtime) {
            persistCompanionPosition()
            captureTab()
            val pane = _state.value.companion ?: return
            val active = _state.value.tabs.find { it.notebookId == _state.value.activeId } ?: return
            val share = SplitPanes.coerce(1f - _state.value.splitFraction)
            prefs.edit().putFloat(AppPrefs.SPLIT_FRACTION, share).putBoolean(AppPrefs.EDITOR_ON_RIGHT, !_state.value.editorOnRight).apply()
            _state.update { it.copy(companion = active.copy(id = "companion", search = PdfSearchState()), companionPdfSearch = PdfSearchState(),
                editorOnRight = !it.editorOnRight, splitFraction = share, companionHostId = pane.notebookId,
                tabs = it.tabs.withTab(pane.copy(id = pane.notebookId, tool = active.tool))) }
            // Avoid capturing the old active page over the destination (same-notebook split).
            selectNotebookTimer(null)
            _state.update { it.copy(activeId = null) }
            open(pane.notebookId)
            _state.update { it.copy(navigationRequest = it.navigationRequest + 1) }
        }
    }

    fun selectPage(index: Int) {
        with(runtime) {
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
                    val companionNote = next.notes.notebook(pane.notebookId)
                    val offset = prefs.getInt(AppPrefs.UX_LINK_OFFSET + ".${note.id}.${pane.notebookId}", 0)
                    val pageId = if (companionNote == null) null else if (offset == 0) linkedCompanionTarget(note, companionNote, target)
                        else companionNote.pages.getOrNull((target + offset).coerceIn(0, companionNote.pages.lastIndex))?.id
                    if (pageId != null && pageId != pane.currentPageId) {
                        next = next.copy(companion = pane.copy(currentPageId = pageId, viewport = WorkspaceViewport()))
                    }
                }
                next
            }
            persistPosition(note.id)
            persistCompanionPosition()
            historyState()
            _state.value.page?.let { loadPage(it.id) }
            _state.value.companion?.let { loadPage(it.currentPageId) }
        }
    }

    fun searchPdf(query: String) {
        with(runtime) {
            val note = _state.value.active ?: return
            if (query.isBlank()) { _state.update { it.copy(pdfSearch = PdfSearchState()) }; captureTab(); return }
            val noteId = note.id
            _state.update { it.copy(pdfSearch = PdfSearchState(query = query, searching = true, searched = true)) }
            captureTab()
            scope.launch {
                val pages = try { repository.pdfs.pdfPageTexts(noteId) } catch (_: Exception) { emptyList() }
                // Matching is CPU-bound; keep it off the main thread.
                val hits = withContext(Dispatchers.Default) { PdfSearch.search(pages, query) }
                // A search finishing after its notebook closed belongs nowhere.
                if (_state.value.activeId != noteId || _state.value.pdfSearch.query != query) return@launch
                _state.update { it.copy(pdfSearch = PdfSearchState(query = query, searched = true, results = hits)) }
                captureTab()
            }
        }
    }

    fun clearPdfSearch() {
        with(runtime) { _state.update { it.copy(pdfSearch = PdfSearchState()) }; captureTab() }
    }
    internal fun start() { with(runtime) {
        scope.launch {
            state
                .distinctUntilChanged { a, b ->
                    a.activeId == b.activeId && a.pageIndex == b.pageIndex && a.folderId == b.folderId &&
                        a.tabs == b.tabs && a.companion == b.companion &&
                        a.splitFraction == b.splitFraction && a.companionLinked == b.companionLinked && a.companionHostId == b.companionHostId &&
                        a.editorOnRight == b.editorOnRight && a.pdfSearch == b.pdfSearch
                }
                .collect { savedState["activeId"] = it.activeId; savedState["pageIndex"] = it.pageIndex; savedState["folderId"] = it.folderId
            val tabSession = WorkspaceSessionCodec.encode(it.tabs.map { tab ->
                if (tab.notebookId == it.activeId) tab.copy(currentPageId = it.page?.id ?: tab.currentPageId, search = it.pdfSearch) else tab
            })
            savedState["workspaceTabs"] = tabSession
            persistCompanionPosition(it.companion)
            val companionSession = WorkspaceSessionCodec.encode(listOfNotNull(it.companion))
            savedState["workspaceCompanion"] = companionSession
            // SavedStateHandle covers recreation; preferences also cover a fresh app launch.
            prefs.edit()
                .putString("workspaceCompanion", companionSession)
                .putString("workspaceTabs", tabSession)
                .putBoolean("companionLinked", it.companionLinked)
                .putString("companionHostId", it.companionHostId)
                .apply()
            savedState["companionHostId"] = it.companionHostId
            savedState["splitFraction"] = it.splitFraction
            savedState["companionLinked"] = it.companionLinked
            savedState["editorOnRight"] = it.editorOnRight
        } }
    } }

    private var sessionAttached = false
    internal fun restoreSession(handle: SavedStateHandle) { with(runtime) {
        if (sessionAttached) return
        sessionAttached = true
        if (!handle.contains("activeId") && !handle.contains("workspaceTabs")) return
        val tabs = WorkspaceSessionCodec.decode(handle["workspaceTabs"])
        _state.update { it.copy(activeId = handle["activeId"], pageIndex = handle["pageIndex"] ?: 0,
            folderId = handle["folderId"], tabs = tabs,
            pdfSearch = tabs.find { tab -> tab.notebookId == handle.get<String>("activeId") }?.search ?: PdfSearchState(),
            companion = if (handle.contains("workspaceCompanion")) WorkspaceSessionCodec.decode(handle["workspaceCompanion"]).firstOrNull() else it.companion,
            companionHostId = if (handle.contains("companionHostId")) handle["companionHostId"] else it.companionHostId,
            splitFraction = AppPrefs.splitFraction(handle.get<Float>("splitFraction") ?: it.splitFraction),
            companionLinked = handle["companionLinked"] ?: it.companionLinked,
            editorOnRight = handle["editorOnRight"] ?: it.editorOnRight) }
        selectNotebookTimer(_state.value.activeId)
    } }

    internal fun copySessionTo(handle: SavedStateHandle, state: FolioState) { with(runtime) {
        handle["activeId"] = state.activeId
        handle["pageIndex"] = state.pageIndex
        handle["folderId"] = state.folderId
        handle["workspaceTabs"] = WorkspaceSessionCodec.encode(state.tabs.map { tab ->
            if (tab.notebookId == state.activeId) tab.copy(currentPageId = state.page?.id ?: tab.currentPageId, search = state.pdfSearch) else tab
        })
        handle["workspaceCompanion"] = WorkspaceSessionCodec.encode(listOfNotNull(state.companion))
        handle["companionHostId"] = state.companionHostId
        handle["splitFraction"] = state.splitFraction
        handle["companionLinked"] = state.companionLinked
        handle["editorOnRight"] = state.editorOnRight
    } }


}
