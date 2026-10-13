package com.folio.notes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import com.folio.notes.DocumentWriteOp as WriteOp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

internal class FolioRuntime(internal val application: FolioApplication, internal val savedState: SavedStateHandle) {
    internal fun restoreSession(handle: SavedStateHandle) = workspaceManager.restoreSession(handle)
    internal fun copySessionTo(handle: SavedStateHandle, state: FolioState) = workspaceManager.copySessionTo(handle, state)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Application> getApplication(): T = application as T
    internal val editorSelectionController by lazy { EditorSelectionController(this) }
    internal val editorMediaController by lazy { EditorMediaController(this) }
    internal val editorTextController by lazy { EditorTextController(this) }
    internal val editorLayerController by lazy { EditorLayerController(this) }
    internal val markingController by lazy { MarkingController(this) }
    internal val documentEngine by lazy { DocumentEngine(this) }
    internal val examSessionManager by lazy { ExamSessionManager(this) }
    internal val documentWriteCoordinator by lazy { DocumentWriteCoordinator(_state, RepositoryWriteStorage(repository), application.storageScope, application.storageGate) { LibraryAutoBackup.requestAfterSave(application) } }
    internal val notebookCatalog by lazy { NotebookCatalog(this) }
    internal val backupService by lazy { BackupService(this) }
    internal val notebookWorkflowService by lazy { NotebookWorkflowService(this) }
    internal val workspaceManager by lazy { WorkspaceManager(this) }
    internal val prefs = application.getSharedPreferences("preferences", 0)
    internal val positionPrefs get() = workspaceManager.positionPrefs
    internal val companionPositionPrefs get() = workspaceManager.companionPositionPrefs
    val repository = (application as FolioApplication).repository
    val thumbnails = (application as FolioApplication).thumbnails
    internal val restoredTabs = WorkspaceSessionCodec.decode(savedState["workspaceTabs"])
    internal val _state = DocumentStateStore(FolioState(tabs = restoredTabs,
        backupExcludedNotebookIds = AppPrefs.backupExcludedNotebookIds(prefs.getStringSet(AppPrefs.BACKUP_EXCLUDED_NOTEBOOKS, AppPrefs.DEFAULT_BACKUP_EXCLUDED_NOTEBOOKS)),
        pdfSearch = restoredTabs.find { it.notebookId == savedState.get<String>("activeId") }?.search ?: PdfSearchState(),
        companion = WorkspaceSessionCodec.decode(
            if (savedState.contains("workspaceCompanion")) savedState.get<String>("workspaceCompanion")
            else prefs.getString("workspaceCompanion", null)
        ).firstOrNull(),
        companionMode = runCatching { CompanionMode.valueOf(savedState.get<String>("workspaceMode") ?: prefs.getString("workspaceMode", "SPLIT") ?: "SPLIT") }.getOrDefault(CompanionMode.SPLIT),
        splitFraction = (savedState.get<Float>("splitFraction")
            ?: prefs.getFloat(AppPrefs.SPLIT_FRACTION, AppPrefs.DEFAULT_SPLIT).takeIf { prefs.contains(AppPrefs.SPLIT_FRACTION) }
            ?: AppPrefs.DEFAULT_SPLIT).let { AppPrefs.splitFraction(it) },
        companionLinked = savedState.get<Boolean>("companionLinked") ?: prefs.getBoolean("companionLinked", false),
        companionHostId = if (savedState.contains("companionHostId")) savedState.get<String>("companionHostId") else prefs.getString("companionHostId", null),
        // A restored session wins; otherwise the side the panes were last left on, so opening
        // Folio tomorrow puts the work document where the user put it.
        editorOnRight = savedState["editorOnRight"] ?: prefs.getBoolean(AppPrefs.EDITOR_ON_RIGHT, AppPrefs.DEFAULT_EDITOR_ON_RIGHT), activeId = savedState["activeId"], pageIndex = savedState["pageIndex"] ?: 0, folderId = savedState["folderId"]))
    val state = _state.state
    val shellState = _state.shellState
    internal val undo get() = documentEngine.undo
    internal val redo get() = documentEngine.redo
    internal val publishedHistory get() = documentEngine.publishedHistory

