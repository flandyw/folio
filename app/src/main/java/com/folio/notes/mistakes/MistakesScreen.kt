@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import com.folio.notes.model.countOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.mistakes.review.MistakePracticeProgress
import com.folio.notes.mistakes.review.MistakeReviewControls
import com.folio.notes.mistakes.review.MistakeReviewScreen
import com.folio.notes.mistakes.review.PreviousAttemptDialog
import com.folio.notes.mistakes.review.handwritingTab
import com.folio.notes.mistakes.review.unfinishedAttempt
import com.folio.notes.mistakes.review.unfinishedAttempts
import com.folio.notes.mistakes.review.practiceQueue
import com.folio.notes.app.AppPrefs
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.mistakes.sync.ExamContext
import com.folio.notes.mistakes.sync.isSyncTrouble
import com.folio.notes.model.Notebook
import com.folio.notes.study.FocalAccountButton
import com.folio.notes.ui.FolioActionPopover
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioScreenHeading
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val syncDateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()) }

private data class ReviewFrame(
    val attempt: LocalMistakeReviewAttempt,
    val card: ExamTrackMistake,
    val context: ExamContext?,
    val editor: FolioState,
)

internal fun formatSyncedAt(iso: String?): String {
    if (iso == null) return "Never synced"
    return runCatching {
        "Last synced ${syncDateFormat.get()!!.format(Date(timestamp(iso)))}"
    }.getOrDefault("Last synced ${iso.replace('T', ' ').take(16)} UTC")
}

