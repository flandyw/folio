package com.folio.notes

import kotlinx.coroutines.CancellationException

/** Imports serially to keep PDF memory use bounded; an unreadable file does not stop the batch. */
internal suspend fun <T, R> importBatch(
    items: List<T>,
    importItem: suspend (T) -> R,
    onSuccess: (R) -> Unit,
    onFailure: (Int, Exception) -> Unit,
    onProgress: (Int, Int) -> Unit
) {
    val unique = items.distinct()
    unique.forEachIndexed { index, item ->
        onProgress(index + 1, unique.size)
        val result = try { importItem(item) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onFailure(index + 1, e); return@forEachIndexed }
        onSuccess(result)
    }
}
