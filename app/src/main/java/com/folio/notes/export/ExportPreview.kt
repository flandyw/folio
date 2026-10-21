@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioViewModel
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.ui.FolioPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Preview a real one-page export through the selected exporter, rather than an editor thumbnail. */
@Composable internal fun ExportPreview(request: PageExportRequest, model: FolioViewModel) {
    var open by remember { mutableStateOf(false) }
    TextButton({ open = true }, enabled = request.indices.isNotEmpty()) { Text("Preview exported appearance") }
    if (!open) return
    val context = LocalContext.current
    val prefs = rememberPrefs()
    val scale by rememberPref(prefs, AppPrefs.EXPORT_PNG_SCALE) { AppPrefs.pngScale(it.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE)) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bytes by remember { mutableLongStateOf(0) }
    LaunchedEffect(request, scale) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                val file = File.createTempFile("export-preview-", if (request.format == PageExportFormat.PDF) ".pdf" else ".png", context.cacheDir)
                try {
                    file.outputStream().use { NoteExporter(model.repository).write(it, request.copy(indices = request.indices.take(1)), scale, context.applicationContext) }
                    bytes = file.length()
                    if (request.format == PageExportFormat.PDF) {
                        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                            pdf.openPage(0).use { page ->
                                val factor = minOf(840f / page.width, 1200f / page.height)
                                Bitmap.createBitmap((page.width * factor).toInt().coerceAtLeast(1), (page.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { image ->
                                    image.eraseColor(android.graphics.Color.WHITE)
                                    page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
                        var sample = 1; while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1600) sample *= 2
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Could not render preview")
                    }
                } finally { file.delete() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Preview failed" }
    }
    DisposableEffect(Unit) { onDispose { bitmap?.recycle() } }
    FolioPanel("Export preview · page ${request.indices.first() + 1}", { open = false }) {
        Column(Modifier.fillMaxWidth().weight(1f, false).padding(16.dp)) {
            Text("${request.format} · ${if (request.format == PageExportFormat.PDF) request.pdfMode else "$scale×"} · sample file ${bytes / 1024} KB", style = MaterialTheme.typography.labelMedium)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            else bitmap?.let { Image(it.asImageBitmap(), "Exported sample with paper, PDF background and annotations", Modifier.fillMaxWidth().heightIn(max = 560.dp)) } ?: Text("Rendering the exported sample…")
        }
    }
}
