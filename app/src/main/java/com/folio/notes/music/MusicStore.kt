package com.folio.notes.music

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID

/** Entirely separate from NoteRepository, its index, sync and notebook backups. */
internal class MusicStore(private val context: Context) {
    private val root = File(context.filesDir, "music").apply { mkdirs() }
    private val pending = File(root, "pending").apply { mkdirs() }
    private val index = AtomicFile(File(root, "library.json"))
    fun pdf(id: String): File {
        require(validMusicId(id)) { "Invalid score ID" }
        val saved = File(root, "$id.pdf")
        return if (saved.exists()) saved else File(pending, "$id.pdf")
    }

    fun load(): MusicLibrary {
        val library = if (!index.baseFile.exists() && !File(root, "library.json.bak").exists()) MusicLibrary()
            else MusicCodec.decode(index.openRead().bufferedReader().use { it.readText() })
        // Only scratch imports are disposable. Never clean saved PDFs on an index failure.
        pending.listFiles()?.forEach { it.delete() }
        return library
    }

    fun save(library: MusicLibrary) {
        val json = MusicCodec.encode(library)
        val stream = index.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch (e: Throwable) { index.failWrite(stream); throw e }
    }

    /** Copy first, validate locally, then publish metadata. Never retain a provider URI. */
    fun import(uri: Uri): MusicScore {
        val id = UUID.randomUUID().toString()
        val staging = File(pending, "$id.part")
        val target = pdf(id)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            } ?: error("The selected PDF could not be read")
            val count = ParcelFileDescriptor.open(staging, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount.also { require(it > 0) { "PDF has no pages" } } }
            }
            val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Untitled score"
            check(staging.renameTo(target)) { "Could not store PDF" }
            return MusicScore(id, title, count)
        } finally { staging.delete() }
    }

    fun review(score: MusicScore, existing: Boolean = false): MusicImportReview {
        return try {
            PDFBoxResourceLoader.init(context)
            val texts = PDDocument.load(pdf(score.id), MemoryUsageSetting.setupMixed(8L * 1024 * 1024).setTempDir(context.cacheDir)).use { doc ->
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                (1..doc.numberOfPages).map { page ->
                    stripper.startPage = page; stripper.endPage = page
                    stripper.getText(doc).take(8000)
                }
            }
            val suggestions = MusicParts.suggest(texts)
            val notice = when {
                texts.all { it.isBlank() } -> "No embedded text found. This may be a scan; use the preview to choose your pages manually."
                suggestions.isEmpty() -> "No clear part headings found. Choose your pages manually using the preview."
                else -> "Suggested from instrument headings. Check the page boundaries: unlabelled pages may belong to the preceding part."
            }
            MusicImportReview(score, suggestions, notice, existing)
        } catch (_: Exception) {
            MusicImportReview(score, emptyList(), "Automatic detection was unavailable. You can still preview and select pages manually.", existing)
        }
    }

    /** Creates independent vector PDFs. The source remains open until each output is saved. */
    fun extract(source: MusicScore, requests: List<MusicPartRequest>): List<MusicScore> {
        require(requests.isNotEmpty()) { "Select at least one part" }
        val results = requests.map { MusicParts.extracted(source, UUID.randomUUID().toString(), it) }
        PDFBoxResourceLoader.init(context)
        try {
            PDDocument.load(pdf(source.id), MemoryUsageSetting.setupMixed(8L * 1024 * 1024).setTempDir(context.cacheDir)).use { original ->
                require(original.numberOfPages == source.pages) { "The source PDF page count changed" }
                requests.zip(results).forEach { (request, result) ->
                    val staging = File(root, "${result.id}.part")
                    try {
                        PDDocument().use { out ->
                            request.pages.forEach { index ->
                                val page = original.getPage(index)
                                out.importPage(page).apply {
                                    // importPage ignores inherited resources; preserve fonts and notation.
                                    resources = page.resources
                                    // Links/widgets can retain references to pages outside this part.
                                    annotations = annotations.filterNot { it.subtype == "Link" || it.subtype == "Widget" }.onEach { it.page = this }
                                }
                            }
                            out.save(staging)
                        }
                        java.io.FileOutputStream(staging, true).use { it.fd.sync() }
                        ParcelFileDescriptor.open(staging, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { check(it.pageCount == request.pages.size) { "Extracted part could not be verified" } }
                        }
                        check(staging.renameTo(File(root, "${result.id}.pdf"))) { "Could not save extracted part" }
                    } finally { staging.delete() }
                }
            }
            return results
        } catch (e: Throwable) { results.forEach { delete(it.id) }; throw e }
    }

    /**
     * A second copy of a score, PDF and all, so a cleaned-up or shortened edition can sit beside the
     * original. The index entry starts unopened, so the copy does not steal "Continue playing".
     */
    fun duplicate(score: MusicScore): MusicScore {
        val id = UUID.randomUUID().toString()
        val target = File(root, "$id.pdf")
        try {
            pdf(score.id).inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
        } catch (e: Throwable) { target.delete(); throw e }
        return score.copy(id = id, title = "${score.title} (copy)", opened = 0, seed = MusicSeed(score.id, (0 until score.pages).toList()))
    }

    fun keepWhole(id: String) {
        val source = pdf(id)
        val saved = File(root, "$id.pdf")
        if (source != saved) check(source.renameTo(saved)) { "Could not keep PDF" }
    }

    fun delete(id: String) { pdf(id).delete() }
    fun export(id: String, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output -> pdf(id).inputStream().use { it.copyTo(output) } }
            ?: error("Could not open export destination")
    }
}
