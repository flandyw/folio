package com.folio.notes

import android.app.Application
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock


/** Application-owned NotebookWorkflowService commands over the single shared document runtime. */
internal class NotebookWorkflowService(private val runtime: FolioRuntime) {
    internal val copyingResponses = mutableSetOf<String>()
    fun create(title: String, cover: Int, paper: Paper, exam: ExamTags = ExamTags(), pageCount: Int = 1, infinite: Boolean = false, pageCover: Boolean = true, response: LongResponse? = null, responseMode: ResponseMode = ResponseMode.FULL, folderId: String? = runtime._state.value.folderId) {
        with(runtime) {
            if (title.isBlank() || _state.value.loading || _state.value.loadFailed) return
            val pages = List(if (infinite) 1 else pageCount.coerceIn(1, 40)) { NotePage(paper = paper, infinite = infinite) }
            val note = Notebook(title = title.trim(), folderId = folderId, cover = cover, pages = pages, exam = exam, pageCover = pageCover,
                defaultPaper = if (response != null) Paper.RULED else null,
                longResponse = response?.copy(attempts = listOf(ResponseAttempt(title = "First attempt", mode = responseMode, pageIds = pages.map { it.id }))))
            installNewNotebook(note)
        }
    }

    private fun installNewNotebook(note: Notebook) = with(runtime) {
            captureTab()
            selectNotebookTimer(note.id)
            _state.update { it.copy(notes = it.notes + note, activeId = note.id, pageIndex = 0, canUndo = false, canRedo = false, pdfSearch = PdfSearchState()) }
            captureTab()
            enqueue { repository.saveAll(note) }
    }
    fun createLike(source: Notebook) = with(runtime) {
        if (_state.value.loading || _state.value.loadFailed || source.hiddenFromLibrary) return
        val names = _state.value.notes.map { it.title }.toSet()
        val base = "New ${source.title}"
        var title = base; var suffix = 2
        while (title in names) title = "$base (${suffix++})"
        installNewNotebook(source.blankLike(title))
    }

