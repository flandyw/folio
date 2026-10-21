package com.folio.notes.storage

import com.folio.notes.model.Notebook
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Trash keeps complete notebook directories; page snapshots and journals are never rewritten. */
data class TrashedNotebook(val notebook: Notebook, val deletedAt: Long)

/** Called under the repository lock. Same-filesystem renames make each lifecycle change atomic. */
internal class NotebookTrash(private val notebooks: File, private val trash: File) {
    private val idPattern = Regex("[a-zA-Z0-9-]+")
    private fun directory(root: File, id: String): File {
        require(id.matches(idPattern)) { "Invalid notebook id" }
        return File(root, id)
    }

    fun stored(id: String): File = directory(trash, id)

    fun entries(): List<File> {
        // A purge that was interrupted has already been explicitly confirmed and detached.
        trash.listFiles().orEmpty().filter { it.name.startsWith(".purge-") }.forEach { it.deleteRecursively() }
        return trash.listFiles().orEmpty().filter {
            it.isDirectory && it.name.matches(idPattern) &&
                (File(it, "note.json").exists() || File(it, "note.json.bak").exists())
        }
    }

    fun deletedAt(dir: File): Long = File(dir, "trashed-at").let {
        it.takeIf(File::isFile)?.readText()?.toLongOrNull() ?: dir.lastModified()
    }

    fun move(id: String, deletedAt: Long) {
        val source = directory(notebooks, id)
        val destination = stored(id)
        if (destination.isDirectory && !source.exists()) return // replay after a committed rename
        check(source.isDirectory && !destination.exists()) { "Couldn't move notebook to Trash" }
        check(trash.isDirectory || trash.mkdirs()) { "Couldn't create Trash" }
        val marker = File(source, "trashed-at")
        FileOutputStream(marker).use { it.write(deletedAt.toString().toByteArray()); it.fd.sync() }
        check(source.renameTo(destination)) { "Couldn't move notebook to Trash" }
    }

    fun restore(id: String) {
        val source = stored(id)
        val destination = directory(notebooks, id)
        check(source.isDirectory && !destination.exists()) { "Couldn't restore notebook; its destination is occupied" }
        check(source.renameTo(destination)) { "Couldn't restore notebook" }
        File(destination, "trashed-at").delete()
    }

    fun deleteForever(id: String) {
        val source = stored(id)
        if (!source.exists()) return
        val detached = File(trash, ".purge-${UUID.randomUUID()}")
        check(source.renameTo(detached)) { "Couldn't delete notebook forever" }
        // If cleanup is interrupted, entries() resumes it on the next load.
        detached.deleteRecursively()
    }
}
