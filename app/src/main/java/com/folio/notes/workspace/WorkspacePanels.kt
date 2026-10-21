@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.model.hiddenFromLibrary
import com.folio.notes.ui.FolioSpacing

/** Controls that accompany notebook selection in the Library, without a second browser. */
@Composable internal fun WorkspaceLibraryControls(
    purpose: PickerPurpose,
    state: FolioState,
    model: FolioViewModel,
    onOpenNotebook: (String) -> Unit,
) {
    when (purpose) {
        PickerPurpose.TABS -> {
            val openNotes = state.tabs.mapNotNull { tab -> state.notes.find { it.id == tab.notebookId && !it.hiddenFromLibrary } }
            if (openNotes.isNotEmpty()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
            ) {
                openNotes.forEach { note ->
                    InputChip(
                        selected = note.id == state.activeId,
                        onClick = { onOpenNotebook(note.id) },
                        label = { Text(note.title.ifBlank { "Untitled" }, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            IconButton({ model.closeTab(note.id) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Close, "Close ${note.title.ifBlank { "Untitled" }}")
                            }
                        },
                    )
                }
            }
        }
        PickerPurpose.OPEN -> Unit
    }
}
