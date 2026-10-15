package com.folio.notes

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlin.math.roundToInt

/** Single owner for explicit navigation, landing protection and continuous-page anchors. */
internal class EditorViewportCoordinator(
    val list: LazyListState, val motion: DocumentMotion, private val model: FolioViewModel,
    private val notebookId: String, private val camera: EditorViewportState,
) {
    private var navigation = PageNavigation()
    var generation by mutableIntStateOf(0)
        private set
    private fun live() = model.state.value.active?.takeIf { it.id == notebookId }

    fun jump(index: Int) {
        val note = live()?.takeIf { it.pages.isNotEmpty() } ?: return
        val target = index.coerceIn(0, note.pages.lastIndex)
        motion.reset()
        camera.anchor = DocumentPageGeometry.Anchor(note.pages[target].id, 0f)
        navigation.request(note.pages[target].id, list.layoutInfo)
        generation++
        model.selectPage(target)
        list.requestScrollToItem(target)
    }
    fun requestCurrent() {
        val note = live()?.takeIf { it.pages.isNotEmpty() } ?: return
        val target = model.state.value.pageIndex.coerceIn(0, note.pages.lastIndex)
        camera.anchor = DocumentPageGeometry.Anchor(note.pages[target].id, 0f)
        navigation.request(note.pages[target].id, list.layoutInfo)
        generation++
        list.requestScrollToItem(target)
    }
    fun observe(viewport: PageNavigation.Viewport) {
        val note = live() ?: return
        navigation.observe(note.pages.map { it.id }, viewport)
            ?.takeIf { it != model.state.value.pageIndex }?.let(model::selectPage)
    }
    fun capture() {
        if (navigation.awaitingLanding) return
        val note = live() ?: return
        val layout = list.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index in note.pages.indices &&
            it.offset.toLong() + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset } ?: return
        val page = note.pages[visible.index]
        val top = 0f
        val bottom = page.height
        if (visible.size > 0) camera.anchor = DocumentPageGeometry.Anchor(page.id,
            top - visible.offset.toFloat() / visible.size * (bottom - top))
    }
    fun restoreVisit(anchor: DocumentPageGeometry.Anchor, paperWidthPx: Float): Boolean {
        navigation = PageNavigation()
        camera.anchor = anchor
        return restore(paperWidthPx)
    }
    fun restore(paperWidthPx: Float): Boolean {
        if (navigation.awaitingLanding) return false
        val anchor = camera.anchor ?: return false
        val note = live() ?: return false
        val index = note.pages.indexOfFirst { it.id == anchor.pageId }
        if (index < 0 || !paperWidthPx.isFinite() || paperWidthPx <= 0) return false
        val page = note.pages[index]
        val top = 0f
        motion.reset()
        // This is a camera restoration, not a new explicit destination: the most-visible page
        // is derived normally after the anchor lands.
        navigation = PageNavigation()
        generation++
        list.requestScrollToItem(index, ((anchor.localY - top) * paperWidthPx / page.width).roundToInt())
        return true
    }
}
