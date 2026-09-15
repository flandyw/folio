package com.folio.notes.mistakes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.decode.GifDecoder
import com.folio.notes.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MistakesScreen(model: MistakesViewModel, folio: FolioViewModel, folioState: FolioState,
    finger: Boolean, haptics: Boolean, shapes: Boolean, onBack: () -> Unit,
    onSettings: () -> Unit, onExport: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var activeReview by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var subject by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var showPractice by rememberSaveable { mutableStateOf(false) }
    val mistakes = state.cache.mistakes.values.toList()
    val due = MistakeScheduler.getDueMistakes(mistakes)
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
    LaunchedEffect(state.userId) { activeReview = null; detail = null; password = "" }
    fun start(mistake: ExamTrackMistake) {
        val user = state.userId ?: return
        if (working) return
        working = true
        scope.launch {
            try {
                val attempt = folio.createMistakePractice(user, mistake)
                model.addAttempt(attempt)
                activeReview = attempt.reviewId
                detail = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { localError = "Could not start a practice page. Please try again." }
            finally { working = false }
        }
    }
    fun leaveReview() { activeReview = null; folio.close() }
    BackHandler { if (activeReview != null) leaveReview() else if (detail != null) detail = null else onBack() }
    if (active != null && card != null && folioState.activeId == active.practiceNotebookId) {
        MistakeReviewScreen(card, state.cache.contexts[card.attemptId], active, model, folio, folioState,
            finger, haptics, shapes, working, localError, ::leaveReview, onSettings, onExport) { rating ->
            working = true
            scope.launch {
                try {
                    val completed = model.rate(active, rating)
                    folio.completeMistakePractice(completed)
                    leaveReview()
                    val next = MistakeScheduler.getDueMistakes(model.state.value.cache.mistakes.values.toList()).firstOrNull()
                    working = false
                    if (next != null) start(next)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { localError = "Could not save the review. Your page is kept; please retry." }
                finally { working = false }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onBack) { Text("Library") }
            Text("Mistakes", style = MaterialTheme.typography.headlineMedium)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("ExamTrack", style = MaterialTheme.typography.titleLarge)
                Text(state.email ?: "Not connected")
                Text(state.status)
                if (state.cache.pending.isNotEmpty()) Text("${state.cache.pending.size} mistake updates waiting to sync")
                state.cache.lastSyncedAt?.let { Text("Last synced: ${it.replace('T', ' ').take(16)} UTC") }
                (state.error ?: localError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.userId == null) {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button({ model.signIn(email, password); password = "" }, enabled = !state.busy && email.isNotBlank() && password.isNotEmpty()) { Text("Sign in") }
                } else Row {
                    TextButton({ model.requestSync(force = true) }) { Text("Sync now") }
                    TextButton({ leaveReview(); model.signOut() }) { Text("Sign out") }
                }
            }
            item {
                // Folio-owned work remains deliberately accessible on this device after sign-out.
                TextButton({ showPractice = !showPractice }) { Text("Saved handwriting on this device") }
                if (showPractice) {
                    Text("These Folio pages stay on this device when you sign out. Open a page to export a Folio backup.")
                    folioState.notes.filter { it.mistakePractice }.forEach { note ->
                        TextButton({ folio.open(note.id); onBack() }) { Text(note.title) }
                    }
                }
            }
            if (state.userId != null) {
                item {
                    Text("${due.size} due · ${mistakes.size} total", style = MaterialTheme.typography.titleLarge)
                    Button({ due.firstOrNull()?.let(::start) }, enabled = due.isNotEmpty() && !working) { Text("Review due") }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("All", "Due", "Upcoming", "Suspended").forEach { f ->
                            FilterChip(filter == f, { filter = f }, { Text(f) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        (listOf("") + state.cache.contexts.values.map { it.subject }.filter { it.isNotBlank() }.distinct().sorted()).forEach { s ->
                            FilterChip(subject == s, { subject = s }, { Text(s.ifBlank { "All subjects" }) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        (listOf("") + mistakes.map { it.category }.distinct().sorted()).forEach { c ->
                            FilterChip(category == c, { category = c }, { Text(c.ifBlank { "All categories" }) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                }
                val selected = mistakes.find { it.id == detail }
                if (selected != null) item {
                    TextButton({ detail = null }) { Text("All mistakes") }
                    QuestionContent(selected, state.cache.contexts[selected.attemptId], state.userId!!, model.attachments)
                    Button({ start(selected) }, enabled = !working && !selected.suspended) { Text("Practice") }
                    Text("Handwritten attempts", style = MaterialTheme.typography.titleMedium)
                    folioState.notes.flatMap { note -> note.mistakeReviews.map { note to it } }
                        .filter { (_, a) -> a.userId == state.userId && a.mistakeId == selected.id }.forEach { (note, a) ->
                            TextButton({
                                if (a.completedAt != null) {
                                    folio.openAt(note.id, note.pages.indexOfFirst { it.id == a.practicePageId }.coerceAtLeast(0)); onBack()
                                } else scope.launch {
                                    try {
                                        model.addAttempt(a)
                                        folio.openAt(note.id, note.pages.indexOfFirst { it.id == a.practicePageId }.coerceAtLeast(0))
                                        activeReview = a.reviewId
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { localError = "Could not resume this review. Your page is kept." }
                                }
                            }) {
                                Text("${a.completedAt?.take(10) ?: "Unfinished"} · ${a.rating ?: "Practice"} · ${if (a.completedAt == null) "Continue review" else "Open handwriting"}")
                            }
                        }
                } else items(mistakes.filter { m ->
                    (subject.isBlank() || state.cache.contexts[m.attemptId]?.subject == subject) && (category.isBlank() || category == m.category) &&
                        when (filter) { "Due" -> m in due; "Upcoming" -> !m.suspended && m !in due; "Suspended" -> m.suspended; else -> true }
                }.sortedBy { MistakeScheduler.getMistakeSchedule(it).dueAt }, key = { it.id }) { m ->
                    OutlinedCard(onClick = { detail = m.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(m.question, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(state.cache.contexts[m.attemptId]?.subject, m.category).joinToString(" · "))
                            Text(if (m.suspended) "Suspended" else if (m in due) "Due" else "Due ${MistakeScheduler.getMistakeSchedule(m).dueAt.take(10)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun QuestionContent(m: ExamTrackMistake, context: ExamContext?, user: String, attachments: MistakeAttachmentRepository) {
    context?.let { Text(listOf(it.subject, it.title, it.paper).filter(String::isNotBlank).joinToString(" · ")) }
    Text(m.question, style = MaterialTheme.typography.titleLarge)
    if (!m.questionText.isNullOrBlank()) Text(m.questionText)
    val androidContext = LocalContext.current
    val loader = remember { ImageLoader.Builder(androidContext).components {
        if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build() }
    m.attachments.forEach { attachment ->
        var file by remember(user, attachment.storagePath) { mutableStateOf<File?>(null) }
        var failed by remember(user, attachment.storagePath) { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }
        LaunchedEffect(user, attachment.storagePath, retry) {
            try { file = attachments.get(user, attachment); failed = false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        if (file != null) AsyncImage(file, attachment.name, imageLoader = loader, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
        else if (failed) TextButton({ retry++ }) { Text("${attachment.name} unavailable offline · Retry") }
        else Text("Loading ${attachment.name}…")
    }
}

@Composable private fun MistakeReviewScreen(m: ExamTrackMistake, context: ExamContext?, attempt: LocalMistakeReviewAttempt,
    model: MistakesViewModel, folio: FolioViewModel, state: FolioState, finger: Boolean, haptics: Boolean, shapes: Boolean,
    busy: Boolean, error: String?, onBack: () -> Unit, onSettings: () -> Unit, onExport: () -> Unit, onRate: (ReviewRating) -> Unit) {
    var revealed by rememberSaveable(attempt.reviewId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            TextButton(onBack) { Text("Back to mistakes · handwriting is saved") }
            QuestionContent(m, context, attempt.userId, model.attachments)
            if (revealed) {
                Text("Correction", style = MaterialTheme.typography.titleMedium); Text(m.correction)
                Text("Why this was wrong", style = MaterialTheme.typography.titleMedium); Text(m.explanation)
                Text("Category: ${m.category}")
                if (m.totalMarks != null && m.marksLost != null) Text("Marks lost: ${m.marksLost} / ${m.totalMarks}")
            }
        }
        Box(Modifier.weight(1f)) { EditorScreen(state, folio, finger, haptics, shapes, onSettings, onExport) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!revealed) Button({ revealed = true }, Modifier.fillMaxWidth().padding(8.dp)) { Text("Reveal answer") }
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ReviewRating.entries.forEach { rating ->
                Button({ onRate(rating) }, enabled = !busy && state.pendingSaves == 0 && !state.saveFailed, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(rating.wire.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}
