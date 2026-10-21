@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.mistakes.review

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.EditorScreen
import com.folio.notes.export.NoteExporter
import com.folio.notes.export.PageExportFormat
import com.folio.notes.export.PageExportRequest
import com.folio.notes.export.exportCacheDir
import com.folio.notes.export.pageHasAnnotations
import com.folio.notes.export.pruneExportCache
import com.folio.notes.export.uniqueShareFilename
import com.folio.notes.mistakes.DeleteCardDialog
import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.LocalMistakeReviewAttempt
import com.folio.notes.mistakes.MistakeScheduler
import com.folio.notes.mistakes.MistakeSplit
import com.folio.notes.mistakes.MistakesViewModel
import com.folio.notes.mistakes.QuestionContent
import com.folio.notes.mistakes.ReviewRating
import com.folio.notes.mistakes.intervalLabel
import com.folio.notes.mistakes.isoTime
import com.folio.notes.mistakes.mistakeLayout
import com.folio.notes.mistakes.richtext.RichText
import com.folio.notes.mistakes.richtext.scaledBy
import com.folio.notes.mistakes.sync.ExamContext
import com.folio.notes.mistakes.timestamp
import com.folio.notes.model.NotePage
import com.folio.notes.model.withPage
import com.folio.notes.settings.rememberPref
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.workspace.SplitDivider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Review ------------------------------------------------------------------------------------

