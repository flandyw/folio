package com.folio.notes.storage

import com.folio.notes.model.InkPoint
import com.folio.notes.model.NoteCodec
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.PageImage
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NotebookArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val note = Notebook(title = "Legacy notebook", pages = listOf(NotePage(pdfIndex = 0,
        strokes = listOf(Stroke(Tool.PEN, 0xFF123456.toInt(), 2f, listOf(InkPoint(1f, 2f)))),
        images = listOf(PageImage("photo-1", 10f, 20f, 100f, 150f)))))
    private fun staging() = File(temporary.root, "decoded")

    private fun zip(block: ZipOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use(block)
    }.toByteArray()

    private fun ZipOutputStream.zeros(name: String, size: Long) {
        putNextEntry(ZipEntry(name))
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val count = minOf(left, buffer.size.toLong()).toInt()
            write(buffer, 0, count)
            left -= count
        }
        closeEntry()
    }

    @Test fun legacyAssetsRoundTripAsFilesAlongsideUnchangedContent() {
        val pdf = byteArrayOf(1, 2, 3)
        val image = byteArrayOf(4, 5, 6)
        val bytes = ByteArrayOutputStream().also { NotebookArchive.write(note, pdf, mapOf("photo-1" to image), it) }.toByteArray()
        val back = NotebookArchive.read(bytes.inputStream(), staging())
        assertEquals(note, back.note)
        assertArrayEquals(pdf, back.pdf!!.readBytes())
        assertArrayEquals(image, back.images.getValue("photo-1").readBytes())
        assertEquals(File(staging(), "source.pdf").canonicalFile, back.pdf?.canonicalFile)
        assertEquals(File(staging(), "images/photo-1").canonicalFile, back.images.getValue("photo-1").canonicalFile)
    }

    @Test fun plainLegacyNotebooksAndUnknownEntriesRemainReadable() {
        val plain = Notebook(title = "Without assets")
        val bytes = zip {
            zeros("extra.txt", 10)
            zeros("images/../../outside", 10)
            putNextEntry(ZipEntry("note.json"))
            write(NoteCodec.encode(plain).toByteArray())
            closeEntry()
        }
        val back = NotebookArchive.read(bytes.inputStream(), staging())
        assertEquals(plain, back.note)
        assertNull(back.pdf)
        assertTrue(back.images.isEmpty())
        assertEquals(listOf("note.json"), staging().list()!!.toList())
    }

    @Test fun missingOrMalformedNotebookRemovesStagedAssets() {
        for (json in listOf(null, "{")) {
            val bytes = zip {
                zeros("source.pdf", 10)
                if (json != null) {
                    putNextEntry(ZipEntry("note.json")); write(json.toByteArray()); closeEntry()
                }
            }
            assertThrows(Exception::class.java) { NotebookArchive.read(bytes.inputStream(), staging()) }
            assertFalse(staging().exists())
        }
    }

    @Test fun duplicateAssetsAreRejectedBeforeOverwritingTheFirstCopy() {
        val bytes = zip { zeros("images/photo-1", 1); zeros("images/photo-2", 1) }
        val original = "images/photo-2".toByteArray()
        val duplicate = "images/photo-1".toByteArray()
        // ZipOutputStream forbids duplicate names, so change both headers after writing.
        for (offset in 0..bytes.size - original.size) {
            if (original.indices.all { bytes[offset + it] == original[it] }) duplicate.copyInto(bytes, offset)
        }
        val error = assertThrows(IllegalArgumentException::class.java) { NotebookArchive.read(bytes.inputStream(), staging()) }
        assertEquals("Invalid image entry", error.message)
        assertFalse(staging().exists())
    }

    @Test fun oversizedImageIsRejectedAndRemoved() {
        val bytes = zip { zeros("images/photo-1", NotebookArchive.MAX_IMAGE_BYTES + 1) }
        val error = assertThrows(IllegalArgumentException::class.java) { NotebookArchive.read(bytes.inputStream(), staging()) }
        assertEquals("Backup entry is too large", error.message)
        assertFalse(staging().exists())
    }

    @Test fun individuallyLegalImagesShareOneDecompressedByteBudget() {
        val bytes = zip { repeat(11) { zeros("images/photo-$it", NotebookArchive.MAX_IMAGE_BYTES) } }
        val error = assertThrows(IllegalArgumentException::class.java) { NotebookArchive.read(bytes.inputStream(), staging()) }
        assertEquals("Backup is too large", error.message)
        assertFalse(staging().exists())
    }

    @Test fun skippedEntriesCannotBypassTheDecompressionBudget() {
        val bytes = zip {
            zeros("extra-1", NotebookArchive.MAX_ARCHIVE_BYTES / 2)
            zeros("images/invalid.png", NotebookArchive.MAX_ARCHIVE_BYTES / 2 + 1)
        }
        val error = assertThrows(IllegalArgumentException::class.java) { NotebookArchive.read(bytes.inputStream(), staging()) }
        assertEquals("Backup is too large", error.message)
        assertFalse(staging().exists())
    }

    @Test fun emptyEntriesCannotBypassTheEntryBudget() {
        val bytes = zip { repeat(NotebookArchive.MAX_ENTRIES + 1) { zeros("extra-$it", 0) } }
        val error = assertThrows(IllegalArgumentException::class.java) { NotebookArchive.read(bytes.inputStream(), staging()) }
        assertEquals("Backup has too many entries", error.message)
        assertFalse(staging().exists())
    }

    @Test fun cancellationDuringAnAssetCopyRemovesAllStagedFiles() {
        val bytes = zip { zeros("source.pdf", 1_000_000) }
        val cancelled = CancellationException("Stopped import")
        val error = assertThrows(CancellationException::class.java) {
            NotebookArchive.read(bytes.inputStream(), staging()) {
                if (File(staging(), "source.pdf").length() > 0) throw cancelled
            }
        }
        assertSame(cancelled, error)
        assertFalse(staging().exists())
    }

    @Test fun anExistingDirectoryIsNeverOverwrittenOrRemoved() {
        val keep = File(temporary.newFolder("decoded"), "keep").apply { writeText("Existing data") }
        assertThrows(IllegalArgumentException::class.java) { NotebookArchive.read(byteArrayOf().inputStream(), staging()) }
        assertEquals("Existing data", keep.readText())
    }
}
