package com.folio.notes.document

import com.folio.notes.app.FolioState
import com.folio.notes.model.Folder
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.storage.PageEdit
import com.folio.notes.storage.PageTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DocumentWriteCoordinatorTest {
    private class Storage : DocumentWriteStorage {
        val events = ArrayList<String>()
        val batches = ArrayList<List<Int>>()
        var failGroup = false
        var cancelPage = false
        val committedGroups = HashSet<String>()
        override suspend fun appendPageTransactions(noteId: String, pageId: String, transactions: List<PageTransaction>) {
            if (cancelPage) throw CancellationException("Injected cancellation")
            events += "page:$pageId"
            batches += transactions.map { it.revision }
        }
        override suspend fun appendDocumentTransactions(noteId: String, id: String, history: DocumentHistory,
            transactions: Map<String, PageTransaction>, pages: Map<String, NotePage>) {
            if (failGroup) { failGroup = false; events += "group:$id:failed"; throw IOException("Injected grouped-write failure") }
            if (committedGroups.add(id)) events += "group:$id"
        }
        override suspend fun saveMeta(note: Notebook) { events += "index:${note.id}" }
        override suspend fun saveFolders(folders: List<Folder>) { events += "folders" }
        override suspend fun compactPending() { events += "compact" }
    }
    private fun page(id: String, revision: Int = 1, before: suspend () -> Unit = {}) =
        DocumentWriteOp.Page("n", id, PageTransaction(0, revision, PageEdit(texts = emptyList())), before)

    @Test fun batchesConsecutivePagesAndKeepsMediaAndOtherCommandsInOrder() = runBlocking {
        val state = DocumentStateStore(FolioState(loading = false))
        val storage = Storage()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writer = DocumentWriteCoordinator(state, storage, scope, Mutex())
        writer.start()
        try {
            writer.queue(page("a", 1) { storage.events += "media:1" })
            writer.queue(page("a", 2) { storage.events += "media:2" })
            writer.enqueue { storage.events += "command" }
            writer.queue(page("b"))
            withTimeout(5000) { writer.awaitSaved() }
            assertEquals(listOf("media:1", "media:2", "page:a", "command", "page:b"), storage.events.take(5))
            assertEquals(listOf(1, 2), storage.batches[0])
            assertEquals(state.value.editGeneration, state.value.durableGeneration)
            assertFalse(state.value.saveFailed)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun failedGroupsBlockLaterEditsAndBarriersWakeUntilOrderedRetrySucceeds() = runBlocking {
        val state = DocumentStateStore(FolioState(loading = false))
        val storage = Storage().also { it.failGroup = true }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writer = DocumentWriteCoordinator(state, storage, scope, Mutex())
        writer.start()
        try {
            writer.queue(page("a"))
            writer.queue(DocumentWriteOp.Document("n", "g", DocumentHistory(), emptyMap(), emptyMap()))
            writer.queue(page("c"))
            assertTrue(withTimeout(5000) { runCatching { writer.awaitSaved() }.isFailure })
            assertTrue(state.value.saveFailed); assertTrue(state.value.saving)
            writer.queue(page("d"))
            assertTrue(withTimeout(5000) { runCatching { writer.awaitQueuedWrites() }.isFailure })
            assertFalse("Later ink cannot overtake the failed group", storage.events.contains("page:d"))
            writer.retrySave()
            withTimeout(5000) { writer.awaitSaved() }
            assertEquals(listOf("page:a", "group:g:failed", "group:g", "page:c", "page:d", "folders"), storage.events.take(6))
            assertEquals(1, storage.events.count { it == "page:a" })
            assertFalse(state.value.saveFailed); assertFalse(state.value.retryingSave)
            assertEquals(state.value.editGeneration, state.value.durableGeneration)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun idleCheckpointUsesLatestNotebookAndDoesNotResurrectDeletion() = runBlocking {
        val state = DocumentStateStore(FolioState(loading = false))
        state.update { it.copy(notes = listOf(Notebook(id = "n", title = "Notebook"))) }
        val storage = Storage()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writer = DocumentWriteCoordinator(state, storage, scope, Mutex())
        writer.start()
        try {
            writer.queue(page("a"))
            writer.enqueue { state.update { it.copy(notes = emptyList()) } }
            withTimeout(5000) { writer.awaitSaved() }
            assertFalse(storage.events.any { it.startsWith("index:") })
            assertEquals(state.value.editGeneration, state.value.durableGeneration)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun immediateStateObserversCannotReverseWriteAdmission() = runBlocking {
        val state = DocumentStateStore(FolioState(loading = false))
        val storage = Storage()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writer = DocumentWriteCoordinator(state, storage, scope, Mutex())
        writer.start()
        try {
            val observer = launch(Dispatchers.Unconfined) {
                state.state.drop(1).take(1).collect { writer.queue(page("a", 2)) }
            }
            writer.queue(page("a", 1))
            observer.join()
            withTimeout(5000) { writer.awaitSaved() }
            assertEquals(listOf(1, 2), storage.batches.flatten())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun barriersDoNotCreateEditsAndReturnOnlyAfterDurabilityIsPublished() = runBlocking {
        val state = DocumentStateStore(FolioState(loading = false))
        val storage = Storage()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writer = DocumentWriteCoordinator(state, storage, scope, Mutex())
        writer.start()
        try {
            writer.queue(page("a"))
            val generation = state.value.editGeneration
            val waiter = async(Dispatchers.Unconfined) {
                writer.awaitSaved()
                state.value.durableGeneration
            }
            assertEquals(generation, withTimeout(5000) { waiter.await() })
            assertEquals(generation, state.value.editGeneration)
            assertFalse(state.value.saving)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun delayedCheckpointCannotWriteAnIndexAfterNotebookDeletion() = runBlocking {
        val state = DocumentStateStore(FolioState(notes = listOf(Notebook(id = "n", title = "Notebook")), loading = false))
        val storage = Storage()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writer = DocumentWriteCoordinator(state, storage, scope, Mutex())
        writer.queueIndexCheckpoint("n")
        state.update { it.copy(notes = emptyList()) }
        writer.start()
        try {
            withTimeout(5000) { writer.awaitSaved() }
            assertFalse(storage.events.any { it.startsWith("index:") })
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun cancellationWakesBarriersWithoutReportingAStorageFailure() = runBlocking {
        val state = DocumentStateStore(FolioState(loading = false))
        val storage = Storage().also { it.cancelPage = true }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writer = DocumentWriteCoordinator(state, storage, scope, Mutex())
        writer.start()
        try {
            writer.queue(page("a"))
            val result = withTimeout(5000) { runCatching { writer.awaitSaved() } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertFalse(state.value.saveFailed)
            assertNull(state.value.error)
            yield()
            val generation = state.value.editGeneration
            assertThrows(IllegalStateException::class.java) { writer.queue(page("b")) }
            assertEquals(generation, state.value.editGeneration)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
