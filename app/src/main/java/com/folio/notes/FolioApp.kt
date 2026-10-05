@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.Manifest
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun FolioApp(model: FolioViewModel, shortcutRequest: Int) {
    val state by model.state.collectAsStateWithLifecycle()
    val mistakes: com.folio.notes.mistakes.MistakesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showMistakes by rememberSaveable { mutableStateOf(false) }
    var showStudy by rememberSaveable { mutableStateOf(false) }
    // Keep the editor open while the Library temporarily chooses another workspace document.
    var workspaceLibraryPurpose by rememberSaveable { mutableStateOf<PickerPurpose?>(null) }
    var workspaceLibraryMode by rememberSaveable { mutableStateOf(CompanionMode.SPLIT) }
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
    // The accent and text scale live in preferences too, so they survive a restart and follow
    // edits made in Settings without the theme re-reading storage itself.
    val accentState = rememberAccentState()
    var uiTextScale by remember { mutableFloatStateOf(AppPrefs.uiTextScale(prefs.getFloat(AppPrefs.UI_TEXT_SCALE, AppPrefs.DEFAULT_UI_TEXT_SCALE).takeIf { prefs.contains(AppPrefs.UI_TEXT_SCALE) })) }
    var finger by rememberSaveable { mutableStateOf(prefs.getBoolean("finger", true)) }
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
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    var newNote by rememberSaveable { mutableStateOf(false) }
    val exportBusy = state.exporting
    var exportMenu by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
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
    // Bluetooth is only ever asked for while the editor is open and the user has opted in.
    val hapticPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val allowed = granted.values.all { it }
        haptics = allowed
        prefs.edit().putBoolean("penHaptics", allowed).apply()
        if (!allowed) model.reportError("Pen haptics need Bluetooth permission")
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
    BackHandler(state.active != null && !exportBusy && !showMistakes && !showStudy && workspaceLibraryPurpose == null) { model.close() }
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
            val topGap = (16.dp - padding.calculateTopPadding()).coerceAtLeast(0.dp)
            Box(Modifier.fillMaxSize().padding(padding).then(
                if (showMistakes || showStudy || workspaceLibraryPurpose != null || state.active == null) Modifier.consumeWindowInsets(padding) else Modifier
            )) {
                val screen = when {
                    state.loading -> "loading"
                    state.loadFailed -> "failed"
                    // Sidebar destinations share one shell; switching panes must not replay
                    // the whole screen's entrance animation (including the sidebar).
                    showStudy || showMistakes -> "library"
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
                        state.active != null && !showMistakes && !showStudy && workspaceLibraryPurpose == null -> WorkspaceScreen(
                            state, model, finger, haptics, shapeRecognition,
                            onSettings = { settings = true }, onExport = { exportMenu = true },
                            onBrowseLibrary = { purpose, mode ->
                                workspaceLibraryMode = mode
                                workspaceLibraryPurpose = purpose
                                showMistakes = false
                            },
                        )
                        else -> LibraryScreen(
                            state.copy(notes = remember(state.notes) { state.notes.filterNot { it.mistakePractice } }), model,
                            onMistakes = { workspaceLibraryPurpose = null; showStudy = false; showMistakes = true },
                            showStudy = showStudy,
                            onStudy = { workspaceLibraryPurpose = null; showMistakes = false; showStudy = true },
                            studyContent = { StudyTimerScreen(state.notes.filterNot { it.mistakePractice }, state.timer,
                                onAccount = { focalAccountOpen = true }) },
                            onNew = { workspaceLibraryPurpose = null; showStudy = false; showMistakes = false; newNote = true },
                            onImport = { workspaceLibraryPurpose = null; showStudy = false; showMistakes = false; pdfPicker.launch(arrayOf("application/pdf")) },
                            onImportArchive = { workspaceLibraryPurpose = null; showStudy = false; showMistakes = false; archivePicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                            onFolder = { folderDialog = true }, onSettings = { settings = true },
                            showMistakes = showMistakes, topGap = topGap,
                            onLibrary = { showStudy = false; showMistakes = false; if (workspaceLibraryPurpose == null) model.close() },
                            onOpenNotebook = { id ->
                                if (workspaceLibraryPurpose == PickerPurpose.COMPANION) model.showCompanion(id, workspaceLibraryMode)
                                else model.open(id)
                                workspaceLibraryPurpose = null
                            },
                            selectionCaption = workspaceLibraryPurpose?.let { WorkspacePicker.libraryCaption(it, workspaceLibraryMode) },
                            onCancelSelection = { workspaceLibraryPurpose = null },
                            mistakesContent = { onReviewMode ->
                                com.folio.notes.mistakes.MistakesScreen(
                                    mistakes, model, state, finger, haptics, shapeRecognition,
                                    onBack = { showMistakes = false }, onSettings = { settings = true },
                                    onExport = { exportMenu = true }, onReviewMode = onReviewMode,
                                    onAccount = { focalAccountOpen = true },
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
        if (state.pendingPdfImports.isNotEmpty() && !state.busy && !state.loading && !state.loadFailed) {
            PdfImportDialog(state, model::cancelPdfImport) { folder, reviewed -> model.importPdfs(folder, reviewed) }
        }
        if (newNote) NewNotebookDialog(onDismiss = { newNote = false }, onCreate = { title, cover, paper, exam, pageCount, infinite, pageCover -> model.create(title, cover, paper, exam, pageCount, infinite = infinite, pageCover = pageCover); newNote = false })
        if (folderDialog) NameDialog("New folder", "Give your ideas a home", "", "Create folder", { folderDialog = false }) { model.createFolder(it); folderDialog = false }
        if (settings) Dialog(onDismissRequest = { settings = false }, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().guardUiTouches()) {
                SettingsScreen(
                    themeMode, { themeMode = it; prefs.edit().putString(ThemeMode.PREF_KEY, it.name).apply() },
                    themePalette, { themePalette = it; prefs.edit().putString(ThemePalette.PREF_KEY, it.name).apply() },
                    amoled, { amoled = it; prefs.edit().putBoolean(AppTheme.AMOLED_PREF_KEY, it).apply() },
                    finger, { finger = it; prefs.edit().putBoolean("finger", it).apply() },
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
            notes = state.notes,
            excludedIds = state.backupExcludedNotebookIds,
            onExcluded = model::setBackupExcluded,
            onDismiss = { backupExclusionsOpen = false }
        )
        if (focalAccountOpen) FocalAccountPanel(
            onDismiss = { focalAccountOpen = false },
            onMistakes = if (showMistakes && !settings) null else {
                { workspaceLibraryPurpose = null; focalAccountOpen = false; settings = false; showStudy = false; showMistakes = true }
            },
            onStudy = if (showStudy && !settings) null else {
                { workspaceLibraryPurpose = null; focalAccountOpen = false; settings = false; showMistakes = false; showStudy = true }
            },
        )
        if (exportMenu) FolioPanel(title = "Export notebook", onDismissRequest = { exportMenu = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Take your ideas with you", style = MaterialTheme.typography.headlineMedium)
                Text("Export a notebook or just this page.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(FolioSpacing.dp12))
                val activeNote = state.active
                if (activeNote != null && shouldShowPdfQuality(activeNote)) {
                    PdfQualitySection(selected = pdfExportMode, onSelect = ::rememberPdfMode)
                    Spacer(Modifier.height(FolioSpacing.dp4))
                }
                ExportOption(Icons.Rounded.PictureAsPdf, "Save as PDF", "All pages, including your annotations") {
                    state.active?.let { launchExport(PageExportRequest(it, it.pages.indices.toList(), PageExportFormat.PDF, pdfExportMode)) }; exportMenu = false
                }
                ExportOption(Icons.Rounded.Image, "Save page as image", "A crisp PNG of the current page, saved to your gallery") {
                    state.active?.let { launchExport(PageExportRequest(it, listOf(state.pageIndex), PageExportFormat.PNG)) }; exportMenu = false
                }
                ExportOption(Icons.Rounded.Share, "Share page as image", "Send a PNG of the current page to another app") {
                    state.active?.let { shareExport(PageExportRequest(it, listOf(state.pageIndex), PageExportFormat.PNG)) }; exportMenu = false
                }
                ExportOption(Icons.Rounded.AutoStories, "Export specific pages", "Choose pages for a PDF or PNG images") {
                    exportMenu = false; pageExportDialog = true
                }
                ExportOption(Icons.Rounded.FolderZip, "Save as Folio backup", "A compact file with every page, image, PDF and undo history") {
                    state.active?.let { saveArchive.launch("${exporter.filename(it)}.folio") }; exportMenu = false
                }
                ExportOption(Icons.Rounded.Share, "Share notebook", "Send a PDF to another app") {
                    exportMenu = false
                    val note = state.active ?: return@ExportOption
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
            }
        }
        if (pageExportDialog) {
            val note = state.active
            if (note == null) pageExportDialog = false
            else ExportPagesDialog(
                note = note,
                initialIndex = state.pageIndex,
                initialPdfMode = pdfExportMode,
                onDismiss = { pageExportDialog = false },
                onExport = { request -> pageExportDialog = false; rememberPdfMode(request.pdfMode); launchExport(request) },
                onShare = { request -> pageExportDialog = false; rememberPdfMode(request.pdfMode); shareExport(request) },
                onPdfModeChange = ::rememberPdfMode
            )
        }

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

@Composable fun NameDialog(title: String, subtitle: String, initial: String, action: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var text by rememberSaveable(initial, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    fun save() { if (text.text.isNotBlank()) submit(text.text.trim()) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Text(subtitle)
            OutlinedTextField(text, { if (it.text.length <= 120) text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true, label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                supportingText = { Text("${text.text.length}/120") })
        }
    }, dismissButton = { TextButton(dismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }, confirmButton = { Button({ save() }, enabled = text.text.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text(action) } })
}

@Composable private fun NewNotebookDialog(onDismiss: () -> Unit, onCreate: (String, Int, Paper, ExamTags, Int, Boolean, Boolean) -> Unit) {
    val context = LocalContext.current
    val dialogPrefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    var title by rememberSaveable { mutableStateOf("") }
    var cover by rememberSaveable { mutableIntStateOf(AppPrefs.defaultCover(dialogPrefs.getInt(AppPrefs.DEFAULT_COVER, AppPrefs.DEFAULT_COVER_INDEX).takeIf { dialogPrefs.contains(AppPrefs.DEFAULT_COVER) } ?: AppPrefs.DEFAULT_COVER_INDEX)) }
    var paper by rememberSaveable { mutableStateOf(AppPrefs.defaultPaper(dialogPrefs.getString(AppPrefs.DEFAULT_PAPER, null))) }
    var template by rememberSaveable { mutableStateOf<String?>(null) }
    var pageCount by rememberSaveable { mutableIntStateOf(1) }
    var infinite by rememberSaveable { mutableStateOf(false) }
    var pageCover by rememberSaveable { mutableStateOf(dialogPrefs.getBoolean(AppPrefs.DEFAULT_PAGE_COVER, AppPrefs.DEFAULT_PAGE_COVER_ENABLED)) }
    fun chooseTemplate(item: NotebookTemplate) {
        infinite = false
        template = item.id
        paper = item.paper
        pageCount = item.pages
    }
    FolioPanel(title = "A fresh start", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp24).padding(top = FolioSpacing.dp8, bottom = FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Text("Every good idea begins with a blank page. For maths practice, Maths grid keeps your workings aligned.")
            Text("Start from", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                FilterChip(template == null && !infinite, { template = null; pageCount = 1; infinite = false }, { Text("Blank") })
                FilterChip(infinite, {
                    template = null; pageCount = 1; infinite = true
                    if (paper == Paper.MC_SHEET) paper = Paper.DOTS
                }, { Text("Infinite canvas") })
                NotebookTemplate.ALL.forEach { item ->
                    FilterChip(template == item.id, { chooseTemplate(item) }, { Text(item.title) })
                }
            }
            if (infinite) Text("An unlimited workspace for handwriting and ideas. Pan in any direction and pinch to zoom.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NotebookTemplate.byId(template)?.let { item -> Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(title, { title = it.take(120) }, label = { Text("Notebook name") }, placeholder = { Text("e.g. Calculus — exam practice") }, singleLine = true)
            CoverPicker(cover, { cover = it }, title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("First page as cover", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (pageCover) "The shelf shows the first page itself" else "The shelf shows the default cover",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(pageCover, { pageCover = it })
            }
            Text("Paper style — pick for maths", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                // Maths papers first so an exam student sees them without scrolling.
                listOf(Paper.MATH_GRID, Paper.GRAPH, Paper.GRID, Paper.DOTS, Paper.PLAIN, Paper.RULED, Paper.SPLIT_RULED, Paper.MC_SHEET, Paper.TIAN_GRID, Paper.MI_GRID).filter { !infinite || it != Paper.MC_SHEET }.forEach { item ->
                    FilterChip(item == paper, { paper = item }, label = { Text(when (item) {
                        Paper.SPLIT_RULED -> "Split ruled"; Paper.MATH_GRID -> "Maths grid"; Paper.GRAPH -> "Graph"; Paper.MC_SHEET -> "MC sheet"
                        Paper.TIAN_GRID -> "Tian (田字格)"; Paper.MI_GRID -> "Mi (米字格)"
                        else -> item.name.lowercase().replaceFirstChar(Char::uppercase)
                    }) })
                }
            }
            if (paper == Paper.MATH_GRID) Text("Fine 20 px grid — ideal for workings & fractions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paper == Paper.GRAPH) Text("Same grid with centred X/Y axes printed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paper == Paper.MC_SHEET) Text("Exam 2 Section A answer sheet — shade A–E with the pen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paper == Paper.TIAN_GRID) Text("田字格 — one character per square with a dashed cross", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paper == Paper.MI_GRID) Text("米字格 — cross plus diagonals in each square", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp24, vertical = FolioSpacing.dp12), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                val chosen = NotebookTemplate.byId(template)
                Button(
                    { onCreate(title.trim(), cover, paper, chosen?.tags(null, "") ?: ExamTags(), pageCount, infinite, pageCover) },
                    shapes = ButtonDefaults.shapes(),
                    enabled = title.isNotBlank()
                ) { Text("Create notebook"); Spacer(Modifier.width(FolioSpacing.dp8)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun PdfImportDialog(state: FolioState, onDismiss: () -> Unit, onImport: (String?, List<PendingPdfImport>) -> Unit) {
    var destination by rememberSaveable { mutableStateOf(state.folderId) }
    var reviewed by remember(state.pendingPdfImports) { mutableStateOf(state.pendingPdfImports) }
    val validDestination = destination?.takeIf { id -> state.folders.any { it.id == id } }
    fun updateExam(uri: Uri, transform: (ExamTags) -> ExamTags) {
        reviewed = reviewed.map { if (it.uri == uri) it.copy(exam = transform(it.exam)) else it }
    }
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = onDismiss,
        title = { Text("Import ${state.pendingPdfImports.size} PDF${if (state.pendingPdfImports.size == 1) "" else "s"}") },
        text = {
            Column(Modifier.heightIn(max = 528.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
                Text("Review the detected exam details. You can change anything before importing.")
                reviewed.forEachIndexed { index, item ->
                    val tags = item.exam
                    Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(FolioSpacing.dp12), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("PDF ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(if (item.detected) "Exam details detected" else "Reading PDF…",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            OutlinedTextField(
                                value = item.title, onValueChange = { value -> reviewed = reviewed.map {
                                    if (it.uri == item.uri) it.copy(title = value.take(120)) else it
                                } }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("Notebook name") }, singleLine = true,
                                trailingIcon = { TextButton({ reviewed = reviewed.map {
                                    if (it.uri == item.uri) it.copy(title = smartImportedNotebookName(tags, item.title)) else it
                                } }) { Text("Auto") } }
                            )
                            OutlinedTextField(
                                value = tags.subjectLabel,
                                onValueChange = { value -> updateExam(item.uri) { old ->
                                    old.copy(subject = VceSubject.match(value), subjectText = if (VceSubject.match(value) == null) value.take(60) else "")
                                } },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Subject") },
                                placeholder = { Text("Not an exam, or unknown") }, singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                                OutlinedTextField(
                                    tags.year?.toString().orEmpty(),
                                    { value -> updateExam(item.uri) { it.copy(year = value.filter(Char::isDigit).take(4).toIntOrNull()) } },
                                    Modifier.weight(1f), label = { Text("Year") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    tags.company, { value -> updateExam(item.uri) { it.copy(company = value.take(40)) } },
                                    Modifier.weight(1.4f), label = { Text("Company / source") },
                                    placeholder = { Text("e.g. VCAA") }, singleLine = true
                                )
                                OutlinedTextField(
                                    tags.marksTotal?.toString().orEmpty(),
                                    { value -> updateExam(item.uri) { it.copy(marksTotal = value.filter(Char::isDigit).take(3).toIntOrNull()) } },
                                    Modifier.weight(0.8f), label = { Text("Marks") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                ExamType.entries.forEach { type ->
                                    FilterChip(tags.type == type, { updateExam(item.uri) {
                                        it.copy(type = if (it.type == type) null else type)
                                    } }, { Text(type.label) })
                                }
                            }
                        }
                    }
                }
                Text("Destination", style = MaterialTheme.typography.titleSmall)
                (listOf(null to "No folder") + state.folders.map { it.id to it.name }).forEach { (id, name) ->
                    Row(Modifier.fillMaxWidth().clickable { destination = id }.padding(vertical = FolioSpacing.dp4),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = validDestination == id, onClick = { destination = id })
                        Text(name, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onImport(validDestination, reviewed) }, shapes = ButtonDefaults.shapes(), enabled = reviewed.all { it.detected && it.title.isNotBlank() }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}
