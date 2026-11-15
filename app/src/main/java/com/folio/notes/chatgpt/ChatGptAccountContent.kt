package com.folio.notes.chatgpt

import com.folio.notes.ui.FolioIcons
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

private fun accountStatus(account: ChatGptAccount): String = when {
    account.provider.key -> "API key saved on this device"
    account.pending -> "Ready to finish connecting"
    !account.connected && !account.registered -> "Sign-in unfinished"
    !account.connected -> "Disconnected"
    account.plan -> "ChatGPT plan connected"
    else -> "Signed in · plan usage not enabled"
}

@Composable internal fun ChatGptAccountSummary(account: ChatGptAccount, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        Icon(FolioIcons.CheckCircle, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(account.email, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (account.provider.key) "Using your ${account.provider.label} API key" else "Using your ChatGPT plan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onManage) { Text("Manage") }
    }
}

@Composable internal fun ChatGptConnectCard(account: ChatGptAccount?, enabled: Boolean, onConnect: () -> Unit, onKey: (() -> Unit)? = null) {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Icon(FolioIcons.Sparkle, null, Modifier.size(32.dp))
            Text(when {
                account?.pending == true -> "You're almost connected"
                account?.connected == true -> "Enable feedback from your ChatGPT plan"
                else -> "Bring a fresh perspective to your notes"
            }, style = MaterialTheme.typography.titleLarge)
            Text(when {
                account?.pending == true -> "The browser step is complete. Finish checking your account here to start reviewing pages."
                account?.connected == true -> "You're signed in. Allow Folio to use your ChatGPT plan, then choose an available model."
                else -> "Connect ChatGPT to check your working, explain tricky steps and suggest improvements. You decide what becomes a sticky note."
            }, style = MaterialTheme.typography.bodyMedium)
            Text("Your notebook stays in Folio. A page is shared only when you request a review.", style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onConnect, enabled = enabled) { Text(connectionLabel(account)) }
            onKey?.let { TextButton(it, enabled = enabled) { Text("Use an API key instead") } }
        }
    }
}

