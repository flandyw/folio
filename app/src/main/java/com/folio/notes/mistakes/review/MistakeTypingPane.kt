@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.mistakes.richtext.RichText
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageLayers
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

/**
 * A sheet of paper to type on. The field owns selection/composition; the document remains
 * the single source of saved text, written on every change through the serialized writer.
 */
@Composable internal fun MistakeTypingPane(
    page: NotePage?, reviewId: String, state: FolioState, folio: FolioViewModel,
    busy: Boolean, focusRequest: Int, compact: Boolean, revealed: Boolean,
    onCompare: (() -> Unit)?, onDone: () -> Unit, onFocusChanged: (Boolean) -> Unit,
) {
    if (page == null || !page.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(28.dp))
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    val box = remember(page, reviewId) { MistakeTypedResponse.box(page, reviewId) }
    val editable = PageLayers.editable(page.layers, box.layer)
    var value by rememberSaveable(reviewId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(box.text, TextRange(box.text.length)))
    }
    var preview by rememberSaveable(reviewId) { mutableStateOf(false) }
    var autoPreviewed by rememberSaveable(reviewId) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Changes made on the canvas (including clearing a response) also appear here. A blank
    // answer has no text box, so leading spaces or newlines being typed are not wiped out.
    LaunchedEffect(box.text) {
        if (value.text != box.text && !(box.text.isEmpty() && value.text.isBlank())) value = TextFieldValue(box.text,
            TextRange(value.selection.start.coerceIn(0, box.text.length), value.selection.end.coerceIn(0, box.text.length)))
    }
    var editRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusRequest, editRequest, editable, preview) {
        if ((focusRequest > 0 || editRequest > 0) && editable && !preview) focus.requestFocus()
    }
    // Revealing the correction shows the answer typeset beside it; one tap goes back to editing.
    LaunchedEffect(revealed) {
        if (revealed && !autoPreviewed && value.text.isNotBlank()) { autoPreviewed = true; preview = true }
    }
    fun update(next: TextFieldValue) {
        if (busy || !editable) return
        if (next.text != value.text) folio.commitText(page.id, box.copy(text = next.text))
        value = next
    }
    fun apply(edit: TypedEdit) = update(TextFieldValue(edit.text, TextRange(edit.start, edit.end)))
    val current = TypedEdit(value.text, value.selection.start, value.selection.end)
    val showPreview = preview && value.text.isNotBlank()

    Column(Modifier.fillMaxSize().padding(horizontal = FolioSpacing.dp16)
        .padding(top = if (compact) FolioSpacing.dp4 else FolioSpacing.dp12, bottom = FolioSpacing.dp8),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        if (!editable) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = colors.error)
            Text("This answer is on a hidden or locked layer. Switch to Write to unlock it.",
                style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        val borderColor by animateColorAsState(when {
            state.saveFailed -> colors.error
            focused -> colors.primary
            else -> colors.outlineVariant
        }, label = "answerBorder")
        val borderWidth by animateDpAsState(if (focused) 2.dp else 1.dp, label = "answerBorderWidth")
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = FolioShapes.large,
            color = colors.surfaceContainerLowest, border = BorderStroke(borderWidth, borderColor)) {
            if (showPreview) SelectionContainer {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24)) {
                    RichText(value.text, style = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface))
                }
            } else BasicTextField(
                value = value,
                onValueChange = { raw ->
                    val listed = if (raw.composition != null) null
                        else TypingBar.continueList(current, TypedEdit(raw.text, raw.selection.start, raw.selection.end))
                    if (listed == null || listed.text == raw.text) update(raw) else apply(listed)
                },
                modifier = Modifier.fillMaxSize().focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused) }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false
                        else if (event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed) && onCompare != null) { onCompare(); true }
                        else if (event.key == Key.Escape) { onDone(); true }
                        else false
                    }
                    .semantics { contentDescription = "Your typed answer" },
                readOnly = busy || !editable,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, lineHeight = 1.6.em),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { field ->
                    Box(Modifier.padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp16)) {
                        if (value.text.isEmpty()) Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Text("Type your answer and how you got there…",
                                style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                            Text("Maths goes between \$ signs, like \$x^2 + 1\$ — or use the maths keys below.",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = .7f))
                        }
                        field()
                    }
                },
            )
        }
        AnimatedVisibility(focused && editable && !showPreview && !busy,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
            TypingKeys(current, onInsert = { apply(TypingBar.insert(current, it)) }, onList = { apply(TypingBar.toggleList(current)) })
        }
        if (!compact || state.saveFailed) AnswerFooter(value.text, state, busy, showPreview, onRetry = folio::retrySave,
            onPreview = { show ->
                if (show) onDone()
                preview = show
                if (!show) editRequest++
            })
    }
}

/** Save state and word count on the left, the Edit / Preview switch on the right. */
@Composable private fun AnswerFooter(text: String, state: FolioState, busy: Boolean, preview: Boolean,
    onRetry: () -> Unit, onPreview: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        val words = MistakeTypedResponse.words(text)
        when {
            state.saveFailed -> {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = colors.error)
                Text("Not saved", style = MaterialTheme.typography.labelMedium, color = colors.error)
                TextButton(onRetry, enabled = !state.retryingSave && !busy, shapes = ButtonDefaults.shapes()) {
                    Text(if (state.retryingSave) "Retrying…" else "Retry save")
                }
            }
            else -> {
                Icon(if (state.saving) Icons.Rounded.CloudSync else Icons.Rounded.Check, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text(when {
                    state.saving -> "Saving…"
                    words == 0 -> "Saves as you type"
                    else -> "Saved · $words ${if (words == 1) "word" else "words"}"
                }, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(selected = !preview, onClick = { onPreview(false) }, enabled = !busy,
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) }) { Text("Edit") }
            SegmentedButton(selected = preview, onClick = { onPreview(true) }, enabled = !busy && text.isNotBlank(),
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Rounded.Visibility, null, Modifier.size(16.dp)) }) { Text("Preview") }
        }
    }
}

/** Keys that are slow on a phone keyboard. They never take focus, so the caret stays put. */
@Composable private fun TypingKeys(edit: TypedEdit, onInsert: (TypingSnippet) -> Unit, onList: () -> Unit) {
    val inMaths = TypingBar.insideMath(edit.text, edit.start)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6), verticalAlignment = Alignment.CenterVertically) {
        val keyModifier = Modifier.focusProperties { canFocus = false }
        FilledTonalButton({ onInsert(TypingBar.MATHS) }, modifier = keyModifier.heightIn(min = 40.dp),
            contentPadding = PaddingValues(horizontal = FolioSpacing.dp12), shapes = ButtonDefaults.shapes()) {
            Icon(if (inMaths) Icons.Rounded.KeyboardTab else Icons.Rounded.Functions, null, Modifier.size(18.dp))
            Spacer(Modifier.width(FolioSpacing.dp6))
            Text(if (inMaths) "Leave maths" else "Maths")
        }
        OutlinedButton(onList, modifier = keyModifier.heightIn(min = 40.dp), contentPadding = PaddingValues(horizontal = FolioSpacing.dp12),
            shapes = ButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "Bulleted list", Modifier.size(18.dp))
        }
        VerticalDivider(Modifier.height(24.dp))
        TypingBar.snippets.forEach { snippet ->
            OutlinedButton({ onInsert(snippet) }, modifier = keyModifier.heightIn(min = 40.dp).widthIn(min = 44.dp)
                .semantics { contentDescription = snippet.description },
                contentPadding = PaddingValues(horizontal = FolioSpacing.dp10), shapes = ButtonDefaults.shapes()) {
                Text(snippet.label, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
