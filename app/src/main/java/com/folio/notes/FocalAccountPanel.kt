@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The one way into Focal account settings, shared by every Focal screen's top bar. */
@Composable
fun FocalAccountButton(onClick: () -> Unit, trouble: Boolean = false) {
    IconButton(onClick, shapes = IconButtonDefaults.shapes()) {
        BadgedBox(badge = { if (trouble) Badge() }) {
            Icon(Icons.Rounded.ManageAccounts, if (trouble) "Focal account, sync needs attention" else "Focal account")
        }
    }
}

/**
 * Focal account settings. A destination callback is null when that screen is already open, so the
 * panel marks it as current instead of offering to navigate to where the user already is.
 */
@Composable
fun FocalAccountPanel(onDismiss: () -> Unit, onMistakes: (() -> Unit)?, onStudy: (() -> Unit)?) {
    FolioPanel("Focal account", onDismiss, actions = { com.folio.notes.mistakes.FocalAccountSyncAction() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = FolioSpacing.dp24, end = FolioSpacing.dp24, bottom = FolioSpacing.dp24, top = FolioSpacing.dp4),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16),
        ) {
            com.folio.notes.mistakes.FocalAccountContent(inlineSync = false) {
                Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Text("One sign-in for", Modifier.padding(start = FolioSpacing.dp4),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(DestinationInset), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                            FocalDestinationRow(Icons.Rounded.Timer, "Study timer", "Sessions and focus time sync to Focal", onStudy)
                            FocalDestinationRow(Icons.Rounded.School, "Mistake review", "Spaced review of the questions you missed", onMistakes)
                        }
                    }
                    Row(Modifier.padding(horizontal = FolioSpacing.dp4), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Icon(Icons.Rounded.Draw, null, Modifier.size(16.dp).padding(top = FolioSpacing.dp2), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Handwriting stays in Folio. Reviews and sessions are kept offline and sync when you are back online.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// Rows sit concentrically inside the list card: their radius is the card's minus the inset between them.
private val DestinationInset = FolioSpacing.dp4
private val DestinationShape = androidx.compose.foundation.shape.RoundedCornerShape(FolioShapes.extraLargeRadius - DestinationInset)

@Composable
private fun FocalDestinationRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    val current = onClick == null
    val scheme = MaterialTheme.colorScheme
    Surface(onClick ?: {}, enabled = !current, shape = DestinationShape,
        color = if (current) scheme.secondaryContainer.copy(alpha = .5f) else scheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp10),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Surface(shape = FolioShapes.medium, color = if (current) scheme.secondary else scheme.secondaryContainer,
                contentColor = if (current) scheme.onSecondary else scheme.onSecondaryContainer) {
                Icon(icon, null, Modifier.padding(FolioSpacing.dp10).size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (current) Text("Open now", style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
            else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open $title", tint = scheme.onSurfaceVariant)
        }
    }
}
