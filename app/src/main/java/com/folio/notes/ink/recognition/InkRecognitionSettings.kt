@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.ink.recognition

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable internal fun InkRecognitionModelStatus(model: InkGestureRecognizer.Model, enabled: Boolean) {
    val state by InkGestureRecognizer.availability(model).collectAsState()
    LaunchedEffect(model, enabled) { if (enabled) InkGestureRecognizer.prepare(model) }
    Text(when (state) {
        InkGestureRecognizer.Availability.NOT_STARTED -> "Enable to download on-device recognition."
        InkGestureRecognizer.Availability.DOWNLOADING -> "Downloading recognition model. Keep writing while it downloads."
        InkGestureRecognizer.Availability.READY -> "Recognition is ready and works offline."
        InkGestureRecognizer.Availability.UNAVAILABLE -> "Recognition model unavailable. Connect to the internet and retry."
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state == InkGestureRecognizer.Availability.UNAVAILABLE && enabled) {
        TextButton({ InkGestureRecognizer.prepare(model, retry = true) }, shapes = ButtonDefaults.shapes()) { Text("Retry model download") }
    }
}
