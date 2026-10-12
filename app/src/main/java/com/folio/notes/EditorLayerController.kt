package com.folio.notes

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update


/** Editor commands over the shared document engine; no independent notebook content. */
internal class EditorLayerController(private val runtime: FolioRuntime) {
    fun activeLayerOf(page: NotePage): Int {
        with(runtime) {
            val chosen = _state.value.activeLayers[page.id]
            return chosen?.takeIf { id -> PageLayers.effective(page.layers).any { it.id == id } } ?: PageLayers.initialActive(page.layers)
        }
    }

    fun setActiveLayer(pageId: String, layer: Int) {
        with(runtime) {
            _state.update { it.copy(activeLayers = it.activeLayers + (pageId to layer)) }
        }
    }

    internal fun editLayers(change: (PageContent) -> PageContent) {
        with(runtime) {
            val page = _state.value.page ?: return
            if (!page.loaded) return
            val before = page.content()
            val after = change(before)
            if (after == before) return
            replacePage(page.copy(strokes = after.strokes, texts = after.texts, images = after.images, layers = after.layers))
        }
    }

    fun addLayer() {
        with(runtime) {
            val page = _state.value.page ?: return
            val grown = PageLayers.add(page.layers)
            if (grown === page.layers || grown == page.layers) return
            editLayers { it.copy(layers = grown) }
            setActiveLayer(page.id, grown.last().id)
        }
    }

    fun renameLayer(id: Int, name: String): Unit = with(runtime) { editLayers { it.copy(layers = PageLayers.rename(it.layers, id, name)) } }

    fun setLayerVisible(id: Int, visible: Boolean): Unit = with(runtime) { editLayers { it.copy(layers = PageLayers.update(it.layers, id) { l -> l.copy(visible = visible) }) } }

    fun setLayerLocked(id: Int, locked: Boolean): Unit = with(runtime) { editLayers { it.copy(layers = PageLayers.update(it.layers, id) { l -> l.copy(locked = locked) }) } }

    fun moveLayer(id: Int, up: Boolean): Unit = with(runtime) { editLayers { it.copy(layers = PageLayers.move(it.layers, id, up)) } }

    fun deleteLayer(id: Int) {
        with(runtime) {
            val page = _state.value.page ?: return
            val target = PageLayers.mergeTarget(page.layers, id) ?: return
            editLayers { content ->
                val moved = PageLayers.reassign(content, id, target)
                moved.copy(layers = PageLayers.remove(content.layers, id))
            }
            if (_state.value.activeLayers[page.id] == id) setActiveLayer(page.id, target)
        }
    }

    fun moveSelectionToLayer(selection: CanvasSelection, layer: Int) {
        with(runtime) {
            if (selection.isEmpty()) return
            val page = _state.value.page ?: return
            val doomed = identitySet(selection.strokes)
            val texts = selection.texts.map { it.id }.toHashSet()
            val images = selection.images.map { it.id }.toHashSet()
            updateContent(page.id,
                page.strokes.map { if (it in doomed && it.layer != layer) it.copy(layer = layer) else it },
                page.texts.map { if (it.id in texts && it.layer != layer) it.copy(layer = layer) else it },
                page.images.map { if (it.id in images && it.layer != layer) it.copy(layer = layer) else it })
        }
    }
}
