package com.folio.notes

import java.util.IdentityHashMap

/**
 * Reuses the JSON of unchanged undo steps instead of walking their ink points on every save.
 * Edits are immutable once queued. Identity keys avoid expensive structural hashes of stroke data;
 * retaining only the most recently encoded stacks bounds the cache to one page's history.
 * Callers must serialize access (the repository's write lock does this).
 */
internal class PageHistoryEncoder(
    private val encodeEdit: (PageEdit) -> String = { PageJournal.encodeEdit(it).toString() }
) {
    private var cached = IdentityHashMap<PageEdit, String>()

    fun encode(history: PageJournal.History): String {
        val next = IdentityHashMap<PageEdit, String>()
        fun stack(edits: List<PageEdit>): String = edits.joinToString(",", "[", "]") { edit ->
            val encoded = next[edit] ?: cached[edit] ?: encodeEdit(edit)
            next[edit] = encoded
            encoded
        }
        val result = "{\"v\":1,\"undo\":${stack(history.undo)},\"redo\":${stack(history.redo)}}"
        cached = next
        return result
    }
}