    internal class StructureStep(val noteId: String, var seq: Long, val undo: () -> Unit, val redo: () -> Unit)
    internal val structureUndo get() = documentEngine.structureUndo
    internal val structureRedo get() = documentEngine.structureRedo
    internal var actionCounter: Long
        get() = documentEngine.actionCounter
        set(value) { documentEngine.actionCounter = value }
    internal var documentHistoryBusy: Boolean
        get() = documentEngine.documentHistoryBusy
        set(value) { documentEngine.documentHistoryBusy = value }
    internal val inkSeq get() = documentEngine.inkSeq
    internal val inkRedoSeq get() = documentEngine.inkRedoSeq
    internal fun recordStructure(noteId: String, undo: () -> Unit, redo: () -> Unit): Unit = documentEngine.recordStructure(noteId, undo, redo)
    internal var ready = CompletableDeferred<Unit>()
    internal var pasteGeneration: Int
        get() = editorSelectionController.pasteGeneration
        set(value) { editorSelectionController.pasteGeneration = value }
    internal var clipboardNoteId: String?
        get() = editorSelectionController.clipboardNoteId
        set(value) { editorSelectionController.clipboardNoteId = value }
    internal var clipboardToken: String?
        get() = editorSelectionController.clipboardToken
        set(value) { editorSelectionController.clipboardToken = value }
    internal val systemClipboard get() = getApplication<Application>()
        .getSystemService(android.content.ClipboardManager::class.java)
    internal val loadingPages get() = documentEngine.loadingPages
    internal var timerNotebookId: String?
        get() = examSessionManager.timerNotebookId
        set(value) { examSessionManager.timerNotebookId = value }
    internal val notebookSittings get() = examSessionManager.notebookSittings
    internal var lastSeenPersistedAt: Long
        get() = examSessionManager.lastSeenPersistedAt
        set(value) { examSessionManager.lastSeenPersistedAt = value }
    internal var lastTimerActivityAt: Long
        get() = examSessionManager.lastTimerActivityAt
        set(value) { examSessionManager.lastTimerActivityAt = value }

    internal fun timerKey(key: String): String = "$key.notebook.${requireNotNull(timerNotebookId)}"

    internal fun migrateLegacyTimer(notes: List<Notebook>): Unit = examSessionManager.migrateLegacyTimer(notes)

    internal fun restoreNotebookTimer(now: Long = timerNow()): Unit = examSessionManager.restoreNotebookTimer(now)

    internal fun selectNotebookTimer(id: String?, now: Long = timerNow()): Unit = examSessionManager.selectNotebookTimer(id, now)

    var pendingExport: PageExportRequest? = null
    init {
        documentWriteCoordinator.start()
        loadLibrary()
        examSessionManager.start()
        workspaceManager.start()
    }
    fun loadLibrary() = notebookCatalog.loadLibrary()
    internal fun enqueue(scheduleAutoBackup: Boolean = true, block: suspend () -> Unit): Unit = documentWriteCoordinator.enqueue(scheduleAutoBackup, block)

    internal fun queue(op: WriteOp, scheduleAutoBackup: Boolean = true): Unit = documentWriteCoordinator.queue(op, scheduleAutoBackup)

    internal suspend fun collectWrites(batch: ArrayList<WriteOp>): Unit = documentWriteCoordinator.collectWrites(batch)

    internal suspend fun executeWriteOps(batch: List<WriteOp>): Unit = documentWriteCoordinator.executeWriteOps(batch)

    internal suspend fun runWrites(batch: List<WriteOp>): Unit = documentWriteCoordinator.runWrites(batch)

    internal fun queueIndexCheckpoint(noteId: String): Unit = documentWriteCoordinator.queueIndexCheckpoint(noteId)

    internal suspend fun compactPendingPages(): Unit = documentWriteCoordinator.compactPendingPages()
    internal suspend fun awaitQueuedWrites(): Unit = documentWriteCoordinator.awaitQueuedWrites()
    internal fun updateNote(note: Notebook): Unit = notebookCatalog.updateNote(note)

    internal fun updateNotes(ids: Set<String>, transform: (Notebook) -> Notebook): Unit = notebookCatalog.updateNotes(ids, transform)

    internal fun findPage(pageId: String): Notebook? = documentEngine.findPage(pageId)

    internal fun findPageContent(pageId: String): NotePage? = documentEngine.findPageContent(pageId)

