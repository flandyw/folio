@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes.editor.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.EditorOverlayState
import com.folio.notes.editor.toolbar.FolioPopover
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioPopover
import com.folio.notes.ui.FolioShapes

@Composable internal fun EditorRenameDialog(note: Notebook, model: FolioViewModel, overlays: EditorOverlayState) {
    var rename by overlays.rename
    var renameTitle by overlays.renameTitle
    if (rename) {
        val renameFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val renameChanged = renameTitle.isNotBlank() && renameTitle != note.title
        fun saveRename() { if (renameChanged) { model.rename(note, renameTitle); rename = false } }
        FolioPopover("Rename notebook", Icons.Rounded.Edit, onDismiss = { rename = false }, actions = {
            TextButton({ rename = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            Button(::saveRename, enabled = renameChanged, shapes = ButtonDefaults.shapes()) { Text("Save") }
        }) {
            OutlinedTextField(renameTitle, { renameTitle = it }, label = { Text("Notebook title") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveRename() }),
                shape = FolioShapes.large, modifier = Modifier.fillMaxWidth().focusRequester(renameFocus))
            LaunchedEffect(Unit) { renameFocus.requestFocus() }
        }
    }
}
