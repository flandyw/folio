package com.folio.notes.marking

import com.folio.notes.app.FolioRuntime
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageLayers
import com.folio.notes.model.withLoadedContent
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.content
import kotlinx.coroutines.*

/** Editor commands over the shared document engine; no independent notebook content. */
internal class MarkingController(private val runtime: FolioRuntime) {
    fun awardMark(pageId: String, zone: MarkZone, value: Int, color: Int) {
        if (zone.parts.isNotEmpty()) { changeRegion(zone, value, color); return }
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            if (!page.loaded) return
            val (texts, strokes) = MarkZones.withAward(zone, page, value, color)
            if (texts != page.texts || strokes != page.strokes) replacePage(page.copy(texts = texts, strokes = strokes))
        }
    }

    fun clearMark(pageId: String, zone: MarkZone) {
        if (zone.parts.isNotEmpty()) { changeRegion(zone, null, 0); return }
        with(runtime) {
            val page = findPageContent(pageId) ?: return
            if (!page.loaded) return
            val (texts, strokes) = MarkZones.withoutAward(zone, page)
            if (texts != page.texts || strokes != page.strokes) replacePage(page.copy(texts = texts, strokes = strokes))
        }
    }

    private fun changeRegion(zone: MarkZone, value: Int?, color: Int) {
        with(runtime) {
            val note = _state.value.active ?: return
            val root = zone.canonical()
            scope.launch {
                val pages = LinkedHashMap<String, NotePage>()
                for (part in root.parts) {
                    if (_state.value.active?.id != note.id) return@launch
                    if (note.pages.none { it.id == part.pageId && it.pdfIndex == part.pageIndex }) {
                        reportError("A page in this marking area is no longer available"); return@launch
                    }
                    val page = awaitLoaded(part.pageId) ?: run { reportError("Couldn't load all pages in this marking area"); return@launch }
                    if (!PageLayers.editable(page.layers, 0)) { reportError("Unlock the marking layer on every page in this area"); return@launch }
                    pages[page.id] = page
                }
                if (_state.value.active?.id != note.id) return@launch
                val content = MarkZones.regionContent(root, pages, value, color) ?: return@launch
                val edits = content.mapNotNull { (id, after) -> PageJournal.diff(pages.getValue(id).content(), after)?.let { id to it } }.toMap()
                if (edits.isNotEmpty() && !commitDocumentEdits(note.id, edits, pages.mapValues { it.value.revision }))
                    reportError("The marking area changed while its pages loaded; try the award again")
            }
        }
    }

    suspend fun pagesForMarking(): List<NotePage> {
        with(runtime) {
            val note = _state.value.active ?: return emptyList()
            return note.pages.map { summary ->
                if (summary.loaded) summary
                else runCatching { summary.withLoadedContent(repository.loadPage(note.id, summary)) }.getOrDefault(summary)
            }
        }
    }
}
