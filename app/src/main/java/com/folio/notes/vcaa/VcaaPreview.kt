package com.folio.notes.vcaa

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A downloaded paper held open for page-by-page rendering; `PdfRenderer` is single-threaded, so renders queue on [lock]. */
private class VcaaPreviewDocument(val file: File, private val descriptor: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private val lock = Mutex()
    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        lock.withLock {
            renderer.openPage(index).use { page ->
                val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    suspend fun aspect(index: Int): Float = withContext(Dispatchers.IO) {
        lock.withLock { renderer.openPage(index).use { it.height.toFloat() / it.width } }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        file.delete()
    }
}

/**
 * Look through an official paper before importing it. The PDF is fetched into the cache (the same
 * VCAA-only, size-bounded download the import uses), pages render as they scroll into view, and the
 * temporary copy is deleted when the preview closes. Nothing is added to the library until Import.
 */
@Composable
internal fun VcaaPreview(exam: VcaaExam, owned: Notebook?, canImport: Boolean, onImport: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf<VcaaPreviewDocument?>(null) }
    LaunchedEffect(exam.url, attempt) {
        failure = null
        val file = File(File(context.cacheDir, "vcaa-preview"), "${UUID.randomUUID()}.pdf")
        try {
            val opened = withContext(Dispatchers.IO) {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Could not prepare the preview folder" }
                downloadVcaaPdf(exam.url, file)
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try { VcaaPreviewDocument(file, descriptor, PdfRenderer(descriptor)) } catch (e: Exception) { descriptor.close(); throw e }
            }
            document = opened
        } catch (e: kotlinx.coroutines.CancellationException) { file.delete(); throw e }
        catch (e: Exception) { file.delete(); failure = e.message ?: "Could not load the preview" }
    }
    DisposableEffect(Unit) { onDispose { document?.close() } }

    FolioPanel(exam.title, onDismiss, actions = {
        if (owned != null) TextButton({ onDismiss(); onOpen() }) { Text("Open") }
        else FilledTonalButton({ onDismiss(); onImport() }, enabled = canImport) {
            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
            Spacer(Modifier.width(FolioSpacing.dp6))
            Text("Import")
        }
    }) {
        val doc = document
        when {
            failure != null -> Column(Modifier.padding(FolioSpacing.dp16)) {
                Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton({ attempt++ }) { Text("Retry") }
            }
            doc == null -> Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Downloading the paper…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> {
                Text("${doc.pageCount} ${if (doc.pageCount == 1) "page" else "pages"} · preview only, not added to your library",
                    Modifier.padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp4),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = rememberLazyListState(),
                    contentPadding = PaddingValues(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    itemsIndexed(List(doc.pageCount) { it }, key = { _, page -> page }) { _, page -> PreviewPage(doc, page) }
                }
            }
        }
    }
}

@Composable
private fun PreviewPage(doc: VcaaPreviewDocument, index: Int) {
    var bitmap by remember(doc, index) { mutableStateOf<Bitmap?>(null) }
    var aspect by remember(doc, index) { mutableStateOf(1.414f) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, 1400)
        LaunchedEffect(doc, index, width) {
            runCatching { doc.aspect(index) }.onSuccess { aspect = it }
            runCatching { doc.render(index, width) }.onSuccess { bitmap = it }
        }
        DisposableEffect(doc, index) { onDispose { bitmap = null } }
        val image = bitmap
        Box(Modifier.fillMaxWidth().aspectRatio(1f / aspect).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
            if (image != null) Image(image.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize())
            else CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}
