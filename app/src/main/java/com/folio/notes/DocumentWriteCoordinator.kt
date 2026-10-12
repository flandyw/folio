package com.folio.notes

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.folio.notes.DocumentWriteOp as WriteOp

/** Sole application writer: batches, ordered replay, barriers, generations and idle checkpoints. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class DocumentWriteCoordinator(
    private val _state: DocumentStateStore,
    private val storage: DocumentWriteStorage,
    private val scope: CoroutineScope,
    private val storageGate: Mutex,
    private val onIdleBackup: () -> Unit = {},
) {
    private val writes = Channel<WriteOp>(Channel.UNLIMITED)
    /** Writer-owned: failed operations retain their order; later edits never leap over a group. */
    private val failedWrites = ArrayList<WriteOp>()
    private val failedBarriers = ArrayList<WriteOp.Once>()
    /** Writer-owned: retain dirty indexes across batches until the queue actually drains. */
    private val pendingIndexCheckpoints = LinkedHashSet<String>()
    @Volatile private var autoBackupDirty = false

    internal fun enqueue(scheduleAutoBackup: Boolean = true, block: suspend () -> Unit): Unit = queue(WriteOp.Once(block), scheduleAutoBackup)

    private val admission = Any()
    internal fun queue(op: WriteOp, scheduleAutoBackup: Boolean = true) = synchronized(admission) {
        if (scheduleAutoBackup) autoBackupDirty = true
        var generation = 0L
        _state.update { generation = it.editGeneration + 1; it.copy(editGeneration = generation,
            lastSaveProgressAt = it.lastSaveProgressAt ?: System.currentTimeMillis()) }
        op.generation = generation
        check(writes.trySend(op).isSuccess) { "Document writer is closed" }
    }

    internal suspend fun collectWrites(batch: ArrayList<WriteOp>) {
        val deadline = System.currentTimeMillis() + WRITE_BATCH_WINDOW_MS
        while (batch.size < MAX_WRITE_BATCH) {
            val queued = writes.tryReceive().getOrNull()
            val next = queued ?: if (batch.last() is WriteOp.Page) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) null
                else runCatching { withTimeoutOrNull(wait) { writes.receive() } }.getOrNull()
            } else null
            if (next == null) return
            batch.add(next)
        }
    }

    internal suspend fun executeWriteOps(batch: List<WriteOp>) {
        var index = 0
        try {
            while (index < batch.size) {
                val op = batch[index]
                if (op is WriteOp.Document) {
                    storage.appendDocumentTransactions(op.noteId, op.id, op.history, op.transactions, op.pages)
                    pendingIndexCheckpoints += op.noteId
                    index++; continue
                }
                if (op !is WriteOp.Page) { (op as WriteOp.Once).block(); index++; continue }
                var end = index
                while (end < batch.size) {
                    val next = batch[end] as? WriteOp.Page ?: break
                    if (next.noteId != op.noteId || next.pageId != op.pageId) break
                    end++
                }
                val group = (index until end).map { batch[it] as WriteOp.Page }
                // Any bytes a record names land first, so a crash cannot leave a placement without its file.
                group.forEach { it.before() }
                storage.appendPageTransactions(op.noteId, op.pageId, group.map { it.transaction })
                pendingIndexCheckpoints += op.noteId
                index = end
            }
        } catch (e: Exception) {
            // Append failed and unattempted operations exactly once. Successfully synced groups
            // are excluded; a failed group's durable intent makes its retry idempotent.
            val remaining = batch.drop(index)
            failedWrites.addAll(remaining.filterNot { it is WriteOp.Once && (it.recovery || it.barrier) })
            failedBarriers.addAll(remaining.filterIsInstance<WriteOp.Once>().filter { it.barrier })
            throw e
        }
    }

    internal suspend fun runWrites(batch: List<WriteOp>) {
        val recovery = batch.indexOfFirst { it is WriteOp.Once && it.recovery }
        if (_state.value.saveFailed && recovery < 0) {
            failedWrites.addAll(batch.filterNot { it is WriteOp.Once && it.barrier })
            batch.filterIsInstance<WriteOp.Once>().filter { it.barrier }.forEach { it.block() }
            return
        }
        val work = if (_state.value.saveFailed && recovery >= 0) {
            val preceding = batch.take(recovery)
            failedWrites.addAll(preceding.filterNot { it is WriteOp.Once && it.barrier })
            preceding.filterIsInstance<WriteOp.Once>().filter { it.barrier }.forEach { it.block() }
            batch.drop(recovery)
        } else batch
        val target = work.maxOfOrNull { it.generation } ?: 0L
        try {
            storageGate.withLock {
                executeWriteOps(work)
            }
        } catch (e: Exception) {
            val reason = when (e) {
                is java.io.IOException -> "Device storage could not finish the write. Check free space and storage access."
                is SecurityException -> "Folio lost access to storage. Check the app's storage access."
                else -> "The device could not finish writing these changes. It did not report a specific storage cause."
            }
            _state.update { it.copy(saveFailed = true, saveFailureReason = reason,
                error = "$reason Your latest changes are still open here. Use Retry save before closing Folio.") }
            val barriers = failedBarriers.toList(); failedBarriers.clear()
            barriers.forEach { it.block() }
            // Failed barriers wake after saveFailed is set; exports fail instead of hanging or claiming a save.
            return
        }
        _state.update { state ->
            state.copy(durableGeneration = maxOf(state.durableGeneration, target),
                lastSaveProgressAt = if (writes.isEmpty) null else System.currentTimeMillis())
        }
        if (writes.isEmpty) {
            val dirty = pendingIndexCheckpoints.toList()
            pendingIndexCheckpoints.clear()
            dirty.forEach { queueIndexCheckpoint(it) }
            if (writes.isEmpty) compactPendingPages()
        }
        if (writes.isEmpty && autoBackupDirty) {
            autoBackupDirty = false
            onIdleBackup()
        }
    }

    internal fun queueIndexCheckpoint(noteId: String) {
        val note = _state.value.notes.notebook(noteId) ?: return
        // Not counted as an edit: the ink is already durable in the journal, so a checkpoint must not
        // flip the page back to "Saving…" (which locks mistake ratings) for housekeeping.
        writes.trySend(WriteOp.Once(block = { storage.saveMeta(note) }))
    }

    internal suspend fun compactPendingPages() {
        runCatching { storage.compactPending() }
    }

    internal suspend fun awaitQueuedWrites() = awaitSaved()

    fun retrySave() {
        if (!_state.value.saveFailed || _state.value.retryingSave) return
        _state.update { it.copy(retryingSave = true) }
        queue(WriteOp.Once({
            try {
                val pending = failedWrites.toList()
                failedWrites.clear()
                executeWriteOps(pending)
                val snapshot = _state.value
                storage.saveFolders(snapshot.folders)
                _state.update { it.copy(saveFailed = false, retryingSave = false, saveFailureReason = null) }
            } catch (e: Exception) {
                _state.update { it.copy(retryingSave = false) }
                throw e
            }
        }, recovery = true))
    }

    internal suspend fun awaitSaved() {
        val barrier = CompletableDeferred<Unit>()
        queue(WriteOp.Once(block = { barrier.complete(Unit) }, barrier = true), scheduleAutoBackup = false)
        barrier.await()
        check(!_state.value.saveFailed) { "Some changes have not been saved. Use Retry save first." }
    }
    private var worker: Job? = null
    internal fun start() {
        check(worker == null) { "The document writer is already running" }
        worker = scope.launch {
            for (first in writes) {
                val batch = ArrayList<WriteOp>()
                batch.add(first)
                collectWrites(batch)
                runWrites(batch)
            }
        }
    }
    private companion object {
        const val WRITE_BATCH_WINDOW_MS = 24L
        const val MAX_WRITE_BATCH = 64
    }
}