@Composable internal fun ChatGptAccountContent(state: ChatGptState, manager: ChatGptManager, modifier: Modifier) {
    val account = state.accounts.firstOrNull { it.client == state.active }
    var tools by rememberSaveable { mutableStateOf(false) }
    fun connect() { if (account?.pending == true) manager.retryVerification() else manager.signIn() }
    fun retry() {
        when (state.operation) {
            ChatGptOperation.SIGN_IN -> connect()
            ChatGptOperation.MODELS -> manager.refreshModels()
            ChatGptOperation.TEST -> manager.test()
            ChatGptOperation.CONNECTION -> manager.checkConnection()
            ChatGptOperation.DISCONNECT -> manager.disconnect()
            ChatGptOperation.ACCOUNT -> account?.let { manager.selectAccount(it.client) }
            else -> Unit
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
        if (state.error != null) ChatGptErrorCard(state, manager::clearError, ::retry, !state.busy && when (state.operation) {
            ChatGptOperation.OPEN, ChatGptOperation.REVIEW, ChatGptOperation.KEY -> false
            ChatGptOperation.TEST -> account?.connected == true && account.plan && state.model != null
            else -> true
        })
        if (account?.connected != true || !account.plan) ChatGptConnectCard(account, !state.busy, ::connect)
        else Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Icon(FolioIcons.CheckCircle, null, Modifier.size(32.dp))
                Text("Ready to review", style = MaterialTheme.typography.titleLarge)
                Text(account.email, style = MaterialTheme.typography.bodyLarge)
                Text("Folio is connected to your ${if (account.provider.key) "${account.provider.label} key" else "ChatGPT plan"}. Choose Page review to send a page and keep the feedback you want.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.accounts.isNotEmpty()) {
            Text("Your accounts", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    state.accounts.forEachIndexed { index, saved ->
                        val selected = saved.client == state.active
                        Surface(shape = FolioShapes.large, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().selectable(selected, enabled = !state.busy, role = Role.RadioButton, onClick = {
                                if (!selected) manager.selectAccount(saved.client)
                            })) {
                            Row(Modifier.padding(FolioSpacing.dp12), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically) {
                                Icon(FolioIcons.Account, null, Modifier.size(28.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                    Text(if (saved.email == "ChatGPT account" && !saved.registered) "Saved sign-in ${index + 1}" else saved.email,
                                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(accountStatus(saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected) Icon(FolioIcons.Check, "Selected account", Modifier.size(20.dp))
                                if (!saved.connected || saved.provider.key) IconButton({ manager.removeAccount(saved.client) }, enabled = !state.busy) {
                                    Icon(FolioIcons.Close, if (saved.provider.key) "Remove ${saved.email}" else "Remove saved sign-in ${index + 1}", Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    TextButton({ manager.signIn(null) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(FolioIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text(if (state.accounts.any { !it.provider.key }) "Add another ChatGPT account" else "Sign in with ChatGPT")
                    }
                }
            }
        }
        if (account?.connected == true) Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            TextButton(manager::disconnect, enabled = !state.busy) { Text(if (account.provider.key) "Remove this key" else "Disconnect") }
            if (state.error != null && !account.provider.key) TextButton({ manager.signIn() }, enabled = !state.busy) { Text("Sign in again") }
        }
        ApiKeyCard(!state.busy, state.error != null, manager::addKey)
        ChatGptUsageContent(state.usage[state.active], account?.provider ?: AiProvider.CHATGPT)
        if (state.result != null) ChatGptNotice(if (state.operation == ChatGptOperation.TEST) FolioIcons.CheckCircle else FolioIcons.Info, state.result)
        Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                TextButton({ tools = !tools }, Modifier.fillMaxWidth()) {
                    Icon(FolioIcons.Tune, null, Modifier.size(20.dp))
                    Text("Connection tools", Modifier.weight(1f).padding(horizontal = FolioSpacing.dp12))
                    Icon(if (tools) FolioIcons.ChevronUp else FolioIcons.ChevronDown, if (tools) "Collapse connection tools" else "Expand connection tools")
                }
                if (tools) {
                    Text("Use these if you want to check the connection or available models.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (account?.connected == true && account.plan) {
                        ChatGptModelPicker(state, manager)
                        ChatGptReasoningPicker(state, manager)
                        OutlinedButton(manager::test, enabled = !state.busy && state.model != null, modifier = Modifier.fillMaxWidth()) { Text("Test text response") }
                    }
                    OutlinedButton(manager::checkConnection, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Check connection") }
                    state.diagnostics?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
        Text("Connections are protected on this device and kept separate from your notebooks and backups.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Paste a key for a provider that has no sign-in. The field is cleared once the key has been saved as a connection. */
@Composable private fun ApiKeyCard(enabled: Boolean, failed: Boolean, onAdd: (AiProvider, String) -> Unit) {
    val context = LocalContext.current
    var providerName by rememberSaveable { mutableStateOf(AiProvider.OPENROUTER.name) }
    var key by remember { mutableStateOf("") } // Not saveable: a key must not reach the saved instance state.
    var sent by remember { mutableStateOf(false) }
    val provider = AiProvider.valueOf(providerName)
    // Busy again → idle after a submit: clear on success; a failure keeps the text for correction (its error card is above).
    LaunchedEffect(enabled) { if (enabled && sent) { sent = false; if (!failed) key = "" } }
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text("Use an API key", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text("${AiProvider.entries.filter { it.key }.joinToString { it.label }}: many models, some free, used with your own key instead of a ChatGPT plan.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                AiProvider.entries.filter { it.key }.forEach { option ->
                    FilterChip(provider == option, { providerName = option.name }, label = { Text(option.label) }, enabled = enabled)
                }
            }
            OutlinedTextField(key, { key = it.trim().take(512) }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, shape = FolioShapes.large,
                label = { Text("${provider.label} API key") }, placeholder = { Text("${provider.keyPrefix}…") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keysUrl))) }) {
                    Icon(FolioIcons.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Get a key")
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton({ sent = true; onAdd(provider, key) }, enabled = enabled && key.isNotBlank()) { Text("Add key") }
            }
        }
    }
}
