package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking

class LibraryBackupTests {
    @Test fun fullLibraryContainsFolderManifestAndPortableNotebooks() {
        val folder = Folder(name = "Research")
        val notes = listOf(Notebook(title = "One", folderId = folder.id), Notebook(title = "Two"))
        val bytes = ByteArrayOutputStream().also { output -> runBlocking {
            LibraryBackup.write(output, listOf(folder), notes) { note, stream ->
                NotebookArchive.write(note, null, stream)
            }
        } }.toByteArray()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            assertEquals(LibraryBackup.MANIFEST, zip.nextEntry.name)
            val manifest = LibraryBackup.parseManifest(zip.readBytes().toString(Charsets.UTF_8))
            assertEquals(listOf(folder), manifest.folders)
            assertEquals(notes.map { it.id }, manifest.notebookIds)
            zip.closeEntry()
            notes.forEach { note ->
                assertEquals(LibraryBackup.entryName(note.id), zip.nextEntry.name)
                assertEquals(note, NotebookArchive.read(object : FilterInputStream(zip) { override fun close() {} }).note)
                zip.closeEntry()
            }
            assertNull(zip.nextEntry)
        }
    }

    @Test fun manifestRejectsUnknownVersionAndDuplicateIds() {
        val note = Notebook(title = "One")
        val valid = LibraryBackup.manifest(emptyList(), listOf(note))
        assertThrows(Exception::class.java) { LibraryBackup.parseManifest(valid.replace("\"version\":1", "\"version\":2")) }
        assertThrows(Exception::class.java) { LibraryBackup.parseManifest(LibraryBackup.manifest(emptyList(), listOf(note, note))) }
        assertThrows(Exception::class.java) { LibraryBackup.entryName("../escape") }
    }
}
