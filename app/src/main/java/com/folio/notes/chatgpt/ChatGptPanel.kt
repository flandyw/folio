@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes.chatgpt

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.app.FolioViewModel
import com.folio.notes.model.PageLayers
import com.folio.notes.model.hiddenFromLibrary
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A review workspace: page sharing and the eventual notebook edit are separate actions. */
@Composable internal fun ChatGptPanel(manager: ChatGptManager, model: FolioViewModel, onDismiss: () -> Unit, startWithAccount: Boolean = false) {
    val state by manager.state.collectAsStateWithLifecycle()
    val folio by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var accountTab by rememberSaveable { mutableStateOf(startWithAccount) }
    var intentName by rememberSaveable { mutableStateOf(ChatGptReviewIntent.CHECK.name) }
    var details by rememberSaveable { mutableStateOf("") }
    var custom by rememberSaveable { mutableStateOf("") }
    var depth by rememberSaveable { mutableIntStateOf(8) }
    var hintsOnly by rememberSaveable { mutableStateOf(false) }
    var followUp by rememberSaveable(state.feedback?.request) { mutableStateOf("") }
    var previewRetry by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val intent = ChatGptReviewIntent.valueOf(intentName)
    val prompt = ChatGptReview.prompt(intent, details, custom, depth, hintsOnly)
    val account = state.accounts.firstOrNull { it.client == state.active }
    val note = folio.active?.takeUnless { it.hiddenFromLibrary }
    val page = folio.page?.takeIf { note != null && it.loaded }
    var firstPage by rememberSaveable(note?.id, page?.id) { mutableStateOf((folio.pageIndex + 1).toString()) }
    var lastPage by rememberSaveable(note?.id, page?.id) { mutableStateOf((folio.pageIndex + 1).toString()) }
    val first = firstPage.toIntOrNull()
    val last = lastPage.toIntOrNull()
    val validRange = note != null && first != null && last != null &&
        first >= 1 && last <= note.pages.size && first <= folio.pageIndex + 1 &&
        last >= folio.pageIndex + 1 && last - first < 10
    val reviewPages = if (validRange) note!!.pages.subList(first!! - 1, last!!) else emptyList()
    var image by remember(note?.id, page, previewRetry) { mutableStateOf<ChatGptPageImage?>(null) }
    var previewError by remember(note?.id, page, previewRetry) { mutableStateOf<String?>(null) }
    var applyError by remember(state.feedback?.request, page) { mutableStateOf<String?>(null) }
    val feedback = state.feedback
    val samePage = feedback != null && note?.id == feedback.notebook && page == feedback.page
    val selected = feedback?.let { ChatGptReview.selected(it.drafts) }
    val layerEditable = page?.let { PageLayers.editable(it.layers, model.activeLayerOf(it)) } == true
    val ready = account?.connected == true && account.plan && state.model != null && page != null && image != null && validRange

    LaunchedEffect(manager) { manager.prepare() }
    LaunchedEffect(note?.id, page, previewRetry) {
        if (note != null && page != null) {
            try { image = ChatGptPageImage.render(note, page, model.repository, longestSide = 1000) }
            catch (e: CancellationException) { throw e }
            catch (e: ChatGptFailure) { previewError = e.message }
            catch (_: Exception) { previewError = "This page's preview could not be loaded. Try again." }
        }
    }

    fun review() {
        if (ready && (if (intent == ChatGptReviewIntent.CUSTOM) custom.isNotBlank() else true)) {
            focus.clearFocus(); applyError = null
            manager.review(note, page, reviewPages, model.repository, prompt)
        }
    }
    fun connect() { if (account?.pending == true) manager.retryVerification() else manager.signIn() }
    fun retry() {
        when (state.operation) {
            ChatGptOperation.REVIEW -> manager.retryReview()
            ChatGptOperation.SIGN_IN -> connect()
            ChatGptOperation.MODELS -> manager.refreshModels()
            ChatGptOperation.TEST -> manager.test()
            ChatGptOperation.CONNECTION -> manager.checkConnection()
            ChatGptOperation.DISCONNECT -> manager.disconnect()
            ChatGptOperation.ACCOUNT -> state.active?.let(manager::selectAccount)
            ChatGptOperation.OPEN -> Unit
        }
    }

    FolioPanel(if (accountTab) "ChatGPT account" else "Review with ChatGPT", onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            SecondaryTabRow(if (accountTab) 1 else 0, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Tab(!accountTab, { focus.clearFocus(); accountTab = false }, text = { Text("Page review") }, icon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp)) })
                Tab(accountTab, { focus.clearFocus(); accountTab = true }, text = { Text("Account") }, icon = { Icon(Icons.Rounded.AccountCircle, null, Modifier.size(20.dp)) })
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (accountTab) {
                    ChatGptAccountContent(state, manager, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24))
                } else {
                    val wide = maxWidth >= 700.dp && maxHeight >= 320.dp
                    val preview: @Composable (Modifier) -> Unit = { modifier ->
                        ChatGptPageCard(note, page, folio.pageIndex, image, previewError, wide, modifier,
                            onRetry = { previewRetry++ }, enabled = !state.busy)
                    }
                    val content: @Composable (Modifier) -> Unit = { modifier ->
                        val scroll = rememberScrollState()
                        LaunchedEffect(feedback?.request) { if (feedback != null) scroll.animateScrollTo(0) }
                        Column(modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                            if (!wide) preview(Modifier.fillMaxWidth())
                            if (state.error != null) ChatGptErrorCard(state, manager::clearError, ::retry,
                                canRetry = !state.busy && when (state.operation) {
                                    ChatGptOperation.REVIEW -> account?.connected == true && account.plan && state.model != null
                                    ChatGptOperation.TEST -> account?.connected == true && account.plan && state.model != null
                                    ChatGptOperation.OPEN -> false
                                    else -> true
                                })
                            if (feedback != null) {
                                ChatGptFeedbackContent(feedback, samePage, !state.busy, manager)
                                OutlinedTextField(followUp, { followUp = it.take(2000) }, Modifier.fillMaxWidth(),
                                    label = { Text("Ask a follow-up") }, placeholder = { Text("Explain note 2, challenge the marking, or make harder practice…") },
                                    enabled = !state.busy, minLines = 2, maxLines = 4)
                                Text("A follow-up shares the reviewed pages again, your edited feedback and this question. The new response replaces this review; notes already added stay in your notebook.",
                                    style = MaterialTheme.typography.bodySmall)
                                OutlinedButton({ focus.clearFocus(); manager.followUp(followUp) },
                                    enabled = !state.busy && followUp.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue review") }
                                if (!feedback.applied && samePage && !layerEditable) ChatGptNotice(Icons.Rounded.Lock, "Choose an unlocked, visible layer in the notebook before adding feedback.")
                                applyError?.let { ChatGptNotice(Icons.Rounded.ErrorOutline, it, error = true) }
                            } else {
                                if (account?.connected == true && account.plan) {
                                    ChatGptAccountSummary(account, { accountTab = true })
                                    ChatGptModelPicker(state, manager)
                                    ChatGptReasoningPicker(state, manager)
                                } else ChatGptConnectCard(account, !state.busy, ::connect)
                                if (note != null && page != null) {
                                    Text("Pages to review", style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedTextField(firstPage, { firstPage = it }, Modifier.weight(1f),
                                            label = { Text("From page") }, singleLine = true, enabled = !state.busy,
                                            isError = !validRange)
                                        OutlinedTextField(lastPage, { lastPage = it }, Modifier.weight(1f),
                                            label = { Text("To page") }, singleLine = true, enabled = !state.busy,
                                            isError = !validRange)
                                    }
                                    Text("Choose up to 10 pages, including the current page (${folio.pageIndex + 1}). Feedback notes are added to the current page.",
                                        style = MaterialTheme.typography.bodySmall)
                                    ChatGptRequestForm(intent, details, custom, enabled = !state.busy,
                                        onIntent = { intentName = it.name }, onDetails = { details = it }, onCustom = { custom = it })
                                    Text("Feedback depth", style = MaterialTheme.typography.titleSmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(4 to "Quick · 4 notes", 8 to "Thorough · 8 notes", 12 to "Deep · 12 notes").forEach { (count, label) ->
                                            FilterChip(depth == count, { depth = count }, label = { Text(label) }, enabled = !state.busy)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Hints only · let me solve it", Modifier.weight(1f))
                                        Switch(hintsOnly, { hintsOnly = it }, enabled = !state.busy)
                                    }
                                    ChatGptNotice(Icons.Rounded.Lock, "Only the selected pages and your request are shared with OpenAI when you choose Review pages.")
                                }
                            }
                            Spacer(Modifier.height(FolioSpacing.dp4))
                        }
                    }
                    if (wide) Row(Modifier.fillMaxSize().padding(FolioSpacing.dp24), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp24)) {
                        preview(Modifier.weight(.42f).fillMaxHeight())
                        content(Modifier.weight(.58f).fillMaxHeight())
                    } else content(Modifier.fillMaxSize().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp16))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                if (state.busy) {
                    ChatGptProgress(state, manager)
                } else if (accountTab) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ accountTab = false }) { Text("Back to review") }
                    }
                } else {
                    val label: String
                    val enabled: Boolean
                    val action: () -> Unit
                    val icon = when {
                        feedback?.applied == true -> Icons.Rounded.Check
                        feedback != null && samePage -> Icons.AutoMirrored.Rounded.NoteAdd
                        account?.connected != true || !account.plan -> Icons.AutoMirrored.Rounded.Login
                        else -> Icons.Rounded.AutoAwesome
                    }
                    when {
                        feedback?.applied == true -> { label = "Return to notebook"; enabled = true; action = onDismiss }
                        feedback != null && samePage -> {
                            val count = feedback.drafts.count { it.included }
                            label = "Add $count sticky ${if (count == 1) "note" else "notes"}"
                            enabled = selected != null && layerEditable && !adding
                            action = {
                                focus.clearFocus()
                                if (selected != null) scope.launch {
                                    adding = true
                                    try { model.awaitChatGptMath(selected) } finally { adding = false }
                                    if (model.applyChatGptFeedback(feedback.copy(comments = selected))) manager.applied(feedback.request)
                                    else applyError = "The notes could not be added. Check the current page and layer, then try again."
                                }
                            }
                        }
                        note == null -> { label = "Close"; enabled = true; action = onDismiss }
                        account?.connected != true || !account.plan -> { label = connectionLabel(account); enabled = true; action = ::connect }
                        page == null -> { label = "Loading page…"; enabled = false; action = {} }
                        state.model == null -> { label = "Load available models"; enabled = true; action = manager::refreshModels }
                        previewError != null -> { label = "Retry page preview"; enabled = true; action = { previewRetry++ } }
                        image == null -> { label = "Preparing preview…"; enabled = false; action = {} }
                        else -> { label = if (feedback != null) "Review selected pages" else "Review pages"; enabled = ready && (if (intent == ChatGptReviewIntent.CUSTOM) custom.isNotBlank() else true); action = ::review }
                    }
                    if (feedback != null && !feedback.applied && samePage && selected == null)
                        Text(if (feedback.drafts.none { it.included }) "Select at least one note to add." else "Write something in each selected note before adding them.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                        if (feedback != null) TextButton(manager::dismissFeedback, Modifier.weight(1f)) { Text(if (feedback.applied) "Review again" else "Start over") }
                        Button(action, Modifier.weight(if (feedback == null) 1f else 1.6f).heightIn(min = 48.dp), enabled = enabled, shape = FolioShapes.extraLarge) {
                            Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text(label)
                        }
                    }
                }
            }
        }
    }
}

internal fun connectionLabel(account: ChatGptAccount?): String = when {
    account?.pending == true -> "Finish connecting"
    account?.connected == true && !account.plan -> "Enable plan usage"
    account != null -> "Continue sign-in"
    else -> "Continue with ChatGPT"
}
