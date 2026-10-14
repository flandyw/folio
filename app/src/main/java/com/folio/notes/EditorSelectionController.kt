package com.folio.notes

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

import com.folio.notes.FolioRuntime.Companion.PASTE_OFFSET

/** Editor commands over the shared document engine; no independent notebook content. */
internal class EditorSelectionController(private val runtime: FolioRuntime) {
    internal var clipboardToken: String? = null

    internal var clipboardNoteId: String? = null

    internal var pasteGeneration = 0

    internal fun identitySet(strokes: List<Stroke>): Set<Stroke> = with(runtime) { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(strokes) } }

    fun copyToClipboard(selection: CanvasSelection) {
        with(runtime) {
            if (selection.isEmpty()) return
            pasteGeneration = 0
            clipboardNoteId = _state.value.active?.id
            clipboardToken = "folio-selection-${UUID.randomUUID()}"
            val plainText = selection.texts.joinToString("\n") { it.text }
            systemClipboard.setPrimaryClip(android.content.ClipData.newPlainText(clipboardToken, plainText))
            _state.update { it.copy(clipboard = selection) }
        }
    }

    fun cutSelection(selection: CanvasSelection) {
        with(runtime) {
            if (selection.isEmpty()) return
            copyToClipboard(selection)
            val page = _state.value.page ?: return
            val doomed = identitySet(selection.strokes)
            updateContent(
                page.id,
                page.strokes.filterNot { it in doomed || it in selection.strokes },
                page.texts.filterNot { box -> selection.texts.any { it.id == box.id } },
                page.images.filterNot { image -> selection.images.any { it.id == image.id } }
            )
        }
    }

    fun deleteSelection(selection: CanvasSelection) {
        with(runtime) {
            if (selection.isEmpty()) return
            val page = _state.value.page ?: return
            val doomed = identitySet(selection.strokes)
            // Fast path: reference check first, structural fallback for reloaded pages.
            val remaining = page.strokes.filterNot { it in doomed || it in selection.strokes }
            updateContent(
                page.id,
                remaining,
                page.texts.filterNot { box -> selection.texts.any { it.id == box.id } },
                page.images.filterNot { image -> selection.images.any { it.id == image.id } }
            )
        }
    }

    fun replaceInkWithText(pageId: String, selection: CanvasSelection, box: TextBox): Boolean {
        with(runtime) {
            val page = findPageContent(pageId) ?: return false
            if (!page.loaded || selection.strokes.isEmpty()) return false
            if (selection.strokes.none { it in page.strokes }) return false
            val doomed = identitySet(selection.strokes)
            val layer = selection.strokes.map { it.layer }.distinct().singleOrNull() ?: activeLayerOf(page)
            updateContent(
                page.id,
                // Fast path: reference check first, value fallback for strokes reloaded from the page.
                page.strokes.filterNot { it in doomed || it in selection.strokes },
                page.texts + box.copy(layer = layer),
                page.images
            )
            return true
        }
    }

    fun duplicateSelection(selection: CanvasSelection) {
        with(runtime) {
            if (selection.isEmpty()) return
            val note = _state.value.active ?: return
            val page = _state.value.page ?: return
            if (!page.loaded) return
            copyToClipboard(selection)
            val offset = 18f
            val movedStrokes = selection.strokes.map { InkGeometry.translate(it, offset, offset) }
            val movedTexts = selection.texts.map { it.copy(id = UUID.randomUUID().toString()).moved(offset, offset) }
            scope.launch {
                val movedImages = selection.images.map { image ->
                    val placed = image.moved(offset, offset)
                    val freshId = UUID.randomUUID().toString()
                    try {
                        repository.loadImageBytes(note.id, image.id)?.let { repository.saveImage(note.id, freshId, it) }
                    } catch (_: Exception) { }
                    placed.copy(id = freshId)
                }
                updateContent(
                    page.id,
                    page.strokes + movedStrokes,
                    page.texts + movedTexts,
                    page.images + movedImages
                )
            }
        }
    }

    fun pasteClipboard(at: InkPoint? = null) {
        with(runtime) {
            val note = _state.value.active ?: return
            val targetId = _state.value.page?.id ?: return
            val system = systemClipboard.primaryClip
            val internal = system?.description?.label?.toString() == clipboardToken
            val clip = if (internal) _state.value.clipboard else {
                val text = system?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                if (text.isBlank()) { reportError("Copy some text or a selection first."); return }
                CanvasSelection(texts = listOf(TextBox(text = text, x = 0f, y = 0f)))
            }
            if (clip.isEmpty()) return
            val sourceNoteId = clipboardNoteId ?: note.id
            scope.launch {
                awaitLoaded(targetId) ?: run { reportError("Couldn't load the page for pasting."); return@launch }
                try {
                    // New image ids keep placements independent, including across notebooks.
                    val images = clip.images.map { image ->
                        val bytes = repository.loadImageBytes(sourceNoteId, image.id)
                            ?: error("The copied picture is unavailable")
                        val id = UUID.randomUUID().toString()
                        repository.saveImage(note.id, id, bytes)
                        image.copy(id = id)
                    }
                    val current = _state.value.active?.takeIf { it.id == note.id } ?: return@launch
                    val page = current.pages.find { it.id == targetId && it.loaded } ?: return@launch
                    val placed = placeSelection(clip.copy(images = images), page, at,
                        PASTE_OFFSET * (pasteGeneration++ % 5))
                    updateContent(page.id, page.strokes + placed.strokes, page.texts + placed.texts, page.images + placed.images)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reportError("Couldn't paste the selection. The original is unchanged.") }
            }
        }
    }

    internal fun placeSelection(clip: CanvasSelection, page: NotePage, at: InkPoint?, offset: Float = 0f, centred: Boolean = false): CanvasSelection {
        with(runtime) {
            val bounds = InkGeometry.contentBounds(clip.strokes, clip.texts, clip.images,
                InkRenderer::textHeight, page.width, page.height)
            var x = (at?.x ?: 32f) + offset - if (centred) (bounds.right - bounds.left) / 2f else 0f
            var y = (at?.y ?: 32f) + offset - if (centred) (bounds.bottom - bounds.top) / 2f else 0f
            if (!page.infinite) {
                x = x.coerceIn(0f, (page.width - (bounds.right - bounds.left)).coerceAtLeast(0f))
                y = y.coerceIn(0f, (page.height - (bounds.bottom - bounds.top)).coerceAtLeast(0f))
            }
            val dx = x - bounds.left
            val dy = y - bounds.top
            // Pasted or dropped items land on the layer being worked on, whatever layer they came from.
            val layer = activeLayerOf(page)
            return CanvasSelection(clip.strokes.map { InkGeometry.translate(it, dx, dy).copy(layer = layer) },
                clip.texts.map { it.copy(id = UUID.randomUUID().toString(), layer = layer).moved(dx, dy) },
                clip.images.map { it.copy(layer = layer).moved(dx, dy) })
        }
    }

    fun moveSelectionToPage(sourceId: String, targetId: String, selection: CanvasSelection, at: InkPoint? = null, centred: Boolean = at != null) {
        with(runtime) {
            val noteId = _state.value.active?.id ?: return
            if (sourceId == targetId || selection.isEmpty()) return
            scope.launch {
                awaitLoaded(targetId) ?: run { reportError("Couldn't open the destination page."); return@launch }
                val movedImages = try {
                    selection.images.map { image ->
                        val bytes = repository.loadImageBytes(noteId, image.id) ?: error("Missing picture")
                        val id = UUID.randomUUID().toString()
                        repository.saveImage(noteId, id, bytes)
                        image.copy(id = id)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reportError("Couldn't move the pictures. The selection is unchanged."); return@launch }
                val note = _state.value.active?.takeIf { it.id == noteId } ?: return@launch
                val source = note.pages.find { it.id == sourceId && it.loaded } ?: return@launch
                val target = note.pages.find { it.id == targetId && it.loaded } ?: return@launch
                if (!source.strokes.containsAll(selection.strokes) || !source.texts.containsAll(selection.texts) ||
                    !source.images.containsAll(selection.images)) {
                    reportError("The selection changed. Select it again before moving."); return@launch
                }
                val placed = placeSelection(selection.copy(images = movedImages), target, at, centred = centred)
                updateContent(target.id, target.strokes + placed.strokes, target.texts + placed.texts, target.images + placed.images)
                updateContent(source.id, source.strokes.filterNot { it in selection.strokes },
                    source.texts.filterNot { it in selection.texts }, source.images.filterNot { it in selection.images })
                // A drag lands where the finger is, so the view stays put; the menu route jumps to the page.
                if (at == null) {
                    selectPage(note.pages.indexOfFirst { it.id == targetId })
                    _state.update { it.copy(navigationRequest = it.navigationRequest + 1) }
                }
            }
        }
    }

    fun moveStickyToPage(sourceId: String, targetId: String, box: TextBox, x: Float, y: Float, scale: Float = 1f): Boolean {
        with(runtime) {
            val note = _state.value.active ?: return false
            val source = note.pages.find { it.id == sourceId && it.loaded } ?: return false
            val target = note.pages.find { it.id == targetId } ?: return false
            if (!target.loaded) {
                loadPage(targetId)
                reportError("The destination page is still loading. Try the move again when it opens.")
                return false
            }
            val transfer = StickyNotes.transfer(source, target, box, x, y, activeLayerOf(target), scale)
                ?: run { reportError("The note or destination layer changed. Pick up the note again to move it."); return false }
            // Both pages are loaded, so accept synchronously against this notebook and these contents.
            updateContent(target.id, target.strokes, transfer.targetTexts, target.images)
            updateContent(source.id, source.strokes, transfer.sourceTexts, source.images)
            return true
        }
    }

    fun restyleSelection(strokes: List<Stroke>, color: Int?, widthScale: Float?, opacity: Float?, style: StrokeStyle? = null) {
        with(runtime) {
            if (strokes.isEmpty()) return
            val page = _state.value.page ?: return
            val restyled = InkGeometry.restyle(strokes, color, widthScale, opacity, style)
            if (restyled == strokes) return
            val doomed = identitySet(strokes)
            this.strokes(page.id, page.strokes.filterNot { it in doomed || it in strokes } + restyled)
        }
    }

    fun duplicateDocumentSelection(noteId: String, selection: Map<String, CanvasSelection>, onDone: () -> Unit) {
        with(runtime) {
            val note = _state.value.active?.takeIf { it.id == noteId } ?: return
            val revisions = selection.keys.associateWith { id -> note.pages.first { it.id == id }.revision }
            scope.launch {
                try {
                    val edits = selection.mapValues { (id, value) ->
                        val page = note.pages.first { it.id == id }
                        val images = value.images.map { image ->
                            val fresh = java.util.UUID.randomUUID().toString()
                            val bytes = repository.loadImageBytes(noteId, image.id) ?: error("Missing picture")
                            repository.saveImage(noteId, fresh, bytes)
                            image.copy(id = fresh).moved(18f, 18f)
                        }
                        PageEdit(StrokesEdit.Add(value.strokes.map { InkGeometry.translate(it, 18f, 18f) }),
                            (page.texts + value.texts.map { it.copy(id = java.util.UUID.randomUUID().toString()).moved(18f, 18f) }).takeIf { value.texts.isNotEmpty() },
                            (page.images + images).takeIf { images.isNotEmpty() })
                    }
                    if (commitDocumentEdits(noteId, edits, revisions)) onDone() else reportError("The selection changed before it could be duplicated")
                } catch (e: Exception) { reportError("Couldn't duplicate the document selection: ${e.message.orEmpty()}") }
            }
        }
    }

    fun moveDocumentSelection(noteId: String, selection: Map<String, CanvasSelection>, flattened: CanvasSelection,
        targetId: String, onDone: () -> Unit) {
        with(runtime) {
            val note = _state.value.active?.takeIf { it.id == noteId } ?: return
            val target = note.pages.firstOrNull { it.id == targetId && it.loaded } ?: return
            if (!PageLayers.editable(target.layers, activeLayerOf(target))) return
            if (selection.any { (id, value) -> val page = note.pages.page(id)
                    page == null || !page.loaded || !page.strokes.containsAll(value.strokes) || !page.texts.containsAll(value.texts) || !page.images.containsAll(value.images) }) {
                reportError("The selection changed. Select it again before moving"); return
            }
            val revisions = (selection.keys + targetId).associateWith { id -> note.pages.first { it.id == id }.revision }
            scope.launch {
                try {
                    val images = flattened.images.map { image ->
                        val fresh = java.util.UUID.randomUUID().toString()
                        repository.saveImage(noteId, fresh, repository.loadImageBytes(noteId, image.id) ?: error("Missing picture"))
                        image.copy(id = fresh)
                    }
                    val bounds = InkGeometry.contentBounds(flattened.strokes, flattened.texts, images, InkRenderer::textHeight, target.width, target.height)
                    val factor = minOf(1f, (target.width - 64f).coerceAtLeast(1f) / (bounds.right - bounds.left).coerceAtLeast(1f),
                        (target.height - 64f).coerceAtLeast(1f) / (bounds.bottom - bounds.top).coerceAtLeast(1f))
                    val scaled = CanvasSelection(InkGeometry.scale(flattened.strokes, InkPoint(0f, 0f), factor),
                        InkGeometry.scaleTexts(flattened.texts, InkPoint(0f, 0f), factor), InkGeometry.scaleImages(images, InkPoint(0f, 0f), factor))
                    val placed = placeSelection(scaled, target, null)
                    val edits = LinkedHashMap<String, PageEdit>()
                    selection.forEach { (id, value) -> val page = note.pages.first { it.id == id }
                        PageJournal.diff(page.content(), page.content().copy(strokes = page.strokes.filterNot { it in value.strokes },
                            texts = page.texts.filterNot { it in value.texts }, images = page.images.filterNot { it in value.images }))?.let { edits[id] = it }
                    }
                    val before = target.content()
                    val stripped = edits[targetId]?.let { PageJournal.apply(before, it) } ?: before
                    PageJournal.diff(before, stripped.copy(strokes = stripped.strokes + placed.strokes, texts = stripped.texts + placed.texts,
                        images = stripped.images + placed.images))?.let { edits[targetId] = it }
                    if (commitDocumentEdits(noteId, edits, revisions)) onDone() else reportError("The document changed before the selection could move")
                } catch (e: Exception) { reportError("Couldn't move the document selection: ${e.message.orEmpty()}") }
            }
        }
    }
}
