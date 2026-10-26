package com.folio.notes.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.folio.notes.editor.follow.WritingLane
import com.folio.notes.marking.MarkOcr
import com.folio.notes.marking.MarkZone
import com.folio.notes.marking.MarkZones
import com.folio.notes.model.NotePage
import com.folio.notes.pdf.PdfLink
import com.folio.notes.pdf.PdfLinkTarget
import com.folio.notes.pdf.PdfLinks
import com.folio.notes.pdf.PdfOutline
import com.folio.notes.pdf.PdfOutlineEntry
import com.folio.notes.pdf.PdfPageText
import com.folio.notes.pdf.PdfRenderCache
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/**
 * What Folio reads out of a notebook's imported `source.pdf`: embedded text (for search and exam
 * detection), tappable links and the outline, printed mark allocations, page labels and rendered page
 * backgrounds. Parsed results are cached per notebook so navigation and search never reparse; the
 * PDF itself is never modified. Owned by [NoteRepository], which supplies each notebook's [directory].
 */
class NotebookPdfs internal constructor(private val context: Context, private val directory: (String) -> File) {
    /** Whole-document PDFBox scans must never hold the editor's native rendering queue. */
    internal val pdfMetadataLock = Mutex()
    /** Extracted PDF text by notebook, so a search never reparses the document. */
    private val pdfTextCache = mutableMapOf<String, List<PdfPageText>>()
    /** Tappable PDF links and bookmarks by notebook, so navigation never reparses either. */
    private val pdfLinkCache = mutableMapOf<String, List<PdfLink>>()
    private val pdfMarkZoneCache = mutableMapOf<String, Pair<String, List<MarkZone>>>()
    private val pdfOutlineCache = mutableMapOf<String, List<PdfOutlineEntry>>()
    private var pdfBoxReady = false

    // ---- Imported PDF text -----------------------------------------------------------------

