@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.music

import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.folio.notes.EditorGlassSurface
import com.folio.notes.FolioExpand
import com.folio.notes.FolioPopover
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.FolioToolToggle
import com.folio.notes.PopoverGroup
import com.folio.notes.PopoverRow
import com.folio.notes.PopoverTile
import com.folio.notes.guardUiTouches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Music's one pencil colour: a rehearsal blue that reads on any engraving. */
internal val MusicPencil = Color(0xFF154DB4)

private enum class ReaderPopover { MARKS, METRONOME, PAGES, MORE }

/**
 * The reader wears the editor's chrome: glass docks floating on the editor's desk colour, with
 * anchored popovers in place of dialogs, so a score feels like a page in Folio rather than a
 * separate viewer. Performance mode drops all of it and leaves only the page.
 */
@Composable internal fun MusicReader(score: MusicScore, model: MusicViewModel, onBack: () -> Unit,
    setLabel: String?, next: MusicScore?, onNextScore: () -> Unit,
    onDetails: () -> Unit, onExport: () -> Unit, onExtract: () -> Unit) {
    var page by rememberSaveable(score.id) { mutableIntStateOf(score.page) }
    var performance by rememberSaveable { mutableStateOf(false) }
    var spread by rememberSaveable { mutableStateOf(false) }
    var pencil by rememberSaveable { mutableStateOf(false) }
    var popover by remember { mutableStateOf<ReaderPopover?>(null) }
    var running by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val focus = remember { FocusRequester() }
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous; model.pageCache.clear() }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) running = false }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(score.id) { focus.requestFocus(); running = false }
    // Visual beat is always available; audio is explicitly enabled and released on leaving.
    LaunchedEffect(running, score.bpm, score.beats, sound) {
        if (!running) { beat = 0; return@LaunchedEffect }
        val tone = if (sound) runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 65) }.getOrNull() else null
        try {
            val period = 60_000L / score.bpm
            var deadline = SystemClock.elapsedRealtime()
            var tick = 0
            while (true) {
                beat = tick % score.beats + 1
                tone?.startTone(if (beat == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 45)
                tick++
                deadline += period
                delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }
        } finally { tone?.release() }
    }
    BackHandler { if (performance) performance = false else onBack() }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        val twoPages = spread && maxWidth > maxHeight
        val narrow = maxWidth < 600.dp
        val step = if (twoPages) 2 else 1
        fun go(target: Int) {
            val next = target.coerceIn(0, score.pages - 1)
            if (next != page) { page = next; model.score(score.id) { it.copy(page = next) } }
        }
        val last = page + step >= score.pages
        val visible = page until minOf(page + step, score.pages)
        val pageLabel = if (twoPages && page + 1 < score.pages) "${page + 1}–${page + 2} of ${score.pages}" else "${page + 1} of ${score.pages}"
        val visibleInk = score.ink.any { it.page in visible }
        fun close() { popover = null; focus.requestFocus() }
        @Composable fun Anchored(kind: ReaderPopover, width: androidx.compose.ui.unit.Dp = 320.dp, content: @Composable ColumnScope.() -> Unit) {
            if (popover == kind) FolioPopover(::close, width = width, content = content)
        }
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val direction = when (key.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> 1
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
                else -> 0
            }
            if (direction == 0 || popover != null) false else {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) go(page + step * direction)
                true
            }
        }.focusRequester(focus).focusable()) {
            FolioExpand(!performance) {
                Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp8),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    EditorGlassSurface(Modifier.weight(1f, fill = false)) {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                            ReaderButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Music", onClick = onBack)
                            if (!narrow) Column(Modifier.padding(start = FolioSpacing.dp4, end = FolioSpacing.dp12)) {
                                Text(score.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(setLabel ?: listOf(score.composer, score.part).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Page $pageLabel" },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    // The tool pill: the same toggle the editor's pens use, with undo beside it.
                    EditorGlassSurface {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                            FolioToolToggle(pencil, { pencil = !pencil }, Icons.Rounded.Edit, if (pencil) "Stop drawing" else "Pencil", indicatorColor = MusicPencil)
                            ReaderButton(Icons.AutoMirrored.Rounded.Undo, "Undo pencil on this page", enabled = visibleInk) {
                                model.score(score.id) { s ->
                                    val index = s.ink.indexOfLast { it.page in visible }
                                    s.copy(ink = s.ink.filterIndexed { i, _ -> i != index })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    EditorGlassSurface {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                ReaderButton(Icons.Rounded.Bookmarks, "Rehearsal marks") { popover = ReaderPopover.MARKS }
                                Anchored(ReaderPopover.MARKS) { MarksPopover(score, page, visible, model) { go(it); close() } }
                            }
                            Box {
                                if (running) BeatPill(score.bpm, score.beats, beat) { popover = ReaderPopover.METRONOME }
                                else ReaderButton(Icons.Rounded.Speed, "Metronome") { popover = ReaderPopover.METRONOME }
                                Anchored(ReaderPopover.METRONOME) {
                                    MetronomePopover(score, model, running, { running = it }, sound, { sound = it }, beat)
                                }
                            }
                            if (!narrow) ReaderButton(Icons.Rounded.AutoStories, if (spread) "Single pages" else "Two pages in landscape", active = spread) { spread = !spread }
                            ReaderButton(Icons.Rounded.Fullscreen, "Performance mode") { pencil = false; popover = null; performance = true; focus.requestFocus() }
                            Box {
                                ReaderButton(Icons.Rounded.MoreVert, "Score options") { popover = ReaderPopover.MORE }
                                Anchored(ReaderPopover.MORE) {
                                    Text(score.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(score.composer.ifBlank { null }, score.part.ifBlank { null }, setLabel).joinToString(" · ").ifEmpty { "${score.pages} pages" },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                        PopoverTile(Icons.Rounded.EditNote, "Details", Modifier.weight(1f)) { close(); onDetails() }
                                        PopoverTile(if (score.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite", Modifier.weight(1f), active = score.starred) {
                                            model.score(score.id) { it.copy(starred = !it.starred) }
                                        }
                                        PopoverTile(Icons.Rounded.IosShare, "Export", Modifier.weight(1f)) { close(); onExport() }
                                    }
                                    if (score.notes.isNotBlank()) PopoverGroup("Rehearsal notes") {
                                        Text(score.notes, Modifier.padding(horizontal = FolioSpacing.dp8), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Column {
                                        if (narrow) PopoverRow(Icons.Rounded.AutoStories, if (spread) "Single pages" else "Two pages in landscape") { spread = !spread; close() }
                                        PopoverRow(Icons.Rounded.ContentCut, "Extract instrument parts") { close(); onExtract() }
                                    }
                                    HorizontalDivider()
                                    Text("Tap the left or right of a page to turn it. Pinch to zoom, double-tap to fit. Arrow, Space and Page keys work with page-turn pedals.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                for (index in visible) key(score.id, index) {
                    MusicPage(model.store.pdf(score.id), index, score.pages, model.pageCache, score.ink.filter { it.page == index }, pencil && !performance, performance,
                        onStroke = { points -> model.score(score.id) { it.copy(ink = it.ink + MusicStroke(index, points)) } },
                        onTurn = { forward -> go(page + if (forward) step else -step) }, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
            FolioExpand(!performance) {
                Box(Modifier.fillMaxWidth().padding(top = FolioSpacing.dp4, bottom = FolioSpacing.dp8), contentAlignment = Alignment.Center) {
                    // Turn buttons stay at a performer's 48dp even inside the slimmer glass dock.
                    EditorGlassSurface(Modifier.height(56.dp)) {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            FilledTonalIconButton({ go(page - step) }, modifier = Modifier.size(48.dp), enabled = page > 0, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page")
                            }
                            Box {
                                TextButton({ popover = ReaderPopover.PAGES }, shapes = ButtonDefaults.shapes()) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Page $pageLabel", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                        if (pencil) Text("Pencil · turn with the arrows", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        else if (setLabel != null) Text(setLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                                    }
                                }
                                Anchored(ReaderPopover.PAGES) { PagesPopover(score, visible) { go(it); close() } }
                            }
                            if (last && next != null) NextScoreButton(next, onNextScore)
                            else FilledTonalIconButton({ go(page + step) }, modifier = Modifier.size(48.dp), enabled = !last, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page")
                            }
                        }
                    }
                }
            }
        }
        if (performance) {
            // On stage only the page matters: a quiet way out, where we are, and the next piece.
            FilledTonalIconButton({ performance = false; focus.requestFocus() }, modifier = Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp12).guardUiTouches(),
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .72f))) {
                Icon(Icons.Rounded.FullscreenExit, "Exit performance mode")
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .72f)) {
                    Row(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp6), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        if (running) BeatDots(score.beats, beat)
                        Text(pageLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (last && next != null) NextScoreButton(next, onNextScore)
            }
        }
    }
}

@Composable private fun NextScoreButton(next: MusicScore, onClick: () -> Unit) {
    Button(onClick, modifier = Modifier.heightIn(min = 48.dp).guardUiTouches(), shapes = ButtonDefaults.shapes()) {
        Text("Next: ${next.title}", Modifier.widthIn(max = 220.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(FolioSpacing.dp8)); Icon(Icons.Rounded.SkipNext, null)
    }
}

/** A dock button that matches the editor's: 40dp, tooltip, and a tonal fill when [active]. */
@Composable private fun ReaderButton(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, modifier = Modifier.size(40.dp), enabled = enabled,
            colors = if (active) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                else IconButtonDefaults.iconButtonColors()) {
            Icon(icon, label, Modifier.size(20.dp))
        }
    }
}

/** While the metronome runs it replaces its own button, so the beat is visible without a popover. */
@Composable private fun BeatPill(bpm: Int, beats: Int, beat: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.height(36.dp)) {
        Row(Modifier.padding(horizontal = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            BeatDots(beats, beat)
            Text("$bpm", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable private fun BeatDots(beats: Int, beat: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..beats) {
            val on = i == beat
            val color by animateColorAsState(when {
                on && i == 1 -> MaterialTheme.colorScheme.primary
                on -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.outlineVariant
            }, label = "beat")
            Box(Modifier.size(if (on) 8.dp else 6.dp).background(color, CircleShape))
        }
    }
}

@Composable private fun MarksPopover(score: MusicScore, page: Int, visible: IntRange, model: MusicViewModel, go: (Int) -> Unit) {
    var name by rememberSaveable(score.id, page) { mutableStateOf("") }
    fun add() { if (name.isNotBlank()) { model.score(score.id) { it.copy(marks = it.marks + MusicMark(page, name.trim())) }; name = "" } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.Bookmarks, null, Modifier.size(20.dp))
        Text("Rehearsal marks", style = MaterialTheme.typography.titleMedium)
    }
    if (score.marks.isEmpty()) Text("Mark movements, codas and rehearsal letters to jump straight back to them.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
        score.marks.sortedBy { it.page }.forEach { mark ->
            val here = mark.page in visible
            Surface(onClick = { go(mark.page) }, shape = FolioShapes.medium,
                color = if (here) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Text(mark.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("p. ${mark.page + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton({ model.score(score.id) { it.copy(marks = it.marks - mark) } }, Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, "Remove ${mark.name}", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, shape = FolioShapes.medium,
            placeholder = { Text("Mark page ${page + 1}, e.g. Coda") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
        FilledTonalIconButton(::add, enabled = name.isNotBlank(), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Add rehearsal mark") }
    }
}

@Composable private fun MetronomePopover(score: MusicScore, model: MusicViewModel, running: Boolean, onRunning: (Boolean) -> Unit,
    sound: Boolean, onSound: (Boolean) -> Unit, beat: Int) {
    var bpm by remember(score.bpm) { mutableFloatStateOf(score.bpm.toFloat()) }
    var lastTap by remember { mutableLongStateOf(0L) }
    fun tempo(value: Int) { val clamped = value.coerceIn(30, 240); bpm = clamped.toFloat(); model.score(score.id) { it.copy(bpm = clamped) } }
    fun meter(value: Int) { model.score(score.id) { it.copy(beats = value.coerceIn(1, 12)) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.Speed, null, Modifier.size(20.dp))
        Text("Metronome", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        BeatDots(score.beats, beat)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        FilledTonalIconButton({ tempo(bpm.roundToInt() - 1) }, enabled = bpm > 30, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Remove, "Slower") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${bpm.roundToInt()}", style = MaterialTheme.typography.displaySmall)
            Text("beats per minute", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton({ tempo(bpm.roundToInt() + 1) }, enabled = bpm < 240, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Faster") }
    }
    Slider(bpm, { bpm = it }, valueRange = 30f..240f, onValueChangeFinished = { tempo(bpm.roundToInt()) })
    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        PopoverTile(Icons.Rounded.TouchApp, "Tap tempo", Modifier.weight(1f)) {
            val now = SystemClock.elapsedRealtime()
            if (lastTap > 0 && now - lastTap in 250..2000) tempo((60_000f / (now - lastTap)).roundToInt())
            lastTap = now
        }
        PopoverTile(if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            if (sound) "Click on" else "Silent", Modifier.weight(1f), active = sound) { onSound(!sound) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Beats per bar", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton({ meter(score.beats - 1) }, enabled = score.beats > 1, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Remove, "Fewer beats per bar") }
        Text("${score.beats}", Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton({ meter(score.beats + 1) }, enabled = score.beats < 12, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "More beats per bar") }
    }
    Button({ onRunning(!running) }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
        Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (running) "Stop" else "Start")
    }
    Text("Tempo and meter are saved with this score. The metronome stops when you leave the score or Folio.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Every page as a tile; pages with a rehearsal mark carry a dot, so codas are easy to find. */
@Composable private fun PagesPopover(score: MusicScore, visible: IntRange, go: (Int) -> Unit) {
    val marked = score.marks.map { it.page }.toSet()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.GridView, null, Modifier.size(20.dp))
        Text("Go to page", style = MaterialTheme.typography.titleMedium)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        for (index in 0 until score.pages) {
            val here = index in visible
            Surface(onClick = { go(index) }, shape = FolioShapes.medium, modifier = Modifier.size(44.dp),
                color = if (here) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (here) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                    if (index in marked) Box(Modifier.align(Alignment.TopEnd).padding(FolioSpacing.dp4).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
        }
    }
    if (marked.isNotEmpty()) Text("Dots mark pages with a rehearsal mark.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private data class MusicPageImage(val bitmap: Bitmap? = null, val error: String? = null)

@Composable internal fun MusicPage(file: File, page: Int, pageCount: Int, cache: MusicPageCache, strokes: List<MusicStroke>, pencil: Boolean, performance: Boolean,
    onStroke: (List<MusicPoint>) -> Unit, onTurn: (Boolean) -> Unit, modifier: Modifier) {
    var retry by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val width = (maxWidth.value * density).roundToInt().coerceIn(1, 2000)
        val result by produceState(MusicPageImage(), file, page, width, retry) {
            value = MusicPageImage()
            value = withContext(Dispatchers.IO) {
                try {
                    MusicPageImage(cache.render(file, page, width))
                } catch (e: Exception) { MusicPageImage(error = e.message ?: "Could not render page") }
            }
        }
        LaunchedEffect(result.bitmap, file, page, width) {
            if (result.bitmap != null) withContext(Dispatchers.IO) {
                for (next in page + 1..minOf(page + 2, pageCount - 1)) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    runCatching { cache.render(file, next, width) }
                }
            }
        }

        val bitmap = result.bitmap
        if (bitmap != null) {
            val ratio = bitmap.width.toFloat() / bitmap.height
            val w = minOf(maxWidth, maxHeight * ratio)
            val h = w / ratio
            var scale by remember(file, page, pencil, performance) { mutableFloatStateOf(1f) }
            var offset by remember(file, page, pencil, performance) { mutableStateOf(Offset.Zero) }
            val transform = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                val boundX = w.value * density * (scale - 1) / 2
                val boundY = h.value * density * (scale - 1) / 2
                offset = Offset((offset.x + pan.x).coerceIn(-boundX, boundX), (offset.y + pan.y).coerceIn(-boundY, boundY))
            }
            val turn by rememberUpdatedState(onTurn)
            val commit by rememberUpdatedState(onStroke)
            var points by remember(file, page, pencil) { mutableStateOf(emptyList<MusicPoint>()) }
            Box(Modifier.size(w, h).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .shadow(2.dp, FolioShapes.hairline).background(Color.White)
                .then(if (performance) Modifier.pointerInput(file, page) {
                    // Stage taps must not wait for the double-tap timeout or start a pan.
                    detectTapGestures(onTap = { turn(it.x >= size.width / 2f) })
                } else if (!pencil) Modifier.transformable(transform).pointerInput(file, page) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }, onTap = { if (scale == 1f) turn(it.x >= size.width / 2f) })
                } else Modifier.pointerInput(file, page) {
                    fun point(p: Offset) = MusicPoint((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
                    detectDragGestures(onDragStart = { points = listOf(point(it)) }, onDrag = { change, _ ->
                        change.consume(); points = points + point(change.position)
                    }, onDragEnd = { if (points.isNotEmpty()) commit(points); points = emptyList() }, onDragCancel = { points = emptyList() })
                })) {
                Image(bitmap.asImageBitmap(), "Score page ${page + 1}", Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    for (line in strokes.map { it.points } + listOf(points)) {
                        if (line.isEmpty()) continue
                        if (line.size == 1) drawCircle(MusicPencil, radius = size.width * .0015f, center = Offset(line[0].x * size.width, line[0].y * size.height))
                        else {
                            val path = Path().apply { moveTo(line[0].x * size.width, line[0].y * size.height)
                                line.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) } }
                            drawPath(path, MusicPencil, style = Stroke(width = size.width * .003f, cap = StrokeCap.Round))
                        }
                    }
                }
            }
        } else if (result.error != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(Icons.Rounded.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Page ${page + 1} couldn't be drawn", style = MaterialTheme.typography.bodyMedium)
            TextButton({ retry++ }, shapes = ButtonDefaults.shapes()) { Text("Retry") }
        } else LoadingIndicator()
    }
}
