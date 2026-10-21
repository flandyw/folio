package com.folio.notes.storage

import com.folio.notes.FolioApplication
import com.folio.notes.app.FolioRuntime
import com.folio.notes.app.PdfSearchState
import com.folio.notes.backup.LibraryAutoBackup
import com.folio.notes.exam.ExamAttempt
import com.folio.notes.exam.ExamFilter
import com.folio.notes.exam.ExamStatus
import com.folio.notes.exam.ExamTagType
import com.folio.notes.exam.ExamTags
import com.folio.notes.model.Folder
import com.folio.notes.model.Notebook
import com.folio.notes.model.asSummary
import com.folio.notes.model.duplicateNotebookTitle
import com.folio.notes.model.notebook
import com.folio.notes.model.replaceNotebook
import com.folio.notes.model.withAttempt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/** Application-owned NotebookCatalog commands over the single shared document runtime. */
internal class NotebookCatalog(private val runtime: FolioRuntime) {
    internal fun updateNote(note: Notebook) {
        with(runtime) {
            val updated = note.copy(updated = System.currentTimeMillis())
            if (_state.value.notes.none { it.id == updated.id }) return
            _state.update { state -> state.copy(notes = state.notes.replaceNotebook(updated)) }
            enqueue { repository.saveMeta(updated) }
        }
    }

