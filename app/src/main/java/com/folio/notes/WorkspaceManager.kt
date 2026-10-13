package com.folio.notes

import android.app.Application
import kotlinx.coroutines.*
import androidx.lifecycle.SavedStateHandle
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
            _state.update { it.copy(activeId = id,
                // A reference stays with the notebook it was opened beside (an unknown host, from an
                // older session, is the notebook that was showing).
                companion = it.companion?.takeUnless { _ ->
                    it.companionMode == CompanionMode.REFERENCE && (it.companionHostId ?: it.activeId ?: id) != id
                },
                tabs = it.tabs.withTab(tab),
                pageIndex = target.pages.pageIndex(tab.currentPageId).coerceAtLeast(0),
                pdfSearch = tab.search) }
            historyState()
            _state.value.page?.let { loadPage(it.id) }
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

    fun closeTab(id: String) {
        with(runtime) {
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

    fun showCompanion(id: String, mode: CompanionMode) {
        with(runtime) {
            captureTab()
            val note = _state.value.notes.notebook(id) ?: return
            persistCompanionPosition()
            val source = (if (mode == CompanionMode.REFERENCE) rememberedTab(note, reference = true) else null)
                ?: _state.value.tabs.find { it.notebookId == id }
                ?: rememberedTab(note)
                ?: EditorTab(id, id, note.pages.first().id, note.title)
            // The side the editor sits on is the user's, not the pane's: opening a companion leaves
            // it wherever they last put it.
            _state.update { it.copy(companion = source.copy(id = "companion"), companionMode = mode, companionPdfSearch = PdfSearchState(),
                companionHostId = it.activeId,
                tabs = if (mode == CompanionMode.SPLIT) it.tabs.withTab(source) else it.tabs) }
            loadPage(source.currentPageId)
        }
    }

    fun dismissCompanion() {
        with(runtime) { persistCompanionPosition(); _state.update { it.copy(companion = null, companionPdfSearch = PdfSearchState()) } }
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

    fun setCompanionMode(mode: CompanionMode) {
        with(runtime) {
            val current = _state.value
            if (current.companionMode == mode) return
            captureTab()
            _state.update { it.copy(companionMode = mode,
                tabs = if (mode == CompanionMode.SPLIT && it.companion != null) it.tabs.withTab(it.companion.copy(id = it.companion.notebookId)) else it.tabs) }
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
                val pages = try { repository.pdfPageTexts(noteId) } catch (_: Exception) { emptyList() }
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

    fun swapCompanion() {
        with(runtime) {
            persistCompanionPosition()
            captureTab()
            val pane = _state.value.companion ?: return
            val active = _state.value.tabs.find { it.notebookId == _state.value.activeId } ?: return
            _state.update { it.copy(companion = active.copy(id = "companion"),
                editorOnRight = !it.editorOnRight, companionHostId = pane.notebookId,
                tabs = it.tabs.withTab(pane.copy(id = pane.notebookId))) }
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
                    val pageId = if (companionNote != null) linkedCompanionTarget(note, companionNote, target) else null
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
                val pages = try { repository.pdfPageTexts(noteId) } catch (_: Exception) { emptyList() }
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
                        a.tabs == b.tabs && a.companion == b.companion && a.companionMode == b.companionMode &&
                        a.splitFraction == b.splitFraction && a.companionLinked == b.companionLinked && a.companionHostId == b.companionHostId &&
                        a.editorOnRight == b.editorOnRight && a.pdfSearch == b.pdfSearch
                }
                .collect { savedState["activeId"] = it.activeId; savedState["pageIndex"] = it.pageIndex; savedState["folderId"] = it.folderId
            savedState["workspaceTabs"] = WorkspaceSessionCodec.encode(it.tabs.map { tab ->
                if (tab.notebookId == it.activeId) tab.copy(currentPageId = it.page?.id ?: tab.currentPageId, search = it.pdfSearch) else tab
            })
            persistCompanionPosition(it.companion)
            val companionSession = WorkspaceSessionCodec.encode(listOfNotNull(it.companion))
            savedState["workspaceCompanion"] = companionSession
            // SavedStateHandle covers recreation; preferences also cover a fresh app launch.
            prefs.edit()
                .putString("workspaceCompanion", companionSession)
                .putString("workspaceMode", it.companionMode.name)
                .putBoolean("companionLinked", it.companionLinked)
                .putString("companionHostId", it.companionHostId)
                .apply()
            savedState["companionHostId"] = it.companionHostId
            savedState["workspaceMode"] = it.companionMode.name
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
            companionMode = handle.get<String>("workspaceMode")?.let { mode -> runCatching { CompanionMode.valueOf(mode) }.getOrNull() } ?: it.companionMode,
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
        handle["workspaceMode"] = state.companionMode.name
        handle["companionHostId"] = state.companionHostId
        handle["splitFraction"] = state.splitFraction
        handle["companionLinked"] = state.companionLinked
        handle["editorOnRight"] = state.editorOnRight
    } }


}
