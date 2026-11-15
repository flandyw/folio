package com.folio.notes.backup

import android.app.Application
import android.net.Uri
import com.folio.notes.FolioApplication
import com.folio.notes.app.AiCheck
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioNotifications
import com.folio.notes.app.FolioRuntime
import com.folio.notes.app.PdfSearchState
import com.folio.notes.app.PendingPdfImport
import com.folio.notes.exam.ExamAiClassification
import com.folio.notes.exam.ExamFilter
import com.folio.notes.model.Notebook
import com.folio.notes.pdf.importBatch
import com.folio.notes.vcaa.VcaaExam
import com.folio.notes.vcaa.VcaaLibrary
import com.folio.notes.vcaa.downloadVcaaPdf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Application-owned BackupService commands over the single shared document runtime. */
internal class BackupService(private val runtime: FolioRuntime) {
    internal var pdfInspectionJob: Job? = null
    internal val pdfInspectionGate = Mutex()
    // Main-thread only. Checks outlive the review dialog; an imported file's result fills its notebook's gaps.
    private val aiChecks = mutableMapOf<Uri, Job>()
    // The notebook a file became, with the details it was imported with, so a late result can tell what the user touched.
    private val importedFrom = mutableMapOf<Uri, Pair<String, PendingPdfImport>>()
    // A result that lands while its file is still being copied in waits here for the notebook.
    private val lateSuggestions = mutableMapOf<Uri, com.folio.notes.exam.ExamTags>()
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
                    checkWithAi(inspected)
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

    /** Turning the check on asks about every tentative file still in review; turning it off stops waiting ones. */
    fun setAiImportCheck(enabled: Boolean) {
        with(runtime) {
            prefs.edit().putBoolean(AppPrefs.AI_IMPORT_CHECK, enabled).apply()
            val pending = _state.value.pendingPdfImports
            if (enabled) pending.filter { it.detected && it.aiCheck == null }.forEach(::checkWithAi)
            else pending.filter { it.aiCheck == AiCheck.RUNNING }.forEach { item ->
                aiChecks.remove(item.uri)?.cancel(); setAiCheck(item.uri) { it.copy(aiCheck = null) }
            }
        }
    }

    private fun setAiCheck(uri: Uri, transform: (PendingPdfImport) -> PendingPdfImport) {
        runtime._state.update { state -> state.copy(pendingPdfImports = state.pendingPdfImports.map { if (it.uri == uri) transform(it) else it }) }
    }

