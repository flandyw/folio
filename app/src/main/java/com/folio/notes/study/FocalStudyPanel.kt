@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.exam.ExamTimerPhase
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.input.guardUiTouches
import com.folio.notes.model.Notebook
import com.folio.notes.ui.EmptyHint
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioExpand
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

@Composable
fun FocalStudyPanel(note: Notebook?, examTimer: ExamTimerState? = null, onDismiss: () -> Unit) {
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    FolioPanel("Study sessions", onDismiss) {
        FocalStudyContent(note, examTimer, onAccount = { accountOpen = true })
    }
    if (accountOpen) FocalAccountPanel({ accountOpen = false }, onMistakes = null, onStudy = null)
}

internal enum class HeroMode { IDLE, RUNNING, PAUSED }

/**
 * One set of controls for the dedicated destination and the editor's quick panel.
 *
 * Wide surfaces (landscape tablet, the landscape panel) split into a timer column and a
 * context column that scroll independently, so Pause/Save never move while history grows.
 * Narrow ones (portrait tablet, phone) stack the same blocks, timer first.
 */
@Composable
internal fun FocalStudyContent(
    note: Notebook?, examTimer: ExamTimerState? = null, dedicated: Boolean = false,
    notebooks: List<Notebook> = emptyList(), onAccount: () -> Unit = {},
) {
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    val entries = remember(state.entries, state.userId) { state.visibleEntries }
    val zone = remember { ZoneId.systemDefault() }
    var notebookId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedNote = if (dedicated) notebooks.firstOrNull { it.id == notebookId } else note
    var notebookMenu by remember { mutableStateOf(false) }
    // The notebook's subject when it has one, else whatever was studied last, so Start is usually one tap.
    var subjectId by rememberSaveable(state.userId, selectedNote?.id) {
        mutableStateOf(selectedNote?.let { FocalSubjects.suggest(it, state.subjects) } ?: focalRecentSubject(entries, state.subjects))
    }
    var notes by rememberSaveable(state.userId, state.visibleFocus?.sessionId) { mutableStateOf(entries.firstOrNull { it.id == state.visibleFocus?.sessionId }?.notes.orEmpty()) }
    var confidence by rememberSaveable(state.userId, state.visibleFocus?.sessionId) { mutableIntStateOf(entries.firstOrNull { it.id == state.visibleFocus?.sessionId }?.confidence ?: 0) }
    var discardId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteHistoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyLimit by rememberSaveable { mutableIntStateOf(20) }
    var manualLog by rememberSaveable { mutableStateOf(false) }
    var manualMinutes by rememberSaveable { mutableStateOf("") }
    var planEditorId by rememberSaveable(state.userId) { mutableStateOf<String?>(null) }
    var planSearch by rememberSaveable(state.userId) { mutableStateOf("") }
    var planScopeName by rememberSaveable(state.userId) { mutableStateOf(PlanScope.ALL.name) }
    val planScope = PlanScope.entries.firstOrNull { it.name == planScopeName } ?: PlanScope.ALL
    var planSubject by rememberSaveable(state.userId) { mutableStateOf<String?>(null) }
    var plannedLimit by rememberSaveable(state.userId) { mutableIntStateOf(5) }
    var showAllShared by rememberSaveable { mutableStateOf(false) }
    var bulkAction by remember { mutableStateOf<String?>(null) }
    var subjectMenu by remember { mutableStateOf(false) }
    // A short confirmation after saving or deleting, so a finished session never just vanishes.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(6_000); notice = null } }
    // A history row asks the timeline to show its day.
    var timelineDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(manager.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, manager) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            manager.retry()
            while (true) {
                now = manager.now()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }
    val focus = state.visibleFocus
    // Study timed on another Focal app takes the timer's place while this device times nothing.
    val remoteMain = if (focus == null) entries.filter { it.active && it.kind == "study" }
        .sortedWith(compareBy({ it.paused }, { -it.endedAt })).firstOrNull() else null
    // The study page is often propped up on a desk: keep the screen awake while its clock runs.
    KeepScreenOn(dedicated && (focus?.resumedAt != null || remoteMain?.paused == false))
    val examRecording = examTimer?.takeIf { it.active && it.startedAt != null }
    val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dayStart = remember(todayDate) { todayDate.atStartOfDay(zone).toInstant().toEpochMilli() }
    val dayEnd = remember(todayDate) { todayDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val today = remember(now / 60_000L, entries) { studyRoundedMinutes(focalStudyMillisBetween(entries, dayStart, now)) }
    val week = remember(now / 60_000L, entries) { studyRoundedMinutes(focalWeekMillis(entries, now, zone)) }
    val streak = remember(entries, todayDate, today > 0) { focalStudyStreak(entries, now, zone) }
    val history = remember(entries) {
        entries.filter { it.completed && !focalIsCalendarPlaceholder(it) }.sortedByDescending { it.endedAt }
    }
    val planned = remember(entries) {
        entries.filter { it.planned && !it.completed && it.kind == "study" }
            .sortedBy { focalPlannedSlots(it).firstOrNull()?.startAt ?: Long.MAX_VALUE }
    }
    val plannedSlots = remember(planned) { planned.associate { it.id to focalPlannedSlots(it) } }
    val scopeCounts = remember(planned, now / 60_000L) {
        PlanScope.entries.associateWith { scope -> planned.count { focalPlanInScope(plannedSlots[it.id].orEmpty(), scope, now, dayStart, dayEnd) } }
    }
    val visiblePlans = remember(planned, state.subjects, planSearch, planScope, planSubject, now / 60_000L) {
        planned.filter { entry ->
            val details = focalPlanDetails(entry)
            (planSubject == null || entry.subjectId == planSubject) &&
                focalPlanMatchesQuery(planSearch, entry.title, state.subjects.firstOrNull { it.id == entry.subjectId }?.name.orEmpty(),
                    details.first, details.second.joinToString(" ")) &&
                focalPlanInScope(plannedSlots[entry.id].orEmpty(), planScope, now, dayStart, dayEnd)
        }
    }
    val filtering = planSearch.isNotBlank() || planScope != PlanScope.ALL || planSubject != null
    val historyDays = remember(history, historyLimit, dedicated) { focalHistoryDays(history.take(if (dedicated) historyLimit else 5), zone) }
    val cardColor = if (dedicated) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHighest
    val anotherRunning = entries.any { it.active && !it.paused }
    val startBlocked = when {
        examRecording != null -> "Stop the exam timer to start regular study."
        anotherRunning -> "Pause or finish your running session before starting another."
        else -> null
    }
    val canStart = startBlocked == null && state.canStartFocus
    // The study finished last today can be picked up again: the same session, its time kept.
    val continuable = remember(entries, todayDate) { entries.filter { focalCanContinue(it, now) }.maxByOrNull { it.endedAt } }
    val subjectName = { id: String? -> state.subjects.firstOrNull { it.id == id }?.name }
    fun startPlan(id: String) {
        manualLog = false
        if (!manager.startPlannedFocus(id, selectedNote)) notice = "That plan couldn't start. Refresh and try again."
    }
    fun startStudy(subject: String?) {
        manualLog = false
        if (!manager.startFocus(selectedNote, subject)) notice = "Couldn't start: another session is already running."
    }
    // Where the timeline sits in the context list (−1 when it spans the page above it), for "show on timeline".
    var timelineIndex by remember { mutableIntStateOf(-1) }
    fun showOnTimeline(entry: FocalStudyEntry) {
        timelineDay = Instant.ofEpochMilli(entry.startedAt).atZone(zone).toLocalDate().toEpochDay()
        if (timelineIndex >= 0) scope.launch { listState.animateScrollToItem(timelineIndex) }
    }

    // ── Timer column: exam notice, the clock with its controls, and the wrap-up form. ──
    val timerBlock: @Composable ColumnScope.() -> Unit = {
        if (examRecording != null) {
            val phase = when {
                examRecording.paused -> "Paused"
                examRecording.phase == ExamTimerPhase.READING -> "Reading"
                else -> "Writing"
            }
            Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Text("Exam recording · $phase", style = MaterialTheme.typography.titleSmall)
                    Text("Reading and writing time are being saved to Focal as this exam runs. Pauses are excluded.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        FolioExpand(notice != null) { StudyNotice(notice.orEmpty()) { notice = null } }
        if (remoteMain != null) {
            val running = !remoteMain.paused
            TimerHero(if (running) HeroMode.RUNNING else HeroMode.PAUSED, formatStudyClock(focalEntryElapsed(remoteMain, now)),
                remoteMain.title, listOfNotNull(subjectName(remoteMain.subjectId), "Timed on another device").joinToString(" · "), dedicated) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically) {
                    Button({ manager.controlEntry(remoteMain.id, if (running) "pause" else "resume") },
                        enabled = running || examRecording == null,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text(if (running) "Pause" else "Resume")
                    }
                    OutlinedButton({
                        manager.controlEntry(remoteMain.id, "finish")
                        notice = "Saved" + (subjectName(remoteMain.subjectId)?.let { " $it" } ?: "") + " session"
                    }, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text("Finish")
                    }
                    OutlinedButton({ discardId = remoteMain.id }, shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(Icons.Rounded.Close, "Discard", Modifier.size(18.dp))
                    }
                }
                if (examRecording != null && !running) Text("Resume regular study after the exam timer stops.", style = MaterialTheme.typography.bodySmall)
                Text(state.syncStatus, style = MaterialTheme.typography.labelSmall)
            }
        } else if (focus == null) {
            TimerHero(HeroMode.IDLE, formatStudyClock(0L), "Ready when you are",
                if (today > 0) "${formatStudyMinutes(today)} studied today" else null, dedicated) {
                Text("What are you studying?", style = MaterialTheme.typography.labelLarge)
                FocalSubjectPicker(state.subjects, subjectId) { subjectId = it }
                if (dedicated) Box {
                    OutlinedButton({ notebookMenu = true }, modifier = Modifier.fillMaxWidth(), shape = FolioShapes.large) {
                        Icon(Icons.Rounded.Book, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp10))
                        Text(selectedNote?.title ?: "No notebook", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    FolioMenuPopover(notebookMenu, { notebookMenu = false }, title = "Session notebook") {
                        FolioMenuItem({ Text("No notebook") }, { notebookId = null; notebookMenu = false }, selected = notebookId == null)
                        notebooks.sortedBy { it.title.lowercase() }.forEach { item ->
                            FolioMenuItem({ Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                { notebookId = item.id; notebookMenu = false }, selected = notebookId == item.id)
                        }
                    }
                }
                Button({ startStudy(subjectId) }, enabled = canStart,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(FolioSpacing.dp8))
                    Text(subjectName(subjectId)?.let { "Start $it" } ?: "Start study", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                continuable?.let { last ->
                    OutlinedButton({
                        manualLog = false
                        if (!manager.continueFocus(last.id)) notice = "Couldn't continue that session. Refresh and try again."
                    }, enabled = canStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(FolioSpacing.dp8))
                        Text("Continue ${subjectName(last.subjectId) ?: last.title} · ${formatStudyMinutes(studyRoundedMinutes(last.activeMillis))}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                startBlocked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton({ manualLog = !manualLog }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (manualLog) "Cancel manual entry" else "Log past study without the timer")
                }
            }
            FolioExpand(manualLog) {
                Surface(shape = FolioShapes.extraLarge, color = cardColor) {
                    Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        val minutes = manualMinutes.toIntOrNull()
                        val valid = minutes != null && minutes in 1..1_440
                        Text("Log past study", style = MaterialTheme.typography.titleMedium)
                        Text("Saved as finished study ending now" + (subjectName(subjectId)?.let { ", under $it." } ?: ", without a subject.") +
                            if (dedicated) " To place it earlier in the day, drag across the timeline instead." else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        fun savePastStudy() {
                            val logged = manualMinutes.toIntOrNull()?.takeIf { it in 1..1_440 } ?: return
                            notice = if (manager.logManual(selectedNote, subjectId, logged, notes, confidence.takeIf { it > 0 }))
                                "Logged ${formatStudyMinutes(logged.toLong())}" + (subjectName(subjectId)?.let { " of $it" } ?: "")
                            else "Couldn't save on this device. Check free storage and try again."
                            manualMinutes = ""; notes = ""; confidence = 0; manualLog = false
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            listOf(15, 30, 45, 60, 90).forEach { preset ->
                                FilterChip(minutes == preset, { manualMinutes = "$preset" }, { Text(formatStudyMinutes(preset.toLong())) })
                            }
                        }
                        OutlinedTextField(manualMinutes, { manualMinutes = it.filter(Char::isDigit).take(4) },
                            Modifier.fillMaxWidth(), label = { Text("Minutes studied") }, singleLine = true,
                            isError = minutes != null && !valid,
                            supportingText = { Text(if (minutes != null && !valid) "Enter 1 to 1,440 minutes (up to a day)."
                                else minutes?.let { formatStudyMinutes(it.toLong()) } ?: "Or type any length, up to a day.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { savePastStudy() }))
                        ReflectionFields(notes, { notes = it }, confidence, { confidence = it })
                        Button(::savePastStudy, enabled = valid,
                            shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                            Text(if (valid) "Save ${formatStudyMinutes(requireNotNull(minutes).toLong())} of past study" else "Save past study")
                        }
                    }
                }
            }
        } else {
            val running = focus.resumedAt != null
            val elapsed = focus.elapsed(now)
            val started = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(focus.startedAt))
            val detail = listOfNotNull(subjectName(focus.subjectId), focus.notebookTitle, "Started $started").joinToString(" · ")
            TimerHero(
                if (running) HeroMode.RUNNING else HeroMode.PAUSED, formatStudyClock(elapsed),
                focus.title, detail, dedicated,
                statusOverride = focalFocusStatus(focus),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        { manager.toggleFocus() },
                        enabled = running || (examRecording == null && entries.none { it.active && !it.paused && it.id != focus.sessionId }),
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text(if (running) "Pause" else "Resume")
                    }
                    OutlinedButton(
                        { discardId = focus.sessionId },
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp8))
                        Text("Discard")
                    }
                }
                if (examRecording != null && !running) {
                    Text("Resume regular study after the exam timer stops.", style = MaterialTheme.typography.bodySmall)
                } else if (!running && entries.any { it.active && !it.paused && it.id != focus.sessionId }) {
                    Text("Another session is running, possibly on another Focal app. Pause it to resume this one.", style = MaterialTheme.typography.bodySmall)
                } else focalFocusStatusDetail(focus)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val plannedMillis = entries.firstOrNull { it.id == focus.sessionId }?.let { entry ->
                    focalPlannedSlots(entry).sumOf { requireNotNull(it.endAt) - it.startAt }
                } ?: 0L
                if (plannedMillis > 0L) PlanGoal(elapsed, plannedMillis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.syncStatus, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Box {
                        TextButton({ subjectMenu = true }) { Text("Change subject") }
                        FolioMenuPopover(subjectMenu, { subjectMenu = false }, title = "Subject for this session") {
                            FolioMenuItem({ Text("No subject") }, {
                                subjectMenu = false; manager.changeFocusSubject(null)
                            }, selected = focus.subjectId == null)
                            state.subjects.forEach { subject ->
                                FolioMenuItem({ Text(subject.name) }, {
                                    subjectMenu = false
                                    if (!manager.changeFocusSubject(subject.id)) notice = "Couldn't change the subject. Try again."
                                }, selected = focus.subjectId == subject.id)
                            }
                        }
                    }
                }
            }
            Surface(shape = FolioShapes.extraLarge, color = cardColor) {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    Text("Wrap up", style = MaterialTheme.typography.titleMedium)
                    ReflectionFields(notes, { notes = it }, confidence, { confidence = it })
                    val minutes = studyRoundedMinutes(elapsed)
                    Button({
                        val saved = "Saved ${formatStudyMinutes(minutes.coerceAtLeast(1L))}" + (subjectName(focus.subjectId)?.let { " of $it" } ?: "")
                        manager.finishFocus(notes, confidence.takeIf { it > 0 }); notes = ""; confidence = 0
                        notice = saved
                    },
                        enabled = elapsed >= 1_000L,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(FolioSpacing.dp8))
                        Text(if (minutes >= 1L) "Save ${formatStudyMinutes(minutes)}" else "Save session")
                    }
                }
            }
        }
    }

    val timeline: @Composable (Boolean) -> Unit = { compact ->
        StudyTimeline(state, now, canStart = canStart, cardColor = cardColor,
            onStartPlanned = ::startPlan,
            onStartFocus = ::startStudy,
            onEditPlan = { planEditorId = it }, compact = compact,
            requestedDay = timelineDay, onDayShown = { timelineDay = null })
    }
    // ── Context column: the day's timeline (when it isn't spanning the page), today's total + sync, other apps' sessions, history. ──
    val contextItems: LazyListScope.(Boolean) -> Unit = { withTimeline ->
        if (dedicated && withTimeline) item(key = "timeline") { timeline(false) }
        item(key = "today") {
            Surface(shape = FolioShapes.extraLarge, color = cardColor) {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        Icon(Icons.Rounded.School, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Studied today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatStudyMinutes(today), style = MaterialTheme.typography.headlineMedium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("This week", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatStudyMinutes(week), style = MaterialTheme.typography.titleMedium)
                            if (streak > 1) Text("$streak-day streak", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("Active study only — pauses, exams and calendar blocks are excluded.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        SyncIcon(state, 20.dp)
                        Text(state.syncStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (state.needsSignIn) {
                            TextButton(onAccount) { Text("Sign in again") }
                        } else if ((state.userId != null && (state.pendingCount > 0 || state.error != null || state.syncing)) ||
                            state.localSaveFailed) {
                            TextButton({ manager.retry() }, enabled = !state.syncing) {
                                Text(if (state.syncing) "Retrying…" else "Retry")
                            }
                        } else if (state.userId == null) {
                            TextButton(onAccount) { Text("Connect Focal") }
                        }
                    }
                    if (state.error != null && !state.syncing) Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val sharedActive = entries.filter { entry ->
            entry.active && entry.id != focus?.sessionId && entry.id != remoteMain?.id &&
                !(entry.kind == "exam" && examRecording?.startedAt == entry.startedAt)
        }
        if (sharedActive.isNotEmpty()) item(key = "shared") {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Active across your apps", style = MaterialTheme.typography.titleMedium)
                val pausedCount = sharedActive.count { it.paused }
                if (pausedCount > 1) {
                    Text("$pausedCount paused sessions can be resolved together.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        TextButton({ bulkAction = "finish" }) { Text("Finish paused") }
                        TextButton({ bulkAction = "discard" }) { Text("Discard paused") }
                    }
                }
                sharedActive.take(if (showAllShared) Int.MAX_VALUE else 6).forEach { entry ->
                    val elapsed = focalEntryElapsed(entry, now)
                    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(if (entry.kind == "exam") "Timed exam" else "Study", focalSubjectNames(entry, state.subjects),
                                if (entry.paused) "Paused" else "In progress", formatStudyClock(elapsed)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                OutlinedButton({ manager.controlEntry(entry.id, if (entry.paused) "resume" else "pause") },
                                    enabled = !entry.paused || (examRecording == null && entries.none { it.active && !it.paused && it.id != entry.id }),
                                    shapes = ButtonDefaults.shapes()) {
                                    Icon(if (entry.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                                    Spacer(Modifier.width(FolioSpacing.dp6)); Text(if (entry.paused) "Resume" else "Pause")
                                }
                                TextButton({ manager.controlEntry(entry.id, "finish") }) { Text("Finish") }
                                TextButton({ discardId = entry.id }) { Text("Discard") }
                            }
                        }
                    }
                }
                if (sharedActive.size > 6) {
                    TextButton({ showAllShared = !showAllShared }) {
                        Text(if (showAllShared) "Show fewer" else "Show all ${sharedActive.size} sessions")
                    }
                }
            }
        }
        item(key = "planned-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Planned study${if (planned.isNotEmpty()) " · ${planned.size}" else ""}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (state.userId != null && state.configured) TextButton({ manager.retry() }, enabled = !state.syncing) {
                        Text(if (state.syncing) "Refreshing…" else "Refresh")
                    }
                    FilledTonalButton({ planEditorId = "new:${java.util.UUID.randomUUID()}" }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp6)); Text("Plan")
                    }
                }
                Text(when {
                    state.userId == null && planned.isEmpty() -> "Plan study here, or connect Focal to start sessions planned on the website."
                    planned.isEmpty() -> "Nothing planned. Plan study here or on Focal and it will appear here."
                    focus != null -> "Finish or discard your current session before starting a plan."
                    examRecording != null -> "Stop the exam timer before starting a plan."
                    anotherRunning -> "Pause or finish your running session before starting a plan."
                    else -> "Start whenever you’re ready. Only time spent studying is recorded."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Filters only earn their space once there is a list to sift.
                if (planned.size > 3 || filtering) {
                    OutlinedTextField(planSearch, { planSearch = it; plannedLimit = 5 }, Modifier.fillMaxWidth(),
                        label = { Text("Search title, subject or topics") }, singleLine = true,
                        trailingIcon = if (planSearch.isNotEmpty()) { { IconButton({ planSearch = "" }) { Icon(Icons.Rounded.Close, "Clear search") } } } else null)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        PlanScope.entries.forEach { option ->
                            val count = scopeCounts[option] ?: 0
                            FilterChip(planScope == option, { planScopeName = option.name; plannedLimit = 5 },
                                label = { Text(if (option == PlanScope.ALL) option.label else "${option.label} · $count") },
                                enabled = option == PlanScope.ALL || count > 0 || planScope == option)
                        }
                    }
                    FocalSubjectPicker(state.subjects, planSubject, emptyLabel = "All subjects") { planSubject = it; plannedLimit = 5 }
                    if (visiblePlans.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("No plans match these filters.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ planSearch = ""; planScopeName = PlanScope.ALL.name; planSubject = null }) { Text("Clear filters") }
                    }
                }
            }
        }
        items(visiblePlans.take(plannedLimit), key = { "planned:${it.id}" }) { entry ->
            PlannedStudyCard(entry, state.subjects, cardColor, now,
                enabled = canStart && !(entry.synced && entry.planDraft != null),
                onStart = { startPlan(entry.id) },
                onEdit = { planEditorId = entry.id })
        }

        if (visiblePlans.size > plannedLimit) item(key = "more-planned") {
            TextButton({ plannedLimit += 10 }) { Text("Show ${minOf(10, visiblePlans.size - plannedLimit)} more of ${visiblePlans.size - plannedLimit}") }
        }
        item(key = "history-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                Text(if (dedicated) "Session history" else "Recent sessions", style = MaterialTheme.typography.titleMedium)
                if (dedicated && history.isNotEmpty()) Text("Tap a session to see it on the timeline.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (history.isEmpty()) item(key = "empty-history") {
            EmptyHint("No completed sessions yet. Your saved study will appear here.")
        }
        historyDays.forEach { day ->
            item(key = "day:${day.date}") {
                Row(Modifier.fillMaxWidth().padding(top = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                    Text(studyDayLabel(day.date, todayDate), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (day.studyMinutes > 0) Text(formatStudyMinutes(day.studyMinutes), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(day.entries, key = { it.id }) { entry ->
                HistoryCard(entry, focalSubjectNames(entry, state.subjects), cardColor, dedicated,
                    onShow = if (dedicated && entry.kind == "study") { { showOnTimeline(entry) } } else null,
                    onDelete = if (dedicated && entry.kind == "study") { { deleteHistoryId = entry.id } } else null)
            }
        }
        if (dedicated && history.size > historyLimit) item(key = "more-history") {
            TextButton({ historyLimit += 20 }) { Text("Show more sessions") }
        }
    }

    // The dedicated destination sits under a FolioScreenHeading, which already leaves the shared
    // gap below itself; the editor's quick panel has no heading, so it keeps its own.
    val top = if (dedicated) 0.dp else FolioSpacing.dp8
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 840.dp) {
            // Landscape tablet: a time strip wants width, so the timeline spans both columns. It is
            // capped at under half the height (scrolling inside) so the clock and history keep theirs.
            val spanning = dedicated && constraints.hasBoundedHeight
            SideEffect { timelineIndex = if (spanning || !dedicated) -1 else 0 }
            val timelineMax = maxHeight * 0.45f
            val columnsTop = if (spanning) FolioSpacing.dp12 else top
            Column(Modifier.fillMaxWidth().then(if (dedicated) Modifier.fillMaxHeight() else Modifier).imePadding()) {
                if (spanning) Box(Modifier.fillMaxWidth().heightIn(max = timelineMax).verticalScroll(rememberScrollState())
                    .padding(horizontal = FolioDestinationInset)) { timeline(true) }
                Row(Modifier.fillMaxWidth().then(if (dedicated) Modifier.weight(1f) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(FolioDestinationInset)) {
                    Column(Modifier.weight(5f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = FolioDestinationInset, top = columnsTop, bottom = FolioSpacing.dp16),
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), content = timerBlock)
                    LazyColumn(Modifier.weight(6f).fillMaxHeight(), state = listState,
                        contentPadding = PaddingValues(end = FolioDestinationInset, top = columnsTop, bottom = FolioSpacing.dp16),
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) { contextItems(!spanning) }
                }
            }
        } else {
            SideEffect { timelineIndex = if (dedicated) 1 else -1 }
            LazyColumn((if (dedicated) Modifier.fillMaxWidth() else Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.TopCenter)).imePadding(),
                state = listState,
                contentPadding = PaddingValues(start = FolioDestinationInset, end = FolioDestinationInset, top = top, bottom = FolioSpacing.dp16),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                item(key = "timer") {
                    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), content = timerBlock)
                }
                contextItems(true)
            }
        }
    }
    planEditorId?.let { id ->
        // Only an unstarted plan is editable; one started or finished meanwhile closes the editor below.
        val existing = entries.firstOrNull { it.id == id && it.planned && !it.completed }
        val copySource = if (id.startsWith("copy:")) entries.firstOrNull { it.id == id.split(":")[1] } else null
        val original = existing ?: copySource?.copy(id = id.substringAfterLast(":"), title = copySource.title + " (copy)",
            revision = 0L, remotePayload = null, planDraft = copySource.planDraft ?: copySource.remotePayload,
            synced = false, changeId = java.util.UUID.randomUUID().toString()) ?: if (id.startsWith("new:")) FocalStudyEntry(id = id.removePrefix("new:"),
            notebookId = selectedNote?.id, notebookTitle = selectedNote?.title, title = "", subjectId = subjectId,
            kind = "study", startedAt = now, endedAt = now, activeMillis = 0L, completed = false, planned = true) else null
        if (original != null) FocalPlanEditor(original, state.subjects, now,
            onDismiss = { planEditorId = null },
            onSave = { title, subject, description, topics, slots ->
                manager.savePlan(original, title, subject, description, topics, slots).also { ok ->
                    if (ok) notice = if (existing != null) "Plan saved" else "Planned ${title.trim().ifBlank { "study" }}"
                }
            },
            onDelete = existing?.let { { manager.deletePlan(it.id); notice = "Plan deleted" } },
            onDuplicate = existing?.let { { planEditorId = "copy:${it.id}:${java.util.UUID.randomUUID()}" } })
        // A plan started, finished or deleted elsewhere closes its editor rather than saving over it.
        else LaunchedEffect(id) { planEditorId = null }
    }
    discardId?.let { id ->
        val target = entries.firstOrNull { it.id == id }
        val elapsed = when {
            id == focus?.sessionId -> focus.elapsed(now)
            target != null -> focalEntryElapsed(target, now)
            else -> 0L
        }
        AlertDialog(onDismissRequest = { discardId = null }, modifier = Modifier.guardUiTouches(),
            title = { Text("Discard this session?") },
            text = { Text("${formatStudyClock(elapsed)} of recorded study will be removed from Folio and Focal. This cannot be undone.") },
            confirmButton = { TextButton({ manager.controlEntry(id, "discard"); discardId = null; notice = "Session discarded" }) { Text("Discard") } },
            dismissButton = { TextButton({ discardId = null }) { Text("Keep session") } })
    }
    deleteHistoryId?.let { id ->
        val target = history.firstOrNull { it.id == id }
        if (target == null) LaunchedEffect(id) { deleteHistoryId = null }
        else AlertDialog(onDismissRequest = { deleteHistoryId = null }, modifier = Modifier.guardUiTouches(),
            title = { Text("Delete this session?") },
            text = { Text("${formatStudyMinutes(focalHistoryMinutes(target))} of ${subjectName(target.subjectId) ?: "study"} " +
                "(${focalSlotLabel(FocalStudyInterval(target.startedAt, target.endedAt), zone)}) " +
                "will be removed from Folio and Focal. This cannot be undone.") },
            confirmButton = { TextButton({
                notice = if (manager.deleteSession(id)) "Session deleted" else "Couldn't delete that session."
                deleteHistoryId = null
            }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteHistoryId = null }) { Text("Keep session") } })
    }
    bulkAction?.let { action ->
        val ids = entries.filter { it.active && it.paused && it.id != focus?.sessionId &&
            !(it.kind == "exam" && examRecording?.startedAt == it.startedAt) }.map { it.id }
        AlertDialog(onDismissRequest = { bulkAction = null }, modifier = Modifier.guardUiTouches(),
            title = { Text(if (action == "discard") "Discard ${ids.size} paused sessions?" else "Finish ${ids.size} paused sessions?") },
            text = { Text(if (action == "discard") "These sessions will be removed from Folio and Focal. This cannot be undone."
                else "These sessions will be saved as completed study with the time they recorded.") },
            confirmButton = { TextButton({ manager.controlPausedEntries(ids, action); bulkAction = null }) {
                Text(if (action == "discard") "Discard sessions" else "Finish sessions")
            } },
            dismissButton = { TextButton({ bulkAction = null }) { Text("Cancel") } })
    }
}

/** Holds the screen awake while [on]; released as soon as it turns off or the caller leaves. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        if (on) view.keepScreenOn = true
        onDispose { if (on) view.keepScreenOn = false }
    }
}

@Composable
internal fun SyncIcon(state: FocalStudyState, size: androidx.compose.ui.unit.Dp) {
    Icon(when {
        state.syncing -> Icons.Rounded.Sync
        state.error != null -> Icons.Rounded.ErrorOutline
        state.userId == null || !state.configured || state.pendingCount > 0 -> Icons.Rounded.CloudOff
        else -> Icons.Rounded.CloudDone
    }, null, Modifier.size(size), tint = if (state.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current)
}

/** A short-lived confirmation ("Saved 25 min of Maths") that can also be dismissed. */
@Composable
internal fun StudyNotice(text: String, onDismiss: () -> Unit) {
    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = FolioSpacing.dp16, end = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(FolioSpacing.dp10))
            Text(text, Modifier.weight(1f).padding(vertical = FolioSpacing.dp12), style = MaterialTheme.typography.bodyMedium)
            IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(18.dp)) }
        }
    }
}

