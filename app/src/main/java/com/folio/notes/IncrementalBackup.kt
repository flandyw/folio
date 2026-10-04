package com.folio.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** SAF object store. Publish a restore point only after all its objects have landed. Pruning never
 * removes objects reachable from either retained restore point (including failed deletions). */
object IncrementalBackup {
    private const val DATA_FOLDER = "Folio backup data"
    private const val PREFIX = "Folio-auto-"
    private const val SUFFIX = ".folio-backup.zip"
    private const val MIME_ZIP = "application/zip"
    private val gate = Mutex()
    private val pointName = Regex("Folio-auto-([0-9]+)(?:-[a-zA-Z0-9-]+)?\\.folio-backup\\.zip")
    private data class Document(val uri: Uri, val name: String, val mime: String, val size: Long)

    private fun children(context: Context, parent: Uri): List<Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(columns[0]); val name = cursor.getColumnIndexOrThrow(columns[1])
            val mime = cursor.getColumnIndexOrThrow(columns[2]); val size = cursor.getColumnIndexOrThrow(columns[3])
            buildList { while (cursor.moveToNext()) add(Document(
                DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(id)), cursor.getString(name),
                cursor.getString(mime), if (cursor.isNull(size)) -1 else cursor.getLong(size))) }
        } ?: error("Couldn't read the backup folder")
    }

    private fun create(context: Context, parent: Uri, mime: String, name: String) =
        DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: error("Couldn't create $name")

    private fun directory(context: Context, parent: Uri, name: String, create: Boolean): Uri? {
        val matches = children(context, parent).filter { it.name == name && it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
        require(matches.size <= 1) { "Duplicate backup folders" }
        return matches.singleOrNull()?.uri ?: if (create) create(context, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) else null
    }

    private fun backupDirectory(context: Context, tree: Uri, create: Boolean): Uri? {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return if (name == LibraryAutoBackup.BACKUP_FOLDER) root else directory(context, root, LibraryAutoBackup.BACKUP_FOLDER, create)
    }

    private fun points(context: Context, folder: Uri) = children(context, folder)
        .filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR && pointName.matches(it.name) }
        .sortedByDescending { pointName.matchEntire(it.name)!!.groupValues[1].toLongOrNull() ?: 0L }

    private fun readPoint(context: Context, uri: Uri): LibraryBackup.Manifest =
        context.contentResolver.openInputStream(uri)?.use { source -> ZipInputStream(source.buffered(64 * 1024)).use { zip ->
            require(zip.nextEntry?.name == LibraryBackup.MANIFEST) { "Missing backup manifest" }
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                total += count
                require(total <= LibraryBackup.MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
                bytes.write(buffer, 0, count)
            }
            LibraryBackup.parseManifest(bytes.toString("UTF-8")).also { info ->
                if (info.native?.external == true) require(zip.nextEntry == null) { "Unexpected backup entry" }
            }
        } } ?: error("Couldn't open the restore point")

    suspend fun write(context: Context, tree: Uri, prepared: NativeBackup.Prepared) = withContext(Dispatchers.IO) {
        gate.withLock {
            val job = currentCoroutineContext()
            val folder = backupDirectory(context, tree, true)!!
            val data = directory(context, folder, DATA_FOLDER, true)!!
            val grouped = children(context, data).groupBy { it.name }
            require(grouped.all { (name, docs) -> !name.endsWith(NativeBackup.OBJECT_SUFFIX) || docs.size == 1 }) { "Duplicate backup data objects" }
            val existing = grouped.mapValues { it.value.first() }
            val snapshots = points(context, folder)
            val previous = snapshots.mapNotNull { point -> runCatching { readPoint(context, point.uri).native }.getOrNull() }
                .filter { it.external }
            val known = previous.flatMap { it.objects.entries }.associate { it.toPair() }
            fun present(hash: String, info: NativeBackup.ObjectInfo): Boolean {
                val doc = existing[NativeBackup.objectName(hash)] ?: return false
                return doc.mime != DocumentsContract.Document.MIME_TYPE_DIR && (doc.size < 0 || doc.size == info.storedBytes)
            }
            val currentKey = prepared.manifest.contentKey()
            val latest = snapshots.firstOrNull()?.let { runCatching { readPoint(context, it.uri).native }.getOrNull() }
            if (latest?.external == true && latest.contentKey() == currentKey && latest.objects.all { (hash, info) -> present(hash, info) }) {
                pruneSafely(context, folder, data, snapshots.first().uri, job::ensureActive)
                return@withLock
            }
            val objects = linkedMapOf<String, NativeBackup.ObjectInfo>()
            prepared.sources.forEach { (hash, source) ->
                job.ensureActive()
                val committed = known[hash]?.takeIf { it.size == source.info.size && present(hash, it) }
                if (committed != null) {
                    objects[hash] = committed
                } else {
                    // Only new/changed objects need a local compressed file. An uncommitted orphan
                    // (for example after process death) is rewritten, never trusted by its filename.
                    val encoded = File.createTempFile("backup-object-", ".tmp", context.cacheDir)
                    try {
                        encoded.outputStream().use { NativeBackup.writeObject(source, it, job::ensureActive) }
                        val info = source.info.copy(storedBytes = encoded.length())
                        existing[NativeBackup.objectName(hash)]?.let {
                            check(DocumentsContract.deleteDocument(context.contentResolver, it.uri)) { "Couldn't replace an incomplete backup object" }
                        }
                        val target = create(context, data, "application/octet-stream", NativeBackup.objectName(hash))
                        try {
                            context.contentResolver.openOutputStream(target, "wt")?.use { output ->
                                encoded.inputStream().use { NativeBackup.copy(it, output, encoded.length(), job::ensureActive) }
                            } ?: error("Couldn't write a backup object")
                            objects[hash] = info
                        } catch (e: Throwable) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                            throw e
                        }
                    } finally { encoded.delete() }
                }
            }
            job.ensureActive()
            val manifest = prepared.manifest.copy(objects = objects, external = true)
            require(hasObjects(manifest, children(context, data).groupBy { it.name })) { "Backup data did not finish writing" }
            val previousStamp = snapshots.maxOfOrNull { pointName.matchEntire(it.name)!!.groupValues[1].toLongOrNull() ?: 0L } ?: 0L
            check(previousStamp < Long.MAX_VALUE) { "Invalid restore point timestamp" }
            // Keep publication order monotonic even when the device clock moves backwards.
            val stamp = maxOf(System.currentTimeMillis(), previousStamp + 1)
            val target = create(context, folder, MIME_ZIP, "$PREFIX$stamp-${UUID.randomUUID()}$SUFFIX")
            try {
                context.contentResolver.openOutputStream(target, "wt")?.use { NativeBackup.writeRestorePoint(it, manifest) }
                    ?: error("Couldn't write the restore point")
                job.ensureActive()
                // Read the published descriptor back before deleting any prior restore point.
                require(readPoint(context, target).native == manifest) { "Automatic backup verification failed" }
            } catch (e: Throwable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                throw e
            }
            pruneSafely(context, folder, data, target, job::ensureActive)
        }
    }

    private fun hasObjects(info: LibraryBackup.Manifest, available: Map<String, List<Document>>): Boolean =
        info.native?.takeIf { it.external }?.let { hasObjects(it, available) } ?: true

    private fun hasObjects(manifest: NativeBackup.Manifest, available: Map<String, List<Document>>): Boolean =
        manifest.objects.all { (hash, objectInfo) ->
            val doc = available[NativeBackup.objectName(hash)]?.singleOrNull()
            doc != null && doc.mime != DocumentsContract.Document.MIME_TYPE_DIR && (doc.size < 0 || doc.size == objectInfo.storedBytes)
        }

    private fun pruneSafely(context: Context, folder: Uri, data: Uri, latest: Uri, check: () -> Unit) {
        try { prune(context, folder, data, latest, check) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* A completed backup remains usable if its provider refuses cleanup. */ }
    }

    private fun prune(context: Context, folder: Uri, data: Uri, latest: Uri, check: () -> Unit) {
        // Retain the new point plus the most recent previous point. Failure to prune is harmless;
        // leave every object's owner visible so a later pass can retry safely.
        val all = points(context, folder)
        val available = children(context, data).groupBy { it.name }
        val previous = all.firstOrNull { point ->
            check()
            point.uri != latest && runCatching { hasObjects(readPoint(context, point.uri), available) }.getOrDefault(false)
        }
        val keep = (listOf(latest) + listOfNotNull(previous?.uri)).toSet()
        all.filter { it.uri !in keep }.forEach { point ->
            check(); runCatching { DocumentsContract.deleteDocument(context.contentResolver, point.uri) }
        }
        val reachable = mutableSetOf<String>()
        for (point in points(context, folder)) {
            check()
            val info = runCatching { readPoint(context, point.uri) }.getOrNull() ?: return
            info.native?.takeIf { it.external }?.let { reachable += it.objects.keys }
        }
        children(context, data).forEach { doc ->
            check()
            val hash = doc.name.removeSuffix(NativeBackup.OBJECT_SUFFIX)
            if (doc.mime != DocumentsContract.Document.MIME_TYPE_DIR && doc.name == hash + NativeBackup.OBJECT_SUFFIX &&
                LibraryBackup.isHash(hash) && hash !in reachable) runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc.uri) }
        }
    }

    /** Folder selection grants access to both the restore point and its shared data on a new device. */
    suspend fun latest(context: Context, tree: Uri): Uri = withContext(Dispatchers.IO) {
        gate.withLock {
            val folder = backupDirectory(context, tree, false) ?: error("This folder has no Folio automatic backups")
            val data = directory(context, folder, DATA_FOLDER, false)
            val available = data?.let { children(context, it).groupBy { doc -> doc.name } }.orEmpty()
            val job = currentCoroutineContext()
            points(context, folder).firstOrNull { point ->
                job.ensureActive()
                runCatching { hasObjects(readPoint(context, point.uri), available) }.getOrDefault(false)
            }?.uri
                ?: error("This folder has no readable Folio automatic backups")
        }
    }

    suspend fun readObjects(context: Context, snapshot: Uri, chosenTree: Uri?, manifest: NativeBackup.Manifest,
        staging: File, check: () -> Unit): Map<String, File> = gate.withLock {
        val trees = (listOfNotNull(chosenTree, LibraryAutoBackup.configuredTreeUri(context)) +
            context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }.map { it.uri }).distinct()
        val snapshotId = DocumentsContract.getDocumentId(snapshot)
        val folder = trees.asSequence().filter { it.authority == snapshot.authority }.mapNotNull { tree ->
            runCatching { backupDirectory(context, tree, false)?.takeIf { dir ->
                points(context, dir).any { DocumentsContract.getDocumentId(it.uri) == snapshotId }
            } }.getOrNull()
        }.firstOrNull() ?: error("Choose Restore from backup folder to give Folio access to this restore point's shared data")
        val data = directory(context, folder, DATA_FOLDER, false) ?: error("Backup data folder is missing")
        val available = children(context, data).groupBy { it.name }
        manifest.objects.mapValues { (hash, info) ->
            check()
            val doc = available[NativeBackup.objectName(hash)]?.singleOrNull()
                ?: error("Backup is missing a data object")
            require((doc.size < 0 || doc.size == info.storedBytes)) { "Backup data object is incomplete" }
            val target = File(staging, hash)
            context.contentResolver.openInputStream(doc.uri)?.use { NativeBackup.readObject(it, target, hash, info, encoded = true, check = check) }
                ?: error("Couldn't read backup data")
            target
        }
    }
}
