package com.folio.notes.app

import android.net.Uri
import com.folio.notes.exam.ExamDetectionResult
import com.folio.notes.exam.ExamFilter
import com.folio.notes.exam.ExamTags
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.exam.smartImportedNotebookName
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.Folder
import com.folio.notes.model.Notebook
import com.folio.notes.model.notebook
import com.folio.notes.pdf.PdfSearchHit
import com.folio.notes.storage.TrashedNotebook
import com.folio.notes.workspace.EditorTab
import kotlinx.coroutines.*

/** Text search over the open notebook's imported PDF: what was asked and what matched. */
data class PdfSearchState(
    val query: String = "",
    val searching: Boolean = false,
    val searched: Boolean = false,
    val results: List<PdfSearchHit> = emptyList()
)

/** A PDF being reviewed before import; the fields are editable and become the notebook metadata. */
data class PendingPdfImport(
    val uri: Uri,
    val title: String,
    val exam: ExamTags = ExamTags(),
    val detected: Boolean = false,
    val filename: String = "",
    val pageCount: Int? = null,
    val detection: ExamDetectionResult = ExamDetectionResult(),
    val inspectionError: String? = null,
    val useSmartTitle: Boolean = true,
    val plainPdf: Boolean = false,
    val savedExam: ExamTags? = null
) {
    val originalTitle: String get() = filename.substringBeforeLast('.', filename).ifBlank { "Imported document" }

    fun withExam(tags: ExamTags): PendingPdfImport = copy(exam = tags,
        title = if (useSmartTitle) smartImportedNotebookName(tags, originalTitle) else title)

    fun asPlainPdf(plain: Boolean): PendingPdfImport = if (plain == plainPdf) this else
        copy(plainPdf = plain, savedExam = if (plain) exam else savedExam)
            .withExam(if (plain) ExamTags() else savedExam ?: detection.toExamTags())
}

data class FolioState(
    val notes: List<Notebook> = emptyList(), val folders: List<Folder> = emptyList(),
    val trash: List<TrashedNotebook> = emptyList(),
    val trashBusyIds: Set<String> = emptySet(),
    val examFilter: ExamFilter = ExamFilter(),
    val navigationRequest: Int = 0,
    /** Explicit restoration recreates presentation cameras, without treating normal page jumps as remounts. */
    val workspaceRestoreRequest: Int = 0,
    val editorOnRight: Boolean = false,
    val tabs: List<EditorTab> = emptyList(),
    val companion: EditorTab? = null,
    /** The notebook the split was opened beside; opening any other notebook parks the second pane. */
    val companionHostId: String? = null,
    /** Share of the split given to the editor pane (0.2..0.8); the companion takes the rest. */
    val splitFraction: Float = 0.5f,
    /** When true, turning the editor's page also turns the companion (linked reference). */
    val companionLinked: Boolean = false,
    val activeId: String? = null, val pageIndex: Int = 0, val folderId: String? = null,
    val documentHistoryBusy: Boolean = false,
    val loading: Boolean = true, val busy: Boolean = false, val exporting: Boolean = false,
    /**
     * How many edits the editor has made, and how many the device has finished writing. Saving is
     * the gap between them: the writer coalesces whatever arrived together, so the number of queued
     * operations behind that gap is none of the user's business, and a hundred strokes that land as
     * one append look exactly like one stroke that took a moment.
     */
    val editGeneration: Long = 0, val durableGeneration: Long = 0,
    val saveFailed: Boolean = false, val retryingSave: Boolean = false, val saveFailureReason: String? = null,
    val lastSaveProgressAt: Long? = null, val loadFailed: Boolean = false, val error: String? = null, val errorIsNotice: Boolean = false,
    val pendingPdfImports: List<PendingPdfImport> = emptyList(),
    val importProgress: String? = null,
    val backupProgress: String? = null,
    val backupExcludedNotebookIds: Set<String> = emptySet(),
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    /** The layer new items land on, per page id; a page with no entry uses its top editable layer. */
    val activeLayers: Map<String, Int> = emptyMap(),
    /** Exam-condition timer for the open notebook, driven by [FolioViewModel.tickTimer]. */
    val timer: ExamTimerState = ExamTimerState(),
    /** Seconds the last stopped timed sitting ran for, offered when recording the mark. */
    val lastTimedSeconds: Int? = null,
    /** Ink, text and pictures cut or copied from a lasso selection, kept so they paste on any page. */
    val clipboard: CanvasSelection = CanvasSelection(),
    /** Text search over the open notebook's imported PDF, driven by [FolioViewModel.searchPdf]. */
    val pdfSearch: PdfSearchState = PdfSearchState(),
    /** The same search over the reference pane's PDF, so a split pane can find text too. */
    val companionPdfSearch: PdfSearchState = PdfSearchState()
) {
    val active get() = notes.notebook(activeId)
    val page get() = active?.pages?.getOrNull(pageIndex)
    /** True while an edit the editor has made is not yet on the device. */
    val saving get() = editGeneration != durableGeneration
    /** Days until the nearest upcoming date of a notebook typed as an exam (not SAC/topic test/notes) across the library, or null when none is set. */
    val daysToExam: Int?
        get() {
            val start = startOfDay()
            return notes.asSequence().filter { it.exam.type?.isExam == true }.mapNotNull { it.exam.examDate }
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

