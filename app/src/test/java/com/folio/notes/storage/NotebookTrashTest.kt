package com.folio.notes.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotebookTrashTest {
    @get:Rule val temporary = TemporaryFolder()
    private val notebooks by lazy { temporary.newFolder("notebooks") }
    private val bin by lazy { File(temporary.root, "trash/notebooks") }
    private fun store() = NotebookTrash(notebooks, bin)

    private fun notebook(id: String): File = File(notebooks, id).apply {
        mkdirs()
        File(this, "note.json").writeText("saved index")
        File(this, "pages").mkdirs()
        File(this, "pages/page.fps").writeBytes(byteArrayOf(0, 1, 2, -1))
        File(this, "pages/page.fjl").writeText("uncompacted ink and undo")
        File(this, "document.pending").writeText("recovery envelope")
        File(this, "source.pdf").writeText("original pdf")
        File(this, "images").mkdirs()
        File(this, "images/photo.jpg").writeBytes(byteArrayOf(7, 8, 9))
    }
    private fun contents(dir: File) = dir.walkTopDown().filter { it.isFile && it.name != "trashed-at" }
        .associate { it.relativeTo(dir).path to it.readBytes().toList() }

    @Test fun completeNotebookSurvivesTrashRestartAndRestore() {
        val original = notebook("note-1")
        val before = contents(original)
        store().move("note-1", 123456L)
        assertFalse(original.exists())
        val reopened = store()
        val entry = reopened.entries().single()
        assertEquals(123456L, reopened.deletedAt(entry))
        assertEquals(before, contents(entry))
        reopened.restore("note-1")
        assertTrue(reopened.entries().isEmpty())
        assertEquals(before, contents(original))
        assertFalse(File(original, "trashed-at").exists())
    }

    @Test fun replayingMoveAndPermanentDeleteIsSafe() {
        notebook("note-1")
        store().move("note-1", 123L)
        store().move("note-1", 999L)
        assertEquals(123L, store().deletedAt(store().entries().single()))
        store().deleteForever("note-1")
        store().deleteForever("note-1")
        assertTrue(store().entries().isEmpty())
        assertFalse(File(notebooks, "note-1").exists())
    }

    @Test fun restoreRefusesToOverwriteAnActiveNotebook() {
        notebook("note-1")
        store().move("note-1", 123L)
        val occupied = notebook("note-1")
        File(occupied, "note.json").writeText("different notebook")
        assertThrows(IllegalStateException::class.java) { store().restore("note-1") }
        assertEquals("different notebook", File(occupied, "note.json").readText())
        assertEquals("saved index", File(store().entries().single(), "note.json").readText())
    }

    @Test fun moveRefusesToOverwriteAnExistingTrashEntry() {
        notebook("note-1")
        store().move("note-1", 123L)
        val other = notebook("note-1")
        assertThrows(IllegalStateException::class.java) { store().move("note-1", 456L) }
        assertTrue(other.exists())
        assertEquals(123L, store().deletedAt(store().entries().single()))
    }

    @Test fun restartingFinishesOnlyPreviouslyConfirmedPurges() {
        notebook("note-1")
        store().move("note-1", 123L)
        val detached = File(bin, ".purge-interrupted").apply { mkdirs() }
        File(detached, "large.pdf").writeText("leftover")
        assertEquals("note-1", store().entries().single().name)
        assertFalse(detached.exists())
    }

    @Test fun idsCannotEscapeTheStorageRoots() {
        val outside = temporary.newFile("keep-me")
        for (id in listOf("../keep-me", "", "/keep-me", "a/b", ".purge-secret")) {
            assertThrows(IllegalArgumentException::class.java) { store().move(id, 1L) }
            assertThrows(IllegalArgumentException::class.java) { store().restore(id) }
            assertThrows(IllegalArgumentException::class.java) { store().deleteForever(id) }
        }
        assertTrue(outside.exists())
    }
}