    internal fun identitySet(strokes: List<Stroke>): Set<Stroke> = editorSelectionController.identitySet(strokes)
    fun clearError() { _state.update { it.copy(error = null) } }
    fun reportError(message: String) { _state.update { it.copy(error = message) } }
    fun export(block: suspend () -> Unit): Unit = backupService.export(block)
    fun folder(id: String?): Unit = notebookCatalog.folder(id)
    fun createFolder(name: String, parentId: String? = _state.value.folderId): Boolean = notebookCatalog.createFolder(name, parentId)
    internal fun validFolderName(name: String, parentId: String?, except: String? = null): Boolean = notebookCatalog.validFolderName(name, parentId, except)
    fun createFolderAndMove(ids: Set<String>, name: String, parentId: String? = null): Boolean = notebookCatalog.createFolderAndMove(ids, name, parentId)
    fun renameFolder(folder: Folder, name: String): Boolean = notebookCatalog.renameFolder(folder, name)
    fun recolorFolder(id: String, color: Int): Unit = notebookCatalog.recolorFolder(id, color)
    fun moveFolder(id: String, parentId: String?): Boolean = notebookCatalog.moveFolder(id, parentId)
    fun deleteFolder(folder: Folder): Unit = notebookCatalog.deleteFolder(folder)
    fun updateNotebookTags(ids: Set<String>, add: List<String>, remove: List<String> = emptyList()): Unit = notebookCatalog.updateNotebookTags(ids, add, remove)
    fun create(title: String, cover: Int, paper: Paper, exam: ExamTags = ExamTags(), pageCount: Int = 1, infinite: Boolean = false, pageCover: Boolean = true, response: LongResponse? = null, responseMode: ResponseMode = ResponseMode.FULL, folderId: String? = _state.value.folderId): Unit = notebookWorkflowService.create(title, cover, paper, exam, pageCount, infinite, pageCover, response, responseMode, folderId)
    fun configureResponse(noteId: String, response: LongResponse, subject: String): Unit = notebookWorkflowService.configureResponse(noteId, response, subject)

    fun updateResponsePlan(noteId: String, attemptId: String, plan: String): Unit = notebookWorkflowService.updateResponsePlan(noteId, attemptId, plan)

    fun renameResponseAttempt(noteId: String, attemptId: String, title: String): Unit = notebookWorkflowService.renameResponseAttempt(noteId, attemptId, title)

    internal val copyingResponses get() = notebookWorkflowService.copyingResponses

    fun newResponseAttempt(noteId: String, mode: ResponseMode, parentId: String? = null,
        actionId: String? = null, copyForMarking: Boolean = false): Unit = notebookWorkflowService.newResponseAttempt(noteId, mode, parentId, actionId, copyForMarking)

    fun addFeedbackAction(noteId: String, pageId: String, text: String, practice: FeedbackPractice, sourceTextId: String? = null): Unit = notebookWorkflowService.addFeedbackAction(noteId, pageId, text, practice, sourceTextId)

    fun completeFeedbackAction(noteId: String, actionId: String, done: Boolean): Unit = notebookWorkflowService.completeFeedbackAction(noteId, actionId, done)

    fun removeFeedbackAction(noteId: String, actionId: String): Unit = notebookWorkflowService.removeFeedbackAction(noteId, actionId)

    fun openResponseAttempt(noteId: String, attemptId: String, beside: Boolean = false): Unit = notebookWorkflowService.openResponseAttempt(noteId, attemptId, beside)

    suspend fun createMistakePractice(user: String, mistake: com.folio.notes.mistakes.ExamTrackMistake, openWhenReady: Boolean = true): com.folio.notes.mistakes.LocalMistakeReviewAttempt = notebookWorkflowService.createMistakePractice(user, mistake, openWhenReady)

    internal suspend fun openMusicScore(score: com.folio.notes.music.MusicScore, pdf: java.io.File): String? = notebookWorkflowService.openMusicScore(score, pdf)

    fun closeMusicScore(notebookId: String): Unit = notebookWorkflowService.closeMusicScore(notebookId)

    internal fun deleteMusicScore(scoreId: String): Unit = notebookWorkflowService.deleteMusicScore(scoreId)

    fun completeMistakePractice(attempt: com.folio.notes.mistakes.LocalMistakeReviewAttempt): Unit = notebookWorkflowService.completeMistakePractice(attempt)

    suspend fun practiceSizes(noteIds: Collection<String>): Map<String, Long> = notebookWorkflowService.practiceSizes(noteIds)

    suspend fun purgeEmptyMistakeNotebooks(): Int = notebookWorkflowService.purgeEmptyMistakeNotebooks()

    internal fun captureTab(): Unit = workspaceManager.captureTab()

    internal fun captureTabInto(next: FolioState): FolioState = workspaceManager.captureTabInto(next)

