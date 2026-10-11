@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalOpenWritingFollowSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Settings, as one stack of grouped pages. A phone shows a home list that drills into a category;
 * a tablet keeps the category list beside the page. Search looks at individual settings, not just
 * category names. Every control saves as it changes, so there is nothing to confirm or cancel.
 */
@Composable fun SettingsScreen(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, themePalette: ThemePalette, onThemePalette: (ThemePalette) -> Unit, amoled: Boolean, onAmoled: (Boolean) -> Unit, finger: Boolean, onFinger: (Boolean) -> Unit, stylus: StylusShortcut, onStylus: (StylusShortcut) -> Unit, haptics: Boolean, onHaptics: (Boolean) -> Unit, shapeRecognition: Boolean, onShapeRecognition: (Boolean) -> Unit, onCheckForUpdates: () -> Unit, updateChecking: Boolean, onBack: () -> Unit, onFocal: () -> Unit = {}, onCheckGitHub: () -> Unit = onCheckForUpdates, onBackupLibrary: () -> Unit = {}, onRestoreLibrary: () -> Unit = {}, onChooseBackupFolder: () -> Unit = {}, onBackupNow: () -> Unit = {}, onDisableAutoBackup: () -> Unit = {}, backupBusy: Boolean = false, onRestoreAutomaticBackup: () -> Unit = {}, backupExcludedCount: Int = 0, onBackupExclusions: () -> Unit = {}, updateBusy: Boolean = updateChecking, updateContent: @Composable () -> Unit = {}, folders: List<Folder> = emptyList(), startWithWritingFollow: Boolean = false) {
    val context = LocalContext.current
    val prefs = rememberPrefs()
    var category by rememberSaveable { mutableStateOf<SettingsCategory?>(if (startWithWritingFollow) SettingsCategory.FOLLOW else null) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()

    val backupProgress by LibraryAutoBackup.progress.collectAsState()
    val backupTree by rememberPref(prefs, LibraryAutoBackup.TREE_URI) { it.getString(LibraryAutoBackup.TREE_URI, null) }
    val backupLastSuccess by rememberPref(prefs, LibraryAutoBackup.LAST_SUCCESS) { it.getLong(LibraryAutoBackup.LAST_SUCCESS, 0L) }
    val backupLastError by rememberPref(prefs, LibraryAutoBackup.LAST_ERROR) { it.getString(LibraryAutoBackup.LAST_ERROR, null) }
    var backupFolderName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context, backupTree) {
        backupFolderName = backupTree?.let { raw -> withContext(Dispatchers.IO) { LibraryAutoBackup.folderName(context, android.net.Uri.parse(raw)) } }
    }

    val app = AppSettings(
        themeMode, onThemeMode, themePalette, onThemePalette, amoled, onAmoled, finger, onFinger, stylus, onStylus, haptics, onHaptics,
        shapeRecognition, onShapeRecognition, remember(context) { PenHapticsManager.isSupported(context) }, Build.VERSION.SDK_INT >= 31,
    )
    val backup = BackupSettings(
        backupTree, backupFolderName, backupLastSuccess, backupLastError, backupBusy || backupProgress != null, backupExcludedCount,
        onChooseBackupFolder, onBackupNow, onDisableAutoBackup, onBackupLibrary, onRestoreLibrary, onRestoreAutomaticBackup, onBackupExclusions, backupProgress,
    )
    val account = AccountSettings(onFocal, onCheckForUpdates, onCheckGitHub, updateChecking, updateBusy, updateContent)

    BackHandler(enabled = searching || category != null) {
        if (searching) query = "" else category = null
    }

    // Same top gap as the shell gives its destinations, so this heading lines up with theirs.
    val topGap = (FolioSpacing.dp16 - WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()).coerceAtLeast(0.dp)
    CompositionLocalProvider(LocalDestinationTopGap provides topGap) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 840.dp
        val open: (SettingsCategory) -> Unit = { category = it; query = "" }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SettingsSidebar(category ?: SettingsCategory.APPEARANCE, searching, query, { query = it }, open, Modifier.width(320.dp).fillMaxHeight())
                VerticalDivider()
                SettingsPane(
                    title = when { searching -> "Search"; else -> (category ?: SettingsCategory.APPEARANCE).title },
                    onNavigate = onBack, navLabel = "Close settings", modifier = Modifier.weight(1f),
                ) { SettingsContent(searching, query, category ?: SettingsCategory.APPEARANCE, app, backup, account, folders, open, { query = "" }) }
            }
        } else {
            SettingsPane(
                title = when { searching -> "Search"; category != null -> category!!.title; else -> "Settings" },
                onNavigate = { if (searching) query = "" else if (category != null) category = null else onBack() },
                navLabel = if (category == null && !searching) "Close settings" else "Back to settings",
                modifier = Modifier.fillMaxSize(),
            ) {
                if (category == null || searching) {
                    SettingsSearchField(query, { query = it })
                }
                if (!searching && category == null) SettingsHome(app, backup, open)
                else SettingsContent(searching, query, category ?: SettingsCategory.APPEARANCE, app, backup, account, folders, open) { query = "" }
            }
        }
    }
    }
}

