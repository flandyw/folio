package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * On-device storage, one directory per notebook:
 *
 * ```
 * note.json        the notebook's fields plus one summary per page, deliberately holding no ink
 * pages/<id>.json  one page's ink and typed text, read only when that page is needed
 * source.pdf       an imported PDF, when the notebook has one
 * ```
 *
 * Keeping the index free of ink is what lets a library of long notebooks list instantly and a page's
 * content be fetched only when it is shown. A notebook saved by an older version, which kept every
 * page inline, is split into this layout the first time it is read.
 */
class NoteRepository(private val context: Context) {
    private val root = File(context.filesDir, "notebooks").apply { mkdirs() }
    private val lock = Mutex()
    /** A single `PdfRenderer` is not safe to use from two threads at once, so renders are serialized. */
    private val pdfLock = Mutex()
    private var pdfNoteId: String? = null
    private var pdfSession: PdfBackgrounds? = null

    /** Notebook and page ids are UUID-shaped, so anything else never reaches the file system. */
    private val idPattern = Regex("[a-zA-Z0-9-]+")
    private fun checked(value: String): String {
        require(value.matches(idPattern))
        return value
    }
    /** A notebook's directory, created because something is about to be written into it. */
    private fun directory(id: String): File = File(root, checked(id)).apply { mkdirs() }
    /** The same directory without touching the disk, for reads that must not create anything. */
    private fun storedDirectory(id: String) = File(root, checked(id))
    private fun noteFile(noteId: String) = File(directory(noteId), "note.json")
    private fun pageFile(noteId: String, pageId: String): File =
        File(File(directory(noteId), "pages").apply { mkdirs() }, "${checked(pageId)}.json")

    // ---- Reading -------------------------------------------------------------------------

    suspend fun load(): Triple<List<Notebook>, List<Folder>, List<ExamSet>> = withContext(Dispatchers.IO) {
        val notes = root.listFiles().orEmpty()
            .filter { it.isDirectory && (File(it, "note.json").exists() || File(it, "note.json.bak").exists()) }
            .map { readIndex(it) }
        val library = File(context.filesDir, "library.json")
        val folders = if (!library.exists() && !File(context.filesDir, "library.json.bak").exists()) emptyList() else {
            val array = JSONArray(AtomicFile(library).openRead().bufferedReader().use { it.readText() })
            (0 until array.length()).map { val f = array.getJSONObject(it); Folder(f.getString("id"), f.getString("name")) }
        }
        Triple(notes, folders, loadSets())
    }

    private fun loadSets(): List<ExamSet> {
        val file = File(context.filesDir, "exam-sets.json")
        if (!file.exists() && !File(context.filesDir, "exam-sets.json.bak").exists()) return emptyList()
        return ExamTagsCodec.decodeSets(AtomicFile(file).openRead().bufferedReader().use { JSONArray(it.readText()) })
    }

    /**
     * Reads a notebook's shape. A version-2 file is re-encoded onto the current index so exam tags
     * gain their fields, and a version-1 file, which held every page's ink inline, is split first:
     * every page file is written before the index is swapped, so an interruption leaves the original
     * file in place and the next open simply migrates again.
     */
    private fun readIndex(dir: File): Notebook {
        val raw = AtomicFile(File(dir, "note.json")).openRead().bufferedReader().use { it.readText() }
        when {
            NoteMetaCodec.isCurrent(raw) -> return NoteMetaCodec.decode(raw)
            NoteMetaCodec.isVersion3(raw) -> {
                val note = NoteMetaCodec.decodeVersion3(raw)
                atomicWrite(File(dir, "note.json"), NoteMetaCodec.encode(note))
                return note
            }
            NoteMetaCodec.isSplitIndex(raw) -> {
                val note = NoteMetaCodec.decodeSplit(raw)
                atomicWrite(File(dir, "note.json"), NoteMetaCodec.encode(note))
                return note
            }
        }
        val full = NoteCodec.decode(raw)
        val pages = File(dir, "pages").apply { mkdirs() }
        full.pages.forEach { atomicWrite(File(pages, "${it.id}.json"), NotePageCodec.encode(it)) }
        atomicWrite(File(dir, "note.json"), NoteMetaCodec.encode(full))
        return full.copy(pages = full.pages.map { it.asSummary() })
    }

