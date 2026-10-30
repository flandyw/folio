package com.folio.notes.editor.dialogs

import androidx.compose.runtime.*
import com.folio.notes.app.FolioViewModel
import com.folio.notes.app.NameDialog
import com.folio.notes.editor.EditorOverlayState
import com.folio.notes.model.Notebook

@Composable internal fun EditorRenameDialog(note: Notebook, model: FolioViewModel, overlays: EditorOverlayState) {
    var rename by overlays.rename
    if (rename) NameDialog("Rename notebook", "", note.title, "Save", { rename = false }) { model.rename(note, it); rename = false }
}