    internal fun persistPosition(notebookId: String): Unit = workspaceManager.persistPosition(notebookId)

    internal fun persistCompanionPosition(pane: EditorTab? = _state.value.companion): Unit = workspaceManager.persistCompanionPosition(pane)

    internal fun rememberedTab(note: Notebook, reference: Boolean = false): EditorTab? = workspaceManager.rememberedTab(note, reference)

    internal fun prunePositions(notes: List<Notebook>): Unit = workspaceManager.prunePositions(notes)

    fun updateTabViewport(id: String, viewport: WorkspaceViewport, tool: Tool): Unit = workspaceManager.updateTabViewport(id, viewport, tool)

    fun open(id: String): Unit = workspaceManager.open(id)

    fun openAt(id: String, index: Int): Unit = workspaceManager.openAt(id, index)

    fun close(): Unit = workspaceManager.close()

    fun closeTab(id: String): Unit = workspaceManager.closeTab(id)

    fun closeOtherTabs(keepId: String): Unit = workspaceManager.closeOtherTabs(keepId)

    fun showCompanion(id: String, mode: CompanionMode): Unit = workspaceManager.showCompanion(id, mode)

    fun dismissCompanion(): Unit = workspaceManager.dismissCompanion()

    fun setSplitFraction(fraction: Float): Unit = workspaceManager.setSplitFraction(fraction)

    fun setCompanionLinked(linked: Boolean): Unit = workspaceManager.setCompanionLinked(linked)

    fun setCompanionMode(mode: CompanionMode): Unit = workspaceManager.setCompanionMode(mode)

    fun swapPaneSides(): Unit = workspaceManager.swapPaneSides()

    fun setEditorOnRight(right: Boolean): Unit = workspaceManager.setEditorOnRight(right)

    fun companionPage(index: Int): Unit = workspaceManager.companionPage(index)

    fun searchCompanionPdf(query: String): Unit = workspaceManager.searchCompanionPdf(query)

    fun clearCompanionPdfSearch(): Unit = workspaceManager.clearCompanionPdfSearch()

    fun updateCompanionViewport(viewport: WorkspaceViewport): Unit = workspaceManager.updateCompanionViewport(viewport)

    fun swapCompanion(): Unit = workspaceManager.swapCompanion()
    fun rename(note: Notebook, title: String): Unit = notebookCatalog.rename(note, title)
    fun pin(note: Notebook, inFolder: Boolean): Unit = notebookCatalog.pin(note, inFolder)
    fun star(note: Notebook): Unit = notebookCatalog.star(note)
    fun move(note: Notebook, folderId: String?): Unit = notebookCatalog.move(note, folderId)
    fun setCover(note: Notebook, cover: Int): Unit = notebookCatalog.setCover(note, cover)
    fun setPageCover(note: Notebook, pageCover: Boolean): Unit = notebookCatalog.setPageCover(note, pageCover)

    // ---- Exam metadata --------------------------------------------------------------------

    fun updateExamTags(noteId: String, tags: ExamTags): Unit = notebookCatalog.updateExamTags(noteId, tags)

    fun toggleExamTag(noteId: String, tag: ExamTagType): Unit = notebookCatalog.toggleExamTag(noteId, tag)

    fun setExamFilter(filter: ExamFilter): Unit = notebookCatalog.setExamFilter(filter)

    fun toggleRedoFlag(): Unit = notebookCatalog.toggleRedoFlag()

    fun recordAttempt(noteId: String, attempt: ExamAttempt, responseAttemptId: String? = null): Unit = notebookCatalog.recordAttempt(noteId, attempt, responseAttemptId)

