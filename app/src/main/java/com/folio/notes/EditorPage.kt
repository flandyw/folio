@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun EditorPage(noteId: String, initialPage: NotePage, model: FolioViewModel, surface: InkSurfaceConfig, presentation: PagePresentation, actions: EditorPageActions)  {
    val snapshot by remember(noteId, initialPage.id) { model.documents.observePage(noteId, initialPage.id) }.collectAsStateWithLifecycle()
    val page = snapshot?.page ?: initialPage
    val tool = surface.tool
    val shapeMeasurements = surface.shapeMeasurements
    val followEnabled = surface.followEnabled
    val autoDetectAnswerAreas = surface.autoDetectAnswerAreas
    val inputBlocked = surface.inputBlocked
    val active = presentation.active
    val fullscreen = presentation.fullscreen
    val pageCamera = presentation.pageCamera
    val selectedImageId = presentation.selectedImageId
    val readOnly = presentation.readOnly
    val selectionAnchor = presentation.selectionAnchor
    val paperWidth = presentation.paperWidth
    val textDraft = presentation.textDraft
    val textTopInset = presentation.textTopInset
    val selectionMenu = presentation.selectionMenu
    val selectionMenuViewport = presentation.selectionMenuViewport
    val markColor = presentation.markColor
    val onLoad = actions.onLoad
    val onSelectAllView = actions.onSelectAllView
    val onStickyDraw = actions.onStickyDraw
    val onTextDraft = actions.onTextDraft
    val onTextDone = actions.onTextDone
    val onReplaceZone = actions.onReplaceZone
    val onPageFrame = actions.onPageFrame
    val onPageRegion = actions.onPageRegion
    val onObjectLift = actions.onObjectLift
    val onObjectDrop = actions.onObjectDrop
    val onStickyLift = actions.onStickyLift
    val onStickyDrop = actions.onStickyDrop
    val onSelectionDrop = actions.onSelectionDrop

    DisposableEffect(page.id) { onDispose { onPageFrame(page.id, null); onPageRegion(page.id, null) } }
    // A carried object keeps its source page alive while the list scrolls under it.
    val pinnable = LocalPinnableContainer.current
    var carrying by remember { mutableStateOf(false) }
    DisposableEffect(carrying) {
        val pin = if (carrying) pinnable?.pin() else null
        onDispose { pin?.release() }
    }
    val inputStylusActivity = LocalStylusActivity.current
    val shapePrefs = rememberPrefs()
    val shapeHoldMs by rememberPref(shapePrefs, AppPrefs.SHAPE_HOLD_MS) {
        AppPrefs.shapeHoldMs(it.getLong(AppPrefs.SHAPE_HOLD_MS, AppPrefs.DEFAULT_SHAPE_HOLD_MS))
    }
    val penSmoothing by rememberPref(shapePrefs, AppPrefs.PEN_SMOOTHING) {
        AppPrefs.penSmoothing(it.getFloat(AppPrefs.PEN_SMOOTHING, AppPrefs.DEFAULT_PEN_SMOOTHING))
    }
    val stickyTextSize by rememberPref(shapePrefs, AppPrefs.STICKY_TEXT_SIZE) {
        AppPrefs.stickyTextSize(it.getFloat(AppPrefs.STICKY_TEXT_SIZE, AppPrefs.DEFAULT_STICKY_TEXT_SIZE))
    }
    // The printed allocation being offered a tick/cross, with its rectangle in this page's view pixels.
    var offeredZone by remember(page.id) { mutableStateOf<Pair<MarkZone, android.graphics.RectF>?>(null) }
    var offerStamp by remember(page.id) { mutableLongStateOf(0L) }
    // A rectangle just drawn (or an existing hand-drawn one being edited) waiting for its marks to be entered.
    var areaEntry by remember(page.id) { mutableStateOf<AreaEntry?>(null) }
    var textFrame by remember(page.id) { mutableStateOf<InlineTextFrame?>(null) }
    var sticky by remember(page.id) { mutableStateOf<StickyFocus?>(null) }
    var stickyDraft by remember(page.id) { mutableStateOf<StickyDraft?>(null) }
    var pageWindowFrame by remember(page.id) { mutableStateOf<Rect?>(null) }
    val canvasBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val shapeMeasurement = remember(page.id) { mutableStateOf<ShapeMeasurement?>(null) }
    val assets = rememberPageAssets(noteId, page, model, active, followEnabled, onLoad)
    val writingGuides = assets.guides
    val ready = assets.ready
    val error = assets.error
    var boundInkView by remember(page.id) { mutableStateOf<InkView?>(null) }
    // Only the current page participates: lazy-list prefetch must not select areas on neighbours.
    // Wait for asynchronous guide detection and the configured native view. No rules means no
    // automatic selection (in particular, never enter the manual drag tool on blank pages).
    LaunchedEffect(page.id, active, followEnabled, autoDetectAnswerAreas, writingGuides, boundInkView) {
        if (active && followEnabled && autoDetectAnswerAreas && writingGuides.isNotEmpty()) {
            boundInkView?.suggestWritingRegion()
        }
    }
    /** Puts typed words into the note; the field closes whichever way typing ends. */
    fun commitStickyDraft() {
        val draft = stickyDraft ?: return
        stickyDraft = null
        boundInkView?.setStickyText(draft.id, draft.value.text)
    }
    // A document page spans the workspace beside its paper, and grows above or below the paper
    // only as far as a note already hangs past it.
    val pageDensity = LocalDensity.current
    val documentPage = paperWidth != null && !readOnly && !fullscreen && !page.infinite
    val notes = if (documentPage) page.texts.filter { it.isSticky } else emptyList()
    val workspaceTop = minOf(0f, notes.minOfOrNull { it.y } ?: 0f)
    val workspaceBottom = maxOf(page.height, notes.maxOfOrNull { it.y + it.stickyHeight } ?: 0f)
    val paperWidthPx = if (documentPage) with(pageDensity) { paperWidth!!.toPx() } else 0f
    val pageModifier = when {
        fullscreen -> Modifier.fillMaxSize()
        documentPage -> Modifier.fillMaxWidth().height(paperWidth!! * ((workspaceBottom - workspaceTop) / page.width))
        else -> Modifier.fillMaxWidth().aspectRatio(page.width / page.height)
    }
    // Keep the full page frame in window coordinates, including scrolled-off portions.
    // The context menu converts the local selection against this frame and clamps to the pane;
    // selection drops between pages land against the paper alone.
    Box(pageModifier.onGloballyPositioned { coordinates ->
            val origin = coordinates.localToWindow(Offset.Zero)
            pageWindowFrame = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            onPageRegion(page.id, pageWindowFrame)
            if (documentPage) {
                val left = origin.x + (coordinates.size.width - paperWidthPx) / 2
                val top = origin.y - workspaceTop * paperWidthPx / page.width
                onPageFrame(page.id, Rect(left, top, left + paperWidthPx, top + page.height * paperWidthPx / page.width))
            } else onPageFrame(page.id, pageWindowFrame)
        }) {
        Surface(
            Modifier.fillMaxSize(),
            shape = if (documentPage || (fullscreen && !pageCamera)) RectangleShape else FolioShapes.medium,
            color = canvasBackground,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            border = null
        ) {
            // Placeholders cover the paper only, not the workspace beside it.
            val placeholderColor = MaterialTheme.colorScheme.surfaceContainerLowest
            val placeholderPaper = if (!documentPage) Modifier.background(placeholderColor) else Modifier.drawBehind {
                val unit = paperWidthPx / page.width
                drawRect(placeholderColor, Offset((size.width - paperWidthPx) / 2, -workspaceTop * unit),
                    androidx.compose.ui.geometry.Size(paperWidthPx, page.height * unit))
            }
            // Nothing is drawn on a page until its own ink has arrived, so a stroke can never land on top
            // of a blank stand-in and replace the content that is still on disk.
            if (!page.loaded) Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().then(placeholderPaper)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    LoadingIndicator(Modifier.semanticsLabel("Loading page"))
                    Text("Loading page…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else if (ready) InkViewHost(noteId, page, surface, presentation, actions, assets, inputStylusActivity, penSmoothing, shapeHoldMs, stickyTextSize,
                onBound = { boundInkView = it }, onReleased = { if (boundInkView === it) boundInkView = null }) { view ->
                view.onDocumentGesturePresence = { carrying = it }
                view.documentPaperWidth = paperWidthPx
                view.documentTop = workspaceTop
                view.onTextEditorFrame = { textFrame = it }
                view.onTextEditingExit = {
                    onTextDone()
                    view.editingText = null
                    // This down also starts the next gesture. Hit-test the committed wording and
                    // bounds now, before Compose's next update, rather than the original box.
                    model.state.value.notes.firstOrNull { it.id == noteId }?.pages?.firstOrNull { it.id == page.id }
                        ?.let { view.bind(it, view.background, view.imageBitmaps) }
                }
                view.editingText = textDraft
                view.onStickyFocus = { box, frame, typing ->
                    val draft = stickyDraft
                    if (draft != null && (!typing || box?.id != draft.id)) commitStickyDraft()
                    if (typing && box != null && stickyDraft == null)
                        stickyDraft = StickyDraft(box.id, TextFieldValue(box.text, TextRange(box.text.length)))
                    sticky = if (box != null && frame != null) StickyFocus(box, frame, typing) else null
                }
                view.canvasBackgroundColor = canvasBackground.toArgb()
                view.onShapeMeasurement = { shapeMeasurement.value = it }
                onSelectAllView?.invoke(view)
                view.onSelectionDrop = { picked, x, y -> !readOnly && onSelectionDrop(page.id, picked, x, y) }
                view.onObjectLift = if (documentPage && !readOnly && onObjectLift != null) { lift -> carrying = lift != null; onObjectLift(lift) } else null
                view.onObjectDrop = if (documentPage && !readOnly && onObjectDrop != null) { lift -> onObjectDrop(page.id, lift) } else null
                view.onStickyLift = if (documentPage && onStickyLift != null) { lift -> carrying = lift != null; onStickyLift(lift) } else null
                view.onStickyDrop = if (documentPage && onStickyDrop != null) { lift -> onStickyDrop(page.id, lift) } else null
                view.onMarkRegion = { lane, rect ->
                    areaEntry = if (lane == null || rect == null) null
                    else AreaEntry(null, lane.left, lane.top, lane.right - lane.left, lane.bottom - lane.top, rect)
                }
                view.onMarkZone = { zone, rect ->
                    offeredZone = if (zone != null && rect != null) zone to rect else null
                    offerStamp++
                }
                if (!active && !carrying && !view.documentSelectionOwner) view.clearSelection()

            } else Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().then(placeholderPaper)) {
                if (error) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), modifier = Modifier.padding(FolioSpacing.dp24)) {
                    Icon(Icons.Rounded.PictureAsPdf, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Couldn't open this PDF page", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Check the file still exists, then try again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton({ assets.retry() }, shapes = ButtonDefaults.shapes()) { Text("Try again") }
                } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    LoadingIndicator(Modifier.semanticsLabel("Loading page"))
                    Text("Rendering PDF…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (page.loaded && ready && !readOnly && shapeMeasurements) ShapeMeasurementTooltip(shapeMeasurement)
        offeredZone?.takeIf { page.loaded && !readOnly }?.let { (zone, rect) ->
            MarkChip(zone, rect, page, offerStamp,
                onAward = { value -> model.awardMark(page.id, zone, value, markColor); offeredZone = null },
                onClear = { model.clearMark(page.id, zone); offeredZone = null },
                onDismiss = { offeredZone = null },
                onEdit = if (zone.manual) ({
                    areaEntry = AreaEntry(zone, zone.x, zone.y, zone.width, zone.height, rect); offeredZone = null
                }) else null)
        }
        areaEntry?.takeIf { page.loaded && !readOnly }?.let { entry ->
            // A fresh box is read in the background so the stepper opens on the number printed inside it.
            var guess by remember(entry) { mutableStateOf<Int?>(null) }
            LaunchedEffect(entry) {
                if (entry.existing == null) guess = try {
                    model.repository.readMarkRegion(noteId, page, WritingLane(entry.x, entry.y, entry.x + entry.width, entry.y + entry.height))
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            }
            MarkAreaPopover(entry.rect, entry.existing?.marks, guess,
                onConfirm = { marks ->
                    val zone = MarkZone(page.pdfIndex ?: 0, entry.x, entry.y, entry.width, entry.height, marks, manual = true)
                    onReplaceZone(entry.existing, zone)
                    boundInkView?.clearMarkRegion()
                    areaEntry = null
                    // Straight on to awarding it, as if the scan had found it.
                    offeredZone = zone to entry.rect; offerStamp++
                },
                onRemove = entry.existing?.let { old -> { model.clearMark(page.id, old); onReplaceZone(old, null); areaEntry = null } },
                onDismiss = { boundInkView?.clearMarkRegion(); areaEntry = null })
        }
        if (textDraft != null && textFrame?.id == textDraft.id && active && !readOnly && !inputBlocked && page.loaded && ready) {
            InlineTextField(textDraft, textFrame!!, page, onTextDraft, onTextDone, textTopInset,
                onRevealCaret = { boundInkView?.revealTextCaret(textDraft, it, textTopInset) })
        }
        val focusedSticky = sticky?.takeIf { active && !readOnly && !inputBlocked && page.loaded && ready }
        val draft = stickyDraft
        val frame = pageWindowFrame
        if (focusedSticky != null && draft != null && draft.id == focusedSticky.box.id && frame != null) {
            StickyNoteField(focusedSticky.box, focusedSticky.frame, frame.width, frame.height, draft.value,
                onValue = { stickyDraft = draft.copy(value = it) }, onDone = { boundInkView?.finishStickyTyping() })
        }
        if (focusedSticky != null) {
            SelectionContextPopup(focusedSticky.frame, pageWindowFrame, selectionMenuViewport, tightGap = true) {
                val id = focusedSticky.box.id
                StickyNoteContextMenu(focusedSticky.typing, paper = focusedSticky.box.stickyColor,
                    textSize = focusedSticky.box.size, onTextSize = { boundInkView?.setStickyTextSize(id, it) },
                    onType = { boundInkView?.typeInSticky() }, onDone = { boundInkView?.finishStickyTyping() },
                    onDraw = onStickyDraw.takeIf { tool !in DrawingTools },
                    onDelete = { stickyDraft = null; boundInkView?.deleteSticky(id) },
                    onPaper = { boundInkView?.setStickyPaper(id, it) },
                    onDuplicate = { commitStickyDraft(); boundInkView?.duplicateSticky(id) },
                    onFront = { boundInkView?.bringStickyToFront(id) },
                    onClearInk = if (focusedSticky.box.stickyInk.isNotEmpty()) ({ boundInkView?.clearStickyInk(id) }) else null)
            }
        }
        if (selectionMenu != null && page.loaded && ready) {
            SelectionContextPopup(selectionAnchor, pageWindowFrame, selectionMenuViewport, tightGap = selectedImageId != null, content = selectionMenu)
        }
    }
}
