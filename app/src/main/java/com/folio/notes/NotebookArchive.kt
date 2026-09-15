package com.folio.notes

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A notebook, its optional imported PDF and its placed images, as read back out of a `.folio` archive. */
data class ArchivedNotebook(val note: Notebook, val pdf: ByteArray?, val images: Map<String, ByteArray> = emptyMap())

/**
 * A self-contained `.folio` archive: the notebook JSON, plus its imported source PDF when there is
 * one. Deliberately free of Android types so packing and unpacking stay JVM-testable; the repository
 * supplies the bytes and owns where they land on disk.
 */
object NotebookArchive {
    const val ENTRY_NOTE = "note.json"
    const val ENTRY_PDF = "source.pdf"
    const val ENTRY_IMAGE_PREFIX = "images/"

    /** A bound so a malformed archive cannot make the app pull an unbounded PDF into memory. */
    const val MAX_PDF_BYTES = 256L * 1024 * 1024
    /** The same bound for a single placed image; phone photos are far smaller than this. */
    const val MAX_IMAGE_BYTES = 48L * 1024 * 1024

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
     * Reads an archive written by [write]. Entries other than the two known names are ignored, and a
     * missing notebook is an error rather than an empty notebook, so an unrelated zip cannot import
     * as a blank note by accident.
     */
    fun read(input: InputStream): ArchivedNotebook {
        var json: String? = null
        var pdf: ByteArray? = null
        val images = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == ENTRY_NOTE -> json = zip.readBytes().toString(Charsets.UTF_8)
                    entry.name == ENTRY_PDF -> {
                        val bytes = zip.readBytes()
                        require(bytes.size.toLong() <= MAX_PDF_BYTES) { "This backup's PDF is too large to import" }
                        pdf = bytes
                    }
                    entry.name.startsWith(ENTRY_IMAGE_PREFIX) && entry.name.length > ENTRY_IMAGE_PREFIX.length -> {
                        val id = entry.name.removePrefix(ENTRY_IMAGE_PREFIX).take(64)
                        if (imageIdPattern.matches(id)) {
                            if (entry.size > MAX_IMAGE_BYTES) error("This backup's image is too large to import")
                            val bytes = zip.readBytes()
                            require(bytes.size.toLong() <= MAX_IMAGE_BYTES) { "This backup's image is too large to import" }
                            images[id] = bytes
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val note = json?.let(NoteCodec::decode) ?: error("This file is not a Folio backup")
        return ArchivedNotebook(note, pdf, images)
    }
}
