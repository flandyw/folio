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
import com.tom_roush.pdfbox.io.MemoryUsageSetting
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * On-device storage, one directory per notebook:
 *
 * ```
 * note.json          the notebook's fields plus one summary per page, deliberately holding no ink
 * pages/<id>.json    one page's compacted snapshot, read only when that page is needed
 * pages/<id>.journal append-only records of edits since that snapshot, fsynced as they happen
 * pages/<id>.history small undo/redo stacks, so undo survives a restart
 * source.pdf         an imported PDF, when the notebook has one
 * ```
 *
 * Keeping the index free of ink is what lets a library of long notebooks list instantly and a page's
 * content be fetched only when it is shown. A committed edit is appended to the page's journal
 * instead of rewriting its whole snapshot, so a page carrying thousands of strokes pays for the
 * strokes that changed rather than for the page; the snapshot is rewritten in the background once
 * the journal grows past a bound. A torn tail is discarded on read, and a snapshot records the last
 * journal record it contains, so compaction can never double-apply a record when a crash lands
 * between the snapshot landing and the journal being cleared. A notebook saved by an older version,
 * which kept every page inline, is split into this layout the first time it is read.
 */
class NoteRepository(private val context: Context) {
    private val root = File(context.filesDir, "notebooks").apply { mkdirs() }
    private val lock = Mutex()
    /**
     * The next journal sequence number and last known-good byte length per `<note>/<page>`. Parallel
     * page reads (an export, a duplicate) touch this from several IO threads, so it is concurrent;
     * writes are guarded by [lock] and these simple assignments are idempotent.
     */
    private val journalSeqs = ConcurrentHashMap<String, Int>()
    private val journalLengths = ConcurrentHashMap<String, Long>()
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
    private fun pagesDirectory(noteId: String): File = File(directory(noteId), "pages").apply { mkdirs() }
    private fun pageFile(noteId: String, pageId: String): File =
        File(pagesDirectory(noteId), "${checked(pageId)}.json")
    private fun pageJournalFile(noteId: String, pageId: String): File =
        File(pagesDirectory(noteId), "${checked(pageId)}.journal")
    private fun pageHistoryFile(noteId: String, pageId: String): File =
        File(pagesDirectory(noteId), "${checked(pageId)}.history")
    private fun pageKey(noteId: String, pageId: String) = "$noteId/$pageId"
    private fun imageFile(noteId: String, imageId: String): File =
        File(File(directory(noteId), "images").apply { mkdirs() }, "${checked(imageId)}.jpg")
    private fun storedImageFile(noteId: String, imageId: String): File =
        File(File(storedDirectory(noteId), "images"), "${checked(imageId)}.jpg")

    // ---- Reading -------------------------------------------------------------------------

    suspend fun load(): Pair<List<Notebook>, List<Folder>> = withContext(Dispatchers.IO) {
        val dirs = root.listFiles().orEmpty()
            .filter { it.isDirectory && (File(it, "note.json").exists() || File(it, "note.json.bak").exists()) }
        // Decode indexes concurrently; each notebook lives in its own directory.
        val notes = coroutineScope {
            dirs.map { async(Dispatchers.IO) { readIndex(it) } }.awaitAll()
        }
        val library = File(context.filesDir, "library.json")
        val folders = if (!library.exists() && !File(context.filesDir, "library.json.bak").exists()) emptyList() else {
            val array = JSONArray(AtomicFile(library).openRead().bufferedReader().use { it.readText() })
            (0 until array.length()).map { val f = array.getJSONObject(it); Folder(f.getString("id"), f.getString("name")) }
        }
        Pair(notes, folders)
    }

