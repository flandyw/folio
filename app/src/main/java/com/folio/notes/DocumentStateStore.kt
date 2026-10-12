package com.folio.notes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One synchronized publication point for compatibility state, indexed content and granular readers. */
internal class DocumentStateStore(initial: FolioState) : DocumentReader {
    private val lock = Any()
    private val full = MutableStateFlow(initial.copy(notes = emptyList()))
    private val timer = MutableStateFlow(initial.timer)
    val timerState = timer.asStateFlow()
    private val shell = MutableStateFlow(initial.copy(notes = emptyList()))
    val state = full.asStateFlow()
    val shellState = shell.asStateFlow()
    val value: FolioState get() = full.value
    private val summariesById = HashMap<String, NotebookSummary>()
    private val owners = HashMap<String, String>()
    private val notebooks = HashMap<String, MutableStateFlow<NotebookSummary?>>()
    private val pages = HashMap<Pair<String, String>, MutableStateFlow<PageSnapshot?>>()
    private val orders = HashMap<String, MutableStateFlow<List<String>>>()

    init { update { initial } }

    fun notebookForPage(id: String): Notebook? = synchronized(lock) { value.notes.notebook(owners[id]) }
    fun page(id: String): NotePage? = synchronized(lock) { value.notes.notebook(owners[id])?.pages?.page(id) }

    fun update(transform: (FolioState) -> FolioState) = synchronized(lock) {
        val old = value
        var next = transform(old)
        if (next === old) return@synchronized
        val notifications = ArrayList<() -> Unit>()
        if (next.notes !== old.notes) {
            val changed = (next.notes as? IndexedItems<Notebook>)?.changedFrom(old.notes)
            val candidates = if (changed != null) listOf(next.notes[changed]) else next.notes
            var notes = IndexedItems.of(next.notes) { it.id }
            var summaries = shell.value.notes
            val existing = if (changed == null) next.notes.mapTo(HashSet()) { it.id } else null
            if (existing != null) {
                old.notes.filter { it.id !in existing }.forEach { removed ->
                    removed.pages.forEach { page -> owners.remove(page.id); notifications += { publishPage(removed.id, page.id) } }
                    summariesById.remove(removed.id); notifications += { publishNotebook(removed.id) }
                }
                if (old.notes.size != next.notes.size || old.notes.map { it.id } != next.notes.map { it.id }) {
                    summaries = next.notes.map { summary(it).metadata }
                }
            }
            candidates.forEach { incoming ->
                val before = old.notes.notebook(incoming.id)
                if (incoming === before) return@forEach
                val note = if (incoming.pages is IndexedItems<NotePage>) incoming else incoming.copy(pages = IndexedItems.of(incoming.pages) { it.id })
                notes = notes.replace(note.id, note)
                val pageChange = (note.pages as? IndexedItems<NotePage>)?.changedFrom(before?.pages.orEmpty())
                val members = if (pageChange != null) listOf(note.pages[pageChange]) else note.pages
                if (pageChange == null) {
                    val ids = note.pages.mapTo(HashSet()) { it.id }
                    before?.pages?.filter { it.id !in ids }?.forEach { page -> owners.remove(page.id); notifications += { publishPage(note.id, page.id) } }
                }
                members.forEach { page ->
                    owners[page.id] = note.id
                    if (pages.containsKey(note.id to page.id)) notifications += { publishPage(note.id, page.id) }
                }
                val summary = summary(note, summariesById[note.id], pageChange)
                summariesById[note.id] = summary
                notifications += { publishNotebook(note.id) }
                summaries = summaries.replaceNotebook(summary.metadata)
            }
            next = next.copy(notes = notes)
            full.value = next
            if (full.value == next) shell.value = next.copy(notes = summaries, clipboard = CanvasSelection())
        } else {
            full.value = next
            if (full.value == next) shell.value = next.copy(notes = shell.value.notes, clipboard = CanvasSelection())
        }
        timer.value = value.timer
        // Readers and reentrant commands must see the committed indexed state before notification.
        // Resolve values at delivery so an immediate observer cannot make a later notification stale.
        notifications.forEach { it() }
    }

    private fun publishPage(noteId: String, pageId: String) {
        val flow = pages[noteId to pageId] ?: return
        val page = value.notes.notebook(noteId)?.pages?.page(pageId)
        if (flow.value?.page !== page) flow.value = page?.let { PageSnapshot(noteId, it) }
    }
    private fun publishNotebook(noteId: String) {
        notebooks[noteId]?.value = summariesById[noteId]
        orders[noteId]?.value = summariesById[noteId]?.pageOrder.orEmpty()
    }

    private fun summary(note: Notebook, previous: NotebookSummary? = null, changedPage: Int? = null): NotebookSummary {
        fun metadata(page: NotePage) = page.copy(strokes = emptyList(), texts = emptyList(), images = emptyList(), layers = emptyList(), loaded = false)
        val pages = if (previous != null && changedPage != null)
            previous.metadata.pages.replacePage(metadata(note.pages[changedPage]))
        else IndexedItems.of(note.pages.map(::metadata)) { it.id }
        val order = if (previous != null && changedPage != null) previous.pageOrder else note.pages.map { it.id }
        return NotebookSummary(note.copy(pages = pages), order)
    }

    override fun observeNotebook(id: String): StateFlow<NotebookSummary?> = synchronized(lock) {
        notebooks.getOrPut(id) { MutableStateFlow(summariesById[id]) }.asStateFlow()
    }
    override fun observePage(notebookId: String, pageId: String): StateFlow<PageSnapshot?> = synchronized(lock) {
        pages.getOrPut(notebookId to pageId) { MutableStateFlow(value.notes.notebook(notebookId)?.pages?.page(pageId)?.let { PageSnapshot(notebookId, it) }) }.asStateFlow()
    }
    override fun observePageOrder(notebookId: String): StateFlow<List<String>> = synchronized(lock) {
        orders.getOrPut(notebookId) { MutableStateFlow(value.notes.notebook(notebookId)?.pages?.map { it.id }.orEmpty()) }.asStateFlow()
    }
}