    fun configureResponse(noteId: String, response: LongResponse, subject: String) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            if (response.prompt.isBlank()) return
            val attempts = note.longResponse?.attempts ?: listOf(ResponseAttempt(
                title = "Original work", pageIds = note.pages.map { it.id }))
            updateNote(note.copy(longResponse = response.copy(attempts = attempts), defaultPaper = Paper.RULED,
                exam = note.exam.copy(subject = VceSubject.entries.find { it.label == subject.trim() }, subjectText = subject.trim())))
        }
    }

    fun updateResponsePlan(noteId: String, attemptId: String, plan: String) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val response = note.longResponse ?: return
            if (response.attempts.none { it.id == attemptId && it.plan != plan }) return
            updateNote(note.copy(longResponse = response.updateAttempt(attemptId) { it.copy(plan = plan) }))
        }
    }

    fun renameResponseAttempt(noteId: String, attemptId: String, title: String) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val response = note.longResponse ?: return
            if (title.isBlank()) return
            updateNote(note.copy(longResponse = response.updateAttempt(attemptId) { it.copy(title = title.trim()) }))
        }
    }

    fun newResponseAttempt(noteId: String, mode: ResponseMode, parentId: String? = null,
        actionId: String? = null, copyForMarking: Boolean = false) {
        with(runtime) {
            if (!copyingResponses.add(noteId)) return
            scope.launch {
                try {
                    awaitQueuedWrites()
                    val source = _state.value.notes.notebook(noteId) ?: return@launch
                    val action = actionId?.let { id -> source.feedbackActions.find { it.id == id } }
                    val response = source.longResponse ?: action?.let {
                        LongResponse(prompt = it.text, attempts = listOf(ResponseAttempt(
                            title = "Original work", pageIds = source.pages.map { page -> page.id })))
                    } ?: return@launch
                    val parent = response.attempts.find { it.id == parentId }
                        ?: action?.let { response.attemptFor(it.pageId) }
                    val title = if (copyForMarking) "Marked copy · ${parent?.title ?: "Response"}"
                        else "${action?.practice?.label ?: mode.label} · ${response.attempts.size + 1}"
                    val pages = if (copyForMarking) {
                        val originals = parent?.pageIds?.map { id ->
                            source.pages.page(id) ?: error("A page from this attempt was deleted")
                        }.orEmpty()
                        check(originals.isNotEmpty()) { "This attempt has no pages to copy" }
                        originals.map { page ->
                            val loaded = if (page.loaded) page else repository.loadPage(noteId, page)
                            loaded.copy(id = java.util.UUID.randomUUID().toString(), revision = 0, title = title)
                        }
                    } else listOf(NotePage(paper = Paper.RULED, title = title))
                    // Loading pages can suspend: merge with the latest metadata and ink, never the old notebook.
                    val current = _state.value.notes.notebook(noteId) ?: return@launch
                    val latest = current.longResponse ?: response
                    val attempt = ResponseAttempt(title = title, mode = mode, pageIds = pages.map { it.id },
                        plan = if (copyForMarking) parent?.plan.orEmpty() else "",
                        parentId = parent?.id, sourceActionId = action?.id)
                    val updated = current.copy(pages = current.pages + pages, defaultPaper = Paper.RULED,
                        longResponse = latest.copy(attempts = latest.attempts + attempt),
                        feedbackActions = current.feedbackActions.map {
                            if (it.id == action?.id) it.copy(practiceAttemptId = attempt.id) else it
                        }, updated = System.currentTimeMillis())
                    _state.update { state -> state.copy(notes = state.notes.map { if (it.id == noteId) updated else it }) }
                    enqueue { repository.saveResponsePages(updated, pages) }
                    openAt(noteId, current.pages.size)
                } catch (e: Exception) {
                    reportError("Couldn't create this attempt: ${e.message.orEmpty()}")
                } finally { copyingResponses.remove(noteId) }
            }
        }
    }

    fun addFeedbackAction(noteId: String, pageId: String, text: String, practice: FeedbackPractice, sourceTextId: String? = null) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            if (text.isBlank() || note.pages.none { it.id == pageId }) return
            val action = FeedbackAction(pageId = pageId, text = text.trim(), practice = practice, sourceTextId = sourceTextId)
            updateNote(note.copy(feedbackActions = note.feedbackActions + action))
        }
    }

    fun completeFeedbackAction(noteId: String, actionId: String, done: Boolean) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            updateNote(note.copy(feedbackActions = note.feedbackActions.map { if (it.id == actionId) it.copy(done = done) else it }))
        }
    }

    fun removeFeedbackAction(noteId: String, actionId: String) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            updateNote(note.copy(feedbackActions = note.feedbackActions.filterNot { it.id == actionId }))
        }
    }

    fun openResponseAttempt(noteId: String, attemptId: String, beside: Boolean = false) {
        with(runtime) {
            val note = _state.value.notes.notebook(noteId) ?: return
            val attempt = note.longResponse?.attempts?.find { it.id == attemptId } ?: return
            val index = note.pages.indexOfFirst { it.id in attempt.pageIds }
            if (index < 0) { reportError("This attempt's pages have been deleted"); return }
            if (beside) {
                showCompanion(noteId, note.pages[index].id)
                setCompanionLinked(false)
            } else openAt(noteId, index)
        }
    }

    suspend fun createMistakePractice(user: String, mistake: com.folio.notes.mistakes.ExamTrackMistake, openWhenReady: Boolean = true): com.folio.notes.mistakes.LocalMistakeReviewAttempt {
        with(runtime) {
            ready.await()
            // Infinite canvas either way; the paper is just the printed guide behind it.
            val paper = AppPrefs.mistakePaper(prefs.getString(AppPrefs.MISTAKE_PAPER, null))
            val page = NotePage(paper = paper, infinite = true, title = mistake.question)
            val noteId = UUID.randomUUID().toString()
            val attempt = com.folio.notes.mistakes.LocalMistakeReviewAttempt(user, mistake.id, UUID.randomUUID().toString(), noteId, page.id)
            val note = Notebook(id = noteId, title = "${mistake.question} · Mistake practice", pages = listOf(page),
                mistakePractice = true, mistakeReviews = listOf(attempt))
            _state.update { it.copy(notes = it.notes + note) }
            if (openWhenReady) open(note.id)
            return attempt
        }
    }

    internal suspend fun openMusicScore(score: com.folio.notes.music.MusicScore, pdf: java.io.File): String? {
        with(runtime) {
            ready.await()
            val existing = _state.value.notes.find { it.musicScoreId == score.id }
            if (existing != null) { open(existing.id); return existing.id }
            return try {
                val seeded = score.seed?.takeIf { !com.folio.notes.music.MusicNotebook.hasLegacyInk(score) }?.let { seed ->
                    val from = _state.value.notes.find { it.musicScoreId == seed.from } ?: return@let null
                    awaitQueuedWrites()
                    seed to repository.loadPages(_state.value.notes.notebook(from.id) ?: from).pages
                }
                val note = getApplication<FolioApplication>().storageGate.withLock {
                    repository.createScoreNotebook(score.id, score.title, pdf) { blank ->
                        when {
                            com.folio.notes.music.MusicNotebook.hasLegacyInk(score) -> com.folio.notes.music.MusicNotebook.carryOver(score, blank)
                            seeded != null -> com.folio.notes.music.MusicNotebook.copyPages(seeded.second, seeded.first, blank.size) ?: blank
                            else -> blank
                        }
                    }
                }
                _state.update { it.copy(notes = it.notes + note) }
                open(note.id)
                note.id
            } catch (e: Exception) {
                reportError("Couldn't open this score: ${e.message.orEmpty()}")
                null
            }
        }
    }

    fun closeMusicScore(notebookId: String) {
        with(runtime) {
            if (_state.value.activeId == notebookId) close()
            _state.update { state -> state.copy(tabs = state.tabs.filterNot { it.notebookId == notebookId }) }
        }
    }

    internal fun deleteMusicScore(scoreId: String) {
        with(runtime) {
            _state.value.notes.find { it.musicScoreId == scoreId }?.let { notebookCatalog.discard(it) }
        }
    }

    fun completeMistakePractice(attempt: com.folio.notes.mistakes.LocalMistakeReviewAttempt) {
        with(runtime) {
            val note = _state.value.notes.notebook(attempt.practiceNotebookId) ?: return
            val updated = note.copy(mistakeReviews = note.mistakeReviews.filterNot { it.reviewId == attempt.reviewId } + attempt)
            // Rating an untouched page must not materialise an empty notebook on disk.
            if (updated.pages.all { it.strokes.isEmpty() && it.images.isEmpty() && it.texts.all { t -> t.text.isBlank() && !t.isSticky } }) {
                notebookCatalog.discard(updated)
                return
            }
            updateNote(updated)
        }
    }

    suspend fun practiceSizes(noteIds: Collection<String>): Map<String, Long> {
        with(runtime) {
            if (noteIds.isEmpty()) return emptyMap()
            ready.await()
            return repository.notebookSizes(noteIds)
        }
    }

    suspend fun purgeEmptyMistakeNotebooks(): Int {
        with(runtime) {
            ready.await()
            val candidates = _state.value.notes.filter { it.mistakePractice }
            if (candidates.isEmpty()) return 0
            val emptyIds = mutableSetOf<String>()
            for (note in candidates) {
                val empty = try { repository.isNotebookEmpty(note) } catch (_: Exception) { false }
                if (empty) emptyIds += note.id
            }
            if (emptyIds.isEmpty()) return 0
            notebookCatalog.discardNotebooks(emptyIds)
            return emptyIds.size
        }
    }
}
