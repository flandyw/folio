package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Versioned, content-addressed container for a complete library. */
object LibraryBackup {
    const val MANIFEST = "manifest.json"
    const val NOTE_PREFIX = "notebooks/"
    const val ASSET_PREFIX = "assets/"
    const val VERSION = 2
    const val MAX_NOTEBOOKS = 10_000
    const val MAX_ASSETS = 100_000
    const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    private val idPattern = Regex("[a-zA-Z0-9-]+")
    private val hashPattern = Regex("[a-f0-9]{64}")

    data class AssetReference(val pdfHash: String?, val imageHashes: Map<String, String>)
    data class Manifest(
        val version: Int,
        val folders: List<Folder>,
        val notebookIds: List<String>,
        val assetsByNotebook: Map<String, AssetReference> = emptyMap()
    )
    data class NotebookPayload(
        val notebookId: String,
        val noteJsonFile: File,
        val pdfHash: String?,
        val imageHashes: Map<String, String>
    )

    fun manifestV2(folders: List<Folder>, notes: List<NotebookPayload>): String = JSONObject()
        .put("format", "folio-library")
        .put("version", VERSION)
        .put("folders", JSONArray().apply { folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .put("notebooks", JSONArray().apply {
            notes.forEach { payload ->
                put(JSONObject()
                    .put("id", payload.notebookId)
                    .put("pdf", payload.pdfHash)
                    .put("images", JSONObject().apply {
                        payload.imageHashes.toSortedMap().forEach { (id, hash) -> put(id, hash) }
                    }))
            }
        })
        .toString()

    /** Compatibility writer for v1 callers and the existing portable-archive tests. */
    fun manifest(folders: List<Folder>, notes: List<Notebook>): String = JSONObject()
        .put("format", "folio-library")
        .put("version", 1)
        .put("folders", JSONArray().apply { folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .put("notebooks", JSONArray().apply { notes.forEach { put(it.id) } })
        .toString()

    /** Reads both the original nested-archive format and the current deduplicated format. */
    fun parseManifest(json: String): Manifest {
        val obj = JSONObject(json)
        val version = obj.getInt("version")
        require(obj.getString("format") == "folio-library" && version in 1..VERSION) {
            "Unsupported Folio library backup"
        }
        val folderArray = obj.getJSONArray("folders")
        val noteArray = obj.getJSONArray("notebooks")
        require(noteArray.length() <= MAX_NOTEBOOKS && folderArray.length() <= MAX_NOTEBOOKS) { "Backup is too large" }
        val folders = (0 until folderArray.length()).map {
            folderArray.getJSONObject(it).let { f -> Folder(f.getString("id"), f.getString("name")) }
        }
        val references = linkedMapOf<String, AssetReference>()
        val ids = ArrayList<String>(noteArray.length())
        for (index in 0 until noteArray.length()) {
            val item = noteArray.get(index)
            if (version == 1) {
                ids += item as? String ?: error("Invalid notebook manifest entry")
            } else {
                val entry = item as? JSONObject ?: error("Invalid notebook manifest entry")
                val id = entry.getString("id")
                val pdf = entry.optString("pdf").takeIf { it.isNotEmpty() && it != "null" }
                val imagesJson = entry.getJSONObject("images")
                val images = buildMap {
                    val keys = imagesJson.keys()
                    while (keys.hasNext()) {
                        val imageId = keys.next()
                        put(imageId, imagesJson.getString(imageId))
                    }
                }
                references[id] = AssetReference(pdf, images)
                ids += id
            }
        }
        require((folders.map { it.id } + ids).all { idPattern.matches(it) }) { "Invalid backup identifier" }
        require(folders.map { it.id }.distinct().size == folders.size && ids.distinct().size == ids.size) {
            "Duplicate backup identifier"
        }
        if (version == 2) {
            require(references.values.all { ref ->
                (ref.pdfHash == null || hashPattern.matches(ref.pdfHash)) &&
                    ref.imageHashes.all { (id, hash) -> id.length <= 64 && idPattern.matches(id) && hashPattern.matches(hash) }
            }) { "Invalid backup asset reference" }
        }
        return Manifest(version, folders, ids, references)
    }

    fun entryName(id: String): String {
        require(idPattern.matches(id))
        return "$NOTE_PREFIX$id.folio" // v1 compatibility
    }

    fun noteEntryName(id: String): String {
        require(idPattern.matches(id))
        return "$NOTE_PREFIX$id/note.json"
    }

    fun assetEntryName(hash: String): String {
        require(hashPattern.matches(hash))
        return "$ASSET_PREFIX$hash"
    }

    suspend fun write(
        output: OutputStream,
        folders: List<Folder>,
        notes: List<NotebookPayload>,
        assetsByHash: Map<String, File>
    ) {
        require(notes.size <= MAX_NOTEBOOKS)
        require(notes.map { it.notebookId }.distinct().size == notes.size) { "Duplicate notebook identifier" }
        val referencedHashes = notes.flatMap { payload ->
            listOfNotNull(payload.pdfHash) + payload.imageHashes.values
        }.toSet()
        require(referencedHashes == assetsByHash.keys) { "Backup asset index does not match its manifest" }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            val manifest = manifestV2(folders, notes).toByteArray(Charsets.UTF_8)
            require(manifest.size.toLong() <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
            zip.write(manifest)
            zip.closeEntry()
            notes.forEach { payload ->
                require(payload.noteJsonFile.isFile && payload.noteJsonFile.length() <= NotebookArchive.MAX_NOTE_BYTES) {
                    "Backup notebook is too large"
                }
                zip.putNextEntry(ZipEntry(noteEntryName(payload.notebookId)))
                payload.noteJsonFile.inputStream().buffered().use { input -> input.copyTo(object : FilterOutputStream(zip) {
                    override fun close() { flush() }
                }) }
                zip.closeEntry()
            }
            // PDFs and JPEGs are already compressed. Store each unique byte sequence once and
            // avoid spending time trying to compress it again.
            referencedHashes.sorted().forEach { hash ->
                val file = assetsByHash.getValue(hash)
                require(file.isFile && file.length() <= MAX_ENTRY_BYTES) { "Backup asset is too large" }
                val crcValue = crc32(file)
                zip.putNextEntry(ZipEntry(assetEntryName(hash)).apply {
                    method = ZipEntry.STORED
                    size = file.length()
                    compressedSize = file.length()
                    crc = crcValue
                })
                file.inputStream().buffered().use { input -> input.copyTo(object : FilterOutputStream(zip) {
                    override fun close() { flush() }
                }) }
                zip.closeEntry()
            }
        }
    }

    /** Writes the original nested `.folio` layout so old library backup fixtures remain useful. */
    suspend fun write(
        output: OutputStream,
        folders: List<Folder>,
        notes: List<Notebook>,
        writeNote: suspend (Notebook, OutputStream) -> Unit
    ) {
        require(notes.size <= MAX_NOTEBOOKS)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(folders, notes).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            notes.forEach { note ->
                zip.putNextEntry(ZipEntry(entryName(note.id)))
                writeNote(note, object : FilterOutputStream(zip) { override fun close() { flush() } })
                zip.closeEntry()
            }
        }
    }

    fun isHash(value: String) = hashPattern.matches(value)
    fun validId(value: String) = idPattern.matches(value)

    private fun crc32(file: File): Long {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        return crc.value
    }
}