    fun deleteAttempt(noteId: String, attemptId: String): Unit = notebookCatalog.deleteAttempt(noteId, attemptId)
    fun updateAttempt(noteId: String, attempt: ExamAttempt): Unit = notebookCatalog.updateAttempt(noteId, attempt)
    fun moveNotebooks(ids: Set<String>, folderId: String?): Unit = notebookCatalog.moveNotebooks(ids, folderId)
    fun favoriteNotebooks(ids: Set<String>, starred: Boolean): Unit = notebookCatalog.favoriteNotebooks(ids, starred)
    fun setPageCoverBatch(ids: Set<String>, pageCover: Boolean): Unit = notebookCatalog.setPageCoverBatch(ids, pageCover)
    fun updateExamTagsBatch(ids: Set<String>, transform: (ExamTags) -> ExamTags): Unit = notebookCatalog.updateExamTagsBatch(ids, transform)
    fun duplicateNotebook(note: Notebook): Unit = notebookCatalog.duplicateNotebook(note)
    fun delete(note: Notebook): Unit = notebookCatalog.delete(note)
    fun deleteNotebooks(ids: Set<String>): Unit = notebookCatalog.deleteNotebooks(ids)
    fun selectPage(index: Int): Unit = workspaceManager.selectPage(index)
    fun loadPage(pageId: String): Unit = documentEngine.loadPage(pageId)
    fun addPage(paper: Paper? = null): Unit = documentEngine.addPage(paper)
    fun duplicatePage(index: Int = _state.value.pageIndex): Int? = documentEngine.duplicatePage(index)
    internal fun duplicateLoaded(note: Notebook, index: Int): Int = documentEngine.duplicateLoaded(note, index)
    fun insertPage(index: Int, paper: Paper? = null): Int = documentEngine.insertPage(index, paper)
    fun deletePage(index: Int): Unit = documentEngine.deletePage(index)
    internal fun deleteLoadedPage(noteId: String, pageId: String): Unit = documentEngine.deleteLoadedPage(noteId, pageId)
    internal fun removePageRaw(noteId: String, pageId: String): Pair<NotePage, PageJournal.History>? = documentEngine.removePageRaw(noteId, pageId)
    internal fun insertPageRaw(noteId: String, index: Int, page: NotePage, history: PageJournal.History?, select: Boolean): Unit = documentEngine.insertPageRaw(noteId, index, page, history, select)
    internal fun recordPageInserted(noteId: String, pageId: String): Unit = documentEngine.recordPageInserted(noteId, pageId)
    fun movePage(from: Int, to: Int): Unit = documentEngine.movePage(from, to)
    internal fun movePageRaw(noteId: String, from: Int, to: Int): Unit = documentEngine.movePageRaw(noteId, from, to)
    internal data class PageMeta(val paper: Paper, val redoFlag: Boolean, val title: String, val bookmarked: Boolean)
    internal fun NotePage.meta(): PageMeta = PageMeta(paper, redoFlag, title, bookmarked)
    internal fun applyPageMeta(noteId: String, pageId: String, meta: PageMeta): Unit = documentEngine.applyPageMeta(noteId, pageId, meta)
    internal fun recordPageMeta(noteId: String, before: NotePage, after: NotePage): Unit = documentEngine.recordPageMeta(noteId, before, after)
    fun renamePage(pageId: String, title: String): Unit = documentEngine.renamePage(pageId, title)

    fun setPeekAnchor(anchor: PeekAnchor?): Unit = documentEngine.setPeekAnchor(anchor)

    fun togglePageBookmark(pageId: String): Unit = documentEngine.togglePageBookmark(pageId)

    fun setPageBookmarked(noteId: String, pageId: String, bookmarked: Boolean): Unit = documentEngine.setPageBookmarked(noteId, pageId, bookmarked)

    fun setPageRedoFlag(noteId: String, pageId: String, flagged: Boolean): Unit = documentEngine.setPageRedoFlag(noteId, pageId, flagged)

    fun setDefaultPaper(paper: Paper): Unit = documentEngine.setDefaultPaper(paper)
    fun setPaper(paper: Paper): Unit = documentEngine.setPaper(paper)
    internal fun replacePage(page: NotePage): Unit = documentEngine.replacePage(page)

    internal fun commitEdit(
        note: Notebook, before: NotePage, after: NotePage,
        knownEdits: Pair<PageEdit, PageEdit>? = null, beforeSave: suspend () -> Unit = {}
    ): Unit = documentEngine.commitEdit(note, before, after, knownEdits, beforeSave)

    internal fun currentCheckpoint(noteId: String, pageId: String): PageCheckpoint? = documentEngine.currentCheckpoint(noteId, pageId)