@Composable internal fun MistakeReviewScreen(m: ExamTrackMistake, context: ExamContext?, attempt: LocalMistakeReviewAttempt,
    model: MistakesViewModel, folio: FolioViewModel, state: FolioState, finger: Boolean, haptics: Boolean, shapes: Boolean,
    busy: Boolean, onBack: () -> Unit, onSettings: () -> Unit, onExport: () -> Unit, dueLeft: Int,
    onShareLongPress: (() -> Unit)? = null,
    queuePos: Int? = null, queueSize: Int? = null,
    shuffle: Boolean = false, onToggleShuffle: () -> Unit = {},
    canSkip: Boolean = false, onSkip: () -> Unit = {},
    actionMessage: String? = null,
    celebration: Int = 0,
    sessionXP: Int = 0,
    onRate: (ReviewRating, String?) -> Unit,
    onDelete: () -> Unit = {}) {
    val mistakesState by model.state.collectAsStateWithLifecycle()
    val reviewPreferences = mistakesState.cache.reviewPreferences
    val androidContext = LocalContext.current
    val shareScope = rememberCoroutineScope()
    val preferences = remember(androidContext) { androidContext.getSharedPreferences("preferences", 0) }
    val confirmReview by rememberPref(preferences, AppPrefs.UX_REVIEW_CONFIRM) { it.getBoolean(AppPrefs.UX_REVIEW_CONFIRM, AppPrefs.DEFAULT_UX_REVIEW_CONFIRM) }
    val rootView = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    // The embedded editor owns its toolbar and viewport surfaces. Match them to
    // the review background so the two panes share one continuous backdrop.
    val responseColors = remember(colors) { colors.copy(
        surfaceContainerLowest = colors.surface,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.surface,
    ) }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // The shell only carries metadata: its pages stay unloaded even after the editor
    // loads them. Observe this review's content in both Write and Type modes.
    val practiceSnapshot by remember(folio, attempt.practiceNotebookId, attempt.practicePageId) {
        folio.documents.observePage(attempt.practiceNotebookId, attempt.practicePageId)
    }.collectAsStateWithLifecycle()
    val practicePage = practiceSnapshot?.page?.takeIf { it.id == attempt.practicePageId }
    LaunchedEffect(attempt.practiceNotebookId, attempt.practicePageId) { folio.loadPage(attempt.practicePageId) }
    var responseMode by rememberSaveable(attempt.reviewId) {
        mutableStateOf(if (practicePage?.let { MistakeTypedResponse.find(it, attempt.reviewId) } != null) "type"
            else AppPrefs.mistakeResponseMode(preferences.getString(AppPrefs.MISTAKE_RESPONSE_MODE, null)))
    }
    var modeChosen by rememberSaveable(attempt.reviewId) { mutableStateOf(false) }
    LaunchedEffect(attempt.reviewId, practicePage?.loaded) {
        if (!modeChosen && practicePage?.loaded == true && MistakeTypedResponse.find(practicePage, attempt.reviewId) != null) responseMode = "type"
    }
    val typing = responseMode == "type"
    var typingFocused by remember(attempt.reviewId) { mutableStateOf(false) }
    var typeFocusRequest by remember(attempt.reviewId) { mutableIntStateOf(0) }
    fun finishTyping() { keyboard?.hide(); focusManager.clearFocus(); typingFocused = false }
    fun skipReview() { finishTyping(); onSkip() }
    var revealed by rememberSaveable(attempt.reviewId) { mutableStateOf(false) }
    var confidence by rememberSaveable(attempt.reviewId) { mutableStateOf<String?>(null) }
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
    // Earlier working for the same question, newest first. The current page is excluded
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
        if (!confirmReview) { confirmed(); return }
        val now = android.os.SystemClock.uptimeMillis()
        if (pendingAction == action && now - firstTapAt <= 1000) {
            pendingAction = null
            confirmed()
        } else {
            pendingAction = action
            firstTapAt = now
        }
    }
    fun revealAnswer() { finishTyping(); revealed = true; questionExpanded = true }
    val hasInk = practicePage?.let { it.strokes.isNotEmpty() || it.images.isNotEmpty() } == true
    val hasTyped = practicePage?.let { MistakeTypedResponse.find(it, attempt.reviewId)?.text?.isNotBlank() } == true
    fun selectResponseMode(mode: String) {
        if (responseMode != mode) {
            finishTyping()
            modeChosen = true
            responseMode = mode
            preferences.edit().putString(AppPrefs.MISTAKE_RESPONSE_MODE, mode).apply()
            if (mode == "type") typeFocusRequest++
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage, attempt.reviewId) { actionMessage?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.saveFailed) {
        if (state.saveFailed) snackbar.showSnackbar("Saving failed — retry saving your response before rating.", duration = SnackbarDuration.Long)
    }
    Scaffold(
        containerColor = colors.surface,
        // Keep the editor mounted while the next page is prepared, but do not let
        // a stray stroke or tap edit a notebook during the handoff.
        modifier = Modifier.then(if (typing) Modifier.imePadding() else Modifier).onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || busy || typingFocused || rootView.findFocus() is android.widget.EditText || event.nativeKeyEvent.repeatCount != 0) false
            else when (event.key) {
                Key.Spacebar -> { if (!revealed) { revealAnswer(); true } else false }
                Key.S -> { if (canSkip) { confirmDoubleTap("skipTop", ::skipReview); true } else false }
                Key.One, Key.Two, Key.Three, Key.Four -> {
                    if (revealed && !state.saveFailed) { val index = listOf(Key.One, Key.Two, Key.Three, Key.Four).indexOf(event.key); ReviewRating.entries.getOrNull(index)?.let { onRate(it, confidence) }; true } else false
                }
                else -> false
            }
        }.pointerInput(busy) {
            if (busy) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        },
        topBar = {
            BoxWithConstraints {
                val roomyHeader = maxWidth >= 600.dp * density.fontScale
                Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.surface, scrolledContainerColor = colors.surface),
                    // The card's own name is the heading of the question pane, so the bar only
                    // carries progress: one quiet line instead of a repeated title and subtitle.
                    title = {
                        Text(
                            when {
                                state.saveFailed -> "Save failed · retry before rating"
                                state.saving -> "Saving your response…"
                                typing && imeVisible -> m.question
                                queuePos != null && queueSize != null && queueSize > 1 -> "Card $queuePos of $queueSize" + if (sessionXP > 0) " · +$sessionXP XP" else ""
                                dueLeft > 1 -> "${dueLeft - 1} more due after this"
                                else -> "Practise at your own pace"
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                            color = if (state.saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    navigationIcon = { IconButton({ finishTyping(); onBack() }, enabled = !busy, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to mistakes · your response is saved") } },
                    actions = {
                        if (roomyHeader && previousAttempts.isNotEmpty()) {
                            IconButton({ finishTyping(); showPrevious = true }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                                BadgedBox({ Badge { Text("${previousAttempts.size}") } }) {
                                    Icon(Icons.Rounded.History, "Compare with your last attempt (${previousAttempts.size} kept, read-only)")
                                }
                            }
                        }
                        // Before the reveal the bottom bar already offers Skip, so the top one only
                        // appears once that row has turned into ratings.
                        if (roomyHeader && revealed && canSkip) {
                            IconButton({ confirmDoubleTap("skipTop", ::skipReview) }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.SkipNext, if (pendingAction == "skipTop") "Tap again to skip" else "Double-tap to skip this card",
                                    tint = if (pendingAction == "skipTop") MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                        }
                        MistakeResponseModeSwitch(responseMode, hasInk, hasTyped, !busy, roomyHeader, ::selectResponseMode)
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton({ menu = true }, enabled = !busy, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "More review options") }
                            DropdownMenu(menu, { menu = false }) {
                                if (!roomyHeader && previousAttempts.isNotEmpty()) DropdownMenuItem(
                                    text = { Text("Previous attempts (${previousAttempts.size})") },
                                    leadingIcon = { Icon(Icons.Rounded.History, null) },
                                    onClick = { menu = false; finishTyping(); showPrevious = true }
                                )
                                if (!roomyHeader && revealed && canSkip) DropdownMenuItem(
                                    text = { Text(if (pendingAction == "skipTop") "Tap again to skip" else "Skip this card") },
                                    leadingIcon = { Icon(Icons.Rounded.SkipNext, null) },
                                    onClick = { confirmDoubleTap("skipTop") { menu = false; skipReview() } }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (shuffle) "Shuffled · use due order" else "Due order · shuffle") },
                                    leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
                                    onClick = { menu = false; onToggleShuffle() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Question display") },
                                    leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                                    onClick = { menu = false; adjustLayout = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete card", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; showDelete = true }
                                )
                            }
                        }
                    }
                )
                // How far through the session: a quiet hairline rather than another line of text.
                if (queuePos != null && queueSize != null && queueSize > 1) {
                    val sessionProgress by androidx.compose.animation.core.animateFloatAsState(
                        ((queuePos - 1).toFloat() / queueSize).coerceIn(0f, 1f), label = "session")
                    LinearProgressIndicator(
                        progress = { sessionProgress },
                        modifier = Modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = "Card $queuePos of $queueSize" },
                        trackColor = colors.surfaceContainerHighest,
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // One slim strip. Before the reveal: recall strength and the optional confidence
            // call on the left, Skip and Compare on the right. After: just the four ratings.
            // Explanations live in labels and content descriptions, not in permanent captions.
            Surface(color = colors.surface) {
                BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8)) {
                    val roomy = maxWidth >= 640.dp
                    val strength = MistakePracticeProgress.strength(m)
                    val rematch = MistakePracticeProgress.rematch(m)
                    if (!revealed) {
                        val recall: @Composable () -> Unit = {
                            RecallStrength(strength, rematch)
                            Text("Confidence", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf("low" to "Guessing", "medium" to "Unsure", "high" to "Confident").forEach { (value, label) ->
                                FilterChip(selected = confidence == value, onClick = { confidence = if (confidence == value) null else value }, enabled = !busy, label = { Text(label) })
                            }
                        }
                        val actions: @Composable () -> Unit = {
                            if (canSkip && !(typing && imeVisible)) {
                                TextButton({ confirmDoubleTap("skipBottom", ::skipReview) }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(FolioSpacing.dp4))
                                    Text(if (pendingAction == "skipBottom") "Tap again" else "Skip")
                                }
                            }
                            if (typing && imeVisible) TextButton(::finishTyping, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.KeyboardHide, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(FolioSpacing.dp4))
                                Text("Done typing")
                            }
                            FilledTonalButton({ confirmDoubleTap("compare", ::revealAnswer) }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(FolioSpacing.dp8))
                                Text(if (pendingAction == "compare") "Tap again to compare" else "Compare answer")
                            }
                        }
                        if (roomy) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            if (!(typing && imeVisible)) recall()
                            Spacer(Modifier.weight(1f))
                            actions()
                        } else Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            if (!(typing && imeVisible)) FlowRow(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
                                itemVerticalAlignment = Alignment.CenterVertically) { recall() }
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) { actions() }
                        }
                    } else {
                        val now = remember(revealed) { isoTime() }
                        Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            val note = when {
                                pendingAction == "skipTop" -> "Tap the Skip button above again to confirm."
                                state.saveFailed -> "Save failed — retry saving your response, then rate."
                                busy -> "Saving your review…"
                                else -> null
                            }
                            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                if (roomy) Box(Modifier.padding(end = FolioSpacing.dp8)) { RecallStrength(strength, rematch) }
                                ReviewRating.entries.forEach { rating ->
                                    val preview = remember(m, rating, reviewPreferences, mistakesState.cache.mistakes, now) {
                                        runCatching { MistakeScheduler.previewMistakeReview(m, rating, now, reviewPreferences, mistakesState.cache.mistakes.values.toList()) }.getOrNull()
                                    }
                                    val label = preview?.let { intervalLabel(it.copy(intervalDays = (timestamp(it.dueAt) - timestamp(now)).toDouble() / 86_400_000)) } ?: ""
                                    // Autosave runs after each edit. Rating waits for it in the
                                    // handler, so drawing must not pulse the buttons' enabled state.
                                    val canRate = !busy && !state.saveFailed
                                    val ratingName = rating.wire.replaceFirstChar { it.uppercase() }
                                    val meaning = when (rating) {
                                        ReviewRating.AGAIN -> "missed it"
                                        ReviewRating.HARD -> "needed help"
                                        ReviewRating.GOOD -> "recalled it"
                                        else -> "confident"
                                    }
                                    val shortcut = rating.ordinal + 1
                                    val modifier = Modifier.weight(1f).semantics { contentDescription = "$ratingName, $meaning${if (label.isNotBlank()) ", next review $label" else ""}, shortcut $shortcut" }
                                    val content: @Composable RowScope.() -> Unit = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(ratingName, style = MaterialTheme.typography.labelLarge)
                                            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(.75f))
                                        }
                                    }
                                    when (rating) {
                                        ReviewRating.AGAIN -> OutlinedButton({ finishTyping(); onRate(rating, confidence) }, enabled = canRate, modifier = modifier, shapes = ButtonDefaults.shapes(), content = content)
                                        ReviewRating.GOOD -> Button({ finishTyping(); onRate(rating, confidence) }, enabled = canRate, modifier = modifier, shapes = ButtonDefaults.shapes(), content = content)
                                        else -> FilledTonalButton({ finishTyping(); onRate(rating, confidence) }, enabled = canRate, modifier = modifier, shapes = ButtonDefaults.shapes(), content = content)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // The IME must not turn a portrait tablet into a landscape split mid-sentence.
            val wide = if (typing) maxWidth >= 840.dp else mistakeLayout(maxWidth.value.toInt(), maxHeight.value.toInt()).splitReview
            val compactTyping = typing && imeVisible && maxHeight < 300.dp
            val referenceExpanded = questionExpanded && !(typing && imeVisible && typingFocused && !wide)
            val referenceHeight = maxHeight * portraitShare
            val dividerThickness = 16.dp
            val density = LocalDensity.current
            val splitWidthPx = with(density) { (maxWidth - dividerThickness).toPx() }
            val splitHeightPx = with(density) { maxHeight.toPx() }
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
                Surface(modifier, color = colors.surface) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp16, end = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                            // A collapsed portrait panel has no heading of its own, so it names the card.
                            Text(
                                if (!wide && !referenceExpanded) m.question else if (revealed) "Compare & reflect" else "Question",
                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant
                            )
                            ShareTextButton(typed = typing,
                                onShare = { questionOnly ->
                                    val text = mistakeShareText(m, if (questionOnly) emptyList() else workingTexts(practicePage))
                                    if (questionOnly || typing && practicePage?.strokes.isNullOrEmpty() && practicePage?.images.isNullOrEmpty()) shareText(androidContext, text)
                                    else shareScope.launch {
                                        val png = runCatching { renderWorkingPng(androidContext, folio, state, attempt, practicePage, preferences) }.getOrNull()
                                        if (png == null) shareText(androidContext, text)
                                        else {
                                            copyText(androidContext, text)
                                            shareText(androidContext, null, png)
                                            launch { snackbar.showSnackbar("Question and working copied to the clipboard") }
                                        }
                                    }
                                },
                            )
                            if (!wide) IconButton({ if (!referenceExpanded) { finishTyping(); questionExpanded = true } else questionExpanded = false }, shapes = IconButtonDefaults.shapes()) {
                                Icon(if (referenceExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (referenceExpanded) "Collapse question" else "Expand question")
                            }
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
                        if (wide || referenceExpanded) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                            QuestionContent(m, context, attempt.userId, model.attachments, textScale)
                            androidx.compose.animation.AnimatedVisibility(visible = revealed,
                                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if (reviewPreferences.practiceEffects) 180 else 0)),
                                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(0))) {
                                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                                    HorizontalDivider()
                                    Text("Correction", style = MaterialTheme.typography.titleMedium)
                                    RichText(m.correction.ifBlank { "No correction saved in Focal." }, style = MaterialTheme.typography.bodyLarge.scaledBy(textScale))
                                    Text("What went wrong", style = MaterialTheme.typography.titleMedium)
                                    RichText(m.explanation.ifBlank { "No explanation saved in Focal." }, style = MaterialTheme.typography.bodyMedium.scaledBy(textScale))
                                }
                            }
                        }
                    }
                }
            }
            @Composable fun ResponsePane(modifier: Modifier) {
                Box(modifier) {
                    MaterialTheme(colorScheme = responseColors) {
                        if (typing) MistakeTypingPane(practicePage, attempt.reviewId, state, folio, busy, typeFocusRequest, compactTyping,
                            revealed = revealed, onCompare = if (revealed) null else ::revealAnswer, onDone = ::finishTyping,
                            onFocusChanged = { typingFocused = it })
                        else EditorScreen(state, folio, finger, haptics, shapes, onSettings, onExport, showBack = false, onShareLongPress = onShareLongPress)
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                ReferencePane(Modifier.weight(landscapeShare).fillMaxHeight())
                SplitDivider(
                    vertical = true,
                    thickness = dividerThickness,
                    onDrag = { draggedShare(it, splitWidthPx) },
                    onRelease = ::releaseShare,
                    onDoubleTap = ::resetShare,
                    contentDescription = "Split between the question and your working. Drag to resize. Double-tap for the default split.",
                )
                ResponsePane(Modifier.weight(1f - landscapeShare).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                if (!compactTyping) {
                    ReferencePane(Modifier.fillMaxWidth().height(if (referenceExpanded) referenceHeight else 52.dp))
                    // A collapsed question panel is still a handle: any drag reopens and resizes it.
                    SplitDivider(
                        vertical = false,
                        thickness = dividerThickness,
                        onDrag = { delta ->
                            if (!referenceExpanded) finishTyping()
                            if (!questionExpanded) questionExpanded = true
                            draggedShare(delta, splitHeightPx)
                        },
                        onRelease = ::releaseShare,
                        onDoubleTap = ::resetShare,
                        contentDescription = "Split between the question and your working. Drag to resize. Double-tap for the default split.",
                    )
                }
                ResponsePane(Modifier.weight(1f).fillMaxWidth())
            }
            PracticeConfetti(celebration, reviewPreferences.practiceEffects, reviewPreferences.practiceFinish, Modifier.matchParentSize())
        }
    }
    if (showPrevious && previousAttempts.isNotEmpty()) {
        PreviousAttemptDialog(attempts = previousAttempts, folio = folio, onClose = { showPrevious = false })
    }
    if (showDelete) DeleteCardDialog(m.question, busy, onDismiss = { showDelete = false }) {
        showDelete = false; onDelete()
    }
}

