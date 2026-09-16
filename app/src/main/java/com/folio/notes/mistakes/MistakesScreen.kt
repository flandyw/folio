@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
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
    var working by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var subject by rememberSaveable { mutableStateOf("") }
    var paper by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var shuffle by rememberSaveable { mutableStateOf(false) }
    var reviewQueue by remember { mutableStateOf(listOf<String>()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showPractice by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val mistakes = remember(state.cache.mistakes) { state.cache.mistakes.values.toList() }
    val due = remember(mistakes) { MistakeScheduler.getDueMistakes(mistakes) }
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
    LaunchedEffect(state.userId) { activeReview = null; detail = null; password = ""; reviewQueue = emptyList() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) }
    }
    fun showTransient(message: String) {
        scope.launch { snackbar.showSnackbar(message, duration = SnackbarDuration.Short) }
    }
    BackHandler {
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
    val visible = remember(mistakes, due, filter, subject, paper, category, query, state.cache.contexts, schedules) {
        val q = query.trim().lowercase()
        mistakes.filter { m ->
            val ctx = state.cache.contexts[m.attemptId]
            val matchesSubject = subject.isBlank() || ctx?.subject == subject
            val matchesPaper = paper.isBlank() || ctx?.paper == paper
            val matchesCategory = category.isBlank() || category == m.category
            val matchesFilter = when (filter) {
                "Due" -> m in due
                "Upcoming" -> !m.suspended && m !in due
                "Suspended" -> m.suspended
                else -> true
            }
            val matchesQuery = q.isBlank() || listOfNotNull(
                m.question, m.questionText, m.category, m.explanation,
                ctx?.subject, ctx?.title, ctx?.paper
            ).any { it.lowercase().contains(q) }
            matchesSubject && matchesPaper && matchesCategory && matchesFilter && matchesQuery
        }.sortedBy { schedules[it.id]?.dueAt ?: it.updatedAt }
    }
    // Review respects the current list filters so "MM · Exam 1" reviews only those due cards.
    val reviewCandidates = remember(visible, due) { visible.filter { it in due } }
    fun leaveReview() { activeReview = null; reviewQueue = emptyList(); folio.close() }
    fun start(mistake: ExamTrackMistake) {
        val user = state.userId ?: return
        if (working) return
        // Keep the session queue so ratings/skips advance in shuffle order.
        if (reviewQueue.isEmpty() || mistake.id !in reviewQueue) {
            val rest = reviewCandidates.filterNot { it.id == mistake.id }
            val restIds = if (shuffle) rest.shuffled().map { it.id } else rest.map { it.id }
            reviewQueue = listOf(mistake.id) + restIds
        }
        working = true
        scope.launch {
            try {
                val attempt = folio.createMistakePractice(user, mistake)
                model.addAttempt(attempt)
                activeReview = attempt.reviewId
                detail = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showTransient("Could not start a practice page. Please try again.") }
            finally { working = false }
        }
    }
    fun startSession(candidates: List<ExamTrackMistake>) {
        if (candidates.isEmpty() || working) return
        val ids = if (shuffle) candidates.shuffled().map { it.id } else candidates.map { it.id }
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
        // Push the skipped card to the end; its unfinished page stays saved for later.
        reviewQueue = remaining + current.mistakeId
        val user = state.userId ?: return
        working = true
        scope.launch {
            try {
                val next = state.cache.mistakes[remaining.first()]
                    ?: model.state.value.cache.mistakes[remaining.first()]
                if (next != null) {
                    val attempt = folio.createMistakePractice(user, next)
                    model.addAttempt(attempt)
                    activeReview = attempt.reviewId
                    showTransient("Skipped for now — your page is kept and the card moves to the end.")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { showTransient("Could not skip this card. Please try again.") }
            finally { working = false }
        }
    }
    if (active != null && card != null && folioState.activeId == active.practiceNotebookId) {
        val queuePos = reviewQueue.indexOf(active.mistakeId).takeIf { it >= 0 }?.plus(1)
        val queueSize = reviewQueue.size.takeIf { it > 0 }
        MistakeReviewScreen(card, state.cache.contexts[card.attemptId], active, model, folio, folioState,
            finger, haptics, shapes, working, onBack = ::leaveReview, onSettings = onSettings, onExport = onExport,
            dueLeft = queueSize ?: due.size, queuePos = queuePos, queueSize = queueSize,
            shuffle = shuffle, onToggleShuffle = ::toggleShuffle,
            canSkip = (queueSize ?: 0) > 1, onSkip = ::skipCurrent) { rating ->
            working = true
            scope.launch {
                try {
                    val finishedId = active.mistakeId
                    val completed = model.rate(active, rating)
                    folio.completeMistakePractice(completed)
                    val remainingIds = reviewQueue.filterNot { it == finishedId }
                    // Leave first so the editor closes even when the session is done.
                    activeReview = null; folio.close()
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
                        reviewQueue = emptyList()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    showTransient("Could not save the review. Your page is kept — please retry.")
                    working = false
                }
            }
        }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mistakes", style = MaterialTheme.typography.titleLarge)
                        if (state.userId != null) {
                            Text(
                                if (due.isEmpty()) "${mistakes.size} cards · all caught up"
                                else "${due.size} due · ${mistakes.size} total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") }
                },
                actions = {
                    if (state.userId != null) {
                        val syncing = state.status == "Syncing…"
                        IconButton(
                            { model.requestSync(force = true) },
                            enabled = !syncing
                        ) {
                            if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Sync, "Sync now")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.userId == null) {
                item { LoginCard(email, { email = it }, password, { password = it }, showPassword, { showPassword = it }, state, model) }
                item { OfflineNoteCard() }
            } else {
                item {
                    AccountCard(
                        email = state.email, status = state.status,
                        lastSyncedAt = state.cache.lastSyncedAt, pending = state.cache.pending.size,
                        syncing = state.status == "Syncing…",
                        onSync = { model.requestSync(force = true) },
                        onSignOut = { confirmSignOut = true }
                    )
                }
                if (state.cache.pending.isNotEmpty()) {
                    item {
                        PendingCard(
                            count = state.cache.pending.size,
                            offline = state.status.startsWith("Offline"),
                            onSync = { model.requestSync(force = true) }
                        )
                    }
                }
                if (state.status.startsWith("Offline")) {
                    item { OfflineBannerCard(state.status) }
                }
                item {
                    val filtersActive = query.isNotBlank() || filter != "All" || subject.isNotBlank() ||
                        paper.isNotBlank() || category.isNotBlank()
                    ReviewHeroCard(
                        due = due.size, total = mistakes.size,
                        filteredDue = if (filtersActive) reviewCandidates.size else null,
                        upcoming = mistakes.count { !it.suspended && schedules[it.id]?.let { s -> runCatching { timestamp(s.dueAt) }.getOrDefault(0L) > System.currentTimeMillis() } == true },
                        working = working,
                        shuffle = shuffle, onToggleShuffle = { shuffle = !shuffle },
                        reviewEnabled = reviewCandidates.isNotEmpty(),
                        onReview = { startSession(reviewCandidates) }
                    )
                }
                item {
                    FilterCard(
                        query = query, onQuery = { query = it },
                        filter = filter, onFilter = { filter = it },
                        all = mistakes.size, dueCount = due.size,
                        upcomingCount = mistakes.count { m -> !m.suspended && m !in due },
                        suspendedCount = mistakes.count { it.suspended },
                        subject = subject, onSubject = { subject = it },
                        subjects = subjects,
                        paper = paper, onPaper = { paper = it },
                        papers = papers,
                        category = category, onCategory = { category = it },
                        categories = mistakes.map { it.category }.distinct().sorted(),
                        onClear = { query = ""; filter = "All"; subject = ""; paper = ""; category = "" }
                    )
                }
                if (selected != null) {
                    item {
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
                            onBackToList = { detail = null },
                            onPractice = { start(selected) },
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
                                        activeReview = attempt.reviewId
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { showTransient("Could not resume this review. Your page is kept.") }
                                }
                            }
                        )
                    }
                } else {
                    if (visible.isEmpty()) {
                        item {
                            EmptyMistakesCard(
                                hasCards = mistakes.isNotEmpty(),
                                onClear = { query = ""; filter = "All"; subject = ""; paper = ""; category = "" }
                            )
                        }
                    } else {
                        items(visible, key = { it.id }) { m ->
                            MistakeCard(
                                mistake = m,
                                context = state.cache.contexts[m.attemptId],
                                schedule = schedules[m.id],
                                due = m in due,
                                attachmentCount = m.attachments.size,
                                attemptCount = folioState.notes.sumOf { n -> n.mistakeReviews.count { it.mistakeId == m.id } },
                                onOpen = { detail = m.id },
                                onPractice = { start(m) },
                                working = working
                            )
                        }
                    }
                }
                item {
                    PracticeNotebookCard(
                        expanded = showPractice,
                        onToggle = { showPractice = !showPractice },
                        titles = folioState.notes.filter { it.mistakePractice }.map { it.id to it.title },
                        onOpen = { folio.open(it); onBack() }
                    )
                }
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.Rounded.Logout, null) },
            title = { Text("Sign out of ExamTrack?") },
            text = { Text("Your cloud list hides until the next sign-in. Handwriting on this device and the offline cache stay put.") },
            dismissButton = { TextButton({ confirmSignOut = false }) { Text("Stay signed in") } },
            confirmButton = {
                Button({
                    confirmSignOut = false
                    leaveReview()
                    model.signOut()
                }) { Text("Sign out") }
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
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Restoring your saved session…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(
                email, { onEmail(it); emailTouched = true },
                Modifier.fillMaxWidth(),
                label = { Text("Email") },
                placeholder = { Text("you@example.com") },
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) },
                trailingIcon = { if (email.isNotEmpty()) IconButton({ onEmail("") }) { Icon(Icons.Rounded.Close, "Clear email") } },
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
                    IconButton({ onShowPassword(!showPassword) }) {
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
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
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
                if (syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else IconButton(onSync) { Icon(Icons.Rounded.Sync, "Sync now") }
                IconButton(onSignOut) { Icon(Icons.Rounded.Logout, "Sign out") }
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
private fun PendingCard(count: Int, offline: Boolean, onSync: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$count review${if (count == 1) "" else "s"} waiting to sync", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    if (offline) "Saved safely on this device — they will upload when you reconnect."
                    else "Saved on this device. Sync to finish uploading.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            if (!offline) FilledTonalButton(onSync) { Text("Sync") }
        }
    }
}

