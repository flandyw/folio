package com.folio.notes.document

import com.folio.notes.model.IndexedItems
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.page

/** Group availability depends on membership and the ledger, never page content or loaded state. */
internal class DocumentHistoryAvailability {
    data class Available(val undo: List<DocumentHistoryStep>, val redo: List<DocumentHistoryStep>)
    private var notebookId: String? = null
    private var ledger: DocumentHistory? = null
    private var membership: Any? = null
    private var available = Available(emptyList(), emptyList())

    fun forNotebook(note: Notebook?): Available {
        if (note == null) {
            notebookId = null; ledger = null; membership = null
            available = EMPTY
            return available
        }
        val token = (note.pages as? IndexedItems<NotePage>)?.membershipIdentity
        if (token != null && notebookId == note.id && ledger === note.documentHistory && membership === token) return available
        fun valid(steps: List<DocumentHistoryStep>) = steps.filter { step -> step.pages.all { note.pages.page(it) != null } }
        val result = Available(valid(note.documentHistory.undo), valid(note.documentHistory.redo))
        // Plain-list callers are computed without retaining their page content as a cache key.
        notebookId = note.id; ledger = note.documentHistory; membership = token
        available = result
        return result
    }

    private companion object { val EMPTY = Available(emptyList(), emptyList()) }
}
