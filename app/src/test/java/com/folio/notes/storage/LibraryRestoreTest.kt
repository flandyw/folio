package com.folio.notes.storage

import com.folio.notes.model.Notebook
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LibraryRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancellationAfterEveryInstallWaitsForCleanupAndKeepsExistingNotebooks() = runBlocking {
        for (count in 0..3) {
            val root = temporary.newFolder("library-$count")
            val existing = File(root, "existing").apply { mkdirs() }
            val keep = File(existing, "note.json").apply { writeText("Existing notebook") }
            val gate = Mutex(locked = true)
            val deleting = CompletableDeferred<Unit>()
            val added = mutableListOf<Notebook>()
            val deleted = mutableListOf<String>()
            val cancelled = CancellationException("Interrupted after $count notebooks")
            var failure: Throwable? = null
            val restore = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    repeat(count) { index ->
                        val note = Notebook(id = "restored-$index", title = "Restored")
                        File(root, note.id).apply { mkdirs(); File(this, "note.json").writeText(note.title) }
                        added += note
                    }
                    awaitCancellation()
                } catch (e: Throwable) {
                    rollbackLibraryRestore(added, e) { id ->
                        withContext(Dispatchers.IO) {
                            deleting.complete(Unit)
                            gate.withLock {
                                check(File(root, id).deleteRecursively())
                                deleted += id
                            }
                        }
                    }
                }
            }
            restore.invokeOnCompletion { failure = it }
            try {
                restore.cancel(cancelled)
                if (count > 0) {
                    withTimeout(5000) { deleting.await() }
                    assertFalse("Cancellation must wait for the storage lock and cleanup", restore.isCompleted)
                    assertTrue(added.all { File(root, it.id).exists() })
                }
            } finally {
                gate.unlock()
                withTimeout(5000) { restore.join() }
            }
            assertSame(cancelled, failure)
            assertEquals(added.map { it.id }, deleted)
            assertEquals(listOf("existing"), root.list()!!.toList())
            assertEquals("Existing notebook", keep.readText())
        }
    }

    @Test fun oneCleanupFailureDoesNotSkipLaterNotebooksOrHideTheRestoreFailure() = runBlocking {
        val added = (1..3).map { Notebook(id = "restored-$it", title = "Restored") }
        val failure = IOException("Restore failed")
        val cleanup = IOException("First delete failed")
        val attempted = mutableListOf<String>()
        val error = runCatching {
            rollbackLibraryRestore(added, failure) { id ->
                attempted += id
                if (id == added.first().id) throw cleanup
                yield()
            }
        }.exceptionOrNull()
        assertSame(failure, error)
        assertEquals(added.map { it.id }, attempted)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
    }

    @Test fun rethrowingTheSameFailureDuringCleanupStillAttemptsEveryNotebook() = runBlocking {
        val added = (1..2).map { Notebook(id = "restored-$it", title = "Restored") }
        val failure = CancellationException("Stopped import")
        val attempted = mutableListOf<String>()
        val error = runCatching {
            rollbackLibraryRestore(added, failure) { id -> attempted += id; throw failure }
        }.exceptionOrNull()
        assertSame(failure, error)
        assertEquals(added.map { it.id }, attempted)
        assertTrue(failure.suppressed.isEmpty())
    }
}
