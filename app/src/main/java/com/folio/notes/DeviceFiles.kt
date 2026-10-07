package com.folio.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

/** All document-provider work stays off the UI thread; URIs remain inside their granted tree. */
internal class DeviceFiles(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS)

    fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    suspend fun info(uri: Uri): DeviceFile = withContext(Dispatchers.IO) {
        val writable = canWrite(uri)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "This folder is no longer available" }
            read(uri, cursor, writable)
        } ?: error("This folder is no longer available")
    }

    suspend fun children(parent: Uri): List<DeviceFile> = withContext(Dispatchers.IO) {
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
        DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("This location couldn't create the folder")
    }
    suspend fun rename(file: DeviceFile, name: String): Uri = withContext(Dispatchers.IO) {
        DocumentsContract.renameDocument(resolver, file.uri, name) ?: error("This location couldn't rename the file")
    }
    suspend fun delete(file: DeviceFile) = withContext(Dispatchers.IO) {
        check(DocumentsContract.deleteDocument(resolver, file.uri)) { "This location couldn't delete the file" }
    }

    /** Stream a copy across document providers. A failed copy leaves the source untouched. */
    suspend fun copy(file: DeviceFile, parent: Uri): Uri = withContext(Dispatchers.IO) {
        require(!file.directory && !file.virtual) { "Choose an ordinary file to copy" }
        val destination = DocumentsContract.createDocument(resolver, parent, file.mime, file.name)
            ?: error("This location couldn't create the copy")
        check(destination.authority != file.uri.authority || DocumentsContract.getDocumentId(destination) != DocumentsContract.getDocumentId(file.uri)) { "This provider returned the original file instead of a new copy" }
        try {
            resolver.openInputStream(file.uri)?.use { input ->
                resolver.openOutputStream(destination, "w")?.use { output ->
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
            withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(resolver, destination) } }
            throw error
        }
    }
}
