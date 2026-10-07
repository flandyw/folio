@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.*

// ---- Review ------------------------------------------------------------------------------------

@Composable internal fun MistakeReviewScreen(m: ExamTrackMistake, context: ExamContext?, attempt: LocalMistakeReviewAttempt,
    model: MistakesViewModel, folio: FolioViewModel, state: FolioState, finger: Boolean, haptics: Boolean, shapes: Boolean,
    busy: Boolean, onBack: () -> Unit, onSettings: () -> Unit, onExport: () -> Unit, dueLeft: Int,
    queuePos: Int? = null, queueSize: Int? = null,
    shuffle: Boolean = false, onToggleShuffle: () -> Unit = {},
    canSkip: Boolean = false, onSkip: () -> Unit = {},
    actionMessage: String? = null,
    onRate: (ReviewRating) -> Unit,
    onDelete: () -> Unit = {}) {
    val androidContext = LocalContext.current
    val preferences = remember(androidContext) { androidContext.getSharedPreferences("preferences", 0) }
    var revealed by rememberSaveable(attempt.reviewId) { mutableStateOf(false) }
    var questionExpanded by rememberSaveable(attempt.reviewId) { mutableStateOf(true) }
    var adjustLayout by rememberSaveable { mutableStateOf(false) }
    // The question/editor split is remembered across cards and sessions: a wide question needs
    // more room than a one-line prompt, and re-tuning it per card is the thing to avoid.
    var landscapeShare by rememberSaveable {
        mutableFloatStateOf(MistakeSplit.coerce(preferences.getFloat(MistakeSplit.KEY_LANDSCAPE, MistakeSplit.DEFAULT_LANDSCAPE)))
    }
    var portraitShare by rememberSaveable {
        mutableFloatStateOf(MistakeSplit.coerce(preferences.getFloat(MistakeSplit.KEY_PORTRAIT, MistakeSplit.DEFAULT_PORTRAIT)))
    }
    var showPrevious by rememberSaveable(attempt.reviewId) { mutableStateOf(false) }
    var showDelete by rememberSaveable(attempt.reviewId) { mutableStateOf(false) }
    // Earlier handwriting for the same question, newest first. The current page is excluded
    // so "compare" always means looking back, never at the page being written now.
    val previousAttempts = remember(state.notes, attempt.userId, m.id, attempt.reviewId) {
        previousPracticeAttempts(state.notes, attempt.userId, m.id, attempt.reviewId)
    }
    var textScale by remember { mutableFloatStateOf(preferences.getFloat("mistakeTextScale", 1f).coerceIn(.75f, 2f)) }
    var pendingAction by remember(attempt.reviewId, busy, revealed) { mutableStateOf<String?>(null) }
    var firstTapAt by remember(attempt.reviewId) { mutableLongStateOf(0L) }
    LaunchedEffect(pendingAction, firstTapAt) {
        if (pendingAction != null) {
            delay(1000)
            pendingAction = null
        }
    }
    fun confirmDoubleTap(action: String, confirmed: () -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        if (pendingAction == action && now - firstTapAt <= 1000) {
            pendingAction = null
            confirmed()
        } else {
            pendingAction = action
            firstTapAt = now
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) { actionMessage?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.saveFailed) {
        if (state.saveFailed) snackbar.showSnackbar("Saving failed — retry from the page status before rating.", duration = SnackbarDuration.Long)
    }
    Scaffold(
        // Keep the editor mounted while the next page is prepared, but do not let
        // a stray stroke or tap edit a notebook during the handoff.
        modifier = Modifier.pointerInput(busy) {
            if (busy) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(m.question, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                state.saveFailed -> "Save failed · retry before rating"
                                state.saving -> "Saving your ink…"
                                queuePos != null && queueSize != null && queueSize > 1 -> "Card $queuePos of $queueSize"
                                dueLeft > 1 -> "${dueLeft - 1} more due after this"
                                else -> "Practise at your own pace"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = { IconButton(onBack, enabled = !busy, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to mistakes · handwriting is saved") } },
                actions = {
                    if (previousAttempts.isNotEmpty()) {
                        IconButton({ showPrevious = true }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.History, "Compare with your last attempt (${previousAttempts.size})")
                        }
                    }
                    IconButton(onToggleShuffle, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.Rounded.Shuffle, if (shuffle) "Shuffled order · tap for due order" else "Due order · tap to shuffle",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton({ confirmDoubleTap("skipTop", onSkip) }, enabled = canSkip && !busy, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.SkipNext, if (pendingAction == "skipTop") "Tap again to skip" else "Double-tap to skip this card")
                    }
                    IconButton({ showDelete = true }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.DeleteOutline, "Delete this card")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp10), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    if (!revealed) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                            Button({ confirmDoubleTap("compare") { revealed = true; questionExpanded = true } }, enabled = !busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Icon(Icons.Rounded.Visibility, null)
                                Spacer(Modifier.width(FolioSpacing.dp8))
                                Text(if (pendingAction == "compare") "Tap again to compare" else "Compare answer")
                            }
                            if (canSkip) {
                                OutlinedButton({ confirmDoubleTap("skipBottom", onSkip) }, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.SkipNext, "Skip this card for now")
                                    Spacer(Modifier.width(FolioSpacing.dp6))
                                    Text(if (pendingAction == "skipBottom") "Tap again" else "Skip")
                                }
                            }
                        }
                        Text(if (pendingAction == "skipTop") "Tap the top Skip button again to confirm." else "Double-tap Compare answer or Skip to confirm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    } else {
                        val now = remember(revealed) { isoTime() }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            ReviewRating.entries.forEach { rating ->
                                val preview = remember(m.id, rating) {
                                    runCatching { MistakeScheduler.previewMistakeReview(m, rating, now) }.getOrNull()
                                }
                                val label = preview?.let { intervalLabel(it, rating) } ?: ""
                                val canRate = !busy && !state.saving && !state.saveFailed
                                val modifier = Modifier.weight(1f)
                                val labelText = label
                                val ratingName = rating.wire.replaceFirstChar { it.uppercase() }
                                @Composable fun RatingContent() {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(ratingName, style = MaterialTheme.typography.labelLarge)
                                        if (labelText.isNotBlank()) Text(labelText, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                when (rating) {
                                    ReviewRating.AGAIN -> OutlinedButton({ onRate(rating) }, enabled = canRate, modifier = modifier, contentPadding = PaddingValues(vertical = FolioSpacing.dp8), shapes = ButtonDefaults.shapes()) { RatingContent() }
                                    ReviewRating.GOOD -> Button({ onRate(rating) }, enabled = canRate, modifier = modifier, shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(vertical = FolioSpacing.dp8)) { RatingContent() }
                                    else -> FilledTonalButton({ onRate(rating) }, enabled = canRate, modifier = modifier, contentPadding = PaddingValues(vertical = FolioSpacing.dp8), shapes = ButtonDefaults.shapes()) { RatingContent() }
                                }
                            }
                        }
                        Text(
                            when {
                                pendingAction == "skipTop" -> "Tap the top Skip button again to confirm."
                                state.saveFailed -> "Save failed — retry from the editor status, then rate."
                                state.saving -> "Saving your ink… ratings unlock when it says Saved."
                                busy -> "Saving your review…"
                                else -> "Again: missed it · Hard: needed help · Good: recalled it · Easy: confident"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = mistakeLayout(maxWidth.value.toInt(), maxHeight.value.toInt()).splitReview
            val referenceHeight = maxHeight * portraitShare
            val splitWidth = maxWidth
            val splitHeight = maxHeight
            // Drag the handle to resize; a release settles on a comfortable share and the last
            // position is remembered for the next card and the next session.
            fun draggedShare(deltaPx: Float, totalPx: Float) {
                val next = MistakeSplit.dragged(if (wide) landscapeShare else portraitShare, deltaPx, totalPx)
                if (wide) landscapeShare = next else portraitShare = next
            }
            fun releaseShare() {
                if (wide) {
                    landscapeShare = MistakeSplit.snap(landscapeShare)
                    preferences.edit().putFloat(MistakeSplit.KEY_LANDSCAPE, landscapeShare).apply()
                } else {
                    portraitShare = MistakeSplit.snap(portraitShare)
                    preferences.edit().putFloat(MistakeSplit.KEY_PORTRAIT, portraitShare).apply()
                }
            }
            fun resetShare() {
                if (wide) {
                    landscapeShare = MistakeSplit.DEFAULT_LANDSCAPE
                    preferences.edit().putFloat(MistakeSplit.KEY_LANDSCAPE, landscapeShare).apply()
                } else {
                    portraitShare = MistakeSplit.DEFAULT_PORTRAIT
                    preferences.edit().putFloat(MistakeSplit.KEY_PORTRAIT, portraitShare).apply()
                }
            }
            @Composable fun ReferencePane(modifier: Modifier) {
                Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (revealed) "Compare & reflect" else "Read the question", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton({ adjustLayout = !adjustLayout }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Adjust question panel and text size") }
                            ShareTextButton(
                                onShare = { questionOnly -> shareText(androidContext, mistakeShareText(m, if (questionOnly) emptyList() else workingTexts(state, attempt))) },
                            )
                            if (!wide) TextButton({ questionExpanded = !questionExpanded }, shapes = ButtonDefaults.shapes()) { Text(if (questionExpanded) "Collapse" else "Expand") }
                        }
                        if (adjustLayout) {
                            AlertDialog(
                                onDismissRequest = { adjustLayout = false },
                                title = { Text("Question display") },
                                text = {
                                    Column {
                                        Text("Question space · ${((if (wide) landscapeShare else portraitShare) * 100).toInt()}%")
                                        Slider(value = MistakeSplit.coerce(if (wide) landscapeShare else portraitShare),
                                            onValueChange = { if (wide) landscapeShare = it else portraitShare = it },
                                            onValueChangeFinished = { releaseShare() },
                                            valueRange = MistakeSplit.MIN..MistakeSplit.MAX)
                                        Text("Text size · ${(textScale * 100).toInt()}%")
                                        Slider(value = textScale, onValueChange = { textScale = it },
                                            onValueChangeFinished = { preferences.edit().putFloat("mistakeTextScale", textScale).apply() },
                                            valueRange = .75f..2f, steps = 4)
                                    }
                                },
                                confirmButton = { TextButton({ adjustLayout = false }) { Text("Done") } }
                            )
                        }
                        if (wide || questionExpanded) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                            QuestionContent(m, context, attempt.userId, model.attachments, textScale)
                            if (previousAttempts.isNotEmpty()) {
                                Surface(
                                    onClick = { showPrevious = true },
                                    shape = FolioShapes.large,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                                        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "Stuck? Compare with your last attempt",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                            Text(
                                                "${previousAttempts.size} earlier attempt${if (previousAttempts.size == 1) "" else "s"} kept · read-only, your current ink stays",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                }
                            }
                            if (revealed) {
                                HorizontalDivider()
                                Text("Correction", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                RichText(m.correction.ifBlank { "No correction saved in Focal." }, style = MaterialTheme.typography.bodyLarge.scaledBy(textScale))
                                Text("What went wrong", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                RichText(m.explanation.ifBlank { "No explanation saved in Focal." }, style = MaterialTheme.typography.bodyMedium.scaledBy(textScale))
                            }
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                ReferencePane(Modifier.weight(landscapeShare).fillMaxHeight())
                SplitDivider(
                    vertical = true,
                    onDrag = { draggedShare(it, splitWidth.value) },
                    onRelease = ::releaseShare,
                    onDoubleTap = ::resetShare,
                    contentDescription = "Split between the question and your working. Drag to resize. Double-tap for the default split.",
                )
                Box(Modifier.weight(1f - landscapeShare).fillMaxHeight()) { EditorScreen(state, folio, finger, haptics, shapes, onSettings, onExport, showBack = false) }
            } else Column(Modifier.fillMaxSize()) {
                ReferencePane(Modifier.fillMaxWidth().height(if (questionExpanded) referenceHeight else 52.dp))
                // A collapsed question panel is still a handle: any drag reopens and resizes it.
                SplitDivider(
                    vertical = false,
                    onDrag = { delta ->
                        if (!questionExpanded) questionExpanded = true
                        draggedShare(delta, splitHeight.value)
                    },
                    onRelease = ::releaseShare,
                    onDoubleTap = ::resetShare,
                    contentDescription = "Split between the question and your working. Drag to resize. Double-tap for the default split.",
                )
                Box(Modifier.weight(1f)) { EditorScreen(state, folio, finger, haptics, shapes, onSettings, onExport, showBack = false) }
            }
        }
    }
    if (showPrevious && previousAttempts.isNotEmpty()) {
        PreviousAttemptDialog(attempts = previousAttempts, folio = folio, onClose = { showPrevious = false })
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete this card?") },
            text = { Text("“${m.question}” leaves your review list on all devices. Your handwriting on this device is kept.") },
            dismissButton = { TextButton({ showDelete = false }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
            confirmButton = {
                Button(
                    { showDelete = false; onDelete() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Delete card") }
            },
        )
    }
}

/** Typed working on the practice page, top to bottom. Handwriting has no text to share. */
private fun workingTexts(state: FolioState, attempt: LocalMistakeReviewAttempt): List<String> =
    state.notes.find { it.id == attempt.practiceNotebookId }?.pages?.find { it.id == attempt.practicePageId }
        ?.texts?.sortedWith(compareBy({ it.y }, { it.x }))?.map { it.text.trim() }?.filter(String::isNotEmpty) ?: emptyList()

/** The question as raw text, followed by [working] when there is any. */
private fun mistakeShareText(m: ExamTrackMistake, working: List<String>): String = buildString {
    append(m.question.trim())
    m.questionText?.trim()?.takeIf(String::isNotEmpty)?.let { append("\n\n").append(it) }
    if (working.isNotEmpty()) append("\n\nMy working:\n").append(working.joinToString("\n\n"))
}

private fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(send, "Share question").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Tap shares the question with your working; long-press shares the question text alone. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun ShareTextButton(onShare: (questionOnly: Boolean) -> Unit) {
    Box(
        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).combinedClickable(
            role = androidx.compose.ui.semantics.Role.Button,
            onClickLabel = "Share question and working",
            onLongClickLabel = "Share question text only",
            onLongClick = { onShare(true) },
            onClick = { onShare(false) }
        ),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Rounded.IosShare, "Share question and working · hold for question only", Modifier.size(24.dp)) }
}
