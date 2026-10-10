@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Notebook tallies the sidebar, place chips and folder tiles label themselves with. */
internal class LibraryCounts(notes: List<Notebook>, folders: List<Folder>) {
    val all = notes.size
    val favorites = notes.count { it.starred }
    val byFolder = notes.groupingBy { it.folderId }.eachCount()
    val subfolders = folders.groupingBy { it.parentId }.eachCount()
    private val byTag = notes.flatMap { note -> note.tags.map { it.lowercase() } }.groupingBy { it }.eachCount()
    fun tag(label: String) = byTag[label.lowercase()] ?: 0
    fun folderDetail(folder: Folder): String {
        val n = byFolder[folder.id] ?: 0
        val sub = subfolders[folder.id] ?: 0
        return "$n ${if (n == 1) "notebook" else "notebooks"}" + if (sub > 0) " · $sub ${if (sub == 1) "folder" else "folders"}" else ""
    }
}

internal fun LibraryPlace.icon(): ImageVector = when (this) {
    LibraryPlace.ALL -> Icons.Rounded.GridView
    LibraryPlace.FAVORITES -> Icons.Rounded.StarOutline
    LibraryPlace.UNFILED -> Icons.Rounded.FolderOff
}

/** Dropping on a place: Favorites stars, Unfiled takes notebooks out of their folder; All is just a view. */
internal fun LibraryPlace.drop(): NotebookDropDestination? = when (this) {
    LibraryPlace.ALL -> null
    LibraryPlace.FAVORITES -> NotebookDropDestination.Favorites
    LibraryPlace.UNFILED -> NotebookDropDestination.Folder(null, "Unfiled")
}

/**
 * The wide library's shared side panel: Tablet files, notebook places, folders and tags.
 * Folder rows accept notebook drops and expand on hover without replacing the source grid.
 */
@Composable internal fun LibrarySidebar(
    folders: List<Folder>, openFolder: String?, place: LibraryPlace, tag: String?, tags: List<String>,
    expanded: Set<String>, counts: LibraryCounts, drag: NotebookDragState?,
    onPlace: (LibraryPlace) -> Unit, onFolder: (String) -> Unit, onExpand: (String) -> Unit, onTag: (String) -> Unit,
    onHoverOpen: (String?) -> Unit, onNewFolder: () -> Unit, onFiles: (() -> Unit)?, onFinishDrag: () -> Unit,
    modifier: Modifier = Modifier, filesActive: Boolean = false,
) {
    val list = rememberLazyListState()
    val rows = remember(folders, expanded) { LibraryBrowse.tree(folders, expanded) }
    val parents = remember(folders) { folders.mapNotNull { it.parentId }.toSet() }
    val home = !filesActive && openFolder == null && tag == null
    Surface(modifier.width(288.dp).fillMaxHeight().padding(start = FolioDestinationInset, bottom = FolioSpacing.dp12),
        shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.then(if (drag != null) Modifier.notebookDragScroll(drag) { list.scrollBy(it) } else Modifier),
            state = list, contentPadding = PaddingValues(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
            if (onFiles != null) {
                item(key = "files") { LibrarySidebarRow("Tablet files", Icons.Rounded.TabletAndroid, filesActive, onClick = onFiles) }
                item(key = "files-divider") { HorizontalDivider(Modifier.padding(vertical = FolioSpacing.dp8, horizontal = FolioSpacing.dp8)) }
            }
            items(LibraryPlace.entries, key = { "place:${it.name}" }) { item ->
                LibrarySidebarRow(item.label, item.icon(), home && place == item,
                    count = when (item) { LibraryPlace.ALL -> counts.all; LibraryPlace.FAVORITES -> counts.favorites; LibraryPlace.UNFILED -> counts.byFolder[null] ?: 0 },
                    modifier = item.drop()?.let { Modifier.notebookDropTarget(drag, it, onHoverOpen = if (item == LibraryPlace.UNFILED) ({ onHoverOpen(null) }) else null) } ?: Modifier) { onPlace(item) }
            }
            item(key = "folders") {
                LibrarySidebarHeading("Folders") {
                    IconButton(onNewFolder, modifier = Modifier.size(FolioTouch.target), shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.CreateNewFolder, "New folder", Modifier.size(20.dp)) }
                }
            }
            if (rows.isEmpty()) item(key = "no-folders") {
                Text("Group notebooks into folders, then drag them in.", Modifier.padding(horizontal = FolioSpacing.dp12, vertical = FolioSpacing.dp4),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rows, key = { "folder:${it.first.id}" }) { (folder, depth) ->
                LibrarySidebarRow(folder.name, if (openFolder == folder.id) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, !filesActive && openFolder == folder.id,
                    count = counts.byFolder[folder.id] ?: 0, depth = depth, iconTint = FolderPalette.color(folder.color),
                    expanded = if (folder.id in parents) folder.id in expanded else null, onExpand = { onExpand(folder.id) },
                    modifier = Modifier.notebookDragSource(drag, onFinishDrag) { NotebookDragPayload.Folder(folder.id, folder.name) }
                        .graphicsLayer { alpha = if ((drag?.payload as? NotebookDragPayload.Folder)?.id == folder.id) .45f else 1f }
                        .notebookDropTarget(drag, NotebookDropDestination.Folder(folder.id, folder.name), onHoverOpen = { if (folder.id in parents && folder.id !in expanded) onExpand(folder.id) })) { onFolder(folder.id) }
            }
            if (tags.isNotEmpty()) {
                item(key = "tags") { LibrarySidebarHeading("Tags") }
                items(tags, key = { "tag:$it" }) { label ->
                    LibrarySidebarRow(label, Icons.Rounded.Sell, !filesActive && tag.equals(label, true), count = counts.tag(label),
                        modifier = Modifier.notebookDropTarget(drag, NotebookDropDestination.Tag(label))) { onTag(label) }
                }
            }
        }
    }
}

