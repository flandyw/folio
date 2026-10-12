@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable internal fun EditorClearDialog(model: FolioViewModel, overlays: EditorOverlayState, interaction: EditorInteractionState) {
    var clear by overlays.clear
    var selectedImage by interaction.selectedImage
    if (clear) FolioPopover("Clear this page?", Icons.Rounded.LayersClear, onDismiss = { clear = false }, actions = {
        TextButton({ clear = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        Button({ model.clearPage(); selectedImage = null; clear = false }, shapes = ButtonDefaults.shapes()) { Text("Clear page") }
    }) { Text("Your paper or PDF stays in place. Ink, text and pictures are removed. You can undo this change.", style = MaterialTheme.typography.bodyMedium) }

}
