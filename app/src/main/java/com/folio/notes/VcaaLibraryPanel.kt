package com.folio.notes

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_PDF_BYTES = 100L * 1024 * 1024

/** Fetches one official paper. Only HTTPS from VCAA's own site is accepted, and the bytes must be a PDF. */
private suspend fun downloadVcaaPdf(url: String, target: File) = withContext(Dispatchers.IO) {
    val source = URL(url)
    require(source.protocol == "https" && (source.host == "vcaa.vic.edu.au" || source.host.endsWith(".vcaa.vic.edu.au"))) { "Not a VCAA address" }
    val connection = (source.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", "Folio")
    }
    try {
        if (connection.responseCode !in 200..299) error("VCAA answered ${connection.responseCode}")
        var total = 0L
        connection.inputStream.use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PDF_BYTES) error("This file is larger than expected")
                output.write(buffer, 0, read)
            }
        } }
        val head = ByteArray(5)
        check(total >= 5 && target.inputStream().use { it.read(head) } == 5 && String(head, Charsets.US_ASCII) == "%PDF-") { "VCAA did not send a PDF" }
    } catch (e: Exception) { target.delete(); throw e }
    finally { connection.disconnect() }
}

/**
 * Focal's VCAA exam library: every official past paper by study, downloaded straight from VCAA and
 * imported as a PDF notebook with its subject, year, source, paper and marks already filled in.
 */
@Composable
internal fun VcaaLibraryPanel(state: FolioState, model: FolioViewModel, folderId: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val exams by produceState<List<VcaaExam>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { VcaaLibrary.parse(context.assets.open("vcaa-exam-resources.json").use { it.readBytes().toString(Charsets.UTF_8) }) }
                .getOrDefault(emptyList())
        }
    }
    val all = exams.orEmpty()
    // Studies you already keep notebooks for come first.
    val studies = remember(all, state.notes) {
        val yours = state.notes.map { it.exam.subjectLabel.lowercase() }.toSet()
        all.map { it.studyName }.distinct().sortedWith(compareBy<String> { name ->
            if (name.lowercase() in yours || VcaaLibrary.subjectFor(name)?.label?.lowercase() in yours) 0 else 1
        }.thenBy { it.lowercase() })
    }
    var study by rememberSaveable { mutableStateOf<String?>(null) }
    val current = study ?: studies.firstOrNull()
    var studyMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val shown = remember(all, current, query) {
        val needle = query.trim()
        all.filter { (needle.isNotEmpty() || it.studyName == current) &&
            "${it.studyName} ${it.year ?: ""} ${it.provider} ${it.label}".contains(needle, ignoreCase = true) }
    }
    val downloading = progress != null

    fun download() {
        val chosen = all.filter { it.url in picked }
        if (chosen.isEmpty() || downloading) return
        error = null
        scope.launch {
            val dir = File(context.cacheDir, "vcaa").apply { mkdirs() }
            val files = mutableListOf<File>()
            val items = mutableListOf<PendingPdfImport>()
            val failed = mutableListOf<String>()
            try {
                chosen.forEachIndexed { index, exam ->
                    progress = "Downloading ${index + 1} of ${chosen.size}…"
                    val file = File(dir, "${java.util.UUID.randomUUID()}.pdf")
                    try {
                        downloadVcaaPdf(exam.url, file)
                        files += file
                        items += PendingPdfImport(Uri.fromFile(file), exam.title, exam.tags, detected = true,
                            filename = exam.url.substringAfterLast('/'))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed += "${exam.title}: ${e.message ?: "download failed"}" }
                }
            } catch (e: CancellationException) { files.forEach { it.delete() }; throw e }
            finally { progress = null }
            if (failed.isNotEmpty()) error = "Could not download:\n" + failed.joinToString("\n")
            if (items.isEmpty()) return@launch
            picked = emptyList()
            model.importDownloadedPdfs(folderId, items, files)
            if (failed.isEmpty()) onDismiss()
        }
    }

    FolioPanel("VCAA exam library", onDismiss) {
        Column(Modifier.padding(horizontal = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text("Official past papers from VCAA. Downloaded papers become PDF notebooks with their exam details filled in.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton({ studyMenu = true }, Modifier.fillMaxWidth(), enabled = query.isBlank(), shape = FolioShapes.large) {
                        Icon(Icons.Rounded.School, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(FolioSpacing.dp10))
                        Text(current ?: "Loading…", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    FolioMenuPopover(studyMenu, { studyMenu = false }, title = "Study") {
                        studies.forEach { name -> FolioMenuItem({ Text(name) }, { study = name; studyMenu = false }, selected = name == current) }
                    }
                }
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search all papers") })
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8)) {
            if (exams == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(FolioSpacing.dp8)) }
            else if (shown.isEmpty()) item { EmptyHint("No papers match.", Modifier.padding(FolioSpacing.dp8)) }
            items(shown, key = { it.url }) { exam ->
                val owned = VcaaLibrary.imported(exam, state.notes)
                val checked = exam.url in picked
                Row(Modifier.fillMaxWidth().clip(FolioShapes.medium)
                    .clickable(enabled = !downloading) { picked = if (checked) picked - exam.url else picked + exam.url }
                    .padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, null, enabled = !downloading)
                    Spacer(Modifier.width(FolioSpacing.dp8))
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(exam.year?.toString(), exam.provider.takeIf { it != "VCAA" }?.removePrefix("VCAA "), exam.type.label)
                            .joinToString(" · ") + if (query.isNotBlank()) " · ${exam.studyName}" else "", style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(exam.label, exam.tags.marksTotal?.let { "$it marks" }, owned?.let { "In your library" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    exam.reports.firstOrNull()?.let { (label, url) ->
                        TextButton({ runCatching { uriHandler.openUri(url) } }) {
                            Text(if (Regex("(?i)assessment guide").containsMatchIn(label)) "Guide" else "Report")
                        }
                    }
                    if (owned != null) TextButton({ onDismiss(); model.open(owned.id) }) { Text("Open") }
                }
            }
        }
        Column(Modifier.padding(start = FolioSpacing.dp24, end = FolioSpacing.dp24, bottom = FolioSpacing.dp16),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(::download, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty() && !downloading && !state.busy) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text(progress ?: if (picked.isEmpty()) "Choose papers to download" else "Download and import ${picked.size}")
            }
        }
    }
}
