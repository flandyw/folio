@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.*
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CancellationException
import java.io.File

// ---- Detail ----------------------------------------------------------------------------

@Composable
internal fun MistakeDetailCard(
    mistake: ExamTrackMistake, context: ExamContext?, schedule: MistakeSchedule?,
    due: Boolean, working: Boolean, userId: String, attachments: MistakeAttachmentRepository,
    attempts: List<Pair<com.folio.notes.Notebook, LocalMistakeReviewAttempt>>,
    onBackToList: () -> Unit, onPractice: () -> Unit,
    onOpenAttempt: (noteId: String, pageId: String, reviewId: String, completed: Boolean) -> Unit,
) {
    var showCorrection by rememberSaveable(mistake.id) { mutableStateOf(false) }
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onBackToList, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("All mistakes")
            }
            context?.let {
                Text(
                    listOf(it.subject, it.title, it.paper).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
                )
            }
            Text(mistake.question, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
            if (!mistake.questionText.isNullOrBlank()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    RichText(mistake.questionText!!, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            AttachmentGallery(mistake, userId, attachments)
            MistakeMetaGrid(mistake, schedule, due)
            if (mistake.suspended) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PauseCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Suspended — unsuspend in ExamTrack to practise it again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(onPractice, enabled = !working && !mistake.suspended, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Rounded.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text(if (mistake.suspended) "Suspended" else "Practise in handwriting")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Correction", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({ showCorrection = !showCorrection }) { Text(if (showCorrection) "Hide" else "Show") }
            }
            if (showCorrection) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Correction", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RichText(mistake.correction.ifBlank { "No correction saved." }, style = MaterialTheme.typography.bodyLarge)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Why this was wrong", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RichText(mistake.explanation.ifBlank { "No explanation saved." }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("Handwritten attempts (${attempts.size})", style = MaterialTheme.typography.titleMedium)
            if (attempts.isEmpty()) {
                Text("No attempts yet — your workings will be listed here after the first practice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                attempts.sortedBy { it.second.completedAt ?: "" }.forEach { (note, a) ->
                    val done = a.completedAt != null
                    Surface(onClick = { onOpenAttempt(note.id, a.practicePageId, a.reviewId, done) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                if (done) Icons.Rounded.CheckCircle else Icons.Rounded.PendingActions,
                                null, tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (a.completedAt?.take(10) ?: "Unfinished") + " · " + (a.rating?.replaceFirstChar { it.uppercase() } ?: "Practising"),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (done) "Open handwriting" else "Continue review",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MistakeMetaGrid(mistake: ExamTrackMistake, schedule: MistakeSchedule?, due: Boolean) {
    val items = buildList {
        if (mistake.totalMarks != null && mistake.marksLost != null) add("Marks lost" to "−${trimMark(mistake.marksLost)} / ${trimMark(mistake.totalMarks)}")
        mistake.category.takeIf { it.isNotBlank() }?.let { add("Category" to it) }
        mistake.areaOfStudy?.takeIf { it.isNotBlank() }?.let { add("Area" to it) }
        mistake.criterion?.takeIf { it.isNotBlank() }?.let { add("Criterion" to it) }
        schedule?.let {
            add("Status" to if (mistake.suspended) "Suspended" else it.state.wire.replaceFirstChar(Char::uppercase) + " · " + dueLabel(it.dueAt))
            if (it.repetitions > 0 || it.lapses > 0) add("Reviews" to "${it.repetitions} ✓ · ${it.lapses} ✗")
        }
        if (mistake.reviewHistory.isNotEmpty()) add("History" to "${mistake.reviewHistory.size} review${if (mistake.reviewHistory.size == 1) "" else "s"}")
    }
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---- Question + attachments (LaTeX aware) ----------------------------------------------------

@Composable internal fun QuestionContent(m: ExamTrackMistake, context: ExamContext?, user: String, attachments: MistakeAttachmentRepository) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        context?.let {
            val crumb = listOf(it.subject, it.title, it.paper).filter(String::isNotBlank).joinToString(" · ")
            if (crumb.isNotBlank()) Text(crumb, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(m.question, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
        if (!m.questionText.isNullOrBlank()) {
            RichText(m.questionText!!, style = MaterialTheme.typography.bodyLarge)
        }
        AttachmentGallery(m, user, attachments)
    }
}

@Composable
private fun AttachmentGallery(m: ExamTrackMistake, user: String, attachments: MistakeAttachmentRepository) {
    if (m.attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        m.attachments.forEach { attachment ->
            AttachmentImage(attachment, user, attachments)
        }
    }
}

@Composable
private fun AttachmentImage(attachment: MistakeAttachment, user: String, attachments: MistakeAttachmentRepository) {
    val androidContext = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(androidContext).components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    var file by remember(user, attachment.storagePath) { mutableStateOf<File?>(null) }
    var failed by remember(user, attachment.storagePath) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(user, attachment.storagePath, retry) {
        try { file = attachments.get(user, attachment); failed = false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        when {
            file != null -> Column {
                AsyncImage(
                    file, attachment.name, imageLoader = loader,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                )
                Text(
                    attachment.name, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            failed -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Unavailable offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ retry++ }) { Text("Retry") }
            }
            else -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoadingIndicator(Modifier.size(20.dp))
                Text("Loading ${attachment.name}…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