@Composable private fun LibrarySidebarHeading(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = FolioSpacing.dp12, top = FolioSpacing.dp12).heightIn(min = FolioTouch.target), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

/** One sidebar row; tree rows pass [depth] and, when they have children, [expanded]. */
@Composable internal fun LibrarySidebarRow(
    label: String, icon: ImageVector, active: Boolean, modifier: Modifier = Modifier, count: Int? = null,
    iconTint: Color? = null, depth: Int? = null, expanded: Boolean? = null, onExpand: () -> Unit = {}, onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = FolioShapes.large,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().semantics { selected = active }) {
        Row(Modifier.heightIn(min = FolioTouch.row).padding(start = FolioSpacing.dp12 + 14.dp * (depth ?: 0), end = if (expanded != null) FolioSpacing.dp4 else FolioSpacing.dp12),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            if (iconTint != null) Icon(icon, null, Modifier.size(20.dp), tint = iconTint) else Icon(icon, null, Modifier.size(20.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count != null && count > 0) Text("$count", style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded != null) {
                val turn by animateFloatAsState(if (expanded) 90f else 0f, folioSpring(), label = "folderDisclosure")
                IconButton(onExpand, modifier = Modifier.size(FolioTouch.target), shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.ChevronRight, if (expanded) "Collapse $label" else "Expand $label", Modifier.size(18.dp).rotate(turn))
                }
            }
        }
    }
}