// ---- Shell --------------------------------------------------------------------------------------

/** The shared screen heading over a centred, scrolling column of groups. */
@Composable private fun SettingsPane(
    title: String,
    onNavigate: () -> Unit,
    navLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            FolioScreenHeading(
                title,
                leading = { IconButton(onNavigate, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, navLabel) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = FolioDestinationInset).padding(bottom = FolioSpacing.dp32),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp24),
                content = content,
            )
        }
    }
}

@Composable private fun SettingsContent(
    searching: Boolean, query: String, category: SettingsCategory,
    app: AppSettings, backup: BackupSettings, account: AccountSettings, folders: List<Folder>, open: (SettingsCategory) -> Unit, onClear: () -> Unit,
) {
    if (searching) { SearchResults(query, open, onClear); return }
    when (category) {
        SettingsCategory.APPEARANCE -> AppearancePage(app)
        SettingsCategory.WRITING -> WritingPage(app)
        SettingsCategory.STYLUS -> StylusPage(app)
        SettingsCategory.ERASING -> ErasingPage()
        SettingsCategory.FOLLOW -> FollowPage()
        SettingsCategory.LIBRARY -> LibraryPage(folders)
        SettingsCategory.BACKUP -> BackupPage(backup)
        SettingsCategory.WORKFLOW -> WorkflowPage()
        SettingsCategory.MISTAKES -> MistakesPage()
        SettingsCategory.ACCOUNT -> AccountPage(account)
    }
}

@Composable private fun SettingsSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query, onValueChange = onQuery, modifier = modifier.fillMaxWidth(), singleLine = true, shape = FolioShapes.extraLarge,
        label = { Text("Search settings") },
        placeholder = { Text("Theme, backup, pencil or timer") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton({ onQuery(""); keyboard?.hide() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Clear, "Clear search") } }) else null,
    )
}

// ---- Home (phone) -------------------------------------------------------------------------------

@Composable private fun SettingsHome(app: AppSettings, backup: BackupSettings, open: (SettingsCategory) -> Unit) {
    SettingsSection.entries.forEach { section ->
        SettingsGroup(section.title) {
            val items = SettingsCategory.entries.filter { it.section == section }
            items.forEachIndexed { index, item ->
                if (index > 0) SettingsDivider()
                CategoryRow(item, summaryFor(item, app, backup)) { open(item) }
            }
        }
    }
    Text(
        "Changes save as you make them. A reset arrow appears beside any setting that differs from its default.",
        Modifier.padding(horizontal = FolioSpacing.dp16), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun summaryFor(item: SettingsCategory, app: AppSettings, backup: BackupSettings): String = when (item) {
    SettingsCategory.APPEARANCE -> "${app.themeMode.label} · ${app.themePalette.label}"
    SettingsCategory.BACKUP -> if (backup.progress != null) "Backup in progress" else if (backup.tree != null) "Automatic backup on" else "Automatic backup off"
    // The rows that hold everyday toggles say how they stand right now.
    SettingsCategory.WRITING -> "Finger drawing ${if (app.finger) "on" else "off"} · Shapes ${if (app.shapeRecognition) "on" else "off"}"
    SettingsCategory.STYLUS -> "Pencil double-tap: ${app.stylus.label}" + if (app.hapticsSupported) " · Haptics ${if (app.haptics) "on" else "off"}" else ""
    else -> item.summary
}

@Composable private fun CategoryRow(item: SettingsCategory, summary: String, onClick: () -> Unit) {
    SettingsLinkRow(
        item.title, summary, onClick = onClick,
        leadingContent = { CategoryIcon(item.icon) },
    )
}

@Composable private fun CategoryIcon(icon: ImageVector) {
    Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

// ---- Sidebar (tablet) ---------------------------------------------------------------------------

@Composable private fun SettingsSidebar(
    selected: SettingsCategory, searching: Boolean, query: String, onQuery: (String) -> Unit,
    onSelect: (SettingsCategory) -> Unit, modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
        FolioScreenHeading("Settings")
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = FolioSpacing.dp12, end = FolioSpacing.dp12, bottom = FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            SettingsSearchField(query, onQuery, Modifier.padding(bottom = FolioSpacing.dp8))
            SettingsSection.entries.forEach { section ->
                Text(section.title, Modifier.padding(start = FolioSpacing.dp12, top = FolioSpacing.dp12, bottom = FolioSpacing.dp4), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SettingsCategory.entries.filter { it.section == section }.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.title) }, selected = !searching && item == selected, onClick = { onSelect(item) },
                        icon = { Icon(item.icon, null) }, modifier = Modifier.fillMaxWidth(), shape = FolioShapes.large,
                    )
                }
            }
        }
        }
    }
}

