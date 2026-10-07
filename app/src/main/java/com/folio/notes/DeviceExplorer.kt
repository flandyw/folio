@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class FileSort(val label: String) { NAME("Name A–Z"), RECENT("Last modified"), SIZE("Largest first") }
private data class DeviceClipboard(val file: DeviceFile, val parent: String, val move: Boolean)
private data class DeviceLocation(val tree: String, val name: String, val available: Boolean)

@Composable internal fun DeviceExplorer(state: FolioState, model: FolioViewModel, onImport: () -> Unit, onImportArchive: () -> Unit, onShelf: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    val files = remember(context) { DeviceFiles(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var trees by remember { mutableStateOf(AppPrefs.explorerLocations(prefs.getStringSet(AppPrefs.EXPLORER_LOCATIONS, emptySet())).toList()) }
    var locationsLoading by remember { mutableStateOf(trees.isNotEmpty()) }
    var clipboard by remember { mutableStateOf<DeviceClipboard?>(null) }
    var locations by remember { mutableStateOf(emptyList<DeviceLocation>()) }
    var activeTree by rememberSaveable { mutableStateOf<String?>(null) }
    var pathUris by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pathNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(FileSort.NAME) }
    var pdfOnly by rememberSaveable { mutableStateOf(false) }
    var selectedUris by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var children by remember { mutableStateOf(emptyList<DeviceFile>()) }
    var parent by remember { mutableStateOf<DeviceFile?>(null) }
    var loading by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<DeviceFile?>(null) }
    var delete by remember { mutableStateOf<DeviceFile?>(null) }
    var forget by remember { mutableStateOf<DeviceLocation?>(null) }
    var importFile by remember { mutableStateOf<DeviceFile?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val current = pathUris.lastOrNull()
    fun rememberTrees(next: List<String>) {
        trees = AppPrefs.explorerLocations(next.toSet()).toList()
        prefs.edit().putStringSet(AppPrefs.EXPLORER_LOCATIONS, trees.toSet()).apply()
    }
    fun clearSelection() { selectedUris = emptyList(); selecting = false }
    fun visit(tree: String, name: String) {
        activeTree = tree; pathUris = listOf(files.root(Uri.parse(tree)).toString()); pathNames = listOf(name); query = ""; clearSelection()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try {
                if (uri.toString() !in trees && trees.size >= 32) { model.reportError("Disconnect a folder before connecting another"); return@launch }
                // Keep the provider's actual offered grants; some locations are read-only.
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                catch (_: SecurityException) { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val root = files.info(files.root(uri))
                rememberTrees(trees + uri.toString())
                visit(uri.toString(), root.name)
                refresh++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { model.reportError("Couldn't connect this folder: ${e.message.orEmpty()}") }
        }
    }
    LaunchedEffect(trees, refresh) {
        locationsLoading = trees.isNotEmpty()
        locations = trees.map { tree ->
            try { DeviceLocation(tree, files.info(files.root(Uri.parse(tree))).name, true) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { DeviceLocation(tree, "Unavailable folder", false) }
        }.sortedBy { it.name.lowercase() }
        locationsLoading = false
    }
    LaunchedEffect(current, refresh) {
        children = emptyList(); parent = null; error = null; loading = current != null
        if (current != null) try {
            val uri = Uri.parse(current)
            parent = files.info(uri)
            children = files.children(uri)
            selectedUris = selectedUris.filter { id -> children.any { it.uri.toString() == id } }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "This folder may have moved, or access has expired. Reconnect it or try again." }
        finally { loading = false }
    }
    val visible = remember(children, query, pdfOnly, sort) {
        children.filter { it.name.contains(query.trim(), true) && (!pdfOnly || it.directory || it.pdf || it.folio) }
            .sortedWith(compareByDescending<DeviceFile> { it.directory }.then(when (sort) {
                FileSort.NAME -> compareBy { it.name.lowercase() }
                FileSort.RECENT -> compareByDescending { it.modified }
                FileSort.SIZE -> compareByDescending { it.size }
            }).thenBy { it.name.lowercase() }.thenBy { it.uri.toString() })
    }
    val selection = visible.filter { it.uri.toString() in selectedUris }
    LaunchedEffect(visible) { selectedUris = selectedUris.filter { id -> visible.any { it.uri.toString() == id } } }
    fun toggle(file: DeviceFile) { val id = file.uri.toString(); selectedUris = if (id in selectedUris) selectedUris - id else selectedUris + id }
    fun back() {
        if (changing) return
        when {
            selecting -> clearSelection()
            query.isNotBlank() -> query = ""
            pathUris.size > 1 -> { pathUris = pathUris.dropLast(1); pathNames = pathNames.dropLast(1) }
            current != null -> { activeTree = null; pathUris = emptyList(); pathNames = emptyList() }
            else -> onShelf()
        }
    }
    BackHandler { back() }
    fun launchFile(file: DeviceFile, share: Boolean = false) {
        try {
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, file.uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, file.name, file.uri)
            context.startActivity(Intent.createChooser(intent, if (share) "Share ${file.name}" else "Open ${file.name}"))
        } catch (_: Exception) { model.reportError("No app could open this file") }
    }
    fun mutate(success: String, block: suspend () -> Unit) {
        if (changing) return
        changing = true
        scope.launch {
            try { block(); model.reportError(success) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { model.reportError("Couldn't change this file: ${e.message.orEmpty()}") }
            finally { changing = false; refresh++ }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(224.dp).fillMaxHeight().padding(start = 16.dp, bottom = 12.dp), shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { Text("Locations", Modifier.padding(8.dp), style = MaterialTheme.typography.titleSmall) }
                    item { ExplorerPlace("Connected folders", Icons.Rounded.TabletAndroid, current == null) { if (!changing) { activeTree = null; pathUris = emptyList(); pathNames = emptyList(); clearSelection() } } }
                    items(locations, key = { it.tree }) { location -> ExplorerPlace(location.name, Icons.Rounded.FolderOpen, activeTree == location.tree) { if (!changing) { if (location.available) visit(location.tree, location.name) else picker.launch(Uri.parse(location.tree)) } } }
                    item { TextButton({ picker.launch(activeTree?.let(Uri::parse)) }, enabled = !changing, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Connect folder") } }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current != null) ExplorerBreadcrumbs(listOf("Locations" to { if (!changing) { activeTree = null; pathUris = emptyList(); pathNames = emptyList(); clearSelection() } }) + pathNames.mapIndexed { index, name -> name to { if (!changing) { pathUris = pathUris.take(index + 1); pathNames = pathNames.take(index + 1); query = ""; clearSelection() } } })
                    else Text("Connected folders", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
                    if (current != null) ExplorerSearch(query, { query = it }, "Search files in this folder")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (current == null) Button({ picker.launch(null) }, enabled = !changing, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Connect folder") }
                        if (current != null) {
                            FilledTonalButton({ newFolder = true }, enabled = !loading && !changing && parent?.supports(DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == true,
                                shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("New folder") }
                            IconButton({ refresh++ }, enabled = !loading && !changing, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Refresh, "Refresh files") }
                            FilterChip(pdfOnly, { pdfOnly = !pdfOnly }, { Text("PDF & Folio") })
                            Box {
                                TextButton({ sortMenu = true }, shapes = ButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Sort, null); Spacer(Modifier.width(4.dp)); Text(sort.label) }
                                DropdownMenu(sortMenu, { sortMenu = false }, modifier = Modifier.guardUiTouches()) { FileSort.entries.forEach { option -> DropdownMenuItem({ Text(option.label) }, { sort = option; sortMenu = false }, trailingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) }) } }
                            }
                            TextButton({ selecting = !selecting; selectedUris = emptyList() }, enabled = !loading && !changing && (selecting || visible.any { !it.directory }), shapes = ButtonDefaults.shapes()) { Text(if (selecting) "Done" else "Select") }
                        }
                    }
                    clipboard?.let { clip ->
                        Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (clip.move) "Move · ${clip.file.name}" else "Copy · ${clip.file.name}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Browse to a destination, then paste", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton({
                                    val destination = current?.let(Uri::parse)
                                    if (destination != null) mutate(if (clip.move) "File moved" else "File copied") {
                                        files.copy(clip.file, destination)
                                        if (clip.move) try { files.delete(clip.file) }
                                        catch (e: Exception) { clipboard = null; error("A copy was saved here, but the original couldn't be removed. ${e.message.orEmpty()}") }
                                        clipboard = null
                                    }
                                }, enabled = current != null && (!clip.move || current != clip.parent) && !loading && !changing && parent?.supports(DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == true,
                                    shapes = ButtonDefaults.shapes()) { Text("Paste") }
                                IconButton({ clipboard = null }, enabled = !changing, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, "Cancel file transfer") }
                            }
                        }
                    }
                    if (changing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { LoadingIndicator(Modifier.size(32.dp)); Text("Updating files…", style = MaterialTheme.typography.bodySmall) }
                }
                val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(current, query, sort, pdfOnly) { scroll.scrollToItem(0) }
                LazyColumn(state = scroll, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current == null) {
                        item { Text("Connect a folder to browse it here. PDFs and Folio notebooks can be copied into your library; other files open in their usual app.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(locations, key = { it.tree }) { location ->
                            var menu by remember { mutableStateOf(false) }
                            ExplorerRow(location.name, if (location.available) "Tap to browse files and subfolders" else "Reconnect to restore access", Icons.Rounded.FolderOpen,
                                { if (location.available) visit(location.tree, location.name) else picker.launch(Uri.parse(location.tree)) }, trailing = {
                                    Box {
                                        IconButton({ menu = true }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Location options") }
                                        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
                                            DropdownMenuItem({ Text("Reconnect") }, { menu = false; picker.launch(Uri.parse(location.tree)) })
                                            DropdownMenuItem({ Text("Disconnect") }, { menu = false; forget = location })
                                        }
                                    }
                                })
                        }
                        if (locationsLoading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                        if (locations.isEmpty() && !locationsLoading) item { ExplorerEmpty(Icons.Rounded.TabletAndroid, "Your tablet, within reach", "Choose a folder once to keep it here. Android’s picker also provides individual files from Downloads and other locations.") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onImport, shapes = ButtonDefaults.shapes()) { Text("Choose PDF files") }
                                TextButton(onImportArchive, shapes = ButtonDefaults.shapes()) { Text("Choose Folio file") }
                            }
                        } }
                    } else if (loading) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() } }
                    else if (error != null) item { ExplorerEmpty(Icons.Rounded.FolderOff, "Couldn't open this folder", error!!) {
                        Row { TextButton({ refresh++ }, shapes = ButtonDefaults.shapes()) { Text("Retry") }; Button({ picker.launch(activeTree?.let(Uri::parse)) }, shapes = ButtonDefaults.shapes()) { Text("Reconnect") } }
                    } }
                    else {
                        if (visible.isEmpty()) item { ExplorerEmpty(Icons.Rounded.FolderOpen, if (query.isNotBlank() || pdfOnly) "No matching files" else "This folder is empty", if (query.isNotBlank() || pdfOnly) "Try another name or turn off the file filter." else "Create a folder here, or bring files in using your tablet’s file manager.") }
                        items(visible, key = { it.uri.toString() }) { file ->
                            var menu by remember { mutableStateOf(false) }
                            val selected = file.uri.toString() in selectedUris
                            val detail = if (file.directory) "Folder" else buildList {
                                add(if (file.pdf) "PDF" else if (file.folio) "Folio notebook" else file.name.substringAfterLast('.', "File").uppercase())
                                if (file.size >= 0) add(Formatter.formatShortFileSize(context, file.size))
                                if (file.modified > 0) add(libraryLastEditedLabel(file.modified))
                            }.joinToString(" · ")
                            Surface(shape = FolioShapes.large, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.semantics { if (selecting && !file.directory) this.selected = selected }.combinedClickable(enabled = !changing,
                                    onClickLabel = if (file.directory) "Open ${file.name}" else if (selecting) "Select ${file.name}" else "Open ${file.name}",
                                    onClick = {
                                        when {
                                            selecting && !file.directory -> toggle(file)
                                            file.directory -> { pathUris = pathUris + file.uri.toString(); pathNames = pathNames + file.name; query = ""; clearSelection() }
                                            (file.pdf || file.folio) && !file.virtual -> importFile = file
                                            else -> launchFile(file)
                                        }
                                    }, onLongClick = { if (!file.directory) { selecting = true; toggle(file) } })) {
                                ListItem(headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { if (selecting && !file.directory) Checkbox(selected, { toggle(file) }, Modifier.semanticsLabel("Select ${file.name}"), enabled = !changing) else Icon(if (file.directory) Icons.Rounded.Folder else if (file.pdf) Icons.Rounded.PictureAsPdf else if (file.folio) Icons.AutoMirrored.Rounded.MenuBook else Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = { if (!selecting) Box {
                                        IconButton({ menu = true }, enabled = !changing, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.MoreVert, "Options for ${file.name}") }
                                        DropdownMenu(menu, { menu = false }, modifier = Modifier.guardUiTouches()) {
                                            if (!file.directory) {
                                                if ((file.pdf || file.folio || file.name.endsWith(".zip", true)) && !file.virtual) DropdownMenuItem({ Text("Import to Folio") }, { menu = false; importFile = file }, enabled = !state.busy && !state.loading && !state.loadFailed)
                                                DropdownMenuItem({ Text("Open with…") }, { menu = false; launchFile(file) })
                                                if (!file.virtual) {
                                                    DropdownMenuItem({ Text("Copy") }, { menu = false; clipboard = DeviceClipboard(file, current.orEmpty(), false) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                                                    if (file.supports(DocumentsContract.Document.FLAG_SUPPORTS_DELETE)) DropdownMenuItem({ Text("Move") }, { menu = false; clipboard = DeviceClipboard(file, current.orEmpty(), true) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) })
                                                }
                                                DropdownMenuItem({ Text("Share") }, { menu = false; launchFile(file, true) })
                                            }
                                            if (file.supports(DocumentsContract.Document.FLAG_SUPPORTS_RENAME)) DropdownMenuItem({ Text("Rename") }, { menu = false; rename = file })
                                            if (file.supports(DocumentsContract.Document.FLAG_SUPPORTS_DELETE)) DropdownMenuItem({ Text("Delete") }, { menu = false; delete = file })
                                        }
                                    } }, colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow))
                            }
                        }
                    }
                }
                if (selecting) ExplorerSelectionBar(selection.size, visible.count { !it.directory },
                    { selectedUris = if (selection.size == visible.count { !it.directory }) emptyList() else visible.filterNot { it.directory }.map { it.uri.toString() } }, { clearSelection() }) {
                    Button({ model.preparePdfImport(selection.map { it.uri }); clearSelection() }, enabled = selection.isNotEmpty() && selection.all { it.pdf && !it.virtual } && !state.busy && !state.loading && !state.loadFailed,
                        shapes = ButtonDefaults.shapes()) { Text("Import PDFs") }
                    Spacer(Modifier.width(8.dp)); Text("Select PDFs to import together", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (newFolder) NameDialog("New device folder", "Inside ${pathNames.lastOrNull().orEmpty()}", "", "Create", { newFolder = false }) { name ->
        val uri = current?.let(Uri::parse)
        if (uri != null && name.isNotBlank() && '/' !in name && '\\' !in name) { newFolder = false; mutate("Folder created") { files.createFolder(uri, name.trim()) } }
        else model.reportError("Use a folder name without slashes")
    }
    rename?.let { file -> NameDialog("Rename ${if (file.directory) "folder" else "file"}", "Keep the file extension so other apps can open it.", file.name, "Save", { rename = null }) { name ->
        if (name.isNotBlank() && '/' !in name && '\\' !in name) { rename = null; mutate("Renamed") { files.rename(file, name.trim()) } }
        else model.reportError("Use a name without slashes")
    } }
    delete?.let { file -> ExplorerConfirm("Delete “${file.name}”?", if (file.directory) "This deletes this device folder and its contents. This cannot be undone in Folio." else "This deletes the original file from this location. This cannot be undone in Folio.", "Delete", { delete = null }) { delete = null; mutate("Deleted") { files.delete(file) } } }
    forget?.let { location -> ExplorerConfirm("Disconnect “${location.name}”?", "It will disappear from Explorer. Files stay on your tablet, and you can connect the folder again.", "Disconnect", { forget = null }) {
        // Other Folio features may hold a grant to the same tree, so don't revoke shared permissions.
        rememberTrees(trees.filterNot { it == location.tree }); forget = null
        if (activeTree == location.tree) { activeTree = null; pathUris = emptyList(); pathNames = emptyList(); clearSelection() }
    } }
    importFile?.let { file -> AlertDialog(onDismissRequest = { importFile = null }, modifier = Modifier.guardUiTouches(), title = { Text(file.name) },
        text = { Text("Import a copy into your notebooks to read and write on it. The original stays in this folder.") },
        dismissButton = { TextButton({ importFile = null; launchFile(file) }, shapes = ButtonDefaults.shapes()) { Text("Open with…") } },
        confirmButton = { Button({ importFile = null; if (file.pdf) model.preparePdfImport(listOf(file.uri)) else model.importArchive(file.uri) }, enabled = !state.busy && !state.loading && !state.loadFailed, shapes = ButtonDefaults.shapes()) { Text("Import to Folio") } }) }
}
