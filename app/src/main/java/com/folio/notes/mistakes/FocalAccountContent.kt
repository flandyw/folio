@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.mistakes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.mistakes.sync.FocalAccountStatus
import com.folio.notes.mistakes.sync.SYNC_FOCAL
import com.folio.notes.mistakes.sync.SYNC_OFFLINE
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

@Composable
internal fun FocalAccountContent(
    model: MistakesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBeforeSignOut: () -> Unit = {},
    inlineSync: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
        if (state.accountStatus == FocalAccountStatus.RESTORING ||
            state.accountStatus == FocalAccountStatus.RECONNECTING && state.userId == null) {
            Text(if (state.accountStatus == FocalAccountStatus.RESTORING) "Restoring Focal account…" else state.status,
                style = MaterialTheme.typography.bodyMedium)
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        } else if (state.userId == null || state.needsSignIn) {
            FocalLoginCard(state, model::signIn, model::signUp, model::resetPassword, model::clearAuthFeedback)
            extra()
        } else {
            AccountCard(state.email, state.status, state.cache.lastSyncedAt, state.cache.pending.size, state.status == "Syncing…",
                inlineSync, state.accountStatus == FocalAccountStatus.RECONNECTING) { model.requestSync(force = true) }
            extra()
        }
        if (state.userId != null) {
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .4f))) {
                Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(FolioSpacing.dp8))
                Text("Sign out")
            }
        }
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        icon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
        title = { Text("Sign out of Focal?") },
        text = { Text("This disconnects both study sessions and mistake sync. Saved sessions, handwriting and the offline cache stay on this device.") },
        dismissButton = { TextButton({ confirmSignOut = false }) { Text("Stay signed in") } },
        confirmButton = { TextButton({
            confirmSignOut = false
            onBeforeSignOut()
            model.signOut()
        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Sign out") } },
    )
}

@Composable
internal fun OfflineNoteCard() {
    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.OfflinePin, null, tint = MaterialTheme.colorScheme.secondary)
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Text("Works offline after the first sync", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Cards, images and ratings are kept on this device. Sync resumes when you are back online.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The signed-in hero: who you are, whether Focal has everything, and what is still waiting. */
@Composable
private fun AccountCard(
    email: String?, status: String, lastSyncedAt: String?, pending: Int,
    syncing: Boolean, inlineSync: Boolean, reconnecting: Boolean, onSync: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = FolioShapes.panel, color = Color.Transparent, contentColor = scheme.onPrimaryContainer) {
        Box(Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))) {
            // A soft halo in the corner; decoration only.
            Box(Modifier.align(Alignment.TopEnd).offset(x = 48.dp, y = (-56).dp).size(180.dp)
                .background(scheme.primary.copy(alpha = .10f), CircleShape))
            Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                    Box(Modifier.size(68.dp).background(scheme.surface.copy(alpha = .55f), CircleShape).padding(FolioSpacing.dp4)) {
                        Surface(shape = CircleShape, color = scheme.primary, modifier = Modifier.fillMaxSize()) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    (email?.trim()?.firstOrNull()?.uppercase() ?: "F"),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = scheme.onPrimary, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                        Text(if (reconnecting) "Reconnecting to Focal" else "Signed in to Focal", style = MaterialTheme.typography.labelLarge,
                            color = scheme.onPrimaryContainer.copy(alpha = .75f))
                        Text(email ?: "Focal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (inlineSync) FocalSyncButton(syncing, onSync)
                }
                SyncStatusRow(status, pending)
                if (syncing) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                    AccountStat(Icons.Rounded.Schedule, "Last sync",
                        formatSyncedAt(lastSyncedAt).removePrefix("Last synced "), Modifier.weight(1f).fillMaxHeight())
                    AccountStat(Icons.Rounded.CloudUpload, "Waiting to upload",
                        if (pending == 0) "Nothing" else "$pending ${if (pending == 1) "review" else "reviews"}", Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun AccountStat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = .6f),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Icon-only "sync now" used in the account panel header (and inline where there is no header). */
@Composable
internal fun FocalSyncButton(syncing: Boolean, onSync: () -> Unit) {
    IconButton(onSync, enabled = !syncing, shapes = IconButtonDefaults.shapes()) {
        if (syncing) LoadingIndicator(Modifier.size(22.dp)) else Icon(Icons.Rounded.Sync, "Sync now")
    }
}

/** Signed-in sync state for the account panel header; null when signed out. */
@Composable
internal fun FocalAccountSyncAction(model: MistakesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    if (state.userId != null && !state.needsSignIn) FocalSyncButton(state.status == "Syncing…") { model.requestSync(force = true) }
}

@Composable
private fun SyncStatusRow(status: String, pending: Int) {
    val (icon, container, content) = when {
        status == "Syncing…" -> Triple(Icons.Rounded.Sync, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        status.startsWith(SYNC_OFFLINE) -> Triple(Icons.Rounded.CloudOff, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        status.startsWith(SYNC_FOCAL) -> Triple(Icons.Rounded.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        status.startsWith("Synced") && pending == 0 -> Triple(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        status.startsWith("Synced") -> Triple(Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        else -> Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (pending > 0) Text("$pending queued", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