// ---- Search -------------------------------------------------------------------------------------

@Composable private fun SearchResults(query: String, open: (SettingsCategory) -> Unit, onClear: () -> Unit) {
    val hits = remember(query) { SettingsIndex.search(query) }
    if (hits.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp32), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(Icons.Rounded.Search, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Nothing matches “${query.trim()}”", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text("Try a word such as theme, backup, pencil or timer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton(onClear, shapes = ButtonDefaults.shapes()) { Text("Clear search") }
        }
        return
    }
    SettingsGroup(if (hits.size == 1) "1 result" else "${hits.size} results") {
        hits.forEachIndexed { index, hit ->
            if (index > 0) SettingsDivider()
            SettingsLinkRow(hit.title, hit.category.title, onClick = { open(hit.category) }, leadingContent = { CategoryIcon(hit.category.icon) })
        }
    }
}

// ---- Model --------------------------------------------------------------------------------------

private enum class SettingsSection(val title: String) {
    PERSONAL("Personalise"), WRITING("Writing"), NOTEBOOKS("Notebooks"), STUDY("Study"), APP("Folio"),
}

private enum class SettingsCategory(val title: String, val summary: String, val icon: ImageVector, val section: SettingsSection) {
    APPEARANCE("Appearance", "Theme, accent colour, text size and display", Icons.Rounded.Palette, SettingsSection.PERSONAL),
    WRITING("Writing & tools", "Toolbar, finger drawing, shapes, default tool and text", Icons.Rounded.Edit, SettingsSection.WRITING),
    STYLUS("Stylus & touch", "Pencil shortcut, haptics, palm rejection, gestures", Icons.Rounded.Gesture, SettingsSection.WRITING),
    ERASING("Erasing", "Pressure, whole strokes and scribble to erase", Icons.Rounded.CleaningServices, SettingsSection.WRITING),
    FOLLOW("Writing follow", "Page movement, direction and line return", Icons.Rounded.AutoStories, SettingsSection.WRITING),
    LIBRARY("Library & covers", "Shelf layout, sorting, paper and covers", Icons.AutoMirrored.Rounded.MenuBook, SettingsSection.NOTEBOOKS),
    BACKUP("Backup & restore", "Automatic backup, library files, exclusions", Icons.Rounded.Backup, SettingsSection.NOTEBOOKS),
    WORKFLOW("Timer & workspace", "Exam timer, image export and split view", Icons.Rounded.Timer, SettingsSection.STUDY),
    MISTAKES("Mistake practice", "Paper for handwritten practice pages", Icons.Rounded.EditNote, SettingsSection.STUDY),
    ACCOUNT("Account & updates", "Focal account and app updates", Icons.Rounded.AccountCircle, SettingsSection.APP),
}

/** What search can find: each setting by name plus the words someone might use for it. */
private object SettingsIndex {
    class Hit(val category: SettingsCategory, val title: String, val keywords: String)

    private fun e(c: SettingsCategory, title: String, keywords: String = "") = Hit(c, title, keywords)

