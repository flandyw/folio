package com.folio.notes

/** Deterministic edit planning. No storage, Android, UI state or live mutable history. */
internal object DocumentReducer {
    data class PageChange(val page: NotePage, val forward: PageEdit?, val inverse: PageEdit?)
    data class GroupChange(val notebook: Notebook, val transactions: Map<String, PageTransaction>, val pages: Map<String, NotePage>)

    fun page(before: NotePage, after: NotePage, known: Pair<PageEdit, PageEdit>? = null): PageChange {
        require(before.id == after.id && before.loaded && after.loaded)
        val forward = known?.first ?: PageJournal.diff(before.content(), after.content())
        val inverse = forward?.let { known?.second ?: PageJournal.invert(it, before.content()) }
        return PageChange(after.revised(), forward, inverse)
    }

    fun group(note: Notebook, edits: Map<String, PageEdit>, expectedRevisions: Map<String, Int>,
        id: String, sequence: Long, now: Long, fit: (NotePage) -> NotePage = { it }): GroupChange? {
        if (edits.isEmpty() || edits.keys.any { pageId ->
                val page = note.pages.page(pageId)
                page == null || !page.loaded || expectedRevisions[pageId]?.let { it != page.revision } == true
            }) return null
        val changed = edits.filterValues { !it.isEmpty }
        if (changed.isEmpty()) return null
        val records = LinkedHashMap<String, PageTransaction>()
        val members = LinkedHashMap<String, NotePage>()
        var pages = IndexedItems.of(note.pages) { it.id }
        // Follow page order, independent of caller map iteration order, for durable group membership.
        // Only visit edited members; a two-page selection must not scan a thousand-page notebook.
        changed.keys.sortedBy(pages::indexOfId).forEach { pageId ->
            val page = pages.byId(pageId)!!
            val raw = changed.getValue(pageId)
            val forward = raw.copy(groupId = id, actionSequence = sequence)
            val inverse = PageJournal.invert(forward, page.content())
            val content = PageJournal.apply(page.content(), forward)
            val revised = fit(page.copy(strokes = content.strokes, texts = content.texts, images = content.images, layers = content.layers)).revised()
            require(revised.id == page.id && revised.loaded)
            records[page.id] = PageTransaction(0, revised.revision, forward, undoPush = inverse, clearRedo = true)
            members[page.id] = revised
            pages = pages.replace(revised.id, revised)
        }
        val history = note.documentHistory.push(DocumentHistoryStep(id, records.keys.toList(), sequence))
        return GroupChange(note.copy(pages = pages, documentHistory = history, updated = now), records, members)
    }

    /** Plan every member before the caller changes any live stack or publishes page content. */
    fun history(note: Notebook, step: DocumentHistoryStep, tops: Map<String, PageEdit?>,
        wasUndo: Boolean, sequence: Long, commitId: String, now: Long): GroupChange? {
        val ledger = if (wasUndo) note.documentHistory.undo else note.documentHistory.redo
        if (step !in ledger || step.pages.isEmpty() || step.pages.distinct().size != step.pages.size) return null
        if (step.pages.any { id ->
                val page = note.pages.page(id)
                val top = tops[id]
                page == null || !page.loaded || top == null || top.groupId != step.id || top.actionSequence != step.sequence
            }) return null
        var pages = IndexedItems.of(note.pages) { it.id }
        val records = LinkedHashMap<String, PageTransaction>()
        val members = LinkedHashMap<String, NotePage>()
        step.pages.sortedBy(pages::indexOfId).forEach { id ->
            val page = pages.byId(id)!!
            val op = tops.getValue(id)!!
            val inverse = PageJournal.invert(op, page.content()).copy(actionSequence = sequence)
            val content = PageJournal.apply(page.content(), op)
            val revised = page.copy(strokes = content.strokes, texts = content.texts,
                images = content.images, layers = content.layers).revised()
            records[id] = PageTransaction(0, revised.revision, op,
                undoPush = inverse.takeUnless { wasUndo }, undoPop = wasUndo,
                redoPush = inverse.takeIf { wasUndo }, redoPop = !wasUndo)
            members[id] = revised
            pages = pages.replace(id, revised)
        }
        val history = note.documentHistory.moved(step, wasUndo, sequence).copy(lastCommit = commitId)
        return GroupChange(note.copy(pages = pages, documentHistory = history, updated = now), records, members)
    }
}
