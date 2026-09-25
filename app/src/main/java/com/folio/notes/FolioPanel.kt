@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** A bounded, centered panel whose own window preserves the app's immersive mode. */
@Composable internal fun FolioPanel(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
                WindowCompat.getInsetsController(it, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        BoxWithConstraints(Modifier.fillMaxSize().guardUiTouches().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            // Folio is used in both orientations. A narrow, very tall panel is particularly
            // awkward in landscape, so give it a wider, shorter canvas there. Portrait keeps
            // the familiar 640dp width for easy reading, while both orientations stay within
            // the available window and let the panel's content scroll when necessary.
            val landscape = maxWidth > maxHeight
            val panelWidth = minOf(if (landscape) 920.dp else 640.dp, maxWidth)
            val panelMaxHeight = minOf(if (landscape) 600.dp else 720.dp, maxHeight)
            Surface(
                modifier = Modifier.width(panelWidth).heightIn(max = panelMaxHeight),
                shape = RoundedCornerShape(if (landscape) 28.dp else 24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onDismissRequest, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Close $title") }
                    }
                    Column(Modifier.weight(1f, fill = false), content = content)
                }
            }
        }
    }
}