    internal fun allPublishedHistory(): Map<String, PageJournal.History> = documentEngine.allPublishedHistory()
    fun strokes(strokes: List<Stroke>): Unit = documentEngine.strokes(strokes)
    fun strokes(pageId: String, strokes: List<Stroke>): Unit = documentEngine.strokes(pageId, strokes)
    fun appendStroke(pageId: String, beforeStrokes: List<Stroke>, stroke: Stroke, afterStrokes: List<Stroke>): Unit = documentEngine.appendStroke(pageId, beforeStrokes, stroke, afterStrokes)
    fun withdrawStroke(pageId: String, stroke: Stroke): Unit = documentEngine.withdrawStroke(pageId, stroke)
    fun texts(texts: List<TextBox>): Unit = editorTextController.texts(texts)
    internal suspend fun awaitChatGptMath(comments: List<String>): Unit = editorTextController.awaitChatGptMath(comments)
    internal fun applyChatGptFeedback(feedback: com.folio.notes.chatgpt.ChatGptFeedback): Boolean = editorTextController.applyChatGptFeedback(feedback)
    fun texts(pageId: String, texts: List<TextBox>): Unit = editorTextController.texts(pageId, texts)
    fun addText(box: TextBox): Unit = editorTextController.addText(box)
    fun updateText(box: TextBox): Unit = editorTextController.updateText(box)
    fun commitText(pageId: String, box: TextBox): Unit = editorTextController.commitText(pageId, box)
    fun removeText(id: String): Unit = editorTextController.removeText(id)
    fun duplicateText(id: String, draft: TextBox? = null): Unit = editorTextController.duplicateText(id, draft)
    // ---- Layers ---------------------------------------------------------------------------

    fun activeLayerOf(page: NotePage): Int = editorLayerController.activeLayerOf(page)
    fun setActiveLayer(pageId: String, layer: Int): Unit = editorLayerController.setActiveLayer(pageId, layer)
    internal fun editLayers(change: (PageContent) -> PageContent): Unit = editorLayerController.editLayers(change)
    fun addLayer(): Unit = editorLayerController.addLayer()
    fun renameLayer(id: Int, name: String): Unit = editorLayerController.renameLayer(id, name)
    fun setLayerVisible(id: Int, visible: Boolean): Unit = editorLayerController.setLayerVisible(id, visible)
    fun setLayerLocked(id: Int, locked: Boolean): Unit = editorLayerController.setLayerLocked(id, locked)
    fun moveLayer(id: Int, up: Boolean): Unit = editorLayerController.moveLayer(id, up)
    fun deleteLayer(id: Int): Unit = editorLayerController.deleteLayer(id)
    fun moveSelectionToLayer(selection: CanvasSelection, layer: Int): Unit = editorLayerController.moveSelectionToLayer(selection, layer)

    fun clearPage(): Unit = documentEngine.clearPage()

    fun clearPageContent(pageId: String): Unit = documentEngine.clearPageContent(pageId)

    fun clearPagesInk(noteId: String, pageIds: List<String>): Unit = documentEngine.clearPagesInk(noteId, pageIds)

    // ---- Placed images ------------------------------------------------------------------

    fun images(pageId: String, images: List<PageImage>): Unit = editorMediaController.images(pageId, images)

    fun addImage(image: PageImage, bytes: ByteArray? = null, pageId: String? = _state.value.page?.id): Unit = editorMediaController.addImage(image, bytes, pageId)
    internal val _imageFiles get() = editorMediaController._imageFiles
    val imageFiles: StateFlow<Int> = _imageFiles

    fun updateImage(image: PageImage): Unit = editorMediaController.updateImage(image)

    fun removeImage(id: String): Unit = editorMediaController.removeImage(id)

    fun bringImageToFront(id: String): Unit = editorMediaController.bringImageToFront(id)

    fun sendImageToBack(id: String): Unit = editorMediaController.sendImageToBack(id)

    fun rotateImageClockwise(id: String): Unit = editorMediaController.rotateImageClockwise(id)

    fun rotateImageCounterClockwise(id: String): Unit = editorMediaController.rotateImageCounterClockwise(id)

    fun cropImage(id: String, left: Float, top: Float, right: Float, bottom: Float): Unit = editorMediaController.cropImage(id, left, top, right, bottom)

    fun resetImageCrop(id: String): Unit = editorMediaController.resetImageCrop(id)
    fun updateContent(pageId: String, strokes: List<Stroke>, texts: List<TextBox>, images: List<PageImage>): Unit = documentEngine.updateContent(pageId, strokes, texts, images)
    fun copyToClipboard(selection: CanvasSelection): Unit = editorSelectionController.copyToClipboard(selection)
    fun cutSelection(selection: CanvasSelection): Unit = editorSelectionController.cutSelection(selection)
    fun deleteSelection(selection: CanvasSelection): Unit = editorSelectionController.deleteSelection(selection)
    fun replaceInkWithText(pageId: String, selection: CanvasSelection, box: TextBox): Boolean = editorSelectionController.replaceInkWithText(pageId, selection, box)
    fun duplicateSelection(selection: CanvasSelection): Unit = editorSelectionController.duplicateSelection(selection)
    fun pasteClipboard(at: InkPoint? = null): Unit = editorSelectionController.pasteClipboard(at)