    internal fun updateNotes(ids: Set<String>, transform: (Notebook) -> Notebook) {
        with(runtime) {
            if (ids.isEmpty()) return
            val now = System.currentTimeMillis()
            var changed: List<Notebook> = emptyList()
            _state.update { state ->
                val updated = state.notes.map { note ->
                    if (note.id !in ids) note
                    else {
                        val next = transform(note)
                        if (next == note) note else next.copy(updated = now)
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
    }

    fun folder(id: String?) {
        with(runtime) { _state.update { it.copy(folderId = id) } }
    }

    fun createFolder(name: String, parentId: String? = runtime._state.value.folderId): Boolean {
        with(runtime) {
            val state = _state.value
            if (state.loadFailed || state.loading || !validFolderName(name, parentId)) return false
            val folders = state.folders + Folder(name = name.trim().take(120), parentId = parentId)
            _state.update { it.copy(folders = folders) }
            enqueue { repository.saveFolders(folders) }
            return true
        }
    }

    internal fun validFolderName(name: String, parentId: String?, except: String? = null): Boolean {
        with(runtime) {
            val folders = _state.value.folders
            if (name.isBlank() || (parentId != null && folders.none { it.id == parentId })) return false
            if (folders.any { it.id != except && it.parentId == parentId && it.name.equals(name.trim().take(120), true) }) {
                reportError("A folder with that name already exists here")
                return false
            }
            return true
        }
    }

    fun createFolderAndMove(ids: Set<String>, name: String, parentId: String? = null): Boolean {
        with(runtime) {
            val state = _state.value
            if (state.loadFailed || state.loading || ids.none { id -> state.notes.any { it.id == id } } || !validFolderName(name, parentId)) return false
            val folder = Folder(name = name.trim().take(120), parentId = parentId)
            val folders = state.folders + folder
            _state.update { it.copy(folders = folders) }
            enqueue { repository.saveFolders(folders) }
            moveNotebooks(ids, folder.id)
            return true
        }
    }

    fun renameFolder(folder: Folder, name: String): Boolean {
        with(runtime) {
            val live = _state.value.folders.find { it.id == folder.id } ?: return false
            if (!validFolderName(name, live.parentId, live.id)) return false
            val folders = _state.value.folders.map { if (it.id == live.id) it.copy(name = name.trim().take(120)) else it }
            _state.update { it.copy(folders = folders) }
            enqueue { repository.saveFolders(folders) }
            return true
        }
    }

    fun recolorFolder(id: String, color: Int) {
        with(runtime) {
            val folders = _state.value.folders.map { if (it.id == id) it.copy(color = color.coerceAtLeast(0)) else it }
            _state.update { it.copy(folders = folders) }
            enqueue { repository.saveFolders(folders) }
        }
    }

    fun moveFolder(id: String, parentId: String?): Boolean {
        with(runtime) {
            val state = _state.value
            val folder = state.folders.find { it.id == id } ?: return false
            if (!LibraryFolders.canMove(state.folders, id, parentId) || !validFolderName(folder.name, parentId, id)) return false
            val folders = state.folders.map { if (it.id == id) it.copy(parentId = parentId) else it }
            _state.update { it.copy(folders = folders) }
            enqueue { repository.saveFolders(folders) }
            return true
        }
    }

    fun deleteFolder(folder: Folder) {
        with(runtime) {
            val live = _state.value.folders.find { it.id == folder.id } ?: return
            val affected = _state.value.notes.filter { it.folderId == live.id }.map { it.id }.toSet()
            if (affected.isNotEmpty()) updateNotes(affected) { it.copy(folderId = live.parentId) }
            val folders = _state.value.folders.filterNot { it.id == live.id }.map {
                if (it.parentId == live.id) it.copy(parentId = live.parentId) else it
            }
            _state.update { it.copy(folders = folders, folderId = if (it.folderId == live.id) live.parentId else it.folderId) }
            enqueue { repository.saveFolders(folders) }
        }
    }

    fun updateNotebookTags(ids: Set<String>, add: List<String>, remove: List<String> = emptyList()) {
        with(runtime) {
            val removed = remove.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
            updateNotes(ids) { note -> note.copy(tags = NotebookTags.normalize(
                note.tags.filterNot { it.lowercase(java.util.Locale.ROOT) in removed } + add)) }
        }
    }

    fun rename(note: Notebook, title: String) {
        with(runtime) {
            if (title.isNotBlank()) updateNotes(setOf(note.id)) { it.copy(title = title.trim()) }
        }
    }

    fun pin(note: Notebook, inFolder: Boolean): Unit = with(runtime) {
        updateNotes(setOf(note.id)) {
            if (inFolder) {
                if (it.folderId == null) it else it.copy(pinnedInFolder = !it.pinnedInFolder)
            } else it.copy(pinned = !it.pinned)
        }
    }

    fun star(note: Notebook): Unit = with(runtime) { updateNotes(setOf(note.id)) { it.copy(starred = !it.starred) } }

    fun move(note: Notebook, folderId: String?) {
        with(runtime) {
            if (folderId != null && _state.value.folders.none { it.id == folderId }) return
            updateNotes(setOf(note.id)) { it.copy(folderId = folderId) }
        }
    }

    fun setCover(note: Notebook, cover: Int) {
        with(runtime) {
            if (note.cover != cover) updateNotes(setOf(note.id)) { it.copy(cover = cover) }
        }
    }

    fun setPageCover(note: Notebook, pageCover: Boolean) {
        with(runtime) {
            updateNotes(setOf(note.id)) { it.copy(pageCover = pageCover) }
        }
    }

    fun updateExamTags(noteId: String, tags: ExamTags) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            updateNote(note.copy(exam = tags))
        }
    }

    fun toggleExamTag(noteId: String, tag: ExamTagType) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val tags = note.exam
            val updated = if (tag in tags.tags) tags.copy(tags = tags.tags - tag) else tags.copy(tags = tags.tags + tag)
            updateNote(note.copy(exam = updated))
        }
    }

    fun setExamFilter(filter: ExamFilter) {
        with(runtime) { _state.update { it.copy(examFilter = filter) } }
    }

    fun toggleRedoFlag() {
        with(runtime) {
            val page = _state.value.page ?: return
            if (!page.loaded) return
            replacePage(page.copy(redoFlag = !page.redoFlag))
        }
    }

