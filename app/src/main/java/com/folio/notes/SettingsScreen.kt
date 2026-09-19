@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable fun SettingsScreen(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, themePalette: ThemePalette, onThemePalette: (ThemePalette) -> Unit, amoled: Boolean, onAmoled: (Boolean) -> Unit, finger: Boolean, onFinger: (Boolean) -> Unit, stylus: StylusShortcut, onStylus: (StylusShortcut) -> Unit, haptics: Boolean, onHaptics: (Boolean) -> Unit, shapeRecognition: Boolean, onShapeRecognition: (Boolean) -> Unit, onCheckForUpdates: () -> Unit, updateChecking: Boolean, onBack: () -> Unit, onExamTrack: () -> Unit = {}) {
    val context = LocalContext.current
    val hapticsSupported = remember(context) { PenHapticsManager.isSupported(context) }
    val dynamicAvailable = Build.VERSION.SDK_INT >= 31
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close settings") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onExamTrack) { Text("ExamTrack · Sign in and manage mistake sync") }
            HorizontalDivider()
            Text("Your writing space", style = MaterialTheme.typography.headlineMedium)
            Text("Make room for your ideas. These preferences apply to all notebooks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Stylus", style = MaterialTheme.typography.titleMedium)
            Text("On a OnePlus or OPPO device, double-tapping the Pencil starts the action you pick here. Other styli keep their own system shortcut. Palm touches are ignored while the stylus is writing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup().padding(8.dp)) {
                    StylusShortcut.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(stylus == option, role = Role.RadioButton, onClick = { onStylus(option) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(stylus == option, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column { Text(option.label, style = MaterialTheme.typography.titleSmall); Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            PreferenceSwitch("Pen haptics", if (hapticsSupported) "Buzz the Pencil on every double tap it sends, whatever the action. Changing tools by hand stays silent. The one-shot pulse is confirmed on the OnePlus Pencil Pro; it needs Bluetooth, and it is not the pen's soft writing feedback." else "Needs Android 12 or newer and a Bluetooth LE pencil.", haptics, onHaptics, hapticsSupported)
            HorizontalDivider()
            Text("Writing & appearance", style = MaterialTheme.typography.titleMedium)
            PreferenceSwitch("Draw with a finger", "When off, use a finger to scroll and a stylus to write. When on, scroll with two fingers or the hand tool. Palm touches are ignored while the stylus writes.", finger, onFinger)
            PreferenceSwitch("Tidy up shapes", "Draw a rough line, square, circle or triangle with the pen and it becomes a clean shape when you lift the pen. Undo brings your own drawing back.", shapeRecognition, onShapeRecognition)
            ScribbleSettingsSection()
            HorizontalDivider()
            LibraryDefaultsSection()
            HorizontalDivider()
            EditorDefaultsSection()
            HorizontalDivider()
            InputGesturesSection()
            HorizontalDivider()
            WritingFollowDefaultsSection()
            HorizontalDivider()
            WorkflowSection(onCheckForUpdates, updateChecking)
            HorizontalDivider()
            Text("Appearance", style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup().padding(8.dp)) {
                    ThemeMode.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(option == themeMode, role = Role.RadioButton, onClick = { onThemeMode(option) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(option == themeMode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column { Text(option.label, style = MaterialTheme.typography.titleSmall); Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Text("Color theme", style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup().padding(8.dp)) {
                    ThemePalette.entries.forEach { option ->
                        val enabled = option != ThemePalette.DYNAMIC || dynamicAvailable
                        Row(Modifier.fillMaxWidth().selectable(option == themePalette, enabled = enabled, role = Role.RadioButton, onClick = { onThemePalette(option) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(option == themePalette, onClick = null, enabled = enabled)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(option.label, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (!enabled) "Needs Android 12 or newer" else option.description,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            PreferenceSwitch("Pure black dark", "Use true black backgrounds whenever the dark theme is active. Accents and ink colors stay the same.", amoled, onAmoled)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("App updates", style = MaterialTheme.typography.titleSmall)
                    Text("Check GitHub for a newer signed Folio release.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onCheckForUpdates, enabled = !updateChecking) {
                    if (updateChecking) LoadingIndicator(Modifier.size(18.dp)) else Text("Check")
                }
            }
            Text("Your notebooks stay on this device. Export a PDF to keep a copy or share your work.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, enabled = enabled)
    }
}

// ---- Shared prefs helpers ----------------------------------------------------------------------

@Composable private fun prefs(): SharedPreferences {
    val context = LocalContext.current
    return remember(context) { context.getSharedPreferences("preferences", 0) }
}

@Composable private fun PrefsSwitch(key: String, default: Boolean, title: String, subtitle: String) {
    val p = prefs()
    var checked by remember { mutableStateOf(p.getBoolean(key, default)) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == key) checked = p.getBoolean(k, default)
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    PreferenceSwitch(title, subtitle, checked, {
        checked = it
        p.edit().putBoolean(key, it).apply()
    })
}

@Composable private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable private fun SectionHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Library & appearance ----------------------------------------------------------------------

@Composable private fun LibraryDefaultsSection() {
    val p = prefs()
    var fullscreen by remember { mutableStateOf(p.getBoolean(AppPrefs.FULLSCREEN, AppPrefs.DEFAULT_FULLSCREEN)) }
    var sort by remember { mutableStateOf(AppPrefs.librarySort(p.getString(AppPrefs.LIB_SORT, null))) }
    var kind by remember { mutableStateOf(AppPrefs.libraryKind(p.getString(AppPrefs.LIB_KIND, null))) }
    var listView by remember { mutableStateOf(p.getBoolean(AppPrefs.LIB_LIST, AppPrefs.DEFAULT_LIST_VIEW)) }
    var defaultPaper by remember { mutableStateOf(AppPrefs.defaultPaper(p.getString(AppPrefs.DEFAULT_PAPER, null))) }
    var defaultCover by remember { mutableStateOf(AppPrefs.defaultCover(p.getInt(AppPrefs.DEFAULT_COVER, AppPrefs.DEFAULT_COVER_INDEX).takeIf { p.contains(AppPrefs.DEFAULT_COVER) } ?: AppPrefs.DEFAULT_COVER_INDEX)) }
    var pageCover by remember { mutableStateOf(p.getBoolean(AppPrefs.DEFAULT_PAGE_COVER, AppPrefs.DEFAULT_PAGE_COVER_ENABLED)) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            when (k) {
                AppPrefs.FULLSCREEN -> fullscreen = p.getBoolean(k, AppPrefs.DEFAULT_FULLSCREEN)
                AppPrefs.LIB_SORT -> sort = AppPrefs.librarySort(p.getString(k, null))
                AppPrefs.LIB_KIND -> kind = AppPrefs.libraryKind(p.getString(k, null))
                AppPrefs.LIB_LIST -> listView = p.getBoolean(k, AppPrefs.DEFAULT_LIST_VIEW)
                AppPrefs.DEFAULT_PAPER -> defaultPaper = AppPrefs.defaultPaper(p.getString(k, null))
                AppPrefs.DEFAULT_COVER -> defaultCover = AppPrefs.defaultCover(p.getInt(k, AppPrefs.DEFAULT_COVER_INDEX))
                AppPrefs.DEFAULT_PAGE_COVER -> pageCover = p.getBoolean(k, AppPrefs.DEFAULT_PAGE_COVER_ENABLED)
            }
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SectionTitle("Library & appearance")
    SectionHint("How the shelf opens and what a fresh notebook looks like. Library sorting and view are also saved whenever you change them on the shelf itself.")
    PreferenceSwitch("Fullscreen", "Hide the status bar and gesture pill across the whole app. They slide back on a swipe from their edge.", fullscreen, {
        fullscreen = it
        p.edit().putBoolean(AppPrefs.FULLSCREEN, it).apply()
    })
    Text("Library sort", style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        LibrarySort.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().selectable(sort == option, role = Role.RadioButton, onClick = {
                sort = option
                p.edit().putString(AppPrefs.LIB_SORT, option.name).apply()
            }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sort == option, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Text("Library type filter", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryKind.entries.forEach { option ->
            FilterChip(kind == option, {
                kind = option
                p.edit().putString(AppPrefs.LIB_KIND, option.name).apply()
            }, { Text(option.label) })
        }
    }
    Text("Library layout", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!listView, {
            listView = false
            p.edit().putBoolean(AppPrefs.LIB_LIST, false).apply()
        }, { Text("Covers") })
        FilterChip(listView, {
            listView = true
            p.edit().putBoolean(AppPrefs.LIB_LIST, true).apply()
        }, { Text("Compact list") })
    }
    Text("Default paper for new notebooks", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Paper.entries.forEach { item ->
            FilterChip(defaultPaper == item, {
                defaultPaper = item
                p.edit().putString(AppPrefs.DEFAULT_PAPER, item.name).apply()
            }, { Text(paperLabel(item)) })
        }
    }
    Text("Default cover color", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CoverColors.forEachIndexed { index, color ->
            IconButton({
                defaultCover = index
                p.edit().putInt(AppPrefs.DEFAULT_COVER, index).apply()
            }) {
                Surface(Modifier.size(34.dp), shape = RoundedCornerShape(12.dp), color = color, border = if (index == defaultCover) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null) {
                    if (index == defaultCover) Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, "Cover ${index + 1}, selected", Modifier.size(18.dp), tint = Color(0xFF2E302B)) }
                    else Box(Modifier.semanticsLabel("Cover ${index + 1}"))
                }
            }
        }
    }
    PreferenceSwitch("First page as cover", "New notebooks show their first page on the shelf. Turn off for the decorative default cover.", pageCover, {
        pageCover = it
        p.edit().putBoolean(AppPrefs.DEFAULT_PAGE_COVER, it).apply()
    })
}

private fun paperLabel(paper: Paper): String = when (paper) {
    Paper.MATH_GRID -> "Maths grid"
    Paper.GRAPH -> "Graph"
    Paper.MC_SHEET -> "MC sheet"
    Paper.TIAN_GRID -> "Tian (田字格)"
    Paper.MI_GRID -> "Mi (米字格)"
    else -> paper.name.lowercase().replaceFirstChar(Char::uppercase)
}

// ---- Editor defaults ---------------------------------------------------------------------------

@Composable private fun EditorDefaultsSection() {
    val p = prefs()
    var defaultTool by remember { mutableStateOf(AppPrefs.defaultTool(p.getString(AppPrefs.DEFAULT_TOOL, null))) }
    var textSize by remember { mutableStateOf(AppPrefs.textSize(p.getFloat(AppPrefs.TEXT_SIZE_KEY, AppPrefs.DEFAULT_TEXT_SIZE).takeIf { p.contains(AppPrefs.TEXT_SIZE_KEY) })) }
    var textAlign by remember { mutableStateOf(runCatching { TextAlignMode.valueOf(p.getString("text.align", "LEFT") ?: "LEFT") }.getOrDefault(TextAlignMode.LEFT)) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            when (k) {
                AppPrefs.DEFAULT_TOOL -> defaultTool = AppPrefs.defaultTool(p.getString(k, null))
                AppPrefs.TEXT_SIZE_KEY -> textSize = AppPrefs.textSize(p.getFloat(k, AppPrefs.DEFAULT_TEXT_SIZE))
                "text.align" -> textAlign = runCatching { TextAlignMode.valueOf(p.getString(k, "LEFT") ?: "LEFT") }.getOrDefault(TextAlignMode.LEFT)
            }
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SectionTitle("Editor defaults")
    SectionHint("Which tool is in hand when a notebook opens, and how new typed text looks. Ink color, width and opacity stay per-tool in Tool settings.")
    Text("Default tool", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tool.entries.forEach { tool ->
            FilterChip(defaultTool == tool, {
                defaultTool = tool
                p.edit().putString(AppPrefs.DEFAULT_TOOL, tool.name).apply()
            }, { Text(tool.name.lowercase().replaceFirstChar(Char::uppercase)) })
        }
    }
    Text("Default text size: ${textSize.roundToInt()} pt", style = MaterialTheme.typography.titleSmall)
    Slider(textSize, {
        textSize = AppPrefs.textSize(it)
        p.edit().putFloat(AppPrefs.TEXT_SIZE_KEY, textSize).apply()
    }, valueRange = AppPrefs.TEXT_SIZE_MIN..AppPrefs.TEXT_SIZE_MAX)
    SectionHint("New text boxes start at this size. Existing boxes are unchanged.")
    Text("Default text alignment", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextAlignMode.entries.forEach { align ->
            FilterChip(textAlign == align, {
                textAlign = align
                p.edit().putString("text.align", align.name).apply()
            }, { Text(align.name.lowercase().replaceFirstChar(Char::uppercase)) })
        }
    }
}

// ---- Input & gestures --------------------------------------------------------------------------

@Composable private fun InputGesturesSection() {
    val p = prefs()
    var palmMs by remember { mutableStateOf(AppPrefs.palmMs(p.getLong(AppPrefs.PALM_MS, AppPrefs.DEFAULT_PALM_MS).takeIf { p.contains(AppPrefs.PALM_MS) })) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == AppPrefs.PALM_MS) palmMs = AppPrefs.palmMs(p.getLong(k, AppPrefs.DEFAULT_PALM_MS))
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SectionTitle("Touch & gestures")
    SectionHint("Fine-tune palm rejection and the shortcuts that live on the page canvas. Pen pressure curves stay per-tool in Tool settings.")
    Text(if (palmMs == 0L) "Palm rejection: off" else "Palm rejection: ${palmMs} ms after the stylus", style = MaterialTheme.typography.titleSmall)
    Slider(palmMs.toFloat(), {
        palmMs = AppPrefs.palmMs(it.roundToInt().toLong())
        p.edit().putLong(AppPrefs.PALM_MS, palmMs).apply()
    }, valueRange = AppPrefs.PALM_MIN_MS.toFloat()..AppPrefs.PALM_MAX_MS.toFloat())
    SectionHint("How long a finger still counts as a resting palm after stylus activity. 0 turns palm filtering off; 500 ms is the balanced default.")
    PrefsSwitch(EditorQuickPrefs.MULTI_TOUCH_UNDO, true, "Two-finger tap undo", "Two fingers: undo, three fingers: redo — on the page canvas (not the toolbar). Stylus and palm input never trigger it.")
    PrefsSwitch(EditorQuickPrefs.ERASER_PRESSURE, true, "Pressure-sensitive eraser", "Slightly grows with stronger pressure (about ±12%).")
    PrefsSwitch(EditorQuickPrefs.ERASER_SINGLE_STROKE, false, "Single-stroke eraser", "When on, one eraser stroke then returns to the previous tool.")
    PrefsSwitch(EditorQuickPrefs.ERASER_WHOLE_STROKE, false, "Whole-stroke eraser", "When on, touching any part of a stroke removes the entire stroke instead of cutting it.")
    PrefsSwitch(EditorQuickPrefs.SHAPE_MEASUREMENTS, true, "Live shape measurements", "Shows length/angle or width×height while drawing a shape.")
    PrefsSwitch("mathSnap", true, "Snap shapes to grid & 15°", "Lines snap to 15° and to grid on Maths/Grid/Graph paper. Toggle any time in the editor.")
}

// ---- Writing follow defaults -------------------------------------------------------------------

@Composable private fun WritingFollowDefaultsSection() {
    val p = prefs()
    var followEnabled by remember { mutableStateOf(p.getBoolean("writingFollow", false)) }
    var mode by remember { mutableStateOf(runCatching { FollowMode.valueOf(p.getString("follow.mode", "TEXT") ?: "TEXT") }.getOrDefault(FollowMode.TEXT)) }
    var direction by remember { mutableStateOf(runCatching { WritingDirection.valueOf(p.getString("follow.direction", "LTR") ?: "LTR") }.getOrDefault(WritingDirection.LTR)) }
    var hand by remember { mutableStateOf(runCatching { WritingHand.valueOf(p.getString("writingHand", "RIGHT") ?: "RIGHT") }.getOrDefault(WritingHand.RIGHT)) }
    var autoReturn by remember { mutableStateOf(p.getBoolean("follow.autoReturn", false)) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            when (k) {
                "writingFollow" -> followEnabled = p.getBoolean(k, false)
                "follow.mode" -> mode = runCatching { FollowMode.valueOf(p.getString(k, "TEXT") ?: "TEXT") }.getOrDefault(FollowMode.TEXT)
                "follow.direction" -> direction = runCatching { WritingDirection.valueOf(p.getString(k, "LTR") ?: "LTR") }.getOrDefault(WritingDirection.LTR)
                "writingHand" -> hand = runCatching { WritingHand.valueOf(p.getString(k, "RIGHT") ?: "RIGHT") }.getOrDefault(WritingHand.RIGHT)
                "follow.autoReturn" -> autoReturn = p.getBoolean(k, false)
            }
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SectionTitle("Writing follow")
    SectionHint("Defaults for new sessions. Height, column, line spacing and glide timing stay in the editor's Writing follow dialog.")
    PreferenceSwitch("Writing follow on by default", "The page stays still while the pen is down and reveals space after a lift.", followEnabled, {
        followEnabled = it
        p.edit().putBoolean("writingFollow", it).apply()
    })
    Text("Follow mode", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == FollowMode.TEXT, {
            mode = FollowMode.TEXT
            p.edit().putString("follow.mode", FollowMode.TEXT.name).apply()
        }, { Text("Text") })
        FilterChip(mode == FollowMode.MATH, {
            mode = FollowMode.MATH
            p.edit().putString("follow.mode", FollowMode.MATH.name).apply()
        }, { Text("Maths") })
    }
    Text("Reading direction", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(direction == WritingDirection.LTR, {
            direction = WritingDirection.LTR
            p.edit().putString("follow.direction", WritingDirection.LTR.name).apply()
        }, { Text("Left → right") })
        FilterChip(direction == WritingDirection.RTL, {
            direction = WritingDirection.RTL
            p.edit().putString("follow.direction", WritingDirection.RTL.name).apply()
        }, { Text("Right → left") })
    }
    Text("Hand holding the pen", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(hand == WritingHand.RIGHT, {
            hand = WritingHand.RIGHT
            p.edit().putString("writingHand", WritingHand.RIGHT.name).apply()
        }, { Text("Right hand") })
        FilterChip(hand == WritingHand.LEFT, {
            hand = WritingHand.LEFT
            p.edit().putString("writingHand", WritingHand.LEFT.name).apply()
        }, { Text("Left hand") })
    }
    PreferenceSwitch("Automatic line return", "Finishing near the edge shows “Next line…” and glides by itself. Touch the pen down quickly to cancel.", autoReturn, {
        autoReturn = it
        p.edit().putBoolean("follow.autoReturn", it).apply()
    })
}

// ---- Workflow: updates, timer, export, split ---------------------------------------------------

@Composable private fun WorkflowSection(onCheckForUpdates: () -> Unit, updateChecking: Boolean) {
    val p = prefs()
    var autoUpdate by remember { mutableStateOf(p.getBoolean(AppPrefs.AUTO_UPDATE, AppPrefs.DEFAULT_AUTO_UPDATE)) }
    var customMinutes by remember { mutableStateOf(AppPrefs.timerCustomMinutes(p.getInt(AppPrefs.TIMER_CUSTOM_MIN, AppPrefs.DEFAULT_TIMER_CUSTOM_MIN).takeIf { p.contains(AppPrefs.TIMER_CUSTOM_MIN) }).toString()) }
    var readingMinutes by remember { mutableStateOf(AppPrefs.timerReadingMinutes(p.getInt(AppPrefs.TIMER_READING_MIN, AppPrefs.DEFAULT_TIMER_READING_MIN).takeIf { p.contains(AppPrefs.TIMER_READING_MIN) }).toFloat()) }
    var pngScale by remember { mutableStateOf(AppPrefs.pngScale(p.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { p.contains(AppPrefs.EXPORT_PNG_SCALE) })) }
    var split by remember { mutableStateOf(AppPrefs.splitFraction(p.getFloat(AppPrefs.SPLIT_FRACTION, AppPrefs.DEFAULT_SPLIT).takeIf { p.contains(AppPrefs.SPLIT_FRACTION) })) }
    var customError by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            when (k) {
                AppPrefs.AUTO_UPDATE -> autoUpdate = p.getBoolean(k, AppPrefs.DEFAULT_AUTO_UPDATE)
                AppPrefs.TIMER_CUSTOM_MIN -> customMinutes = AppPrefs.timerCustomMinutes(p.getInt(k, AppPrefs.DEFAULT_TIMER_CUSTOM_MIN)).toString()
                AppPrefs.TIMER_READING_MIN -> readingMinutes = AppPrefs.timerReadingMinutes(p.getInt(k, AppPrefs.DEFAULT_TIMER_READING_MIN)).toFloat()
                AppPrefs.EXPORT_PNG_SCALE -> pngScale = AppPrefs.pngScale(p.getFloat(k, AppPrefs.DEFAULT_PNG_SCALE))
                AppPrefs.SPLIT_FRACTION -> split = AppPrefs.splitFraction(p.getFloat(k, AppPrefs.DEFAULT_SPLIT))
            }
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SectionTitle("Timer, export & updates")
    SectionHint("Exam presets, PNG sharpness and the split-view balance. PDF exports keep vector ink; PNG scale only affects page images.")
    PreferenceSwitch("Check for updates on launch", "After a short delay, Folio checks GitHub Releases for a newer signed build.", autoUpdate, {
        autoUpdate = it
        p.edit().putBoolean(AppPrefs.AUTO_UPDATE, it).apply()
    })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("App updates", style = MaterialTheme.typography.titleSmall)
            Text("Manual check any time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onCheckForUpdates, enabled = !updateChecking) {
            if (updateChecking) LoadingIndicator(Modifier.size(18.dp)) else Text("Check")
        }
    }
    Text("Custom timer writing minutes", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(customMinutes, {
        customMinutes = it.filter(Char::isDigit).take(3)
        val parsed = customMinutes.toIntOrNull()
        customError = parsed != null && (parsed < AppPrefs.TIMER_CUSTOM_MIN_RANGE || parsed > AppPrefs.TIMER_CUSTOM_MAX)
        parsed?.let { minutes ->
            if (minutes in AppPrefs.TIMER_CUSTOM_MIN_RANGE..AppPrefs.TIMER_CUSTOM_MAX) {
                p.edit().putInt(AppPrefs.TIMER_CUSTOM_MIN, minutes).apply()
            }
        }
    }, label = { Text("Writing minutes (1–480)") }, singleLine = true, isError = customError,
        supportingText = { if (customError) Text("Enter 1–480") else Text("Prefills the Custom timer; Exam 1 (90) and Exam 2 (120) presets are unchanged.") })
    Text("Custom timer reading minutes: ${readingMinutes.roundToInt()} min", style = MaterialTheme.typography.titleSmall)
    Slider(readingMinutes, {
        readingMinutes = it
        p.edit().putInt(AppPrefs.TIMER_READING_MIN, it.roundToInt()).apply()
    }, valueRange = AppPrefs.TIMER_READING_MIN_RANGE.toFloat()..AppPrefs.TIMER_READING_MAX.toFloat(), steps = AppPrefs.TIMER_READING_MAX - AppPrefs.TIMER_READING_MIN_RANGE - 1)
    Text("Page image (PNG) sharpness: ${"%.1f".format(pngScale)}×", style = MaterialTheme.typography.titleSmall)
    Slider(pngScale, {
        pngScale = AppPrefs.pngScale(it)
        p.edit().putFloat(AppPrefs.EXPORT_PNG_SCALE, pngScale).apply()
    }, valueRange = AppPrefs.PNG_SCALE_MIN..AppPrefs.PNG_SCALE_MAX)
    SectionHint("Higher is crisper on large pages and larger to share. 2.0× is the balanced default.")
    Text("Split view balance: ${(split * 100).roundToInt()}% editor", style = MaterialTheme.typography.titleSmall)
    Slider(split, {
        split = AppPrefs.splitFraction(it)
        p.edit().putFloat(AppPrefs.SPLIT_FRACTION, split).apply()
    }, valueRange = SplitPanes.MIN_FRACTION..SplitPanes.MAX_FRACTION)
    SectionHint("Default share of the split given to the editor pane. Drag the divider any time; the last position is remembered.")
}