    /** Content equality avoids duplicate warnings based only on filenames or guessed metadata. */
    suspend fun findDuplicatePdf(uri: android.net.Uri, notebookIds: List<String>): String? = withContext(Dispatchers.IO) {
        fun digest(input: java.io.InputStream): ByteArray = input.use { stream ->
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = stream.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
            hash.digest()
        }
        val wanted = context.contentResolver.openInputStream(uri)?.let(::digest) ?: return@withContext null
        for (id in notebookIds) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val source = File(directory(id), "source.pdf")
            if (source.exists() && digest(source.inputStream()).contentEquals(wanted)) return@withContext id
        }
        null
    }

    suspend fun pdfPageLabels(noteId: String): Map<Int, String> = withContext(Dispatchers.IO) {
        pdfMetadataLock.withLock {
            val file = File(directory(noteId), "source.pdf")
            if (!file.exists()) return@withLock emptyMap()
            ensurePdfBox()
            PDDocument.load(file, MemoryUsageSetting.setupMixed(8L * 1024 * 1024).setTempDir(context.cacheDir)).use { doc ->
                doc.documentCatalog.pageLabels?.labelsByPageIndices?.mapIndexed { index, label -> index to label }?.toMap().orEmpty()
            }
        }
    }

    /**
     * Every page's embedded text in notebook order, extracted once and remembered while the app
     * runs. A scanned or locked PDF simply yields empty strings, which the search panel reports
     * as having no searchable text rather than failing the search.
     */
    suspend fun pdfPageTexts(noteId: String): List<PdfPageText> = withContext(Dispatchers.IO) {
        pdfMetadataLock.withLock {
            pdfTextCache[noteId]?.let { return@withLock it }
            val file = File(directory(noteId), "source.pdf")
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
    internal fun extractPdfPageTexts(doc: PDDocument, maxPages: Int = doc.numberOfPages,
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
        File(directory(noteId), "source.pdf").takeIf { it.exists() }

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
        pdfMetadataLock.withLock {
            pdfLinkCache[noteId]?.let { return@withLock it }
            val file = File(directory(noteId), "source.pdf")
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
        pdfMetadataLock.withLock {
            val file = File(directory(noteId), "source.pdf")
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
        pdfMetadataLock.withLock {
            pdfOutlineCache[noteId]?.let { return@withLock it }
            val file = File(directory(noteId), "source.pdf")
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
            return render(index, targetWidth)
        }

        internal fun render(index: Int, targetWidth: Int): Bitmap {
            return renderer.openPage(index).use { pdfPage ->
                // Bound both dimensions for unusually tall or wide imported documents.
                val scale = minOf(targetWidth.toFloat() / pdfPage.width, 2800f / pdfPage.height)
                val bitmap = Bitmap.createBitmap((pdfPage.width * scale).toInt().coerceAtLeast(1),
                    (pdfPage.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        }

        /** [region] of a [pageWidth]×[pageHeight] page (page units) at [pixelScale] px per unit. */
        internal fun renderRegion(index: Int, pageWidth: Float, pageHeight: Float, region: RectF, pixelScale: Float): Bitmap {
            return renderer.openPage(index).use { pdfPage ->
                val bitmap = Bitmap.createBitmap((region.width() * pixelScale).roundToInt().coerceAtLeast(1),
                    (region.height() * pixelScale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val transform = Matrix().apply {
                    setScale(pageWidth / pdfPage.width * pixelScale, pageHeight / pdfPage.height * pixelScale)
                    postTranslate(-region.left * pixelScale, -region.top * pixelScale)
                }
                try {
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        }
        override fun close() { renderer.close(); descriptor.close() }
    }

    /** Opens a notebook's imported PDF for repeated rendering, or null when it has no source file. */
    fun openPdf(noteId: String): PdfBackgrounds? {
        val file = File(directory(noteId), "source.pdf")
        if (!file.exists()) return null
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfBackgrounds(descriptor, PdfRenderer(descriptor)) }
        catch (e: Exception) { descriptor.close(); throw e }
    }

    // Keep the editor, split/reference pane and a recently used notebook parsed. The bounded image
    // cache is independent of metadata scans and cached hits bypass even an in-progress page render.
    private val pdfRenders = PdfRenderCache<PdfBackgrounds, Bitmap>(
        maxBytes = backgroundBudget(),
        open = ::openPdf,
        draw = { document, index, width -> document.render(index, width) },
        sizeOf = { it.allocationByteCount },
    )

    /**
     * Bitmap pixels live in native memory (API 26+), so the Java heap says little about what fits: size the
     * background cache from device RAM like [com.folio.notes.ink.InkRasterStore], so scrolling back up a long
     * paper on a tablet redraws cached pages instead of re-rendering them. Low-RAM devices keep the old budget.
     */
    private fun backgroundBudget(): Int {
        val heap = (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024)
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        if (manager == null || manager.isLowRamDevice) return heap.toInt()
        val memory = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return (memory.totalMem / 64).coerceIn(heap, 192L * 1024 * 1024).toInt()
    }

    /** Gives cached backgrounds back under memory pressure; parsed documents stay open. */
    fun trimMemory() = pdfRenders.trimImages()

    /** Drops the named document and its backgrounds, or all recent documents when unnamed. */
    suspend fun closePdf(noteId: String? = null) = withContext(Dispatchers.IO) { pdfRenders.close(noteId) }

    /** Shared immutable background, possibly sharper than requested; callers must not recycle it. */
    suspend fun cachedPdfBackground(noteId: String, page: NotePage, targetWidth: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        val index = page.pdfIndex ?: return@withContext null
        pdfRenders.get(noteId, index, targetWidth)?.also { it.prepareToDraw() }
    }

    /** Fresh caller-owned background for paths that recycle their bitmaps, sharing parsed documents. */
    suspend fun pdfBackground(noteId: String, page: NotePage, targetWidth: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        val index = page.pdfIndex ?: return@withContext null
        pdfRenders.render(noteId, index, targetWidth)
    }

    /** Fresh, uncached render of part of a page, for a sharp zoomed-in view; null for a native page. */
    suspend fun pdfRegion(noteId: String, page: NotePage, region: RectF, pixelScale: Float): Bitmap? = withContext(Dispatchers.IO) {
        val index = page.pdfIndex ?: return@withContext null
        pdfRenders.withDocument(noteId) { it.renderRegion(index, page.width, page.height, region, pixelScale) }
    }

    /** Drops everything remembered about a notebook's PDF, as the notebook is deleted. */
    internal suspend fun forget(noteId: String) {
        closePdf(noteId)
        pdfMetadataLock.withLock { pdfTextCache.remove(noteId); pdfLinkCache.remove(noteId); pdfMarkZoneCache.remove(noteId); pdfOutlineCache.remove(noteId) }
    }

    private companion object {
        /** Extracted PDF texts kept in memory; the oldest goes when another notebook is searched. */
        const val MAX_CACHED_TEXTS = 8
    }
}
