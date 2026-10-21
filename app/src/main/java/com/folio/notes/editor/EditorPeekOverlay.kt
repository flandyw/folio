@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.notes.editor.page.EditorPage
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.app.FolioViewModel
import com.folio.notes.ink.InkView
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.PeekAnchor
import com.folio.notes.model.PeekMode
import com.folio.notes.model.Tool
import com.folio.notes.editor.toolbar.ToolOptions
import com.folio.notes.input.guardUiTouches
import com.folio.notes.model.page

/**
 * The peek view drawn over the editor: [anchor]'s page, read-only, with a caption. Drawn on top of
 * the editor so the original composition and cameras stay alive underneath, and closing the peek is
 * an exact return. A held peek is a glance; one kept open ([PeekMode.LATCHED]) can be panned and
 * zoomed to read, re-centred, pinned again ([canPin]) or gone to.
 */
@Composable internal fun EditorPeekOverlay(
    note: Notebook, anchor: PeekAnchor, mode: PeekMode?, model: FolioViewModel, options: ToolOptions,
    view: InkView?, canPin: Boolean, onPinned: (PeekAnchor) -> Unit,
    onGoTo: (pageIndex: Int, target: NotePage, shown: PeekAnchor) -> Unit, onClose: () -> Unit,
) {
    val target = anchor.page(note.pages) ?: return
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().zIndex(10f).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        EditorPage(note.id, target, model,
            surface = InkSurfaceConfig(tool = Tool.HAND,
                options = options,
                finger = false,
                snapEnabled = false,
                shapeRecognition = false,
                inputBlocked = mode == PeekMode.HELD),
            presentation = PagePresentation(active = false,
                fullscreen = true,
                readOnly = true,
                peekRegion = anchor),
            actions = EditorPageActions(mutations = remember(target.id) { model.pageMutations(target.id) }, onActive = {},
                onPan = { _, _ -> },
                onPanEnd = {},
                onSelection = {},
                onTextEdit = {},
                onTextCreate = {},
                onLoad = { model.loadPage(target.id) },
                onSelectAllView = {}))
        Surface(
            Modifier.align(Alignment.TopCenter).padding(top = FolioSpacing.dp12).guardUiTouches(),
            shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 4.dp, tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        ) {
            Row(Modifier.padding(start = FolioSpacing.dp12, end = FolioSpacing.dp4, top = FolioSpacing.dp4, bottom = FolioSpacing.dp4).heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Icon(Icons.Rounded.Visibility, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                val pageIndex = note.pages.indexOfFirst { it.id == target.id }
                val pageName = target.title.takeIf { it.isNotBlank() }?.let { "page ${pageIndex + 1} · $it" } ?: "page ${pageIndex + 1}"
                Text(if (mode == PeekMode.HELD) "Peeking at $pageName · release to return" else "Peek · $pageName",
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (mode == PeekMode.LATCHED) {
                    // A kept-open peek can be panned; these bring it back, keep the new framing, or go there to write.
                    IconButton({ view?.fitPeekAnchor(anchor) }, Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.CenterFocusStrong, "Re-centre on the pinned view", Modifier.size(20.dp))
                    }
                    if (canPin) IconButton({
                        val shown = view?.currentPeekAnchor() ?: return@IconButton
                        model.setPeekAnchor(shown); onPinned(shown.fittedTo(target))
                        android.widget.Toast.makeText(context, "Peek view updated", android.widget.Toast.LENGTH_SHORT).show()
                    }, Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.PushPin, "Pin what the peek shows now", Modifier.size(20.dp))
                    }
                    TextButton({ onGoTo(pageIndex, target, view?.currentPeekAnchor() ?: anchor) }, shapes = ButtonDefaults.shapes()) { Text("Go to") }
                    TextButton(onClose, shapes = ButtonDefaults.shapes()) { Text("Close") }
                }
            }
        }
    }
}
