package com.folio.notes

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/** Application-owned BackupService commands over the single shared document runtime. */
internal class BackupService(private val runtime: FolioRuntime) {
    internal var pdfInspectionJob: Job? = null
    internal val pdfInspectionGate = Mutex()
    fun export(block: suspend () -> Unit) {
        with(runtime) {
            if (_state.value.exporting) return
            _state.update { it.copy(exporting = true) }
            scope.launch {
                try { block() }
                catch (e: Exception) { reportError("Export failed: ${e.message ?: "unknown error"}") }
                finally { _state.update { it.copy(exporting = false) } }
            }
        }
    }

    fun preparePdfImport(uris: List<Uri>) {
        with(runtime) {
            if (uris.isEmpty() || _state.value.busy) return
            val existing = _state.value.pendingPdfImports
            val additions = uris.distinct().filter { uri -> existing.none { it.uri == uri } }
            if (additions.isEmpty()) return
            _state.update { it.copy(pendingPdfImports = it.pendingPdfImports + additions.map { PendingPdfImport(it, "Reading PDF…") }) }
            // One reader for the whole review; new selections join its queue without opening more PDFs.
            if (pdfInspectionJob?.isActive == true) return
            pdfInspectionJob = scope.launch {
                while (true) {
                    val pending = _state.value.pendingPdfImports.firstOrNull { !it.detected } ?: break
                    val uri = pending.uri
                    // Cancelled OCR must finish releasing its bitmap before a new review starts reading.
                    val inspected = try { pdfInspectionGate.withLock { repository.inspectPdf(uri) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { pending.copy(detected = true, title = "Imported document",
                        inspectionError = e.message ?: "This PDF could not be read") }
                    _state.update { state -> state.copy(pendingPdfImports = state.pendingPdfImports.map {
                        if (it.uri == uri) inspected.copy(detected = true) else it
                    }) }
                }
            }
        }
    }

    fun updatePdfImport(item: PendingPdfImport) {
        with(runtime) {
            _state.update { state -> state.copy(pendingPdfImports = state.pendingPdfImports.map {
                if (it.uri == item.uri && it.detected) item else it
            }) }
        }
    }

    fun removePdfImport(uri: Uri) {
        with(runtime) {
            _state.update { it.copy(pendingPdfImports = it.pendingPdfImports.filterNot { item -> item.uri == uri }) }
            if (_state.value.pendingPdfImports.isEmpty()) cancelPdfImport()
        }
    }

    fun cancelPdfImport() {
        with(runtime) {
            pdfInspectionJob?.cancel()
            pdfInspectionJob = null
            _state.update { it.copy(pendingPdfImports = emptyList()) }
        }
    }

    fun importPdfs(folderId: String?, reviewed: List<PendingPdfImport> = runtime._state.value.pendingPdfImports) {
        with(runtime) {
            val request = _state.value
            if (request.busy || request.loading || request.loadFailed || request.pendingPdfImports.isEmpty()) return
            if (folderId != null && request.folders.none { it.id == folderId }) return
            val items = request.pendingPdfImports
            if (items.any { !it.detected } || reviewed.size != items.size) return
            val options = reviewed.associateBy { it.uri }
            if (items.any { options[it.uri]?.let { option -> option.title.isBlank() || option.inspectionError != null ||
                (option.exam.year != null && option.exam.year !in 1900..2099) ||
                (option.exam.marksTotal != null && option.exam.marksTotal !in 1..300) } != false }) return
            _state.update { it.copy(busy = true, pendingPdfImports = emptyList()) }
            scope.launch {
                val imported = mutableListOf<Notebook>()
                val failures = mutableListOf<String>()
                try {
                    ready.await()
                    importBatch(items,
                        importItem = { item ->
                            getApplication<FolioApplication>().storageGate.withLock { repository.importPdf(item.uri, folderId, options[item.uri] ?: item) }
                        },
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
                        LibraryAutoBackup.requestAfterSave(getApplication<FolioApplication>())
                        captureTab()
                        selectNotebookTimer(if (items.size == 1) imported.single().id else null)
                        _state.update { it.copy(activeId = if (items.size == 1) imported.single().id else null,
                            folderId = folderId, pageIndex = 0, canUndo = false, canRedo = false,
                            examFilter = ExamFilter(), pdfSearch = PdfSearchState()) }
                    }
                    captureTab()
                    reportError(buildString {
                        append("Imported ${imported.size} of ${items.size} PDFs.")
                        if (imported.any { it.exam.isTagged })
                            append("\nExam details and smart notebook names were applied.")
                        if (failures.isNotEmpty()) append("\n" + failures.joinToString("\n"))
                    })
                } finally { _state.update { it.copy(busy = false, importProgress = null) } }
            }
        }
    }

    fun importArchive(uri: Uri) {
        with(runtime) {
            if (_state.value.busy) return
            _state.update { it.copy(busy = true) }
            scope.launch {
                try {
                    ready.await()
                    val note = getApplication<FolioApplication>().storageGate.withLock {
                        repository.importArchive(uri, _state.value.folderId)
                    }
                    LibraryAutoBackup.requestAfterSave(getApplication<FolioApplication>())
                    captureTab()
                    selectNotebookTimer(note.id)
                    _state.update { it.copy(notes = it.notes + note, activeId = note.id, pageIndex = 0, canUndo = false, canRedo = false, pdfSearch = PdfSearchState()) }
                    captureTab()
                } catch (e: Exception) { reportError("Couldn't open this backup. It may be damaged. ${e.message.orEmpty()}") }
                finally { _state.update { it.copy(busy = false) } }
            }
        }
    }

    fun exportArchive(note: Notebook, uri: Uri) {
        with(runtime) {
            export {
                awaitSaved()
                val staged = getApplication<FolioApplication>().storageGate.withLock {
                    val current = repository.load().first.firstOrNull { it.id == note.id }
                        ?: error("This notebook is no longer in the library")
                    repository.stageLibrary(listOf(current.copy(folderId = null)), emptyList(), includeExcluded = true)
                }
                staged.use { repository.writeStaged(it, uri) }
                reportError("Notebook saved as a Folio backup")
            }
        }
    }

    fun setBackupExcluded(ids: Set<String>, excluded: Boolean) {
        with(runtime) {
            val snapshot = _state.value
            if (snapshot.loading || snapshot.loadFailed) return
            val validIds = ids.intersect(snapshot.notes.map { it.id }.toSet())
            if (validIds.isEmpty()) return
            val next = if (excluded) snapshot.backupExcludedNotebookIds + validIds else snapshot.backupExcludedNotebookIds - validIds
            if (next == snapshot.backupExcludedNotebookIds) return
            prefs.edit().putStringSet(AppPrefs.BACKUP_EXCLUDED_NOTEBOOKS, next).apply()
            _state.update { it.copy(backupExcludedNotebookIds = next) }
            LibraryAutoBackup.requestAfterSave(getApplication<FolioApplication>())
        }
    }

    fun backupLibrary(uri: Uri) {
        with(runtime) {
            if (_state.value.busy || _state.value.exporting || _state.value.loadFailed) return
            _state.update { it.copy(exporting = true, backupProgress = "Saving pending changes…") }
            scope.launch {
                try {
                    val progress: (String) -> Unit = { message -> _state.update { it.copy(backupProgress = message) } }
                    awaitSaved()
                    _state.update { it.copy(backupProgress = "Capturing library…") }
                    val (staged, count) = getApplication<FolioApplication>().storageGate.withLock {
                        val (notes, folders) = repository.load()
                        val staged = repository.stageLibrary(notes, folders, onProgress = progress)
                        staged to staged.notebookCount
                    }
                    _state.update { it.copy(backupProgress = "Writing compact library backup…") }
                    staged.use { repository.writeStaged(it, uri, progress) }
                    reportError("Library backup saved ($count notebooks)")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportError("Library backup failed: ${e.message ?: "unknown error"}") }
                finally { _state.update { it.copy(exporting = false, backupProgress = null) } }
            }
        }
    }

    fun restoreLibrary(uri: Uri): Unit = with(runtime) { restoreBackup(uri, null) }

    fun restoreAutomaticBackup(tree: Uri): Unit = with(runtime) { restoreBackup(null, tree) }

    internal fun restoreBackup(uri: Uri?, backupTree: Uri?) {
        with(runtime) {
            if (_state.value.busy || _state.value.exporting || _state.value.loadFailed) return
            _state.update { it.copy(busy = true, importProgress = "Checking library backup…") }
            scope.launch {
                try {
                    awaitSaved()
                    val snapshot = _state.value
                    val source = uri ?: IncrementalBackup.latest(getApplication(), backupTree!!)
                    val (notes, folders) = getApplication<FolioApplication>().storageGate.withLock {
                        repository.importLibrary(source, snapshot.folders, backupTree)
                    }
                    _state.update { it.copy(notes = it.notes + notes, folders = it.folders + folders) }
                    LibraryAutoBackup.requestAfterSave(getApplication<FolioApplication>())
                    reportError("Restored ${notes.size} notebooks and ${folders.size} folders")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportError("Library restore failed: ${e.message ?: "unknown error"}") }
                finally { _state.update { it.copy(busy = false, importProgress = null) } }
            }
        }
    }
}
