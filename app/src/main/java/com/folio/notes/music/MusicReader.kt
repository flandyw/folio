@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.music

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.folio.notes.library.semanticsLabel
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.EditorScreen
import com.folio.notes.editor.toolbar.FolioPopover
import com.folio.notes.input.guardUiTouches
import com.folio.notes.ui.FolioPopover
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.PopoverGroup
import com.folio.notes.ui.PopoverRow
import com.folio.notes.ui.PopoverTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private enum class ReaderPopover { MARKS, METRONOME, PAGES, MORE, ORDER, JUMPS, READING }

/**
 * The music reader is the real editor. A score is a hidden notebook (its PDF as ordinary pages) shown
 * by `EditorScreen` in its music layout: the editor's own pens, highlighter, eraser, shapes, text,
 * lasso, layers, undo and journal, on a window of one or two fitted sheets. What is music's own is the
 * chrome laid over it — two floating rails (back, rehearsal marks, metronome, page turns, score
 * options, performance mode), the reading light, page-turn keys — and the reading position, tempo and
 * marks, which stay in the music index. Marking and sticky notes are left out of the strip: a marking
 * bar belongs to a marked response, and a score has no space beside it for a note.
 */
@Composable internal fun MusicReader(score: MusicScore, model: MusicViewModel, folio: FolioViewModel, folioState: FolioState,
    finger: Boolean, haptics: Boolean, shapeRecognition: Boolean, onBack: () -> Unit,
    initialPage: Int, performance: Boolean, onPerformance: (Boolean) -> Unit,
    runningOrder: List<MusicScore>, onChooseScore: (MusicScore) -> Unit,
    setLabel: String?, next: MusicScore?, onNextScore: () -> Unit,
    onDetails: () -> Unit, onExport: () -> Unit, onExtract: () -> Unit, onSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val appPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    val latest by rememberUpdatedState(score)
    // The notebook that carries this score; the editor only shows once it is the open document.
    var notebookId by remember(score.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(score.id) {
        val opened = folio.openMusicScore(latest, model.store.pdf(score.id))
        if (opened == null) { onBack(); return@LaunchedEffect }
        notebookId = opened
        // The marks an older Folio kept in the index now live on the pages: empty the index only after that.
        val current = latest
        if (MusicNotebook.hasLegacyInk(current) || current.seed != null) {
            model.score(score.id) { it.copy(ink = emptyList(), texts = emptyList(), seed = null) }
        }
    }
    val closing by rememberUpdatedState(notebookId)
    DisposableEffect(score.id) { onDispose { closing?.let(folio::closeMusicScore) } }
    val note = folioState.active?.takeIf { it.musicScoreId == score.id }
    // The shelf and the page grid count pencil marks from the score's index; keep it in step with the pages.
    LaunchedEffect(note?.pages) {
        val pages = note?.pages ?: return@LaunchedEffect
        val counts = MusicNotebook.counts(pages)
        val merged = pages.indices.map { i -> if (pages[i].loaded) counts[i] else latest.pencilOn(i) }
        delay(600)
        if (merged != latest.pencil.let { stored -> merged.indices.map { stored.getOrNull(it) ?: 0 } })
            model.score(score.id) { it.copy(pencil = merged) }
    }
    // Reader comfort: how far the sheet is dimmed for a dark pit, and whether the tool strip shows.
    var dim by remember { mutableFloatStateOf(AppPrefs.musicDim(appPrefs.getFloat(AppPrefs.MUSIC_DIM, AppPrefs.DEFAULT_MUSIC_DIM))) }
    var showToolbar by remember { mutableStateOf(appPrefs.getBoolean(AppPrefs.MUSIC_TOOLBAR, AppPrefs.DEFAULT_MUSIC_TOOLBAR)) }
    fun setDim(value: Float) { dim = AppPrefs.musicDim(value); appPrefs.edit().putFloat(AppPrefs.MUSIC_DIM, dim).apply() }
    fun setShowToolbar(value: Boolean) { showToolbar = value; appPrefs.edit().putBoolean(AppPrefs.MUSIC_TOOLBAR, value).apply() }
    // The metronome remembers its click, downbeat accent, subdivision and count-in between scores.
    var accent by remember { mutableStateOf(appPrefs.getBoolean(AppPrefs.MUSIC_ACCENT, AppPrefs.DEFAULT_MUSIC_ACCENT)) }
    var subdivision by remember { mutableIntStateOf(AppPrefs.musicSubdivision(appPrefs.getInt(AppPrefs.MUSIC_SUBDIVISION, AppPrefs.DEFAULT_MUSIC_SUBDIVISION))) }
    var countInBars by remember { mutableIntStateOf(AppPrefs.musicCountInBars(appPrefs.getInt(AppPrefs.MUSIC_COUNT_IN_BARS,
        if (appPrefs.getBoolean(AppPrefs.MUSIC_COUNT_IN, false)) 1 else AppPrefs.DEFAULT_MUSIC_COUNT_IN_BARS))) }
    var voice by remember { mutableStateOf(AppPrefs.musicVoice(appPrefs.getString(AppPrefs.MUSIC_VOICE, AppPrefs.DEFAULT_MUSIC_VOICE))) }
    var volume by remember { mutableFloatStateOf(AppPrefs.musicVolume(appPrefs.getFloat(AppPrefs.MUSIC_VOLUME, AppPrefs.DEFAULT_MUSIC_VOLUME))) }
    fun setAccent(value: Boolean) { accent = value; appPrefs.edit().putBoolean(AppPrefs.MUSIC_ACCENT, value).apply() }
    fun setSubdivision(value: Int) { subdivision = AppPrefs.musicSubdivision(value); appPrefs.edit().putInt(AppPrefs.MUSIC_SUBDIVISION, subdivision).apply() }
    fun setCountInBars(value: Int) { countInBars = AppPrefs.musicCountInBars(value); appPrefs.edit().putInt(AppPrefs.MUSIC_COUNT_IN_BARS, countInBars).apply() }
    fun setVoice(value: MetronomeVoice) { voice = value; appPrefs.edit().putString(AppPrefs.MUSIC_VOICE, value.name).apply() }
    fun setVolume(value: Float) { volume = AppPrefs.musicVolume(value); appPrefs.edit().putFloat(AppPrefs.MUSIC_VOLUME, volume).apply() }
    // The first page on view; the editor's own current page is whichever page in the window was last written on.
    var start by rememberSaveable(score.id) { mutableIntStateOf(initialPage.coerceIn(0, score.pages - 1)) }
    var returnPage by rememberSaveable(score.id) { mutableStateOf<Int?>(null) }
    var autoTurning by remember { mutableStateOf(false) }
    var turnCountdown by remember { mutableIntStateOf(0) }
    var popover by remember { mutableStateOf<ReaderPopover?>(null) }
    var running by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(appPrefs.getBoolean(AppPrefs.MUSIC_CLICK, AppPrefs.DEFAULT_MUSIC_CLICK)) }
    fun setSound(value: Boolean) { sound = value; appPrefs.edit().putBoolean(AppPrefs.MUSIC_CLICK, value).apply() }
    var beat by remember { mutableIntStateOf(0) }
    var leadBars by remember { mutableIntStateOf(0) }
    var pendingCountIn by remember { mutableStateOf(false) }
    var audioError by remember { mutableStateOf<String?>(null) }
    fun setRunning(value: Boolean) { pendingCountIn = value; audioError = null; running = value }
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous; model.pageCache.clear() }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { running = false; autoTurning = false } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(score.id) { running = false }
    LaunchedEffect(running, score.bpm, score.beats, score.beatAccents, sound, accent, subdivision, voice, volume) {
        if (!running) { beat = 0; leadBars = 0; return@LaunchedEffect }
        val rhythm = MetronomeRhythm(score.bpm, score.beats, subdivision,
            if (pendingCountIn) countInBars else 0, score.beatAccents, accent)
        pendingCountIn = false
        audioError = null
        fun position(value: MetronomePosition) { beat = value.beat; leadBars = value.countInBars }
        if (sound) {
            try {
                playMetronome(context, rhythm, voice, volume, onFocusLost = { running = false }, onPosition = ::position)
                return@LaunchedEffect
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { audioError = e.message ?: "Audio could not start" }
        }
        // Silent operation and an unavailable audio device retain the same bar/count-in rules.
        val began = SystemClock.elapsedRealtime()
        while (true) {
            position(rhythm.position((SystemClock.elapsedRealtime() - began) * MetronomeRhythm.SAMPLE_RATE / 1000))
            delay(16)
        }
    }
    BackHandler(enabled = performance) { autoTurning = false; onPerformance(false) }
    BackHandler(enabled = !performance) { if (popover != null) popover = null else onBack() }
    if (note == null) {
        // The score's notebook is being opened; show the desk, not a flash of the library.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Landscape can lay two pages side by side (never more); portrait always shows one.
        val landscape = maxWidth > maxHeight
        val step = if (landscape && score.twoUp) 2 else 1
        val lastStart = maxOf(0, score.pages - step)
        // A light tick on every real turn, so a pedal or a tap confirms without looking down.
        val feedback = LocalHapticFeedback.current
        fun go(target: Int) {
            val following = target.coerceIn(0, lastStart)
            if (following != start) {
                start = following
                model.score(score.id) { it.copy(page = following) }
                feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            folio.selectPage(following)
        }
        // A saved position, a rotation or a page link can leave the window hanging past the end or off the page being edited.
        LaunchedEffect(step, note.id) { if (start > lastStart) go(lastStart) else folio.selectPage(folioState.pageIndex.coerceIn(start, minOf(start + step, score.pages) - 1)) }
        LaunchedEffect(folioState.pageIndex) {
            val index = folioState.pageIndex
            if (index < start || index >= start + step) go(index.coerceAtMost(lastStart))
        }
        val last = start >= lastStart
        val visible = start until minOf(start + step, score.pages)
        val pageLabel = if (step > 1 && start + 1 < score.pages) "${start + 1}–${minOf(start + step, score.pages)} of ${score.pages}" else "${start + 1} of ${score.pages}"
        val annotated = visible.any { score.pencilOn(it) > 0 }
        fun close() { popover = null }
        fun turn(forward: Boolean) {
            val target = musicTurnTarget(start, score.pages, step, forward, score.overlapTurns)
            if (target == null) { if (next != null) onNextScore() } else go(target)
        }
        LaunchedEffect(autoTurning, performance, start, step, landscape, score.turnSeconds, score.overlapTurns) {
            turnCountdown = 0
            if (!autoTurning) return@LaunchedEffect
            if (!performance || last) { autoTurning = false; return@LaunchedEffect }
            val deadline = SystemClock.elapsedRealtime() + musicTurnSeconds(score.turnSeconds) * 1000L
            while (true) {
                val now = SystemClock.elapsedRealtime()
                turnCountdown = musicTurnCountdown(deadline, now)
                if (turnCountdown == 0) break
                delay(minOf(1000L, deadline - now))
            }
            // A timed turn never starts another piece or continues behind another app.
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                musicTurnTarget(start, score.pages, step, true, score.overlapTurns)?.let(::go)
            } else autoTurning = false
        }
        fun jump(target: Int) { returnPage = start; go(target) }
        fun onKey(event: KeyEvent): Boolean {
            if (popover != null) return false
            // Home/End jump to the ends of the score, matching what pedal users expect of a viewer.
            if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
            val forward = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> true
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> false
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> null
                else -> return false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (forward != null) turn(forward)
                else go(if (event.keyCode == KeyEvent.KEYCODE_MOVE_HOME) 0 else score.pages - 1)
            }
            return true
        }
        val stage = MusicStage(
            start = start, step = step, performance = performance, showToolbar = showToolbar,
            onTurn = ::turn,
            onKey = ::onKey,
            chrome = { insets ->
                @Composable fun Anchored(kind: ReaderPopover, width: androidx.compose.ui.unit.Dp = 320.dp, content: @Composable ColumnScope.() -> Unit) {
                    if (popover == kind) FolioPopover(::close, width = width, content = content)
                }
                // Night reading: a plain scrim over the desk only. It carries no pointer input, so every
                // tap and stroke still reaches the page underneath.
                if (dim > 0f) Box(Modifier.matchParentSize().zIndex(10f).background(Color.Black.copy(alpha = dim)))
                val shortcuts = score.jumps.filter { it.from in visible }
                if (shortcuts.isNotEmpty() || returnPage != null) {
                    Row(Modifier.align(Alignment.BottomStart).zIndex(12f).fillMaxWidth()
                        .padding(start = insets.side, end = insets.side, bottom = if (performance) 68.dp else FolioSpacing.dp8)
                        .guardUiTouches().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        returnPage?.let { target ->
                            FilledTonalButton({ go(target); returnPage = null }, shapes = ButtonDefaults.shapes()) { Text("Return to p. ${target + 1}") }
                        }
                        shortcuts.forEach { shortcut ->
                            FilledTonalButton({ jump(shortcut.to) }, shapes = ButtonDefaults.shapes()) {
                                Text("${shortcut.name} → p. ${shortcut.to + 1}", Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (!performance) {
                    Box(Modifier.align(Alignment.CenterStart).zIndex(11f).padding(start = FolioSpacing.dp6, top = insets.top, bottom = insets.bottom)) {
                        FloatingRail {
                            ReaderButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Music", onClick = onBack)
                            Box {
                                ReaderButton(Icons.Rounded.Bookmarks, "Rehearsal marks") { popover = ReaderPopover.MARKS }
                                Anchored(ReaderPopover.MARKS) { MarksPopover(score, start, visible, model) { go(it); close() } }
                            }
                            Box {
                                if (running) BeatButton(score.bpm, beat, metronomeBeatStrength(beat - 1, if (leadBars > 0) emptyList() else score.beatAccents, leadBars > 0 || accent) == 2) { popover = ReaderPopover.METRONOME }
                                else ReaderButton(Icons.Rounded.Speed, "Metronome") { popover = ReaderPopover.METRONOME }
                                Anchored(ReaderPopover.METRONOME) {
                                    MetronomePopover(score, model, running, ::setRunning, sound, ::setSound, beat,
                                        accent, ::setAccent, subdivision, ::setSubdivision, countInBars, ::setCountInBars,
                                        voice, ::setVoice, volume, ::setVolume, leadBars, audioError)
                                }
                            }
                            FilledTonalIconButton({ turn(false) }, modifier = Modifier.size(48.dp), enabled = start > 0, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous page")
                            }
                        }
                    }
                    Box(Modifier.align(Alignment.CenterEnd).zIndex(11f).padding(end = FolioSpacing.dp6, top = insets.top, bottom = insets.bottom)) {
                        FloatingRail {
                            Box {
                                ReaderButton(Icons.Rounded.MoreVert, "Score options") { popover = ReaderPopover.MORE }
                                Anchored(ReaderPopover.READING) {
                                    ReadingPopover(score, model, canStart = !last) {
                                        close(); autoTurning = true; onPerformance(true)
                                    }
                                }
                                Anchored(ReaderPopover.MORE) {
                                    Text(score.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(score.composer.ifBlank { null }, score.part.ifBlank { null }, setLabel,
                                        com.folio.notes.model.countOf(score.pages, "page"), if (score.annotationCount() > 0) com.folio.notes.model.countOf(score.annotationCount(), "pencil mark") else null).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                        PopoverTile(Icons.Rounded.EditNote, "Details", Modifier.weight(1f)) { close(); onDetails() }
                                        PopoverTile(if (score.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, "Favourite", Modifier.weight(1f), active = score.starred) {
                                            model.score(score.id) { it.copy(starred = !it.starred) }
                                        }
                                        PopoverTile(Icons.Rounded.IosShare, "Export", Modifier.weight(1f)) { close(); onExport() }
                                    }
                                    if (score.notes.isNotBlank()) PopoverGroup("Rehearsal notes") {
                                        Text(score.notes, Modifier.padding(horizontal = FolioSpacing.dp8), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Column {
                                        PopoverRow(Icons.Rounded.AutoStories, "Reading and timed turns") { popover = ReaderPopover.READING }
                                        PopoverRow(if (showToolbar) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showToolbar) "Hide the tool strip" else "Show the tool strip") { setShowToolbar(!showToolbar); close() }
                                        PopoverRow(Icons.Rounded.ContentCut, "Extract instrument parts") { close(); onExtract() }
                                        PopoverRow(Icons.Rounded.Tune, "App settings") { close(); onSettings() }
                                        if (annotated) PopoverRow(Icons.Rounded.DeleteSweep, "Clear annotations on these pages") {
                                            close()
                                            folio.clearPagesInk(note.id, visible.mapNotNull { note.pages.getOrNull(it)?.id })
                                        }
                                        if (score.annotationCount() > 0) PopoverRow(Icons.Rounded.DeleteForever, "Clear every annotation in this score", destructive = true) {
                                            close()
                                            folio.clearPagesInk(note.id, note.pages.map { it.id })
                                        }
                                    }
                                    PopoverGroup("Reading light") {
                                        PopoverRow(if (dim > 0f) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (dim > 0f) "Stop dimming the page" else "Dim the page for a dark pit") { setDim(if (dim > 0f) 0f else .35f) }
                                        if (dim > 0f) Slider(dim, ::setDim, valueRange = 0f..AppPrefs.MUSIC_DIM_MAX, modifier = Modifier.semanticsLabel("Score dimming"))
                                    }
                                    HorizontalDivider()
                                    Text("Tap the left or right of a page to turn it, or use the arrows. Pinch to zoom, double-tap to fit. Arrow, Space and Page keys work with page-turn pedals; Home/End jump to the ends. In landscape you can show two pages side by side. The pens, highlighter, eraser, shapes, text and lasso are the editor's own and all work on a score.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Box {
                                ReaderButton(Icons.Rounded.Repeat, "Repeats and codas") { popover = ReaderPopover.JUMPS }
                                Anchored(ReaderPopover.JUMPS) { JumpsPopover(score, start, model) { jump(it); close() } }
                            }
                            if (runningOrder.isNotEmpty()) Box {
                                ReaderButton(Icons.Rounded.FormatListNumbered, "Running order") { popover = ReaderPopover.ORDER }
                                Anchored(ReaderPopover.ORDER) {
                                    Text(setLabel.orEmpty(), style = MaterialTheme.typography.titleMedium)
                                    runningOrder.forEachIndexed { index, piece ->
                                        PopoverRow(Icons.Rounded.MusicNote, "${index + 1}. ${piece.displayTitle()}",
                                            selected = piece.id == score.id) { close(); if (piece.id != score.id) onChooseScore(piece) }
                                    }
                                }
                            }
                            ReaderButton(Icons.Rounded.Fullscreen, "Performance mode") { popover = null; onPerformance(true) }
                            Box {
                                // The position readout is also the way into the page grid.
                                TextButton({ popover = ReaderPopover.PAGES }, modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 40.dp), shapes = ButtonDefaults.shapes(),
                                    contentPadding = PaddingValues(horizontal = FolioSpacing.dp4)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(if (step > 1 && start + 1 < score.pages) "${start + 1}–${minOf(start + step, score.pages)}" else "${start + 1}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                        Text("of ${score.pages}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                                Anchored(ReaderPopover.PAGES) { PagesPopover(score, visible) { go(it); close() } }
                            }
                            if (last && next != null) FilledTonalIconButton(onNextScore, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.SkipNext, "Next: ${next.title}")
                            } else FilledTonalIconButton({ turn(true) }, modifier = Modifier.size(48.dp), enabled = !last, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next page")
                            }
                        }
                    }
                } else {
                    // On stage only the page matters: a quiet way out, where we are, and the next piece.
                    FilledTonalIconButton({ autoTurning = false; onPerformance(false) }, modifier = Modifier.align(Alignment.TopEnd).zIndex(12f).padding(FolioSpacing.dp12).guardUiTouches(),
                        shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .72f))) {
                        Icon(Icons.Rounded.FullscreenExit, "Exit performance mode")
                    }
                    Row(Modifier.align(Alignment.BottomCenter).zIndex(12f).padding(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .72f)) {
                            Row(Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp6), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                if (running) {
                                    BeatDots(score.beats, beat, if (leadBars > 0) emptyList() else score.beatAccents, leadBars > 0 || accent)
                                    IconButton({ setRunning(false) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Stop, "Stop metronome", Modifier.size(18.dp)) }
                                    if (leadBars > 0) Text("Count in · $leadBars", style = MaterialTheme.typography.labelMedium)
                                }
                                Text(pageLabel, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (autoTurning) FilledTonalButton({ autoTurning = false }, modifier = Modifier.guardUiTouches(), shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(FolioSpacing.dp4))
                            Text("${turnCountdown}s", Modifier.semanticsLabel("Stop timed page turns, next turn in $turnCountdown seconds"))
                        }
                        if (last && next != null) NextScoreButton(next, onNextScore)
                    }
                }
            })
        EditorScreen(folioState, folio, finger, haptics, shapeRecognition, onSettings, onExport = {}, music = stage)
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

/** A vertical glass pill that floats at a screen edge, holding the reader's button controls. */
@Composable private fun FloatingRail(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.guardUiTouches(), shape = FolioShapes.panel, color = colors.surfaceContainerHigh.copy(alpha = .9f), contentColor = colors.onSurface,
        shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .35f))) {
        Column(Modifier.padding(FolioSpacing.dp4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2), content = content)
    }
}

/** The running metronome as a rail button: the tempo, flashing on the downbeat. */
@Composable private fun BeatButton(bpm: Int, beat: Int, accented: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(if (beat > 0 && accented) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, label = "beat")
    Surface(onClick = onClick, shape = CircleShape, color = container, contentColor = if (beat > 0 && accented) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Text("$bpm", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable private fun BeatDots(beats: Int, beat: Int, accents: List<Int>, accentFirst: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..beats) {
            val on = i == beat
            val strength = metronomeBeatStrength(i - 1, accents, accentFirst)
            val color by animateColorAsState(when {
                on && strength == 2 -> MaterialTheme.colorScheme.primary
                on && strength == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
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
    val previous = previousMark(page, score.marks)
    val following = nextMark(page, score.marks)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.Bookmarks, null, Modifier.size(20.dp))
        Text("Rehearsal marks", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        // Jump along the marks in order without opening the list again.
        FilledTonalIconButton({ previous?.let { go(it.page) } }, enabled = previous != null,
            shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous rehearsal mark", Modifier.size(18.dp))
        }
        FilledTonalIconButton({ following?.let { go(it.page) } }, enabled = following != null,
            shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next rehearsal mark", Modifier.size(18.dp))
        }
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
        OutlinedTextField(name, { name = it }, Modifier.weight(1f).semanticsLabel("Rehearsal mark name"), singleLine = true, shape = FolioShapes.medium,
            placeholder = { Text("Mark page ${page + 1}, e.g. Coda") },
            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
        FilledTonalIconButton(::add, enabled = name.isNotBlank(), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Add rehearsal mark") }
    }
}

@Composable private fun ReadingPopover(score: MusicScore, model: MusicViewModel, canStart: Boolean, start: () -> Unit) {
    var seconds by remember(score.id, score.turnSeconds) { mutableFloatStateOf(musicTurnSeconds(score.turnSeconds).toFloat()) }
    Text("Reading and timed turns", style = MaterialTheme.typography.titleMedium)
    PopoverGroup("Landscape layout") {
        PopoverRow(Icons.Rounded.Description, "One page", selected = !score.twoUp) {
            model.score(score.id) { it.copy(twoUp = false) }
        }
        PopoverRow(Icons.Rounded.AutoStories, "Two pages side by side", selected = score.twoUp) {
            model.score(score.id) { it.copy(twoUp = true) }
        }
        Text("Portrait always shows one page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (score.twoUp) PopoverGroup("Two-page turns") {
        PopoverRow(Icons.Rounded.NavigateNext, "Advance one page", selected = score.overlapTurns) {
            model.score(score.id) { it.copy(overlapTurns = true) }
        }
        PopoverRow(Icons.Rounded.SkipNext, "Advance both pages", selected = !score.overlapTurns) {
            model.score(score.id) { it.copy(overlapTurns = false) }
        }
        Text("Advancing one page keeps the right-hand sheet visible on the left after each turn.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    Text("Turn every ${seconds.roundToInt()} seconds", style = MaterialTheme.typography.titleSmall)
    Slider(seconds, { seconds = it }, valueRange = 5f..300f,
        modifier = Modifier.semanticsLabel("Seconds between page turns"),
        onValueChangeFinished = { model.score(score.id) { it.copy(turnSeconds = musicTurnSeconds(seconds.roundToInt())) } })
    Text("Start enters performance mode. Manual turns and repeat jumps restart the countdown. Timed turns stop at the final page, when you exit performance mode, or when you leave Folio.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(start, enabled = canStart, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
        Icon(Icons.Rounded.PlayArrow, null)
        Spacer(Modifier.width(FolioSpacing.dp8))
        Text("Start timed turns")
    }
    if (!canStart) Text("Timed turns need another page beyond this view.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun JumpsPopover(score: MusicScore, page: Int, model: MusicViewModel, go: (Int) -> Unit) {
    var name by rememberSaveable(score.id, page) { mutableStateOf("") }
    var destination by rememberSaveable(score.id, page) { mutableStateOf("") }
    val target = destination.toIntOrNull()?.minus(1)
    val valid = target != null && target in 0 until score.pages && target != page
    fun add() {
        if (!valid || name.isBlank()) return
        val jump = MusicJump(page, target!!, name.trim())
        model.score(score.id) { it.copy(jumps = (it.jumps + jump).distinct()) }
        name = ""; destination = ""
    }
    Text("Repeats and codas", style = MaterialTheme.typography.titleMedium)
    Text("Add a shortcut from page ${page + 1}. Tap its button when you reach a repeat, D.S. or coda; Return takes you back.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    score.jumps.sortedBy { it.from }.forEach { jump ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ go(jump.to) }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) {
                Text("${jump.name} · p. ${jump.from + 1} → ${jump.to + 1}", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ model.score(score.id) { it.copy(jumps = it.jumps - jump) } }) {
                Icon(Icons.Rounded.Close, "Remove ${jump.name}")
            }
        }
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Label, e.g. D.S. al Coda") }, keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences))
    OutlinedTextField(destination, { destination = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Destination page") }, isError = destination.isNotEmpty() && !valid,
        supportingText = { Text("Choose another page between 1 and ${score.pages}.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }))
    Button(::add, enabled = valid && name.isNotBlank(), modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("Add shortcut") }
}

@Composable private fun MetronomePopover(score: MusicScore, model: MusicViewModel, running: Boolean, onRunning: (Boolean) -> Unit,
    sound: Boolean, onSound: (Boolean) -> Unit, beat: Int,
    accent: Boolean, onAccent: (Boolean) -> Unit, subdivision: Int, onSubdivision: (Int) -> Unit,
    countInBars: Int, onCountInBars: (Int) -> Unit,
    voice: MetronomeVoice, onVoice: (MetronomeVoice) -> Unit, volume: Float, onVolume: (Float) -> Unit,
    leadBars: Int, audioError: String?) {
    var bpm by remember(score.bpm) { mutableFloatStateOf(score.bpm.toFloat()) }
    val taps = remember { MetronomeTaps() }
    fun tempo(value: Int) { val clamped = value.coerceIn(30, 240); bpm = clamped.toFloat(); model.score(score.id) { it.copy(bpm = clamped) } }
    fun meter(value: Int) { model.score(score.id) { it.copy(beats = value.coerceIn(1, 12), beatAccents = emptyList()) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.Speed, null, Modifier.size(20.dp))
        Text("Metronome", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        BeatDots(score.beats, beat, if (leadBars > 0) emptyList() else score.beatAccents, leadBars > 0 || accent)
    }
    Button({ onRunning(!running) }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
        Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (running) "Stop" else "Start")
    }
    if (audioError != null) Text("Visual only: $audioError. Turn the click off and on to retry.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (leadBars > 0) Text("Count in · $leadBars ${if (leadBars == 1) "bar" else "bars"} left", color = MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        FilledTonalIconButton({ tempo(bpm.roundToInt() - 1) }, enabled = bpm > 30, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Remove, "Slower") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${bpm.roundToInt()}", style = MaterialTheme.typography.displaySmall)
            Text(tempoName(bpm.roundToInt()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("beats per minute", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton({ tempo(bpm.roundToInt() + 1) }, enabled = bpm < 240, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, "Faster") }
    }
    Slider(bpm, { bpm = it }, modifier = Modifier.semanticsLabel("Tempo in beats per minute"), valueRange = 30f..240f, onValueChangeFinished = { tempo(bpm.roundToInt()) })
    // Coarse steps for finding a tempo quickly, then the slider for the exact number.
    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        OutlinedButton({ tempo(bpm.roundToInt() - 5) }, modifier = Modifier.weight(1f), enabled = bpm > 30, shapes = ButtonDefaults.shapes()) { Text("−5") }
        OutlinedButton({ tempo(nearestTempo(bpm.roundToInt()).bpm) }, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) { Text(nearestTempo(bpm.roundToInt()).label) }
        OutlinedButton({ tempo(bpm.roundToInt() + 5) }, modifier = Modifier.weight(1f), enabled = bpm < 240, shapes = ButtonDefaults.shapes()) { Text("+5") }
    }
    // One tap to a familiar marking, then fine-tune with the slider.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        MusicTempos.forEach { preset ->
            FilterChip(bpm.roundToInt() == preset.bpm, { tempo(preset.bpm) }, { Text("${preset.label} · ${preset.bpm}") })
        }
    }
    Text("Beat pattern · tap to cycle normal, strong, silent", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        for (index in 0 until score.beats) {
            val strength = metronomeBeatStrength(index, score.beatAccents, accent)
            val label = listOf("Silent", "Normal", "Strong")[strength]
            FilterChip(strength == 2, {
                model.score(score.id) { live ->
                    if (index >= live.beats) live else {
                        val pattern = (0 until live.beats).map { i -> metronomeBeatStrength(i, live.beatAccents, accent) }.toMutableList()
                        pattern[index] = (pattern[index] + 1) % 3
                        live.copy(beatAccents = pattern.toList())
                    }
                }
            }, { Text("${index + 1} · $label") }, modifier = Modifier.semanticsLabel("Beat ${index + 1}, $label; change beat strength"))
        }
    }
    if (score.beatAccents.isEmpty()) FilterChip(accent, { onAccent(!accent) }, { Text("Accent beat 1") })
    else TextButton({ model.score(score.id) { it.copy(beatAccents = emptyList()) } }, shapes = ButtonDefaults.shapes()) { Text("Reset beat pattern") }
    Text("Count-in bars", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        listOf(0, 1, 2, 4).forEach { bars ->
            FilterChip(countInBars == bars, { onCountInBars(bars) }, { Text(if (bars == 0) "Off" else "$bars") })
        }
    }
    Text("Subdivision", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        listOf(0 to "Off", 2 to "2", 3 to "3", 4 to "4").forEach { (value, label) ->
            FilterChip(subdivision == value, { onSubdivision(value) }, { Text(label) })
        }
    }
    if (sound) {
        Text("Click sound", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
            MetronomeVoice.entries.forEach { choice ->
                FilterChip(voice == choice, { onVoice(choice) }, { Text(choice.label) })
            }
        }
        var level by remember(volume) { mutableFloatStateOf(volume) }
        Text("Click volume · ${(level * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(level, { level = it }, valueRange = 0f..1f,
            modifier = Modifier.semanticsLabel("Metronome click volume"), onValueChangeFinished = { onVolume(level) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        PopoverTile(Icons.Rounded.TouchApp, "Tap tempo", Modifier.weight(1f)) {
            taps.tap(SystemClock.elapsedRealtime())?.let(::tempo)
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
    Text("Tempo, meter and beat pattern are saved with this score. Tap tempo averages up to four intervals. Sound, volume, subdivision and count-in are remembered across scores. The metronome stops when you leave the score or Folio.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Every page as a tile; a dot marks a rehearsal mark and a blue number counts the pencil notes. */
@Composable private fun PagesPopover(score: MusicScore, visible: IntRange, go: (Int) -> Unit) {
    val marked = score.marks.map { it.page }.toSet()
    val counts = score.pencil.withIndex().filter { it.value > 0 }.associate { it.index to it.value }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Icon(Icons.Rounded.GridView, null, Modifier.size(20.dp))
        Text("Go to page", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        // Long scores rarely need the middle: jump straight to an end from here.
        TextButton({ go(0) }, enabled = visible.first > 0, shapes = ButtonDefaults.shapes()) { Text("First") }
        TextButton({ go(score.pages - 1) }, enabled = visible.last < score.pages - 1, shapes = ButtonDefaults.shapes()) { Text("Last") }
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
                    (counts[index] ?: 0).takeIf { it > 0 }?.let { count ->
                        Text("$count", Modifier.align(Alignment.BottomEnd).padding(FolioSpacing.dp2),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    // A scrubber for a long score: drag straight to a page instead of hunting for its tile.
    if (score.pages > 1) Slider(visible.first.toFloat(), { go(it.roundToInt()) }, valueRange = 0f..(score.pages - 1).toFloat(), modifier = Modifier.semanticsLabel("Score page"))
    if (marked.isNotEmpty() || counts.isNotEmpty()) Text(
        listOfNotNull(if (marked.isNotEmpty()) "a dot marks a rehearsal mark" else null,
            if (counts.isNotEmpty()) "a blue number counts pencil notes" else null).joinToString("; ").replaceFirstChar { it.uppercase() } + ".",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}


private data class MusicPageImage(val bitmap: Bitmap? = null, val error: String? = null)

/**
 * One score page, rendered off the UI thread and fitted to its room. The import review uses it to
 * look through a PDF before choosing parts; tapping the left or right half turns the page.
 */
@Composable internal fun MusicPage(file: File, page: Int, pageCount: Int, cache: MusicPageCache,
    onTurn: (Boolean) -> Unit = { _ -> }, modifier: Modifier) {
    var retry by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current.density
        val width = (maxWidth.value * density).roundToInt().coerceIn(1, 2000)
        val result by produceState(MusicPageImage(), file, page, width, retry) {
            value = MusicPageImage()
            value = withContext(Dispatchers.IO) {
                try { MusicPageImage(cache.render(file, page, width)) }
                catch (e: Exception) { MusicPageImage(error = e.message ?: "Could not render page") }
            }
        }
        val bitmap = result.bitmap
        if (bitmap != null) {
            val ratio = bitmap.width.toFloat() / bitmap.height
            val w = minOf(maxWidth, maxHeight * ratio)
            Image(bitmap.asImageBitmap(), "Score page ${page + 1} of $pageCount",
                Modifier.width(w).aspectRatio(ratio).pointerInput(file, page) {
                    detectTapGestures(onTap = { onTurn(it.x >= size.width / 2f) })
                })
        } else if (result.error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text(result.error!!, style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton({ retry++ }, shapes = ButtonDefaults.shapes()) { Text("Try again") }
            }
        } else LoadingIndicator()
    }
}
