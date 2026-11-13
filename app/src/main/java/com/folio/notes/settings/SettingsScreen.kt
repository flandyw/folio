@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.folio.notes.settings

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
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folio.notes.backup.LibraryAutoBackup
import com.folio.notes.ink.pen.PenHapticsManager
import com.folio.notes.input.StylusShortcut
import com.folio.notes.model.Folder
import com.folio.notes.ui.FolioDestinationInset
import com.folio.notes.ui.FolioScreenHeading
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.LocalDestinationTopGap
import com.folio.notes.ui.ThemeMode
import com.folio.notes.ui.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalOpenBackupSettings = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalOpenWritingFollowSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Settings: eight pages, one per thing you might want to change. A phone shows the list and
 * drills into a page; a tablet keeps the list beside the page. Search finds a setting by name and
 * scrolls to it. Every control saves as it changes.
 */
@Composable fun SettingsScreen(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, themePalette: ThemePalette, onThemePalette: (ThemePalette) -> Unit, amoled: Boolean, onAmoled: (Boolean) -> Unit, finger: Boolean, onFinger: (Boolean) -> Unit, stylus: StylusShortcut, onStylus: (StylusShortcut) -> Unit, haptics: Boolean, onHaptics: (Boolean) -> Unit, @Suppress("UNUSED_PARAMETER") shapeRecognition: Boolean, @Suppress("UNUSED_PARAMETER") onShapeRecognition: (Boolean) -> Unit, onCheckForUpdates: () -> Unit, updateChecking: Boolean, onBack: () -> Unit, onFocal: () -> Unit = {}, onCheckGitHub: () -> Unit = onCheckForUpdates, onBackupLibrary: () -> Unit = {}, onRestoreLibrary: () -> Unit = {}, onChooseBackupFolder: () -> Unit = {}, onBackupNow: () -> Unit = {}, onDisableAutoBackup: () -> Unit = {}, backupBusy: Boolean = false, onRestoreAutomaticBackup: () -> Unit = {}, backupExcludedCount: Int = 0, onBackupExclusions: () -> Unit = {}, updateBusy: Boolean = updateChecking, updateContent: @Composable () -> Unit = {}, folders: List<Folder> = emptyList(), startWithWritingFollow: Boolean = false, startWithBackup: Boolean = false, onChatGpt: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = rememberPrefs()
    val focus = LocalFocusManager.current
    var page by rememberSaveable { mutableStateOf(if (startWithBackup) SettingsPage.BACKUP else if (startWithWritingFollow) SettingsPage.FOLLOW else null) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val target = remember { mutableStateOf("") }

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
        remember(context) { PenHapticsManager.isSupported(context) }, Build.VERSION.SDK_INT >= 31,
    )
    val backup = BackupSettings(
        backupTree, backupFolderName, backupLastSuccess, backupLastError, backupBusy || backupProgress != null, backupExcludedCount,
        onChooseBackupFolder, onBackupNow, onDisableAutoBackup, onBackupLibrary, onRestoreLibrary, onRestoreAutomaticBackup, onBackupExclusions, backupProgress,
    )
    val account = AccountSettings(onFocal, onCheckForUpdates, onCheckGitHub, updateChecking, updateBusy, updateContent, onChatGpt)

    val open: (SettingsPage) -> Unit = { focus.clearFocus(); page = it; query = "" }
    val summary: (SettingsPage) -> String = {
        when (it) {
            SettingsPage.APPEARANCE -> "${themeMode.label} · ${themePalette.label}"
            SettingsPage.BACKUP -> if (backupProgress != null) "Backup in progress" else if (backupTree != null) "Automatic backup on" else "Automatic backup off"
            else -> it.summary
        }
    }

    BackHandler(enabled = searching || page != null) { if (searching) query = "" else page = null }

    // Same top gap as the shell gives its destinations, so this heading lines up with theirs.
    val topGap = (FolioSpacing.dp16 - WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()).coerceAtLeast(0.dp)
    CompositionLocalProvider(LocalDestinationTopGap provides topGap, LocalSettingsTarget provides target) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 840.dp
            // A tablet always has a page open beside the list; a phone shows the list until one is picked.
            val shown = page ?: SettingsPage.APPEARANCE.takeIf { wide }
            val body: @Composable ColumnScope.() -> Unit = {
                when {
                    searching -> SearchResults(query, onOpen = { hit -> target.value = hit.title; open(hit.page) }, onClear = { query = "" })
                    shown == null -> SettingsGroup {
                        SettingsPage.entries.forEachIndexed { index, item ->
                            if (index > 0) SettingsDivider()
                            SettingsLinkRow(item.title, summary(item), onClick = { open(item) }, leadingContent = { PageIcon(item.icon) })
                        }
                    }
                    else -> when (shown) {
                        SettingsPage.APPEARANCE -> AppearancePage(app)
                        SettingsPage.PEN -> PenPage(app)
                        SettingsPage.EDITOR -> EditorPage()
                        SettingsPage.FOLLOW -> FollowPage()
                        SettingsPage.LIBRARY -> LibraryPage(folders)
                        SettingsPage.BACKUP -> BackupPage(backup)
                        SettingsPage.STUDY -> StudyPage()
                        SettingsPage.ACCOUNT -> AccountPage(account)
                    }
                }
            }
            val title = if (searching) "Search" else shown?.title ?: "Settings"
            if (wide) Row(Modifier.fillMaxSize()) {
                Surface(Modifier.width(320.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        FolioScreenHeading("Settings")
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp12).padding(bottom = FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                            SearchField(query, { query = it }, Modifier.padding(bottom = FolioSpacing.dp8))
                            SettingsPage.entries.forEach { item ->
                                NavigationDrawerItem(
                                    label = { Text(item.title) }, selected = !searching && item == shown, onClick = { open(item) },
                                    icon = { Icon(item.icon, null) }, modifier = Modifier.fillMaxWidth(), shape = FolioShapes.large,
                                )
                            }
                        }
                    }
                }
                VerticalDivider()
                Pane(title, onBack, "Close settings", Modifier.weight(1f), body)
            } else Pane(
                title,
                onNavigate = { if (searching) query = "" else if (page != null) page = null else onBack() },
                navLabel = if (shown == null && !searching) "Close settings" else "Back to settings",
                Modifier.fillMaxSize(),
            ) {
                if (shown == null || searching) SearchField(query, { query = it })
                body()
            }
        }
    }
}