@Composable
fun MistakesScreen(model: MistakesViewModel, folio: FolioViewModel, folioState: FolioState,
    finger: Boolean, haptics: Boolean, shapes: Boolean, onBack: () -> Unit,
    onSettings: () -> Unit, onExport: () -> Unit,
    onReviewMode: (Boolean) -> Unit = {}, onAccount: () -> Unit = {},
    onShareLongPress: (() -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val preferences = rememberPrefs()
    val recordStudy by rememberPref(preferences, AppPrefs.MISTAKE_RECORD_STUDY) {
        it.getBoolean(AppPrefs.MISTAKE_RECORD_STUDY, AppPrefs.DEFAULT_MISTAKE_RECORD_STUDY)
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var activeReview by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingAttempt by remember { mutableStateOf<Pair<Notebook, LocalMistakeReviewAttempt>?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var subject by rememberSaveable { mutableStateOf("") }
    var paper by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    // Debounced query drives the O(N) filter so typing never blocks the text field.
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query == debouncedQuery) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        debouncedQuery = query
    }
    var shuffle by rememberSaveable { mutableStateOf(false) }
    var reviewQueue by rememberSaveable { mutableStateOf(listOf<String>()) }
    var destination by rememberSaveable { mutableStateOf("Today") }
    var showPacing by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var sessionLimit by rememberSaveable { mutableIntStateOf(state.cache.reviewPreferences.practiceTarget) }
    LaunchedEffect(state.cache.reviewPreferences.practiceTarget) { sessionLimit = state.cache.reviewPreferences.practiceTarget }
    var sessionWeek by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionChallengeSize by rememberSaveable { mutableIntStateOf(0) }
    var sessionInitialXP by rememberSaveable { mutableIntStateOf(0) }
    var celebration by rememberSaveable { mutableIntStateOf(0) }
    val practiceView = androidx.compose.ui.platform.LocalView.current
    var newestDueFirst by rememberSaveable { mutableStateOf(false) }
    var focusSubject by rememberSaveable { mutableStateOf("") }
    var sessionTotal by rememberSaveable { mutableIntStateOf(0) }
    var sessionCompleted by rememberSaveable { mutableIntStateOf(0) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    // Ratings given in the current session, for the summary's "2 Again · 3 Good" line.
    var sessionRatings by rememberSaveable { mutableStateOf(listOf<String>()) }
    var sort by rememberSaveable { mutableStateOf(MistakeLibrarySort.DUE.name) }
    var confirmDelete by remember { mutableStateOf<ExamTrackMistake?>(null) }
    // Dismissing the "saved locally" banner hides it until more reviews queue up.
    var dismissedPending by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.cache.pending.size) {
        if (state.cache.pending.size < dismissedPending) dismissedPending = state.cache.pending.size
    }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { clockNow = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    if (showPacing) MistakeReviewControls(state.cache.reviewPreferences, state.cache.mistakes.values.toList(),
        working, { showPacing = false },
        onSave = { preferences -> scope.launch {
            working = true
            try { model.saveReviewPreferences(preferences); showPacing = false }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; snackbar.showSnackbar("Could not save review pacing. Try again.") }
            finally { working = false }
        } },
        onReduce = { days, target -> scope.launch {
            working = true
            try { val moved = model.reduceReviews(days, target); showPacing = false; snackbar.showSnackbar("Moved ${countOf(moved, "review date")}") }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; snackbar.showSnackbar("Could not move review dates. Try again.") }
            finally { working = false }
        } },
    )
    val mistakes = remember(state.cache.mistakes) { state.cache.mistakes.values.toList() }
    val practiceProgress = remember(mistakes, clockNow) { MistakePracticeProgress.get(mistakes, maxOf(clockNow, System.currentTimeMillis())) }
    val due = remember(mistakes, clockNow) { MistakeScheduler.getDueMistakes(mistakes, clockNow) }
    val schedules = remember(mistakes) { mistakes.associate { it.id to MistakeScheduler.getMistakeSchedule(it) } }
    val orderedDue = remember(due, newestDueFirst) { if (newestDueFirst) due.asReversed() else due }
    val overdue = remember(mistakes, clockNow) { MistakeScheduler.getOverdueMistakes(mistakes, clockNow) }
    val overdueIds = remember(overdue) { overdue.map { it.id }.toSet() }
    // Today can focus one subject; a focus whose questions are all done falls back to everything.
    fun subjectOf(m: ExamTrackMistake) = state.cache.contexts[m.attemptId]?.subject.orEmpty().ifBlank { MistakeToday.UNSORTED }
    val dueSubjects = remember(due, state.cache.contexts) { MistakeToday.subjects(due, ::subjectOf) }
    val todayFocus = focusSubject.takeIf { f -> dueSubjects.any { it.first == f } }.orEmpty()
    val focusedDue = remember(orderedDue, todayFocus, state.cache.contexts) {
        if (todayFocus.isBlank()) orderedDue else orderedDue.filter { subjectOf(it) == todayFocus }
    }
    // The Overdue heading counts the overdue queue itself, so it can never disagree with the
    // Library chip or drop a card because it was missing from the due-now list.
    val focusedOverdue = remember(overdue, newestDueFirst, todayFocus, state.cache.contexts) {
        val ordered = overdue.sortedBy { schedules[it.id]?.dueAt ?: it.updatedAt }
            .filter { todayFocus.isBlank() || subjectOf(it) == todayFocus }
        if (newestDueFirst) ordered.asReversed() else ordered
    }
    val todayStats = remember(mistakes, schedules, clockNow) { MistakeToday.stats(mistakes, schedules, clockNow) }
    val active = state.cache.attempts.find { it.reviewId == activeReview && it.userId == state.userId }
    val card = active?.let { state.cache.mistakes[it.mistakeId] }
    LaunchedEffect(active?.reviewId, card?.id) {
        if (active != null && card != null) model.showPracticeStudyQuestion(active, card, state.cache.contexts[card.attemptId])
        else if (activeReview == null) model.finishPracticeStudy()
    }
    LaunchedEffect(Unit) { model.requestSync() }
    // Recover a rating saved to the cache just before a process interruption of the notebook save.
    LaunchedEffect(state.cache.attempts, folioState.notes.size) {
        state.cache.attempts.filter { it.completedAt != null }.forEach { a ->
            val stored = folioState.notes.find { it.id == a.practiceNotebookId }?.mistakeReviews?.find { it.reviewId == a.reviewId }
            if (stored != null && stored != a) folio.completeMistakePractice(a)
        }
    }
    var previousUser by rememberSaveable { mutableStateOf(state.userId) }
    LaunchedEffect(state.userId) {
        if (previousUser != state.userId) {
            activeReview = null; detail = null; reviewQueue = emptyList(); showSummary = false
            sessionCompleted = 0; sessionTotal = 0; sessionRatings = emptyList(); previousUser = state.userId
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) }
    }
    fun showTransient(message: String) {
        actionMessage = message
        if (activeReview == null) scope.launch { snackbar.showSnackbar(message, duration = SnackbarDuration.Short) }
    }
    BackHandler {
        if (working) return@BackHandler
        if (activeReview != null) {
            model.finishPracticeStudy()
            activeReview = null; reviewQueue = emptyList(); folio.close()
            if (sessionCompleted > 0) { showSummary = true; destination = "Today" }
        }
        else if (detail != null) detail = null else onBack()
    }
    val selected = remember(mistakes, detail) { mistakes.find { it.id == detail } }
    // Keep every option visible so a selected Subject/Paper chip never disappears
    // when the other filter changes; the list itself still enforces both filters.
    val papers = remember(state.cache.contexts, paper) {
        (state.cache.contexts.values.map { it.paper }.filter { it.isNotBlank() } + paper)
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val subjects = remember(state.cache.contexts, subject) {
        (state.cache.contexts.values.map { it.subject }.filter { it.isNotBlank() } + subject)
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val librarySort = MistakeLibrarySort.parse(sort)
    val visible = remember(mistakes, due, overdueIds, filter, subject, paper, category, debouncedQuery, state.cache.contexts, schedules, librarySort) {
        val q = debouncedQuery.trim().lowercase()
        val words = q.split(Regex("\\s+"))
        // Set lookup keeps Due/Upcoming filtering O(N) instead of O(N²) list scans.
        val dueSet = due.toSet()
        mistakes.filter { m ->
            val ctx = state.cache.contexts[m.attemptId]
            val matchesSubject = subject.isBlank() || ctx?.subject == subject
            val matchesPaper = paper.isBlank() || ctx?.paper == paper
            val matchesCategory = category.isBlank() || category == m.category
            val matchesFilter = when (filter) {
                "Due" -> m in dueSet
                "Overdue" -> m.id in overdueIds
                "Upcoming" -> !m.suspended && m !in dueSet
                "Suspended" -> m.suspended
                else -> true
            }
            val matchesQuery = q.isBlank() || listOfNotNull(
                m.question, m.questionText, m.category, m.explanation, m.correction, m.areaOfStudy, m.criterion,
                ctx?.subject, ctx?.title, ctx?.paper
            ).joinToString(" ").lowercase().let { text -> words.all { it in text } }
            matchesSubject && matchesPaper && matchesCategory && matchesFilter && matchesQuery
        }.let { librarySort.sorted(it, schedules) }
    }
    // Review respects the current list filters so "MM · Exam 1" reviews only those due cards,
    // always oldest-due first whatever order the list is browsed in.
    val reviewCandidates = remember(visible, due) { val shown = visible.mapTo(HashSet()) { it.id }; due.filter { it.id in shown } }
    // Attempt counts hoisted out of the card list: one O(notes) pass instead of one per card.
    val dueSet = remember(due) { due.toSet() }
    val attemptCountMap = remember(folioState.notes, state.userId) {
        buildMap<String, Int> {
            folioState.notes.forEach { n ->
                n.mistakeReviews.filter { it.userId == state.userId }.forEach { r -> put(r.mistakeId, (get(r.mistakeId) ?: 0) + 1) }
            }
        }
    }
    /** Leaving part-way still ends on the summary, so finished work is acknowledged. */
    fun leaveReview() {
        model.finishPracticeStudy()
        activeReview = null; reviewQueue = emptyList(); folio.close()
        if (sessionCompleted > 0) { showSummary = true; destination = "Today" }
    }
    // Signing out happens in the shared account panel, so drop any review that belonged to the account.
    LaunchedEffect(state.userId) { if (state.userId == null && activeReview != null) leaveReview() }
    suspend fun openReview(mistake: ExamTrackMistake, user: String): LocalMistakeReviewAttempt {
        val attempt = unfinishedAttempt(folioState.notes, user, mistake.id)
            ?: folio.createMistakePractice(user, mistake, openWhenReady = false)
        // Finish disk/cache work before changing the displayed notebook. No suspension
        // between opening the editor and selecting its corresponding question.
        model.addAttempt(attempt)
        val note = folio.state.value.notes.first { it.id == attempt.practiceNotebookId }
        folio.openAt(note.id, note.pages.indexOfFirst { it.id == attempt.practicePageId }.coerceAtLeast(0))
        return attempt
    }
    fun start(mistake: ExamTrackMistake) {
        val user = state.userId ?: return
        if (working) return
        if (reviewQueue.isEmpty() || mistake.id !in reviewQueue) {
            model.beginPracticeStudy()
            reviewQueue = listOf(mistake.id)
            sessionTotal = 1; sessionCompleted = 0; sessionWeek = null; sessionInitialXP = practiceProgress.xp
            sessionRatings = emptyList()
        }
        showSummary = false
        if (sessionCompleted == 0) actionMessage = null
        working = true
        scope.launch {
            try {
                val attempt = openReview(mistake, user)
                activeReview = attempt.reviewId
                detail = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // A successfully rated card must not become rateable again if preparing
                // the next notebook fails. Its completed review is already durable.
                if (model.state.value.cache.attempts.any { it.reviewId == activeReview && it.completedAt != null }) leaveReview()
                showTransient("Could not start a practice page. Please try again.")
            }
            finally { working = false }
        }
    }
    fun startSession(candidates: List<ExamTrackMistake>, weekly: Boolean = false, limit: Int = sessionLimit,
                     first: ExamTrackMistake? = null) {
        if ((candidates.isEmpty() && first == null) || working) return
        model.beginPracticeStudy()
        val cards = practiceQueue(candidates, limit, first, shuffle)
        val ids = cards.map { it.id }
        sessionTotal = ids.size; sessionCompleted = 0; sessionRatings = emptyList()
        sessionInitialXP = practiceProgress.xp
        sessionWeek = if (weekly) practiceProgress.week else null
        sessionChallengeSize = if (weekly) practiceProgress.weeklyTarget else 0
        reviewQueue = ids
        cards.firstOrNull()?.let(::start)
    }
    fun toggleShuffle() {
        shuffle = !shuffle
        val currentId = active?.mistakeId
        val remaining = reviewQueue.filterNot { it == currentId }
        val reordered = if (shuffle) remaining.shuffled() else remaining.mapNotNull { id ->
            mistakes.find { it.id == id }
        }.sortedBy { schedules[it.id]?.dueAt ?: it.updatedAt }.map { it.id }
        reviewQueue = (currentId?.let { listOf(it) } ?: emptyList()) + reordered
    }
    fun skipCurrent() {
        val current = active ?: return
        if (working) return
        val remaining = reviewQueue.filterNot { it == current.mistakeId }
        if (remaining.isEmpty()) {
            showTransient("Only one card in this session — rate it to finish.")
            return
        }
        actionMessage = null
        // Push the skipped card to the end; its unfinished page stays saved for later.
        val user = state.userId ?: return
        working = true
        model.pausePracticeStudy()
        scope.launch {
            try {
                val next = state.cache.mistakes[remaining.first()]
                    ?: model.state.value.cache.mistakes[remaining.first()]
                if (next != null) {
                    val attempt = openReview(next, user)
                    reviewQueue = remaining + current.mistakeId
                    activeReview = attempt.reviewId
                    showTransient("Skipped for now — your page is kept and the card moves to the end.")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                card?.let { model.showPracticeStudyQuestion(current, it, state.cache.contexts[it.attemptId]) }
                showTransient("Could not skip this card. Please try again.")
            }
            finally { working = false }
        }
    }
    fun deleteCurrentCard() {
        val current = active ?: return
        if (working) return
        actionMessage = null
        working = true
        model.pausePracticeStudy()
        scope.launch {
            try {
                val deletedId = current.mistakeId
                model.deleteMistake(deletedId)
                sessionTotal = maxOf(sessionCompleted, sessionTotal - 1)
                if (detail == deletedId) detail = null
                val remainingIds = reviewQueue.filterNot { it == deletedId }
                showTransient("Card deleted · responses kept on this device.")
                val fresh = model.state.value.cache.mistakes
                val validNext = remainingIds.mapNotNull { fresh[it] }.filterNot { it.suspended }
                working = false
                if (validNext.isNotEmpty()) {
                    reviewQueue = validNext.map { it.id }
                    start(validNext.first())
                } else {
                    model.finishPracticeStudy()
                    activeReview = null; folio.close()
                    reviewQueue = emptyList()
                    showSummary = sessionCompleted > 0
                    destination = "Today"
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                card?.let { model.showPracticeStudyQuestion(current, it, state.cache.contexts[it.attemptId]) }
                showTransient("Could not delete this card. Please try again.")
                working = false
            }
        }
    }
    // The two ViewModels can be collected on different frames. Keep the last coherent
    // question/editor pair during that handoff instead of briefly rendering the library.
    var previousFrame by remember(state.userId, activeReview == null) { mutableStateOf<ReviewFrame?>(null) }
    val currentFrame = if (active != null && card != null && folioState.activeId == active.practiceNotebookId)
        ReviewFrame(active, card, state.cache.contexts[card.attemptId], folioState) else null
    SideEffect { if (currentFrame != null) previousFrame = currentFrame }
    val reviewFrame = currentFrame ?: previousFrame?.takeIf { activeReview != null && (working || (active != null && card != null)) }
    LaunchedEffect(reviewFrame != null) { onReviewMode(reviewFrame != null) }
    DisposableEffect(Unit) { onDispose { model.pausePracticeStudy(); onReviewMode(false) } }
    if (reviewFrame != null) {
        val active = reviewFrame.attempt
        val card = reviewFrame.card
        val queuePos = sessionCompleted + 1
        val queueSize = sessionTotal.takeIf { it > 0 }
        MistakeReviewScreen(card, reviewFrame.context, active, model, folio, reviewFrame.editor,
            finger, haptics, shapes, working || currentFrame == null, onBack = ::leaveReview, onSettings = onSettings, onExport = onExport,
            onShareLongPress = onShareLongPress,
            dueLeft = queueSize ?: due.size, queuePos = queuePos, queueSize = queueSize,
            shuffle = shuffle, onToggleShuffle = ::toggleShuffle,
            canSkip = reviewQueue.size > 1, onSkip = ::skipCurrent, actionMessage = actionMessage,
            onDelete = ::deleteCurrentCard,
            celebration = celebration,
            sessionXP = maxOf(0, practiceProgress.xp - sessionInitialXP),
            recordStudy = recordStudy, onRecordStudy = model::setPracticeStudyRecording,
            onRate = onRate@{ rating, confidence ->
            if (working || currentFrame == null || folio.state.value.saveFailed) return@onRate
            actionMessage = null
            working = true
            model.pausePracticeStudy()
            scope.launch {
                try {
                    // Lock the review once, then let autosave finish before recording the
                    // rating. The footer stays steady while writing, and a tap just after a
                    // stroke is accepted rather than silently dropped. A save failure also
                    // ends this wait so the page stays open for retry.
                    val saved = folio.state.first { !it.saving || it.saveFailed }
                    check(!saved.saveFailed) { "Save the response before rating" }
                    val finishedId = active.mistakeId
                    val completed = model.rate(active, rating, confidence, sessionWeek, sessionChallengeSize.takeIf { sessionWeek != null })
                    folio.completeMistakePractice(completed)
                    sessionCompleted++
                    sessionRatings = sessionRatings + rating.wire
                    val remainingIds = reviewQueue.filterNot { it == finishedId }
                    showTransient(MistakePracticeProgress.feedback(card, rating, confidence))
                    if (haptics) practiceView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    if (state.cache.reviewPreferences.practiceSound) {
                        val tone = runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 12) }.getOrNull()
                        tone?.let { player ->
                            runCatching { player.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 100) }
                            scope.launch { try { kotlinx.coroutines.delay(180) } finally { player.release() } }
                        }
                    }
                    if (remainingIds.isEmpty() || MistakePracticeProgress.rematch(card) && rating in listOf(ReviewRating.GOOD, ReviewRating.EASY)) celebration++
                    val fresh = model.state.value.cache.mistakes
                    val validNext = remainingIds.mapNotNull { fresh[it] }.filterNot { it.suspended }
                    working = false
                    if (validNext.isNotEmpty()) {
                        reviewQueue = validNext.map { it.id }
                        start(validNext.first())
                    } else {
                        model.finishPracticeStudy()
                        activeReview = null; folio.close()
                        reviewQueue = emptyList()
                        showSummary = true
                        destination = "Today"
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    model.showPracticeStudyQuestion(active, card, reviewFrame.context)
                    showTransient("Could not save the review. Your page is kept — please retry.")
                    working = false
                }
            }
            }
        )
        return
    }
    viewingAttempt?.let { previous ->
        PreviousAttemptDialog(listOf(previous), folio, closeLabel = "Back to question",
            onOpenPage = { id -> viewingAttempt = null; folio.open(id); onBack() }) { viewingAttempt = null }
    }
    fun clearFilters() { query = ""; filter = "All"; subject = ""; paper = ""; category = "" }
    /** Deletes one card from a list row's long-press menu — same flow as the detail screen. */
    fun deleteCard(id: String) {
        if (working) return
        working = true
        scope.launch {
            try {
                model.deleteMistake(id)
                if (detail == id) detail = null
                reviewQueue = reviewQueue.filterNot { it == id }
                showTransient("Card deleted · responses kept on this device.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showTransient("Could not delete this card. Please try again.") }
            finally { working = false }
        }
    }
    confirmDelete?.let { target ->
        DeleteCardDialog(target.question, working, onDismiss = { confirmDelete = null }) {
            confirmDelete = null; deleteCard(target.id)
        }
    }
    val scopeCount = listOf(subject, paper, category).count { it.isNotBlank() }
    val localNotes = remember(folioState.notes) { folioState.notes.filter { it.mistakePractice }.sortedByDescending { it.updated } }
    val unfinishedByMistake = remember(folioState.notes, state.userId) {
        state.userId?.let { unfinishedAttempts(folioState.notes, it) }.orEmpty()
    }
    val listState = rememberSaveable(destination, saver = LazyGridState.Saver) { LazyGridState() }
    val detailListState = rememberSaveable(detail, saver = LazyGridState.Saver) { LazyGridState() }
    @Composable fun DetailContent() {
        if (selected != null && state.userId != null) {
            MistakeDetailCard(
                mistake = selected,
                context = state.cache.contexts[selected.attemptId],
                schedule = schedules[selected.id],
                due = selected in due,
                now = clockNow,
                working = working,
                userId = state.userId!!,
                attachments = model.attachments,
                attempts = folioState.notes.flatMap { note -> note.mistakeReviews.map { note to it } }
                    .filter { (_, a) -> a.userId == state.userId && a.mistakeId == selected.id },
                onPractice = { if (selected in dueSet) startSession(reviewCandidates, first = selected)
                    else { reviewQueue = emptyList(); start(selected) } },
                onDelete = { deleteCard(selected.id) },
                onOpenAttempt = { noteId, pageId, reviewId, completed ->
                    if (completed) {
                        folioState.notes.find { it.id == noteId }?.let { note ->
                            note.mistakeReviews.find { it.reviewId == reviewId }?.let { viewingAttempt = note to it }
                        }
                    } else scope.launch {
                        try {
                            val note = folioState.notes.find { it.id == noteId } ?: return@launch
                            val attempt = note.mistakeReviews.find { it.reviewId == reviewId } ?: return@launch
                            model.addAttempt(attempt)
                            model.beginPracticeStudy()
                            val idx = note.pages.indexOfFirst { it.id == attempt.practicePageId }.coerceAtLeast(0)
                            folio.openAt(note.id, idx)
                            reviewQueue = if (selected in dueSet) practiceQueue(reviewCandidates, sessionLimit, selected, shuffle).map { it.id }
                                else listOf(attempt.mistakeId)
                            sessionTotal = reviewQueue.size; sessionCompleted = 0; showSummary = false; sessionWeek = null; sessionInitialXP = practiceProgress.xp
                            sessionRatings = emptyList()
                            detail = null
                            activeReview = attempt.reviewId
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { showTransient("Could not resume this review. Your page is kept.") }
                    }
                }
            )
        }
    }
    @Composable fun SyncBanner() {
        Surface(onClick = onAccount, shape = FolioShapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                Modifier.fillMaxWidth().padding(start = FolioSpacing.dp12, top = FolioSpacing.dp8, bottom = FolioSpacing.dp8, end = FolioSpacing.dp4),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val trouble = isSyncTrouble(state.status)
                // M3e contained loading indicator while a sync is actually running.
                if (state.status == "Syncing…") ContainedLoadingIndicator(Modifier.size(22.dp))
                else Icon(if (trouble) Icons.Rounded.CloudOff else Icons.Rounded.CloudUpload, null, Modifier.size(20.dp),
                    tint = if (trouble) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (trouble) state.status
                    else "${countLabel(state.cache.pending.size, "review")} saved on this device · waiting to sync",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { dismissedPending = state.cache.pending.size; model.dismissStatus() }, shapes = ButtonDefaults.shapes()) {
                    Text("Dismiss")
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = mistakeLayout(maxWidth.value.toInt(), maxHeight.value.toInt())
        val split = layout.splitLibrary && destination == "Library" && state.userId != null
        val standaloneDetail = selected != null && !split
        val columns = if (standaloneDetail || split) 1 else layout.columns
        val showDestinations = !standaloneDetail
        var toolbarHeight by remember { mutableStateOf(80.dp) }
        // Only the scroll content's trailing padding clears the overlay; the viewport continues
        // behind it instead of ending at a reserved Scaffold bottom-bar strip.
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val toolbarClearance = bottomInset + if (showDestinations) toolbarHeight + FolioSpacing.dp32 else 0.dp
        val contentInset = FolioDestinationInset
        val todayView = destination == "Today" && state.userId != null && !standaloneDetail
        val showSyncBanner = isSyncTrouble(state.status) || state.cache.pending.size > dismissedPending
        Scaffold(
            // The shell consumes the top inset. Draw the body to the bottom edge; navigation/IME
            // clearance belongs to scroll padding and the floating toolbar, never a full-width bar.
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
            topBar = {
                FolioScreenHeading(
                    if (selected != null) "Question details" else "Mistakes",
                    leading = if (detail != null) ({
                        IconButton({ detail = null }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to mistakes")
                        }
                    }) else null,
                ) {
                    if (state.userId != null && detail == null) IconButton({ showPacing = true }) {
                        Icon(Icons.Rounded.Tune, "Practice routine & pacing")
                    }
                    FocalAccountButton(onAccount, trouble = state.userId != null && isSyncTrouble(state.status))
                }
            },
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = toolbarClearance)) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (todayView) Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (showSyncBanner) Box(Modifier.padding(start = contentInset, end = contentInset, bottom = FolioSpacing.dp12)) { SyncBanner() }
                        MistakesTodayView(
                            content = TodayContent(
                                stats = todayStats,
                                progress = practiceProgress,
                                ratings = sessionRatings,
                                mistakes = mistakes,
                                preferences = state.cache.reviewPreferences,
                                sessionXP = maxOf(0, practiceProgress.xp - sessionInitialXP),
                                feedback = actionMessage,
                                celebration = celebration,
                                totalCards = mistakes.size,
                                allDue = due.size,
                                overdue = focusedOverdue,
                                dueToday = focusedDue.filter { it.id !in overdueIds },
                                resume = mistakes.filter { it.id in unfinishedByMistake && !it.suspended }.take(4),
                                subjects = dueSubjects,
                                focus = todayFocus,
                                limit = sessionLimit,
                                order = when { shuffle -> TodayOrder.SHUFFLE; newestDueFirst -> TodayOrder.NEWEST; else -> TodayOrder.OLDEST },
                                completed = sessionCompleted.takeIf { showSummary },
                                pending = state.cache.pending.size,
                                contexts = state.cache.contexts,
                                schedules = schedules,
                                attempts = attemptCountMap,
                                working = working,
                                now = maxOf(clockNow, System.currentTimeMillis()),
                                recordStudy = recordStudy,
                            ),
                            actions = TodayActions(
                                onStart = { startSession(focusedDue) },
                                onRecordStudy = model::setPracticeStudyRecording,
                                onMission = { cards, weekly -> startSession(cards, weekly, cards.size) },
                                onRoutine = { showPacing = true },
                                onPreferences = { preferences -> scope.launch {
                                    if (!working) {
                                        working = true
                                        try { model.saveReviewPreferences(preferences) }
                                        catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { snackbar.showSnackbar("Could not save practice settings. Try again.") }
                                        finally { working = false }
                                    }
                                } },
                                onBrowse = { destination = "Library" },
                                onFocus = { focusSubject = it },
                                onLimit = { sessionLimit = it },
                                onOrder = { order -> shuffle = order == TodayOrder.SHUFFLE; if (order != TodayOrder.SHUFFLE) newestDueFirst = order == TodayOrder.NEWEST },
                                onDismissSummary = { showSummary = false },
                                onOpen = { m -> detail = m.id; destination = "Library" },
                                onPractice = { m -> startSession(focusedDue, first = m) },
                                onDelete = { m -> confirmDelete = m },
                            ),
                            wide = layout.splitToday,
                            columns = layout.columns,
                            inset = contentInset,
                            bottomPadding = toolbarClearance,
                            listState = listState,
                        )
                    } else LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.weight(if (split) .42f else 1f).fillMaxHeight(),
                        state = if (standaloneDetail) detailListState else listState,
                        contentPadding = PaddingValues(
                            start = contentInset, top = 0.dp, end = contentInset,
                            bottom = contentInset + toolbarClearance,
                        ),
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)
                    ) {
                        if (standaloneDetail && state.userId != null) {
                            item(key = selected.id, span = { GridItemSpan(maxLineSpan) }) {
                                DetailContent()
                            }
                        } else if (destination == "Handwriting") {
                            handwritingTab(
                                notes = localNotes,
                                mistakes = state.cache.mistakes,
                                userId = state.userId,
                                folio = folio,
                                model = model,
                                scope = scope,
                                onOpenNotebook = { id -> folio.open(id); onBack() },
                                onShowMessage = ::showTransient,
                            )
                        } else if (state.userId == null) {
                            fullWidthItem { FocalAccountContent(model) }
                            fullWidthItem { OfflineNoteCard() }
                        } else {
                            if (showSyncBanner) fullWidthItem { SyncBanner() }
                            fullWidthItem {
                                val searchKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search your mistakes") },
                                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; searchKeyboard?.hide() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } },
                                    singleLine = true, shape = FolioShapes.large,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { searchKeyboard?.hide() }))
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                    listOf("All" to mistakes.size, "Due" to due.size, "Overdue" to overdue.size, "Upcoming" to mistakes.count { !it.suspended && it !in dueSet }, "Suspended" to mistakes.count { it.suspended }).forEach { (label, count) ->
                                        FilterChipWithCount(label, count, filter == label) { filter = label }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(countLabel(visible.size, "question") + " · " + librarySort.label.lowercase(),
                                        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box {
                                        TextButton({ showFilters = true }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp6)); Text(if (scopeCount == 0) "Filters" else "Filters ($scopeCount)") }
                                        if (showFilters) FolioActionPopover("Focus your library", { showFilters = false }) {
                                            MistakeFilterOptions("Sort by", MistakeLibrarySort.entries.map { it.label }, librarySort.label, allowAll = false) { label ->
                                                sort = MistakeLibrarySort.entries.firstOrNull { it.label == label }?.name ?: MistakeLibrarySort.DUE.name
                                            }
                                            MistakeFilterOptions("Subject", subjects, subject) { subject = it }
                                            MistakeFilterOptions("Paper", papers, paper) { paper = it }
                                            MistakeFilterOptions("Category", mistakes.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(), category) { category = it }
                                            Button({ showFilters = false }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("Show ${countLabel(visible.size, "question")}") }
                                            TextButton({ subject = ""; paper = ""; category = "" }, shapes = ButtonDefaults.shapes()) { Text("Reset filters") }
                                        }
                                    }
                                    if (scopeCount > 0 || query.isNotBlank() || filter != "All") TextButton(::clearFilters, shapes = ButtonDefaults.shapes()) { Text("Reset") }
                                }
                                if (scopeCount > 0) Text(listOf(subject, paper, category).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (reviewCandidates.isNotEmpty()) FilledTonalButton({ startSession(reviewCandidates) }, enabled = !working, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(FolioSpacing.dp6))
                                    Text("Review ${countLabel(minOf(reviewCandidates.size, sessionLimit), "matching due question")}")
                                }
                            }
                            if (visible.isEmpty()) fullWidthItem { EmptyMistakesCard(mistakes.isNotEmpty(), filter, scopeCount > 0 || query.isNotBlank(), ::clearFilters) }
                            items(visible, key = { it.id }) { m ->
                                MistakeLibraryRow(m, state.cache.contexts[m.attemptId], schedules[m.id], m.id in unfinishedByMistake,
                                    attemptCountMap[m.id] ?: 0, { detail = m.id; destination = "Library" }, {
                                        if (m in dueSet) startSession(reviewCandidates, first = m)
                                        else { reviewQueue = emptyList(); start(m) }
                                    }, working, selected = m.id == detail,
                                    onDelete = { confirmDelete = m }, now = clockNow)
                            }
                        }
                    }
                    if (split) {
                        VerticalDivider()
                        Surface(Modifier.weight(.58f).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            if (selected != null) key(selected.id) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                                    start = FolioSpacing.dp24, top = FolioSpacing.dp24, end = FolioSpacing.dp24,
                                    bottom = FolioSpacing.dp24 + toolbarClearance,
                                )) { DetailContent() }
                            } else Column(Modifier.fillMaxSize().padding(FolioSpacing.dp32), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(FolioSpacing.dp16))
                                Text("Room to work through it", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(FolioSpacing.dp8))
                                Text("Choose a question to see its solution and previous attempts here.", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
        if (showDestinations) {
            val tabs = if (state.userId == null) listOf("Connect", "Handwriting") else listOf("Today", "Library", "Handwriting")
            val tab = destination.takeIf { it in tabs } ?: tabs.first()
            MistakesDestinationToolbar(
                destinations = tabs,
                selected = tab,
                badges = mapOf("Today" to due.size),
                onSelect = { destination = it; detail = null },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(start = FolioSpacing.dp16, end = FolioSpacing.dp16, bottom = FolioSpacing.dp16),
                onHeightChanged = { toolbarHeight = it },
            )
        }
    }

}

