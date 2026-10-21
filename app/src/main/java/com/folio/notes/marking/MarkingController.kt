package com.folio.notes.marking

import com.folio.notes.app.FolioRuntime
import com.folio.notes.ink.InkRenderer
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageLayers
import com.folio.notes.model.Paper
import com.folio.notes.model.withInsertedPage
import com.folio.notes.model.withLoadedContent
import com.folio.notes.model.withPage
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.content
import kotlinx.coroutines.*

/** Editor commands over the shared document engine; no independent notebook content. */
internal class MarkingController(private val runtime: FolioRuntime) {
    fun applyMarking(action: MarkingAction, at: InkPoint, color: Int) {
        with(runtime) {
            val page = _state.value.page ?: return
            if (!page.loaded) return
            when (action) {
                is MarkingAction.Comment -> texts(page.id, page.texts + Marking.commentBox(action.text, at.x, at.y, page, color))
                is MarkingAction.Mark -> texts(page.id, page.texts + Marking.markBox(action.label, at.x - 14f, at.y - 20f, color))
                is MarkingAction.Note -> placeFeedbackNote(at, action.handwritten, color)
                MarkingAction.Flag -> dropFlag(page, at, color)
                is MarkingAction.MakeRoom -> makeRoom(page, at.y, action.amount)
                is MarkingAction.RemoveRoom -> {
                    val shift = Marking.removeRoom(page, at.y, action.amount, InkRenderer::textHeight)
                    if (shift == null) reportError("Choose an empty gap large enough for this amount. Nothing was removed.")
                    else {
                        // Save shifted content before shortening the index: interruption leaves spare paper.
                        replacePage(page.copy(strokes = shift.strokes, texts = shift.texts, images = shift.images))
                        val note = _state.value.active ?: return
                        val fresh = findPageContent(page.id) ?: return
                        updateNote(note.withPage(fresh.copy(height = shift.height)))
                    }
                }
            }
        }
    }

    fun placeInFreeSpace(action: MarkingAction, color: Int): Boolean {
        with(runtime) {
            val page = _state.value.page ?: return false
            if (!page.loaded) return false
            val box = when (action) {
                is MarkingAction.Comment -> Marking.commentBox(action.text, 0f, 0f, page, color)
                is MarkingAction.Mark -> Marking.markBox(action.label, 0f, 0f, color)
                else -> return false
            }
            val slot = Marking.freeSlot(page, box.width, Marking.estimateHeight(box), InkRenderer::textHeight) ?: return false
            applyMarking(action, slot, color)
            return true
        }
    }

    fun placeFeedbackNote(at: InkPoint, handwritten: Boolean, color: Int): NotePlan? {
        with(runtime) {
            val page = _state.value.page ?: return null
            if (!page.loaded) return null
            val plan = Marking.feedbackNote(page, at, handwritten, color, InkRenderer::textHeight)
            if (plan == null) { reportError("No clear space near there. Try a spot nearer a margin, or use the Feedback page."); return null }
            strokes(page.id, page.strokes + plan.strokes)
            return plan
        }
    }

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

    internal fun makeRoom(page: NotePage, y: Float, amount: Float) {
        with(runtime) {
            val shift = Marking.makeRoom(page, y, amount, InkRenderer::textHeight)
            if (shift == null) {
                reportError(if (!page.infinite && page.pdfIndex != null)
                    "An imported PDF page can't grow. Use margin comments or the Feedback sheet instead."
                else "This page can't grow any further.")
                return
            }
            if (shift.moved == 0 && page.infinite) return
            val note = _state.value.active ?: return
            // The taller page is written to the index first: a crash between the two writes leaves spare
            // paper rather than ink hanging off the bottom.
            if (shift.height != page.height) updateNote(note.withPage(page.copy(height = shift.height)))
            val grown = findPageContent(page.id) ?: return
            replacePage(grown.copy(strokes = shift.strokes, texts = shift.texts, images = shift.images))
        }
    }

    internal fun dropFlag(page: NotePage, at: InkPoint, color: Int) {
        with(runtime) {
            val note = _state.value.active ?: return
            if (Marking.findSheet(note.pages)?.id == page.id) {
                reportError("Flags go on the pages being marked. Open one of those pages first.")
                return
            }
            scope.launch {
                var sheetId = Marking.findSheet(note.pages)?.id
                if (sheetId == null) {
                    val current = _state.value.active?.takeIf { it.id == note.id } ?: return@launch
                    val sheet = NotePage(paper = Paper.RULED, title = Marking.FEEDBACK_TITLE)
                    updateNote(current.withInsertedPage(current.pages.size, sheet))
                    texts(sheet.id, Marking.sheetHeader())
                    sheetId = sheet.id
                }
                val sheet = awaitLoaded(sheetId) ?: run { reportError("Couldn't open the feedback sheet."); return@launch }
                val number = Marking.nextFlagNumber(sheet)
                val latest = _state.value.active?.takeIf { it.id == note.id } ?: return@launch
                val pageNumber = latest.pages.indexOfFirst { it.id == page.id } + 1
                val (entry, height) = Marking.sheetEntry(number, pageNumber, sheet, color)
                val fresh = findPageContent(sheet.id) ?: sheet
                if (height != fresh.height) updateNote(latest.withPage(fresh.copy(height = height)))
                texts(sheet.id, fresh.texts + entry)
                val (ring, label) = Marking.flag(number, at.x, at.y, color)
                val target = findPageContent(page.id) ?: return@launch
                updateContent(target.id, target.strokes + ring, target.texts + label, target.images)
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
