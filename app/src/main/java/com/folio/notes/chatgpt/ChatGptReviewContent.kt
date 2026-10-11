@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.chatgpt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.FolioMenuItem
import com.folio.notes.FolioMenuPopover
import com.folio.notes.FolioPanel
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing
import com.folio.notes.NotePage
import com.folio.notes.Notebook
import com.folio.notes.StickyNotes
import com.folio.notes.math.KaTeXText

@Composable internal fun ChatGptPageCard(
    note: Notebook?, page: NotePage?, pageIndex: Int, image: ChatGptPageImage?, error: String?, wide: Boolean,
    modifier: Modifier, onRetry: () -> Unit, enabled: Boolean,
) {
    var expanded by rememberSaveable(note?.id, page?.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(modifier, shape = FolioShapes.extraLarge, color = scheme.surfaceContainerLow) {
        if (note == null) Column(Modifier.padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Icon(Icons.Rounded.MenuBook, null, Modifier.size(32.dp), tint = scheme.primary)
            Text("Your page, with a second pair of eyes", style = MaterialTheme.typography.titleMedium)
            Text("Open a notebook, then tap the sparkle button beside Share and choose Review page with ChatGPT. You can connect your account here first.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        } else {
            val picture: @Composable (Modifier) -> Unit = { pictureModifier ->
                Surface(pictureModifier, shape = FolioShapes.medium, color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (image != null) Image(image.bitmap.asImageBitmap(), "Preview of ${note.title}, page ${pageIndex + 1}, as shared for review",
                            Modifier.fillMaxSize().padding(FolioSpacing.dp8), contentScale = ContentScale.Fit)
                        else if (error == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.BrokenImage, null, tint = scheme.onSurfaceVariant)
                    }
                }
            }
            val caption: @Composable ColumnScope.() -> Unit = {
                Text("CURRENT PAGE", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Page ${pageIndex + 1}${page?.title?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (page?.infinite == true) "The whole canvas is shared. Zooming here does not change the image sent."
                    else "Visible writing, pictures and the PDF background. Side notes outside the page stay here.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    TextButton(onRetry, enabled = enabled) { Text("Retry preview") }
                } else TextButton({ expanded = true }, enabled = image != null) {
                    Icon(Icons.Rounded.OpenInFull, null, Modifier.size(16.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Inspect page")
                }
            }
            if (wide) Column(Modifier.fillMaxSize().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                picture(Modifier.fillMaxWidth().weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4), content = caption)
            } else Row(Modifier.padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically) {
                picture(Modifier.width(88.dp).height(128.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4), content = caption)
            }
        }
    }
    if (expanded && image != null) FolioPanel("Page ${pageIndex + 1} · ${note?.title.orEmpty()}", { expanded = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Image(image.bitmap.asImageBitmap(), "Full page preview", Modifier.fillMaxWidth().aspectRatio(
                image.bitmap.width.toFloat() / image.bitmap.height), contentScale = ContentScale.Fit)
            Text("This is the whole-page image used for feedback.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun ChatGptRequestForm(
    intent: ChatGptReviewIntent, details: String, custom: String, enabled: Boolean,
    onIntent: (ChatGptReviewIntent) -> Unit, onDetails: (String) -> Unit, onCustom: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        Text("What would help you most?", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            ChatGptReviewIntent.entries.forEach { option ->
                FilterChip(intent == option, { onIntent(option) }, label = { Text(option.label) }, enabled = enabled,
                    leadingIcon = if (intent == option) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
            }
        }
        val own = intent == ChatGptReviewIntent.CUSTOM
        OutlinedTextField(if (own) custom else details, { if (own) onCustom(it.take(4000)) else onDetails(it.take(3000)) },
            modifier = Modifier.fillMaxWidth(), enabled = enabled, minLines = 2, maxLines = 8, shape = FolioShapes.large,
            label = { Text(if (own) "Your feedback request" else "Anything to focus on? (optional)") },
            placeholder = { Text(if (own) "Tell ChatGPT what you'd like checked…" else "Add the question, criteria or a specific step…") },
            supportingText = { Text(if (own) "Ask for specific feedback on the selected pages." else when (intent) {
                ChatGptReviewIntent.CHECK -> "Check the steps, explain mistakes, suggest improvements and give a mark if the page shows one."
                ChatGptReviewIntent.EXPLAIN -> "Make the ideas and confusing steps easier to understand."
                ChatGptReviewIntent.MARK -> "Add a rubric or marking guide for evidence-based assessment."
                ChatGptReviewIntent.ERRORS -> "Find recurring errors and the misunderstanding behind them."
                ChatGptReviewIntent.CONNECTIONS -> "Connect topics, reconcile notation and find missing steps."
                ChatGptReviewIntent.PRACTICE -> "Generate progressively harder questions with separate answer notes."
                ChatGptReviewIntent.FLASHCARDS -> "Turn the important ideas into focused question-and-answer cards."
                ChatGptReviewIntent.PLAN -> "Prioritize study tasks with time estimates and mastery checks."
                ChatGptReviewIntent.WRITING -> "Improve argument, evidence, structure and clarity."
                ChatGptReviewIntent.SUMMARY -> "Combine the pages into concise notes, formulas and examples."
                else -> "Find key ideas, gaps and questions for revision."
            }) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        Text("You'll choose which suggestions become sticky notes. Nothing is added automatically.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ChatGptModelPicker(state: ChatGptState, manager: ChatGptManager) {
    var menu by remember { mutableStateOf(false) }
    val selected = state.models.firstOrNull { it.slug == state.model }
    Box {
        OutlinedButton({ menu = true }, Modifier.fillMaxWidth(), enabled = !state.busy, shape = FolioShapes.large) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(horizontal = FolioSpacing.dp12), horizontalAlignment = Alignment.Start) {
                Text("Model", style = MaterialTheme.typography.labelSmall)
                Text(selected?.name ?: "Choose an available model", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
        }
        FolioMenuPopover(menu, { menu = false }, title = "Choose a model") {
            state.models.forEach { available ->
                FolioMenuItem({ Text(available.name) }, { manager.selectModel(available.slug); menu = false }, selected = available.slug == state.model)
            }
            FolioMenuItem({ Text("Refresh available models") }, { menu = false; manager.refreshModels() }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) })
        }
    }
}

@Composable internal fun ChatGptReasoningPicker(state: ChatGptState, manager: ChatGptManager) {
    val model = state.models.firstOrNull { it.slug == state.model }
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
        if (!model?.efforts.isNullOrEmpty()) Box {
            OutlinedButton({ menu = true }, Modifier.fillMaxWidth(), enabled = !state.busy, shape = FolioShapes.large) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(horizontal = FolioSpacing.dp12), horizontalAlignment = Alignment.Start) {
                    Text("Reasoning level", style = MaterialTheme.typography.labelSmall)
                    Text(ChatGptInference.effortLabel(state.effort))
                }
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
            }
            FolioMenuPopover(menu, { menu = false }, title = "Reasoning level") {
                FolioMenuItem({ Text("Model default") }, { manager.selectEffort(null); menu = false }, selected = state.effort == null)
                model?.efforts.orEmpty().forEach { effort ->
                    FolioMenuItem({ Text(ChatGptInference.effortLabel(effort)) }, { manager.selectEffort(effort); menu = false }, selected = state.effort == effort)
                }
            }
        }
        if (!model?.efforts.isNullOrEmpty()) Text("Lower levels favor speed. Higher levels allow more thinking and can use more of your plan.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text("Using ChatGPT plan", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChatGptUsageLink()
        }
    }
}

@Composable internal fun ChatGptFeedbackContent(feedback: ChatGptFeedback, samePage: Boolean, enabled: Boolean, manager: ChatGptManager) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        if (feedback.applied) ChatGptNotice(Icons.Rounded.CheckCircle, "Feedback added beside the page. You can move or edit the notes, or undo them together in the notebook.")
        Text(if (feedback.applied) "Your feedback" else "Choose what to keep", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text("${feedback.notebookTitle} · ${feedback.pageLabel} · ${feedback.modelName}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!feedback.applied) {
            Text("Select useful suggestions and edit the wording before adding them.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!samePage) ChatGptNotice(Icons.Rounded.Info, "The reviewed page has changed or is no longer open. Review the current page before adding notes.")
        }
        if (!feedback.applied) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ feedback.drafts.indices.forEach { manager.updateDraft(feedback.request, it, included = true) } }, enabled = enabled) { Text("Select all") }
            TextButton({ feedback.drafts.indices.forEach { manager.updateDraft(feedback.request, it, included = false) } }, enabled = enabled) { Text("Deselect all") }
        }
        feedback.drafts.forEachIndexed { index, draft ->
            if (feedback.applied && !draft.included) return@forEachIndexed
            var editing by rememberSaveable(feedback.request, index) { mutableStateOf(false) }
            val paper = if (draft.included) Color(StickyNotes.COLOR) else MaterialTheme.colorScheme.surfaceContainerLow
            val ink = if (draft.included) Color(0xFF302918) else MaterialTheme.colorScheme.onSurface
            Surface(shape = FolioShapes.large, color = paper, contentColor = ink,
                border = BorderStroke(1.dp, if (draft.included) Color(StickyNotes.EDGE) else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!feedback.applied) Checkbox(draft.included, { manager.updateDraft(feedback.request, index, included = it) }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "Include sticky note ${index + 1}" },
                            colors = CheckboxDefaults.colors(checkedColor = ink, checkmarkColor = paper, uncheckedColor = ink))
                        else Icon(Icons.Rounded.Note, null, Modifier.padding(FolioSpacing.dp12).size(20.dp))
                        Text("Note ${index + 1}${if (!draft.included) " · not selected" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        if (!feedback.applied) IconButton({ editing = !editing }, enabled = enabled) {
                            Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, if (editing) "Finish editing note ${index + 1}" else "Edit note ${index + 1}", Modifier.size(20.dp))
                        }
                    }
                    if (editing && !feedback.applied) OutlinedTextField(draft.text, { manager.updateDraft(feedback.request, index, text = it) },
                        enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, shape = FolioShapes.medium,
                        isError = draft.included && draft.text.isBlank(),
                        supportingText = { Text(if (draft.included && draft.text.isBlank()) "Add text or deselect this note." else "${draft.text.length}/800", color = ink) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = ink, unfocusedTextColor = ink, disabledTextColor = ink.copy(alpha = .5f),
                            cursorColor = ink, focusedBorderColor = ink, unfocusedBorderColor = ink.copy(alpha = .4f), focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent, errorTextColor = ink))
                    else SelectionContainer {
                        KaTeXText(draft.text.ifBlank { "Empty note — edit it or leave it unselected." },
                            Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp8),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink))
                    }
                }
            }
        }
        Text("AI feedback can miss details. Check each suggestion against your work.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ChatGptNotice(icon: ImageVector, text: String, error: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = FolioShapes.large, color = if (error) scheme.errorContainer else scheme.surfaceContainerLow,
        contentColor = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun ChatGptErrorCard(state: ChatGptState, onDismiss: () -> Unit, onRetry: () -> Unit, canRetry: Boolean) {
    var details by remember(state.error) { mutableStateOf(false) }
    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(22.dp))
                Text(when (state.operation) {
                    ChatGptOperation.REVIEW -> "This review couldn't finish"
                    ChatGptOperation.SIGN_IN -> "Sign-in needs another step"
                    ChatGptOperation.MODELS -> "Models couldn't be loaded"
                    ChatGptOperation.TEST, ChatGptOperation.CONNECTION -> "The connection check couldn't finish"
                    else -> "Something needs attention"
                }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Dismiss error", Modifier.size(18.dp)) }
            }
            Text(if (state.operation == ChatGptOperation.REVIEW) "Your writing is unchanged. You can try this review again." else "Your saved account stays here. Open the details for the exact error.",
                style = MaterialTheme.typography.bodyMedium)
            if (details) SelectionContainer { Text(state.error.orEmpty(), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                if (canRetry) TextButton(onRetry) { Text("Try again") }
                TextButton({ details = !details }) { Text(if (details) "Hide details" else "Show details") }
            }
        }
    }
}
