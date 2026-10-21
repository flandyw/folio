package com.folio.notes

import com.folio.notes.app.FolioState
import com.folio.notes.document.DocumentHistory
import com.folio.notes.document.DocumentHistoryAvailability
import com.folio.notes.document.DocumentHistoryStep
import com.folio.notes.document.DocumentReducer
import com.folio.notes.document.DocumentStateStore
import com.folio.notes.model.IndexedItems
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.PageLayer
import com.folio.notes.model.PageLayers
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import com.folio.notes.model.notebook
import com.folio.notes.model.page
import com.folio.notes.model.replaceNotebook
import com.folio.notes.model.replacePage
import com.folio.notes.model.revised
import com.folio.notes.storage.PageEdit
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.PageTransaction
import com.folio.notes.storage.content
import java.lang.management.ManagementFactory
import kotlin.system.measureNanoTime

/** Desktop allocation/CPU baseline, independent of tablet input and rendering performance. */
fun main() {
    val pages = List(1024) { NotePage(id = "p$it") }
    val notebooks = List(256) { Notebook(id = "n$it", title = "Notebook $it", pages = if (it == 128) pages else listOf(NotePage(id = "other$it"))) }
    val repetitions = 5000
    val memory = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    memory?.isThreadAllocatedMemoryEnabled = true
    fun measure(name: String, edit: (Int) -> Unit) {
        repeat(1000, edit)
        val before = memory?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: 0L
        val samples = LongArray(repetitions) { index -> measureNanoTime { edit(index) } }
        val bytes = (memory?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: 0L) - before
        samples.sort()
        println("$name median_ns=${samples[repetitions / 2]} p95_ns=${samples[(repetitions * .95).toInt()]} p99_ns=${samples[(repetitions * .99).toInt()]} allocated_bytes_per_edit=${bytes / repetitions}")
    }
    var legacy = notebooks
    measure("legacy-list-rewrite") { revision ->
        val note = legacy[128]
        val page = note.pages[512].copy(revision = revision)
        val updated = note.copy(pages = note.pages.map { if (it.id == page.id) page else it })
        legacy = legacy.map { if (it.id == updated.id) updated else it }
    }
    var indexed: List<Notebook> = IndexedItems.of(notebooks.map { it.copy(pages = IndexedItems.of(it.pages) { p -> p.id }) }) { it.id }
    measure("indexed-rewrite") { revision ->
        val note = indexed.notebook("n128")!!
        indexed = indexed.replaceNotebook(note.copy(pages = note.pages.replacePage(note.pages.page("p512")!!.copy(revision = revision))))
    }
    val store = DocumentStateStore(FolioState(notes = notebooks, loading = false))
    store.observePage("n128", "p512")
    store.observeNotebook("n128")
    measure("engine-publication") { revision ->
        store.update { state ->
            val note = state.notes.notebook("n128")!!
            state.copy(notes = state.notes.replaceNotebook(note.copy(pages = note.pages.replacePage(note.pages.page("p512")!!.copy(revision = revision)))))
        }
    }
    var traversed = 0L
    measure("indexed-full-traversal") {
        var sum = 0L
        indexed.notebook("n128")!!.pages.forEach { sum += it.revision }
        traversed = sum
    }
    val metadataStore = DocumentStateStore(FolioState(notes = notebooks, loading = false))
    metadataStore.observeNotebook("n128")
    measure("metadata-publication") { revision ->
        metadataStore.update { state ->
            val note = state.notes.notebook("n128")!!
            state.copy(notes = state.notes.replaceNotebook(note.copy(updated = revision.toLong())))
        }
    }
    val groupedStore = DocumentStateStore(FolioState(notes = notebooks, loading = false))
    groupedStore.observeNotebook("n128")
    groupedStore.observePage("n128", "p128")
    groupedStore.observePage("n128", "p900")
    measure("two-page-publication") { revision ->
        groupedStore.update { state ->
            val note = state.notes.notebook("n128")!!
            val updated = note.copy(pages = note.pages
                .replacePage(note.pages.page("p900")!!.copy(revision = revision))
                .replacePage(note.pages.page("p128")!!.copy(revision = revision)))
            state.copy(notes = state.notes.replaceNotebook(updated))
        }
    }
    check(legacy[128].pages[512].revision == repetitions - 1)
    check(indexed[128].pages[512].revision == repetitions - 1)
    check(store.page("p512")!!.revision == repetitions - 1)
    check(traversed == (repetitions - 1).toLong())
    check(metadataStore.value.notes.notebook("n128")!!.updated == (repetitions - 1).toLong())
    check(groupedStore.page("p128")!!.revision == repetitions - 1)
    check(groupedStore.page("p900")!!.revision == repetitions - 1)
    val layers = List(12) { PageLayer(it, "Layer $it", visible = it % 3 != 0) }
    val layerStrokes = List(4096) { Stroke(Tool.PEN, 0, 2f, emptyList(), layer = (it * 7) % 14) }
    var projected: List<Stroke> = emptyList()
    measure("layer-stroke-projection") { projected = PageLayers.viewStrokes(layerStrokes, layers) }
    check(projected.size == layerStrokes.count { PageLayers.find(layers, it.layer)!!.visible })
    check(projected.zipWithNext().all { (a, b) -> a.layer <= b.layer })

    val group = DocumentReducer.group(indexed.notebook("n128")!!,
        listOf("p128", "p900").associateWith { PageEdit(texts = listOf(TextBox(x = 0f, y = 0f, text = "Answer"))) },
        emptyMap(), "g", 10, 0)!!
    val step = group.notebook.documentHistory.undo.single()
    val tops = group.transactions.mapValues { it.value.undoPush!! }
    fun measureHistory(name: String, plan: (Long) -> DocumentReducer.GroupChange) {
        val historyStore = DocumentStateStore(FolioState(notes = indexed.replaceNotebook(group.notebook), loading = false))
        historyStore.observeNotebook("n128")
        historyStore.observePage("n128", "p128")
        historyStore.observePage("n128", "p900")
        measure(name) { index ->
            val change = plan(index + 20L)
            historyStore.update { it.copy(notes = it.notes.replaceNotebook(change.notebook)) }
        }
        check(historyStore.page("p128")!!.texts.isEmpty())
        check(historyStore.value.notes.notebook("n128")!!.documentHistory.redo.single().sequence == repetitions + 19L)
    }
    measureHistory("legacy-grouped-history-publication") { sequence -> legacyGroupedUndo(group.notebook, step, tops, sequence) }
    measureHistory("grouped-history-publication") { sequence ->
        DocumentReducer.history(group.notebook, step, tops, true, sequence, "undo", sequence)!!
    }
    val historyNote = group.notebook.copy(documentHistory = DocumentHistory(enabled = true, undo = List(60) { index ->
        DocumentHistoryStep("g$index", List(64) { "p${(index * 13 + it) % 1024}" }, index.toLong())
    }))
    var available: List<DocumentHistoryStep> = emptyList()
    measure("legacy-history-availability") {
        available = historyNote.documentHistory.undo.filter { candidate -> candidate.pages.all { historyNote.pages.page(it) != null } }
    }
    val availability = DocumentHistoryAvailability()
    measure("cached-history-availability") { available = availability.forNotebook(historyNote).undo }
    check(available.size == 60)
}

/** Former full-page scan, retained only as a benchmark baseline; excludes live stack mutation. */
private fun legacyGroupedUndo(note: Notebook, step: DocumentHistoryStep, tops: Map<String, PageEdit>, sequence: Long): DocumentReducer.GroupChange {
    val records = LinkedHashMap<String, PageTransaction>()
    val pages = note.pages.map { page ->
        val op = tops[page.id] ?: return@map page
        val inverse = PageJournal.invert(op, page.content()).copy(actionSequence = sequence)
        val content = PageJournal.apply(page.content(), op)
        val revised = page.copy(strokes = content.strokes, texts = content.texts, images = content.images, layers = content.layers).revised()
        records[page.id] = PageTransaction(0, revised.revision, op, undoPop = true, redoPush = inverse)
        revised
    }
    val history = note.documentHistory.moved(step, true, sequence).copy(lastCommit = "undo")
    return DocumentReducer.GroupChange(note.copy(pages = pages, documentHistory = history, updated = sequence), records,
        pages.filter { it.id in records }.associateBy { it.id })
}