    /**
     * Reads a notebook's shape. A version-2 file is re-encoded onto the current index so exam tags
     * gain their fields, and a version-1 file, which held every page's ink inline, is split first:
     * every page file is written before the index is swapped, so an interruption leaves the original
     * file in place and the next open simply migrates again.
     */
    private fun readIndex(dir: File): Notebook {
        val raw = AtomicFile(File(dir, "note.json")).openRead().bufferedReader().use { it.readText() }
        val version = NoteMetaCodec.versionOf(raw)
        when (version) {
            NoteMetaCodec.VERSION -> return NoteMetaCodec.decode(raw)
            5 -> {
                val note = NoteMetaCodec.decodeVersion5(raw)
                atomicWrite(File(dir, "note.json"), NoteMetaCodec.encode(note))
                return note
            }
            4 -> {
                val note = NoteMetaCodec.decodeVersion4(raw)
                atomicWrite(File(dir, "note.json"), NoteMetaCodec.encode(note))
                return note
            }
            3 -> {
                val note = NoteMetaCodec.decodeVersion3(raw)
                atomicWrite(File(dir, "note.json"), NoteMetaCodec.encode(note))
                return note
            }
            2 -> {
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
     * The compacted snapshot is the base and the journal is replayed on top; a torn final record
     * from a process death is ignored, and the rest of the page is still exactly what was saved.
     */
    suspend fun loadPage(noteId: String, summary: NotePage): NotePage = withContext(Dispatchers.IO) {
        if (summary.loaded) return@withContext summary
        val pages = File(storedDirectory(noteId), "pages")
        val file = File(pages, "${checked(summary.id)}.json")
        val journal = File(pages, "${checked(summary.id)}.journal")
        if (!file.exists() && !journal.exists()) return@withContext summary.copy(loaded = true)
        val snapshot = file.takeIf { it.exists() }?.let { AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() } }
        val base = snapshot?.let { NotePageCodec.decode(it, summary) }
            ?.let { PageContent(it.strokes, it.texts, it.images) } ?: PageContent.EMPTY
        val baseSeq = snapshot?.let { NotePageCodec.journalSeq(it) } ?: 0
        val records = readJournal(journal)
        journalSeqs[pageKey(noteId, summary.id)] = maxOf(baseSeq, PageJournal.lastSeq(records))
        val content = PageJournal.replay(base, baseSeq, records)
        summary.copy(strokes = content.strokes, texts = content.texts, images = content.images, loaded = true)
    }

    /** Fetches every page still on disk; exports and backups need the whole notebook at once. */
    suspend fun loadPages(note: Notebook): Notebook = withContext(Dispatchers.IO) {
        // Parallel page reads; each page is an independent file.
        val loaded = coroutineScope {
            note.pages.map { page ->
                async { if (page.loaded) page else loadPage(note.id, page) }
            }.awaitAll()
        }
        note.copy(pages = loaded)
    }

    // ---- Writing -------------------------------------------------------------------------

    /** Writes the index: notebook fields and one summary per page, never any ink. */
    suspend fun saveMeta(note: Notebook) = withContext(Dispatchers.IO) {
        lock.withLock { atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note)) }
    }

