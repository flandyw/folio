@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    onPractice: () -> Unit,
    onOpenAttempt: (noteId: String, pageId: String, reviewId: String, completed: Boolean) -> Unit,
    onDelete: () -> Unit = {},
) {
    var tab by rememberSaveable(mistake.id) { mutableIntStateOf(0) }
    var showMetadata by rememberSaveable(mistake.id) { mutableStateOf(false) }
    var showDelete by rememberSaveable(mistake.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        context?.let {
            Text(listOf(it.subject, it.title, it.paper).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(mistake.question, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif)
        Text(if (mistake.suspended) "Suspended in ExamTrack" else schedule?.let { dueLabel(it.dueAt) } ?: "Ready to practise",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onPractice, enabled = !working && !mistake.suspended, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) {
            if (working) LoadingIndicator(Modifier.size(20.dp))
            else Icon(Icons.Rounded.Edit, null)
            Spacer(Modifier.width(8.dp))
            Text(if (mistake.suspended) "Unsuspend in ExamTrack to practise" else if (attempts.any { it.second.completedAt == null }) "Continue handwritten review" else "Practise this question")
        }
        TextButton({ showDelete = true }, enabled = !working, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Delete this card")
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Question", "Solution", "Attempts").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> {
                if (!mistake.questionText.isNullOrBlank()) RichText(mistake.questionText, style = MaterialTheme.typography.bodyLarge)
                else if (mistake.attachments.isEmpty()) Text("No extra question text saved. Use the question reference above.", style = MaterialTheme.typography.bodyMedium)
                AttachmentGallery(mistake, userId, attachments)
                TextButton({ showMetadata = !showMetadata }, shapes = ButtonDefaults.shapes()) {
                    Icon(if (showMetadata) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    Text(if (showMetadata) "Hide question information" else "Marks, topic & review information")
                }
                if (showMetadata) MistakeMetaGrid(mistake, schedule, due)
            }
            1 -> {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("The correction", style = MaterialTheme.typography.titleMedium)
                        RichText(mistake.correction.ifBlank { "No correction saved in ExamTrack." }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("What went wrong", style = MaterialTheme.typography.titleMedium)
                RichText(mistake.explanation.ifBlank { "No explanation saved in ExamTrack." }, style = MaterialTheme.typography.bodyLarge)
            }
            2 -> {
                Text("Your handwritten attempts", style = MaterialTheme.typography.titleMedium)
                if (attempts.isEmpty()) Text("Work through the question once and your page will appear here. Every attempt is kept.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                attempts.sortedByDescending { it.second.completedAt ?: "9999" }.forEach { (note, attempt) ->
                    val done = attempt.completedAt != null
                    Surface(onClick = { onOpenAttempt(note.id, attempt.practicePageId, attempt.reviewId, done) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.PendingActions, null)
                            Column(Modifier.weight(1f)) {
                                Text(if (done) attempt.rating?.replaceFirstChar(Char::uppercase) ?: "Reviewed" else "Ready to continue", style = MaterialTheme.typography.titleSmall)
                                Text(attempt.completedAt?.take(10) ?: "Unfinished · your ink is saved", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, if (done) "Open handwriting" else "Continue review")
                        }
                    }
                }
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { if (!working) showDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete this card?") },
            text = { Text("“${mistake.question}” leaves your review list on all devices. Your handwriting on this device is kept.") },
            dismissButton = { TextButton({ showDelete = false }, enabled = !working, shapes = ButtonDefaults.shapes()) { Text("Keep") } },
            confirmButton = {
                Button(
                    { showDelete = false; onDelete() },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Delete card") }
            },
        )
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

@Composable internal fun QuestionContent(m: ExamTrackMistake, context: ExamContext?, user: String, attachments: MistakeAttachmentRepository, textScale: Float = 1f) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        context?.let {
            val crumb = listOf(it.subject, it.title, it.paper).filter(String::isNotBlank).joinToString(" · ")
            if (crumb.isNotBlank()) Text(crumb, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(m.question, style = MaterialTheme.typography.headlineSmall.scaledBy(textScale), fontFamily = FontFamily.Serif)
        if (!m.questionText.isNullOrBlank()) {
            RichText(m.questionText!!, style = MaterialTheme.typography.bodyLarge.scaledBy(textScale))
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
    var expanded by remember { mutableStateOf(false) }
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Enlarge attachment") { expanded = true }
                )
                Text(
                    "${attachment.name} · Tap to enlarge", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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
                TextButton({ retry++ }, shapes = ButtonDefaults.shapes()) { Text("Retry") }
            }
            else -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoadingIndicator(Modifier.size(20.dp))
                Text("Loading ${attachment.name}…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (expanded && file != null) AttachmentViewer(file!!, attachment.name, loader) { expanded = false }
}

@Composable private fun AttachmentViewer(file: File, name: String, loader: ImageLoader, onClose: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Close attachment") }
                    Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton({ zoom = 1f; offset = Offset.Zero }, shapes = ButtonDefaults.shapes()) { Text("Reset") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp)).pointerInput(Unit) {
                    detectTransformGestures { _, pan, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 5f)
                        val maxX = size.width * (zoom - 1f) / 2f
                        val maxY = size.height * (zoom - 1f) / 2f
                        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                    }
                }, contentAlignment = Alignment.Center) {
                    AsyncImage(file, name, imageLoader = loader, modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                    })
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ zoom = (zoom - .5f).coerceAtLeast(1f); offset = Offset.Zero }, shapes = ButtonDefaults.shapes()) { Text("Zoom out") }
                    Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    TextButton({ zoom = (zoom + .5f).coerceAtMost(5f) }, shapes = ButtonDefaults.shapes()) { Text("Zoom in") }
                }
            }
        }
    }
}
