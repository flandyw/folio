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
import androidx.compose.ui.text.input.KeyboardType
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.EditorOverlayState
import com.folio.notes.editor.toolbar.FolioPopover
import com.folio.notes.model.Notebook
import com.folio.notes.model.displayTitle
import com.folio.notes.ui.FolioPopover
import com.folio.notes.ui.FolioShapes

@Composable internal fun EditorPageDialogs(note: Notebook, model: FolioViewModel, overlays: EditorOverlayState) {
    var namedPage by overlays.namedPage
    var pageTitle by overlays.pageTitle
    var movingPage by overlays.movingPage
    var destinationPage by overlays.destinationPage
    var deletingPage by overlays.deletingPage
        namedPage?.let { target ->
            val nameFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            val changed = pageTitle != target.title
            fun savePageName() { if (changed) { model.renamePage(target.id, pageTitle); namedPage = null } }
            FolioPopover("Name page", Icons.Rounded.Edit, onDismiss = { namedPage = null }, actions = {
                TextButton({ namedPage = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button(::savePageName, enabled = changed, shapes = ButtonDefaults.shapes()) { Text("Save") }
            }) {
                OutlinedTextField(pageTitle, { pageTitle = it.take(120) }, label = { Text("Page name") },
                    placeholder = { Text("e.g. Quadratics homework") },
                    supportingText = { Text("${pageTitle.length}/120 · Leave blank to use the page number.") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { savePageName() }),
                    shape = FolioShapes.large, modifier = Modifier.fillMaxWidth().focusRequester(nameFocus))
                LaunchedEffect(target.id) { nameFocus.requestFocus() }
            }
        }
        movingPage?.let { pageId ->
            val destination = destinationPage.toIntOrNull()
            val moveFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            fun commit() {
                val from = note.pages.indexOfFirst { it.id == pageId }
                if (from >= 0 && destination != null && destination in 1..note.pages.size) model.movePage(from, destination - 1)
                movingPage = null
            }
            FolioPopover("Move page", Icons.Rounded.LowPriority, onDismiss = { movingPage = null }, actions = {
                TextButton({ movingPage = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button(::commit, enabled = destination != null && destination in 1..note.pages.size, shapes = ButtonDefaults.shapes()) { Text("Move") }
            }) {
                OutlinedTextField(destinationPage, { destinationPage = it.filter(Char::isDigit).take(9) },
                    label = { Text("New position (1–${note.pages.size})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    shape = FolioShapes.large, modifier = Modifier.fillMaxWidth().focusRequester(moveFocus))
                LaunchedEffect(pageId) { moveFocus.requestFocus() }
            }
        }
        deletingPage?.let { pageId ->
            val index = note.pages.indexOfFirst { it.id == pageId }
            FolioPopover("Delete ${note.pages.getOrNull(index)?.displayTitle(index) ?: "page"}?", Icons.Rounded.DeleteOutline,
                onDismiss = { deletingPage = null }, actions = {
                    TextButton({ deletingPage = null }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                    Button({ if (index >= 0 && note.pages.size > 1) model.deletePage(index); deletingPage = null },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        shapes = ButtonDefaults.shapes()) { Text("Delete") }
                }) { Text("This removes the page and its content. Use Undo to restore it during this session.", style = MaterialTheme.typography.bodyMedium) }
        }

}
