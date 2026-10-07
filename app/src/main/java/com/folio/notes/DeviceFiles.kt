package com.folio.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class DeviceFile(
    val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long, val flags: Int
) {
    val directory get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    val pdf get() = !directory && (mime == "application/pdf" || name.endsWith(".pdf", true))
    val folio get() = !directory && name.endsWith(".folio", true)
    val virtual get() = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
    fun supports(flag: Int) = flags and flag != 0
}

/** Both shared disk storage and connected document providers use the same explorer actions. */
internal class DeviceFiles(private val context: Context) {
    private val storage = TabletStorage(context)
    private val resolver get() = context.contentResolver
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS)

    fun root(tree: Uri): Uri = if (tree.scheme == "file") tree else DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Content URIs preserve names/imports and grant just the selected file to other apps. */
    fun contentUri(uri: Uri): Uri = if (uri.scheme == "file")
        FileProvider.getUriForFile(context, "${context.packageName}.files", storage.file(uri)) else uri

    suspend fun info(uri: Uri): DeviceFile = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext localInfo(storage.file(uri))
        val writable = canWrite(uri)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "This folder is no longer available" }
            read(uri, cursor, writable)
        } ?: error("This folder is no longer available")
    }

    suspend fun children(parent: Uri): List<DeviceFile> = withContext(Dispatchers.IO) {
        if (parent.scheme == "file") {
            val directory = storage.file(parent)
            val roots = storage.volumes().map { File(requireNotNull(it.uri.path)).canonicalFile }
            val entries = directory.listFiles() ?: error("Android doesn't allow access to this folder")
            return@withContext buildList {
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    if (!Files.isSymbolicLink(entry.toPath()) && SharedStorageRules.contains(roots, entry.canonicalFile)) add(localInfo(entry, roots))
                }
            }
        }
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val writable = canWrite(parent)
        resolver.query(query, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    add(read(parent, cursor, writable))
                }
            }
        } ?: error("Couldn't read this folder")
    }

    private fun localInfo(file: File, roots: List<File> = storage.volumes().map { File(requireNotNull(it.uri.path)).canonicalFile }): DeviceFile {
        check(file.exists()) { "This file is no longer available" }
        val directory = file.isDirectory
        val root = roots.any { it == file.canonicalFile }
        var flags = if (directory && file.canWrite()) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
        if (!root && file.parentFile?.canWrite() == true) flags = flags or
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        val mime = if (directory) DocumentsContract.Document.MIME_TYPE_DIR else
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
        return DeviceFile(Uri.fromFile(file), file.name, mime, if (directory) -1 else file.length(), file.lastModified(), flags)
    }

    private fun editableFile(uri: Uri): File = storage.file(uri).also { file ->
        check(storage.volumes().none { File(requireNotNull(it.uri.path)).canonicalFile == file }) { "A storage volume cannot be renamed or deleted" }
    }

    private fun canWrite(document: Uri): Boolean {
        val tree = DocumentsContract.buildTreeDocumentUri(document.authority, DocumentsContract.getTreeDocumentId(document))
        return resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }

    private fun read(tree: Uri, cursor: android.database.Cursor, writable: Boolean): DeviceFile {
        fun text(column: String, fallback: String = "") = cursor.getColumnIndex(column).let {
            if (it < 0 || cursor.isNull(it)) fallback else cursor.getString(it)
        }
        fun number(column: String, fallback: Long = 0) = cursor.getColumnIndex(column).let {
            if (it < 0 || cursor.isNull(it)) fallback else cursor.getLong(it)
        }
        val writes = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        val flags = number(columns[5]).toInt().let { if (writable) it else it and writes.inv() }
        return DeviceFile(DocumentsContract.buildDocumentUriUsingTree(tree, text(columns[0])),
            text(columns[1], "Untitled"), text(columns[2], "application/octet-stream"), number(columns[3], -1),
            number(columns[4]), flags)
    }

    suspend fun createFolder(parent: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        SharedStorageRules.validateName(name)
        if (parent.scheme == "file") {
            val folder = File(storage.file(parent), name)
            check(folder.mkdir()) { "Couldn't create this folder; the name may already exist" }
            return@withContext Uri.fromFile(folder)
        }
        DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("This location couldn't create the folder")
    }
    suspend fun rename(file: DeviceFile, name: String): Uri = withContext(Dispatchers.IO) {
        SharedStorageRules.validateName(name)
        if (file.uri.scheme == "file") {
            val source = editableFile(file.uri)
            val destination = File(source.parentFile, name)
            check(!destination.exists()) { "A file with this name already exists" }
            Files.move(source.toPath(), destination.toPath())
            return@withContext Uri.fromFile(destination)
        }
        DocumentsContract.renameDocument(resolver, file.uri, name) ?: error("This location couldn't rename the file")
    }
    suspend fun delete(file: DeviceFile) = withContext(Dispatchers.IO) {
        if (file.uri.scheme == "file") {
            deleteLocal(editableFile(file.uri))
            return@withContext
        }
        check(DocumentsContract.deleteDocument(resolver, file.uri)) { "This location couldn't delete the file" }
    }

    private suspend fun deleteLocal(file: File) {
        currentCoroutineContext().ensureActive()
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            val children = file.listFiles() ?: error("Couldn't read ${file.name}")
            children.forEach { deleteLocal(it) }
        }
        check(file.delete()) { "Couldn't delete ${file.name}" }
    }

    /** Stream a copy across document providers. A failed copy leaves the source untouched. */
    suspend fun copy(file: DeviceFile, parent: Uri): Uri = withContext(Dispatchers.IO) {
        require(!file.directory && !file.virtual) { "Choose an ordinary file to copy" }
        val local = parent.scheme == "file"
        val destination = if (local) Uri.fromFile(SharedStorageRules.createCopy(storage.file(parent), file.name)) else
            DocumentsContract.createDocument(resolver, parent, file.mime, file.name) ?: error("This location couldn't create the copy")
        check(destination != file.uri && (destination.scheme != "content" || file.uri.scheme != "content" ||
            destination.authority != file.uri.authority || DocumentsContract.getDocumentId(destination) != DocumentsContract.getDocumentId(file.uri))) {
            "This provider returned the original file instead of a new copy"
        }
        try {
            (if (file.uri.scheme == "file") storage.file(file.uri).inputStream() else resolver.openInputStream(file.uri))?.use { input ->
                (if (local) storage.file(destination).outputStream() else resolver.openOutputStream(destination, "w"))?.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } ?: error("Couldn't write the copy")
            } ?: error("Couldn't read the original file")
            destination
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { if (local) File(requireNotNull(destination.path)).delete() else DocumentsContract.deleteDocument(resolver, destination) } }
            throw error
        }
    }
}
