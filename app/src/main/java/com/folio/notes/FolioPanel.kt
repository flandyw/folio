@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer

/** A bounded, centered panel whose own window preserves the app's immersive mode. */
@Composable internal fun FolioPanel(
    title: String,
    onDismissRequest: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    FolioAnimatedDialog(onDismissRequest) { progress, dismiss ->
        BoxWithConstraints(Modifier.fillMaxSize().guardUiTouches().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(FolioSpacing.dp16), contentAlignment = Alignment.Center) {
            // Folio is used in both orientations. A narrow, very tall panel is particularly
            // awkward in landscape, so give it a wider canvas there. The slightly taller
            // limits use spare tablet space, while both orientations remain bounded by the
            // safe window (and shrink above the keyboard). Short dialogs still wrap content.
            val landscape = maxWidth > maxHeight
            val panelWidth = minOf(if (landscape) 920.dp else 640.dp, maxWidth)
            val panelMaxHeight = minOf(if (landscape) 680.dp else 800.dp, maxHeight)
            Surface(
                modifier = Modifier.graphicsLayer {
                    alpha = progress.value
                    translationY = (1f - progress.value) * 20.dp.toPx()
                    scaleX = .96f + .04f * progress.value
                    scaleY = scaleX
                }.width(panelWidth).heightIn(max = panelMaxHeight),
                shape = if (landscape) FolioShapes.panel else FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp24, end = FolioSpacing.dp8, top = FolioSpacing.dp8, bottom = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        actions()
                        IconButton(dismiss, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Close $title") }
                    }
                    Column(Modifier.weight(1f, fill = false), content = content)
                }
            }
        }
    }
}
