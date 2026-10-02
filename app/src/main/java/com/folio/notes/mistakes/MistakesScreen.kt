@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Logout
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

private val syncDateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()) }

private data class ReviewFrame(
    val attempt: LocalMistakeReviewAttempt,
    val card: ExamTrackMistake,
    val context: ExamContext?,
    val editor: FolioState,
)

private fun formatSyncedAt(iso: String?): String {
    if (iso == null) return "Never synced"
    return runCatching {
        "Last synced ${syncDateFormat.get()!!.format(Date(timestamp(iso)))}"
    }.getOrDefault("Last synced ${iso.replace('T', ' ').take(16)} UTC")
}

internal fun dueLabel(dueAt: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val due = runCatching { Instant.ofEpochMilli(timestamp(dueAt)).atZone(zone) }.getOrNull()
        ?: return "Unknown due date"
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), due.toLocalDate())
    return when {
        days < 0 -> "${-days}d overdue"
        due.toInstant().toEpochMilli() <= now -> "Due today"
        days == 0L -> "Later today · ${due.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        days == 1L -> "Due tomorrow"
        days < 30 -> "Due in ${days}d"
        else -> "Due ${due.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}"
    }
}

internal fun intervalLabel(schedule: MistakeSchedule, rating: ReviewRating): String {
    if (rating == ReviewRating.AGAIN) return "10 min"
    val days = schedule.intervalDays.toLong()
    return when {
        days <= 0 -> "today"
        days == 1L -> "1 day"
        days < 30 -> "$days days"
        days < 365 -> "${days / 30} mo"
        else -> "${days / 365} yr"
    }
}

