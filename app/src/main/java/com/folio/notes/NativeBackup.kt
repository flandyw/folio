package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Native, content-addressed backups. Objects are raw files in portable ZIPs and gzip/raw files
 * beside automatic restore points. Neither path expands ink into the portable JSON representation. */
object NativeBackup {
    const val VERSION = 3
    const val OBJECT_PREFIX = "objects/"
    const val OBJECT_SUFFIX = ".fbo"
    const val MAX_OBJECTS = 250_000
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024
    private val pathPattern = Regex("(?:note\\.json|source\\.pdf|pages/[a-zA-Z0-9-]+\\.(?:fps|fjl)|images/[a-zA-Z0-9-]+\\.jpg)")

    data class ObjectInfo(val size: Long, val compressed: Boolean, val storedBytes: Long = 0)
    data class Note(val id: String, val files: Map<String, String>)
    data class Manifest(
        val folders: List<Folder>, val notes: List<Note>, val objects: Map<String, ObjectInfo>,
        val external: Boolean = false, val created: Long = System.currentTimeMillis()
    ) {
        /** Stable across timestamps, portable/incremental encoding, and object insertion order. */
        fun contentKey(): String = sha256(encode(copy(external = false, created = 0, notes = notes.sortedBy { it.id },
            objects = objects.mapValues { (_, info) -> info.copy(compressed = false, storedBytes = 0) })).toByteArray())
    }
    data class Source(val file: File, val info: ObjectInfo, val crc: Long)
    data class Prepared(val manifest: Manifest, val sources: Map<String, Source>)

    fun validPath(path: String) = path.length <= 160 && pathPattern.matches(path)
    fun objectName(hash: String): String {
        require(LibraryBackup.isHash(hash)) { "Invalid backup object" }
        return "$hash$OBJECT_SUFFIX"
    }

