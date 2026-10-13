package com.folio.notes

internal sealed class DocumentWriteOp {
    var generation: Long = 0L
    class Page(
        val noteId: String, val pageId: String, val transaction: PageTransaction,
        val before: suspend () -> Unit = {}
    ) : DocumentWriteOp()
    class Document(val noteId: String, val id: String, val history: DocumentHistory,
        val transactions: Map<String, PageTransaction>, val pages: Map<String, NotePage>) : DocumentWriteOp()
    class Once(val block: suspend () -> Unit, val recovery: Boolean = false) : DocumentWriteOp()
    /** Read-only queue fence. Its completion follows durability publication, including failures. */
    class Barrier(val completion: kotlinx.coroutines.CompletableDeferred<Unit>) : DocumentWriteOp()
}
