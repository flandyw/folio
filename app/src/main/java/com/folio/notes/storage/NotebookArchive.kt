package com.folio.notes.storage

import com.folio.notes.model.NoteCodec
import com.folio.notes.model.Notebook
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Assets stay in the caller's staging directory until the notebook has been installed. */
data class ArchivedNotebook(val note: Notebook, val pdf: File?, val images: Map<String, File> = emptyMap())

/**
 * A self-contained `.folio` archive: the notebook JSON, plus its imported source PDF when there is
 * one. Deliberately free of Android types so packing and unpacking stay JVM-testable; the repository
 * owns where extracted files land on disk.
 */
object NotebookArchive {
    const val ENTRY_NOTE = "note.json"
    const val ENTRY_PDF = "source.pdf"
    const val ENTRY_IMAGE_PREFIX = "images/"

    /** Per-asset bounds apply to decompressed bytes, regardless of ZIP size headers. */
    const val MAX_PDF_BYTES = 256L * 1024 * 1024
    /** The same bound for a single placed image; phone photos are far smaller than this. */
    const val MAX_IMAGE_BYTES = 48L * 1024 * 1024
    const val MAX_NOTE_BYTES = 64L * 1024 * 1024
    /** Includes ignored entries so neither assets nor unknown files can expand without limit. */
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRIES = 10_000

    private val imageIdPattern = Regex("[a-zA-Z0-9-]+")

    fun write(note: Notebook, pdf: ByteArray?, output: OutputStream) =
        write(note, pdf, emptyMap(), output)

    fun write(note: Notebook, pdf: ByteArray?, images: Map<String, ByteArray>, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_NOTE))
            val jsonBytes = NoteCodec.encode(note).toByteArray(Charsets.UTF_8)
            zip.write(jsonBytes)
            zip.closeEntry()
            if (pdf != null) {
                zip.putNextEntry(ZipEntry(ENTRY_PDF))
                // Chunked writes avoid one giant native call for large PDFs.
                var offset = 0
                while (offset < pdf.size) {
                    val chunk = minOf(64 * 1024, pdf.size - offset)
                    zip.write(pdf, offset, chunk)
                    offset += chunk
                }
                zip.closeEntry()
            }
            images.forEach { (id, bytes) ->
                zip.putNextEntry(ZipEntry(ENTRY_IMAGE_PREFIX + id))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * Extracts into a new [directory], which the caller owns on success. Unknown entries are ignored
     * but count toward the decompression budget. Failure removes all partially extracted files.
     */
    fun read(input: InputStream, directory: File, checkActive: () -> Unit = {}): ArchivedNotebook {
        require(!directory.exists() && directory.mkdirs()) { "Couldn't stage the notebook" }
        try {
            var json: File? = null
            var pdf: File? = null
            val images = mutableMapOf<String, File>()
            ZipInputStream(input).use { zip ->
                var total = 0L
                var entries = 0
                val buffer = ByteArray(64 * 1024)
                fun copyEntry(output: OutputStream?, max: Long) {
                    var size = 0L
                    while (true) {
                        checkActive()
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size += n
                        total += n
                        require(size <= max) { "Backup entry is too large" }
                        require(total <= MAX_ARCHIVE_BYTES) { "Backup is too large" }
                        output?.write(buffer, 0, n)
                    }
                }
                while (true) {
                    checkActive()
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_ENTRIES) { "Backup has too many entries" }
                    when {
                        entry.name == ENTRY_NOTE -> {
                            require(json == null) { "Duplicate notebook entry" }
                            json = File(directory, ENTRY_NOTE).also { file ->
                                file.outputStream().use { copyEntry(it, MAX_NOTE_BYTES) }
                            }
                        }
                        entry.name == ENTRY_PDF -> {
                            require(pdf == null) { "Duplicate PDF entry" }
                            pdf = File(directory, ENTRY_PDF).also { file ->
                                file.outputStream().use { copyEntry(it, MAX_PDF_BYTES) }
                            }
                        }
                        entry.name.startsWith(ENTRY_IMAGE_PREFIX) && imageIdPattern.matches(entry.name.removePrefix(ENTRY_IMAGE_PREFIX)) -> {
                            val id = entry.name.removePrefix(ENTRY_IMAGE_PREFIX)
                            require(id.length <= 64 && id !in images) { "Invalid image entry" }
                            val file = File(directory, ENTRY_IMAGE_PREFIX + id).apply { parentFile?.mkdirs() }
                            file.outputStream().use { copyEntry(it, MAX_IMAGE_BYTES) }
                            images[id] = file
                        }
                        else -> copyEntry(null, MAX_ARCHIVE_BYTES)
                    }
                    zip.closeEntry()
                }
            }
            checkActive()
            val note = json?.let { NoteCodec.decode(it.readText(Charsets.UTF_8)) } ?: error("This file is not a Folio backup")
            checkActive()
            return ArchivedNotebook(note, pdf, images)
        } catch (e: Throwable) {
            directory.deleteRecursively()
            throw e
        }
    }
}