// ---- Destination toolbar -----------------------------------------------------------------------

/** A centered overlay with equal destination cells and concentric outer/selected pill shapes. */
@Composable
private fun MistakesDestinationToolbar(
    destinations: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<String, Int> = emptyMap(),
    onHeightChanged: (Dp) -> Unit = {},
) {
    val standard = FloatingToolbarDefaults.standardFloatingToolbarColors()
    val vibrant = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    val labelWidth = remember(destinations, labelStyle, density) {
        with(density) { destinations.maxOf { textMeasurer.measure(it, labelStyle).size.width }.toDp() }
    }
    val cellWidth = (labelWidth + FolioSpacing.dp24).coerceAtLeast(72.dp)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val width = (cellWidth * destinations.size + FolioSpacing.dp16).coerceAtMost(maxWidth)
        Surface(
            modifier = Modifier.width(width).onSizeChanged { onHeightChanged(with(density) { it.height.toDp() }) },
            shape = CircleShape,
            color = standard.toolbarContainerColor,
            contentColor = standard.toolbarContentColor,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(FolioSpacing.dp8).selectableGroup()) {
                destinations.forEach { title ->
                    val isSelected = title == selected
                    val icon = when (title) {
                        "Today" -> Icons.Rounded.Today
                        "Library" -> Icons.Rounded.GridView
                        "Connect" -> Icons.Rounded.CloudOff
                        else -> Icons.AutoMirrored.Rounded.TextSnippet
                    }
                    Surface(
                        modifier = Modifier.weight(1f).clip(CircleShape).selectable(
                            selected = isSelected, onClick = { onSelect(title) }, role = Role.Tab,
                        ),
                        shape = CircleShape,
                        color = if (isSelected) vibrant.toolbarContainerColor else Color.Transparent,
                        contentColor = if (isSelected) vibrant.toolbarContentColor else standard.toolbarContentColor,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp8),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4, Alignment.CenterVertically),
                        ) {
                            val count = badges[title] ?: 0
                            // The due count rides on the tab, so it is visible from Library and Responses too.
                            BadgedBox(badge = { if (count > 0 && !isSelected) Badge { Text(if (count > 99) "99+" else "$count") } },
                                modifier = Modifier.semantics { if (count > 0) stateDescription = "$count due" }) {
                                Icon(icon, null, Modifier.size(24.dp))
                            }
                            Text(if (title == "Handwriting") "Responses" else title, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---- Shared Focal account ----------------------------------------------------------------------

@Composable
private fun FilterChipWithCount(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, { Text("$label · $count") })
}

@Composable
private fun EmptyMistakesCard(hasCards: Boolean, status: String, narrowed: Boolean, onClear: () -> Unit) {
    // A status filter with nothing in it is good news, not a failed search.
    val (icon, title, body) = when {
        !hasCards -> Triple(Icons.Rounded.School, "Your library starts here",
            "New mistakes from Focal will land here. Log one on the web and sync.")
        !narrowed && status == "Due" -> Triple(Icons.Rounded.DoneAll, "Nothing is due", "Questions return here when their next review is due.")
        !narrowed && status == "Overdue" -> Triple(Icons.Rounded.DoneAll, "Nothing is overdue", "You're on top of every scheduled review.")
        !narrowed && status == "Suspended" -> Triple(Icons.Rounded.PauseCircle, "No suspended questions", "Questions you suspend in Focal are listed here.")
        !narrowed && status == "Upcoming" -> Triple(Icons.Rounded.EventAvailable, "Nothing upcoming", "Every active question is due now.")
        else -> Triple(Icons.Rounded.SearchOff, "No mistakes match", "Try a different search or clear the filters to see the rest.")
    }
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp32), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, null, Modifier.padding(FolioSpacing.dp16).size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (hasCards) TextButton(onClear, shapes = ButtonDefaults.shapes()) { Text(if (narrowed) "Clear filters" else "Show all questions") }
        }
    }
}

private fun LazyGridScope.fullWidthItem(content: @Composable ColumnScope.() -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), content = content)
    }
}
