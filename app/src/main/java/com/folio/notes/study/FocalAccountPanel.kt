@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.study

import com.folio.notes.ui.FolioIcons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.mistakes.FocalAccountContent
import com.folio.notes.mistakes.FocalAccountSyncAction
import com.folio.notes.settings.MistakePracticeSettings
import com.folio.notes.sync.FocalSupabaseConnection
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioScreenHeading
import com.folio.notes.ui.FolioSpacing


@Composable
fun FocalAccountButton(onClick: () -> Unit, trouble: Boolean = false) {
    val context = LocalContext.current
    val account by remember(context) { FocalSupabaseConnection.of(context).lifecycle.state }.collectAsStateWithLifecycle()
    val attention = trouble || account.needsSignIn
    IconButton(onClick, shapes = IconButtonDefaults.shapes()) {
        BadgedBox(badge = { if (attention) Badge() }) {
            Icon(FolioIcons.Account, if (account.needsSignIn) "Focal account, sign in again" else if (attention) "Focal account, sync needs attention" else "Focal account")
        }
    }
}

/**
 * The heading Study, Progress and Planner share: where the data stands with Focal under the title,
 * then Sync and the screen's own [actions].
 */
@Composable
fun FocalScreenHeading(
    title: String, statusIcon: ImageVector, status: String, syncing: Boolean, canSync: Boolean, onSync: () -> Unit,
    trouble: Boolean = false, actions: @Composable RowScope.() -> Unit = {},
) {
    FolioScreenHeading(title, subtitle = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
            Icon(statusIcon, null, Modifier.size(16.dp), tint = if (trouble) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }) {
        IconButton(onSync, enabled = canSync && !syncing, shapes = IconButtonDefaults.shapes()) {
            if (syncing) LoadingIndicator(Modifier.size(24.dp)) else Icon(FolioIcons.Sync, "Sync with Focal")
        }
        actions()
    }
}

@Composable
fun FocalAccountPanel(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApplication
    FolioPanel("Focal account", onDismiss, actions = {
        FocalAccountSyncAction {
            app.focalStudy.retry()
            app.focalProgress.sync()
        }
    }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = FolioSpacing.dp24, end = FolioSpacing.dp24, bottom = FolioSpacing.dp24, top = FolioSpacing.dp4),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16),
        ) {
            FocalAccountContent(inlineSync = false) {
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Row(Modifier.padding(horizontal = FolioSpacing.dp4), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Icon(FolioIcons.Handwriting, null, Modifier.size(16.dp).padding(top = FolioSpacing.dp2), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Handwriting stays in Folio. Reviews and sessions are kept offline and sync when you are back online.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            MistakePracticeSettings()
        }
    }
}