    /**
     * Writes one page's content as a fresh snapshot together with the index that carries its
     * revision, so a preview or an export can never believe a page is newer or older than it really
     * is. Used where a page is born whole — a duplicated page or an imported archive — rather than
     * edited, so there is no journal to append to and any journal left over is cleared with it.
     */
    suspend fun savePage(note: Notebook, page: NotePage) = withContext(Dispatchers.IO) {
        if (!page.loaded) return@withContext
        lock.withLock {
            writeSnapshot(note.id, page)
            atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note))
        }
    }

    /**
     * Durably appends one edit to a page's journal and rewrites the small index. It does not rewrite
     * the page snapshot: the journal is fsynced as it is appended, so a pen stroke is on disk before
     * the next one begins, and the snapshot is compacted later once the log grows past a bound.
     */
    suspend fun appendPageEdit(note: Notebook, page: NotePage, edit: PageEdit) = withContext(Dispatchers.IO) {
        if (!page.loaded || edit.isEmpty) return@withContext
        lock.withLock {
            val key = pageKey(note.id, page.id)
            val seq = (journalSeqs[key] ?: storedMaxSeq(note.id, page.id)) + 1
            journalSeqs[key] = seq
            appendJournal(key, pageJournalFile(note.id, page.id), PageJournal.encode(seq, edit))
            atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note))
            if (pageJournalFile(note.id, page.id).length() > MAX_JOURNAL_BYTES) writeSnapshot(note.id, page, seq)
        }
    }

    /** One page's persisted undo/redo stacks; an absent file is an empty history. Never creates one. */
    suspend fun loadHistory(noteId: String, pageId: String): PageJournal.History = withContext(Dispatchers.IO) {
        val file = File(File(storedDirectory(noteId), "pages"), "${checked(pageId)}.history")
        if (!file.exists()) return@withContext PageJournal.History.EMPTY
        PageJournal.decodeHistory(runCatching {
            AtomicFile(file).openRead().bufferedReader().use { it.readText() }
        }.getOrNull())
    }

    /** Persists the bounded undo/redo stacks so undo survives a restart. */
    suspend fun saveHistory(noteId: String, pageId: String, history: PageJournal.History) = withContext(Dispatchers.IO) {
        lock.withLock { atomicWrite(pageHistoryFile(noteId, pageId), PageJournal.encodeHistory(history)) }
    }

    /** The index plus the content of every page held in memory; pages still on disk are left alone. */
    suspend fun saveAll(note: Notebook) = withContext(Dispatchers.IO) {
        lock.withLock {
            note.pages.filter { it.loaded }.forEach { writeSnapshot(note.id, it) }
            atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note))
        }
    }

    suspend fun deletePage(noteId: String, pageId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            journalSeqs.remove(pageKey(noteId, pageId))
            journalLengths.remove(pageKey(noteId, pageId))
            pageFile(noteId, pageId).delete()
            pageJournalFile(noteId, pageId).delete()
            pageHistoryFile(noteId, pageId).delete()
        }
        Unit
    }

    suspend fun saveFolders(folders: List<Folder>) = withContext(Dispatchers.IO) {
        lock.withLock { atomicWrite(File(context.filesDir, "library.json"), JSONArray().apply {
            folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }.toString()) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        closePdf(id)
        pdfLock.withLock { pdfTextCache.remove(id); pdfLinkCache.remove(id); pdfOutlineCache.remove(id) }
        lock.withLock {
            val dir = storedDirectory(id)
            if (!dir.exists()) return@withLock
            val deleted = File(context.cacheDir, "deleted-$id-${System.currentTimeMillis()}")
            check(dir.renameTo(deleted)) { "Couldn't delete notebook" }
            // The library stops seeing the notebook atomically; interrupted cleanup is safe.
            deleted.deleteRecursively()
        }
        Unit
    }

    /**
     * Copies [note] beside itself under [title] with fresh notebook/page ids. Page files are
     * written before the new index, like migration, so an interruption leaves no half index
     * behind; placed pictures (same image ids, new notebook directory) and the imported PDF
     * travel along. Pages still only on disk are read first, so duplicating never drops ink.
     */
    suspend fun duplicateNotebook(note: Notebook, title: String): Notebook = withContext(Dispatchers.IO) {
        val full = loadPages(note)
        val copy = full.duplicatedAsCopy(title)
        lock.withLock {
            val dir = directory(copy.id)
            try {
                val pagesDir = File(dir, "pages").apply { mkdirs() }
                copy.pages.filter { it.loaded }.forEach { page ->
                    atomicWrite(File(pagesDir, "${checked(page.id)}.json"), NotePageCodec.encode(page))
                }
                full.pages.flatMap { it.images }.distinctBy { it.id }.forEach { image ->
                    storedImageFile(note.id, image.id).takeIf { it.exists() }?.let { src ->
                        src.copyTo(imageFile(copy.id, image.id), overwrite = true)
                    }
                }
                File(storedDirectory(note.id), "source.pdf").takeIf { it.exists() }?.let { src ->
                    src.copyTo(File(dir, "source.pdf"), overwrite = true)
                }
                atomicWrite(File(dir, "note.json"), NoteMetaCodec.encode(copy))
            } catch (e: Exception) {
                File(root, copy.id).takeIf { it.exists() }?.deleteRecursively()
                throw e
            }
        }
        copy
    }

    /** Bytes used on device by one notebook directory, or 0 when it was never saved. */
    suspend fun notebookSize(noteId: String): Long = withContext(Dispatchers.IO) {
        val dir = storedDirectory(noteId)
        if (!dir.exists()) return@withContext 0L
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }

    /** Bytes per notebook for [noteIds]; missing directories report 0 without touching disk. */
    suspend fun notebookSizes(noteIds: Collection<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        noteIds.distinct().associateWith { notebookSize(it) }
    }

    /**
     * True when every page of [note] carries no ink, typed text or pictures.
     * Pages still only on disk are read for the check; an unreadable page counts as
     * non-empty so cleanup never deletes work it could not inspect.
     */
    suspend fun isNotebookEmpty(note: Notebook): Boolean = withContext(Dispatchers.IO) {
        for (page in note.pages) {
            val loaded = if (page.loaded) page else try {
                loadPage(note.id, page)
            } catch (_: Exception) { return@withContext false }
            val hasInk = loaded.strokes.isNotEmpty() || loaded.images.isNotEmpty() ||
                loaded.texts.any { it.text.isNotBlank() }
            if (hasInk) return@withContext false
        }
        true
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
            val detection = pdfLock.withLock {
                detectImportedExam(title) {
                    ensurePdfBox()
                    PDDocument.load(pdf, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
                        .setTempDir(context.cacheDir)).use { doc ->
                        ExamDocumentEvidence(
                            pages = extractPdfPageTexts(doc, ExamEvidenceCollector.MAX_PAGES,
                                ExamEvidenceCollector.MAX_PAGE_CHARACTERS),
                            metadata = listOfNotNull(doc.documentInformation.title,
                                doc.documentInformation.subject, doc.documentInformation.author)
                        )
                    }
                }
            }
            note.copy(pages = pages, exam = detection.toExamTags()).also { saveAll(it) }
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
        val newId = UUID.randomUUID().toString()
        val note = archived.note.copy(id = newId, folderId = folder, updated = System.currentTimeMillis(),
            mistakeReviews = archived.note.mistakeReviews.map { it.copy(practiceNotebookId = newId) })
        val dir = directory(note.id)
        try {
            archived.pdf?.let { File(dir, "source.pdf").writeBytes(it) }
            archived.images.forEach { (id, bytes) ->
                if (idPattern.matches(id)) imageFile(note.id, id).writeBytes(bytes)
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
        if (page.images.isEmpty()) return@withContext emptyMap()
        coroutineScope {
            page.images.map { image ->
                async { loadImage(noteId, image.id)?.let { image.id to it } }
            }.awaitAll().filterNotNull().toMap()
        }
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
                PDDocument.load(file, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
                    .setTempDir(context.cacheDir)).use { doc ->
                    extractPdfPageTexts(doc)
                }
            } catch (_: Exception) { emptyList() }
            evictPdfCache(pdfTextCache, noteId)
            pdfTextCache[noteId] = texts
            texts
        }
    }

    /** Shared embedded-text path; import only visits the front matter and does not fill search's cache. */
    private fun extractPdfPageTexts(doc: PDDocument, maxPages: Int = doc.numberOfPages,
        maxCharacters: Int = Int.MAX_VALUE): List<PdfPageText> {
        val stripper = PDFTextStripper()
        return (1..minOf(doc.numberOfPages, maxPages)).map { page ->
            stripper.startPage = page
            stripper.endPage = page
            PdfPageText(page - 1, stripper.getText(doc).take(maxCharacters))
        }
    }

    // ---- Imported PDF navigation -----------------------------------------------------------

    /** Loads pdfbox's bundled resources once; extraction without it fails on some font tables. */
    fun ensurePdfBox() {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(context)
            pdfBoxReady = true
        }
    }

    /** The notebook's imported source PDF file, or null when it has none. No disk writes. */
    fun sourcePdfFile(noteId: String): File? =
        File(storedDirectory(noteId), "source.pdf").takeIf { it.exists() }

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
                PDDocument.load(file, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
                    .setTempDir(context.cacheDir)).use { doc ->
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
                PDDocument.load(file, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
                    .setTempDir(context.cacheDir)).use { doc ->
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
            val current = child ?: break
            val title = try { current.title } catch (_: Exception) { null }.orEmpty()
            val dest = try { current.destination } catch (_: Exception) { null }
                ?: (try { current.action } catch (_: Exception) { null } as? PDActionGoTo)?.let { action ->
                    try { action.destination } catch (_: Exception) { null }
                }
            val page = destinationPage(doc, dest)
            if (page >= 0) out += PdfOutlineEntry(title, page, depth)
            // Children deeper than the cap stay listed one level up instead of nesting further.
            if (depth < PdfOutline.MAX_DEPTH) {
                try { walkOutline(doc, current, depth + 1, out) } catch (_: Exception) { }
            }
            child = try { current.nextSibling } catch (_: Exception) { null }
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

    private data class PdfCacheKey(val noteId: String, val index: Int, val width: Int)
    // Shared immutable bitmaps: eviction drops ownership, never recycles a bitmap a View may use.
    private val pdfBitmapCache = object : android.util.LruCache<PdfCacheKey, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: PdfCacheKey, value: Bitmap) = value.allocationByteCount
    }

    suspend fun cachedPdfBackground(noteId: String, page: NotePage, targetWidth: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        val index = page.pdfIndex ?: return@withContext null
        val key = PdfCacheKey(noteId, index, targetWidth)
        pdfLock.withLock {
            pdfBitmapCache.get(key) ?: sharedPdf(noteId)?.render(page, targetWidth)?.also {
                it.prepareToDraw()
                pdfBitmapCache.put(key, it)
            }
        }
    }

    /** The page's white background, reusing the open renderer and serializing actual renders. */
    suspend fun pdfBackground(noteId: String, page: NotePage, targetWidth: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        if (page.pdfIndex == null) return@withContext null
        pdfLock.withLock { sharedPdf(noteId)?.render(page, targetWidth) }
    }

    private fun atomicWrite(file: File, value: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try {
            // Stream characters directly instead of materialising a second huge ByteArray.
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(value) }
            atomic.finishWrite(stream)
        }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    /**
     * Folds every journal record into a fresh snapshot. The snapshot records [seq], and only then is
     * the journal cleared, so a crash in between leaves both copies of the records and the next read
     * simply skips the ones the snapshot already contains.
     */
    private fun writeSnapshot(noteId: String, page: NotePage, seq: Int? = null) {
        val key = pageKey(noteId, page.id)
        val effective = seq ?: journalSeqs[key] ?: storedMaxSeq(noteId, page.id)
        atomicWrite(pageFile(noteId, page.id), NotePageCodec.encode(page, effective))
        atomicWrite(pageJournalFile(noteId, page.id), "")
        journalSeqs[key] = effective
        journalLengths[key] = 0L
    }

    /**
     * Appends one JSONL record and forces it to disk before returning. A tail left torn by an earlier
     * crash is trimmed first, so a new record can never hide behind it; a failed append is rolled
     * back to the length it started from. Both keep the log readable from the front, which is what
     * replay relies on.
     */
    private fun appendJournal(key: String, file: File, line: String) {
        file.parentFile?.mkdirs()
        val known = journalLengths[key]
        if (known == null || known != file.length()) journalLengths[key] = repairJournalTail(file)
        val start = file.length()
        try {
            FileOutputStream(file, true).use { out ->
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            journalLengths[key] = file.length()
        } catch (e: Exception) {
            runCatching { RandomAccessFile(file, "rw").use { it.setLength(start) } }
            journalLengths[key] = start
            throw e
        }
    }

    /** Trims a journal to its complete records and returns the byte length that remains. */
    private fun repairJournalTail(file: File): Long {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return 0L
        val validEnd = PageJournal.completePrefixLength(bytes)
        if (validEnd < bytes.size) runCatching { RandomAccessFile(file, "rw").use { it.setLength(validEnd.toLong()) } }
        return validEnd.toLong()
    }

    /**
     * Reads a journal in order, stopping at the first unreadable line. A process killed mid-append
     * leaves at most a torn final line, which is discarded; the records before it are intact.
     */
    private fun readJournal(file: File): List<JournalRecord> {
        if (!file.exists()) return emptyList()
        val records = mutableListOf<JournalRecord>()
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val record = PageJournal.decode(line) ?: break
                    records += record
                }
            }
        } catch (_: Exception) { /* unreadable tail: the snapshot still stands on its own */ }
        return records
    }

    /** The highest record folded into a page's snapshot or still sitting in its journal. */
    private fun storedMaxSeq(noteId: String, pageId: String): Int = maxOf(
        snapshotSeq(pageFile(noteId, pageId)),
        PageJournal.lastSeq(readJournal(pageJournalFile(noteId, pageId)))
    )

    private fun snapshotSeq(file: File): Int {
        if (!file.exists()) return 0
        return try {
            NotePageCodec.journalSeq(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        } catch (_: Exception) { 0 }
    }

    private companion object {
        /** Extracted PDF texts kept in memory; the oldest goes when another notebook is searched. */
        const val MAX_CACHED_TEXTS = 8
        /** A page journal is compacted into its snapshot once it grows past this many bytes. */
        const val MAX_JOURNAL_BYTES = 256L * 1024L
    }
}
