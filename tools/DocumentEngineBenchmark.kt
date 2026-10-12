package com.folio.notes

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
    check(legacy[128].pages[512].revision == repetitions - 1)
    check(indexed[128].pages[512].revision == repetitions - 1)
    check(store.page("p512")!!.revision == repetitions - 1)
}