    internal fun placeSelection(clip: CanvasSelection, page: NotePage, at: InkPoint?, offset: Float = 0f, centred: Boolean = false): CanvasSelection = editorSelectionController.placeSelection(clip, page, at, offset, centred)

    fun moveSelectionToPage(sourceId: String, targetId: String, selection: CanvasSelection, at: InkPoint? = null, centred: Boolean = at != null): Unit = editorSelectionController.moveSelectionToPage(sourceId, targetId, selection, at, centred)
    fun moveStickyToPage(sourceId: String, targetId: String, box: TextBox, x: Float, y: Float): Unit = editorSelectionController.moveStickyToPage(sourceId, targetId, box, x, y)
    fun restyleSelection(strokes: List<Stroke>, color: Int?, widthScale: Float?, opacity: Float?, style: StrokeStyle? = null): Unit = editorSelectionController.restyleSelection(strokes, color, widthScale, opacity, style)

    // ---- Marking & feedback ---------------------------------------------------------------------

    fun applyMarking(action: MarkingAction, at: InkPoint, color: Int): Unit = markingController.applyMarking(action, at, color)

    fun placeInFreeSpace(action: MarkingAction, color: Int): Boolean = markingController.placeInFreeSpace(action, color)

    fun placeFeedbackNote(at: InkPoint, handwritten: Boolean, color: Int): NotePlan? = markingController.placeFeedbackNote(at, handwritten, color)

    fun awardMark(pageId: String, zone: MarkZone, value: Int, color: Int): Unit = markingController.awardMark(pageId, zone, value, color)

    fun clearMark(pageId: String, zone: MarkZone): Unit = markingController.clearMark(pageId, zone)

    internal fun makeRoom(page: NotePage, y: Float, amount: Float): Unit = markingController.makeRoom(page, y, amount)

    internal fun dropFlag(page: NotePage, at: InkPoint, color: Int): Unit = markingController.dropFlag(page, at, color)

    internal suspend fun awaitLoaded(pageId: String): NotePage? = documentEngine.awaitLoaded(pageId)

    suspend fun pagesForSearch(notebookId: String): List<NotePage> = documentEngine.pagesForSearch(notebookId)

    fun commitDocumentEdits(noteId: String, edits: Map<String, PageEdit>, expectedRevisions: Map<String, Int> = emptyMap()): Boolean = documentEngine.commitDocumentEdits(noteId, edits, expectedRevisions)

    fun appendDocumentStrokes(noteId: String, strokes: Map<String, List<Stroke>>, revisions: Map<String, Int>): Boolean = documentEngine.appendDocumentStrokes(noteId, strokes, revisions)

    fun duplicateDocumentSelection(noteId: String, selection: Map<String, CanvasSelection>, onDone: () -> Unit): Unit = editorSelectionController.duplicateDocumentSelection(noteId, selection, onDone)

    fun moveDocumentSelection(noteId: String, selection: Map<String, CanvasSelection>, flattened: CanvasSelection,
        targetId: String, onDone: () -> Unit): Unit = editorSelectionController.moveDocumentSelection(noteId, selection, flattened, targetId, onDone)

    internal suspend fun loadDocumentHistoryPages(noteId: String, ids: List<String>): Unit = documentEngine.loadDocumentHistoryPages(noteId, ids)

    internal fun documentHistory(step: DocumentHistoryStep, wasUndo: Boolean): Unit = documentEngine.documentHistory(step, wasUndo)

    suspend fun pagesForMarking(): List<NotePage> = markingController.pagesForMarking()

    fun undo(): Unit = documentEngine.undo()
    fun redo(): Unit = documentEngine.redo()

    // ---- Exam timer -----------------------------------------------------------------------

    internal fun timerNow(): Long = examSessionManager.timerNow()

    internal fun recordTimerInFocal(timer: ExamTimerState, now: Long, force: Boolean = false, phaseAt: Long? = null): Unit = examSessionManager.recordTimerInFocal(timer, now, force, phaseAt)

    internal fun saveSitting(timer: ExamTimerState, lastSeen: Long = timerNow()): Unit = examSessionManager.saveSitting(timer, lastSeen)

    internal fun clearSitting(): Unit = examSessionManager.clearSitting()

    internal fun storedSitting(): ExamTimerPreset? = examSessionManager.storedSitting()

