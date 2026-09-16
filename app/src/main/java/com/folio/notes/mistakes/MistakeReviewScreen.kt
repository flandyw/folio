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
    onRate: (ReviewRating) -> Unit) {
    var revealed by rememberSaveable(attempt.reviewId) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
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
                                else -> "Last one due — nice"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to mistakes · handwriting is saved") } },
                actions = {
                    IconButton(onToggleShuffle, enabled = !busy) {
                        Icon(
                            Icons.Rounded.Shuffle, if (shuffle) "Shuffled order · tap for due order" else "Due order · tap to shuffle",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onSkip, enabled = canSkip && !busy) {
                        Icon(Icons.Rounded.SkipNext, "Skip this card for now")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!revealed) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button({ revealed = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Icon(Icons.Rounded.Visibility, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Reveal answer")
                            }
                            if (canSkip) {
                                OutlinedButton(onSkip, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp)) {
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
                                    ReviewRating.AGAIN -> OutlinedButton({ onRate(rating) }, enabled = canRate, modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) { RatingContent() }
                                    ReviewRating.GOOD -> Button({ onRate(rating) }, enabled = canRate, modifier = modifier, shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(vertical = 8.dp)) { RatingContent() }
                                    else -> FilledTonalButton({ onRate(rating) }, enabled = canRate, modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) { RatingContent() }
                                }
                            }
                        }
                        Text(
                            when {
                                state.saveFailed -> "Save failed — retry from the editor status, then rate."
                                state.pendingSaves > 0 -> "Saving your ink… ratings unlock when it says Saved."
                                busy -> "Saving your review…"
                                else -> "How well did you recall it? The interval previews above."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 250.dp), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestionContent(m, context, attempt.userId, model.attachments)
                    if (revealed) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Correction", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        RichText(m.correction.ifBlank { "No correction saved." }, style = MaterialTheme.typography.bodyLarge)
                        Text("Why this was wrong", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        RichText(m.explanation.ifBlank { "No explanation saved." }, style = MaterialTheme.typography.bodyMedium)
                        if (m.category.isNotBlank()) Text("Category: ${m.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.totalMarks != null && m.marksLost != null) {
                            Text("Marks lost: ${trimMark(m.marksLost)} / ${trimMark(m.totalMarks)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("Cover the answer in your head — reveal when your page is done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Box(Modifier.weight(1f).padding(top = 8.dp)) { EditorScreen(state, folio, finger, haptics, shapes, onSettings, onExport) }
        }
    }
}
