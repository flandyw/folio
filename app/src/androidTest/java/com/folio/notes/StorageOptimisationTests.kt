package com.folio.notes

import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class StorageOptimisationTests {
    @Test fun importedPdfNeedsOnlyAnIndexUntilAnnotatedAndRestoresUndo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "import-test-${UUID.randomUUID()}.pdf")
        var notebookDir: File? = null
        try {
            val pdf = PdfDocument()
            try {
                repeat(12) { index ->
                    val info = PdfDocument.PageInfo.Builder(600, 800 + index * 10, index + 1).create()
                    pdf.finishPage(pdf.startPage(info))
                }
                source.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            val repository = NoteRepository(context)
            val uri = Uri.fromFile(source)
            val note = repository.importPdf(uri, null, PendingPdfImport(uri, "Storage test", detected = true))
            val dir = File(context.filesDir, "notebooks/${note.id}")
            notebookDir = dir
            assertArrayEquals(source.readBytes(), File(dir, "source.pdf").readBytes())
            assertFalse("Import must not write empty snapshots or journals", File(dir, "pages").exists())
            val reopened = NoteRepository(context)
            val index = reopened.load().first.single { it.id == note.id }
            assertEquals(12, index.pages.size)
            index.pages.forEachIndexed { i, summary ->
                val page = reopened.loadPage(note.id, summary)
                assertEquals(i, page.pdfIndex)
                assertEquals(840f * (800 + i * 10) / 600, page.height, .001f)
                assertTrue(page.loaded)
                assertTrue(page.strokes.isEmpty())
            }
            val page = note.pages.first()
            val stroke = Stroke(Tool.PEN, -16777216, 3f, listOf(InkPoint(10f, 20f), InkPoint(30f, 40f)))
            val marked = page.copy(strokes = listOf(stroke), revision = 1)
            val edit = requireNotNull(PageJournal.diff(page.content(), marked.content()))
            val inverse = PageJournal.invert(edit, page.content())
            repository.appendPageTransactions(note.id, page.id,
                listOf(PageTransaction(0, 1, edit, undoPush = inverse, clearRedo = true)))
            val afterRestart = NoteRepository(context)
            assertEquals(marked.strokes, afterRestart.loadPage(note.id, index.pages.first()).strokes)
            val history = afterRestart.loadHistory(note.id, page.id)
            assertEquals(listOf(inverse), history.undo)
            assertEquals(page.content(), PageJournal.apply(marked.content(), history.undo.single()))
        } finally {
            notebookDir?.deleteRecursively()
            source.delete()
        }
    }

    @Test fun streamedBatchSurvivesRestartAndSnapshotClearsOnlyExistingJournal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = NoteRepository(context)
        val note = Notebook(title = "Streamed storage test")
        val page = note.pages.single()
        val dir = File(context.filesDir, "notebooks/${note.id}")
        try {
            repository.saveAll(note)
            val journal = File(dir, "pages/${page.id}.journal")
            assertFalse("A fresh snapshot needs no empty journal", journal.exists())
            var current = page
            val transactions = (1..64).map { revision ->
                val next = current.copy(revision = revision, texts = listOf(TextBox(x = 10f, y = 20f, text = "Maths ∑ $revision — 中文")))
                val edit = requireNotNull(PageJournal.diff(current.content(), next.content()))
                val inverse = PageJournal.invert(edit, current.content())
                current = next
                PageTransaction(0, revision, edit, undoPush = inverse, clearRedo = true)
            }
            repository.appendPageTransactions(note.id, page.id, transactions)
            val reopened = NoteRepository(context)
            val restored = reopened.loadPage(note.id, page.asSummary())
            assertEquals(current.texts, restored.texts)
            assertEquals(64, restored.revision)
            val history = reopened.loadHistory(note.id, page.id)
            assertEquals(transactions.mapNotNull { it.undoPush }.takeLast(PageJournal.HISTORY_LIMIT), history.undo)
            // Force an encoding failure after enough valid records to flush the writer's
            // buffer. None of this failed batch may become visible, even after reopening.
            val length = journal.length()
            val invalid = PageTransaction(0, 65,
                PageEdit(texts = listOf(TextBox(x = 0f, y = 0f, text = "invalid", size = Float.NaN))))
            var rejected = false
            try { reopened.appendPageTransactions(note.id, page.id, transactions + invalid) }
            catch (_: Exception) { rejected = true }
            assertTrue("Non-finite JSON must fail the append", rejected)
            assertEquals(length, journal.length())
            assertEquals(history, NoteRepository(context).loadHistory(note.id, page.id))
            reopened.saveAll(note.copy(pages = listOf(restored)), mapOf(page.id to history))
            assertEquals(0L, journal.length())
            val afterSnapshot = NoteRepository(context)
            assertEquals(current.texts, afterSnapshot.loadPage(note.id, page.asSummary()).texts)
            assertEquals(history, afterSnapshot.loadHistory(note.id, page.id))
            val next = restored.copy(revision = 65, texts = listOf(TextBox(x = 1f, y = 2f, text = "after rollback")))
            val edit = requireNotNull(PageJournal.diff(restored.content(), next.content()))
            afterSnapshot.appendPageTransactions(note.id, page.id, listOf(PageTransaction(0, 65, edit)))
            assertEquals(65, requireNotNull(PageJournal.decode(journal.readLines().single())).seq)
            assertEquals(next.texts, NoteRepository(context).loadPage(note.id, page.asSummary()).texts)
        } finally { dir.deleteRecursively() }
    }
}