    fun tickTimer(now: Long = timerNow()): Unit = examSessionManager.tickTimer(now)
    fun onPenActivity(beginsStroke: Boolean, now: Long = timerNow()): Unit = examSessionManager.onPenActivity(beginsStroke, now)
    internal fun touchTimerActivity(now: Long = timerNow()): Unit = examSessionManager.touchTimerActivity(now)
    internal fun idleStopMinutes(): Int = examSessionManager.idleStopMinutes()
    fun autoPauseTimer(now: Long = timerNow()): Unit = examSessionManager.autoPauseTimer(now)
    fun pauseForUpdate(now: Long = timerNow()): Unit = examSessionManager.pauseForUpdate(now)
    fun startTimer(preset: ExamTimerPreset, now: Long = timerNow()): Unit = examSessionManager.startTimer(preset, now)
    fun adjustTimer(seconds: Int): Unit = examSessionManager.adjustTimer(seconds)
    fun toggleTimerPause(): Unit = examSessionManager.toggleTimerPause()
    fun skipTimerPhase(): Unit = examSessionManager.skipTimerPhase()
    fun stopTimer(): Unit = examSessionManager.stopTimer()
    internal fun history(from: MutableMap<String, MutableList<PageEdit>>, to: MutableMap<String, MutableList<PageEdit>>): Unit = documentEngine.history(from, to)
    internal fun historyState(): Unit = documentEngine.historyState()
    fun retrySave(): Unit = documentWriteCoordinator.retrySave()
    fun searchPdf(query: String): Unit = workspaceManager.searchPdf(query)
    fun clearPdfSearch(): Unit = workspaceManager.clearPdfSearch()
    internal var pdfInspectionJob: Job?
        get() = backupService.pdfInspectionJob
        set(value) { backupService.pdfInspectionJob = value }
    internal val pdfInspectionGate get() = backupService.pdfInspectionGate

    fun preparePdfImport(uris: List<Uri>): Unit = backupService.preparePdfImport(uris)
    fun updatePdfImport(item: PendingPdfImport): Unit = backupService.updatePdfImport(item)

    fun removePdfImport(uri: Uri): Unit = backupService.removePdfImport(uri)

    fun cancelPdfImport(): Unit = backupService.cancelPdfImport()

    fun importPdfs(folderId: String?, reviewed: List<PendingPdfImport> = _state.value.pendingPdfImports): Unit = backupService.importPdfs(folderId, reviewed)
    fun importArchive(uri: Uri): Unit = backupService.importArchive(uri)
    fun exportArchive(note: Notebook, uri: Uri): Unit = backupService.exportArchive(note, uri)

    internal suspend fun awaitSaved() { ready.await(); documentWriteCoordinator.awaitSaved() }

    fun setBackupExcluded(ids: Set<String>, excluded: Boolean): Unit = backupService.setBackupExcluded(ids, excluded)

    fun backupLibrary(uri: Uri): Unit = backupService.backupLibrary(uri)

    fun restoreLibrary(uri: Uri): Unit = backupService.restoreLibrary(uri)

    fun restoreAutomaticBackup(tree: Uri): Unit = backupService.restoreAutomaticBackup(tree)

    internal fun restoreBackup(uri: Uri?, backupTree: Uri?): Unit = backupService.restoreBackup(uri, backupTree)
    

    internal companion object {
            const val MAX_UNDO = PageJournal.HISTORY_LIMIT
        const val MAX_STRUCTURE_UNDO = 30
        const val CHATGPT_NOTE_WIDTH = 320f
            const val WRITE_BATCH_WINDOW_MS = 24L
            const val MAX_WRITE_BATCH = 64
            const val PASTE_OFFSET = 22f
        const val TIMER_START_KEY = "examTimer.startedAt"
        const val TIMER_WRITING_KEY = "examTimer.writingSeconds"
        const val TIMER_READING_KEY = "examTimer.readingSeconds"
        const val TIMER_LABEL_KEY = "examTimer.label"
        const val TIMER_PAUSED_AT_KEY = "examTimer.pausedAt"
        const val TIMER_PAUSED_MILLIS_KEY = "examTimer.pausedMillis"
        const val TIMER_LAST_SEEN_KEY = "examTimer.lastSeen"
        const val TIMER_PARK_AUTO_KEY = "examTimer.parkAuto"
        const val TIMER_VISITS_KEY = "examTimer.visits"
            const val LAST_SEEN_THROTTLE_MS = 5_000L
    }
}
