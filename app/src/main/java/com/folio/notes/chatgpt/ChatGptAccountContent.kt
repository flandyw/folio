package com.folio.notes.chatgpt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import com.folio.notes.FolioShapes
import com.folio.notes.FolioSpacing

private fun accountStatus(account: ChatGptAccount): String = when {
    account.pending -> "Ready to finish connecting"
    !account.connected && !account.registered -> "Sign-in unfinished"
    !account.connected -> "Disconnected"
    account.plan -> "ChatGPT plan connected"
    else -> "Signed in · plan usage not enabled"
}

@Composable internal fun ChatGptAccountSummary(account: ChatGptAccount, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(account.email, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Using your ChatGPT plan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onManage) { Text("Manage") }
    }
}

@Composable internal fun ChatGptConnectCard(account: ChatGptAccount?, enabled: Boolean, onConnect: () -> Unit) {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(32.dp))
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
            ChatGptOperation.OPEN, ChatGptOperation.REVIEW -> false
            ChatGptOperation.TEST -> account?.connected == true && account.plan && state.model != null
            else -> true
        })
        if (account?.connected != true || !account.plan) ChatGptConnectCard(account, !state.busy, ::connect)
        else Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(32.dp))
                Text("Ready to review", style = MaterialTheme.typography.titleLarge)
                Text(account.email, style = MaterialTheme.typography.bodyLarge)
                Text("Folio is connected to your ChatGPT plan. Choose Page review to send a page and keep the feedback you want.", style = MaterialTheme.typography.bodyMedium)
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
                                Icon(Icons.Rounded.AccountCircle, null, Modifier.size(28.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                    Text(if (saved.email == "ChatGPT account" && !saved.registered) "Saved sign-in ${index + 1}" else saved.email,
                                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(accountStatus(saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (selected) Icon(Icons.Rounded.Check, "Selected account", Modifier.size(20.dp))
                                if (!saved.connected) IconButton({ manager.removeAccount(saved.client) }, enabled = !state.busy) {
                                    Icon(Icons.Rounded.Close, "Remove saved sign-in ${index + 1}", Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    TextButton({ manager.signIn(null) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(FolioSpacing.dp8)); Text("Add another account")
                    }
                }
            }
        }
        if (account?.connected == true) Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            TextButton(manager::disconnect, enabled = !state.busy) { Text("Disconnect") }
            if (state.error != null) TextButton({ manager.signIn() }, enabled = !state.busy) { Text("Sign in again") }
        }
        ChatGptUsageContent(state.usage[state.active])
        if (state.result != null) ChatGptNotice(if (state.operation == ChatGptOperation.TEST) Icons.Rounded.CheckCircle else Icons.Rounded.Info, state.result)
        Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                TextButton({ tools = !tools }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(20.dp))
                    Text("Connection tools", Modifier.weight(1f).padding(horizontal = FolioSpacing.dp12))
                    Icon(if (tools) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (tools) "Collapse connection tools" else "Expand connection tools")
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
