@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.backup.BackupExclusionsPanel
import com.folio.notes.backup.LibraryAutoBackup
import com.folio.notes.chatgpt.ChatGptPanel
import com.folio.notes.export.ExportPagesDialog
import com.folio.notes.export.NoteExporter
import com.folio.notes.export.PageExportFormat
import com.folio.notes.export.PageExportRequest
import com.folio.notes.export.PdfExportMode
import com.folio.notes.export.PdfQualitySection
import com.folio.notes.export.ShareShortcut
import com.folio.notes.export.exportCacheDir
import com.folio.notes.export.exportShareMimeType
import com.folio.notes.export.exportShareUsesMultipleUris
import com.folio.notes.export.normalizeExportIndices
import com.folio.notes.export.pruneExportCache
import com.folio.notes.export.selectiveExportFilename
import com.folio.notes.export.selectiveShareFilenames
import com.folio.notes.export.shareExportChooserTitle
import com.folio.notes.export.shouldShowPdfQuality
import com.folio.notes.export.uniqueShareFilename
import com.folio.notes.ink.pen.PenHapticsManager
import com.folio.notes.input.StylusShortcut
import com.folio.notes.input.guardUiTouches
import com.folio.notes.library.LibraryScreen
import com.folio.notes.library.semanticsLabel
import com.folio.notes.mistakes.MistakesScreen
import com.folio.notes.mistakes.MistakesViewModel
import com.folio.notes.model.Notebook
import com.folio.notes.model.Paper
import com.folio.notes.model.hiddenFromLibrary
import com.folio.notes.music.MusicScreen
import com.folio.notes.pdf.PdfImportDialog
import com.folio.notes.settings.LocalOpenBackupSettings
import com.folio.notes.settings.LocalOpenWritingFollowSettings
import com.folio.notes.settings.SettingsScreen
import com.folio.notes.settings.rememberPref
import com.folio.notes.study.FocalAccountPanel
import com.folio.notes.study.StudyTimerScreen
import com.folio.notes.ui.AppTheme
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.FolioTheme
import com.folio.notes.ui.LocalDestinationTopGap
import com.folio.notes.ui.ThemeMode
import com.folio.notes.ui.ThemePalette
import com.folio.notes.ui.folioEntrance
import com.folio.notes.ui.rememberAccentState
import com.folio.notes.update.UpdateStatus
import com.folio.notes.update.commitUpdateSession
import com.folio.notes.workspace.PickerPurpose
import com.folio.notes.workspace.WorkspaceLibraryControls
import com.folio.notes.workspace.WorkspacePicker
import com.folio.notes.workspace.WorkspaceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun FolioApp(model: FolioViewModel, shortcutRequest: Int) {
    val library: LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by library.state.collectAsStateWithLifecycle()
    val mistakes: MistakesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showMistakes by rememberSaveable { mutableStateOf(false) }
    var showStudy by rememberSaveable { mutableStateOf(false) }
    var showMusic by rememberSaveable { mutableStateOf(false) }
    // Keep the editor open while the Library temporarily chooses another workspace document.
    var workspaceLibraryPurpose by rememberSaveable { mutableStateOf<PickerPurpose?>(null) }
    var focalAccountOpen by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, mistakes) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                mistakes.requestSync()
            }
        }
    }
    DisposableEffect(lifecycleOwner, mistakes) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) mistakes.requestSync()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) mistakes.setForeground(true)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) mistakes.setForeground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mistakes.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { mistakes.setForeground(false); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current
    val chatGpt = remember(context.applicationContext) { (context.applicationContext as FolioApplication).chatGpt }
    DisposableEffect(lifecycleOwner, chatGpt) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) chatGpt.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) chatGpt.setForeground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        chatGpt.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { chatGpt.setForeground(false); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val prefs = remember { context.getSharedPreferences("preferences", 0) }
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.of(prefs.getString(ThemeMode.PREF_KEY, null))) }
    var themePalette by rememberSaveable {
        mutableStateOf(
            ThemePalette.migrate(
                prefs.getString(ThemePalette.PREF_KEY, null),
                prefs.getBoolean("dynamic", false).takeIf { prefs.contains("dynamic") }
            )
        )
    }
    var amoled by rememberSaveable { mutableStateOf(prefs.getBoolean(AppTheme.AMOLED_PREF_KEY, false)) }
    var splashDone by rememberSaveable { mutableStateOf(false) }
    // The accent and text scale live in preferences too, so they survive a restart and follow
    // edits made in Settings without the theme re-reading storage itself.
    val accentState = rememberAccentState()
    var uiTextScale by remember { mutableFloatStateOf(AppPrefs.uiTextScale(prefs.getFloat(AppPrefs.UI_TEXT_SCALE, AppPrefs.DEFAULT_UI_TEXT_SCALE).takeIf { prefs.contains(AppPrefs.UI_TEXT_SCALE) })) }
    var finger by remember { mutableStateOf(AppPrefs.fingerDrawing(prefs)) }
    var stylusShortcut by rememberSaveable { mutableStateOf(StylusShortcut.of(prefs.getString(StylusShortcut.PREF_KEY, null))) }
    var haptics by rememberSaveable { mutableStateOf(prefs.getBoolean("penHaptics", false)) }
    var shapeRecognition by rememberSaveable { mutableStateOf(prefs.getBoolean("shapeRecognition", false)) }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean(AppPrefs.FULLSCREEN, AppPrefs.DEFAULT_FULLSCREEN)) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean(AppPrefs.KEEP_SCREEN_ON, AppPrefs.DEFAULT_KEEP_SCREEN_ON)) }
    var autoUpdate by remember { mutableStateOf(prefs.getBoolean(AppPrefs.AUTO_UPDATE, AppPrefs.DEFAULT_AUTO_UPDATE)) }
    // Fullscreen is applied here (not only in MainActivity) so turning it off in Settings
    // brings the status bar and gesture pill back without restarting the app.
    LaunchedEffect(fullscreen) {
        val activity = context as? androidx.activity.ComponentActivity ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    // Long writing and exam-timer sessions should not go dark mid-thought.
    LaunchedEffect(keepScreenOn) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        if (keepScreenOn) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                AppPrefs.FULLSCREEN -> fullscreen = prefs.getBoolean(key, AppPrefs.DEFAULT_FULLSCREEN)
                AppPrefs.KEEP_SCREEN_ON -> keepScreenOn = prefs.getBoolean(key, AppPrefs.DEFAULT_KEEP_SCREEN_ON)
                AppPrefs.AUTO_UPDATE -> autoUpdate = prefs.getBoolean(key, AppPrefs.DEFAULT_AUTO_UPDATE)
                AppPrefs.UI_TEXT_SCALE -> uiTextScale = AppPrefs.uiTextScale(prefs.getFloat(key, AppPrefs.DEFAULT_UI_TEXT_SCALE))
                "shapeRecognition" -> shapeRecognition = prefs.getBoolean(key, false)
                AppPrefs.FINGER, AppPrefs.STYLUS_SEEN -> finger = AppPrefs.fingerDrawing(prefs)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    var settingsStartWithBackup by rememberSaveable { mutableStateOf(false) }
    var settingsStartWithFollow by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings) { if (!settings) { settingsStartWithFollow = false; settingsStartWithBackup = false } }
    var newNote by rememberSaveable { mutableStateOf(false) }
    val exportBusy = state.exporting
    var exportMenu by remember { mutableStateOf(false) }
    var chatGptOpen by rememberSaveable { mutableStateOf(false) }
    var chatGptStartAccount by rememberSaveable { mutableStateOf(false) }
    val updates = remember(context) { (context.applicationContext as FolioApplication).updates }
    val updateState by updates.state.collectAsStateWithLifecycle()
    val experimentalUpdates by rememberPref(prefs, AppPrefs.EXPERIMENTAL_UPDATES) {
        it.getBoolean(AppPrefs.EXPERIMENTAL_UPDATES, AppPrefs.DEFAULT_EXPERIMENTAL_UPDATES)
    }
    LaunchedEffect(experimentalUpdates) { updates.sourceChanged(experimentalUpdates) }
    val snackbar = remember { SnackbarHostState() }
    val focalState by (context.applicationContext as FolioApplication).focalStudy.state.collectAsStateWithLifecycle()
    // Not saveable on purpose: the counter lives in the manager and restarts at zero with the
    // process, so a restored higher value would swallow every later notice.
    var shownRemoteNoticeId by remember { mutableLongStateOf(focalState.remoteNoticeId) }
    LaunchedEffect(focalState.remoteNoticeId) {
        if (focalState.remoteNoticeId > shownRemoteNoticeId) focalState.remoteNotice?.let {
            shownRemoteNoticeId = focalState.remoteNoticeId
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
        }
    }
    val exporter = remember { NoteExporter(model.repository) }
    var pdfExportMode by remember {
        mutableStateOf(AppPrefs.pdfExportMode(prefs.getString(AppPrefs.EXPORT_PDF_MODE, null)))
    }
    fun rememberPdfMode(mode: PdfExportMode) {
        pdfExportMode = mode
        prefs.edit().putString(AppPrefs.EXPORT_PDF_MODE, mode.name).apply()
    }
    var pagesExportFormat by remember {
        mutableStateOf(AppPrefs.exportPagesFormat(prefs.getString(AppPrefs.EXPORT_PAGES_FORMAT, null)))
    }
    fun rememberPagesFormat(format: PageExportFormat) {
        pagesExportFormat = format
        prefs.edit().putString(AppPrefs.EXPORT_PAGES_FORMAT, format.name).apply()
    }
    val shareShortcut by rememberPref(prefs, AppPrefs.SHARE_LONG_PRESS) { AppPrefs.shareShortcut(it.getString(AppPrefs.SHARE_LONG_PRESS, null)) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.preparePdfImport(uris) }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importArchive) }
    val saveArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { target -> state.active?.let { model.exportArchive(it, target) } }
    }
    var backupExclusionsOpen by rememberSaveable { mutableStateOf(false) }
    val saveLibraryBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(model::backupLibrary)
    }
    val openLibraryBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::restoreLibrary)
    }
    val restoreBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { tree -> model.restoreAutomaticBackup(tree) }
    }
    val pickBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                LibraryAutoBackup.enable(context.applicationContext, uri)
            } catch (e: Exception) {
                model.reportError("Couldn't keep access to that backup folder: ${e.message.orEmpty()}")
            }
        }
    }
    fun exportTo(uri: android.net.Uri?) {
        val request = model.pendingExport ?: return
        model.pendingExport = null
        if (uri == null) return
        val pngScale = AppPrefs.pngScale(prefs.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { prefs.contains(AppPrefs.EXPORT_PNG_SCALE) })
        model.export {
            exporter.write(context.applicationContext, uri, request, pngScale)
            // The request already carries the user-chosen PDF mode, so Save and Share
            // never diverge: both honour the same explicit choice.
            val message = when (request.format) {
                PageExportFormat.PDF -> if (request.indices.size == request.note.pages.size) "Notebook saved as PDF"
                    else if (request.indices.size == 1) "Page ${request.indices.first() + 1} saved as PDF" else "${request.indices.size} pages saved as PDF"
                PageExportFormat.PNG -> if (request.indices.size == 1) "Page ${request.indices.first() + 1} saved to gallery" else "${request.indices.size} pages saved as images"
            }
            model.reportError(message)
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { exportTo(it) }
    val savePngZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { exportTo(it) }
    var pageExportDialog by remember { mutableStateOf(false) }
    // Pre-Q gallery saves need WRITE_EXTERNAL_STORAGE; the pending page is retried once granted.
    var pendingGallerySave by remember { mutableStateOf<PageExportRequest?>(null) }
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingGallerySave
        pendingGallerySave = null
        if (!granted) {
            model.reportError("Gallery needs storage permission to save the image")
            return@rememberLauncherForActivityResult
        }
        if (pending != null) {
            val pngScale = AppPrefs.pngScale(prefs.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { prefs.contains(AppPrefs.EXPORT_PNG_SCALE) })
            val scoped = pending
            model.export {
                exporter.saveSinglePngToGallery(context.applicationContext, scoped.note, scoped.indices.first(), pngScale)
                model.reportError("Page ${scoped.indices.first() + 1} saved to gallery")
            }
        }
    }
    fun savePageToGallery(request: PageExportRequest) {
        val indices = normalizeExportIndices(request.indices, request.note.pages.size)
        if (indices.isEmpty()) return
        val scoped = request.copy(indices = listOf(indices.first()))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingGallerySave = scoped
            galleryPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val pngScale = AppPrefs.pngScale(prefs.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { prefs.contains(AppPrefs.EXPORT_PNG_SCALE) })
        model.export {
            exporter.saveSinglePngToGallery(context.applicationContext, scoped.note, scoped.indices.first(), pngScale)
            model.reportError("Page ${scoped.indices.first() + 1} saved to gallery")
        }
    }
    fun launchExport(request: PageExportRequest) {
        val indices = normalizeExportIndices(request.indices, request.note.pages.size)
        if (indices.isEmpty()) return
        val scoped = request.copy(indices = indices)
        // A single PNG goes straight to the photo gallery (camera roll); several PNGs
        // still need a .zip destination, and PDFs keep the file picker.
        if (scoped.format == PageExportFormat.PNG && scoped.indices.size == 1) {
            savePageToGallery(scoped)
            return
        }
        model.pendingExport = scoped
        when (scoped.format) {
            PageExportFormat.PDF -> savePdf.launch(selectiveExportFilename(scoped.note, scoped.indices, scoped.format))
            PageExportFormat.PNG -> savePngZip.launch(selectiveExportFilename(scoped.note, scoped.indices, scoped.format))
        }
    }
    fun shareExport(request: PageExportRequest) {
        val indices = normalizeExportIndices(request.indices, request.note.pages.size)
        if (indices.isEmpty()) return
        val scoped = request.copy(indices = indices)
        val pngScale = AppPrefs.pngScale(prefs.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { prefs.contains(AppPrefs.EXPORT_PNG_SCALE) })
        model.export {
            try {
                if (exportShareUsesMultipleUris(scoped.format, scoped.indices.size)) {
                    val files = withContext(Dispatchers.IO) {
                        val parent = exportCacheDir(context.cacheDir)
                        pruneExportCache(parent)
                        val dir = File(parent, "share-${System.currentTimeMillis()}").apply { mkdirs() }
                        exporter.writeSeparatePngs(dir, scoped.note, scoped.indices, pngScale)
                    }
                    if (files.isEmpty()) error("Nothing to share")
                    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = exportShareMimeType(scoped.format)
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        clipData = ClipData.newRawUri("Pages", uris.first()).apply {
                            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.applicationContext.startActivity(
                        Intent.createChooser(send, shareExportChooserTitle(scoped.indices, scoped.format))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    val file = withContext(Dispatchers.IO) {
                        val dir = exportCacheDir(context.cacheDir)
                        pruneExportCache(dir)
                        val name = uniqueShareFilename(selectiveShareFilenames(scoped.note, scoped.indices, scoped.format).first())
                        File(dir, name).also { out ->
                            out.outputStream().use { stream ->
                                when (scoped.format) {
                                    PageExportFormat.PDF -> exporter.writePdf(
                                        stream, scoped.note, scoped.indices,
                                        scoped.pdfMode, context.applicationContext
                                    )
                                    PageExportFormat.PNG -> exporter.write(stream, scoped, pngScale)
                                }
                            }
                        }
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = exportShareMimeType(scoped.format)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Pages", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.applicationContext.startActivity(
                        Intent.createChooser(send, shareExportChooserTitle(scoped.indices, scoped.format))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (_: ActivityNotFoundException) {
                model.reportError("No app can share these pages")
            }
        }
    }
    fun copyPage(note: Notebook, index: Int) {
        val indices = normalizeExportIndices(listOf(index), note.pages.size)
        if (indices.isEmpty()) return
        val request = PageExportRequest(note, indices, PageExportFormat.PNG)
        val pngScale = AppPrefs.pngScale(prefs.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { prefs.contains(AppPrefs.EXPORT_PNG_SCALE) })
        model.export {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
                File(dir, uniqueShareFilename(selectiveShareFilenames(note, indices, PageExportFormat.PNG).first())).also { out ->
                    out.outputStream().use { exporter.write(it, request, pngScale) }
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(context.contentResolver, "Page", uri))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                android.widget.Toast.makeText(context, "Page copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            withContext(Dispatchers.IO) { pruneExportCache(file.parentFile!!) }
        }
    }
    /** Sends a whole notebook as one PDF through the share sheet; the export menu and the share shortcut both use it. */
    fun shareWholeNotebook(note: Notebook) {
        val mode = pdfExportMode
        model.export {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = exportCacheDir(context.cacheDir)
                    pruneExportCache(dir)
                    File(dir, "${exporter.filename(note)}-${System.currentTimeMillis()}.pdf").also { file ->
                        file.outputStream().use {
                            exporter.writePdf(it, note, note.pages.indices.toList(), mode, context.applicationContext)
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("Notebook", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                context.applicationContext.startActivity(Intent.createChooser(send, "Share notebook").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                model.reportError("No app can share this notebook")
            }
        }
    }
    /** Runs the share action picked in Settings; a long-press on the editor's share button lands here. */
    fun runShareShortcut() {
        val note = state.active ?: return
        val page = listOf(state.pageIndex)
        when (shareShortcut) {
            ShareShortcut.OFF -> Unit
            ShareShortcut.NOTEBOOK_PDF -> shareWholeNotebook(note)
            ShareShortcut.PAGE_PNG -> shareExport(PageExportRequest(note, page, PageExportFormat.PNG))
            ShareShortcut.PAGE_PDF -> shareExport(PageExportRequest(note, page, PageExportFormat.PDF, pdfExportMode))
            ShareShortcut.COPY_PAGE -> copyPage(note, state.pageIndex)
        }
    }
    // Null when the shortcut is off, so the share button keeps no long-press at all.
    val shareLongPress: (() -> Unit)? = if (shareShortcut == ShareShortcut.OFF) null else ({ runShareShortcut() })
    // Bluetooth is only ever asked for while the editor is open and the user has opted in.
    val hapticPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val allowed = granted.values.all { it }
        haptics = allowed
        prefs.edit().putBoolean("penHaptics", allowed).apply()
        if (!allowed) model.reportError("Double-tap buzz needs Bluetooth permission")
    }
    fun installUpdate() {
        val ready = updateState.ready ?: return
        if (!ready.file.isFile) {
            updates.discard()
            updates.message("The downloaded update is missing. Download it again.")
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            updates.message("Allow Folio to install updates, then tap Install update again.")
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }.onFailure { updates.message("Open Android settings to allow Folio to install updates.") }
            return
        }
        try {
            model.pauseForUpdate()
            commitUpdateSession(context, ready.file)
            updates.message("Installing Folio ${ready.update.versionName}…")
            // Keep the APK if the install is cancelled. The next app start removes installed builds.
        } catch (_: Exception) {
            updates.message("Android could not install the downloaded update.")
        }
    }
    // Deferred past first paint + library load so cold start never competes with a network fetch.
    LaunchedEffect(autoUpdate, experimentalUpdates) {
        if (!autoUpdate) return@LaunchedEffect
        updates.awaitReady()
        kotlinx.coroutines.delay(3000)
        updates.check(experimentalUpdates, manual = false)
    }
    LaunchedEffect(shortcutRequest, state.loading) { if (shortcutRequest > 0 && !state.loading) newNote = true }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); model.clearError() } }
    LaunchedEffect(state.loading, state.activeId) {
        if (!state.loading && state.activeId == null) workspaceLibraryPurpose = null
    }
    // A score's notebook is only ever open inside the Music reader; anywhere else it is closed again.
    LaunchedEffect(showMusic, state.activeId) {
        state.active?.takeIf { it.musicScoreId != null && !showMusic }?.let { model.closeMusicScore(it.id) }
    }
    BackHandler(state.active != null && !showMusic && !exportBusy && !showMistakes && !showStudy && workspaceLibraryPurpose == null) { model.close() }
    BackHandler(showStudy && !exportBusy) { showStudy = false }
    BackHandler(workspaceLibraryPurpose != null && !exportBusy) { workspaceLibraryPurpose = null }
    FolioTheme(mode = themeMode, palette = themePalette, amoled = amoled, accent = accentState.accent, textScale = uiTextScale) {
        // The shared library/mistakes shell owns safe edges; nested app bars consume them.
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!settings && (updateState.downloading != null || updateState.ready != null || updateState.downloadCandidate != null)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding()) {
                        UpdateStatus(updateState, updates::download, ::installUpdate, updates::discard)
                    }
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            // Full screen hides the status bar, so its inset is zero. Screens without a top bar of
            // their own (the library shelf, progress, the sidebar logo) get a minimum gap instead.
            val topGap = (FolioSpacing.dp16 - padding.calculateTopPadding()).coerceAtLeast(0.dp)
            CompositionLocalProvider(LocalDestinationTopGap provides topGap,
                LocalOpenWritingFollowSettings provides { settingsStartWithFollow = true; settings = true },
                LocalOpenBackupSettings provides { settingsStartWithBackup = true; settings = true }) {
            Box(Modifier.fillMaxSize().padding(padding).then(
                if (showMusic || showMistakes || showStudy || workspaceLibraryPurpose != null || state.active == null) Modifier.consumeWindowInsets(padding) else Modifier
            )) {
                val screen = when {
                    state.loading -> "loading"
                    state.loadFailed -> "failed"
                    // Sidebar destinations share one shell; switching panes must not replay
                    // the whole screen's entrance animation (including the sidebar).
                    showStudy || showMistakes || showMusic -> "library"
                    workspaceLibraryPurpose != null -> "workspace-library"
                    state.active != null -> "editor"
                    else -> "library"
                }
                Box(Modifier.fillMaxSize().folioEntrance(screen)) {
                    when {
                        state.loading -> ContainedLoadingIndicator(Modifier.align(Alignment.Center).semanticsLabel("Loading notebooks"))
                        state.loadFailed -> Column(Modifier.align(Alignment.Center).padding(FolioSpacing.dp24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                            Icon(Icons.Rounded.ErrorOutline, "Library failed to load")
                            Text("Your library couldn't be loaded", style = MaterialTheme.typography.titleLarge)
                            Text("Your stored files have been kept. Retry to open them.")
                            Button(model::loadLibrary, shapes = ButtonDefaults.shapes()) { Text("Retry") }
                        }
                        state.active != null && state.active?.musicScoreId == null && !showMistakes && !showStudy && !showMusic && workspaceLibraryPurpose == null -> WorkspaceScreen(
                            state, model, finger, haptics, shapeRecognition,
                            onSettings = { settings = true }, onExport = { if (model.exportSelection != null) pageExportDialog = true else exportMenu = true },
                            onShareLongPress = shareLongPress,
                            onChatGpt = { chatGptStartAccount = false; chatGptOpen = true },
                            onBrowseLibrary = { purpose ->
                                workspaceLibraryPurpose = purpose
                                showMistakes = false
                            },
                        )
                        else -> LibraryScreen(
                            state.copy(notes = remember(state.notes) { state.notes.filterNot { it.hiddenFromLibrary } }), model,
                            snackbar = snackbar,
                            onShareNotebook = ::shareWholeNotebook,
                            onMistakes = { workspaceLibraryPurpose = null; showStudy = false; showMusic = false; showMistakes = true },
                            showStudy = showStudy,
                            onStudy = { workspaceLibraryPurpose = null; showMistakes = false; showMusic = false; showStudy = true },
                            showMusic = showMusic,
                            onMusic = { workspaceLibraryPurpose = null; showMistakes = false; showStudy = false; showMusic = true },
                            musicContent = { onReaderMode ->
                                MusicScreen(model, state, finger, haptics, shapeRecognition, onReaderMode = onReaderMode, onSettings = { settings = true }, onBack = { showMusic = false })
                            },
                            studyContent = { StudyTimerScreen(state.notes.filterNot { it.hiddenFromLibrary }, state.timer,
                                onAccount = { focalAccountOpen = true }) },
                            onQuickCanvas = {
                                workspaceLibraryPurpose = null; showStudy = false; showMistakes = false; showMusic = false
                                model.create("Canvas · ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))}",
                                    AppPrefs.defaultCover(prefs.getInt(AppPrefs.DEFAULT_COVER, AppPrefs.DEFAULT_COVER_INDEX)),
                                    Paper.DOTS, infinite = true,
                                    pageCover = prefs.getBoolean(AppPrefs.DEFAULT_PAGE_COVER, AppPrefs.DEFAULT_PAGE_COVER_ENABLED),
                                    folderId = AppPrefs.quickFolderId(prefs.getString(AppPrefs.QUICK_FOLDER, null), state.folders))
                            },
                            onQuickNote = {
                                workspaceLibraryPurpose = null; showStudy = false; showMistakes = false; showMusic = false
                                model.create("Quick note · ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))}",
                                    AppPrefs.defaultCover(prefs.getInt(AppPrefs.DEFAULT_COVER, AppPrefs.DEFAULT_COVER_INDEX)),
                                    AppPrefs.defaultPaper(prefs.getString(AppPrefs.DEFAULT_PAPER, null)),
                                    pageCover = prefs.getBoolean(AppPrefs.DEFAULT_PAGE_COVER, AppPrefs.DEFAULT_PAGE_COVER_ENABLED),
                                    folderId = AppPrefs.quickFolderId(prefs.getString(AppPrefs.QUICK_FOLDER, null), state.folders))
                            },
                            onNew = { workspaceLibraryPurpose = null; showStudy = false; showMistakes = false; showMusic = false; newNote = true },
                            onImport = { workspaceLibraryPurpose = null; showStudy = false; showMistakes = false; showMusic = false; pdfPicker.launch(arrayOf("application/pdf")) },
                            onImportArchive = { workspaceLibraryPurpose = null; showStudy = false; showMistakes = false; showMusic = false; archivePicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                            onSettings = { settings = true },
                            showMistakes = showMistakes,
                            onLibrary = { showStudy = false; showMistakes = false; showMusic = false; if (workspaceLibraryPurpose == null) model.close() },
                            onOpenNotebook = { id ->
                                model.open(id)
                                workspaceLibraryPurpose = null
                            },
                            selectionCaption = workspaceLibraryPurpose?.let { WorkspacePicker.libraryCaption(it) },
                            onCancelSelection = { workspaceLibraryPurpose = null },
                            selectionControls = {
                                workspaceLibraryPurpose?.let { purpose ->
                                    WorkspaceLibraryControls(purpose, state, model,
                                        onOpenNotebook = { id -> model.open(id); workspaceLibraryPurpose = null })
                                }
                            },
                            mistakesContent = { onReviewMode ->
                                MistakesScreen(
                                    mistakes, model, state, finger, haptics, shapeRecognition,
                                    onBack = { showMistakes = false }, onSettings = { settings = true },
                                    onExport = { if (model.exportSelection != null) pageExportDialog = true else exportMenu = true }, onReviewMode = onReviewMode,
                                    onAccount = { focalAccountOpen = true }, onShareLongPress = shareLongPress,
                                )
                            },
                        )
                    }
                }
                if (state.busy || exportBusy) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
                        Surface(shape = FolioShapes.panel) {
                            Row(Modifier.padding(FolioSpacing.dp24), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp24)) {
                                LoadingIndicator(Modifier.size(48.dp)); Text(if (state.busy) (state.importProgress ?: "Opening your file…") else (state.backupProgress ?: "Preparing your export…"))
                            }
                        }
                }
            }
            }
        }
        if (state.pendingPdfImports.isNotEmpty() && !state.busy && !state.loading && !state.loadFailed) {
            PdfImportDialog(state, model::cancelPdfImport, model::updatePdfImport, model::removePdfImport, model) { folder, reviewed -> model.importPdfs(folder, reviewed) }
        }
        if (newNote) NewNotebookDialog(onDismiss = { newNote = false }, onCreate = { title, cover, paper, exam, pageCount, infinite, pageCover -> model.create(title, cover, paper, exam, pageCount, infinite = infinite, pageCover = pageCover); newNote = false })
        if (settings) Dialog(onDismissRequest = { settings = false }, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().guardUiTouches()) {
                SettingsScreen(
                    themeMode, { themeMode = it; prefs.edit().putString(ThemeMode.PREF_KEY, it.name).apply() },
                    themePalette, { themePalette = it; prefs.edit().putString(ThemePalette.PREF_KEY, it.name).apply() },
                    amoled, { amoled = it; prefs.edit().putBoolean(AppTheme.AMOLED_PREF_KEY, it).apply() },
                    finger, { finger = it; prefs.edit().putBoolean(AppPrefs.FINGER, it).apply() },
                    stylusShortcut, { stylusShortcut = it; prefs.edit().putString(StylusShortcut.PREF_KEY, it.name).apply() },
                    haptics, { wanted ->
                        if (!wanted) { haptics = false; prefs.edit().putBoolean("penHaptics", false).apply() }
                        else if (PenHapticsManager.isSupported(context)) hapticPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    },
                    shapeRecognition, { shapeRecognition = it; prefs.edit().putBoolean("shapeRecognition", it).apply() },
                    onCheckForUpdates = { updates.check(experimentalUpdates, manual = true) },
                    onCheckGitHub = { updates.check(experimental = false, manual = true) },
                    updateChecking = updateState.checking,
                    updateBusy = updateState.busy,
                    updateContent = { UpdateStatus(updateState, updates::download, ::installUpdate, updates::discard) },
                    onBack = { settings = false },
                    onFocal = { settings = false; focalAccountOpen = true },
                    onChatGpt = { chatGptStartAccount = true; chatGptOpen = true },
                    folders = state.folders,
                    startWithWritingFollow = settingsStartWithFollow,
                    startWithBackup = settingsStartWithBackup,
                    backupExcludedCount = state.notes.count { it.id in state.backupExcludedNotebookIds },
                    onBackupExclusions = { backupExclusionsOpen = true },
                    onBackupLibrary = { settings = false; saveLibraryBackup.launch("Folio-library-${java.time.LocalDate.now()}.folio-backup.zip") },
                    onRestoreAutomaticBackup = { settings = false; restoreBackupFolder.launch(null) },
                    onRestoreLibrary = { settings = false; openLibraryBackup.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                    onChooseBackupFolder = { pickBackupFolder.launch(null) },
                    onBackupNow = { LibraryAutoBackup.requestNow(context.applicationContext) },
                    onDisableAutoBackup = { LibraryAutoBackup.disable(context.applicationContext) },
                    backupBusy = state.busy || state.exporting || state.loading || state.loadFailed)
            }
        }
        if (backupExclusionsOpen) BackupExclusionsPanel(
            notes = state.notes.filterNot { it.hiddenFromLibrary },
            excludedIds = state.backupExcludedNotebookIds,
            onExcluded = model::setBackupExcluded,
            onDismiss = { backupExclusionsOpen = false }
        )
        if (focalAccountOpen) FocalAccountPanel(
            onDismiss = { focalAccountOpen = false },
            onMistakes = if (showMistakes && !settings) null else {
                { workspaceLibraryPurpose = null; focalAccountOpen = false; settings = false; showStudy = false; showMusic = false; showMistakes = true }
            },
            onStudy = if (showStudy && !settings) null else {
                { workspaceLibraryPurpose = null; focalAccountOpen = false; settings = false; showMistakes = false; showMusic = false; showStudy = true }
            },
        )
        if (chatGptOpen) ChatGptPanel(
            manager = chatGpt,
            model = model, onDismiss = { chatGptOpen = false }, startWithAccount = chatGptStartAccount,
        )
        if (exportMenu) FolioPanel(title = "Export notebook", onDismissRequest = { exportMenu = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = FolioSpacing.dp24, end = FolioSpacing.dp24, bottom = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                val activeNote = state.active
                if (activeNote != null && shouldShowPdfQuality(activeNote)) {
                    PdfQualitySection(selected = pdfExportMode, onSelect = ::rememberPdfMode)
                    Spacer(Modifier.height(FolioSpacing.dp4))
                }
                ExportOption(Icons.Rounded.Share, "Share notebook", "Send a PDF to another app") {
                    exportMenu = false
                    state.active?.let(::shareWholeNotebook)
                }
                ExportOption(Icons.Rounded.PictureAsPdf, "Save as PDF", "All pages, including your annotations") {
                    state.active?.let { launchExport(PageExportRequest(it, it.pages.indices.toList(), PageExportFormat.PDF, pdfExportMode)) }; exportMenu = false
                }
                ExportOption(Icons.Rounded.Image, "Save page as image", "A PNG of this page, saved to your gallery") {
                    state.active?.let { launchExport(PageExportRequest(it, listOf(state.pageIndex), PageExportFormat.PNG)) }; exportMenu = false
                }
                ExportOption(Icons.Rounded.Share, "Share page as image", "Send a PNG of this page to another app") {
                    state.active?.let { shareExport(PageExportRequest(it, listOf(state.pageIndex), PageExportFormat.PNG)) }; exportMenu = false
                }
                ExportOption(Icons.Rounded.ContentCopy, "Copy page as image", "Paste this page into another app") {
                    state.active?.let { copyPage(it, state.pageIndex) }; exportMenu = false
                }
                ExportOption(Icons.Rounded.AutoStories, "Export specific pages", "Choose pages for a PDF or PNG images") {
                    exportMenu = false; pageExportDialog = true
                }
                ExportOption(Icons.Rounded.FolderZip, "Save as Folio backup", "A compact file with every page, image, PDF and undo history") {
                    state.active?.let { saveArchive.launch("${exporter.filename(it)}.folio") }; exportMenu = false
                }
            }
        }
        if (pageExportDialog) {
            val note = state.active
            if (note == null) pageExportDialog = false
            else ExportPagesDialog(
                note = note,
                thumbnails = model.thumbnails,
                model = model,
                initialIndex = state.pageIndex,
                initialSelection = model.exportSelection.also { model.exportSelection = null },
                initialFormat = pagesExportFormat,
                initialPdfMode = pdfExportMode,
                onDismiss = { pageExportDialog = false },
                onExport = { request -> pageExportDialog = false; rememberPdfMode(request.pdfMode); launchExport(request) },
                onShare = { request -> pageExportDialog = false; rememberPdfMode(request.pdfMode); shareExport(request) },
                onFormatChange = ::rememberPagesFormat,
                onPdfModeChange = ::rememberPdfMode
            )
        }
        // Once per launch of the app (it is saved, so rotation and recreation never replay it).
        if (!splashDone) FolioSplash(onFinished = { splashDone = true })
    }
}

@Composable private fun ExportOption(icon: ImageVector, title: String, subtitle: String, action: () -> Unit) {
    Surface(onClick = action, shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(FolioSpacing.dp16), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
        }
    }
}
