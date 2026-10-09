@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.mistakes

import com.folio.notes.FolioMenuPopover
import com.folio.notes.FolioMenuItem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.guardUiTouches
import com.folio.notes.longPressAction
import com.folio.notes.rememberLongPressGuard
import java.time.format.TextStyle
import java.util.Locale

/** How the due queue (and therefore the next session) is ordered. */
internal enum class TodayOrder(val label: String, val icon: ImageVector) {
    OLDEST("Oldest", Icons.Rounded.History),
    NEWEST("Newest", Icons.Rounded.Update),
    SHUFFLE("Shuffle", Icons.Rounded.Shuffle),
}

/** Session lengths offered on Today; [Int.MAX_VALUE] is "every due question". */
internal val TodaySessionSizes = listOf(5, 10, 20, Int.MAX_VALUE)

/** Everything Today shows. The due lists are already narrowed to [focus] and ordered. */
internal data class TodayContent(
    val stats: TodayStats,
    val totalCards: Int,
    val allDue: Int,
    val overdue: List<ExamTrackMistake>,
    val dueToday: List<ExamTrackMistake>,
    val resume: List<ExamTrackMistake>,
    val subjects: List<Pair<String, Int>>,
    val focus: String,
    val limit: Int,
    val order: TodayOrder,
    val completed: Int?,
    val pending: Int,
    val contexts: Map<String, ExamContext>,
    val schedules: Map<String, MistakeSchedule>,
    val attempts: Map<String, Int>,
    val working: Boolean,
    val now: Long,
) {
    val due get() = overdue.size + dueToday.size
    val sessionSize get() = minOf(due, limit)
}

internal class TodayActions(
    val onStart: () -> Unit,
    val onBrowse: () -> Unit,
    val onFocus: (String) -> Unit,
    val onLimit: (Int) -> Unit,
    val onOrder: (TodayOrder) -> Unit,
    val onDismissSummary: () -> Unit,
    val onOpen: (ExamTrackMistake) -> Unit,
    val onPractice: (ExamTrackMistake) -> Unit,
    val onDelete: (ExamTrackMistake) -> Unit,
)

/**
 * The Today destination. It answers three questions in order: what should I do now (the session
 * card with one primary action), what is waiting (the queue), and how am I doing (today, streak,
 * the week ahead). Wide windows keep the session card and progress in a fixed side pane beside a
 * multi-column queue; narrower windows stack them in one scrolling list.
 */
@Composable
internal fun MistakesTodayView(
    content: TodayContent,
    actions: TodayActions,
    wide: Boolean,
    columns: Int,
    inset: Dp,
    bottomPadding: Dp,
    listState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val gap = FolioSpacing.dp12
    if (wide) BoxWithConstraints(modifier.fillMaxSize()) {
        val pane = (maxWidth * .4f).coerceIn(360.dp, 480.dp)
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(pane).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(start = inset, bottom = inset + bottomPadding),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                TodaySessionCard(content, actions)
                TodayStatsRow(content.stats, content.totalCards)
                TodayForecast(content.stats)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(320.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(start = FolioSpacing.dp24, end = inset, bottom = inset + bottomPadding),
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) { todayQueue(content, actions, showEmpty = true) }
        }
    } else LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = inset, end = inset, bottom = inset + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(gap),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (columns > 1) span {
            // Portrait tablets: the session card and the progress column share the first row.
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                TodaySessionCard(content, actions, Modifier.weight(1.15f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    TodayStatsRow(content.stats, content.totalCards)
                    TodayForecast(content.stats)
                }
            }
        } else {
            span { TodaySessionCard(content, actions) }
            span { TodayStatsRow(content.stats, content.totalCards) }
        }
        todayQueue(content, actions, showEmpty = false)
        if (columns == 1) span { TodayForecast(content.stats, Modifier.padding(top = FolioSpacing.dp8)) }
    }
}