/** When the sidebar is folded, places and tags become scrolling, finger-sized drop targets. */
@Composable internal fun LibraryPlacesRow(
    place: LibraryPlace, home: Boolean, tag: String?, tags: List<String>, counts: LibraryCounts, drag: NotebookDragState?,
    onPlace: (LibraryPlace) -> Unit, onTag: (String) -> Unit, onHoverOpen: (String?) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(Modifier.then(if (drag != null) Modifier.notebookDragScroll(drag, horizontal = true) { scroll.scrollBy(it) } else Modifier).horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        LibraryPlace.entries.forEach { item ->
            // The same tallies the sidebar wears, including the total on All.
            val count = when (item) { LibraryPlace.ALL -> counts.all; LibraryPlace.FAVORITES -> counts.favorites; LibraryPlace.UNFILED -> counts.byFolder[null] ?: 0 }
            FilterChip(home && place == item, { onPlace(item) }, { Text(if (count > 0) "${item.label} · $count" else item.label) },
                leadingIcon = { Icon(item.icon(), null, Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = FolioTouch.target).then(item.drop()?.let { Modifier.notebookDropTarget(drag, it, onHoverOpen = if (item == LibraryPlace.UNFILED) ({ onHoverOpen(null) }) else null) } ?: Modifier))
        }
        tags.forEach { label ->
            val count = counts.tag(label)
            FilterChip(tag.equals(label, true), { onTag(label) }, { Text(if (count > 0) "$label · $count" else label) },
                leadingIcon = { Icon(Icons.Rounded.Sell, null, Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = FolioTouch.target).notebookDropTarget(drag, NotebookDropDestination.Tag(label)))
        }
    }
}

/** A folder in the shelf: open on tap, hold and move to refile its subtree, drop notebooks on it. */
@Composable internal fun FolderTile(
    name: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    Surface(onClick = onClick, modifier = modifier, shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(start = FolioSpacing.dp12, top = FolioSpacing.dp8, bottom = FolioSpacing.dp8, end = FolioSpacing.dp4),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Surface(shape = FolioShapes.medium, color = tint?.copy(alpha = .22f) ?: MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Icons.Rounded.Folder, null, Modifier.padding(FolioSpacing.dp8).size(24.dp), tint = tint ?: MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (menu != null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton({ open = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for $name") }
                    if (open) FolioActionPopover(name, { open = false }) { menu { open = false } }
                }
            } else Spacer(Modifier.width(FolioSpacing.dp8))
        }
    }
}

/** The same four folder actions wherever a folder offers a menu. */
@Composable internal fun FolderMenuItems(close: () -> Unit, onNewInside: () -> Unit, onRename: () -> Unit, onColour: () -> Unit, onMove: () -> Unit, onRemove: () -> Unit) {
    PopoverRow(Icons.Rounded.CreateNewFolder, "New folder inside") { close(); onNewInside() }
    PopoverRow(Icons.Rounded.Edit, "Rename folder") { close(); onRename() }
    PopoverRow(Icons.Rounded.Palette, "Folder colour") { close(); onColour() }
    PopoverRow(Icons.AutoMirrored.Rounded.DriveFileMove, "Move folder") { close(); onMove() }
    PopoverRow(Icons.Rounded.DeleteOutline, "Remove folder", destructive = true) { close(); onRemove() }
}

/** Folder colours are palette indices stored on the folder; 0 keeps the theme's default tint. */
object FolderPalette {
    val colors = listOf(Color(0xFFE57373), Color(0xFFF2994A), Color(0xFFE5B800), Color(0xFF66BB6A), Color(0xFF26A69A), Color(0xFF42A5F5), Color(0xFF7E8CE0), Color(0xFFBA68C8), Color(0xFFEC7FA9), Color(0xFF8D8D8D))
    val names = listOf("Red", "Orange", "Yellow", "Green", "Teal", "Blue", "Indigo", "Purple", "Pink", "Grey")
    fun color(index: Int): Color? = if (index in 1..colors.size) colors[index - 1] else null
}

@Composable internal fun FolderColourDialog(folder: Folder, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Colour for ${folder.name}") }, text = {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            (0..FolderPalette.colors.size).forEach { index ->
                val tint = FolderPalette.color(index)
                val label = if (index == 0) "Default" else FolderPalette.names[index - 1]
                IconButton({ onPick(index); onDismiss() }, modifier = Modifier.size(FolioTouch.target).semantics { selected = folder.color == index }) {
                    Icon(if (folder.color == index) Icons.Rounded.CheckCircle else Icons.Rounded.Folder, label, Modifier.size(28.dp),
                        tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Close") } })
}
