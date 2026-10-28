package com.folio.notes.editor.layout

import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioViewModel
import com.folio.notes.document.DocumentToolEdits
import com.folio.notes.editor.EditorInteractionState
import com.folio.notes.editor.EditorSurfaceRegistry
import com.folio.notes.editor.EditorSurfaceRole
import com.folio.notes.editor.follow.WritingLane
import com.folio.notes.editor.selection.ObjectSnapping
import com.folio.notes.ink.InkGeometry
import com.folio.notes.ink.InkRenderer
import com.folio.notes.ink.graph.GraphAxes
import com.folio.notes.ink.graph.GraphStyle
import com.folio.notes.ink.recognition.InkConversionDraft
import com.folio.notes.ink.recognition.InkGestureRecognizer
import com.folio.notes.ink.recognition.InkRecognitionSnapshot
import com.folio.notes.ink.recognition.InkToText
import com.folio.notes.ink.recognition.ScribbleSensitivity
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.PageImage
import com.folio.notes.model.PageLayers
import com.folio.notes.model.ShapePickerTools
import com.folio.notes.model.Stroke
import com.folio.notes.model.StrokeStyle
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import com.folio.notes.storage.PageContent
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.content

/** Native contacts stay in their originating InkView. This coordinator receives admitted, smoothed
 * page samples and updates native previews directly; Compose sees only selection/commit boundaries. */
internal class DocumentInputCoordinator(private val surfaces: EditorSurfaceRegistry, private val interaction: EditorInteractionState) {
    private var note: Notebook? = null
    private var model: FolioViewModel? = null
    private var seam = 0f
    private var pageById = emptyMap<String, NotePage>()
    private var capture: DocumentInkCapture? = null
    private var recognitionEpoch = 0L
    private var eraserSession = DocumentToolEdits.EraserSession()
    private var frameDrifted = false
    private var lasso = false
    private var revisions = emptyMap<String, Int>()
    private var selectionFrames = emptyMap<String, DocumentInkPage>()

