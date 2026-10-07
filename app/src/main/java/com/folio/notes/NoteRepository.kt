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
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * On-device storage, one directory per notebook:
 *
 * ```
 * note.json          the notebook's fields plus one summary per page, deliberately holding no ink
 * pages/<id>.fps     one page's compacted binary snapshot, read only when that page is needed
 * pages/<id>.fjl     one framed binary record per edit: the ink, its revision and its undo step
 * source.pdf         an imported PDF, when the notebook has one
 * ```
 *
 * Keeping the index free of ink is what lets a library of long notebooks list instantly and a page's
 * content be fetched only when it is shown. Editing a page touches its journal and nothing else: one
 * record makes the change and the undo step durable together, so a pen stroke costs one append of a
 * few hundred bytes no matter how full the page is, and neither the notebook index nor the undo
 * stacks are rewritten to keep up. A torn tail is discarded on read, and a snapshot records the last
 * journal record it contains along with the undo stacks as of that point, so compaction can never
 * double-apply a record — or lose an undo step — when a crash lands between the snapshot landing and
 * the journal being cleared. Compaction itself never runs on the pen path: a journal past its bound
 * is queued and folded by [compactPending] once the writer is idle. A notebook saved by an older
 * version, which kept every page inline, is split into this layout the first time it is read, and a
 * page still stored as JSON (`<id>.json` + `<id>.journal`, optionally `<id>.history`) is converted
 * to the binary files the first time it is opened, after the new snapshot has been read back and
 * proven identical. Nothing writes the JSON shape any more; legacy `.folio` archives
 * still carry it as the portable interchange form. New backups carry the native binary files.
 */
class NoteRepository(private val context: Context) {
    private val root = File(context.filesDir, "notebooks").apply { mkdirs() }
    private val lock = Mutex()
    private val backupStagingLock = Mutex()
    private var backupStagingCleaned = false

    /** Remove unpublished files left by process death once, before any current backup starts. */
    private suspend fun cleanBackupStaging() = backupStagingLock.withLock {
        if (!backupStagingCleaned) {
            val uuid = "[a-f0-9-]{36}"
            val cacheName = Regex("(?:backup|restore)-$uuid|backup-object-.*\\.tmp")
            context.cacheDir.listFiles().orEmpty().filter { cacheName.matches(it.name) }.forEach { it.deleteRecursively() }
            root.listFiles().orEmpty().filter { it.isDirectory && Regex("\\.restore-$uuid").matches(it.name) }.forEach { it.deleteRecursively() }
            backupStagingCleaned = true
        }
    }

    private suspend fun newBackupStaging(prefix: String): File {
        cleanBackupStaging()
        return File(context.cacheDir, "$prefix-${UUID.randomUUID()}").apply { check(mkdirs()) { "Couldn't stage the backup" } }
    }
    /**
     * The next journal sequence number, last known-good byte length, and newest page revision seen,
     * per `<note>/<page>`. Parallel page reads (an export, a duplicate) touch this from several IO
     * threads, so it is concurrent; writes are guarded by [lock] and these simple assignments are
     * idempotent.
     */
    private val journalSeqs = ConcurrentHashMap<String, Int>()
    private val journalLengths = ConcurrentHashMap<String, Long>()
    private val journalRevs = ConcurrentHashMap<String, Int>()
    /**
     * Pages whose journal has outgrown [MAX_JOURNAL_BYTES] and whose snapshot is therefore stale.
     * Marking a page is one set insert on the pen path; folding it is a background job that only
     * runs while the writer has nothing else to do.
     */
    private val pendingCompaction = ConcurrentHashMap.newKeySet<String>()
    /** Where a marked page's current content and undo stacks are read from when it is folded. */
    private val compactionSources = ConcurrentHashMap<String, () -> PageCheckpoint?>()
    /** A single `PdfRenderer` is not safe to use from two threads at once, so renders are serialized. */
    private val pdfLock = Mutex()
    private var pdfNoteId: String? = null
    private var pdfSession: PdfBackgrounds? = null
    /** Extracted PDF text by notebook, so a search never reparses the document. */
    private val pdfTextCache = mutableMapOf<String, List<PdfPageText>>()
    /** Tappable PDF links and bookmarks by notebook, so navigation never reparses either. */
    private val pdfLinkCache = mutableMapOf<String, List<PdfLink>>()
    private val pdfMarkZoneCache = mutableMapOf<String, Pair<String, List<MarkZone>>>()
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
        File(pagesDirectory(noteId), "${checked(pageId)}.fps")
    private fun pageJournalFile(noteId: String, pageId: String): File =
        File(pagesDirectory(noteId), "${checked(pageId)}.fjl")
    /** The same file without touching the disk, for reads that must not create a directory. */
    private fun storedPageFile(noteId: String, pageId: String): File =
        File(storedDirectory(noteId), "pages/${checked(pageId)}.fps")
    private fun storedJournalFile(noteId: String, pageId: String): File =
        File(storedDirectory(noteId), "pages/${checked(pageId)}.fjl")
    /** The JSON-era files: snapshot, JSONL journal and the standalone undo file that predates both. */
    private fun legacyFiles(noteId: String, pageId: String): List<File> {
        val pages = File(storedDirectory(noteId), "pages")
        val id = checked(pageId)
        return listOf(File(pages, "$id.json"), File(pages, "$id.journal"), File(pages, "$id.history"))
    }
    private fun hasLegacyFiles(noteId: String, pageId: String): Boolean =
        legacyFiles(noteId, pageId).any { it.exists() || File(it.path + ".bak").exists() }
    private fun pageKey(noteId: String, pageId: String) = "$noteId/$pageId"
    private fun imageFile(noteId: String, imageId: String): File =
        File(File(directory(noteId), "images").apply { mkdirs() }, "${checked(imageId)}.jpg")
    private fun storedImageFile(noteId: String, imageId: String): File =
        File(File(storedDirectory(noteId), "images"), "${checked(imageId)}.jpg")

    // ---- Reading -------------------------------------------------------------------------