@Composable
private fun OfflineBannerCard(status: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("You are offline", style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Home ----------------------------------------------------------------------------

@Composable
private fun ReviewHeroCard(
    due: Int, total: Int, filteredDue: Int?, upcoming: Int, working: Boolean,
    shuffle: Boolean, onToggleShuffle: () -> Unit, reviewEnabled: Boolean, onReview: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (due == 0 && total > 0) "All caught up"
                        else if (total == 0) "No mistakes yet"
                        else if (filteredDue != null && filteredDue != due) "$filteredDue due in filter"
                        else "$due due",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        when {
                            total == 0 -> "Mistakes you log in ExamTrack will appear here for review."
                            due == 0 -> "$total cards · $upcoming upcoming — enjoy the clear desk."
                            filteredDue != null && filteredDue == 0 -> "$due due total · none match the current filters."
                            filteredDue != null -> "$filteredDue of $due due match the filters · $upcoming upcoming."
                            else -> "$total cards · $upcoming upcoming — one page at a time."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Button(
                    onReview, enabled = reviewEnabled && !working,
                    shapes = ButtonDefaults.shapes()
                ) {
                    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Rounded.PlayArrow, "Review due mistakes")
                    Spacer(Modifier.width(6.dp))
                    Text("Review")
                }
            }
            if (total > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        shuffle, onToggleShuffle,
                        { Text(if (shuffle) "Shuffled" else "In order") },
                        leadingIcon = { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)) }
                    )
                    Text(
                        if (shuffle) "Random order" else "Oldest due first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterCard(
    query: String, onQuery: (String) -> Unit,
    filter: String, onFilter: (String) -> Unit,
    all: Int, dueCount: Int, upcomingCount: Int, suspendedCount: Int,
    subject: String, onSubject: (String) -> Unit, subjects: List<String>,
    paper: String, onPaper: (String) -> Unit, papers: List<String>,
    category: String, onCategory: (String) -> Unit, categories: List<String>,
    onClear: () -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                query, onQuery, Modifier.fillMaxWidth(),
                placeholder = { Text("Search questions, subjects, categories…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
                singleLine = true, shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChipWithCount("All", all, filter == "All", { onFilter("All") })
                FilterChipWithCount("Due", dueCount, filter == "Due", { onFilter("Due") })
                FilterChipWithCount("Upcoming", upcomingCount, filter == "Upcoming", { onFilter("Upcoming") })
                FilterChipWithCount("Suspended", suspendedCount, filter == "Suspended", { onFilter("Suspended") })
            }
            if (subjects.isNotEmpty()) {
                Text("Subject", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(subject.isBlank(), { onSubject("") }, { Text("All subjects") })
                    subjects.forEach { s -> FilterChip(subject == s, { onSubject(if (subject == s) "" else s) }, { Text(s) }) }
                }
            }
            if (papers.isNotEmpty()) {
                Text("Paper", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(paper.isBlank(), { onPaper("") }, { Text("All papers") })
                    papers.forEach { p -> FilterChip(paper == p, { onPaper(if (paper == p) "" else p) }, { Text(p) }) }
                }
            }
            if (categories.isNotEmpty()) {
                Text("Category", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(category.isBlank(), { onCategory("") }, { Text("All categories") })
                    categories.forEach { c ->
                        FilterChip(category == c, { onCategory(if (category == c) "" else c) }, { Text(c.ifBlank { "Uncategorised" }) })
                    }
                }
            }
            if (query.isNotBlank() || filter != "All" || subject.isNotBlank() || paper.isNotBlank() || category.isNotBlank()) {
                TextButton(onClear, Modifier.align(Alignment.End)) { Text("Clear filters") }
            }
        }
    }
}

