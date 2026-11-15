package com.folio.notes.library

import com.folio.notes.FolioApplication
import com.folio.notes.app.FolioNotifications
import com.folio.notes.app.FolioRuntime
import com.folio.notes.exam.ExamAiClassification
import com.folio.notes.exam.ExamDetectionResult
import com.folio.notes.exam.ExamDocumentEvidence
import com.folio.notes.exam.ExamField
import com.folio.notes.exam.ExamReclassify
import com.folio.notes.exam.ExamTags
import com.folio.notes.exam.detectImportedExam
import com.folio.notes.model.Notebook
import com.folio.notes.model.hiddenFromLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One notebook's suggested details; [chosen] is what the review will commit. */
data class ReclassifyProposal(
    val noteId: String, val title: String, val current: ExamTags, val suggested: ExamTags,
    val byAi: Boolean, val chosen: Set<ExamField>,
) {
    val changed: Set<ExamField> get() = ExamReclassify.changed(current, suggested)
}

data class ReclassifyState(
    val running: Boolean = false, val done: Int = 0, val total: Int = 0,
    val proposals: List<ReclassifyProposal> = emptyList(), val aiFailures: Int = 0,
    /** True once a run has finished and its proposals await review. */
    val ready: Boolean = false, val applied: String? = null,
)

/**
 * Re-runs import classification over existing notebooks in the application scope, so closing Settings or leaving
 * Folio does not stop it. Nothing is written until [apply]; a notebook edited during review is skipped, never overwritten.
 */
internal class LibraryReclassifier(private val runtime: FolioRuntime) {
    private val mutable = MutableStateFlow(ReclassifyState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun start(noteIds: Set<String>, useAi: Boolean) {
        if (mutable.value.running || noteIds.isEmpty()) return
        val app = runtime.getApplication<FolioApplication>()
        val chatGpt = app.chatGpt
        val notes = runtime._state.value.notes.filter { it.id in noteIds && !it.hiddenFromLibrary }
        mutable.value = ReclassifyState(running = true, total = notes.size)
        fun finish(note: Notebook, suggested: ExamTags, byAi: Boolean) {
            val proposal = ReclassifyProposal(note.id, note.title, note.exam, suggested, byAi, ExamReclassify.gaps(note.exam, suggested))
            mutable.update { state -> state.copy(done = state.done + 1,
                proposals = if (proposal.changed.isEmpty()) state.proposals else state.proposals + proposal) }
        }
        job = runtime.scope.launch {
            try {
                coroutineScope {
                    // The device pass reads PDFs one at a time (they share one PDF lock) while earlier batches are with
                    // ChatGPT; the manager caps how many requests are in flight.
                    val waiting = mutableListOf<Unsure>()
                    fun send(batch: List<Unsure>) = launch {
                        val answers = try { chatGpt.classifyExams(batch.map { Triple(it.note.id, it.note.title, it.evidence) }) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
                        for (item in batch) {
                            // A failed batch or a document it skipped is asked about alone before giving up.
                            val tags = answers[item.note.id] ?: try { chatGpt.classifyExam(item.note.title, item.evidence) }
                                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            if (tags == null) mutable.update { it.copy(aiFailures = it.aiFailures + 1) }
                            finish(item.note, tags?.let { ExamAiClassification.merge(item.detection, it) } ?: item.detection.toExamTags(), tags != null)
                        }
                    }
                    for (note in notes) {
                        val evidence = runtime.repository.notebookExamEvidence(note.id, note.title)
                        // The notebook's title stands in for the file name: it is often the imported file's name.
                        val detection = detectImportedExam(note.title) { evidence }
                        if (useAi && detection.uncertain && chatGpt.canClassify) {
                            waiting += Unsure(note, evidence, detection)
                            if (waiting.size == ExamAiClassification.BATCH) { send(waiting.toList()); waiting.clear() }
                        } else finish(note, detection.toExamTags(), false)
                    }
                    if (waiting.isNotEmpty()) send(waiting.toList())
                }
                mutable.update { it.copy(running = false, ready = true, proposals = it.proposals.sortedBy { p -> p.title.lowercase() }) }
                if (!chatGpt.visible) mutable.value.let {
                    FolioNotifications.classificationReady(app, "reclassify".hashCode(), "Library details ready to review",
                        "${it.proposals.size} of ${it.total} notebooks have suggested changes. Open Folio to review them.")
                }
            } catch (e: CancellationException) {
                // Keep what was found so far reviewable.
                mutable.update { it.copy(running = false, ready = true, proposals = it.proposals.sortedBy { p -> p.title.lowercase() }) }
                throw e
            }
        }
    }

    private class Unsure(val note: Notebook, val evidence: ExamDocumentEvidence, val detection: ExamDetectionResult)

    fun cancel() { job?.cancel() }

    fun choose(noteId: String, field: ExamField, on: Boolean) = mutable.update { state ->
        state.copy(proposals = state.proposals.map { if (it.noteId == noteId && field in it.changed) it.copy(chosen = if (on) it.chosen + field else it.chosen - field) else it })
    }

    fun chooseAll(noteId: String?, on: Boolean) = mutable.update { state ->
        state.copy(proposals = state.proposals.map { if (noteId == null || it.noteId == noteId) it.copy(chosen = if (on) it.changed else emptySet()) else it })
    }

    /** Back to the safe default: only fields that are empty today. */
    fun chooseGaps() = mutable.update { state -> state.copy(proposals = state.proposals.map { it.copy(chosen = ExamReclassify.gaps(it.current, it.suggested)) }) }

    fun apply() {
        val state = mutable.value
        if (state.running) return
        val notes = runtime._state.value.notes.associateBy { it.id }
        var skipped = 0
        val finals = mutableMapOf<String, ExamTags>()
        state.proposals.filter { it.chosen.isNotEmpty() }.forEach { proposal ->
            val note = notes[proposal.noteId]
            if (note == null || note.exam != proposal.current) { skipped++; return@forEach }
            finals[note.id] = ExamReclassify.apply(note.exam, proposal.suggested, proposal.chosen)
        }
        // One state pass and one queued save for the whole review.
        runtime.updateNotes(finals.keys) { note -> finals[note.id]?.let { note.copy(exam = it) } ?: note }
        val applied = finals.size
        mutable.value = ReclassifyState(applied = buildString {
            append("Updated $applied notebook${if (applied == 1) "" else "s"}.")
            if (skipped > 0) append(" $skipped changed since the check and ${if (skipped == 1) "was" else "were"} left alone.")
        })
    }

    fun discard() { if (!mutable.value.running) mutable.value = ReclassifyState() }
}