    suspend fun load(): Pair<List<Notebook>, List<Folder>> = withContext(Dispatchers.IO) {
        cleanBackupStaging()
        val dirs = root.listFiles().orEmpty()
            .filter { idPattern.matches(it.name) && it.isDirectory && (File(it, "note.json").exists() || File(it, "note.json.bak").exists()) }
        // Decode indexes concurrently; each notebook lives in its own directory.
        val notes = coroutineScope {
            dirs.map { async(Dispatchers.IO) { readIndex(it) } }.awaitAll()
        }
        val library = File(context.filesDir, "library.json")
        val folders = if (!library.exists() && !File(context.filesDir, "library.json.bak").exists()) emptyList() else {
            val array = JSONArray(AtomicFile(library).openRead().bufferedReader().use { it.readText() })
            (0 until array.length()).map { val f = array.getJSONObject(it); FolderCodec.decode(f) }
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
        full.pages.forEach { writeSnapshotFile(File(pages, "${checked(it.id)}.fps"), it, 0, PageJournal.History.EMPTY) }
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
        convertLegacyPage(noteId, summary.id)
        val file = storedPageFile(noteId, summary.id)
        val journal = storedJournalFile(noteId, summary.id)
        if (!file.exists() && !journal.exists()) return@withContext summary.copy(loaded = true)
        val snapshot = file.takeIf { it.exists() }?.let { readSnapshot(it) }
        val base = snapshot?.let { PageContent(it.strokes, it.texts, it.images, it.layers) } ?: PageContent.EMPTY
        val baseSeq = snapshot?.journalSeq ?: 0
        val records = readJournal(journal)
        val key = pageKey(noteId, summary.id)
        journalSeqs[key] = maxOf(baseSeq, PageJournal.lastSeq(records))
        // The index is written when the writer goes idle, so a burst of ink can sit in the journal
        // ahead of it. The journal carries the revision of every record, so the newest of the two is
        // the truth and a preview cache key can never go backwards.
        val revision = maxOf(summary.revision, snapshot?.revision ?: 0,
            records.maxOfOrNull { it.revision } ?: 0)
        journalRevs[key] = revision
        // A journal that outgrew its bound in an earlier session is folded once this page is idle.
        if (journal.length() > MAX_JOURNAL_BYTES) pendingCompaction += key
        val content = PageJournal.replay(base, baseSeq, records)
        summary.copy(strokes = content.strokes, texts = content.texts, images = content.images,
            layers = content.layers, revision = revision, loaded = true)
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

    /** Publish an attempt only after all of its new page snapshots exist. Existing histories stay intact. */
    suspend fun saveResponsePages(note: Notebook, pages: List<NotePage>) = withContext(Dispatchers.IO) {
        lock.withLock {
            pages.forEach { writeSnapshot(note.id, it, history = PageJournal.History.EMPTY) }
            atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note))
        }
    }