@Composable
private fun FilterChipWithCount(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, { Text("$label · $count") })
}

@Composable
private fun StatusPill(text: String, due: Boolean, suspended: Boolean) {
    val container = when {
        suspended -> MaterialTheme.colorScheme.surfaceContainerHigh
        due -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        suspended -> MaterialTheme.colorScheme.onSurfaceVariant
        due -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MistakeCard(
    mistake: ExamTrackMistake, context: ExamContext?, schedule: MistakeSchedule?,
    due: Boolean, attachmentCount: Int, attemptCount: Int,
    onOpen: () -> Unit, onPractice: () -> Unit, working: Boolean,
) {
    ElevatedCard(onClick = onOpen, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    when {
                        mistake.suspended -> "Suspended"
                        due -> if (schedule != null) dueLabel(schedule.dueAt) else "Due"
                        else -> if (schedule != null) dueLabel(schedule.dueAt) else "Upcoming"
                    },
                    due = due && !mistake.suspended, suspended = mistake.suspended
                )
                listOfNotNull(
                    context?.subject?.takeIf { it.isNotBlank() },
                    context?.paper?.takeIf { it.isNotBlank() }
                ).takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                mistake.category.takeIf { it.isNotBlank() }?.let {
                    Text("· $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(mistake.question, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!mistake.questionText.isNullOrBlank()) {
                RichText(
                    mistake.questionText!!, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mistake.totalMarks != null && mistake.marksLost != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Grade, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("−${trimMark(mistake.marksLost)} / ${trimMark(mistake.totalMarks)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (attachmentCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Image, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$attachmentCount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (attemptCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.History, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$attemptCount ${if (attemptCount == 1) "try" else "tries"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (RichTextParser.containsMath(mistake.question + " " + (mistake.questionText ?: ""))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Functions, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Maths", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!mistake.suspended) {
                    FilledTonalButton({ onPractice() }, enabled = !working, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("Practise")
                    }
                }
            }
        }
    }
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
                if (hasCards) "No mistakes match" else "Nothing due right now",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                if (hasCards) "Try a different search or clear the filters to see the rest."
                else "New mistakes from ExamTrack will land here. Log one on the web and sync.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (hasCards) TextButton(onClear) { Text("Clear filters") }
        }
    }
}

@Composable
private fun PracticeNotebookCard(
    expanded: Boolean, onToggle: () -> Unit,
    titles: List<Pair<String, String>>, onOpen: (String) -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Saved handwriting on this device", style = MaterialTheme.typography.titleSmall)
                    Text("${titles.size} page${if (titles.size == 1) "" else "s"} · stays after sign-out", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onToggle) { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide handwriting" else "Show handwriting") }
            }
            if (expanded) {
                if (titles.isEmpty()) {
                    Text("No practice pages yet. Review a card and your workings are kept here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("These Folio pages stay on this device when you sign out. Open one to export a backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    titles.take(20).forEach { (id, title) ->
                        Surface(onClick = { onOpen(id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open $title", Modifier.size(16.dp))
                            }
                        }
                    }
                    if (titles.size > 20) Text("+ ${titles.size - 20} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
