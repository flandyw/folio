package com.folio.notes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PdfImportBatchTests {
    @Test fun deduplicatesAndContinuesAfterUnreadableFile() = runBlocking {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Int>()
        val progress = mutableListOf<Pair<Int, Int>>()
        importBatch(listOf("first", "broken", "first", "last"),
            importItem = { if (it == "broken") error("Unreadable") else it },
            onSuccess = { successes += it },
            onFailure = { index, _ -> failures += index },
            onProgress = { current, total -> progress += current to total })
        assertEquals(listOf("first", "last"), successes)
        assertEquals(listOf(2), failures)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test fun cancellationStopsTheBatchInsteadOfReportingAFileFailure() = runBlocking {
        val visited = mutableListOf<String>()
        try {
            importBatch(listOf("first", "cancel", "last"),
                importItem = { visited += it; if (it == "cancel") throw CancellationException() else it },
                onSuccess = {}, onFailure = { _, _ -> fail("Cancellation is not a failed PDF") },
                onProgress = { _, _ -> })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf("first", "cancel"), visited)
    }
}