    private val all = listOf(
        e(SettingsCategory.APPEARANCE, "Theme", "light dark system mode night"),
        e(SettingsCategory.APPEARANCE, "Colour theme", "color palette sage ocean plum warm mono monochrome greyscale grey neutral wallpaper dynamic material you"),
        e(SettingsCategory.APPEARANCE, "Black and white theme", "mono monochrome greyscale grey colourless neutral no colour pure"),
        e(SettingsCategory.APPEARANCE, "Pure black dark", "amoled oled black"),
        e(SettingsCategory.APPEARANCE, "Accent colour", "color tint highlight picker custom"),
        e(SettingsCategory.APPEARANCE, "Text size", "font bigger smaller scale"),
        e(SettingsCategory.APPEARANCE, "Fullscreen", "status bar gesture immersive"),
        e(SettingsCategory.APPEARANCE, "Keep screen on", "sleep display awake"),
        e(SettingsCategory.WRITING, "Draw with a finger", "touch palm scroll"),
        e(SettingsCategory.WRITING, "Tidy up shapes", "shape recognition hold delay pause stylus line arrow circle oval square rectangle triangle diamond pentagon hexagon star"),
        e(SettingsCategory.WRITING, "Live shape measurements", "length angle width height"),
        e(SettingsCategory.WRITING, "Snap to grid and 15°", "math maths angle graph"),
        e(SettingsCategory.WRITING, "Toolbar style", "goodnotes folio tabs pills layout editor bar"),
        e(SettingsCategory.WRITING, "Toolbar tools and order", "customise customize edit hide reorder pin presets strip"),
        e(SettingsCategory.WRITING, "Toolbar undo and redo", "history buttons"),
        e(SettingsCategory.WRITING, "Toolbar colour dots, presets and timer", "color indicator pinned focal chip ink options tabs"),
        e(SettingsCategory.WRITING, "Tool in hand", "default tool pen highlighter open lasso hand sticky note shape"),
        e(SettingsCategory.WRITING, "Typed text size and alignment", "font default left centre right"),
        e(SettingsCategory.STYLUS, "Pencil double-tap", "stylus shortcut action oneplus oppo"),
        e(SettingsCategory.STYLUS, "Pen haptics", "vibration buzz bluetooth"),
        e(SettingsCategory.STYLUS, "Palm rejection", "resting hand touch milliseconds"),
        e(SettingsCategory.STYLUS, "Two-finger tap to undo", "gesture redo three fingers"),
        e(SettingsCategory.ERASING, "Pressure-sensitive eraser", "size grows"),
        e(SettingsCategory.ERASING, "Single-stroke eraser", "previous tool"),
        e(SettingsCategory.ERASING, "Whole-stroke eraser", "remove entire stroke"),
        e(SettingsCategory.ERASING, "Scribble to erase", "scrub sensitivity"),
        e(SettingsCategory.FOLLOW, "Writing follow", "page move glide scroll automatically"),
        e(SettingsCategory.FOLLOW, "Reading direction", "left right rtl ltr"),
        e(SettingsCategory.FOLLOW, "Hand holding the pen", "left handed right handed"),
        e(SettingsCategory.FOLLOW, "Automatic line return", "next line answer area"),
        e(SettingsCategory.FOLLOW, "Pale edge strip width", "tinted rectangle bar length visual hint hidden"),
        e(SettingsCategory.FOLLOW, "Writing position and line spacing", "height column millimetres learn adaptive"),
        e(SettingsCategory.FOLLOW, "Movement feel and timing", "relaxed balanced responsive delay glide speed pause threshold tolerance"),
        e(SettingsCategory.FOLLOW, "Answer areas", "detect outline switch boundaries"),
        e(SettingsCategory.FOLLOW, "Zoom pane height and auto peek", "enlarged panel size overview"),
        e(SettingsCategory.LIBRARY, "Sort by", "order shelf recent name"),
        e(SettingsCategory.LIBRARY, "Library layout", "covers compact list view grid"),
        e(SettingsCategory.LIBRARY, "Default paper", "new notebook ruled grid dots blank"),
        e(SettingsCategory.LIBRARY, "Default cover colour", "new notebook"),
        e(SettingsCategory.LIBRARY, "First page as cover", "thumbnail shelf"),
        e(SettingsCategory.LIBRARY, "Your cover colours", "custom add remove"),
        e(SettingsCategory.LIBRARY, "Quick note folder", "quick canvas infinite save folder default"),
        e(SettingsCategory.BACKUP, "Automatic backup", "folder restore point cloud drive daily"),
        e(SettingsCategory.BACKUP, "Restore from backup folder", "recover"),
        e(SettingsCategory.BACKUP, "Backup exclusions", "leave out textbook pdf skip"),
        e(SettingsCategory.BACKUP, "Library backup file", "save restore export zip portable move device"),
        e(SettingsCategory.WORKFLOW, "Exam timer", "custom minutes reading time"),
        e(SettingsCategory.WORKFLOW, "Resume timer on pen down", "pause start"),
        e(SettingsCategory.WORKFLOW, "Stop timer after inactivity", "idle"),
        e(SettingsCategory.WORKFLOW, "Page image sharpness", "png export scale quality"),
        e(SettingsCategory.WORKFLOW, "Split view balance", "workspace editor share companion pane"),
        e(SettingsCategory.WORKFLOW, "Share button long-press", "share export quick action pdf png page notebook"),
        e(SettingsCategory.MISTAKES, "Mistake practice paper", "question wrong revision review handwriting infinite canvas"),
        e(SettingsCategory.ACCOUNT, "Focal account", "sign in sync study sessions mistakes"),
        e(SettingsCategory.ACCOUNT, "Check for updates", "github release version launch"),
        e(SettingsCategory.ACCOUNT, "Enable experimental builds", "folio server beta update channel"),
    )

    /** Every word typed must appear in the title, keywords or category name. */
    fun search(query: String): List<Hit> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { hit ->
            val haystack = "${hit.title} ${hit.keywords} ${hit.category.title}".lowercase()
            words.all { it in haystack }
        }
    }
}