/** The shared screen heading over a centred, scrolling column of groups. */
@Composable private fun Pane(title: String, onNavigate: () -> Unit, navLabel: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            FolioScreenHeading(title, leading = { IconButton(onNavigate, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, navLabel) } })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            // Keyed by title so each page starts scrolled to its top.
            key(title) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = FolioDestinationInset).padding(bottom = FolioSpacing.dp32),
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp24),
                    content = content,
                )
            }
        }
    }
}

@Composable private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query, onValueChange = onQuery, modifier = modifier.fillMaxWidth(), singleLine = true, shape = FolioShapes.extraLarge,
        placeholder = { Text("Search settings") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton({ onQuery(""); keyboard?.hide() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Clear, "Clear search") } }) else null,
    )
}

@Composable private fun PageIcon(icon: ImageVector) {
    Surface(shape = FolioShapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@Composable private fun SearchResults(query: String, onOpen: (SettingsIndex.Hit) -> Unit, onClear: () -> Unit) {
    val hits = remember(query) { SettingsIndex.search(query) }
    if (hits.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = FolioSpacing.dp32), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Text("Nothing matches “${query.trim()}”", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text("Pen smoothing, eraser behaviour and shelf sorting are changed in the editor and Library.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton(onClear, shapes = ButtonDefaults.shapes()) { Text("Clear search") }
        }
        return
    }
    SettingsGroup {
        hits.forEachIndexed { index, hit ->
            if (index > 0) SettingsDivider()
            SettingsLinkRow(hit.title, hit.page.title, onClick = { onOpen(hit) }, leadingContent = { PageIcon(hit.page.icon) })
        }
    }
}

private enum class SettingsPage(val title: String, val summary: String, val icon: ImageVector) {
    APPEARANCE("Appearance", "Theme, colours, text size and sidebar", Icons.Rounded.Palette),
    PEN("Pen & touch", "Finger drawing, palm rejection, pencil, shapes and scribble", Icons.Rounded.Gesture),
    EDITOR("Editor", "Toolbar, starting tool, zoom, text and keyboard", Icons.Rounded.Edit),
    FOLLOW("Writing follow", "Move the page as you write", Icons.Rounded.AutoStories),
    LIBRARY("Library", "New notebooks, covers, quick notes and swipes", Icons.AutoMirrored.Rounded.MenuBook),
    BACKUP("Backup & restore", "Automatic backup and library files", Icons.Rounded.Backup),
    STUDY("Study", "Exam timer and mistake practice", Icons.Rounded.Timer),
    ACCOUNT("Account & updates", "Focal, ChatGPT and app updates", Icons.Rounded.AccountCircle),
}

/**
 * What search can find. A title here is the title of the row or group on its page, which is how a
 * result scrolls to it (`Modifier.settingsTarget`), so keep the two in step.
 */
private object SettingsIndex {
    class Hit(val page: SettingsPage, val title: String, val keywords: String)

    private val all = listOf(
        SettingsPage.APPEARANCE to listOf(
            "Theme" to "light dark system mode night", "Pure black dark" to "amoled oled", "Colours" to "color palette sage ocean plum warm mono black white wallpaper dynamic",
            "Accent colour" to "color tint highlight", "Text size" to "font bigger smaller scale", "Larger controls" to "accessibility touch targets buttons",
            "Show tool names" to "labels icons", "Sidebar" to "navigation rail bottom bar hide mistakes study progress music vcaa", "Fullscreen" to "status bar immersive", "Keep screen on" to "sleep awake",
        ),
        SettingsPage.PEN to listOf(
            "Draw with a finger" to "touch scroll", "Palm rejection" to "resting hand", "Pan speed" to "fast navigation drag fling", "Two-finger tap to undo" to "gesture redo three",
            "Gestures and shortcuts" to "guide help", "Pencil double-tap" to "stylus shortcut oneplus oppo", "Double-tap buzz" to "haptics vibration bluetooth",
            "Buzz while writing" to "haptics vibration feedback texture feel", "Hold at stroke start" to "highlighter eraser switch tool", "Tilt widens the highlighter" to "angle marker",
            "Tidy up shapes" to "shape assist recognition hold line circle rectangle", "Scribble to erase" to "scrub",
        ),
        SettingsPage.EDITOR to listOf(
            "Tools and order" to "toolbar customise hide reorder", "Undo and redo" to "toolbar buttons", "Document tabs" to "toolbar open notebooks", "Timer on the toolbar" to "exam focal",
            "Starting tool" to "default pen highlighter", "Remember tools per notebook" to "last tool", "Pull past the end to add a page" to "scroll blank", "Maximum zoom" to "pinch limit",
            "Snap moved selections" to "align grid margins", "Typed text size" to "font text box", "Sticky note text size" to "font", "Tool letter shortcuts" to "keyboard keys",
            "Customise tool keys" to "keyboard remap", "Share button long-press" to "export pdf png copy",
        ),
        SettingsPage.FOLLOW to listOf(
            "Follow on pages" to "writing follow move glide scroll automatically", "What you write" to "text maths mode", "Writing direction" to "left right rtl ltr", "Hand holding the pen" to "left handed right handed",
            "Feel" to "relaxed balanced responsive speed pause", "Move the view" to "sideways down", "Writing height" to "vertical position", "Automatic line return" to "next line",
            "Learn my line spacing" to "adaptive", "Line spacing" to "millimetres ruled", "Detect answer areas automatically" to "pdf lines", "Reset writing follow" to "defaults",
        ),
        SettingsPage.LIBRARY to listOf(
            "Paper" to "new notebook default ruled grid dots blank", "Cover design and colour" to "new notebook default", "First page as cover" to "thumbnail shelf",
            "Your cover colours" to "custom add remove", "Quick notes and canvases" to "folder save", "Swipe right" to "gesture favourite trash tags move share", "Swipe left" to "gesture favourite trash tags move share",
        ),
        SettingsPage.BACKUP to listOf(
            "Automatic backup" to "folder cloud drive daily back up now", "Restore from backup folder" to "recover", "Save library backup" to "export file",
            "Restore library backup" to "import file recover", "Excluded notebooks" to "exclusions leave out textbook skip",
        ),
        SettingsPage.STUDY to listOf(
            "Custom timer" to "exam minutes duration", "Reading time" to "exam minutes", "Resume on pen down" to "timer pause", "Stop after inactivity" to "timer idle",
            "Paper guide" to "mistake practice", "Starting zoom" to "mistake practice handwriting size", "Confirm answer reveal and skip" to "mistake review single tap",
        ),
        SettingsPage.ACCOUNT to listOf(
            "Focal account" to "sign in sync", "ChatGPT" to "ai feedback sign in", "Check on launch" to "automatic updates", "Experimental builds" to "beta channel", "Updates" to "check version github release",
        ),
    ).flatMap { (page, rows) -> rows.map { (title, keywords) -> Hit(page, title, keywords) } }

    /** Every word typed must appear in the title, keywords or page name. */
    fun search(query: String): List<Hit> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { hit ->
            val haystack = "${hit.title} ${hit.keywords} ${hit.page.title}".lowercase()
            words.all { it in haystack }
        }
    }
}