    /**
     * Writes one page's content as a fresh snapshot together with the index that carries its
     * revision, so a preview or an export can never believe a page is newer or older than it really
     * is. Used where a page is born whole — a duplicated page or an imported archive — rather than
     * edited, so there is no journal to append to and any journal left over is cleared with it.
     */
    suspend fun savePage(note: Notebook, page: NotePage, history: PageJournal.History = PageJournal.History.EMPTY) =
        withContext(Dispatchers.IO) {
            if (!page.loaded) return@withContext
            lock.withLock {
                writeSnapshot(note.id, page, history = history)
                atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note))
            }
        }

    /**
     * Durably appends a batch of transactions to one page's journal: buffered writes and a single
     * fsync for the whole batch, so a run of pen-ups that arrived while the writer was busy costs one
     * durable operation rather than one each. Nothing else is written — not the notebook index, not
     * an undo file, not the page snapshot — which is what keeps the cost of a stroke proportional to
     * the stroke rather than to the notebook. The journal is fsynced before returning, so everything
     * in the batch, ink and undo state together, is on disk before the writer moves on.
     */
    suspend fun appendPageTransactions(
        noteId: String, pageId: String, transactions: List<PageTransaction>, before: suspend () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (transactions.isEmpty()) return@withContext
        lock.withLock {
            val key = pageKey(noteId, pageId)
            // A JSON-era page must become binary before anything binary is appended to it: the
            // reader prefers the binary files, so a journal beside an unconverted snapshot would hide it.
            if (hasLegacyFiles(noteId, pageId) && !storedPageFile(noteId, pageId).exists()) convertLegacyLocked(noteId, pageId)
            var seq = journalSeqs[key] ?: storedMaxSeq(noteId, pageId)
            // Any bytes a record names land first, so a crash cannot leave a placement without its file.
            before()
            appendJournal(key, pageJournalFile(noteId, pageId)) { out ->
                for (transaction in transactions) {
                    seq += 1
                    out.write(JournalBinary.frame(transaction.copy(seq = seq)))
                }
            }
            journalSeqs[key] = seq
            journalRevs[key] = transactions.last().revision
            if ((journalLengths[key] ?: 0L) > MAX_JOURNAL_BYTES) pendingCompaction += key
        }
    }

    /**
     * One page's persisted undo/redo stacks, rebuilt rather than stored. The snapshot carries the
     * stacks as of the last journal record it folded in, and every record after it says what it did
     * to them, so folding the log onto the snapshot is the same history the editor had. Records the
     * snapshot already holds are skipped, exactly as they are for ink: a crash between the snapshot
     * landing and the journal being cleared must not push the same step twice. A JSON-era page is
     * converted first, which folds its old history file and journal into the binary snapshot.
     */
    suspend fun loadHistory(noteId: String, pageId: String): PageJournal.History = withContext(Dispatchers.IO) {
        convertLegacyPage(noteId, pageId)
        val snapshot = storedPageFile(noteId, pageId).takeIf { it.exists() }?.let { readSnapshot(it) }
        PageJournal.foldHistory(snapshot?.history ?: PageJournal.History.EMPTY,
            readJournal(storedJournalFile(noteId, pageId)), snapshot?.journalSeq ?: 0)
    }

    /**
     * Marks a page for background compaction and remembers where its current content and undo stacks
     * can be read. Registering is cheap enough to sit on the pen path; the fold itself happens in
     * [compactPending], off the queue, once the writer has drained.
     */
    fun requestCompaction(noteId: String, pageId: String, source: () -> PageCheckpoint?) {
        compactionSources[pageKey(noteId, pageId)] = source
    }

    /**
     * Folds every journal that outgrew [MAX_JOURNAL_BYTES] into its snapshot. Only safe to call
     * while nothing else is queued: a snapshot records the sequence it contains, so it must describe
     * exactly the content the journal has reached. A page whose content has moved on since the last
     * record — a queued edit not yet written — is left for the next idle moment rather than folded
     * early, and a page with nothing to fold is left alone.
     */
    suspend fun compactPending() = withContext(Dispatchers.IO) {
        if (pendingCompaction.isEmpty()) return@withContext
        val pages = pendingCompaction.mapNotNull { key ->
            val noteId = key.substringBefore('/')
            val pageId = key.substringAfter('/')
            val checkpoint = compactionSources[key]?.invoke()
            if (checkpoint != null && checkpoint.page.loaded) key to checkpoint else null
        }
        if (pages.isEmpty()) return@withContext
        lock.withLock {
            for ((key, checkpoint) in pages) {
                val (noteId, pageId) = key.substringBefore('/') to key.substringAfter('/')
                val journal = pageJournalFile(noteId, pageId)
                if (journal.length() <= MAX_JOURNAL_BYTES) { pendingCompaction -= key; continue }
                // The journal must have caught up with the content the snapshot would be built from.
                val written = journalRevs[key] ?: continue
                if (checkpoint.page.revision != written) continue
                writeSnapshot(noteId, checkpoint.page, null, checkpoint.history)
                compactionSources.remove(key)
            }
        }
    }

    /**
     * The index plus the content of every page held in memory; pages still on disk are left alone.
     * [history] carries each page's undo stacks, which the snapshot takes over from the journal.
     */
    suspend fun saveAll(note: Notebook, history: Map<String, PageJournal.History> = emptyMap()) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                note.pages.filter { it.loaded }.forEach {
                    writeSnapshot(note.id, it, history = history[it.id] ?: PageJournal.History.EMPTY)
                }
                atomicWrite(noteFile(note.id), NoteMetaCodec.encode(note))
            }
        }

    suspend fun deletePage(noteId: String, pageId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = pageKey(noteId, pageId)
            journalSeqs.remove(key); journalLengths.remove(key); journalRevs.remove(key)
            pendingCompaction -= key; compactionSources.remove(key)
            pageFile(noteId, pageId).delete()
            pageJournalFile(noteId, pageId).delete()
            // JSON-era files only exist for a page that was never opened since the binary format.
            legacyFiles(noteId, pageId).forEach { deleteAtomic(it) }
        }
        Unit
    }

    suspend fun saveFolders(folders: List<Folder>) = withContext(Dispatchers.IO) {
        lock.withLock { atomicWrite(File(context.filesDir, "library.json"), JSONArray().apply {
            folders.forEach { put(FolderCodec.encode(it)) }
        }.toString()) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        closePdf(id)
        pdfLock.withLock { pdfTextCache.remove(id); pdfLinkCache.remove(id); pdfMarkZoneCache.remove(id); pdfOutlineCache.remove(id) }
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
                    writeSnapshotFile(File(pagesDir, "${checked(page.id)}.fps"), page, 0, PageJournal.History.EMPTY)
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

    /** Reads only enough of a PDF to prefill the import review. The original URI is never modified. */
    suspend fun inspectPdf(uri: Uri): PendingPdfImport = withContext(Dispatchers.IO) {
        val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported document.pdf"
        val title = filename.substringBeforeLast('.', filename)
        val detection = detectImportedExam(title) {
            ensurePdfBox()
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
                    .setTempDir(context.cacheDir)).use { doc ->
                    ExamDocumentEvidence(
                        pages = extractPdfPageTexts(doc, ExamEvidenceCollector.MAX_PAGES,
                            ExamEvidenceCollector.MAX_PAGE_CHARACTERS),
                        metadata = listOfNotNull(doc.documentInformation.title,
                            doc.documentInformation.subject, doc.documentInformation.author)
                    )
                }
            } ?: error("This PDF could not be opened")
        }
        val exam = detection.toExamTags()
        PendingPdfImport(uri, smartImportedNotebookName(exam, title), exam, detected = true)
    }

    suspend fun importPdf(uri: Uri, folder: String?, options: PendingPdfImport? = null): Notebook = withContext(Dispatchers.IO) {
        val title = options?.title ?: context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).substringBeforeLast('.', it.getString(0)) else null
        } ?: "Imported document"
        val requestedExam = options?.exam
        val note = Notebook(title = title, folderId = folder, cover = 3)
        val dir = directory(note.id)
        try {
            val pdf = File(dir, "source.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                pdf.outputStream().use { output ->
                    input.copyTo(output, 256 * 1024)
                    // Publish the index only after its source PDF is durable too.
                    output.fd.sync()
                }
            }
                ?: error("This PDF could not be opened")
            val pages = PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                require(renderer.pageCount > 0) { "This PDF has no pages" }
                (0 until renderer.pageCount).map { index -> renderer.openPage(index).use {
                    NotePage(width = 840f, height = 840f * it.height / it.width, paper = Paper.PLAIN, pdfIndex = index)
                } }
            }
            val exam = requestedExam ?: pdfLock.withLock {
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
                }.toExamTags()
            }
            // Empty PDF pages are fully described by the index. Their first annotation creates
            // a journal lazily; hundreds of empty snapshots/fsyncs buy us no additional data.
            note.copy(pages = pages, exam = exam).also { saveMeta(it) }
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    /**
     * The hidden notebook that carries a Music score in the real editor: the score's PDF copied in as
     * ordinary pages, then [arrange] puts any pencil marks onto them (an older Folio's ink, or pages
     * copied from another score). Pages that already hold marks are written as snapshots before the
     * index, so an interruption leaves no notebook that points at missing ink.
     */
    suspend fun createScoreNotebook(scoreId: String, title: String, pdf: File,
        arrange: (List<NotePage>) -> List<NotePage> = { it }): Notebook = withContext(Dispatchers.IO) {
        val note = Notebook(title = title, cover = 3, musicScoreId = scoreId)
        val dir = directory(note.id)
        try {
            val source = File(dir, "source.pdf")
            pdf.inputStream().use { input -> source.outputStream().use { output ->
                input.copyTo(output, 256 * 1024)
                output.fd.sync()
            } }
            val blank = PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                require(renderer.pageCount > 0) { "This score has no pages" }
                (0 until renderer.pageCount).map { index -> renderer.openPage(index).use {
                    NotePage(width = 840f, height = 840f * it.height / it.width, paper = Paper.PLAIN, pdfIndex = index)
                } }
            }
            val created = note.copy(pages = arrange(blank))
            lock.withLock {
                created.pages.filter { it.strokes.isNotEmpty() || it.texts.isNotEmpty() }
                    .forEach { writeSnapshot(created.id, it, history = PageJournal.History.EMPTY) }
                atomicWrite(noteFile(created.id), NoteMetaCodec.encode(created))
            }
            // Read every carried page back before the caller empties the older copy of its marks.
            created.pages.filter { it.strokes.isNotEmpty() || it.texts.isNotEmpty() }.forEach { page ->
                val back = loadPage(created.id, page.copy(strokes = emptyList(), texts = emptyList(), loaded = false))
                check(back.strokes.size == page.strokes.size && back.texts.size == page.texts.size &&
                    back.strokes.sumOf { it.points.size } == page.strokes.sumOf { it.points.size }) { "A page's pencil marks could not be verified" }
            }
            created
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    /** Reads either a native single-notebook backup or an older portable JSON `.folio` archive. */
    suspend fun importArchive(uri: Uri, folder: String?): Notebook = withContext(Dispatchers.IO) {
        val staging = newBackupStaging("restore")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> ZipInputStream(input.buffered()).use { zip ->
                if (zip.nextEntry?.name == LibraryBackup.MANIFEST) {
                    val manifest = NativeBackup.decode(readBounded(zip, LibraryBackup.MAX_MANIFEST_BYTES).toString(Charsets.UTF_8))
                    require(!manifest.external && manifest.notes.size == 1 && manifest.folders.isEmpty()) { "Use Restore library backup for this file" }
                    zip.closeEntry()
                    val job = currentCoroutineContext()
                    val objects = NativeBackup.readEntries(zip, manifest, staging, job::ensureActive)
                    return@withContext importNativeLibrary(manifest, objects, emptyList(), folder, saveLibraryFolders = false).first.single()
                }
            } } ?: error("This backup could not be opened")
            val archived = context.contentResolver.openInputStream(uri)?.use { NotebookArchive.read(it) }
                ?: error("This backup could not be opened")
            importArchived(archived, folder)
        } finally { staging.deleteRecursively() }
    }

    private suspend fun importArchived(archived: ArchivedNotebook, folder: String?): Notebook {
        require(archived.note.pages.none { it.pdfIndex != null } || archived.pdf != null) { "Backup is missing its source PDF" }
        require(archived.note.pages.flatMap { it.images }.all { it.id in archived.images }) { "Backup is missing an image" }
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
        return note
    }

    /** Save the entire shelf through a SAF document, including folder organization. */
    suspend fun exportLibrary(uri: Uri, notes: List<Notebook>, folders: List<Folder>) =
        stageLibrary(notes, folders).use { writeStaged(it, uri) }

    /** Captures a consistent native file set. Call under the app storage gate. Immutable files
     * are pinned with hard links; only appendable journals need copying. Hashing, compression and
     * destination I/O happen later, after the editor's save gate has been released. */
    suspend fun stageLibrary(notes: List<Notebook>, folders: List<Folder>, includeExcluded: Boolean = false, onProgress: (String) -> Unit = {}): StagedLibrary = withContext(Dispatchers.IO) {
        val exclusions = AppPrefs.backupExcludedNotebookIds(context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            .getStringSet(AppPrefs.BACKUP_EXCLUDED_NOTEBOOKS, AppPrefs.DEFAULT_BACKUP_EXCLUDED_NOTEBOOKS))
        val included = AppPrefs.notebooksForBackup(notes, exclusions, includeExcluded)
        val staging = newBackupStaging("backup")
        val job = currentCoroutineContext()
        try {
            included.forEachIndexed { index, note ->
                onProgress("Preparing notebook ${index + 1} of ${included.size}: ${note.title}")
                note.pages.forEach { page ->
                    job.ensureActive()
                    convertLegacyPage(note.id, page.id)
                }
            }
            val files = lock.withLock {
                included.withIndex().associate { (index, note) ->
                    job.ensureActive()
                    onProgress("Capturing notebook ${index + 1} of ${included.size}: ${note.title}")
                    val captured = linkedMapOf<String, File>()
                    fun capture(path: String, source: File, appendable: Boolean = false) {
                        if (!source.exists() && !File(source.path + ".bak").exists()) return
                        // Recover any AtomicFile interrupted before its new value was published.
                        AtomicFile(source).openRead().use { }
                        val target = File(staging, "${checked(note.id)}/$path").apply { parentFile?.mkdirs() }
                        require(source.length() <= LibraryBackup.MAX_ENTRY_BYTES) { "Backup file is too large" }
                        if (appendable) source.copyTo(target) else pinFile(source, target)
                        captured[path] = target
                    }
                    val meta = File(staging, "${checked(note.id)}/note.json").apply { parentFile?.mkdirs() }
                    meta.writeText(NoteMetaCodec.encode(note), Charsets.UTF_8)
                    captured["note.json"] = meta
                    note.pages.forEach { page ->
                        job.ensureActive()
                        capture("pages/${checked(page.id)}.fps", storedPageFile(note.id, page.id))
                        capture("pages/${checked(page.id)}.fjl", storedJournalFile(note.id, page.id), appendable = true)
                    }
                    capture("source.pdf", File(storedDirectory(note.id), "source.pdf"))
                    require(note.pages.none { it.pdfIndex != null } || "source.pdf" in captured) { "Source PDF is missing from ${note.title}" }
                    // Keep images referenced by undo/redo too, without decoding every page's history.
                    val images = File(storedDirectory(note.id), "images")
                    images.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }.distinct().forEach { name ->
                        val id = name.removeSuffix(".jpg")
                        if (name.endsWith(".jpg") && id.length <= 64 && idPattern.matches(id)) capture("images/$name", File(images, name))
                    }
                    note.id to captured.toMap()
                }
            }
            StagedLibrary(staging, folders, files)
        } catch (e: Throwable) { staging.deleteRecursively(); throw e }
    }

    /** Pin files whose writers publish with AtomicFile; fall back for filesystems without links. */
    private fun pinFile(source: File, target: File) {
        try { android.system.Os.link(source.path, target.path) }
        catch (_: android.system.ErrnoException) { source.copyTo(target, overwrite = true) }
    }

    class StagedLibrary internal constructor(
        private val staging: File,
        private val folders: List<Folder>,
        private val files: Map<String, Map<String, File>>
    ) : java.io.Closeable {
        val notebookCount: Int get() = files.size

        suspend fun prepare(onProgress: (String) -> Unit = {}): NativeBackup.Prepared = withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            val sources = linkedMapOf<String, NativeBackup.Source>()
            val total = files.values.sumOf { it.size }
            var checked = 0
            val notes = files.map { (id, paths) ->
                NativeBackup.Note(id, paths.mapValues { (path, file) ->
                    job.ensureActive()
                    onProgress("Checking file ${++checked} of $total")
                    val limit = when {
                        path == "note.json" -> NotebookArchive.MAX_NOTE_BYTES
                        path == "source.pdf" -> NotebookArchive.MAX_PDF_BYTES
                        path.startsWith("images/") -> NotebookArchive.MAX_IMAGE_BYTES
                        else -> LibraryBackup.MAX_ENTRY_BYTES
                    }
                    require(file.length() <= limit) { "Backup file is too large" }
                    val (hash, source) = NativeBackup.inspect(file,
                        path != "source.pdf" && !path.startsWith("images/"), job::ensureActive)
                    sources.putIfAbsent(hash, source)
                    hash
                })
            }
            val manifest = NativeBackup.Manifest(folders, notes, sources.mapValues { it.value.info })
            NativeBackup.decode(NativeBackup.encode(manifest))
            NativeBackup.Prepared(manifest, sources)
        }
        suspend fun writeTo(output: OutputStream, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            NativeBackup.writePortable(output, prepare(onProgress), job::ensureActive, onProgress)
        }
        override fun close() { staging.deleteRecursively() }
    }

    suspend fun writeStaged(staged: StagedLibrary, uri: Uri, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { staged.writeTo(it, onProgress) }
            ?: error("Couldn't open the backup destination")
    }

    /** Validate every archive entry before adding anything to the existing library. */
    suspend fun importLibrary(uri: Uri, existingFolders: List<Folder>, backupTree: Uri? = null): Pair<List<Notebook>, List<Folder>> = withContext(Dispatchers.IO) {
        val staged = newBackupStaging("restore")
        val added = mutableListOf<Notebook>()
        try {
            var manifest: LibraryBackup.Manifest? = null
            val legacyFiles = mutableMapOf<String, File>()
            val noteFiles = mutableMapOf<String, File>()
            val assets = mutableMapOf<String, File>()
            context.contentResolver.openInputStream(uri)?.use { source ->
                ZipInputStream(source).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == LibraryBackup.MANIFEST) {
                            require(manifest == null) { "Duplicate backup manifest" }
                            val bytes = readBounded(zip, LibraryBackup.MAX_MANIFEST_BYTES)
                            manifest = LibraryBackup.parseManifest(bytes.toString(Charsets.UTF_8))
                            manifest.native?.let { native ->
                                zip.closeEntry()
                                val job = currentCoroutineContext()
                                val objects = NativeBackup.readEntries(zip, native, staged, job::ensureActive).toMutableMap()
                                if (native.external) objects.putAll(IncrementalBackup.readObjects(context, uri, backupTree, native, staged, job::ensureActive))
                                return@withContext importNativeLibrary(native, objects, existingFolders)
                            }
                        } else {
                            val info = manifest ?: error("The library manifest must come first")
                            when {
                                info.version == 1 && entry.name.startsWith(LibraryBackup.NOTE_PREFIX) && entry.name.endsWith(".folio") -> {
                                    val id = entry.name.removePrefix(LibraryBackup.NOTE_PREFIX).removeSuffix(".folio")
                                    require(entry.name == LibraryBackup.entryName(id) && id !in legacyFiles) { "Invalid notebook entry" }
                                    require(legacyFiles.size < LibraryBackup.MAX_NOTEBOOKS) { "Backup is too large" }
                                    val file = File(staged, "legacy-$id.folio")
                                    file.outputStream().use { out -> copyBounded(zip, out, LibraryBackup.MAX_ENTRY_BYTES) }
                                    legacyFiles[id] = file
                                }
                                info.version == 2 && entry.name.startsWith(LibraryBackup.NOTE_PREFIX) -> {
                                    val id = entry.name.removePrefix(LibraryBackup.NOTE_PREFIX).substringBefore('/')
                                    require(LibraryBackup.validId(id) && entry.name == LibraryBackup.noteEntryName(id) && id !in noteFiles) {
                                        "Invalid notebook entry"
                                    }
                                    require(noteFiles.size < LibraryBackup.MAX_NOTEBOOKS) { "Backup is too large" }
                                    val file = File(staged, "notes/$id.json").apply { parentFile?.mkdirs() }
                                    file.outputStream().use { out -> copyBounded(zip, out, NotebookArchive.MAX_NOTE_BYTES) }
                                    noteFiles[id] = file
                                }
                                info.version == 2 && entry.name.startsWith(LibraryBackup.ASSET_PREFIX) -> {
                                    val hash = entry.name.removePrefix(LibraryBackup.ASSET_PREFIX)
                                    require(LibraryBackup.isHash(hash) && hash !in assets) { "Invalid backup asset" }
                                    require(assets.size < LibraryBackup.MAX_ASSETS) { "Backup has too many assets" }
                                    val file = File(staged, "assets/$hash").apply { parentFile?.mkdirs() }
                                    file.outputStream().use { out -> copyBounded(zip, out, LibraryBackup.MAX_ENTRY_BYTES) }
                                    assets[hash] = file
                                }
                                else -> error("Unexpected file in Folio library backup")
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("This backup could not be opened")
            val info = manifest ?: error("This file is not a Folio library backup")
            val foundNotebookIds = if (info.version == 1) legacyFiles.keys else noteFiles.keys
            require(foundNotebookIds == info.notebookIds.toSet()) { "Backup is missing a notebook" }
            val folderIds = info.folders.map { it.id }.toSet()
            val legacyFolders = mutableMapOf<String, String?>()
            val notebookFolders = mutableMapOf<String, String?>()
            if (info.version == 1) {
                // Decode every nested archive before the first write, catching damaged backups.
                info.notebookIds.forEach { id ->
                    val archived = legacyFiles.getValue(id).inputStream().use(NotebookArchive::read)
                    require(archived.note.id == id &&
                        (archived.note.folderId == null || archived.note.folderId in folderIds)) {
                        "Notebook does not match the backup manifest"
                    }
                    require(archived.note.pages.flatMap { it.images }.all { it.id in archived.images }) {
                        "Backup is missing an image"
                    }
                    require(archived.note.pages.none { it.pdfIndex != null } || archived.pdf != null) {
                        "Backup is missing its source PDF"
                    }
                    legacyFolders[id] = archived.note.folderId
                }
            } else {
                val refs = info.assetsByNotebook
                val referencedHashes = refs.values.flatMap { listOfNotNull(it.pdfHash) + it.imageHashes.values }.toSet()
                require(assets.keys == referencedHashes) { "Backup is missing an asset" }
                require(referencedHashes.all { hash -> sha256(assets.getValue(hash)) == hash }) { "Backup asset checksum failed" }
                refs.values.forEach { reference ->
                    reference.pdfHash?.let { require(assets.getValue(it).length() <= NotebookArchive.MAX_PDF_BYTES) {
                        "This backup's PDF is too large to import"
                    } }
                    reference.imageHashes.values.forEach { hash -> require(assets.getValue(hash).length() <= NotebookArchive.MAX_IMAGE_BYTES) {
                        "This backup's image is too large to import"
                    } }
                }
                info.notebookIds.forEach { id ->
                    val note = NoteCodec.decode(noteFiles.getValue(id).readText(Charsets.UTF_8))
                    val assetRefs = refs.getValue(id)
                    val noteImageIds = note.pages.flatMap { it.images }.map { it.id }.toSet()
                    require(note.id == id && (note.folderId == null || note.folderId in folderIds) &&
                        noteImageIds == assetRefs.imageHashes.keys) {
                        "Notebook does not match the backup manifest"
                    }
                    require(note.pages.none { it.pdfIndex != null } || assetRefs.pdfHash != null) {
                        "Backup is missing its source PDF"
                    }
                    notebookFolders[id] = note.folderId
                }
            }
            val (folders, folderMap) = LibraryFolders.remap(info.folders)
            info.notebookIds.forEach { id ->
                val targetFolder = if (info.version == 1) legacyFolders.getValue(id)
                    else notebookFolders.getValue(id)
                val mappedFolder = targetFolder?.let(folderMap::get)
                added += if (info.version == 1) {
                    val archived = legacyFiles.getValue(id).inputStream().use(NotebookArchive::read)
                    importArchived(archived, mappedFolder)
                } else {
                    val note = NoteCodec.decode(noteFiles.getValue(id).readText(Charsets.UTF_8))
                    importLibraryNote(note, info.assetsByNotebook.getValue(id), assets, mappedFolder)
                }
            }
            saveFolders(existingFolders + folders)
            added.toList() to folders
        } catch (e: Exception) {
            added.forEach { runCatching { delete(it.id) } }
            throw e
        } finally { staged.deleteRecursively() }
    }

    private fun readBounded(input: java.io.InputStream, max: Long): ByteArray =
        java.io.ByteArrayOutputStream().use { output -> copyBounded(input, output, max); output.toByteArray() }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, max: Long) {
        var count = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            require(count <= max) { "Backup entry is too large" }
            output.write(buffer, 0, n)
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private suspend fun importLibraryNote(
        archivedNote: Notebook,
        references: LibraryBackup.AssetReference,
        assets: Map<String, File>,
        folder: String?
    ): Notebook {
        val newId = UUID.randomUUID().toString()
        val note = archivedNote.copy(id = newId, folderId = folder, updated = System.currentTimeMillis(),
            mistakeReviews = archivedNote.mistakeReviews.map { it.copy(practiceNotebookId = newId) })
        val dir = directory(note.id)
        try {
            references.pdfHash?.let { assets.getValue(it).copyTo(File(dir, "source.pdf"), overwrite = true) }
            references.imageHashes.forEach { (imageId, hash) ->
                require(idPattern.matches(imageId)) { "Invalid image identifier" }
                assets.getValue(hash).copyTo(imageFile(note.id, imageId), overwrite = true)
            }
            saveAll(note)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
        return note
    }

    /** Validates one page at a time, then installs native files without re-encoding ink or history. */
    private suspend fun importNativeLibrary(
        manifest: NativeBackup.Manifest, objects: Map<String, File>, existingFolders: List<Folder>,
        targetFolder: String? = null, saveLibraryFolders: Boolean = true
    ): Pair<List<Notebook>, List<Folder>> {
        val job = currentCoroutineContext()
        val folderIds = manifest.folders.map { it.id }.toSet()
        val notes = manifest.notes.map { entry ->
            job.ensureActive()
            fun file(path: String) = entry.files[path]?.let(objects::getValue)
            val note = NoteMetaCodec.decode(file("note.json")!!.readText(Charsets.UTF_8))
            require(note.id == entry.id && (note.folderId == null || note.folderId in folderIds) &&
                note.pages.map { it.id }.distinct().size == note.pages.size && note.pages.all { LibraryBackup.validId(it.id) && it.id.length <= 64 }) {
                "Notebook does not match the backup manifest"
            }
            val allowedPages = note.pages.flatMap { listOf("pages/${it.id}.fps", "pages/${it.id}.fjl") }.toSet()
            require(entry.files.keys.filter { it.startsWith("pages/") }.all { it in allowedPages }) { "Unexpected backup page" }
            require(note.pages.none { it.pdfIndex != null } || file("source.pdf") != null) { "Backup is missing its source PDF" }
            require((file("source.pdf")?.length() ?: 0) <= NotebookArchive.MAX_PDF_BYTES &&
                entry.files.filterKeys { it.startsWith("images/") }.values.all { objects.getValue(it).length() <= NotebookArchive.MAX_IMAGE_BYTES }) { "Backup asset is too large" }
            note.copy(pages = note.pages.map { page ->
                job.ensureActive()
                val snapshot = file("pages/${page.id}.fps")?.let { PageSnapshotBinary.read(it.readBytes()) }
                val records = file("pages/${page.id}.fjl")?.let { JournalBinary.readAll(it.readBytes()) }.orEmpty()
                val base = snapshot?.let { PageContent(it.strokes, it.texts, it.images, it.layers) } ?: PageContent.EMPTY
                val content = PageJournal.replay(base, snapshot?.journalSeq ?: 0, records)
                require(content.images.all { "images/${it.id}.jpg" in entry.files }) { "Backup is missing an image" }
                page.copy(revision = maxOf(page.revision, snapshot?.revision ?: 0, records.maxOfOrNull { it.revision } ?: 0))
            })
        }
        val (folders, folderMap) = LibraryFolders.remap(manifest.folders)
        val added = mutableListOf<Notebook>()
        try {
            notes.zip(manifest.notes).forEach { (original, entry) ->
                job.ensureActive()
                val id = UUID.randomUUID().toString()
                val note = original.copy(id = id, folderId = if (saveLibraryFolders) original.folderId?.let(folderMap::get) else targetFolder,
                    updated = System.currentTimeMillis(), mistakeReviews = original.mistakeReviews.map { it.copy(practiceNotebookId = id) })
                val pending = File(root, ".restore-$id").apply { mkdirs() }
                try {
                    entry.files.filterKeys { it != "note.json" }.forEach { (path, hash) ->
                        job.ensureActive()
                        val target = File(pending, path).apply { parentFile?.mkdirs() }
                        // Journals are appendable, so they must never share an inode with another page.
                        if (path.endsWith(".fjl")) atomicWriteStream(target) { out -> objects.getValue(hash).inputStream().use { it.copyTo(out) } }
                        else pinFile(objects.getValue(hash), target)
                    }
                    atomicWrite(File(pending, "note.json"), NoteMetaCodec.encode(note))
                    check(pending.renameTo(storedDirectory(id))) { "Couldn't install restored notebook" }
                    added += note
                } finally { pending.deleteRecursively() }
            }
            if (saveLibraryFolders) saveFolders(existingFolders + folders)
            return added.toList() to folders
        } catch (e: Throwable) {
            // Cleanup also runs on coroutine cancellation.
            added.forEach { storedDirectory(it.id).deleteRecursively() }
            throw e
        }
    }

    // ---- Placed images -------------------------------------------------------------------

    /** Stores one placed picture's bytes; the page JSON keeps only its placement. */
    suspend fun saveImage(noteId: String, imageId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        lock.withLock { atomicWriteStream(imageFile(noteId, imageId)) { it.write(bytes) } }
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

    /**
     * Every printed mark allocation ("[4 marks]") of the notebook in Folio page coordinates, read once and
     * remembered. Needs the PDF's own text layer, so a scanned paper yields none; rotated pages are skipped
     * like links, since their text space no longer lines up with the rendered background.
     */
    private suspend fun pdfTextMarkZones(noteId: String, pages: List<NotePage>): List<MarkZone> = withContext(Dispatchers.IO) {
        pdfLock.withLock {
            val file = File(storedDirectory(noteId), "source.pdf")
            if (!file.exists()) return@withLock emptyList()
            val dims = pages.filter { it.pdfIndex != null }.associate { it.pdfIndex!! to (it.width to it.height) }
            val signature = "${file.length()}:${file.lastModified()}:$dims"
            pdfMarkZoneCache[noteId]?.takeIf { it.first == signature }?.let { return@withLock it.second }
            val zones = try {
                ensurePdfBox()
                PDDocument.load(file, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
                    .setTempDir(context.cacheDir)).use { doc -> extractMarkZones(doc, dims) }
            } catch (_: Exception) { emptyList() }
            evictPdfCache(pdfMarkZoneCache, noteId)
            pdfMarkZoneCache[noteId] = signature to zones
            zones
        }
    }

    private val markOcrLock = Mutex()

    /** OCR has its own renderer and lock: a long paper must not hold up editor PDF backgrounds. */
    suspend fun pdfMarkZones(
        noteId: String, pages: List<NotePage>,
        onProgress: (List<MarkZone>, String?) -> Unit = { _, _ -> }
    ): List<MarkZone> = withContext(Dispatchers.IO) {
        markOcrLock.withLock {
            val zones = pdfTextMarkZones(noteId, pages).toMutableList()
            val targets = pages.distinctBy { it.pdfIndex }.filter { page ->
                page.pdfIndex != null && zones.none { it.pageIndex == page.pdfIndex }
            }
            suspend fun report(message: String?) = withContext(Dispatchers.Main) {
                onProgress(zones.toList(), message)
            }
            report(if (targets.isEmpty()) null else "Looking for printed marks on scanned pages…")
            if (targets.isEmpty()) return@withLock zones.toList()
            val file = sourcePdfFile(noteId) ?: run {
                report("The source PDF is unavailable. Manual stamps still work.")
                return@withLock zones.toList()
            }
            var failures = 0
            val ocr = MarkOcr(context.cacheDir, file)
            try {
                openPdf(noteId)?.use { renderer ->
                    for ((position, page) in targets.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        report("Reading scanned page ${position + 1} of ${targets.size}…")
                        try {
                            zones += ocr.read(page) { renderer.render(page, 2200)!! }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { failures++ }
                    }
                } ?: run { failures = targets.size }
            } finally { ocr.close() }
            report(if (failures == 0) null else
                "Couldn’t read $failures pages. Turn printed marks off and on to retry; manual stamps still work.")
            zones.toList()
        }
    }

    /** Reads the number inside a hand-boxed area, to prefill its marks; null when nothing legible is there. */
    suspend fun readMarkRegion(noteId: String, page: NotePage, lane: WritingLane): Int? = withContext(Dispatchers.IO) {
        if (page.pdfIndex == null) return@withContext null
        markOcrLock.withLock {
            val file = sourcePdfFile(noteId) ?: return@withLock null
            val ocr = MarkOcr(context.cacheDir, file)
            try {
                openPdf(noteId)?.use { renderer ->
                    val bitmap = renderer.render(page, 2200) ?: return@use null
                    try { ocr.readRegion(page, bitmap, lane) } finally { bitmap.recycle() }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null } finally { ocr.close() }
        }
    }

    private fun extractMarkZones(doc: PDDocument, dims: Map<Int, Pair<Float, Float>>): List<MarkZone> {
        val zones = mutableListOf<MarkZone>()
        val line = StringBuilder()
        val glyphs = ArrayList<TextPosition?>()
        var pageIndex = 0
        fun flush() {
            if (line.isNotEmpty()) {
                val (pageW, pageH) = dims[pageIndex] ?: (0f to 0f)
                val page = doc.getPage(pageIndex)
                val crop = page.cropBox
                if (pageW > 0f && pageH > 0f && page.rotation == 0 && crop.width > 0f && crop.height > 0f) {
                    for (match in MarkZones.find(line.toString())) {
                        val hit = (match.start until match.end).mapNotNull { glyphs.getOrNull(it) }
                        if (hit.isEmpty()) continue
                        val left = hit.minOf { it.xDirAdj }
                        val right = hit.maxOf { it.xDirAdj + it.widthDirAdj }
                        val top = hit.minOf { it.yDirAdj - it.heightDir }
                        val bottom = hit.maxOf { it.yDirAdj }
                        if (right <= left || bottom <= top) continue
                        zones += MarkZone(pageIndex, left / crop.width * pageW, top / crop.height * pageH,
                            (right - left) / crop.width * pageW, (bottom - top) / crop.height * pageH, match.marks)
                    }
                }
            }
            line.setLength(0); glyphs.clear()
        }
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                for (p in textPositions) { val u = p.unicode ?: continue; line.append(u); repeat(u.length) { glyphs.add(p) } }
            }
            override fun writeWordSeparator() { line.append(' '); glyphs.add(null) }
            override fun writeLineSeparator() { flush() }
            override fun writeParagraphEnd() { flush() }
        }
        for (index in 0 until doc.numberOfPages) {
            pageIndex = index
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            flush()
        }
        return zones
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
            // finishWrite must sync the still-open descriptor before closing it.
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            writer.write(value)
            writer.flush()
            atomic.finishWrite(stream)
        }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    /** Streams [write] into [file] atomically, synced before the old copy is replaced. */
    private fun atomicWriteStream(file: File, write: (OutputStream) -> Unit) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try {
            val buffered = java.io.BufferedOutputStream(stream, 64 * 1024)
            write(buffered)
            buffered.flush()
            atomic.finishWrite(stream)
        }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    /** Removes an [AtomicFile] and any backup it left, so no stale copy can be recovered later. */
    private fun deleteAtomic(file: File) {
        file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete()
    }

    private fun writeSnapshotFile(file: File, page: NotePage, seq: Int, history: PageJournal.History) =
        atomicWriteStream(file) { out -> PageSnapshotBinary.write(out, page, seq, history) }

    /**
     * Folds every journal record into a fresh snapshot. The snapshot records [seq] and the undo
     * stacks as they stand at that sequence, and only then is the journal cleared, so a crash in
     * between leaves both copies of the records and the next read simply skips the ones the snapshot
     * already contains. [history] is the editor's live stacks, which is what the records describe;
     * a caller that has none — importing a backup, writing a page that is born whole — passes empty
     * stacks for a journal that is empty too.
     */
    private fun writeSnapshot(
        noteId: String, page: NotePage, seq: Int? = null, history: PageJournal.History = PageJournal.History.EMPTY
    ) {
        val key = pageKey(noteId, page.id)
        val effective = seq ?: journalSeqs[key] ?: storedMaxSeq(noteId, page.id)
        writeSnapshotFile(pageFile(noteId, page.id), page, effective, history)
        val journal = pageJournalFile(noteId, page.id)
        // A fresh snapshot has no log to clear. Recover an AtomicFile backup before checking
        // length so a crash during an earlier clear cannot leave old records hidden in .bak.
        val atomicJournal = AtomicFile(journal)
        if (journal.exists() || File(journal.path + ".bak").exists()) {
            atomicJournal.openRead().use { }
            if (journal.length() > 0L) atomicWrite(journal, "")
        }
        journalSeqs[key] = effective
        journalLengths[key] = 0L
        journalRevs[key] = page.revision
        pendingCompaction -= key
    }

    /**
     * Appends a whole batch of framed records and forces them to disk before returning. A tail left
     * torn by an earlier crash is trimmed first, so a new record can never hide behind it; a failed
     * append is rolled back to the length it started from, so a partial batch is never half applied.
     * Both keep the log readable from the front, which is what replay relies on.
     */
    private fun appendJournal(key: String, file: File, writeRecords: (OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val known = journalLengths[key]
        if (known == null || known != file.length()) journalLengths[key] = repairJournalTail(file)
        val start = file.length()
        try {
            FileOutputStream(file, true).use { out ->
                // One transaction's frame plus the writer's buffer, never the whole batch twice.
                val buffered = java.io.BufferedOutputStream(out, 64 * 1024)
                writeRecords(buffered)
                buffered.flush()
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
        val validEnd = JournalBinary.completePrefixLength(bytes)
        if (validEnd < bytes.size) runCatching { RandomAccessFile(file, "rw").use { it.setLength(validEnd.toLong()) } }
        return validEnd.toLong()
    }

    /**
     * Reads a journal in order, stopping at the first frame that is incomplete or fails its
     * checksum. A process killed mid-append leaves at most a torn final frame, which is discarded;
     * the records before it are intact.
     */
    private fun readJournal(file: File): List<PageTransaction> {
        if (!file.exists()) return emptyList()
        return try { JournalBinary.readAll(file.readBytes()) } catch (_: Exception) { emptyList() }
    }

    /** Reads a snapshot whole; an unreadable or damaged one reads as absent rather than half a page. */
    private fun readSnapshot(file: File): PageSnapshotBinary.Snapshot? =
        runCatching { PageSnapshotBinary.read(AtomicFile(file).readFully()) }.getOrNull()

    /** The highest record folded into a page's snapshot or still sitting in its journal. */
    private fun storedMaxSeq(noteId: String, pageId: String): Int = maxOf(
        snapshotSeq(pageFile(noteId, pageId)),
        PageJournal.lastSeq(readJournal(pageJournalFile(noteId, pageId)))
    )

    /** Reads only the head of a snapshot: the sequence is stored first so this never parses a page. */
    private fun snapshotSeq(file: File): Int {
        if (!file.exists()) return 0
        return try {
            AtomicFile(file).openRead().use { input ->
                val head = ByteArray(PageSnapshotBinary.HEAD_BYTES)
                var read = 0
                while (read < head.size) { val n = input.read(head, read, head.size - read); if (n < 0) break; read += n }
                PageSnapshotBinary.peek(head, read)?.first ?: 0
            }
        } catch (_: Exception) { 0 }
    }

    // ---- Converting JSON-era pages ---------------------------------------------------------

    /** Converts a page still stored as JSON, if it is; a page already binary costs three file checks. */
    private suspend fun convertLegacyPage(noteId: String, pageId: String) {
        if (!hasLegacyFiles(noteId, pageId)) return
        lock.withLock { convertLegacyLocked(noteId, pageId) }
    }

    /**
     * Rewrites a JSON page as a binary snapshot and then deletes the JSON. The snapshot carries the
     * content with its journal already replayed and the undo stacks folded from the old snapshot,
     * journal and history file, so nothing a page remembered is lost. It is read back and compared
     * with what was decoded before a single JSON file is removed; if the two ever differ the page is
     * left exactly as it was and the open fails loudly. If a binary snapshot already exists it is the
     * truth and the JSON is only debris from an interrupted conversion.
     */
    private fun convertLegacyLocked(noteId: String, pageId: String) {
        val legacy = legacyFiles(noteId, pageId)
        if (!legacy.any { it.exists() || File(it.path + ".bak").exists() }) return
        val target = pageFile(noteId, pageId)
        if (target.exists() || File(target.path + ".bak").exists()) {
            legacy.forEach { deleteAtomic(it) }
            return
        }
        val (jsonFile, journalFile, historyFile) = legacy
        val raw = jsonFile.takeIf { it.exists() }?.let { readLegacyText(it) }
        val summary = NotePage(id = pageId)
        val base = raw?.let { NotePageCodec.decode(it, summary) }
            ?.let { PageContent(it.strokes, it.texts, it.images, it.layers) } ?: PageContent.EMPTY
        val baseSeq = raw?.let { NotePageCodec.journalSeq(it) } ?: 0
        val records = readLegacyJournal(journalFile)
        val content = PageJournal.replay(base, baseSeq, records)
        val seq = maxOf(baseSeq, PageJournal.lastSeq(records))
        val revision = maxOf(raw?.let { NotePageCodec.revision(it) } ?: 0, records.maxOfOrNull { it.revision } ?: 0)
        // A standalone history file already reflects every record on disk; whatever the log adds on
        // top of it is folded in, exactly as the JSON-era loader did.
        val carried = raw?.let { text -> NotePageCodec.history(text)?.let { it to baseSeq } }
            ?: historyFile.takeIf { it.exists() }?.let { readLegacyText(it) }?.let { PageJournal.decodeHistory(it) to 0 }
        val history = PageJournal.foldHistory((carried?.first ?: PageJournal.History.EMPTY), records, carried?.second ?: 0)
        val page = summary.copy(strokes = content.strokes, texts = content.texts, images = content.images,
            revision = revision, loaded = true)
        writeSnapshotFile(target, page, seq, history)
        val check = readSnapshot(target)
        val sound = check != null && check.strokes == content.strokes && check.texts == content.texts &&
            check.images == content.images && check.journalSeq == seq && check.revision == revision &&
            check.history.undo.size == history.undo.size && check.history.redo.size == history.redo.size
        if (!sound) {
            deleteAtomic(target)
            throw java.io.IOException("Couldn't convert this page to the new format; it was left untouched")
        }
        val key = pageKey(noteId, pageId)
        journalSeqs[key] = seq
        journalLengths[key] = 0L
        journalRevs[key] = revision
        legacy.forEach { deleteAtomic(it) }
    }

    /** Reads a JSON-era file; a failure propagates so an unreadable page is never mistaken for an empty one. */
    private fun readLegacyText(file: File): String =
        AtomicFile(file).openRead().bufferedReader().use { it.readText() }

    /** Reads a JSONL journal in order, stopping at the first unreadable line (a torn final record). */
    private fun readLegacyJournal(file: File): List<PageTransaction> {
        if (!file.exists()) return emptyList()
        val records = mutableListOf<PageTransaction>()
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

    private companion object {
        /** Extracted PDF texts kept in memory; the oldest goes when another notebook is searched. */
        const val MAX_CACHED_TEXTS = 8
        /**
         * A page journal is folded into its snapshot once it grows past this many bytes. Handwriting
         * is dense — a single busy page reaches megabytes — so a small bound would compact over and
         * over while the page is still being written, and the fold is background work now anyway.
         */
        const val MAX_JOURNAL_BYTES = 4L * 1024L * 1024L
    }
}
