package com.folio.notes

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Repository-owned bitmaps are shared; this adapter never recycles a live view's resources. */
internal data class PageAssets(
    val background: Bitmap?, val pictures: Map<String, Bitmap>, val guides: List<WritingGuide>,
    val ready: Boolean, val error: Boolean, val retry: () -> Unit,
)

@Composable internal fun rememberPageAssets(noteId: String, page: NotePage, model: FolioViewModel,
    active: Boolean, followEnabled: Boolean, onLoad: () -> Unit): PageAssets {
    var background by remember(noteId, page.id) { mutableStateOf<Bitmap?>(null) }
    var writingGuides by remember(noteId, page.id) { mutableStateOf<List<WritingGuide>>(emptyList()) }
    var ready by remember(noteId, page.id) { mutableStateOf(page.pdfIndex == null) }
    var error by remember(noteId, page.id) { mutableStateOf(false) }
    var retry by remember(noteId, page.id) { mutableIntStateOf(0) }
    var pictures by remember(noteId, page.id) { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    // A page whose ink is still on disk is fetched as soon as it is about to be shown.
    LaunchedEffect(page.id, page.loaded) { if (!page.loaded) onLoad() }
    // PDF backgrounds belong to the repository's bounded cache. Let bitmap references expire
    // naturally: a native View or retained display list may still use them after composition ends.
    LaunchedEffect(noteId, page.id, retry) {
        if (page.pdfIndex != null) {
            ready = false; error = false
            try {
                val rendered = model.repository.cachedPdfBackground(noteId, page)
                background = rendered
                ready = true
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
        }
    }
    // Detect only the immutable paper/PDF background, never the user's ink. Pixel scanning
    // runs off the input thread and reruns only when the source or follow setting changes.
    LaunchedEffect(page.id, page.paper, page.width, page.height, background, active, followEnabled) {
        writingGuides = if (!active || !followEnabled || page.infinite) emptyList() else withContext(Dispatchers.Default) {
            val bitmap = background
            when {
                page.pdfIndex != null && bitmap != null ->
                    // One row at a time: no copy of the whole page's pixels.
                    WritingGuides.analyze(bitmap.width, bitmap.height, page.width, page.height) { y, row ->
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    }.guides
                page.pdfIndex == null && page.paper == Paper.RULED -> WritingGuides.ruled(page.width, page.height)
                page.pdfIndex == null && page.paper == Paper.SPLIT_RULED -> WritingGuides.ruled(page.width, page.height, split = true)
                page.pdfIndex == null && page.paper.isHanzi -> WritingGuides.hanzi(page.width, page.height, Paper.HANZI_CELL)
                else -> emptyList()
            }
        }
    }
    // Pictures arrive with the page content; a missing file simply leaves no bitmap to draw.
    // Keyed by image ids only: ink edits bump the page revision but never change picture bytes,
    // so redrawing a stroke must not re-decode every photo on the page.
    // Keyed by image count + content hash instead of a fresh id list allocated per composition.
    val imageKey = remember(noteId, page.id, page.loaded, page.images) {
        page.images.fold(0) { acc, img -> 31 * acc + img.id.hashCode() }
    }
    val imageFiles by model.imageFiles.collectAsState()
    LaunchedEffect(noteId, page.id, page.loaded, imageKey, imageFiles) {
        if (!page.loaded) return@LaunchedEffect
        if (page.images.isEmpty()) {
            pictures = emptyMap()
            return@LaunchedEffect
        }
        val decoded = model.repository.loadImages(noteId, page)
        pictures = decoded
    }
    return PageAssets(background, pictures, writingGuides, ready, error) { retry++ }
}
