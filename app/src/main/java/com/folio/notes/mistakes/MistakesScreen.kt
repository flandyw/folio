@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val syncDateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()) }
private val dueDateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM", Locale.getDefault()) }

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

internal fun dueLabel(dueAt: String, now: Long = System.currentTimeMillis()): String {
    val diff = runCatching { timestamp(dueAt) - now }.getOrDefault(0L)
    if (diff <= 0) {
        val overdueDays = TimeUnit.MILLISECONDS.toDays(-diff)
        return if (overdueDays < 1) "Due now" else "Overdue $overdueDays d"
    }
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        diff < TimeUnit.HOURS.toMillis(20) -> "Due today"
        diff < TimeUnit.HOURS.toMillis(44) -> "Due tomorrow"
        days < 30 -> "Due in $days d"
        else -> runCatching { "Due ${dueDateFormat.get()!!.format(Date(timestamp(dueAt)))}" }
            .getOrDefault("Due ${dueAt.take(10)}")
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

private fun emailLooksValid(email: String): Boolean {
    val t = email.trim()
    if (' ' in t || '@' !in t) return false
    val domain = t.substringAfter('@')
    return '.' in domain && t.length >= 6
}

@Composable
fun MistakesScreen(model: MistakesViewModel, folio: FolioViewModel, folioState: FolioState,
    finger: Boolean, haptics: Boolean, shapes: Boolean, onBack: () -> Unit,
    onSettings: () -> Unit, onExport: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var activeReview by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf("Today") }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var sessionLimit by rememberSaveable { mutableIntStateOf(10) }
    var sessionTotal by rememberSaveable { mutableIntStateOf(0) }
    var sessionCompleted by rememberSaveable { mutableIntStateOf(0) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { clockNow = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    var confirmSignOut by remember { mutableStateOf(false) }
    val mistakes = remember(state.cache.mistakes) { state.cache.mistakes.values.toList() }
    val due = remember(mistakes, clockNow) { MistakeScheduler.getDueMistakes(mistakes, clockNow) }
    val schedules = remember(mistakes) { mistakes.associate { it.id to MistakeScheduler.getMistakeSchedule(it) } }
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
        password = ""
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
    val visible = remember(mistakes, due, filter, subject, paper, category, debouncedQuery, state.cache.contexts, schedules) {
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
            if (working || currentFrame == null || folioState.pendingSaves > 0 || folioState.saveFailed) return@onRate
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
                working = working,
                userId = state.userId!!,
                attachments = model.attachments,
                attempts = folioState.notes.flatMap { note -> note.mistakeReviews.map { note to it } }
                    .filter { (_, a) -> a.userId == state.userId && a.mistakeId == selected.id },
                onPractice = { start(selected) },
                onDelete = {
                    val id = selected.id
                    if (!working) {
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
                },
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected != null) "Question details" else "Mistakes", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton({ if (detail != null) detail = null else onBack() }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (detail != null) "Back to mistakes" else "Back to library")
                } },
                actions = {
                    if (state.userId != null) IconButton({ showAccount = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(if (state.status.startsWith("Offline")) Icons.Rounded.CloudOff else Icons.Rounded.AccountCircle, "Account and sync")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val tablet = maxWidth >= 600.dp
            val layout = mistakeLayout(maxWidth.value.toInt(), maxHeight.value.toInt())
            val split = layout.splitLibrary && destination == "Library" && state.userId != null
            val standaloneDetail = selected != null && !split
            val columns = if (standaloneDetail || split) 1 else layout.columns
            Column(Modifier.fillMaxSize()) {
                if (!standaloneDetail) {
                    val tabs = if (state.userId == null) listOf("Connect", "Handwriting") else listOf("Today", "Library", "Handwriting")
                    val tab = destination.takeIf { it in tabs } ?: tabs.first()
                    PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
                        tabs.forEach { title -> Tab(selected = tab == title, onClick = { destination = title; detail = null }, text = { Text(title) }) }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.weight(if (split) .42f else 1f).fillMaxHeight(),
                        state = if (standaloneDetail) detailListState else listState, contentPadding = PaddingValues(if (tablet) 16.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                            fullWidthItem { LoginCard(email, { email = it }, password, { password = it }, showPassword, { showPassword = it }, state, model) }
                            fullWidthItem { OfflineNoteCard() }
                        } else {
                            if (state.status.startsWith("Offline") || state.status.startsWith("ExamTrack") || state.cache.pending.isNotEmpty()) fullWidthItem {
                                Surface(onClick = { showAccount = true }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Rounded.CloudOff, null, Modifier.size(20.dp))
                                        Text(if (state.status.startsWith("ExamTrack") || state.status.startsWith("Offline")) state.status
                                            else "${state.cache.pending.size} reviews saved locally · view sync", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (destination != "Library") {
                                if (showSummary) fullWidthItem {
                                    SessionSummary(sessionCompleted, state.cache.pending.size) { showSummary = false; destination = "Library" }
                                }
                                fullWidthItem {
                                    ReviewDashboard(due.size, mistakes.size, sessionLimit, { sessionLimit = it }, shuffle, { shuffle = !shuffle }, working,
                                        onReview = { startSession(due) }, onBrowse = { destination = "Library" })
                                }
                                val unfinished = mistakes.filter { it.id in unfinishedByMistake && !it.suspended }
                                if (unfinished.isNotEmpty()) {
                                    fullWidthItem { Text("Pick up where you left off", style = MaterialTheme.typography.titleLarge) }
                                    items(unfinished.take(3), key = { "resume-${it.id}" }) { m ->
                                        MistakeLibraryRow(m, state.cache.contexts[m.attemptId], schedules[m.id], true, attemptCountMap[m.id] ?: 0,
                                            { detail = m.id; destination = "Library" }, { reviewQueue = emptyList(); start(m) }, working, onDelete = { deleteCard(m.id) })
                                    }
                                }
                                if (due.isNotEmpty()) {
                                    fullWidthItem {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Next to review", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                            TextButton({ destination = "Library"; filter = "Due" }, shapes = ButtonDefaults.shapes()) { Text("See all ${due.size}") }
                                        }
                                    }
                                    items(due.take(5), key = { "due-${it.id}" }) { m ->
                                        MistakeLibraryRow(m, state.cache.contexts[m.attemptId], schedules[m.id], false, attemptCountMap[m.id] ?: 0,
                                            { detail = m.id; destination = "Library" }, { reviewQueue = emptyList(); start(m) }, working, onDelete = { deleteCard(m.id) })
                                    }
                                }
                            } else {
                                fullWidthItem {
                                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search your mistakes") },
                                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear search") } },
                                        singleLine = true, shape = RoundedCornerShape(16.dp))
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("All" to mistakes.size, "Due" to due.size, "Upcoming" to mistakes.count { !it.suspended && it !in dueSet }, "Suspended" to mistakes.count { it.suspended }).forEach { (label, count) ->
                                            FilterChipWithCount(label, count, filter == label) { filter = label }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${visible.size} questions", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                        TextButton({ showFilters = true }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (scopeCount == 0) "Filters" else "Filters ($scopeCount)") }
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
                                        onDelete = { deleteCard(m.id) })
                                }
                            }
                        }
                    }
                    if (split) {
                        VerticalDivider()
                        Surface(Modifier.weight(.58f).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            if (selected != null) key(selected.id) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) { DetailContent() }
                            } else Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(16.dp))
                                Text("Room to work through it", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(8.dp))
                                Text("Choose a question to see its solution and handwritten attempts here.", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAccount && state.userId != null) ModalBottomSheet(onDismissRequest = { showAccount = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ExamTrack connection", style = MaterialTheme.typography.headlineSmall)
            AccountCard(state.email, state.status, state.cache.lastSyncedAt, state.cache.pending.size, state.status == "Syncing…",
                { model.requestSync(force = true) }, { showAccount = false; confirmSignOut = true })
            Text("Questions and ratings sync with ExamTrack. Handwriting stays in Folio.", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Focus your library", style = MaterialTheme.typography.headlineSmall)
            MistakeFilterOptions("Subject", subjects, subject) { subject = it }
            MistakeFilterOptions("Paper", papers, paper) { paper = it }
            MistakeFilterOptions("Category", mistakes.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(), category) { category = it }
            Button({ showFilters = false }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("Show ${visible.size} questions") }
            TextButton({ subject = ""; paper = ""; category = "" }, shapes = ButtonDefaults.shapes()) { Text("Reset filters") }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
            title = { Text("Sign out of ExamTrack?") },
            text = { Text("Your cloud list hides until the next sign-in. Handwriting on this device and the offline cache stay put.") },
            dismissButton = { TextButton({ confirmSignOut = false }, shapes = ButtonDefaults.shapes()) { Text("Stay signed in") } },
            confirmButton = {
                Button({
                    confirmSignOut = false
                    leaveReview()
                    model.signOut()
                }, shapes = ButtonDefaults.shapes()) { Text("Sign out") }
            }
        )
    }
}

// ---- Login + account ---------------------------------------------------------------------------

@Composable
private fun LoginCard(
    email: String, onEmail: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    showPassword: Boolean, onShowPassword: (Boolean) -> Unit,
    state: MistakesState, model: MistakesViewModel,
) {
    val focus = LocalFocusManager.current
    var emailTouched by remember { mutableStateOf(false) }
    val emailValid = email.isBlank() || emailLooksValid(email)
    val canSubmit = !state.busy && emailLooksValid(email) && password.isNotEmpty()
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.School, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text("Review your mistakes", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Spaced repetition from ExamTrack, answered in your own handwriting.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.status == "Loading saved mistakes…") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoadingIndicator(Modifier.size(18.dp))
                    Text("Restoring your saved session…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(
                email, { onEmail(it); emailTouched = true },
                Modifier.fillMaxWidth(),
                label = { Text("Email") },
                placeholder = { Text("you@example.com") },
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) },
                trailingIcon = { if (email.isNotEmpty()) IconButton({ onEmail("") }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Clear email") } },
                singleLine = true,
                isError = emailTouched && !emailValid,
                supportingText = {
                    if (emailTouched && !emailValid) Text("Enter the email you use in ExamTrack.")
                    else Text("Use the same email and password as ExamTrack sync.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                enabled = !state.busy
            )
            OutlinedTextField(
                password, onPassword,
                Modifier.fillMaxWidth(),
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                trailingIcon = {
                    IconButton({ onShowPassword(!showPassword) }, shapes = IconButtonDefaults.shapes()) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "Hide password" else "Show password")
                    }
                },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (canSubmit) {
                        val pw = password; onPassword(""); focus.clearFocus()
                        model.signIn(email, pw)
                    }
                }),
                enabled = !state.busy
            )
            if (state.error != null) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(state.error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Button(
                {
                    val pw = password; onPassword(""); focus.clearFocus()
                    model.signIn(email, pw)
                },
                enabled = canSubmit,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                if (state.busy) {
                    LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Signing in…")
                } else {
                    Text("Sign in")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            Text(
                "No account yet, or forgot your password? Create and recover it in ExamTrack — Folio only signs in.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OfflineNoteCard() {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.OfflinePin, null, tint = MaterialTheme.colorScheme.secondary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Text(
                        (email?.trim()?.firstOrNull()?.uppercase() ?: "E"),
                        Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(email ?: "ExamTrack", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatSyncedAt(lastSyncedAt),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (syncing) LoadingIndicator(Modifier.size(22.dp))
                else IconButton(onSync, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Sync, "Sync now") }
                IconButton(onSignOut, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Logout, "Sign out") }
            }
            SyncStatusRow(status, pending)
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SyncStatusRow(status: String, pending: Int) {
    val (icon, container, content) = when {
        status == "Syncing…" -> Triple(Icons.Rounded.Sync, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        status.startsWith("Offline") -> Triple(Icons.Rounded.CloudOff, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        status.startsWith("ExamTrack") -> Triple(Icons.Rounded.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        status.startsWith("Synced") && pending == 0 -> Triple(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        status.startsWith("Synced") -> Triple(Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        else -> Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(14.dp), color = container, contentColor = content) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(if (hasCards) Icons.Rounded.SearchOff else Icons.Rounded.School, null, Modifier.padding(16.dp).size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                if (hasCards) "No mistakes match" else "Your library starts here",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                if (hasCards) "Try a different search or clear the filters to see the rest."
                else "New mistakes from ExamTrack will land here. Log one on the web and sync.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (hasCards) TextButton(onClear, shapes = ButtonDefaults.shapes()) { Text("Clear filters") }
        }
    }
}

private fun LazyGridScope.fullWidthItem(content: @Composable ColumnScope.() -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
