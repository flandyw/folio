package com.folio.notes

import kotlinx.coroutines.flow.StateFlow

/** No ink arrays in a catalog value. Null means the notebook/page was removed. */
data class NotebookSummary(val metadata: Notebook, val pageOrder: List<String>)
data class PageSnapshot(val notebookId: String, val page: NotePage) {
    val revision: Int get() = page.revision
}
interface DocumentReader {
    fun observeNotebook(id: String): StateFlow<NotebookSummary?>
    fun observePage(notebookId: String, pageId: String): StateFlow<PageSnapshot?>
    fun observePageOrder(notebookId: String): StateFlow<List<String>>
}
