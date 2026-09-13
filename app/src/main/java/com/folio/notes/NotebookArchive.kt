package com.folio.notes

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A notebook and its optional imported PDF, as read back out of a `.folio` archive. */
data class ArchivedNotebook(val note: Notebook, val pdf: ByteArray?)

/**
 * A self-contained `.folio` archive: the notebook JSON, plus its imported source PDF when there is
 * one. Deliberately free of Android types so packing and unpacking stay JVM-testable; the repository
 * supplies the bytes and owns where they land on disk.
 */
object NotebookArchive {
    const val ENTRY_NOTE = "note.json"
    const val ENTRY_PDF = "source.pdf"

    /** A bound so a malformed archive cannot make the app pull an unbounded PDF into memory. */
    const val MAX_PDF_BYTES = 256L * 1024 * 1024

    fun write(note: Notebook, pdf: ByteArray?, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_NOTE))
            zip.write(NoteCodec.encode(note).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (pdf != null) {
                zip.putNextEntry(ZipEntry(ENTRY_PDF))
                zip.write(pdf)
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
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    ENTRY_NOTE -> json = zip.readBytes().toString(Charsets.UTF_8)
                    ENTRY_PDF -> {
                        val bytes = zip.readBytes()
                        require(bytes.size.toLong() <= MAX_PDF_BYTES) { "This backup's PDF is too large to import" }
                        pdf = bytes
                    }
                }
                zip.closeEntry()
            }
        }
        val note = json?.let(NoteCodec::decode) ?: error("This file is not a Folio backup")
        return ArchivedNotebook(note, pdf)
    }
}
