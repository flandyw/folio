@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale

@Composable internal fun UpdateStatus(state: FolioUpdateState, onDownload: (FolioUpdate) -> Unit, onInstall: () -> Unit, onDelete: () -> Unit) {
    if (state.initializing || state.checking || state.downloading != null || state.ready != null || state.available != null || state.message != null) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            when {
                state.downloading != null -> {
                    Text(if (state.progress.verifying) "Verifying Folio ${state.downloading.versionName}…" else "Downloading Folio ${state.downloading.versionName}…")
                    val percent = state.progress.percent
                    if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    if (!state.progress.verifying) {
                        val speed = state.progress.bytesPerSecond
                        val rate = if (speed >= 1024 * 1024) String.format(Locale.getDefault(), "%.1f MB/s", speed / (1024f * 1024))
                            else "${speed / 1024} KB/s"
                        Text("${percent?.let { "$it% · " }.orEmpty()}$rate", style = MaterialTheme.typography.labelMedium)
                    }
                }
                state.initializing -> Text("Loading downloaded update…")
                state.checking -> Text("Checking for updates…")
            }
            state.ready?.let { ready ->
                Text("Folio ${ready.update.versionName} is ready to install.")
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Button(onInstall, enabled = !state.busy, shapes = ButtonDefaults.shapes()) { Text("Install update") }
                    TextButton(onDelete, enabled = !state.busy, shapes = ButtonDefaults.shapes()) { Text("Delete download") }
                }
            }
            state.downloadCandidate?.takeIf { state.downloading == null }?.let { update ->
                Text("Folio ${update.versionName} is available.")
                Button({ onDownload(update) }, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                    Text(if (state.ready != null) "Download newer update" else "Download update")
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
