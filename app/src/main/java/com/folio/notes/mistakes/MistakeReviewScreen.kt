@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onRate: (ReviewRating) -> Unit) {
    var revealed by rememberSaveable(attempt.reviewId) { mutableStateOf(false) }
    var questionExpanded by rememberSaveable(attempt.reviewId) { mutableStateOf(true) }
    var adjustLayout by rememberSaveable { mutableStateOf(false) }
    var landscapeShare by rememberSaveable { mutableFloatStateOf(.36f) }
    var portraitShare by rememberSaveable { mutableFloatStateOf(.30f) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) { actionMessage?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.saveFailed) {
        if (state.saveFailed) snackbar.showSnackbar("Saving failed — retry from the page status before rating.", duration = SnackbarDuration.Long)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(m.question, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                state.saveFailed -> "Save failed · retry before rating"
                                state.pendingSaves > 0 -> "Saving your ink…"
                                queuePos != null && queueSize != null && queueSize > 1 -> "Card $queuePos of $queueSize"
                                dueLeft > 1 -> "${dueLeft - 1} more due after this"
                                else -> "Practise at your own pace"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = { IconButton(onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to mistakes · handwriting is saved") } },
                actions = {
                    IconButton(onToggleShuffle, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.Rounded.Shuffle, if (shuffle) "Shuffled order · tap for due order" else "Due order · tap to shuffle",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onSkip, enabled = canSkip && !busy, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.SkipNext, "Skip this card for now")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!revealed) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button({ revealed = true; questionExpanded = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Icon(Icons.Rounded.Visibility, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Compare answer")
                            }
                            if (canSkip) {
                                OutlinedButton(onSkip, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.SkipNext, "Skip this card for now")
                                    Spacer(Modifier.width(6.dp))
                                    Text("Skip")
                                }
                            }
                        }
                        Text("Write first, then compare — honest ratings build the schedule.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    } else {
                        val now = remember(revealed) { isoTime() }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReviewRating.entries.forEach { rating ->
                                val preview = remember(m.id, rating) {
                                    runCatching { MistakeScheduler.previewMistakeReview(m, rating, now) }.getOrNull()
                                }
                                val label = preview?.let { intervalLabel(it, rating) } ?: ""
                                val canRate = !busy && state.pendingSaves == 0 && !state.saveFailed
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
                                    ReviewRating.AGAIN -> OutlinedButton({ onRate(rating) }, enabled = canRate, modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp), shapes = ButtonDefaults.shapes()) { RatingContent() }
                                    ReviewRating.GOOD -> Button({ onRate(rating) }, enabled = canRate, modifier = modifier, shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(vertical = 8.dp)) { RatingContent() }
                                    else -> FilledTonalButton({ onRate(rating) }, enabled = canRate, modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp), shapes = ButtonDefaults.shapes()) { RatingContent() }
                                }
                            }
                        }
                        Text(
                            when {
                                state.saveFailed -> "Save failed — retry from the editor status, then rate."
                                state.pendingSaves > 0 -> "Saving your ink… ratings unlock when it says Saved."
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
            @Composable fun ReferencePane(modifier: Modifier) {
                Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (revealed) "Compare & reflect" else "Read the question", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton({ adjustLayout = !adjustLayout }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Tune, "Adjust question panel size") }
                            if (!wide) TextButton({ questionExpanded = !questionExpanded }, shapes = ButtonDefaults.shapes()) { Text(if (questionExpanded) "Collapse" else "Expand") }
                        }
                        if (adjustLayout && (wide || questionExpanded)) {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Text("Question space · ${((if (wide) landscapeShare else portraitShare) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                                Slider(value = if (wide) landscapeShare else portraitShare,
                                    onValueChange = { if (wide) landscapeShare = it else portraitShare = it }, valueRange = .2f.. .55f)
                            }
                        }
                        if (wide || questionExpanded) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            QuestionContent(m, context, attempt.userId, model.attachments)
                            if (revealed) {
                                HorizontalDivider()
                                Text("Correction", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                RichText(m.correction.ifBlank { "No correction saved in ExamTrack." }, style = MaterialTheme.typography.bodyLarge)
                                Text("What went wrong", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                RichText(m.explanation.ifBlank { "No explanation saved in ExamTrack." }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                ReferencePane(Modifier.weight(landscapeShare).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f - landscapeShare).fillMaxHeight()) { EditorScreen(state, folio, finger, haptics, shapes, onSettings, onExport) }
            } else Column(Modifier.fillMaxSize()) {
                ReferencePane(Modifier.fillMaxWidth().height(if (questionExpanded) referenceHeight else 52.dp))
                Box(Modifier.weight(1f)) { EditorScreen(state, folio, finger, haptics, shapes, onSettings, onExport) }
            }
        }
    }
}
