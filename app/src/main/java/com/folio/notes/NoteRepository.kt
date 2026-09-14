package com.folio.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
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
    /** Extracted PDF text by notebook, so a search never reparses the document. */
    private val pdfTextCache = mutableMapOf<String, List<PdfPageText>>()
    /** Tappable PDF links and bookmarks by notebook, so navigation never reparses either. */
    private val pdfLinkCache = mutableMapOf<String, List<PdfLink>>()
    private val pdfOutlineCache = mutableMapOf<String, List<PdfOutlineEntry>>()
    private var pdfBoxReady = false

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
    private fun imageFile(noteId: String, imageId: String): File =
        File(File(directory(noteId), "images").apply { mkdirs() }, "${checked(imageId)}.jpg")
    private fun storedImageFile(noteId: String, imageId: String): File =
        File(File(storedDirectory(noteId), "images"), "${checked(imageId)}.jpg")

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
        pdfLock.withLock { pdfTextCache.remove(id); pdfLinkCache.remove(id); pdfOutlineCache.remove(id) }
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
        val images = full.pages.flatMap { it.images }.distinctBy { it.id }.mapNotNull { image ->
            storedImageFile(note.id, image.id).takeIf { it.exists() }?.readBytes()?.let { image.id to it }
        }.toMap()
        context.contentResolver.openOutputStream(uri, "wt")?.use { NotebookArchive.write(full, pdf, images, it) }
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
            archived.images.forEach { (id, bytes) ->
                if (id.matches(idPattern)) imageFile(note.id, id).writeBytes(bytes)
            }
            saveAll(note)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
        note
    }

    // ---- Placed images -------------------------------------------------------------------

    /** Stores one placed picture's bytes; the page JSON keeps only its placement. */
    suspend fun saveImage(noteId: String, imageId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        lock.withLock { imageFile(noteId, imageId).writeBytes(bytes) }
        Unit
    }

    /** One picture's raw bytes, or null when its file never landed on disk. */
    suspend fun loadImageBytes(noteId: String, imageId: String): ByteArray? = withContext(Dispatchers.IO) {
        storedImageFile(noteId, imageId).takeIf { it.exists() }?.readBytes()
    }

    /**
     * One picture decoded for drawing, downsampled so a phone photo never becomes a full-size
     * bitmap per page. Returns null when the file is missing or cannot be decoded.
     */
    suspend fun loadImage(noteId: String, imageId: String, maxSize: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        val file = storedImageFile(noteId, imageId)
        if (!file.exists()) return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSize) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) { null }
    }

    /** Every picture on [page] decoded for drawing; missing files are simply skipped. */
    suspend fun loadImages(noteId: String, page: NotePage): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        page.images.mapNotNull { image ->
            loadImage(noteId, image.id)?.let { image.id to it }
        }.toMap()
    }

    // ---- Imported PDF text -----------------------------------------------------------------

    /**
     * Every page's embedded text in notebook order, extracted once and remembered while the app
     * runs. A scanned or locked PDF simply yields empty strings, which the search panel reports
     * as having no searchable text rather than failing the search.
     */
    suspend fun pdfPageTexts(noteId: String): List<PdfPageText> = withContext(Dispatchers.IO) {
        pdfLock.withLock {
            pdfTextCache[noteId]?.let { return@withLock it }
            val file = File(storedDirectory(noteId), "source.pdf")
            if (!file.exists()) return@withLock emptyList()
            val texts = try {
                ensurePdfBox()
                PDDocument.load(file).use { doc ->
                    val stripper = PDFTextStripper()
                    (1..doc.numberOfPages).map { page ->
                        stripper.startPage = page
                        stripper.endPage = page
                        PdfPageText(page - 1, stripper.getText(doc))
                    }
                }
            } catch (_: Exception) { emptyList() }
            evictPdfCache(pdfTextCache, noteId)
            pdfTextCache[noteId] = texts
            texts
        }
    }

    // ---- Imported PDF navigation -----------------------------------------------------------

    /** Loads pdfbox's bundled resources once; extraction without it fails on some font tables. */
    private fun ensurePdfBox() {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(context)
            pdfBoxReady = true
        }
    }

    /** Keeps one cached readout per recent notebook; the oldest goes when another arrives. */
    private fun <T> evictPdfCache(cache: MutableMap<String, T>, noteId: String) {
        if (cache.size >= MAX_CACHED_TEXTS && noteId !in cache) {
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
    }

    /**
     * Every tappable link of the notebook in Folio page coordinates, read once and remembered.
     * Pages the PDF marks as rotated are skipped — their annotation space no longer lines up with
     * the rendered background — and unsafe targets never become links at all.
     */
    suspend fun pdfPageLinks(noteId: String, pages: List<NotePage>): List<PdfLink> = withContext(Dispatchers.IO) {
        pdfLock.withLock {
            pdfLinkCache[noteId]?.let { return@withLock it }
            val file = File(storedDirectory(noteId), "source.pdf")
            if (!file.exists()) return@withLock emptyList()
            val dims = pages.filter { it.pdfIndex != null }.associate { it.pdfIndex!! to (it.width to it.height) }
            val links = try {
                ensurePdfBox()
                PDDocument.load(file).use { doc ->
                    (0 until doc.numberOfPages).flatMap { index ->
                        val (pageW, pageH) = dims[index] ?: return@flatMap emptyList()
                        try { pageLinks(doc, index, pageW, pageH) } catch (_: Exception) { emptyList() }
                    }
                }
            } catch (_: Exception) { emptyList() }
            evictPdfCache(pdfLinkCache, noteId)
            pdfLinkCache[noteId] = links
            links
        }
    }

    /** The links of one PDF page mapped onto its Folio page, or nothing when it is rotated. */
    private fun pageLinks(doc: PDDocument, index: Int, pageW: Float, pageH: Float): List<PdfLink> {
        val page = doc.getPage(index)
        if (page.rotation != 0) return emptyList()
        val crop = page.cropBox
        return page.annotations.mapNotNull { annotation ->
            val link = annotation as? PDAnnotationLink ?: return@mapNotNull null
            try {
                val rect = link.rectangle ?: return@mapNotNull null
                val target = linkTarget(doc, link) ?: return@mapNotNull null
                PdfLinks.mapLink(index, rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY,
                    crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, pageW, pageH, target)
            } catch (_: Exception) { null }
        }
    }

    /** A link's destination as Folio understands it: a safe URL or a page of the same document. */
    private fun linkTarget(doc: PDDocument, link: PDAnnotationLink): PdfLinkTarget? {
        val uri = (link.action as? PDActionURI)?.uri
        PdfLinks.urlTarget(uri)?.let { return PdfLinkTarget.Url(it) }
        val dest = try { link.destination } catch (_: Exception) { null }
            ?: (link.action as? PDActionGoTo)?.let { action ->
                try { action.destination } catch (_: Exception) { null }
            }
        val page = destinationPage(doc, dest)
        return if (page >= 0) PdfLinkTarget.Page(page) else null
    }

    /** The 0-based page a destination points at, or -1 when it cannot be followed. */
    private fun destinationPage(doc: PDDocument, dest: PDDestination?): Int {
        if (dest == null) return -1
        return try {
            val explicit = if (dest is PDNamedDestination) {
                doc.documentCatalog.names?.dests?.getValue(dest.namedDestination)
            } else dest
            val page = (explicit as? PDPageDestination)?.retrievePageNumber() ?: -1
            if (page in 0 until doc.numberOfPages) page else -1
        } catch (_: Exception) { -1 }
    }

    /**
     * The PDF's bookmarks as a flat list carrying its nesting depth, read once and remembered.
     * A missing outline simply yields no entries, which the contents panel reports as such.
     */
    suspend fun pdfOutline(noteId: String): List<PdfOutlineEntry> = withContext(Dispatchers.IO) {
        pdfLock.withLock {
            pdfOutlineCache[noteId]?.let { return@withLock it }
            val file = File(storedDirectory(noteId), "source.pdf")
            if (!file.exists()) return@withLock emptyList()
            val entries = try {
                ensurePdfBox()
                PDDocument.load(file).use { doc ->
                    val out = mutableListOf<PdfOutlineEntry>()
                    doc.documentCatalog.documentOutline?.let { walkOutline(doc, it, 0, out) }
                    PdfOutline.sanitize(out, doc.numberOfPages)
                }
            } catch (_: Exception) { emptyList() }
            evictPdfCache(pdfOutlineCache, noteId)
            pdfOutlineCache[noteId] = entries
            entries
        }
    }

    /** Depth-first walk of one outline level, capped so a malformed file cannot loop forever. */
    private fun walkOutline(doc: PDDocument, node: PDOutlineNode, depth: Int, out: MutableList<PdfOutlineEntry>) {
        var child: PDOutlineItem? = try { node.firstChild } catch (_: Exception) { null } ?: return
        while (out.size < PdfOutline.MAX_ENTRIES) {
            val title = try { child.title } catch (_: Exception) { null }.orEmpty()
            val dest = try { child.destination } catch (_: Exception) { null }
                ?: (try { child.action } catch (_: Exception) { null } as? PDActionGoTo)?.let { action ->
                    try { action.destination } catch (_: Exception) { null }
                }
            val page = destinationPage(doc, dest)
            if (page >= 0) out += PdfOutlineEntry(title, page, depth)
            // Children deeper than the cap stay listed one level up instead of nesting further.
            if (depth < PdfOutline.MAX_DEPTH) {
                try { walkOutline(doc, child, depth + 1, out) } catch (_: Exception) { }
            }
            child = try { child.nextSibling } catch (_: Exception) { null } ?: break
        }
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

    private companion object {
        /** Extracted PDF texts kept in memory; the oldest goes when another notebook is searched. */
        const val MAX_CACHED_TEXTS = 8
    }
}