    fun encode(manifest: Manifest): String = JSONObject()
        .put("format", "folio-library").put("version", VERSION)
        .put("external", manifest.external).put("created", manifest.created)
        .put("folders", JSONArray().apply {
            manifest.folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        .put("notebooks", JSONArray().apply {
            manifest.notes.forEach { note -> put(JSONObject().put("id", note.id)
                .put("files", JSONObject().apply { note.files.toSortedMap().forEach { (path, hash) -> put(path, hash) } })) }
        })
        .put("objects", JSONObject().apply {
            manifest.objects.toSortedMap().forEach { (hash, info) -> put(hash, JSONObject()
                .put("size", info.size).put("compressed", info.compressed).put("storedBytes", info.storedBytes)) }
        }).toString()

    fun decode(json: String): Manifest {
        require(json.toByteArray(Charsets.UTF_8).size.toLong() <= LibraryBackup.MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
        val root = JSONObject(json)
        require(root.getString("format") == "folio-library" && root.getInt("version") == VERSION) { "Unsupported Folio library backup" }
        val folderArray = root.getJSONArray("folders")
        val noteArray = root.getJSONArray("notebooks")
        require(folderArray.length() <= LibraryBackup.MAX_NOTEBOOKS && noteArray.length() <= LibraryBackup.MAX_NOTEBOOKS) { "Backup is too large" }
        val folders = (0 until folderArray.length()).map { folderArray.getJSONObject(it).let { f -> Folder(f.getString("id"), f.getString("name")) } }
        var fileCount = 0
        val notes = (0 until noteArray.length()).map { index ->
            val note = noteArray.getJSONObject(index)
            val filesJson = note.getJSONObject("files")
            fileCount += filesJson.length()
            require(fileCount <= MAX_OBJECTS) { "Backup has too many files" }
            val files = filesJson.keys().asSequence().associateWith { filesJson.getString(it) }
            require("note.json" in files && files.all { (path, hash) -> validPath(path) && LibraryBackup.isHash(hash) }) { "Invalid backup file reference" }
            Note(note.getString("id"), files)
        }
        require((folders.map { it.id } + notes.map { it.id }).all { it.length <= 64 && LibraryBackup.validId(it) } &&
            folders.map { it.id }.distinct().size == folders.size && notes.map { it.id }.distinct().size == notes.size) { "Invalid or duplicate backup identifier" }
        val external = root.getBoolean("external")
        val objectsJson = root.getJSONObject("objects")
        require(objectsJson.length() <= MAX_OBJECTS) { "Backup has too many objects" }
        var total = 0L
        val objects = objectsJson.keys().asSequence().associateWith { hash ->
            require(LibraryBackup.isHash(hash)) { "Invalid backup object" }
            val obj = objectsJson.getJSONObject(hash)
            val info = ObjectInfo(obj.getLong("size"), obj.getBoolean("compressed"), obj.optLong("storedBytes", 0))
            require(info.size in 0..LibraryBackup.MAX_ENTRY_BYTES &&
                (!external || (info.storedBytes in 0..LibraryBackup.MAX_ENTRY_BYTES + 1024 * 1024 &&
                    (if (info.compressed) info.storedBytes > 0 else info.storedBytes == info.size)))) { "Backup object is too large" }
            total += info.size
            require(total <= MAX_TOTAL_BYTES) { "Backup is too large" }
            info
        }
        require(objects.keys == notes.flatMap { it.files.values }.toSet()) { "Backup object index does not match its manifest" }
        // Include deduplicated copies in the bound too: tiny shared objects must not expand without limit.
        val expanded = notes.sumOf { note -> note.files.values.sumOf { objects.getValue(it).size } }
        require(expanded <= MAX_TOTAL_BYTES) { "Restored library is too large" }
        notes.forEach { note -> note.files.forEach { (path, hash) ->
            val limit = when {
                path == "note.json" -> NotebookArchive.MAX_NOTE_BYTES
                path == "source.pdf" -> NotebookArchive.MAX_PDF_BYTES
                path.startsWith("images/") -> NotebookArchive.MAX_IMAGE_BYTES
                else -> LibraryBackup.MAX_ENTRY_BYTES
            }
            require(objects.getValue(hash).size <= limit) { "Backup file is too large" }
        } }
        return Manifest(folders, notes, objects, external, root.getLong("created"))
    }

    /** Hash and checksum together, in one streaming pass. No entire file is held in memory. */
    fun inspect(file: File, compressed: Boolean, check: () -> Unit = {}): Pair<String, Source> {
        require(file.isFile && file.length() <= LibraryBackup.MAX_ENTRY_BYTES) { "Backup file is too large" }
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val snapshot = file.extension == "fps"
        val length = file.length()
        require(!snapshot || length >= 9) { "Invalid backup page snapshot" }
        val bodyCrc = if (snapshot) CRC32() else null
        val tail = ByteArray(4)
        var size = 0L
        file.inputStream().buffered(BUFFER).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (snapshot) {
                    if (size == 0L) require(PageSnapshotBinary.peek(buffer, count) != null) { "Invalid backup page snapshot" }
                    val bodyBytes = (length - 4 - size).coerceIn(0L, count.toLong()).toInt()
                    bodyCrc!!.update(buffer, 0, bodyBytes)
                    for (index in bodyBytes until count) {
                        val at = size + index - (length - 4)
                        require(at in 0L..3L) { "Backup snapshot changed while being read" }
                        tail[at.toInt()] = buffer[index]
                    }
                }
                size += count
                require(size <= LibraryBackup.MAX_ENTRY_BYTES) { "Backup file is too large" }
                digest.update(buffer, 0, count); crc.update(buffer, 0, count)
            }
        }
        require(size == length) { "Backup file changed while being read" }
        if (snapshot) {
            val stored = (tail[0].toInt() and 0xff) or ((tail[1].toInt() and 0xff) shl 8) or
                ((tail[2].toInt() and 0xff) shl 16) or ((tail[3].toInt() and 0xff) shl 24)
            require(bodyCrc!!.value.toInt() == stored) { "Backup page snapshot checksum failed" }
        }
        return hex(digest.digest()) to Source(file, ObjectInfo(size, compressed), crc.value)
    }

    fun writePortable(output: OutputStream, prepared: Prepared, check: () -> Unit = {}, onProgress: (String) -> Unit = {}) {
        val manifest = prepared.manifest.copy(external = false)
        // Validate what we write with exactly the same limits used by the reader.
        val json = encode(manifest).also(::decode)
        require(prepared.sources.keys == manifest.objects.keys && prepared.sources.all { (hash, source) -> source.info == manifest.objects[hash] }) {
            "Backup sources do not match the manifest"
        }
        ZipOutputStream(output.buffered(BUFFER)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(LibraryBackup.MANIFEST)); zip.write(json.toByteArray()); zip.closeEntry()
            val totalBytes = prepared.sources.values.sumOf { it.info.size }
            var written = 0L
            var reported = -1L
            prepared.sources.entries.forEachIndexed { index, (hash, source) ->
                check()
                fun report(bytes: Long) {
                    val current = written + bytes
                    if (bytes == 0L || current - reported >= 1024 * 1024 || bytes == source.info.size) {
                        onProgress("Writing file ${index + 1} of ${prepared.sources.size} · ${BackupProgress.bytes(current)} of ${BackupProgress.bytes(totalBytes)} of source data")
                        reported = current
                    }
                }
                report(0)
                zip.putNextEntry(ZipEntry(OBJECT_PREFIX + hash).apply {
                    if (!source.info.compressed) {
                        method = ZipEntry.STORED; size = source.info.size; compressedSize = size; crc = source.crc
                    }
                })
                source.file.inputStream().use { copy(it, zip, source.info.size, check, ::report) }
                zip.closeEntry()
                written += source.info.size
            }
            onProgress("Finishing library backup…")
        }
    }

