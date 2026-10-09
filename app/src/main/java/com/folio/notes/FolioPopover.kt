@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * A light card that opens under the top bar instead of dimming the page. There is no scrim, so the
 * canvas stays visible while the user decides; a tap outside or Back simply closes it (the
 * pending edit is discarded, like dismissing a menu).
 */
@Composable internal fun FolioPopover(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    val below = WindowInsets.statusBars.getTop(density) + with(density) { 64.dp.roundToPx() }
    val provider = remember(margin, below) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                IntOffset((windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin), below)
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true)) {
        FolioPopoverSurface(
            modifier = modifier.guardUiTouches().widthIn(min = 260.dp, max = 340.dp).imePadding()
        ) {
            Column(Modifier.padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    Icon(icon, null, Modifier.size(20.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { content() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}