    fun recordAttempt(noteId: String, attempt: ExamAttempt, responseAttemptId: String? = null) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            var exam = if (note.exam.status == ExamStatus.TO_DO) note.exam.copy(status = ExamStatus.MARKED) else note.exam
            if (exam.marksTotal == null && attempt.total != null && attempt.total > 0) {
                exam = exam.copy(marksTotal = attempt.total)
            }
            val responseAttempt = note.longResponse?.attempts?.find { it.id == responseAttemptId }
            val recorded = responseAttempt?.resultId?.let { attempt.copy(id = it) } ?: attempt
            updateNote(note.withAttempt(recorded).copy(exam = exam,
                longResponse = if (responseAttempt == null) note.longResponse else note.longResponse?.updateAttempt(responseAttempt.id) { it.copy(resultId = recorded.id) }))
            // A timed sitting is spent on the mark it belongs to, not offered to the next one.
            if (attempt.timed) {
                notebookSittings.consumeResult(noteId)
                if (_state.value.activeId == noteId) _state.update { it.copy(lastTimedSeconds = null) }
            }
        }
    }

    fun deleteAttempt(noteId: String, attemptId: String) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            updateNote(note.copy(attempts = note.attempts.filterNot { it.id == attemptId }))
        }
    }

    fun updateAttempt(noteId: String, attempt: ExamAttempt) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            if (note.attempts.none { it.id == attempt.id }) return
            updateNote(note.copy(attempts = note.attempts.map { if (it.id == attempt.id) attempt else it }))
        }
    }

    fun moveNotebooks(ids: Set<String>, folderId: String?) {
        with(runtime) {
            if (folderId != null && _state.value.folders.none { it.id == folderId }) return
            val affected = _state.value.notes.filter { it.id in ids && it.folderId != folderId }.map { it.id }.toSet()
            if (affected.isNotEmpty()) updateNotes(affected) { it.copy(folderId = folderId) }
        }
    }

    fun favoriteNotebooks(ids: Set<String>, starred: Boolean) {
        with(runtime) {
            val affected = _state.value.notes.filter { it.id in ids && it.starred != starred }.map { it.id }.toSet()
            if (affected.isNotEmpty()) updateNotes(affected) { it.copy(starred = starred) }
        }
    }

    fun setPageCoverBatch(ids: Set<String>, pageCover: Boolean) {
        with(runtime) {
            val affected = _state.value.notes.filter { it.id in ids && it.pageCover != pageCover }.map { it.id }.toSet()
            if (affected.isNotEmpty()) updateNotes(affected) { it.copy(pageCover = pageCover) }
        }
    }

    fun updateExamTagsBatch(ids: Set<String>, transform: (ExamTags) -> ExamTags) {
        with(runtime) {
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
                    next.id in ids && state.notes.notebook(next.id) != next
                }
                state.copy(notes = updated)
            }
            if (changed.isEmpty()) return
            enqueue { changed.forEach { repository.saveMeta(it) } }
        }
    }

    fun duplicateNotebook(note: Notebook) {
        with(runtime) {
            scope.launch {
                try {
                    ready.await()
                    awaitQueuedWrites()
                    val live = _state.value.notes.notebook(note.id) ?: return@launch
                    val title = duplicateNotebookTitle(live.title, _state.value.notes.map { it.title }.toSet())
                    getApplication<FolioApplication>().storageGate.withLock {
                        val copy = repository.duplicateNotebook(live, title)
                        _state.update { it.copy(notes = it.notes + copy) }
                        // Publish the copy's preference before an automatic backup can capture it.
                        if (live.id in _state.value.backupExcludedNotebookIds) setBackupExcluded(setOf(copy.id), true)
                    }
                    LibraryAutoBackup.requestAfterSave(getApplication<FolioApplication>())
                } catch (e: Exception) {
                    reportError("Couldn't duplicate notebook: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    fun delete(note: Notebook) = deleteNotebooks(setOf(note.id))

    fun deleteNotebooks(ids: Set<String>) {
        with(runtime) {
            val state = _state.value
            if (state.loading || state.loadFailed || state.saveFailed) return
            val removed = state.notes.filter { it.id in ids && it.musicScoreId == null }
            if (removed.isEmpty()) return
            val removedIds = removed.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            if (state.activeId in removedIds) selectNotebookTimer(null)
            _state.update {
                it.copy(notes = it.notes.filterNot { n -> n.id in removedIds },
                    trash = removed.map { n -> TrashedNotebook(n.copy(pages = n.pages.map { p -> p.asSummary() }), now) } + it.trash,
                    trashBusyIds = it.trashBusyIds + removedIds,
                    tabs = it.tabs.filterNot { tab -> tab.notebookId in removedIds },
                    companion = it.companion?.takeUnless { tab -> tab.notebookId in removedIds },
                    activeId = it.activeId?.takeUnless { id -> id in removedIds },
                    pdfSearch = if (it.activeId in removedIds) PdfSearchState() else it.pdfSearch)
            }
            removed.forEach { note ->
                enqueue {
                    // A failed operation stays queued for Retry save, including its final metadata.
                    repository.moveToTrash(note, now)
                    thumbnails.clear(note.id)
                    _state.update { it.copy(trashBusyIds = it.trashBusyIds - note.id) }
                }
            }
        }
    }

    fun restoreNotebooks(ids: Set<String>) {
        with(runtime) {
            val state = _state.value
            if (state.loading || state.loadFailed || state.saveFailed) return
            val selected = state.trash.filter { it.notebook.id in ids && it.notebook.id !in state.trashBusyIds }
            _state.update { it.copy(trashBusyIds = it.trashBusyIds + selected.map { n -> n.notebook.id }) }
            selected.forEach { entry -> enqueue {
                val note = repository.restoreFromTrash(entry.notebook.id, _state.value.folders)
                _state.update { it.copy(notes = it.notes + note,
                    trash = it.trash.filterNot { n -> n.notebook.id == note.id },
                    trashBusyIds = it.trashBusyIds - note.id) }
            } }
        }
    }

    fun deleteTrashedNotebooks(ids: Set<String>) {
        with(runtime) {
            val state = _state.value
            if (state.loading || state.loadFailed || state.saveFailed) return
            val selected = state.trash.filter { it.notebook.id in ids && it.notebook.id !in state.trashBusyIds }
            _state.update { it.copy(trashBusyIds = it.trashBusyIds + selected.map { n -> n.notebook.id }) }
            selected.forEach { entry -> enqueue {
                val id = entry.notebook.id
                repository.deleteFromTrash(id)
                positionPrefs.edit().remove(id).apply()
                companionPositionPrefs.edit().remove(id).apply()
                _state.update { it.copy(trash = it.trash.filterNot { n -> n.notebook.id == id },
                    trashBusyIds = it.trashBusyIds - id) }
            } }
        }
    }

    internal fun discard(note: Notebook) {
        with(runtime) {
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
            companionPositionPrefs.edit().remove(note.id).apply()
            thumbnails.clear(note.id)
            enqueue {
                try { repository.delete(note.id) }
                catch (e: Exception) { _state.update { it.copy(notes = it.notes + note) }; throw e }
            }
        }
    }

    internal fun discardNotebooks(ids: Set<String>) {
        with(runtime) {
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
            companionPositionPrefs.edit().apply { removed.forEach { remove(it.id) } }.apply()
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
    }
    fun loadLibrary() { with(runtime) {
        if (ready.isCompleted) ready = CompletableDeferred()
        _state.update { it.copy(loading = true, loadFailed = false) }
        scope.launch {
            try {
                val (notes, folders) = repository.load()
                val trash = repository.loadTrash()
                actionCounter = maxOf(actionCounter, notes.flatMap { it.documentHistory.undo + it.documentHistory.redo }.maxOfOrNull { it.sequence } ?: 0L)
                migrateLegacyTimer(notes)
                prunePositions(notes + trash.map { it.notebook })
                _state.update { state -> state.copy(notes = notes, folders = folders, trash = trash, loading = false,
                    activeId = state.activeId?.takeIf { id -> notes.any { it.id == id } },
                    tabs = state.tabs.filter { tab -> notes.any { it.id == tab.notebookId } },
                    companion = state.companion?.takeIf { tab -> notes.any { it.id == tab.notebookId } }) }
                captureTab()
                ready.complete(Unit)
                // Empty practice pages from older versions never reach the shelf again.
                scope.launch {
                    try { purgeEmptyMistakeNotebooks() } catch (_: Exception) { }
                }
                val search = _state.value.pdfSearch
                if (search.query.isNotBlank() && !search.searched) searchPdf(search.query)
            }
            catch (e: Exception) { _state.update { it.copy(loading = false, loadFailed = true, error = "Couldn't load your library: ${e.message ?: "unknown error"}") }; ready.completeExceptionally(e) }
        }
    }
    }

}
