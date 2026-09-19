@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.Manifest
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun FolioApp(model: FolioViewModel, shortcutRequest: Int) {
    val state by model.state.collectAsStateWithLifecycle()
    val mistakes: com.folio.notes.mistakes.MistakesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showMistakes by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mistakes) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) mistakes.requestSync()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
    var finger by rememberSaveable { mutableStateOf(prefs.getBoolean("finger", true)) }
    var stylusShortcut by rememberSaveable { mutableStateOf(StylusShortcut.of(prefs.getString(StylusShortcut.PREF_KEY, null))) }
    var haptics by rememberSaveable { mutableStateOf(prefs.getBoolean("penHaptics", false)) }
    var shapeRecognition by rememberSaveable { mutableStateOf(prefs.getBoolean("shapeRecognition", false)) }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean(AppPrefs.FULLSCREEN, AppPrefs.DEFAULT_FULLSCREEN)) }
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
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                AppPrefs.FULLSCREEN -> fullscreen = prefs.getBoolean(key, AppPrefs.DEFAULT_FULLSCREEN)
                AppPrefs.AUTO_UPDATE -> autoUpdate = prefs.getBoolean(key, AppPrefs.DEFAULT_AUTO_UPDATE)
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
    val updateChecker = remember(context) { FolioUpdateChecker(context.applicationContext) }
    val updateScope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<FolioUpdate?>(null) }
    var updateReady by remember { mutableStateOf<Uri?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateFailure by remember { mutableStateOf(false) }
    val updateProgressFlow = remember { MutableStateFlow(0) }
    val updateProgress by updateProgressFlow.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val exporter = remember { NoteExporter(model.repository) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.preparePdfImport(uris) }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importArchive) }
    val saveArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { target -> state.active?.let { model.exportArchive(it, target) } }
    }
    fun exportTo(uri: android.net.Uri?, png: Boolean) {
        val request = model.pendingExport ?: return
        model.pendingExport = null
        if (uri == null) return
        val pngScale = AppPrefs.pngScale(prefs.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { prefs.contains(AppPrefs.EXPORT_PNG_SCALE) })
        model.export {
            exporter.write(context.applicationContext, uri, request.first, request.second, png, pngScale)
            model.reportError(if (png) "Page saved as PNG" else "Notebook saved as PDF")
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { exportTo(it, false) }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { exportTo(it, true) }
    // Bluetooth is only ever asked for while the editor is open and the user has opted in.
    val hapticPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val allowed = granted.values.all { it }
        haptics = allowed
        prefs.edit().putBoolean("penHaptics", allowed).apply()
        if (!allowed) model.reportError("Pen haptics need Bluetooth permission")
    }
    fun installUpdate(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            updateReady = uri
            updateMessage = "Allow Folio to install updates, then tap Install update again."
            updateDialog = true
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }.onFailure {
                updateMessage = "Open Android settings to allow Folio to install updates."
            }
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            updateReady = null
            updateDialog = false
        } catch (_: ActivityNotFoundException) {
            updateMessage = "Android could not open the downloaded update."
            updateFailure = true
            updateDialog = true
        }
    }
    fun downloadUpdate(update: FolioUpdate) {
        if (updateDownloading) return
        updateDownloading = true
        updateMessage = null
        updateFailure = false
        updateProgressFlow.value = 0
        updateDialog = true
        updateScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    updateChecker.download(update) { updateProgressFlow.tryEmit(it) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                updateReady = uri
                installUpdate(uri)
            } catch (error: Exception) {
                updateMessage = error.message ?: "The update could not be downloaded."
                updateFailure = true
                updateDialog = true
            } finally {
                updateDownloading = false
            }
        }
    }
    fun checkForUpdates(showDialog: Boolean) {
        if (updateChecking || updateDownloading) return
        updateChecking = true
        updateInfo = null
        updateReady = null
        updateMessage = null
        updateFailure = false
        updateDialog = showDialog
        updateScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { updateChecker.check() }
                updateInfo = result
                if (result != null) updateDialog = true
                else if (showDialog) updateMessage = "You’re up to date."
            } catch (error: Exception) {
                if (showDialog) {
                    updateMessage = error.message ?: "Could not check for updates."
                    updateFailure = true
                    updateDialog = true
                }
            } finally {
                updateChecking = false
            }
        }
    }
    // Deferred past first paint + library load so cold start never competes with a network fetch.
    LaunchedEffect(autoUpdate) {
        if (!autoUpdate) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        checkForUpdates(showDialog = false)
    }
    LaunchedEffect(shortcutRequest, state.loading) { if (shortcutRequest > 0 && !state.loading) newNote = true }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); model.clearError() } }
    BackHandler(state.active != null && !exportBusy && !showMistakes) { model.close() }
    FolioTheme(mode = themeMode, palette = themePalette, amoled = amoled) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.loading -> LoadingIndicator(Modifier.align(Alignment.Center).semanticsLabel("Loading notebooks"))
                    state.loadFailed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.ErrorOutline, "Library failed to load")
                        Text("Your library couldn't be loaded", style = MaterialTheme.typography.titleLarge)
                        Text("Your stored files have been kept. Retry to open them.")
                        Button(model::loadLibrary, shapes = ButtonDefaults.shapes()) { Text("Retry") }
                    }
                    showMistakes -> com.folio.notes.mistakes.MistakesScreen(mistakes, model, state, finger, haptics, shapeRecognition,
                        onBack = { showMistakes = false }, onSettings = { settings = true }, onExport = { exportMenu = true })
                    state.active != null -> WorkspaceScreen(state, model, finger, haptics, shapeRecognition, onSettings = { settings = true }, onExport = { exportMenu = true })
                    else -> LibraryScreen(state.copy(notes = state.notes.filterNot { it.mistakePractice }), model, onMistakes = { showMistakes = true }, onNew = { newNote = true }, onImport = { pdfPicker.launch(arrayOf("application/pdf")) }, onImportArchive = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }, onFolder = { folderDialog = true }, onSettings = { settings = true })
                }
                if (state.busy || exportBusy) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
                        Surface(shape = RoundedCornerShape(28.dp)) {
                            Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                LoadingIndicator(Modifier.size(48.dp)); Text(if (state.busy) (state.importProgress ?: "Opening your file…") else "Preparing your export…")
                            }
                        }
                }
            }
        }
        if (state.pendingPdfImports.isNotEmpty() && !state.busy && !state.loading && !state.loadFailed) {
            PdfImportDialog(state, model::cancelPdfImport, model::importPdfs)
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
                    onCheckForUpdates = { checkForUpdates(showDialog = true) },
                    updateChecking = updateChecking,
                    onBack = { settings = false },
                    onExamTrack = { settings = false; showMistakes = true })
            }
        }
        if (exportMenu) FolioPanel(title = "Export notebook", onDismissRequest = { exportMenu = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Take your ideas with you", style = MaterialTheme.typography.headlineMedium)
                Text("Export a notebook or just this page.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                ExportOption(Icons.Rounded.PictureAsPdf, "Save as PDF", "All pages, including your annotations") {
                    state.active?.let { model.pendingExport = it to state.pageIndex; savePdf.launch("${exporter.filename(it)}.pdf") }; exportMenu = false
                }
                ExportOption(Icons.Rounded.Image, "Save page as image", "A crisp PNG of the current page") {
                    state.active?.let { model.pendingExport = it to state.pageIndex; savePng.launch("${exporter.filename(it)}-${state.pageIndex + 1}.png") }; exportMenu = false
                }
                ExportOption(Icons.Rounded.FolderZip, "Save as Folio backup", "A file holding the notebook and its PDF, to open again in Folio") {
                    state.active?.let { saveArchive.launch("${exporter.filename(it)}.folio") }; exportMenu = false
                }
                ExportOption(Icons.Rounded.Share, "Share notebook", "Send a PDF to another app") {
                    exportMenu = false
                    val note = state.active ?: return@ExportOption
                    model.export {
                            val file = withContext(Dispatchers.IO) {
                                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                                File(dir, "${exporter.filename(note)}-${System.currentTimeMillis()}.pdf").also { file -> file.outputStream().use { exporter.write(it, note, state.pageIndex, false) } }
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val send = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("Notebook", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            context.applicationContext.startActivity(Intent.createChooser(send, "Share notebook").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        if (updateDialog) AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
            onDismissRequest = { if (!updateChecking && !updateDownloading) updateDialog = false },
            title = { Text(if (updateInfo != null) "Folio update available" else "App updates") },
            text = {
                when {
                    updateChecking -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(24.dp))
                        Text("Checking GitHub releases…")
                    }
                    updateDownloading -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Downloading and verifying Folio ${updateInfo?.versionName ?: "update"}…")
                        LinearProgressIndicator(progress = { updateProgress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$updateProgress%", style = MaterialTheme.typography.labelMedium)
                    }
                    updateReady != null -> Text(updateMessage ?: "The update is ready to install.")
                    updateInfo != null -> Text("Folio ${updateInfo!!.versionName} is ready. Download it and Android will verify the existing release signature before installing.")
                    else -> Text(updateMessage ?: "No update information available.")
                }
            },
            dismissButton = { if (!updateChecking && !updateDownloading) TextButton({ updateDialog = false }) { Text("Later") } },
            confirmButton = {
                when {
                    updateReady != null && !updateDownloading -> Button({ installUpdate(updateReady!!) }, shapes = ButtonDefaults.shapes()) { Text("Install update") }
                    updateInfo != null && !updateDownloading -> Button({ downloadUpdate(updateInfo!!) }, shapes = ButtonDefaults.shapes()) { Text("Download & install") }
                    updateFailure && !updateChecking -> TextButton({ checkForUpdates(showDialog = true) }) { Text("Retry") }
                }
            }
        )
    }
}

@Composable private fun ExportOption(icon: ImageVector, title: String, subtitle: String, action: () -> Unit) {
    Surface(onClick = action, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
        }
    }
}

@Composable fun NameDialog(title: String, subtitle: String, initial: String, action: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { Text(subtitle); OutlinedTextField(text, { text = it.take(120) }, singleLine = true, label = { Text("Name") }) }
    }, dismissButton = { TextButton(dismiss) { Text("Cancel") } }, confirmButton = { Button({ submit(text.trim()) }, enabled = text.isNotBlank()) { Text(action) } })
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
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), modifier = Modifier.guardUiTouches(), onDismissRequest = onDismiss, icon = { Icon(Icons.Rounded.AutoStories, null) }, title = { Text("A fresh start") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Every good idea begins with a blank page. For maths practice, Maths grid keeps your workings aligned.")
            Text("Start from", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Text("Cover color", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CoverColors.forEachIndexed { index, color ->
                    IconButton({ cover = index }) {
                        Surface(Modifier.size(34.dp), shape = RoundedCornerShape(12.dp), color = color, border = if (index == cover) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null) {
                            if (index == cover) Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, "Cover ${index + 1}, selected", Modifier.size(18.dp), tint = Color(0xFF2E302B)) }
                            else Box(Modifier.semanticsLabel("Cover ${index + 1}"))
                        }
                    }
                }
            }
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
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Maths papers first so an exam student sees them without scrolling.
                listOf(Paper.MATH_GRID, Paper.GRAPH, Paper.GRID, Paper.DOTS, Paper.PLAIN, Paper.RULED, Paper.MC_SHEET, Paper.TIAN_GRID, Paper.MI_GRID).filter { !infinite || it != Paper.MC_SHEET }.forEach { item ->
                    FilterChip(item == paper, { paper = item }, label = { Text(when (item) {
                        Paper.MATH_GRID -> "Maths grid"; Paper.GRAPH -> "Graph"; Paper.MC_SHEET -> "MC sheet"
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
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } }, confirmButton = {
        val chosen = NotebookTemplate.byId(template)
        Button(
            { onCreate(title.trim(), cover, paper, chosen?.tags(null, "") ?: ExamTags(), pageCount, infinite, pageCover) },
            shapes = ButtonDefaults.shapes(),
            enabled = title.isNotBlank()
        ) { Text("Create notebook"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
    })
}

@Composable
private fun PdfImportDialog(state: FolioState, onDismiss: () -> Unit, onImport: (String?) -> Unit) {
    var destination by rememberSaveable { mutableStateOf(state.folderId) }
    val validDestination = destination?.takeIf { id -> state.folders.any { it.id == id } }
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = onDismiss,
        title = { Text("Import ${state.pendingPdfImports.size} PDF${if (state.pendingPdfImports.size == 1) "" else "s"}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Each PDF becomes a separate notebook. Choose where to put them.")
                Spacer(Modifier.height(8.dp))
                Text("Exam details are detected automatically where possible. Review and edit them in Exam details after import; uncertain fields stay blank.")
                Spacer(Modifier.height(16.dp))
                (listOf(null to "No folder") + state.folders.map { it.id to it.name }).forEach { (id, name) ->
                    Row(Modifier.fillMaxWidth().clickable { destination = id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = validDestination == id, onClick = { destination = id })
                        Text(name, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onImport(validDestination) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
