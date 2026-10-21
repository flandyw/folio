@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.study

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.input.guardUiTouches
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioExpand
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import java.time.Instant
import java.time.ZoneId

/**
 * The editor's stopwatch is the Focal study focus, not a second clock: starting it records a
 * study session for the open notebook, and the session shows up in history and on other Focal
 * apps. It counts only while the pages are on screen, so leaving the editor or backgrounding
 * the app parks it with a visible reason, and a kill recovers it paused at the last checkpoint.
 */
@Composable
internal fun StudyStopwatchContent(note: Notebook?, examTimer: ExamTimerState, onOpenStudy: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    val entries = remember(state.entries, state.userId) { state.visibleEntries }
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
    var confirmDiscard by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(6_000); notice = null } }
    var subjectId by rememberSaveable(state.userId, note?.id) {
        mutableStateOf(note?.let { FocalSubjects.suggest(it, state.subjects) } ?: focalRecentSubject(entries, state.subjects))
    }
    val focus = state.visibleFocus
    val examRunning = examTimer.active && examTimer.startedAt != null
    val otherRunning = entries.any { it.active && !it.paused && it.id != focus?.sessionId }
    val blocked = when {
        examRunning -> "The exam timer is recording this sitting. Stop it to start regular study."
        otherRunning -> "Another study session is running, possibly on another Focal app. Pause or finish it first."
        else -> null
    }
    val zone = remember { ZoneId.systemDefault() }
    val dayStart = remember(now / 60_000L) { Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() }
    val today = remember(now / 60_000L, entries) { studyRoundedMinutes(focalStudyMillisBetween(entries, dayStart, now)) }
    val subjectName = { id: String? -> state.subjects.firstOrNull { it.id == id }?.name }
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)
    ) {
        FolioExpand(notice != null) { StudyNotice(notice.orEmpty()) { notice = null } }
        if (focus == null) {
            Text("Time your study here. It is saved as a Focal study session" + (note?.let { " for this notebook" } ?: "") +
                " and appears in your history and on your other Focal apps. It pauses by itself when you leave the pages.",
                color = scheme.onSurfaceVariant)
            Text("Subject", style = MaterialTheme.typography.labelLarge)
            FocalSubjectPicker(state.subjects, subjectId) { subjectId = it }
            Button({
                if (!manager.startFocus(note, subjectId, attended = true)) notice = "Couldn't start: another session is already running."
            },
                enabled = blocked == null && state.canStartFocus,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(subjectName(subjectId)?.let { "Start studying $it" } ?: "Start studying", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            val planned = remember(entries) {
                entries.filter { it.planned && !it.completed && it.kind == "study" }
                    .sortedBy { focalPlannedSlots(it).firstOrNull()?.startAt ?: Long.MAX_VALUE }
            }
            if (planned.isNotEmpty()) {
                Text("Planned study", style = MaterialTheme.typography.titleMedium)
                planned.take(3).forEach { entry ->
                    PlannedStudyCard(entry, state.subjects, scheme.surfaceContainerHighest, now,
                        enabled = blocked == null && state.canStartFocus && !(entry.synced && entry.planDraft != null),
                        onStart = {
                            if (!manager.startPlannedFocus(entry.id, note, attended = true)) notice = "That plan couldn't start. Refresh and try again."
                        })
                }
                if (planned.size > 3) TextButton(onOpenStudy) { Text("All ${planned.size} planned sessions") }
            } else if (state.userId == null) {
                Text("Connect Focal in Study sessions to start plans from the website.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val running = focus.resumedAt != null
            val elapsed = focus.elapsed(now)
            Surface(shape = FolioShapes.extraLarge,
                color = if (running) scheme.primaryContainer else scheme.tertiaryContainer,
                contentColor = if (running) scheme.onPrimaryContainer else scheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp24, horizontal = FolioSpacing.dp16),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    Text((focalFocusStatus(focus) ?: "Recording").uppercase(),
                        style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp, textAlign = TextAlign.Center)
                    Text(formatStudyClock(elapsed),
                        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                        modifier = Modifier.semantics { contentDescription = "Active study time, ${formatStudyMinutes(elapsed / 60_000L)}" })
                    Text(focus.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(subjectName(focus.subjectId), focus.notebookTitle)
                        .joinToString(" · ").takeIf { it.isNotBlank() }
                        ?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    (if (examRunning && !running) "Resume regular study after the exam timer stops." else focalFocusStatusDetail(focus))
                        ?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                    val plannedMillis = entries.firstOrNull { it.id == focus.sessionId }
                        ?.let { entry -> focalPlannedSlots(entry).sumOf { requireNotNull(it.endAt) - it.startAt } } ?: 0L
                    if (plannedMillis > 0L) Column(Modifier.fillMaxWidth().padding(top = FolioSpacing.dp4),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        PlanGoal(elapsed, plannedMillis)
                    }
                }
            }
            Button({ manager.toggleFocus() },
                enabled = running || (!examRunning && !otherRunning),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(if (running) "Pause" else "Resume")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                FilledTonalButton({
                    val entry = entries.firstOrNull { it.id == focus.sessionId }
                    val saved = "Saved ${formatStudyMinutes(studyRoundedMinutes(elapsed).coerceAtLeast(1L))}" +
                        (subjectName(focus.subjectId)?.let { " of $it" } ?: "")
                    manager.finishFocus(entry?.notes.orEmpty(), entry?.confidence)
                    notice = saved
                }, enabled = elapsed >= 1_000L,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp6))
                    Text("Finish & save")
                }
                OutlinedButton({ confirmDiscard = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error)) {
                    Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(FolioSpacing.dp6))
                    Text("Discard")
                }
            }
        }
        if (today > 0) Text("${formatStudyMinutes(today)} studied today", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            SyncIcon(state, 18.dp)
            Text(state.syncStatus, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onOpenStudy) { Text(if (focus == null) "Study sessions" else "Add notes & history") }
        }
        if (state.error != null && !state.syncing) Text(state.error!!, color = scheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, modifier = Modifier.guardUiTouches(),
        title = { Text("Discard this session?") },
        text = { Text("${focus?.let { formatStudyClock(it.elapsed(now)) } ?: "Its"} of recorded study will be removed from Folio and Focal. This cannot be undone.") },
        confirmButton = { TextButton({ manager.discardFocus(); confirmDiscard = false; notice = "Session discarded" }) { Text("Discard") } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep session") } })
}

/** The clock. Colour carries state (running / paused / idle) so it reads from across a desk. */
@Composable
internal fun TimerHero(
    mode: HeroMode, time: String, title: String, detail: String?, large: Boolean,
    statusOverride: String? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(when (mode) {
        HeroMode.RUNNING -> scheme.primaryContainer
        HeroMode.PAUSED -> scheme.tertiaryContainer
        HeroMode.IDLE -> scheme.surfaceContainerHighest
    }, label = "heroContainer")
    val content = when (mode) {
        HeroMode.RUNNING -> scheme.onPrimaryContainer
        HeroMode.PAUSED -> scheme.onTertiaryContainer
        HeroMode.IDLE -> scheme.onSurface
    }
    val status = statusOverride ?: when (mode) { HeroMode.RUNNING -> "Recording"; HeroMode.PAUSED -> "Paused"; HeroMode.IDLE -> "Ready" }
    // The recording dot breathes, so a running clock is obvious even when the seconds are out of view.
    val pulse = if (mode == HeroMode.RUNNING) rememberInfiniteTransition(label = "heroPulse").animateFloat(
        1f, .25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "heroDot").value else 1f
    Surface(shape = FolioShapes.panel, color = container, contentColor = content) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Box(Modifier.size(10.dp).alpha(pulse).background(if (mode == HeroMode.IDLE) content.copy(alpha = .4f) else content, CircleShape))
                Text(status, style = MaterialTheme.typography.labelLarge)
            }
            Text(time,
                style = (if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium)
                    .copy(fontFeatureSettings = "tnum"),
                maxLines = 1, softWrap = false,
                modifier = Modifier.alpha(if (mode == HeroMode.IDLE) .45f else 1f).semantics {
                    contentDescription = "Active study time, $time"
                })
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(FolioSpacing.dp4))
            actions()
        }
    }
}