/** Elapsed study against a plan's scheduled length; reaching it never finishes the session. */
@Composable
internal fun PlanGoal(elapsed: Long, plannedMillis: Long) {
    val reached = elapsed >= plannedMillis
    Text("${formatStudyMinutes(elapsed / 60_000L)} of ${formatStudyMinutes(studyRoundedMinutes(plannedMillis))} planned" +
        if (reached) " · Goal reached" else " · ${formatStudyMinutes(studyRoundedMinutes(plannedMillis - elapsed).coerceAtLeast(1L))} to go",
        style = MaterialTheme.typography.bodySmall)
    LinearProgressIndicator(progress = { (elapsed.toFloat() / plannedMillis).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun HistoryCard(entry: FocalStudyEntry, subject: String?, color: Color, dedicated: Boolean,
                        onShow: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    Surface(shape = FolioShapes.large, color = color, onClick = onShow ?: {}, enabled = onShow != null) {
        Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp16, top = FolioSpacing.dp12, bottom = FolioSpacing.dp12,
            end = if (onDelete != null) FolioSpacing.dp4 else FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Box(Modifier.size(10.dp).background(FocalSubjects.color(entry.subjectId)?.let { Color(it) }
                ?: MaterialTheme.colorScheme.outline, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOf(if (entry.kind == "exam") "Exam" else null, subject ?: "No subject",
                    entry.notebookTitle, "${time.format(Date(entry.startedAt))} – ${time.format(Date(entry.endedAt))}")
                    .filterNot { it.isNullOrBlank() }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dedicated) {
                    if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    entry.confidence?.let { Text("Confidence $it/5 · ${CONFIDENCE_LABELS[it - 1]}", style = MaterialTheme.typography.labelSmall) }
                    if (!entry.synced) Text(if (entry.userId == null) "Saved on this device" else "Saved locally · awaiting sync",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(formatStudyMinutes(focalHistoryMinutes(entry)), style = MaterialTheme.typography.titleMedium)
            onDelete?.let { IconButton(it) { Icon(Icons.Rounded.DeleteOutline, "Delete session", tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
internal fun PlannedStudyCard(entry: FocalStudyEntry, subjects: List<FocalSubject>, color: Color, now: Long,
                             enabled: Boolean, onStart: () -> Unit, onEdit: (() -> Unit)? = null) {
    val slots = remember(entry.remotePayload, entry.planDraft, entry.pendingBlocks) { focalPlannedSlots(entry) }
    val details = remember(entry.remotePayload, entry.planDraft) { focalPlanDetails(entry) }
    val zone = remember { ZoneId.systemDefault() }
    val scheme = MaterialTheme.colorScheme
    val conflicted = entry.synced && entry.planDraft != null
    val whenLabel = focalPlanWhen(slots, now, zone)
    val overdue = focalPlanOverdue(slots, now)
    val current = slots.any { it.startAt <= now && (it.endAt ?: it.startAt) > now }
    Surface(shape = FolioShapes.large, color = color) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                    Text(entry.title.ifBlank { "Untitled plan" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(focalSubjectNames(entry, subjects),
                        slots.takeIf { it.isNotEmpty() }?.let { formatStudyMinutes(studyRoundedMinutes(it.sumOf { s -> requireNotNull(s.endAt) - s.startAt })) },
                        if (slots.size > 1) "${slots.size} blocks" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                whenLabel?.let { label ->
                    Surface(shape = FolioShapes.small, color = when {
                        overdue -> scheme.errorContainer
                        current -> scheme.primaryContainer
                        else -> scheme.secondaryContainer
                    }) {
                        Text(label, Modifier.padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, color = when {
                                overdue -> scheme.onErrorContainer
                                current -> scheme.onPrimaryContainer
                                else -> scheme.onSecondaryContainer
                            })
                    }
                }
            }
            if (conflicted) Text("Draft needs review · edit and save to resolve changes from Focal.",
                color = scheme.error, style = MaterialTheme.typography.bodySmall)
            slots.take(3).forEach { slot ->
                Text(focalSlotLabel(slot, zone), style = MaterialTheme.typography.bodySmall)
            }
            if (slots.size > 3) Text("+${slots.size - 3} more blocks", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (details.first.isNotBlank()) Text(details.first, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (details.second.isNotEmpty()) Text("Topics: ${details.second.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                onEdit?.let { OutlinedButton(it, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (conflicted) "Review" else "Edit") } }
                Button(onStart, enabled = enabled, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Text("Start session")
                }
            }
        }
    }
}