private fun LazyGridScope.span(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

// ---- Queue -------------------------------------------------------------------------------------

private fun LazyGridScope.todayQueue(content: TodayContent, actions: TodayActions, showEmpty: Boolean) {
    @Composable fun Card(m: ExamTrackMistake, resume: Boolean, kind: QueueKind) = TodayQueueCard(
        m, content.contexts[m.attemptId], content.schedules[m.id], kind, resume,
        content.attempts[m.id] ?: 0, content.working, content.now,
        onOpen = { actions.onOpen(m) }, onPractice = { actions.onPractice(m) }, onDelete = { actions.onDelete(m) },
    )
    val resumeIds = content.resume.map { it.id }.toSet()
    if (content.resume.isNotEmpty()) {
        span { QueueHeading("Continue where you left off", content.resume.size, Icons.Rounded.EditNote) }
        items(content.resume, key = { "resume-${it.id}" }) { Card(it, true, QueueKind.RESUME) }
    }
    if (content.overdue.isNotEmpty()) {
        span { QueueHeading("Overdue", content.overdue.size, Icons.Rounded.EventBusy, urgent = true) }
        items(content.overdue, key = { "overdue-${it.id}" }) { Card(it, it.id in resumeIds, QueueKind.OVERDUE) }
    }
    if (content.dueToday.isNotEmpty()) {
        span { QueueHeading("Due today", content.dueToday.size, Icons.Rounded.Today) }
        items(content.dueToday, key = { "today-${it.id}" }) { Card(it, it.id in resumeIds, QueueKind.DUE) }
    }
    if (showEmpty && content.due == 0 && content.resume.isEmpty()) span { QueueEmpty(content, actions.onBrowse) }
}

private enum class QueueKind { RESUME, OVERDUE, DUE }

@Composable
private fun QueueHeading(title: String, count: Int, icon: ImageVector, urgent: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = FolioSpacing.dp12, bottom = FolioSpacing.dp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (urgent) scheme.error else scheme.primary)
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
            color = if (urgent) scheme.error else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(shape = CircleShape, color = if (urgent) scheme.errorContainer else scheme.secondaryContainer,
            contentColor = if (urgent) scheme.onErrorContainer else scheme.onSecondaryContainer) {
            Text("$count", Modifier.padding(horizontal = FolioSpacing.dp10, vertical = FolioSpacing.dp2),
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A compact, scannable queue row: state badge, question, one line of facts and a play action. */
@Composable
private fun TodayQueueCard(
    mistake: ExamTrackMistake, context: ExamContext?, schedule: MistakeSchedule?, kind: QueueKind, resume: Boolean,
    attempts: Int, working: Boolean, now: Long, onOpen: () -> Unit, onPractice: () -> Unit, onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val hold = rememberLongPressGuard()
    val overdueDays = schedule?.let { runCatching { MistakeScheduler.overdueDays(it.dueAt, now) }.getOrNull() } ?: 0L
    // The badge's shape and colour both carry the card's state, so neither is needed alone.
    val (badgeShape, badgeColor, badgeContent) = when (kind) {
        QueueKind.RESUME -> Triple(MaterialShapes.Cookie4Sided.toShape(), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        QueueKind.OVERDUE -> Triple(MaterialShapes.SoftBurst.toShape(), scheme.errorContainer, scheme.onErrorContainer)
        QueueKind.DUE -> Triple(MaterialShapes.Cookie9Sided.toShape(), scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
    val where = listOfNotNull(context?.subject, context?.paper).filter { it.isNotBlank() }.joinToString(" · ")
        .ifBlank { if (mistake.attemptId.isEmpty()) "Uncategorised" else "Focal question" }
    val facts = buildList {
        schedule?.let { add(dueLabel(it.dueAt, now)) }
        mistake.marksLost?.let { add("−${trimMark(it)} ${if (it == 1.0) "mark" else "marks"}") }
        if (attempts > 0) add("$attempts ${if (attempts == 1) "attempt" else "attempts"}")
        mistake.category.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")
    Box {
        Card(
            onClick = hold.click(onOpen),
            shape = FolioShapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().longPressAction(hold) { menu = true },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = FolioSpacing.dp16, top = FolioSpacing.dp16, bottom = FolioSpacing.dp16, end = FolioSpacing.dp8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16),
            ) {
                ShapeBadge(badgeShape, badgeColor, badgeContent, Modifier.size(48.dp)) {
                    when {
                        kind == QueueKind.RESUME -> Icon(Icons.Rounded.EditNote, null, Modifier.size(22.dp))
                        kind == QueueKind.OVERDUE && overdueDays > 0 ->
                            Text("${overdueDays}d", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        else -> Icon(Icons.Rounded.Today, null, Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                    Text(where, style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(mistake.question, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.labelMedium,
                        color = if (kind == QueueKind.OVERDUE) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalIconButton(hold.click(onPractice), enabled = !working, shapes = IconButtonDefaults.shapes()) {
                    Icon(if (resume) Icons.Rounded.Edit else Icons.Rounded.PlayArrow,
                        if (resume) "Continue this question" else "Practise this question")
                }
            }
        }
        FolioMenuPopover(menu, { menu = false }, modifier = Modifier.guardUiTouches(), title = "Review card") {
            FolioMenuItem({ Text("Open details") }, { menu = false; onOpen() }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) })
            FolioMenuItem(
                { Text(if (resume) "Continue handwritten review" else "Practise this question") },
                { menu = false; onPractice() },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
            )
            FolioMenuItem({ Text("Delete card") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, destructive = true)
        }
    }
}

@Composable
private fun QueueEmpty(content: TodayContent, onBrowse: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp32, horizontal = FolioSpacing.dp24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
    ) {
        ShapeBadge(MaterialShapes.Cookie12Sided.toShape(), scheme.secondaryContainer, scheme.onSecondaryContainer, Modifier.size(112.dp)) {
            Icon(if (content.totalCards == 0) Icons.Rounded.School else Icons.Rounded.DoneAll, null, Modifier.size(44.dp))
        }
        Text(if (content.totalCards == 0) "Your queue starts here" else "The queue is clear",
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            if (content.totalCards == 0) "Questions you log in Focal appear here once they sync."
            else "Questions return here when their next review is due.",
            style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (content.totalCards > 0) TextButton(onBrowse, shapes = ButtonDefaults.shapes()) {
            Text("Browse the library"); Spacer(Modifier.width(FolioSpacing.dp8))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
        }
    }
}

// ---- Session card ------------------------------------------------------------------------------

@Composable
private fun TodaySessionCard(content: TodayContent, actions: TodayActions, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = FolioShapes.panel, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
        Column(Modifier.padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            content.completed?.let { SessionDone(it, content.pending, actions.onDismissSummary) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                TodayRing(content)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Text(
                        when {
                            content.totalCards == 0 -> "Turn mistakes into understanding"
                            content.due == 0 -> "You're all caught up"
                            content.due == 1 -> "1 question to review"
                            else -> "${content.due} questions to review"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        when {
                            content.totalCards == 0 -> "Log a mistake in Focal, then sync to practise it here."
                            content.due == 0 -> content.stats.nextDueAt?.let { "Next up: ${dueLabel(it, content.now).replaceFirstChar(Char::lowercase)}" }
                                ?: "Nothing else is scheduled yet."
                            else -> buildList {
                                if (content.overdue.isNotEmpty()) add("${content.overdue.size} overdue")
                                if (content.dueToday.isNotEmpty()) add("${content.dueToday.size} due today")
                                if (content.focus.isNotBlank()) add(content.focus)
                            }.joinToString(" · ")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onPrimaryContainer.copy(alpha = .8f),
                    )
                }
            }
            if (content.due > 0) {
                if (content.subjects.size > 1) ChoiceSection("Focus") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        FilterChip(content.focus.isBlank(), { actions.onFocus("") }, { Text("All · ${content.allDue}") })
                        content.subjects.forEach { (subject, count) ->
                            FilterChip(content.focus == subject, { actions.onFocus(if (content.focus == subject) "" else subject) },
                                { Text("$subject · $count", maxLines = 1) })
                        }
                    }
                }
                ChoiceSection("Session length") {
                    ConnectedChoice(TodaySessionSizes, content.limit, actions.onLimit,
                        label = { if (it == Int.MAX_VALUE) "All" else "$it" })
                }
                ChoiceSection("Order") {
                    ConnectedChoice(TodayOrder.entries, content.order, actions.onOrder, label = { it.label }, icon = { it.icon })
                }
                Button(
                    actions.onStart,
                    enabled = !content.working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    if (content.working) LoadingIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Text(
                        when {
                            content.working -> "Opening your page…"
                            content.sessionSize == 1 -> "Start 1 question"
                            else -> "Start ${content.sessionSize} questions"
                        },
                        style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                    )
                }
            } else if (content.totalCards > 0) FilledTonalButton(actions.onBrowse, shapes = ButtonDefaults.shapes()) {
                Text("Browse the library"); Spacer(Modifier.width(FolioSpacing.dp8))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

/** Today's progress: reviewed today against everything that was due, with the count left inside. */
@Composable
private fun TodayRing(content: TodayContent) {
    val scheme = MaterialTheme.colorScheme
    val done = content.stats.reviewedToday
    val left = content.allDue
    val target = if (done + left == 0) 0f else done.toFloat() / (done + left)
    val progress by animateFloatAsState(target, MaterialTheme.motionScheme.slowSpatialSpec(), label = "today")
    val description = when {
        content.totalCards == 0 -> "No questions yet"
        left == 0 -> "All due questions reviewed"
        else -> "$done reviewed today, $left left"
    }
    Box(Modifier.size(96.dp).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (content.totalCards == 0) {
            ShapeBadge(MaterialShapes.Cookie9Sided.toShape(), scheme.primary, scheme.onPrimary, Modifier.fillMaxSize()) {
                Icon(Icons.Rounded.School, null, Modifier.size(40.dp))
            }
        } else if (left == 0) {
            ShapeBadge(MaterialShapes.Sunny.toShape(), scheme.primary, scheme.onPrimary, Modifier.fillMaxSize()) {
                Icon(Icons.Rounded.Check, null, Modifier.size(44.dp))
            }
        } else {
            CircularWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = scheme.primary,
                trackColor = scheme.surface.copy(alpha = .6f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$left", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("left", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SessionDone(completed: Int, pending: Int, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = FolioShapes.extraLarge, color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = FolioSpacing.dp12, top = FolioSpacing.dp12, bottom = FolioSpacing.dp12, end = FolioSpacing.dp4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
        ) {
            ShapeBadge(MaterialShapes.Sunny.toShape(), scheme.tertiary, scheme.onTertiary, Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Check, null, Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Session complete", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$completed ${if (completed == 1) "question" else "questions"} reviewed · " +
                        if (pending > 0) "$pending waiting to sync" else "everything is saved",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onDismiss, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Dismiss") }
        }
    }
}

@Composable
private fun ChoiceSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f))
        content()
    }
}

/** M3 Expressive connected button group behaving as a single-choice selector. */
@Composable
private fun <T> ConnectedChoice(
    options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String,
    icon: ((T) -> ImageVector)? = null,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = FolioSpacing.dp8),
            ) {
                icon?.let { Icon(it(option), null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp6)) }
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- Progress ----------------------------------------------------------------------------------

@Composable
private fun TodayStatsRow(stats: TodayStats, total: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        StatTile(Icons.Rounded.TaskAlt, "${stats.reviewedToday}", "Reviewed today", Modifier.weight(1f).fillMaxHeight())
        StatTile(Icons.Rounded.LocalFireDepartment, "${stats.streak}", if (stats.streak == 1) "Day streak" else "Days streak",
            Modifier.weight(1f).fillMaxHeight())
        StatTile(Icons.Rounded.Style, "$total", "In library", Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier.semantics(mergeDescendants = true) {}, shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The week ahead as bars: later today, then the next six days. */
@Composable
private fun TodayForecast(stats: TodayStats, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val locale = Locale.getDefault()
    val days = listOf(Triple("Today", "later today", stats.laterToday)) + stats.week.map {
        Triple(it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
            it.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale), it.count)
    }
    val total = days.sumOf { it.third }
    val peak = days.maxOf { it.third }.coerceAtLeast(1)
    Surface(modifier.fillMaxWidth(), shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Coming up", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text(if (total == 0) "Nothing this week" else "$total this week",
                    style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                days.forEachIndexed { index, (short, full, count) ->
                    val height by animateDpAsState(if (count == 0) 6.dp else 6.dp + 66.dp * (count.toFloat() / peak),
                        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "bar")
                    Column(
                        Modifier.weight(1f).clearAndSetSemantics {
                            contentDescription = "$full: $count ${if (count == 1) "question" else "questions"}"
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
                    ) {
                        Text(if (count == 0) "" else "$count", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp))
                                .background(when {
                                    count == 0 -> scheme.surfaceContainerHighest
                                    index == 0 -> scheme.tertiary
                                    else -> scheme.primary
                                }))
                        }
                        Text(short, style = MaterialTheme.typography.labelSmall,
                            color = if (index == 0) scheme.onSurface else scheme.onSurfaceVariant,
                            fontWeight = if (index == 0) FontWeight.SemiBold else null, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShapeBadge(shape: Shape, color: Color, contentColor: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(shape).background(color), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}