/** A compact app-bar control; its two touch targets remain available above the keyboard. */
@Composable private fun MistakeResponseModeSwitch(
    responseMode: String, hasInk: Boolean, hasTyped: Boolean, enabled: Boolean, showLabels: Boolean,
    onModeChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = FolioSpacing.dp4)
        .semantics { contentDescription = "Response mode" }) {
        listOf("write" to "Write", "type" to "Type").forEachIndexed { index, (mode, label) ->
            val selected = responseMode == mode
            val waiting = !selected && if (mode == "write") hasInk else hasTyped
            SegmentedButton(selected = selected, onClick = { onModeChange(mode) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).then(if (showLabels) Modifier else Modifier.width(48.dp))
                    .semantics { contentDescription = "$label response${if (waiting) ", has working" else ""}" },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.secondaryContainer, activeContentColor = colors.onSecondaryContainer,
                    inactiveContainerColor = colors.surface, inactiveContentColor = colors.onSurfaceVariant),
                icon = {},
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    BadgedBox(badge = { if (waiting) Badge(containerColor = colors.primary) }) {
                        Icon(if (mode == "type") Icons.Rounded.Keyboard else Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    }
                    if (showLabels) Text(label, maxLines = 1)
                }
            }
        }
    }
}

/** Three dots instead of a labelled bar: one per day of spaced recall, with a quiet "Rematch" tag. */
@Composable private fun RecallStrength(strength: Int, rematch: Boolean) {
    Row(
        Modifier.semantics { contentDescription = "Recall strength $strength of 3 spaced recalls${if (rematch) ", rematch" else ""}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)
    ) {
        repeat(3) { i ->
            Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(
                if (i < strength) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
        Text(if (rematch) "Rematch" else "Recall", Modifier.padding(start = FolioSpacing.dp4), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Typed working on the practice page, top to bottom. Handwriting has no text to share. */
private fun workingTexts(page: NotePage?): List<String> =
    page?.texts?.sortedWith(compareBy({ it.y }, { it.x }))?.map { it.text.trim() }?.filter(String::isNotEmpty) ?: emptyList()

/** The question as raw text, followed by [working] when there is any. */
private fun mistakeShareText(m: ExamTrackMistake, working: List<String>): String = buildString {
    append(m.question.trim())
    m.questionText?.trim()?.takeIf(String::isNotEmpty)?.let { append("\n\n").append(it) }
    if (working.isNotEmpty()) append("\n\nMy working:\n").append(working.joinToString("\n\n"))
}

/** The practice page as a PNG in the share cache, or null when it holds nothing to draw. */
private suspend fun renderWorkingPng(context: android.content.Context, folio: FolioViewModel, state: FolioState,
    attempt: LocalMistakeReviewAttempt, page: NotePage?, preferences: android.content.SharedPreferences): java.io.File? {
    if (page == null || !page.loaded || !pageHasAnnotations(page)) return null
    val note = state.notes.find { it.id == attempt.practiceNotebookId }?.withPage(page) ?: return null
    val index = note.pages.indexOfFirst { it.id == attempt.practicePageId }
    if (index < 0) return null
    val scale = AppPrefs.pngScale(preferences.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { preferences.contains(AppPrefs.EXPORT_PNG_SCALE) })
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = exportCacheDir(context.cacheDir)
        pruneExportCache(dir)
        java.io.File(dir, uniqueShareFilename("working-out.png")).also { out ->
            out.outputStream().use {
                NoteExporter(folio.repository).write(it, PageExportRequest(note, listOf(index), PageExportFormat.PNG, tightBounds = true), scale)
            }
        }
    }
}

private fun copyText(context: android.content.Context, text: String) {
    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
        .setPrimaryClip(android.content.ClipData.newPlainText("Question and working", text))
}

/** Opens the Android share sheet with [text] and/or [image]. */
private fun shareText(context: android.content.Context, text: String?, image: java.io.File? = null) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        if (text != null) putExtra(android.content.Intent.EXTRA_TEXT, text)
        if (image == null) type = "text/plain"
        else {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", image)
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("Working out", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    try {
        context.startActivity(android.content.Intent.createChooser(send, "Share question").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {}
}

/** Tap shares a picture of the working and copies the text; long-press shares the question text alone. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun ShareTextButton(typed: Boolean, onShare: (questionOnly: Boolean) -> Unit) {
    Box(
        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).combinedClickable(
            role = androidx.compose.ui.semantics.Role.Button,
            onClickLabel = if (typed) "Share your response" else "Share a picture of your working out and copy the text",
            onLongClickLabel = "Share question text only",
            onLongClick = { onShare(true) },
            onClick = { onShare(false) }
        ),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Rounded.IosShare, if (typed) "Share your response · hold to share the question text" else "Share working out as a picture and copy the text · hold to share the question text", Modifier.size(24.dp)) }
}