    fun writeRestorePoint(output: OutputStream, manifest: Manifest) {
        require(manifest.external)
        val bytes = encode(manifest).also(::decode).toByteArray()
        ZipOutputStream(output.buffered(BUFFER)).use { zip ->
            zip.putNextEntry(ZipEntry(LibraryBackup.MANIFEST)); zip.write(bytes); zip.closeEntry()
        }
    }

    fun writeObject(source: Source, output: OutputStream, check: () -> Unit = {}) {
        if (source.info.compressed) {
            object : GZIPOutputStream(output.buffered(BUFFER), BUFFER) {
                init { def.setLevel(Deflater.BEST_SPEED) }
            }.use { encoded -> source.file.inputStream().use { copy(it, encoded, source.info.size, check) } }
        } else output.buffered(BUFFER).use { out -> source.file.inputStream().use { copy(it, out, source.info.size, check) } }
    }

    /** Extracts one bounded object and verifies its content hash before it can be installed. */
    fun readObject(input: InputStream, destination: File, hash: String, info: ObjectInfo,
        encoded: Boolean = false, check: () -> Unit = {}) {
        val digest = MessageDigest.getInstance("SHA-256")
        var encodedBytes = 0L
        val bounded = if (encoded) object : java.io.FilterInputStream(input) {
            private fun record(count: Int): Int {
                if (count > 0) encodedBytes += count
                require(encodedBytes <= info.storedBytes) { "Encoded backup object is too large" }
                return count
            }
            override fun read(): Int = `in`.read().also { if (it >= 0) record(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = record(`in`.read(buffer, offset, length))
        } else input
        val decoded = if (encoded && info.compressed) GZIPInputStream(bounded.buffered(BUFFER), BUFFER) else bounded
        try {
            destination.outputStream().use { stream ->
                val output = stream.buffered(BUFFER)
                val buffer = ByteArray(BUFFER)
                var size = 0L
                while (true) {
                    check()
                    val count = decoded.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= info.size) { "Backup object is too large" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                require(size == info.size && hex(digest.digest()) == hash) { "Backup object checksum failed" }
                require(!encoded || encodedBytes == info.storedBytes) { "Encoded backup object length does not match its manifest" }
                output.flush()
                stream.fd.sync()
            }
        } finally { if (decoded !== input) decoded.close() }
    }

    /** Portable backups must contain precisely their object set; restore points have only a manifest. */
    fun readEntries(zip: ZipInputStream, manifest: Manifest, directory: File, check: () -> Unit = {}): Map<String, File> {
        val files = linkedMapOf<String, File>()
        while (true) {
            check()
            val entry = zip.nextEntry ?: break
            val hash = entry.name.removePrefix(OBJECT_PREFIX)
            require(!manifest.external && entry.name == OBJECT_PREFIX + hash &&
                hash in manifest.objects && hash !in files && !entry.isDirectory) { "Unexpected or duplicate backup object" }
            val file = File(directory, hash)
            readObject(zip, file, hash, manifest.objects.getValue(hash), check = check)
            zip.closeEntry(); files[hash] = file
        }
        require(manifest.external || files.keys == manifest.objects.keys) { "Backup is missing an object" }
        return files
    }

    fun copy(input: InputStream, output: OutputStream, max: Long, check: () -> Unit = {}, onBytes: (Long) -> Unit = {}) {
        val buffer = ByteArray(BUFFER)
        var size = 0L
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= max) { "Backup entry is too large" }
            output.write(buffer, 0, count)
            onBytes(size)
        }
        require(size == max) { "Backup file changed while being read" }
    }

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private const val BUFFER = 64 * 1024
}
