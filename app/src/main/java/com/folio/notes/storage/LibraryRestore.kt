package com.folio.notes.storage

import com.folio.notes.model.Notebook
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Cancellation must not skip the suspending file/cache cleanup of notebooks already installed. */
internal suspend fun rollbackLibraryRestore(
    added: List<Notebook>,
    failure: Throwable,
    delete: suspend (String) -> Unit
): Nothing {
    withContext(NonCancellable) {
        added.forEach { note ->
            try { delete(note.id) }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        }
    }
    throw failure
}