@Composable
fun MistakesScreen(model: MistakesViewModel, folio: FolioViewModel, folioState: FolioState,
    finger: Boolean, haptics: Boolean, shapes: Boolean, onBack: () -> Unit,
    onSettings: () -> Unit, onExport: () -> Unit,
    onReviewMode: (Boolean) -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var activeReview by rememberSaveable { mutableStateOf<String?>(null) }
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
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var sessionLimit by rememberSaveable { mutableIntStateOf(10) }
    var newestDueFirst by rememberSaveable { mutableStateOf(false) }
    var showDueSort by remember { mutableStateOf(false) }
    var sessionTotal by rememberSaveable { mutableIntStateOf(0) }
    var sessionCompleted by rememberSaveable { mutableIntStateOf(0) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { clockNow = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    val mistakes = remember(state.cache.mistakes) { state.cache.mistakes.values.toList() }
    val due = remember(mistakes, clockNow) { MistakeScheduler.getDueMistakes(mistakes, clockNow) }
    val schedules = remember(mistakes) { mistakes.associate { it.id to MistakeScheduler.getMistakeSchedule(it) } }
    val orderedDue = remember(due, newestDueFirst) { if (newestDueFirst) due.asReversed() else due }
    val overdue = remember(mistakes, clockNow) { MistakeScheduler.getOverdueMistakes(mistakes, clockNow) }
    val overdueIds = remember(overdue) { overdue.map { it.id }.toSet() }
    // The Overdue header counts the overdue queue itself, so it can never disagree with the
    // Library chip or drop a card because it was missing from the due-now list.
    val dueGroups = remember(orderedDue, overdue, overdueIds) {
        val orderedOverdue = overdue.sortedBy { schedules[it.id]?.dueAt ?: it.updatedAt }
        val shownOverdue = if (newestDueFirst) orderedOverdue.asReversed() else orderedOverdue
        listOf("Overdue" to shownOverdue, "Due today" to orderedDue.filter { it.id !in overdueIds })
            .filter { it.second.isNotEmpty() }
    }
    val dueOrderLabel = if (newestDueFirst) "Newest due first" else "Oldest due first"
    val active = state.cache.attempts.find { it.reviewId == activeReview && it.userId == state.userId }
    val card = active?.let { state.cache.mistakes[it.mistakeId] }
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
            sessionCompleted = 0; sessionTotal = 0; previousUser = state.userId
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
        if (activeReview != null) { activeReview = null; reviewQueue = emptyList(); folio.close() }
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
    val visible = remember(mistakes, due, overdueIds, filter, subject, paper, category, debouncedQuery, state.cache.contexts, schedules) {
        val q = debouncedQuery.trim().lowercase()
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
            ).any { it.lowercase().contains(q) }
            matchesSubject && matchesPaper && matchesCategory && matchesFilter && matchesQuery
        }.sortedBy { schedules[it.id]?.dueAt ?: it.updatedAt }
    }
    // Review respects the current list filters so "MM · Exam 1" reviews only those due cards.
    val reviewCandidates = remember(visible, due) { val dueSet = due.toSet(); visible.filter { it in dueSet } }
    // Attempt counts hoisted out of the card list: one O(notes) pass instead of one per card.
    val dueSet = remember(due) { due.toSet() }
    val attemptCountMap = remember(folioState.notes, state.userId) {
        buildMap<String, Int> {
            folioState.notes.forEach { n ->
                n.mistakeReviews.filter { it.userId == state.userId }.forEach { r -> put(r.mistakeId, (get(r.mistakeId) ?: 0) + 1) }
            }
        }
    }
    fun leaveReview() { activeReview = null; reviewQueue = emptyList(); folio.close() }
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
            reviewQueue = listOf(mistake.id)
            sessionTotal = 1; sessionCompleted = 0
        }
        showSummary = false
        actionMessage = null
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
    fun startSession(candidates: List<ExamTrackMistake>) {
        if (candidates.isEmpty() || working) return
        val ids = (if (shuffle) candidates.shuffled() else candidates).take(sessionLimit).map { it.id }
        sessionTotal = ids.size; sessionCompleted = 0
        reviewQueue = ids
        ids.firstOrNull()?.let { id -> candidates.find { it.id == id }?.let(::start) }
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
            catch (_: Exception) { showTransient("Could not skip this card. Please try again.") }
            finally { working = false }
        }
    }
    fun deleteCurrentCard() {
        val current = active ?: return
        if (working) return
        actionMessage = null
        working = true
        scope.launch {
            try {
                val deletedId = current.mistakeId
                model.deleteMistake(deletedId)
                sessionTotal = maxOf(sessionCompleted, sessionTotal - 1)
                if (detail == deletedId) detail = null
                val remainingIds = reviewQueue.filterNot { it == deletedId }
                showTransient("Card deleted · handwriting kept on this device.")
                val fresh = model.state.value.cache.mistakes
                val validNext = remainingIds.mapNotNull { fresh[it] }.filterNot { it.suspended }
                working = false
                if (validNext.isNotEmpty()) {
                    reviewQueue = validNext.map { it.id }
                    start(validNext.first())
                } else {
                    activeReview = null; folio.close()
                    reviewQueue = emptyList()
                    showSummary = sessionCompleted > 0
                    destination = "Today"
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
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
    DisposableEffect(Unit) { onDispose { onReviewMode(false) } }
    if (reviewFrame != null) {
        val active = reviewFrame.attempt
        val card = reviewFrame.card
        val queuePos = sessionCompleted + 1
        val queueSize = sessionTotal.takeIf { it > 0 }
        MistakeReviewScreen(card, reviewFrame.context, active, model, folio, reviewFrame.editor,
            finger, haptics, shapes, working || currentFrame == null, onBack = ::leaveReview, onSettings = onSettings, onExport = onExport,
            dueLeft = queueSize ?: due.size, queuePos = queuePos, queueSize = queueSize,
            shuffle = shuffle, onToggleShuffle = ::toggleShuffle,
            canSkip = reviewQueue.size > 1, onSkip = ::skipCurrent, actionMessage = actionMessage,
            onDelete = ::deleteCurrentCard,
            onRate = onRate@{ rating ->
            if (working || currentFrame == null || folioState.saving || folioState.saveFailed) return@onRate
            actionMessage = null
            working = true
            scope.launch {
                try {
                    val finishedId = active.mistakeId
                    val completed = model.rate(active, rating)
                    folio.completeMistakePractice(completed)
                    sessionCompleted++
                    val remainingIds = reviewQueue.filterNot { it == finishedId }
                    showTransient(
                        when (rating) {
                            ReviewRating.AGAIN -> "Saved · this card returns in about 10 minutes"
                            ReviewRating.HARD -> "Saved · next review soon"
                            ReviewRating.GOOD -> "Saved · nicely done"
                            ReviewRating.EASY -> "Saved · pushed further out"
                        }
                    )
                    val fresh = model.state.value.cache.mistakes
                    val validNext = remainingIds.mapNotNull { fresh[it] }.filterNot { it.suspended }
                    working = false
                    if (validNext.isNotEmpty()) {
                        reviewQueue = validNext.map { it.id }
                        start(validNext.first())
                    } else {
                        activeReview = null; folio.close()
                        reviewQueue = emptyList()
                        showSummary = true
                        destination = "Today"
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    showTransient("Could not save the review. Your page is kept — please retry.")
                    working = false
                }
            }
            }
        )
        return
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
                showTransient("Card deleted · handwriting kept on this device.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showTransient("Could not delete this card. Please try again.") }
            finally { working = false }
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
                onPractice = { start(selected) },
                onDelete = { deleteCard(selected.id) },
                onOpenAttempt = { noteId, pageId, reviewId, completed ->
                    if (completed) {
                        val idx = folioState.notes.find { it.id == noteId }
                            ?.pages?.indexOfFirst { it.id == pageId }?.coerceAtLeast(0) ?: 0
                        folio.openAt(noteId, idx); onBack()
                    } else scope.launch {
                        try {
                            val note = folioState.notes.find { it.id == noteId } ?: return@launch
                            val attempt = note.mistakeReviews.find { it.reviewId == reviewId } ?: return@launch
                            model.addAttempt(attempt)
                            val idx = note.pages.indexOfFirst { it.id == attempt.practicePageId }.coerceAtLeast(0)
                            folio.openAt(note.id, idx)
                            reviewQueue = listOf(attempt.mistakeId)
                            sessionTotal = 1; sessionCompleted = 0; showSummary = false
                            detail = null
                            activeReview = attempt.reviewId
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { showTransient("Could not resume this review. Your page is kept.") }
                    }
                }
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tablet = maxWidth >= 600.dp
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
        val contentInset = if (tablet) FolioSpacing.dp24 else FolioSpacing.dp16
        Scaffold(
            // TopAppBar handles the top inset. Draw the body to the bottom edge; navigation/IME
            // clearance belongs to scroll padding and the floating toolbar, never a full-width bar.
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
            topBar = {
                TopAppBar(
                    title = { Text(if (selected != null) "Question details" else "Mistakes", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        if (detail != null) IconButton({ detail = null }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to mistakes")
                        }
                    },
                    actions = {
                        if (state.userId != null) IconButton({ showAccount = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(if (isSyncTrouble(state.status)) Icons.Rounded.CloudOff else Icons.Rounded.AccountCircle, "Account and sync")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = toolbarClearance)) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.weight(if (split) .42f else 1f).fillMaxHeight(),
                        state = if (standaloneDetail) detailListState else listState,
                        contentPadding = PaddingValues(
                            start = contentInset, top = contentInset, end = contentInset,
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
                            if (isSyncTrouble(state.status) || state.cache.pending.isNotEmpty()) fullWidthItem {
                                Surface(onClick = { showAccount = true }, shape = FolioShapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = FolioSpacing.dp12, top = FolioSpacing.dp8, bottom = FolioSpacing.dp8, end = FolioSpacing.dp4),
                                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.CloudOff, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        // M3e contained loading indicator while a sync is actually running.
                                        if (state.status == "Syncing…") ContainedLoadingIndicator(Modifier.size(22.dp))
                                        Text(
                                            if (isSyncTrouble(state.status)) state.status
                                            else "${state.cache.pending.size} reviews saved locally · view sync",
                                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        TextButton(onClick = model::dismissStatus, shapes = ButtonDefaults.shapes()) {
                                            Text("Dismiss")
                                        }
                                    }
                                }
                            }
                            if (destination != "Library") {
                                if (showSummary) fullWidthItem {
                                    SessionSummary(sessionCompleted, state.cache.pending.size) { showSummary = false; destination = "Library" }
                                }
                                fullWidthItem {
                                    ReviewDashboard(due.size, mistakes.size, sessionLimit, { sessionLimit = it }, shuffle, { shuffle = !shuffle }, working,
                                        onReview = { startSession(orderedDue) }, onBrowse = { destination = "Library" }, orderLabel = dueOrderLabel)
                                }
                                val unfinished = mistakes.filter { it.id in unfinishedByMistake && !it.suspended }
                                if (unfinished.isNotEmpty()) {
                                    fullWidthItem { Text("Pick up where you left off", style = MaterialTheme.typography.titleLarge) }
                                    items(unfinished.take(3), key = { "resume-${it.id}" }) { m ->
                                        MistakeLibraryRow(m, state.cache.contexts[m.attemptId], schedules[m.id], true, attemptCountMap[m.id] ?: 0,
                                            { detail = m.id; destination = "Library" }, { reviewQueue = emptyList(); start(m) }, working, onDelete = { deleteCard(m.id) }, now = clockNow)
                                    }
                                }
                                if (due.isNotEmpty()) {
                                    fullWidthItem {
                                        Column(Modifier.padding(top = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Due for review", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                                Text("${due.size} ${if (due.size == 1) "question" else "questions"}",
                                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Box {
                                                TextButton({ showDueSort = true }, shapes = ButtonDefaults.shapes()) {
                                                    Text(dueOrderLabel, color = MaterialTheme.colorScheme.onSurface)
                                                    Icon(Icons.Rounded.ArrowDropDown, null)
                                                }
                                                DropdownMenu(showDueSort, { showDueSort = false }) {
                                                    DropdownMenuItem({ Text("Oldest due first") }, { newestDueFirst = false; showDueSort = false })
                                                    DropdownMenuItem({ Text("Newest due first") }, { newestDueFirst = true; showDueSort = false })
                                                }
                                            }
                                        }
                                    }
                                    dueGroups.forEach { (label, cards) ->
                                        fullWidthItem {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                                    color = if (label == "Overdue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                                Text("${cards.size} ${if (cards.size == 1) "question" else "questions"}",
                                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        items(cards, key = { "due-${it.id}" }) { m ->
                                            MistakeLibraryRow(m, state.cache.contexts[m.attemptId], schedules[m.id], false, attemptCountMap[m.id] ?: 0,
                                                { detail = m.id; destination = "Library" }, { reviewQueue = emptyList(); start(m) }, working, onDelete = { deleteCard(m.id) }, now = clockNow)
                                        }
                                    }
                                }
                            } else {
                                fullWidthItem {
                                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search your mistakes") },
                                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } },
                                        singleLine = true, shape = FolioShapes.large)
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                        listOf("All" to mistakes.size, "Due" to due.size, "Overdue" to overdue.size, "Upcoming" to mistakes.count { !it.suspended && it !in dueSet }, "Suspended" to mistakes.count { it.suspended }).forEach { (label, count) ->
                                            FilterChipWithCount(label, count, filter == label) { filter = label }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${visible.size} questions", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                        TextButton({ showFilters = true }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp6)); Text(if (scopeCount == 0) "Filters" else "Filters ($scopeCount)") }
                                        if (scopeCount > 0 || query.isNotBlank() || filter != "All") TextButton(::clearFilters, shapes = ButtonDefaults.shapes()) { Text("Reset") }
                                    }
                                    if (scopeCount > 0) Text(listOf(subject, paper, category).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    if (reviewCandidates.isNotEmpty()) FilledTonalButton({ startSession(reviewCandidates) }, enabled = !working, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                                        Text("Review ${minOf(reviewCandidates.size, sessionLimit)} matching due questions")
                                    }
                                }
                                if (visible.isEmpty()) fullWidthItem { EmptyMistakesCard(mistakes.isNotEmpty(), ::clearFilters) }
                                items(visible, key = { it.id }) { m ->
                                    MistakeLibraryRow(m, state.cache.contexts[m.attemptId], schedules[m.id], m.id in unfinishedByMistake,
                                        attemptCountMap[m.id] ?: 0, { detail = m.id; destination = "Library" }, { reviewQueue = emptyList(); start(m) }, working, selected = m.id == detail,
                                        onDelete = { deleteCard(m.id) }, now = clockNow)
                                }
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
                                Text("Choose a question to see its solution and handwritten attempts here.", style = MaterialTheme.typography.bodyLarge)
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
                onSelect = { destination = it; detail = null },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(start = FolioSpacing.dp16, end = FolioSpacing.dp16, bottom = FolioSpacing.dp16),
                onHeightChanged = { toolbarHeight = it },
            )
        }
    }
    if (showAccount && state.userId != null) FolioSideSheet("Account and sync", onDismissRequest = { showAccount = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            FocalAccountContent(model, onBeforeSignOut = { showAccount = false; leaveReview() })
            OfflineNoteCard()
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Text("Focus your library", style = MaterialTheme.typography.headlineSmall)
            MistakeFilterOptions("Subject", subjects, subject) { subject = it }
            MistakeFilterOptions("Paper", papers, paper) { paper = it }
            MistakeFilterOptions("Category", mistakes.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(), category) { category = it }
            Button({ showFilters = false }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("Show ${visible.size} questions") }
            TextButton({ subject = ""; paper = ""; category = "" }, shapes = ButtonDefaults.shapes()) { Text("Reset filters") }
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
                        else -> Icons.Rounded.Draw
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
                            Icon(icon, null, Modifier.size(24.dp))
                            Text(title, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---- Shared Focal account ----------------------------------------------------------------------

@Composable
internal fun FocalAccountContent(
    model: MistakesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBeforeSignOut: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        if (state.userId == null) {
            FocalLoginCard(state, model::signIn, model::signUp, model::resetPassword, model::clearAuthFeedback)
        } else {
            Text("Focal account", style = MaterialTheme.typography.headlineSmall)
            AccountCard(state.email, state.status, state.cache.lastSyncedAt, state.cache.pending.size, state.status == "Syncing…",
                { model.requestSync(force = true) }, { confirmSignOut = true })
            Text("One sign-in connects study sessions and mistake review. Handwriting stays in Folio.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out of Focal?") },
        text = { Text("This disconnects both study sessions and mistake sync. Saved sessions, handwriting and the offline cache stay on this device.") },
        dismissButton = { TextButton({ confirmSignOut = false }) { Text("Stay signed in") } },
        confirmButton = { TextButton({
            confirmSignOut = false
            onBeforeSignOut()
            model.signOut()
        }) { Text("Sign out") } },
    )
}

@Composable
private fun OfflineNoteCard() {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.OfflinePin, null, tint = MaterialTheme.colorScheme.secondary)
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Text("Works offline after the first sync", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Cards, images and ratings are kept on this device. Sync resumes when you are back online.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AccountCard(
    email: String?, status: String, lastSyncedAt: String?, pending: Int,
    syncing: Boolean, onSync: () -> Unit, onSignOut: () -> Unit,
) {
    ElevatedCard(shape = FolioShapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Text(
                        (email?.trim()?.firstOrNull()?.uppercase() ?: "F"),
                        Modifier.padding(FolioSpacing.dp12), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(email ?: "Focal", style = MaterialTheme.typography.titleMedium, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatSyncedAt(lastSyncedAt),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (syncing) LoadingIndicator(Modifier.size(22.dp))
            }
            SyncStatusRow(status, pending)
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            FilledTonalButton(onSync, enabled = !syncing, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(if (syncing) "Syncing…" else "Sync now")
            }
            OutlinedButton(onSignOut, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun SyncStatusRow(status: String, pending: Int) {
    val (icon, container, content) = when {
        status == "Syncing…" -> Triple(Icons.Rounded.Sync, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        status.startsWith(SYNC_OFFLINE) -> Triple(Icons.Rounded.CloudOff, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        status.startsWith(SYNC_FOCAL) -> Triple(Icons.Rounded.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        status.startsWith("Synced") && pending == 0 -> Triple(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        status.startsWith("Synced") -> Triple(Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        else -> Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = FolioShapes.large, color = container, contentColor = content) {
        Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (pending > 0) Text("$pending queued", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FilterChipWithCount(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, { Text("$label · $count") })
}

internal fun trimMark(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun EmptyMistakesCard(hasCards: Boolean, onClear: () -> Unit) {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp32), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(if (hasCards) Icons.Rounded.SearchOff else Icons.Rounded.School, null, Modifier.padding(FolioSpacing.dp16).size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                if (hasCards) "No mistakes match" else "Your library starts here",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                if (hasCards) "Try a different search or clear the filters to see the rest."
                else "New mistakes from Focal will land here. Log one on the web and sync.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (hasCards) TextButton(onClear, shapes = ButtonDefaults.shapes()) { Text("Clear filters") }
        }
    }
}

private fun LazyGridScope.fullWidthItem(content: @Composable ColumnScope.() -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), content = content)
    }
}
