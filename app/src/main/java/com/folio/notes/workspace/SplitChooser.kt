@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.workspace

import com.folio.notes.ui.FolioIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.notes.app.FolioState
import com.folio.notes.app.FolioViewModel
import com.folio.notes.editor.page.PageThumbnailCache
import com.folio.notes.input.guardUiTouches
import com.folio.notes.library.NotebookFace
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing

/**
 * The empty half of a new split: the documents that can go beside the editor, chosen right here
 * instead of on a trip through the Library. The notebook being edited comes first (for a second page
 * of the same notebook), then the other open tabs, then everything else by when it last changed.
 */
@Composable internal fun SplitChooserPane(
    state: FolioState,
    model: FolioViewModel,
    replacing: Boolean,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler { if (query.isNotEmpty()) query = "" else onCancel() }
    val choices = remember(state.notes, state.tabs, state.activeId, query) {
        SplitChooser.choices(state.notes, state.tabs, state.activeId, query)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize().guardUiTouches()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = FolioSpacing.dp16, end = FolioSpacing.dp4, top = FolioSpacing.dp8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
            ) {
                Icon(FolioIcons.Split, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (replacing) "Show another document here" else "Open beside", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onCancel, shapes = IconButtonDefaults.shapes()) {
                    Icon(FolioIcons.Close, if (replacing) "Keep the current document" else "Cancel split view")
                }
            }
            OutlinedTextField(
                query, { query = it.take(80) },
                Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp8),
                label = { Text("Search notebooks") },
                leadingIcon = { Icon(FolioIcons.Search, null) },
                trailingIcon = if (query.isEmpty()) null else {
                    { IconButton({ query = "" }, shapes = IconButtonDefaults.shapes()) { Icon(FolioIcons.Close, "Clear search") } }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = FolioShapes.large,
            )
            if (choices.isEmpty) {
                Box(Modifier.fillMaxSize().padding(FolioSpacing.dp24), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                        Text(if (query.isBlank()) "No notebooks available" else "No notebooks match “${query.trim()}”",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (query.isNotBlank()) TextButton({ query = ""; keyboard?.hide() }) { Text("Show all notebooks") }
                        TextButton(onCancel) { Text(if (replacing) "Keep current document" else "Cancel split view") }
                    }
                }
            } else LazyVerticalGrid(
                GridCells.Adaptive(112.dp),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = FolioSpacing.dp16, end = FolioSpacing.dp16, bottom = FolioSpacing.dp24),
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12),
            ) {
                choices.current?.let { current ->
                    section("This notebook")
                    item(key = "current") {
                        ChooserTile(current, model.thumbnails, "Page ${state.pageIndex + 1} of ${current.pages.size}") { onPick(current.id) }
                    }
                }
                if (choices.tabs.isNotEmpty()) {
                    section("Open tabs")
                    items(choices.tabs, key = { "tab:" + it.id }) { note -> ChooserTile(note, model.thumbnails, pageCount(note)) { onPick(note.id) } }
                }
                if (choices.recent.isNotEmpty()) {
                    section(if (query.isBlank()) "Recent" else "Notebooks")
                    items(choices.recent, key = { "note:" + it.id }) { note -> ChooserTile(note, model.thumbnails, pageCount(note)) { onPick(note.id) } }
                }
            }
        }
    }
}

private fun pageCount(note: Notebook) = if (note.pages.size == 1) "1 page" else "${note.pages.size} pages"

private fun LazyGridScope.section(title: String) {
    item(key = "section:$title", span = { GridItemSpan(maxLineSpan) }) {
        Text(title, Modifier.padding(top = FolioSpacing.dp8).semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun ChooserTile(note: Notebook, thumbnails: PageThumbnailCache, detail: String, onClick: () -> Unit) {
    val title = note.title.ifBlank { "Untitled" }
    Column(
        Modifier.fillMaxWidth().clip(FolioShapes.medium).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Open $title beside the editor" }
            .padding(FolioSpacing.dp4),
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4),
    ) {
        Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
            NotebookFace(note, thumbnails, Modifier.fillMaxHeight())
        }
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
