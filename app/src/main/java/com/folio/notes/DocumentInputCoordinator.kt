package com.folio.notes

/** Native contacts stay in their originating InkView. This coordinator receives admitted, smoothed
 * page samples and updates native previews directly; Compose sees only selection/commit boundaries. */
internal class DocumentInputCoordinator(private val surfaces: EditorSurfaceRegistry, private val interaction: EditorInteractionState) {
    private var note: Notebook? = null
    private var model: FolioViewModel? = null
    private var seam = 0f
    private var pageById = emptyMap<String, NotePage>()
    private var capture: DocumentInkCapture? = null
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
                layer, page.loaded && PageLayers.editable(page.layers, layer))
        }
    }
    fun begin(sourceId: String, stroke: Stroke, isLasso: Boolean): Boolean {
        if (capture != null) return false
        val pages = frames(sourceId).map { if (it.frame.pageId == sourceId) it.copy(layer = stroke.layer) else it }
        if (pages.none { it.frame.pageId == sourceId }) return false
        clearSelection()
        capture = DocumentInkCapture(sourceId, stroke, pages)
        lasso = isLasso
        revisions = note?.pages?.associate { it.id to it.revision }.orEmpty()
        capture?.add(stroke.points, isLasso)
        return true
    }
    fun samples(sourceId: String, points: List<InkPoint>): Boolean {
        val current = capture?.takeIf { it.sourceId == sourceId } ?: return false
        val actual = interaction.transfers.paperFrame(sourceId)
        val original = current.sourceFrame.paper
        if (actual == null || kotlin.math.abs(actual.left - original.left) > .5f || kotlin.math.abs(actual.top - original.top) > .5f ||
            kotlin.math.abs(actual.width - original.width) > .5f) current.invalidate()
        else current.add(points, lasso)
        if (current.spanning) {
            val strokes = if (!lasso && !current.blocked) current.strokes() else emptyMap()
            val predict = surfaces.find(EditorSurfaceRole.MAIN, sourceId)?.hasDocumentStylusContact == true
            surfaces.mainSurfaces().forEach { view ->
                view.documentPreviewLiveStroke = if (predict && current.livePageId == view.page.id) strokes[view.page.id]?.lastOrNull() else null
                view.documentInkPreview = strokes[view.page.id].orEmpty()
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
        return current.spanning
    }
    fun finish(sourceId: String): Boolean {
        val current = capture?.takeIf { it.sourceId == sourceId } ?: return false
        val spanning = current.spanning
        capture = null
        clearPreviews()
        if (!spanning) return false
        val note = note ?: return true
        val model = model ?: return true
        if (current.blocked) { model.reportError("The page layout changed or a crossed page is unavailable. Draw the stroke again"); return true }
        if (lasso) {
            val bounds = InkGeometry.lassoBounds(current.loop)
            val pageById = note.pages.associateBy { it.id }
            val candidates = current.pages.filter { page -> bounds[0] < page.frame.paper.right && bounds[2] > page.frame.paper.left && bounds[1] < page.frame.paper.bottom && bounds[3] > page.frame.paper.top }.map { it.frame.pageId }.toHashSet()
            if (current.pages.any { page -> !pageById.getValue(page.frame.pageId).loaded &&
                    bounds[0] < page.frame.paper.right && bounds[2] > page.frame.paper.left && bounds[1] < page.frame.paper.bottom && bounds[3] > page.frame.paper.top }) {
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
            val strokes = current.strokes().mapValues { (_, values) -> values.map { it.copy(points = it.points.toList()) } }
            if (!model.appendDocumentStrokes(note.id, strokes, revisions)) model.reportError("The document changed during this stroke; draw it again")
            else {
                val committed = model.state.value.active?.takeIf { it.id == note.id }?.pages?.associateBy { it.id }.orEmpty()
                surfaces.mainSurfaces().forEach { view -> if (view.page.id in strokes) committed[view.page.id]?.let { view.bind(it, view.background, view.imageBitmaps) } }
            }
        }
        return true
    }
    fun isHistoryBusy(): Boolean = model?.state?.value?.documentHistoryBusy == true
    fun cancelNativeContacts() { surfaces.editingSurfaces().forEach { it.cancelExternalGesture() }; cancel() }
    fun cancel() { capture = null; clearPreviews() }
    private fun clearPreviews() { surfaces.mainSurfaces().forEach { it.documentInkPreview = emptyList(); it.documentPreviewLiveStroke = null; it.documentLassoPreview = emptyList() } }
    fun selectionOn(id: String): CanvasSelection? = interaction.documentSelection.value[id]
    fun selectedOn(id: String): Boolean = interaction.documentSelection.value[id]?.isNotEmpty() == true
    fun clearSelection() {
        interaction.documentSelection.value = emptyMap(); interaction.selection.value = null; selectionFrames = emptyMap()
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
    fun restyleSelection(color: Int?, widthScale: Float?, opacity: Float?, style: StrokeStyle?) {
        val note = note ?: return; val model = model ?: return
        val selected = interaction.documentSelection.value
        val edits = selected.mapNotNull { (id, value) ->
            val page = pageById[id] ?: return@mapNotNull null
            val styled = InkGeometry.restyle(value.strokes, color, widthScale, opacity, style)
            val replacements = java.util.IdentityHashMap<Stroke, Stroke>()
            value.strokes.zip(styled).forEach { (before, after) -> if (before != after) replacements[before] = after }
            val positions = page.strokes.mapIndexedNotNull { index, stroke -> replacements[stroke]?.let { IndexedStroke(index, it) } }
            if (positions.isEmpty()) null else id to PageEdit(StrokesEdit.Replace(positions))
        }.toMap()
        if (model.commitDocumentEdits(note.id, edits, selected.keys.associateWith { pageById.getValue(it).revision })) clearSelection()
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
    fun copySelection() { model?.copyToClipboard(flattened()) }
    private fun flattened(): CanvasSelection {
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