    fun configure(notebook: Notebook, viewModel: FolioViewModel, seamPx: Float) {
        if (note?.id != null && note?.id != notebook.id) cancel()
        val selected = interaction.documentSelection.value
        if (selected.any { (id, value) ->
                val page = notebook.pages.firstOrNull { it.id == id }
                page == null || !page.strokes.containsAll(value.strokes) || !page.texts.containsAll(value.texts) || !page.images.containsAll(value.images) ||
                value.strokes.any { !PageLayers.editable(page.layers, it.layer) } || value.texts.any { !PageLayers.editable(page.layers, it.layer) } || value.images.any { !PageLayers.editable(page.layers, it.layer) }
            }) clearSelection()
        if (seamPx != seam) capture?.invalidate()
        note = notebook; model = viewModel; seam = seamPx
        pageById = notebook.pages.associateBy { it.id }
    }
    private fun frames(sourceId: String): List<DocumentInkPage> {
        if (interaction.transfers.paperFrame(sourceId) == null) return emptyList()
        // Only hosted/nearby paper can receive a captured contact. Geometry is frozen once,
        // without traversing the notebook or loading content on the high-frequency path.
        return interaction.transfers.visiblePaperFrames().mapNotNull { (id, paper) ->
            val page = pageById[id]?.takeUnless { it.infinite } ?: return@mapNotNull null
            val workspace = interaction.transfers.workspaceFrame(id) ?: paper
            val layer = model?.activeLayerOf(page) ?: 0
            fun androidx.compose.ui.geometry.Rect.bounds() = DocumentPageGeometry.Bounds(left, top, right, bottom)
            DocumentInkPage(DocumentPageGeometry.Frame(id, page.width, page.height, paper.bounds(), workspace.bounds()),
                layer, page.loaded && PageLayers.editable(page.layers, layer), PageLayers.view(page).texts.filter { it.isSticky }, page.layers, page.loaded)
        }
    }
    fun begin(sourceId: String, stroke: Stroke, isLasso: Boolean): Boolean {
        if (capture != null) return false
        val pages = frames(sourceId).map { hosted ->
            val frame = if (hosted.frame.pageId == sourceId) hosted.copy(layer = stroke.layer) else hosted
            if (stroke.tool == Tool.MARK_AREA) frame.copy(notes = emptyList()) else frame
        }
        if (pages.none { it.frame.pageId == sourceId }) return false
        recognitionEpoch++
        clearSelection()
        eraserSession = DocumentToolEdits.EraserSession()
        frameDrifted = false
        capture = DocumentInkCapture(sourceId, stroke, pages)
        lasso = isLasso
        revisions = note?.pages?.associate { it.id to it.revision }.orEmpty()
        capture?.add(stroke.points, isLasso)
        return true
    }
    private fun routed(current: DocumentInkCapture): DocumentInkCapture {
        val view = surfaces.find(EditorSurfaceRole.MAIN, current.sourceId)
        view?.documentHeldShape?.let { return current.generated(it) }
        if (current.template.tool !in ShapePickerTools) return current
        var drag = current.dragStroke()
        if (view?.snapEnabled == true && drag.points.size >= 2) {
            var end = drag.points.last()
            val page = pageById.getValue(current.sourceId)
            if (page.paper.isGrid) end = InkGeometry.snapToGrid(end, page.paper.gridSpacing)
            if (drag.tool == Tool.LINE) end = InkGeometry.snapAngle(drag.points.first(), end, 15f)
            drag = drag.copy(points = listOf(drag.points.first(), end))
        }
        return current.generated(if (drag.tool == Tool.GRAPH) GraphAxes.strokes(drag, view?.graphStyle ?: GraphStyle.DEFAULT) else listOf(drag))
    }
    private fun toolOptions(current: DocumentInkCapture): DocumentToolEdits.Options {
        val view = surfaces.find(EditorSurfaceRole.MAIN, current.sourceId)
        val held = view?.documentHeldErase == true
        return DocumentToolEdits.Options(current.template.tool == Tool.ERASER, view?.eraserWholeStroke == true,
            view?.eraserPressureEnabled == true && view.hasDocumentStylusContact, held,
            view?.scribbleSensitivity ?: ScribbleSensitivity.DEFAULT, scribbleInk = !held)
    }
    private fun contentAfter(current: DocumentInkCapture): Map<String, PageContent> {
        val strokes = current.strokes(); val notes = current.noteStrokes()
        val options = toolOptions(current)
        return (strokes.keys + notes.keys).associateWith { id ->
            if (options.erasing) eraserSession.content(pageById.getValue(id), strokes[id].orEmpty(), notes[id].orEmpty(), options)
            else DocumentToolEdits.content(pageById.getValue(id), strokes[id].orEmpty(), notes[id].orEmpty(), options)
        }
    }
    private fun commitContents(current: DocumentInkCapture): List<Map<String, PageContent>> {
        val options = toolOptions(current)
        // A held scrub is erasure only: its drawing is never committed first.
        if (options.erasing || options.scribble) return listOf(contentAfter(current))
        val strokes = current.strokes(); val notes = current.noteStrokes()
        val steps = (strokes.keys + notes.keys).associateWith { id ->
            DocumentToolEdits.contentSteps(pageById.getValue(id), strokes[id].orEmpty(), notes[id].orEmpty(), options)
        }
        val after = steps.mapValues { it.value.last() }
        return if (steps.values.any { it.size > 1 }) listOf(steps.mapValues { it.value.first() }, after) else listOf(after)
    }
    fun samples(sourceId: String, points: List<InkPoint>): Boolean {
        val current = capture?.takeIf { it.sourceId == sourceId } ?: return false
        if (current.template.tool == Tool.LASER) {
            val windows = points.map(current::toWindow)
            current.pages.filter { it.frame.pageId != sourceId }.forEach { page ->
                val local = windows.filter { page.frame.workspace.contains(it.x, it.y) }.map { p ->
                    val q = page.frame.toPage(p.x, p.y)!!; InkPoint(q.x, q.y, p.pressure)
                }
                surfaces.find(EditorSurfaceRole.MAIN, page.frame.pageId)?.let { view ->
                    if (local.isNotEmpty()) view.addDocumentLaser(local) else view.finishDocumentLaser()
                }
            }
            return true
        }
        val actual = interaction.transfers.paperFrame(sourceId)
        val original = current.sourceFrame.paper
        if (actual == null || kotlin.math.abs(actual.left - original.left) > .5f || kotlin.math.abs(actual.top - original.top) > .5f ||
            kotlin.math.abs(actual.width - original.width) > .5f) {
            // A nudge of the paper (a scroll, a toolbar relayout) only ruins the frozen geometry that
            // maps the stroke onto *other* pages. While it stays on its own page the page-local
            // samples are still exact, so the gesture carries on; it is blocked only if it later crosses.
            if (current.spanning) current.invalidate() else { frameDrifted = true; current.add(points, lasso) }
        } else current.add(points, lasso)
        if (frameDrifted && current.spanning) current.invalidate()
        if (current.template.tool == Tool.MARK_AREA) {
            val parts = DocumentMarking.parts(current, pageById)
            surfaces.mainSurfaces().forEach { view ->
                view.documentMarkRegionPreview = parts?.firstOrNull { it.pageId == view.page.id }?.let { WritingLane(it.x, it.y, it.x + it.width, it.y + it.height) }
            }
            return parts == null || parts.size > 1
        }
        val routed = routed(current)
        if (current.spanning || routed.spanning) {
            val erasing = current.template.tool == Tool.ERASER
            val strokes = if (!lasso && !routed.blocked && !erasing) routed.strokes() else emptyMap()
            val contents = if (!lasso && !routed.blocked) contentAfter(routed) else emptyMap()
            val predict = surfaces.find(EditorSurfaceRole.MAIN, sourceId)?.hasDocumentStylusContact == true
            surfaces.mainSurfaces().forEach { view ->
                view.documentPreviewLiveStroke = if (predict && current.livePageId == view.page.id) strokes[view.page.id]?.lastOrNull() else null
                view.documentInkPreview = strokes[view.page.id].orEmpty()
                view.documentContentPreview = contents[view.page.id]?.let { content ->
                    view.page.copy(strokes = if (erasing) content.strokes else view.page.strokes, texts = content.texts)
                }
                if (lasso) {
                    val frame = current.page(view.page.id)?.frame
                    val preview = (view.documentLassoPreview as? ArrayList<InkPoint>) ?: ArrayList()
                    if (frame != null) for (i in preview.size until current.loop.size) {
                        val p = current.loop[i]; val q = frame.toPage(p.x, p.y)!!; preview += InkPoint(q.x, q.y, p.pressure)
                    }
                    view.documentLassoPreview = preview
                } else view.documentLassoPreview = emptyList()
            }
        }
        return current.spanning || routed.spanning
    }
    fun finish(sourceId: String): Boolean {
        val current = capture?.takeIf { it.sourceId == sourceId } ?: return false
        if (current.template.tool == Tool.LASER) { cancel(); return true }
        if (current.template.tool == Tool.MARK_AREA) {
            val parts = DocumentMarking.parts(current, pageById)
            capture = null; clearPreviews()
            if (parts == null) { model?.reportError("The marking area needs loaded PDF pages and a stable layout; drag it again"); return true }
            if (parts.size < 2) return false
            val source = parts.firstOrNull { it.pageId == sourceId } ?: return true
            if (source.width >= 16f && parts.sumOf { it.height.toDouble() } >= 8f) surfaces.find(EditorSurfaceRole.MAIN, sourceId)?.showDocumentMarkRegion(parts)
            return true
        }
        val stickySelection = lasso && current.pages.any { page ->
            val loop = current.loopFor(page.frame)
            val bounds = InkGeometry.lassoBounds(loop)
            page.notes.any { InkGeometry.lassoSelectsText(loop, bounds, it, InkRenderer.textHeight(it)) }
        }
        val spanning = current.spanning || routed(current).spanning || stickySelection
        capture = null
        clearPreviews()
        if (!spanning) return false
        val note = note ?: return true
        val model = model ?: return true
        if (current.blocked) { model.reportError("The page layout changed or a crossed page is unavailable. Draw the stroke again"); return true }
        if (lasso) {
            val bounds = InkGeometry.lassoBounds(current.loop)
            val pageById = note.pages.associateBy { it.id }
            val candidates = current.pages.filter { page -> bounds[0] < page.frame.workspace.right && bounds[2] > page.frame.workspace.left && bounds[1] < page.frame.workspace.bottom && bounds[3] > page.frame.workspace.top }.map { it.frame.pageId }.toHashSet()
            if (current.pages.any { page -> !pageById.getValue(page.frame.pageId).loaded &&
                    bounds[0] < page.frame.workspace.right && bounds[2] > page.frame.workspace.left && bounds[1] < page.frame.workspace.bottom && bounds[3] > page.frame.workspace.top }) {
                model.reportError("Wait for the pages inside the lasso to load"); return true
            }
            val frames = current.pages.associateBy { it.frame.pageId }
            val selected = note.pages.filter { it.loaded && it.id in candidates }.mapNotNull { page ->
                val loop = current.loopFor(frames.getValue(page.id).frame)
                if (loop.size < 3) return@mapNotNull null
                val bounds = InkGeometry.lassoBounds(loop)
                val selection = CanvasSelection(
                    page.strokes.filter { PageLayers.editable(page.layers, it.layer) && InkGeometry.lassoSelects(loop, bounds, it) },
                    page.texts.filter { PageLayers.editable(page.layers, it.layer) && InkGeometry.lassoSelectsText(loop, bounds, it, InkRenderer.textHeight(it)) },
                    page.images.filter { PageLayers.editable(page.layers, it.layer) && InkGeometry.lassoSelectsImage(loop, bounds, it) })
                if (selection.isEmpty()) null else page.id to selection
            }.toMap()
            selectionFrames = frames
            interaction.documentSelection.value = selected
            surfaces.mainSurfaces().forEach { it.showDocumentSelection(selected[it.page.id] ?: CanvasSelection()) }
        } else {
            val routed = routed(current)
            if (routed.blocked) { model.reportError("A crossed surface is locked or unavailable; draw again"); return true }
            val originalPages = pageById
            val source = surfaces.find(EditorSurfaceRole.MAIN, sourceId)
            val drawn = if (source?.scribbleToErase == true && source.documentHeldShape == null && !source.documentHeldErase &&
                    current.template.tool in setOf(Tool.PEN, Tool.HIGHLIGHTER))
                current.dragStroke().let { it.copy(points = it.points.toList()) } else null
            val scribble = drawn != null && source!!.canClassifyScribble(drawn)
            val paths = if (scribble) routed.strokes().mapValues { (_, ink) -> ink.map { it.copy(points = it.points.toList()) } } else emptyMap()
            val notes = if (scribble) routed.noteStrokes().mapValues { (_, boxes) -> boxes.mapValues { (_, ink) -> ink.map { it.copy(points = it.points.toList()) } } } else emptyMap()
            var before = pageById
            var expectedRevisions = revisions
            for (contents in commitContents(routed)) {
                val edits = contents.mapNotNull { (id, content) -> PageJournal.diff(before.getValue(id).content(), content)?.let { id to it } }.toMap()
                if (edits.isEmpty()) continue
                val expected = edits.keys.associateWith { expectedRevisions.getValue(it) }
                // The first step must still match the contact-down revisions; later steps use the
                // just-published drawing, including its incremented member revisions.
                if (!model.commitDocumentEdits(note.id, edits, expected)) {
                    model.reportError("The document changed during this gesture; try again")
                    break
                }
                val committed = model.state.value.active?.takeIf { it.id == note.id }?.pages?.associateBy { it.id }.orEmpty()
                surfaces.mainSurfaces().forEach { view -> if (view.page.id in edits) committed[view.page.id]?.let { view.bind(it, view.background, view.imageBitmaps) } }
                before = committed
                expectedRevisions = committed.mapValues { it.value.revision }
            }
            val ids = paths.keys + notes.keys
            val expected = model.state.value.active?.takeIf { it.id == note.id }?.pages
                ?.filter { it.id in ids }?.associateBy { it.id }.orEmpty()
            // Only classify successfully committed gestures; never reinterpret a refused transaction.
            if (scribble && drawn != null && ids.all { id -> expected[id]?.revision == revisions.getValue(id) + 1 }) {
                val epoch = recognitionEpoch
                val guards = expected.mapValues { InkRecognitionSnapshot(it.value) }
                InkGestureRecognizer.scribbleDocument(drawn, originalPages.filterKeys { it in ids }, paths, notes,
                    source!!.scribbleSensitivity) { contents ->
                    val active = model.state.value.active
                    if (epoch != recognitionEpoch || active?.id != note.id ||
                        !source.scribbleToErase || source.readOnly || source.inputBlocked || !source.isAttachedToWindow ||
                        ids.any { id -> !guards.getValue(id).matches(active.pages.find { it.id == id }) }) return@scribbleDocument
                    val edits = contents.mapNotNull { (id, content) ->
                        PageJournal.diff(expected.getValue(id).content(), content)?.let { id to it }
                    }.toMap()
                    if (edits.isNotEmpty() && model.commitDocumentEdits(note.id, edits,
                            edits.keys.associateWith { expected.getValue(it).revision })) {
                        val committed = model.state.value.active?.pages?.associateBy { it.id }.orEmpty()
                        surfaces.mainSurfaces().forEach { view -> if (view.page.id in edits)
                            committed[view.page.id]?.let { view.bind(it, view.background, view.imageBitmaps) } }
                    }
                }
            }
        }
        if (current.template.tool == Tool.ERASER) surfaces.find(EditorSurfaceRole.MAIN, sourceId)?.let { if (it.tool == Tool.ERASER) it.onEraserFinished?.invoke() }
        return true
    }
    fun isHistoryBusy(): Boolean = model?.state?.value?.documentHistoryBusy == true
    fun cancelNativeContacts() { surfaces.editingSurfaces().forEach { it.cancelExternalGesture() }; cancel() }
    fun cancel() { recognitionEpoch++; capture = null; surfaces.mainSurfaces().forEach { it.finishDocumentLaser() }; clearPreviews() }
    private fun clearPreviews() { surfaces.mainSurfaces().forEach { it.documentMarkRegionPreview = null; it.documentContentPreview = null; it.documentInkPreview = emptyList(); it.documentPreviewLiveStroke = null; it.documentLassoPreview = emptyList() } }
    fun selectionOn(id: String): CanvasSelection? = interaction.documentSelection.value[id]
    fun selectedOn(id: String): Boolean = interaction.documentSelection.value[id]?.isNotEmpty() == true
    fun clearSelection() {
        interaction.documentSelection.value = emptyMap(); interaction.selection.value = null; selectionFrames = emptyMap(); snapSelection = null; snapBounds = null; snapX = emptyList(); snapY = emptyList()
        surfaces.mainSurfaces().forEach { it.showDocumentSelection(CanvasSelection()) }
    }
    fun deleteSelection() {
        val note = note ?: return; val model = model ?: return
        val selected = interaction.documentSelection.value
        val edits = selected.mapValues { (id, value) ->
            val page = note.pages.first { it.id == id }
            PageJournal.diff(page.content(), page.content().copy(strokes = page.strokes.filterNot { it in value.strokes },
                texts = page.texts.filterNot { it in value.texts }, images = page.images.filterNot { it in value.images }))!!
        }
        if (model.commitDocumentEdits(note.id, edits, selected.keys.associateWith { id -> note.pages.first { it.id == id }.revision })) clearSelection()
    }
    fun duplicateSelection() {
        val note = note ?: return
        model?.duplicateDocumentSelection(note.id, interaction.documentSelection.value) { clearSelection() }
    }
    fun restyleSelection(color: Int?, widthScale: Float?, opacity: Float?, style: StrokeStyle?, brush: StrokeStyle? = null) {
        val note = note ?: return; val model = model ?: return
        val selected = interaction.documentSelection.value
        val edits = selected.mapNotNull { (id, value) ->
            val page = pageById[id] ?: return@mapNotNull null
            val after = DocumentToolEdits.restyle(page, value, color, widthScale, opacity, style, brush)
            PageJournal.diff(page.content(), after)?.let { id to it }
        }.toMap()
        if (model.commitDocumentEdits(note.id, edits, selected.keys.associateWith { pageById.getValue(it).revision })) clearSelection()
    }
    /** Recognition finishes before one revision-checked commit touches any selected page. */
    suspend fun prepareTextConversion(): InkConversionDraft? {
        val note = note ?: return null
        val selected = interaction.documentSelection.value
        val pages = LinkedHashMap<String, Pair<NotePage, PageContent>>()
        for ((id, selection) in selected) {
            val page = pageById[id] ?: continue
            val after = InkToText.convert(page, selection)
            if (PageJournal.diff(page.content(), after) != null) pages[id] = page to after
        }
        return if (pages.isEmpty()) { model?.reportError("No readable text in this selection"); null } else InkConversionDraft(note.id, pages)
    }
    fun moveSelectionToLayer(layer: Int) {
        val note = note ?: return; val model = model ?: return
        val selected = interaction.documentSelection.value
        if (selected.keys.any { id -> val page = pageById.getValue(id)
                PageLayers.effective(page.layers).none { it.id == layer } || !PageLayers.editable(page.layers, layer) }) {
            model.reportError("Choose a layer present and editable on every selected page"); return
        }
        val edits = selected.mapNotNull { (id, value) ->
            val page = pageById.getValue(id)
            val strokes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(value.strokes) }
            val texts = value.texts.map { it.id }.toSet(); val images = value.images.map { it.id }.toSet()
            val after = page.content().copy(strokes = page.strokes.map { if (it in strokes) it.copy(layer = layer) else it },
                texts = page.texts.map { if (it.id in texts) it.copy(layer = layer) else it }, images = page.images.map { if (it.id in images) it.copy(layer = layer) else it })
            PageJournal.diff(page.content(), after)?.let { id to it }
        }.toMap()
        if (model.commitDocumentEdits(note.id, edits, selected.keys.associateWith { pageById.getValue(it).revision })) clearSelection()
    }
    private var snapSelection: Map<String, CanvasSelection>? = null
    private var snapBounds: androidx.compose.ui.geometry.Rect? = null
    private var snapX: List<Pair<Float, Float>> = emptyList()
    private var snapY: List<Pair<Float, Float>> = emptyList()
    private fun snappedSelectionDelta(sourceId: String, dx: Float, dy: Float): Pair<Float, Float> {
        if (dx == 0f && dy == 0f) return dx to dy
        val view = surfaces.find(EditorSurfaceRole.MAIN, sourceId) ?: return dx to dy
        if (view.selectionSnapBypassed || !view.context.getSharedPreferences("preferences", 0).getBoolean(AppPrefs.UX_SNAP, AppPrefs.DEFAULT_UX_SNAP)) return dx to dy
        val selected = interaction.documentSelection.value
        val firstId = selected.keys.firstOrNull() ?: return dx to dy
        val firstPage = pageById[firstId] ?: return dx to dy
        val firstFrame = interaction.transfers.paperFrame(firstId) ?: return dx to dy
        val sourceFrame = interaction.transfers.paperFrame(sourceId) ?: return dx to dy
        val sourcePage = pageById[sourceId] ?: return dx to dy
        val firstUnit = firstFrame.width / firstPage.width
        val sourceUnit = sourceFrame.width / sourcePage.width
        if (firstUnit <= 0f || sourceUnit <= 0f) return dx to dy
        if (snapSelection !== selected) {
            snapSelection = selected
            val flat = flattened()
            snapBounds = InkGeometry.contentBounds(flat.strokes, flat.texts, flat.images, InkRenderer::textHeight, firstPage.width, firstPage.height)
            val picked = selected[firstId] ?: CanvasSelection()
            val visible = PageLayers.view(firstPage)
            val items = visible.strokes.filterNot { it in picked.strokes }.take(200).map { CanvasSelection(strokes = listOf(it)) } + visible.texts.filterNot { it in picked.texts }.take(100).map { CanvasSelection(texts = listOf(it)) } + visible.images.filterNot { it in picked.images }.take(100).map { CanvasSelection(images = listOf(it)) }
            val bounds = items.map { InkGeometry.contentBounds(it.strokes, it.texts, it.images, InkRenderer::textHeight, firstPage.width, firstPage.height) }
            snapX = bounds.map { it.left to it.right }; snapY = bounds.map { it.top to it.bottom }
        }
        val bounds = snapBounds ?: return dx to dy
        val ratio = sourceUnit / firstUnit
        return ObjectSnapping.offset(bounds.left, bounds.right, dx * ratio, firstPage.width, snapX, 8f / firstUnit) / ratio to
            ObjectSnapping.offset(bounds.top, bounds.bottom, dy * ratio, firstPage.height.takeIf { selected.size == 1 }, snapY, 8f / firstUnit) / ratio
    }
    fun dragSelection(sourceId: String, rawDx: Float, rawDy: Float, commit: Boolean): Boolean {
        val (dx, dy) = snappedSelectionDelta(sourceId, rawDx, rawDy)
        if (commit) {
            val result = transformSelection(1f, 0f, dx, dy, sourceId)
            surfaces.mainSurfaces().forEach { it.previewDocumentSelection(0f, 0f) }
            return result
        }
        val source = interaction.transfers.paperFrame(sourceId) ?: return false
        val page = pageById[sourceId] ?: return false
        val unit = source.width / page.width
        surfaces.mainSurfaces().forEach { view ->
            val target = interaction.transfers.paperFrame(view.page.id) ?: return@forEach
            val scale = unit * view.page.width / target.width
            view.previewDocumentSelection(dx * scale, dy * scale)
        }
        return true
    }
    fun transformSelection(factor: Float, degrees: Float, dx: Float = 0f, dy: Float = 0f, sourceId: String? = null): Boolean {
        val note = note ?: return false; val model = model ?: return false
        val selected = interaction.documentSelection.value
        val firstId = selected.keys.firstOrNull() ?: return false
        val frames = frames(firstId)
        if (selected.keys.any { id -> frames.none { it.frame.pageId == id } }) {
            model.reportError("Bring the selected pages into view to resize or rotate them"); return false
        }
        selectionFrames = frames.associateBy { it.frame.pageId }
        val flat = flattened()
        val center = InkGeometry.selectionCenter(flat.strokes, flat.texts, flat.images, InkRenderer::textHeight) ?: return false
        val first = frames.first { it.frame.pageId == firstId }.frame
        val source = frames.firstOrNull { it.frame.pageId == sourceId }?.frame ?: first
        val deltaScale = source.paper.width / source.width * first.width / first.paper.width
        val moveX = dx * deltaScale; val moveY = dy * deltaScale
        val strokes = InkGeometry.rotate(InkGeometry.scale(flat.strokes, center, factor), center, degrees).map { InkGeometry.translate(it, moveX, moveY) }
        val texts = InkGeometry.rotateTexts(InkGeometry.scaleTexts(flat.texts, center, factor), center, degrees).map { it.moved(moveX, moveY) }
        val images = InkGeometry.rotateImages(InkGeometry.scaleImages(flat.images, center, factor), center, degrees).map { it.moved(moveX, moveY) }
        val contents = selected.mapValues { (id, value) ->
            val page = pageById.getValue(id)
            page.content().copy(strokes = page.strokes.filterNot { it in value.strokes },
                texts = page.texts.filterNot { it in value.texts }, images = page.images.filterNot { it in value.images })
        }.toMutableMap()
        val targets = frames.map { frame -> frame.copy(notes = frame.notes.filterNot { box -> selected[frame.frame.pageId]?.texts?.any { it.id == box.id } == true }) }
        fun page(id: String): NotePage = pageById.getValue(id).let { p -> contents[id]?.let { p.copy(strokes = it.strokes, texts = it.texts, images = it.images) } ?: p }
        val left = frames.minOf { it.frame.paper.left }; val right = frames.maxOf { it.frame.paper.right }
        val top = frames.minOf { it.frame.paper.top }; val bottom = frames.maxOf { it.frame.paper.bottom }
        if (strokes.any { stroke -> InkGeometry.pathPoints(stroke).any { point ->
                val window = first.toWindow(point.x, point.y)!!
                window.x < left || window.x > right || window.y < top || window.y > bottom
            } }) {
            model.reportError("The transformed ink extends beyond the visible paper; show more pages or use a smaller size"); return false
        }
        for (stroke in strokes) {
            val strokeTargets = targets.map { target ->
                val layer = stroke.layer.takeIf { id -> PageLayers.effective(target.layers).any { it.id == id } && PageLayers.editable(target.layers, id) } ?: target.layer
                target.copy(layer = layer, writable = target.loaded && PageLayers.editable(target.layers, layer))
            }
            val capture = DocumentInkCapture(firstId, stroke, strokeTargets)
            val routed = if (stroke.tool in ShapePickerTools) capture.generated(listOf(stroke)) else capture.also { it.add(stroke.points, false) }
            if (routed.blocked || routed.strokes().isEmpty() && routed.noteStrokes().isEmpty()) {
                model.reportError("The transformed ink needs visible, editable paper"); return false
            }
            val ink = routed.strokes(); val notes = routed.noteStrokes()
            for (id in ink.keys + notes.keys) contents[id] = DocumentToolEdits.content(page(id), ink[id].orEmpty(), notes[id].orEmpty(), DocumentToolEdits.Options())
        }
        val index = DocumentPageGeometry.Index(frames.map { it.frame })
        fun destination(x: Float, y: Float): Pair<DocumentInkPage, DocumentPageGeometry.Point>? {
            val window = first.toWindow(x, y) ?: return null
            val frame = index.nearest(window.y) ?: return null
            val target = frames.first { it.frame.pageId == frame.pageId }
            if (!target.loaded || !target.writable) return null
            return target to frame.toPage(window.x, window.y)!!
        }
        for (box in texts) {
            val (target, at) = destination(box.x + box.width / 2f, box.y + (if (box.isSticky) box.stickyHeight else InkRenderer.textHeight(box)) / 2f)
                ?: run { model.reportError("The destination needs visible, editable paper"); return false }
            val scale = first.paper.width / first.width * target.frame.width / target.frame.paper.width
            val height = if (box.isSticky) box.stickyHeight else InkRenderer.textHeight(box)
            val moved = box.copy(x = at.x - box.width * scale / 2f, y = at.y - height * scale / 2f, width = box.width * scale,
                size = box.size * scale, stickyHeight = box.stickyHeight * scale, stickyInk = InkGeometry.scale(box.stickyInk, InkPoint(0f, 0f), scale), layer = box.layer.takeIf { id -> PageLayers.effective(target.layers).any { it.id == id } && PageLayers.editable(target.layers, id) } ?: target.layer)
            val p = page(target.frame.pageId)
            contents[p.id] = p.content().copy(texts = p.texts + moved)
        }
        for (image in images) {
            val (target, at) = destination(image.x + image.width / 2f, image.y + image.height / 2f)
                ?: run { model.reportError("The destination needs visible, editable paper"); return false }
            val scale = first.paper.width / first.width * target.frame.width / target.frame.paper.width
            val moved = image.copy(x = at.x - image.width * scale / 2f, y = at.y - image.height * scale / 2f,
                width = image.width * scale, height = image.height * scale, layer = image.layer.takeIf { id -> PageLayers.effective(target.layers).any { it.id == id } && PageLayers.editable(target.layers, id) } ?: target.layer)
            val p = page(target.frame.pageId)
            contents[p.id] = p.content().copy(images = p.images + moved)
        }
        val edits = contents.mapNotNull { (id, after) -> PageJournal.diff(pageById.getValue(id).content(), after)?.let { id to it } }.toMap()
        val committed = edits.isEmpty() || model.commitDocumentEdits(note.id, edits, edits.keys.associateWith { pageById.getValue(it).revision })
        if (committed) clearSelection()
        return committed
    }
    fun copySelection() { model?.copyToClipboard(flattened()) }
    fun flattened(): CanvasSelection {
        val selected = interaction.documentSelection.value
        val first = selected.keys.firstOrNull()?.let { selectionFrames[it] } ?: return CanvasSelection()
        val unit = first.frame.paper.width / first.frame.width
        val strokes = ArrayList<Stroke>(); val texts = ArrayList<TextBox>(); val images = ArrayList<PageImage>()
        selected.forEach { (id, selection) ->
            val frame = selectionFrames[id]?.frame ?: return@forEach
            val scale = frame.paper.width / frame.width / unit
            val dx = (frame.paper.left - first.frame.paper.left) / unit; val dy = (frame.paper.top - first.frame.paper.top) / unit
            selection.strokes.forEach { stroke -> strokes += stroke.copy(width = stroke.width * scale,
                points = stroke.points.map { InkPoint(it.x * scale + dx, it.y * scale + dy, it.pressure) }) }
            selection.texts.forEach { texts += it.copy(x = it.x * scale + dx, y = it.y * scale + dy, width = it.width * scale,
                size = it.size * scale, stickyHeight = it.stickyHeight * scale, stickyInk = InkGeometry.scale(it.stickyInk, InkPoint(0f, 0f), scale)) }
            selection.images.forEach { images += it.copy(x = it.x * scale + dx, y = it.y * scale + dy, width = it.width * scale, height = it.height * scale) }
        }
        return CanvasSelection(strokes, texts, images)
    }
    fun moveToPage(targetId: String): Boolean {
        val note = note ?: return false; val model = model ?: return false
        val target = note.pages.firstOrNull { it.id == targetId } ?: return false
        val layer = model.activeLayerOf(target)
        if (!target.loaded || !PageLayers.editable(target.layers, layer)) { model.reportError("Load the destination and choose an unlocked layer"); return false }
        model.moveDocumentSelection(note.id, interaction.documentSelection.value, flattened(), targetId) { clearSelection() }
        return true
    }
}

internal val LocalDocumentInput = androidx.compose.runtime.staticCompositionLocalOf<DocumentInputCoordinator?> { null }