    /**
     * Reads one page's ink and text, or an empty page when nothing has ever been written for it.
     * A read never creates a directory, so listing or drawing a notebook cannot leave one behind.
     */
    suspend fun loadPage(noteId: String, summary: NotePage): NotePage = withContext(Dispatchers.IO) {
        if (summary.loaded) return@withContext summary
        val file = File(File(storedDirectory(noteId), "pages"), "${checked(summary.id)}.json")
        if (!file.exists()) return@withContext summary.copy(loaded = true)
        NotePageCodec.decode(AtomicFile(file).openRead().bufferedReader().use { it.readText() }, summary)
    }

    /** Fetches every page still on disk; exports and backups need the whole notebook at once. */
    suspend fun loadPages(note: Notebook): Notebook = withContext(Dispatchers.IO) {
        note.copy(pages = note.pages.map { if (it.loaded) it else loadPage(note.id, it) })
    }

    // ---- Writing -------------------------------------------------------------------------

    /** Writes the index: notebook fields and one summary per page, never any ink. */
    suspend fun saveMeta(note: Notebook) = withContext(Dispatchers.IO) {
        lock.withLock { atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note)) }
    }

    /**
     * Writes one page's content together with the index that carries its revision, so a preview or
     * an export can never believe a page is newer or older than it really is.
     */
    suspend fun savePage(note: Notebook, page: NotePage) = withContext(Dispatchers.IO) {
        if (!page.loaded) return@withContext
        lock.withLock {
            atomicWrite(pageFile(note.id, page.id), NotePageCodec.encode(page))
            atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note))
        }
    }

    /** The index plus the content of every page held in memory; pages still on disk are left alone. */
    suspend fun saveAll(note: Notebook) = withContext(Dispatchers.IO) {
        lock.withLock {
            note.pages.filter { it.loaded }.forEach { atomicWrite(pageFile(note.id, it.id), NotePageCodec.encode(it)) }
            atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note))
        }
    }

    suspend fun deletePage(noteId: String, pageId: String) = withContext(Dispatchers.IO) {
        lock.withLock { pageFile(noteId, pageId).delete() }
        Unit
    }

    suspend fun saveFolders(folders: List<Folder>) = withContext(Dispatchers.IO) {
        lock.withLock { atomicWrite(File(context.filesDir, "library.json"), JSONArray().apply {
            folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }.toString()) }
    }

    /** Exam sets live beside the library file; the notebooks carry the membership link. */
    suspend fun saveSets(sets: List<ExamSet>) = withContext(Dispatchers.IO) {
        lock.withLock { atomicWrite(File(context.filesDir, "exam-sets.json"), ExamTagsCodec.encodeSets(sets).toString()) }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        closePdf(id)
        lock.withLock {
            val deleted = File(context.cacheDir, "deleted-$id-${System.currentTimeMillis()}")
            check(directory(id).renameTo(deleted)) { "Couldn't delete notebook" }
            // The library stops seeing the notebook atomically; interrupted cleanup is safe.
            deleted.deleteRecursively()
        }
        Unit
    }

    suspend fun importPdf(uri: Uri, folder: String?): Notebook = withContext(Dispatchers.IO) {
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).substringBeforeLast('.', it.getString(0)) else null
        } ?: "Imported document"
        val note = Notebook(title = title, folderId = folder, cover = 3)
        val dir = directory(note.id)
        try {
            val pdf = File(dir, "source.pdf")
            context.contentResolver.openInputStream(uri)?.use { input -> pdf.outputStream().use { input.copyTo(it) } }
                ?: error("This PDF could not be opened")
            val pages = PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                require(renderer.pageCount > 0) { "This PDF has no pages" }
                (0 until renderer.pageCount).map { index -> renderer.openPage(index).use {
                    NotePage(width = 840f, height = 840f * it.height / it.width, paper = Paper.PLAIN, pdfIndex = index)
                } }
            }
            note.copy(pages = pages).also { saveAll(it) }
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    /** Writes a portable `.folio` backup, expanding every page of a lazily stored notebook first. */
    suspend fun exportArchive(note: Notebook, uri: Uri) = withContext(Dispatchers.IO) {
        val full = loadPages(note)
        val pdf = File(storedDirectory(note.id), "source.pdf").takeIf { it.exists() }?.readBytes()
        context.contentResolver.openOutputStream(uri, "wt")?.use { NotebookArchive.write(full, pdf, it) }
            ?: error("Couldn't open the backup destination")
    }

    /**
     * Reads a `.folio` backup into a notebook with a fresh identity, so importing the same file twice
     * leaves the copy already on the device untouched.
     */
    suspend fun importArchive(uri: Uri, folder: String?): Notebook = withContext(Dispatchers.IO) {
        val archived = context.contentResolver.openInputStream(uri)?.use { NotebookArchive.read(it) }
            ?: error("This backup could not be opened")
        val note = archived.note.copy(id = UUID.randomUUID().toString(), folderId = folder, updated = System.currentTimeMillis())
        val dir = directory(note.id)
        try {
            archived.pdf?.let { File(dir, "source.pdf").writeBytes(it) }
            saveAll(note)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
        note
    }

    // ---- Imported PDF rendering ----------------------------------------------------------

    /**
     * An imported PDF held open for background rendering. Building a [PdfRenderer] parses the whole
     * document, so a multi-page export opens this once and reuses it instead of reparsing the file
     * for every page.
     */
    class PdfBackgrounds internal constructor(private val descriptor: ParcelFileDescriptor, private val renderer: PdfRenderer) : Closeable {
        /** The page's white background at roughly [targetWidth] px wide, or null for a native page. */
        fun render(page: NotePage, targetWidth: Int): Bitmap? {
            val index = page.pdfIndex ?: return null
            return renderer.openPage(index).use { pdfPage ->
                // Bound both dimensions for unusually tall or wide imported documents.
                val scale = minOf(targetWidth.toFloat() / pdfPage.width, 2800f / pdfPage.height)
                Bitmap.createBitmap((pdfPage.width * scale).toInt().coerceAtLeast(1),
                    (pdfPage.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE); pdfPage.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        override fun close() { renderer.close(); descriptor.close() }
    }

    /** Opens a notebook's imported PDF for repeated rendering, or null when it has no source file. */
    fun openPdf(noteId: String): PdfBackgrounds? {
        val file = File(storedDirectory(noteId), "source.pdf")
        if (!file.exists()) return null
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfBackgrounds(descriptor, PdfRenderer(descriptor)) }
        catch (e: Exception) { descriptor.close(); throw e }
    }

    /**
     * The renderer kept open for the notebook on screen, so scrolling through a PDF notebook does not
     * reparse the document for every page. Only reachable through [pdfBackground], which holds the
     * lock that makes one serialized renderer safe.
     */
    private fun sharedPdf(noteId: String): PdfBackgrounds? {
        pdfSession?.let { if (pdfNoteId == noteId) return it }
        pdfSession?.close(); pdfSession = null; pdfNoteId = null
        val session = openPdf(noteId) ?: return null
        pdfSession = session; pdfNoteId = noteId
        return session
    }

    /** Drops the open renderer, for the notebook named or for whichever one is open. */
    suspend fun closePdf(noteId: String? = null) = withContext(Dispatchers.IO) {
        pdfLock.withLock {
            if (noteId == null || pdfNoteId == noteId) { pdfSession?.close(); pdfSession = null; pdfNoteId = null }
        }
    }

    /** The page's white background, reusing the open renderer and serializing actual renders. */
    suspend fun pdfBackground(noteId: String, page: NotePage, targetWidth: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        if (page.pdfIndex == null) return@withContext null
        pdfLock.withLock { sharedPdf(noteId)?.render(page, targetWidth) }
    }

    private fun atomicWrite(file: File, value: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
}
