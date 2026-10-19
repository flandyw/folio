@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.folio.notes.*

/** The field owns selection/composition; the document remains the single source of saved text. */
@Composable internal fun MistakeTypingPane(
    page: NotePage?, reviewId: String, state: FolioState, folio: FolioViewModel,
    busy: Boolean, focusRequest: Int, compact: Boolean, onFocusChanged: (Boolean) -> Unit,
) {
    if (page == null || !page.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(28.dp))
        }
        return
    }
    val box = remember(page, reviewId) { MistakeTypedResponse.box(page, reviewId) }
    val editable = PageLayers.editable(page.layers, box.layer)
    var value by rememberSaveable(reviewId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(box.text, TextRange(box.text.length)))
    }
    var preview by rememberSaveable(reviewId) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Changes made on the canvas (including clearing a response) also appear here.
    LaunchedEffect(box.text) {
        if (value.text != box.text) value = TextFieldValue(box.text,
            TextRange(value.selection.start.coerceIn(0, box.text.length), value.selection.end.coerceIn(0, box.text.length)))
    }
    LaunchedEffect(focusRequest, editable, preview) {
        if (focusRequest > 0 && editable && !preview) focus.requestFocus()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = FolioSpacing.dp16).padding(bottom = FolioSpacing.dp8)) {
        if (!compact || state.saveFailed) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(if (state.saveFailed) Icons.Rounded.ErrorOutline else Icons.Rounded.Check,
                null, Modifier.size(16.dp), tint = if (state.saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                state.saveFailed -> "Response not saved"
                state.saving -> "Saving…"
                value.text.isBlank() -> "Answers save on this device"
                else -> "Saved on this device"
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (state.saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.saveFailed) TextButton(folio::retrySave, enabled = !state.retryingSave && !busy, shapes = ButtonDefaults.shapes()) {
                Text(if (state.retryingSave) "Retrying…" else "Retry save")
            } else if (value.text.isNotBlank()) TextButton({
                keyboard?.hide()
                focusManager.clearFocus()
                preview = !preview
            }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                Icon(if (preview) Icons.Rounded.Edit else Icons.Rounded.Visibility, null, Modifier.size(16.dp))
                Spacer(Modifier.width(FolioSpacing.dp4))
                Text(if (preview) "Edit answer" else "Preview")
            } else Spacer(Modifier.height(48.dp))
        }
        if (!editable) Text("This response’s layer is hidden or locked. Switch to Write to change its layers.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (preview) {
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = FolioShapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp16)) {
                        RichText(value.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } else OutlinedTextField(
            value = value,
            onValueChange = { next ->
                if (!busy && editable) {
                    if (next.text != value.text) folio.commitText(page.id, box.copy(text = next.text))
                    value = next
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(focus)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            readOnly = busy || !editable,
            label = { Text("Your answer") },
            placeholder = { Text("Type your answer and reasoning…") },
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = FolioShapes.large,
        )
    }
}
