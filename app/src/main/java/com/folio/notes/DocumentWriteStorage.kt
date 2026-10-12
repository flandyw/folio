package com.folio.notes

/** Durable operations used by the ordering layer. Media/import work enters it as an ordered command. */
internal interface DocumentWriteStorage {
    suspend fun appendPageTransactions(noteId: String, pageId: String, transactions: List<PageTransaction>)
    suspend fun appendDocumentTransactions(noteId: String, id: String, history: DocumentHistory,
        transactions: Map<String, PageTransaction>, pages: Map<String, NotePage>)
    suspend fun saveMeta(note: Notebook)
    suspend fun saveFolders(folders: List<Folder>)
    suspend fun compactPending()
}

internal class RepositoryWriteStorage(private val repository: NoteRepository) : DocumentWriteStorage {
    override suspend fun appendPageTransactions(noteId: String, pageId: String, transactions: List<PageTransaction>) {
        repository.appendPageTransactions(noteId, pageId, transactions)
    }
    override suspend fun appendDocumentTransactions(noteId: String, id: String, history: DocumentHistory,
        transactions: Map<String, PageTransaction>, pages: Map<String, NotePage>) {
        repository.appendDocumentTransactions(noteId, id, history, transactions, pages)
    }
    override suspend fun saveMeta(note: Notebook) { repository.saveMeta(note) }
    override suspend fun saveFolders(folders: List<Folder>) { repository.saveFolders(folders) }
    override suspend fun compactPending() { repository.compactPending() }
}