    /**
     * Background ChatGPT second opinion for a file the on-device classifier is unsure about. Untouched details are
     * replaced by the merged suggestion; edited ones get a suggestion to accept. If the file was imported meanwhile,
     * only its notebook's empty fields are filled. A notification says so when Folio is out of sight.
     */
    private fun checkWithAi(item: PendingPdfImport) {
        with(runtime) {
            val app = getApplication<FolioApplication>()
            val chatGpt = app.chatGpt
            if (!prefs.getBoolean(AppPrefs.AI_IMPORT_CHECK, AppPrefs.DEFAULT_AI_IMPORT_CHECK) || !chatGpt.canClassify ||
                item.inspectionError != null || !item.detection.uncertain || aiChecks[item.uri]?.isActive == true) return
            val uri = item.uri
            setAiCheck(uri) { it.copy(aiCheck = AiCheck.RUNNING) }
            // shortcut: an ordinary coroutine, so Android may kill a long background request; a foreground service if that bites.
            aiChecks[uri] = scope.launch {
                val suggested = try { ExamAiClassification.merge(item.detection, chatGpt.classifyExam(item.filename, item.evidence)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { setAiCheck(uri) { it.copy(aiCheck = AiCheck.FAILED) }; aiChecks.remove(uri); return@launch }
                aiChecks.remove(uri)
                val pending = _state.value.pendingPdfImports.firstOrNull { it.uri == uri }
                val (title, message) = if (pending != null) {
                    val untouched = !pending.plainPdf && pending.exam == pending.detection.toExamTags()
                    setAiCheck(uri) { if (untouched) it.withExam(suggested).copy(aiCheck = AiCheck.APPLIED, aiTags = suggested)
                        else it.copy(aiCheck = AiCheck.SUGGESTED, aiTags = suggested) }
                    if (chatGpt.visible) return@launch
                    pending.title to if (untouched) "Exam details are filled in. Check them before importing." else "Suggested exam details are ready to review."
                } else {
                    val (noteId, option) = importedFrom.remove(uri) ?: run { if (_state.value.busy) lateSuggestions[uri] = suggested; return@launch }
                    val note = applyAfterImport(noteId, option, suggested) ?: return@launch
                    val text = "${chatGpt.name} updated the exam details: ${note.exam.summaryLine()}"
                    if (chatGpt.visible) { reportNotice("${note.title}: $text"); return@launch }
                    note.title to text
                }
                FolioNotifications.classificationReady(app, uri.hashCode(), title, message)
            }
        }
    }

    /** Applies a result that landed after import, renaming a notebook that still wears its suggested name. Returns the updated notebook, or null if nothing changed. */
    private fun applyAfterImport(noteId: String, option: PendingPdfImport, suggested: com.folio.notes.exam.ExamTags): Notebook? {
        with(runtime) {
            val note = _state.value.notes.firstOrNull { it.id == noteId } ?: return null
            val tags = ExamAiClassification.afterImport(note.exam, option.exam, option.exam == option.detection.toExamTags(), suggested)
            if (tags == note.exam) return null
            updateExamTags(noteId, tags)
            if (option.useSmartTitle && note.title == option.title)
                rename(note, com.folio.notes.exam.smartImportedNotebookName(tags, option.originalTitle))
            return _state.value.notes.firstOrNull { it.id == noteId }
        }
    }

    fun removePdfImport(uri: Uri) {
        with(runtime) {
            aiChecks.remove(uri)?.cancel()
            _state.update { it.copy(pendingPdfImports = it.pendingPdfImports.filterNot { item -> item.uri == uri }) }
            if (_state.value.pendingPdfImports.isEmpty()) cancelPdfImport()
        }
    }

    fun cancelPdfImport() {
        with(runtime) {
            pdfInspectionJob?.cancel()
            pdfInspectionJob = null
            aiChecks.values.forEach { it.cancel() }; aiChecks.clear()
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
            runPdfImport(folderId, items, options)
        }
    }

    /** Own the download as well as import, so closing a library view cannot cancel the request. */
    fun importVcaaPapers(folderId: String?, papers: List<VcaaExam>) {
        with(runtime) {
            val request = _state.value
            if (request.busy || request.loading || request.loadFailed || papers.isEmpty() ||
                (folderId != null && request.folders.none { it.id == folderId })) return
            val chosen = papers.distinctBy { it.url }.filter { VcaaLibrary.imported(it, request.notes) == null }
            if (chosen.isEmpty()) return
            _state.update { it.copy(busy = true, importProgress = "Downloading VCAA papers…") }
            scope.launch {
                val files = mutableListOf<java.io.File>()
                var handedOff = false
                try {
                    val dir = java.io.File(getApplication<Application>().cacheDir, "vcaa")
                    withContext(Dispatchers.IO) { check(dir.isDirectory || dir.mkdirs()) { "Could not prepare the download folder" } }
                    // A few papers at once: each download mostly waits on the network. Results keep the chosen order.
                    val downloads = kotlinx.coroutines.sync.Semaphore(3)
                    var finished = 0
                    _state.update { it.copy(importProgress = "Downloading 0 of ${chosen.size}…") }
                    val results = coroutineScope {
                        chosen.map { exam ->
                            val file = java.io.File(dir, "${java.util.UUID.randomUUID()}.pdf")
                            files += file
                            async {
                                downloads.withPermit {
                                    try {
                                        downloadVcaaPdf(exam.url, file)
                                        Result.success(PendingPdfImport(Uri.fromFile(file), exam.title, exam.tags, detected = true,
                                            filename = exam.url.substringAfterLast('/')))
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { Result.failure(Exception("${exam.title}: ${e.message ?: "Download failed"}")) }
                                    finally {
                                        val done = ++finished
                                        _state.update { it.copy(importProgress = "Downloading $done of ${chosen.size}…") }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    val items = results.mapNotNull { it.getOrNull() }
                    val failures = results.mapNotNull { it.exceptionOrNull()?.message }.toMutableList()
                    if (items.isEmpty()) reportError("Could not download VCAA papers.\n" + failures.joinToString("\n"))
                    else {
                        runPdfImport(folderId, items, items.associateBy { it.uri }, initialFailures = failures) { files.forEach { it.delete() } }
                        handedOff = true
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportError("Could not import VCAA papers: ${e.message ?: "Try again"}") }
                finally {
                    if (!handedOff) {
                        files.forEach { it.delete() }
                        _state.update { it.copy(busy = false, importProgress = null) }
                    }
                }
            }
        }
    }

    /**
     * PDFs Folio fetched itself (the VCAA library), already described, so there is nothing to
     * review. The downloaded copies are deleted once the notebooks own theirs.
     */
    fun importDownloadedPdfs(folderId: String?, items: List<PendingPdfImport>, files: List<java.io.File>) {
        with(runtime) {
            val request = _state.value
            if (request.busy || request.loading || request.loadFailed || items.isEmpty() ||
                (folderId != null && request.folders.none { it.id == folderId })) { files.forEach { it.delete() }; return }
            _state.update { it.copy(busy = true) }
            runPdfImport(folderId, items, items.associateBy { it.uri }) { files.forEach { it.delete() } }
        }
    }

    private fun runPdfImport(folderId: String?, items: List<PendingPdfImport>, options: Map<Uri, PendingPdfImport>,
                             initialFailures: List<String> = emptyList(), cleanup: () -> Unit = {}) {
        with(runtime) {
            scope.launch {
                val imported = mutableListOf<Notebook>()
                val late = mutableMapOf<String, Pair<PendingPdfImport, com.folio.notes.exam.ExamTags>>()
                val failures = initialFailures.toMutableList()
                try {
                    ready.await()
                    importBatch(items,
                        importItem = { item ->
                            val option = options[item.uri] ?: item
                            getApplication<FolioApplication>().storageGate.withLock { repository.importPdf(item.uri, folderId, option) }
                                .also { note -> if (!option.plainPdf) {
                                    if (aiChecks[item.uri]?.isActive == true) importedFrom[item.uri] = note.id to option
                                    lateSuggestions.remove(item.uri)?.let { late[note.id] = option to it }
                                } }
                        },
                        onSuccess = { note ->
                            imported += note
                            _state.update { it.copy(notes = it.notes + note) }
                            late.remove(note.id)?.let { (option, suggested) -> applyAfterImport(note.id, option, suggested) }
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
                    // One PDF that opens straight away needs no count; a batch or a failure does.
                    val total = items.size + initialFailures.size
                    val lines = listOfNotNull("Imported ${imported.size} of $total PDFs.".takeUnless { total == 1 && imported.size == 1 },
                        "Exam details and smart notebook names were applied.".takeIf { imported.any { it.exam.isTagged } }) + failures
                    if (lines.isNotEmpty()) reportError(lines.joinToString("\n"))
                } finally { _state.update { it.copy(busy = false, importProgress = null) }; cleanup() }
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
