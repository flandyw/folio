@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

// One composable per settings category. Each reads and writes its own preferences through
// rememberPref, so a page keeps no state beyond what is on screen; the few values that live in
// FolioApp (theme, finger, stylus, haptics, shapes) arrive through AppSettings.

internal class AppSettings(
    val themeMode: ThemeMode, val onThemeMode: (ThemeMode) -> Unit,
    val themePalette: ThemePalette, val onThemePalette: (ThemePalette) -> Unit,
    val amoled: Boolean, val onAmoled: (Boolean) -> Unit,
    val finger: Boolean, val onFinger: (Boolean) -> Unit,
    val stylus: StylusShortcut, val onStylus: (StylusShortcut) -> Unit,
    val haptics: Boolean, val onHaptics: (Boolean) -> Unit,
    val shapeRecognition: Boolean, val onShapeRecognition: (Boolean) -> Unit,
    val hapticsSupported: Boolean, val dynamicAvailable: Boolean,
)

internal class BackupSettings(
    val tree: String?, val folderName: String?, val lastSuccess: Long, val lastError: String?, val busy: Boolean,
    val excludedCount: Int,
    val onChooseFolder: () -> Unit, val onBackupNow: () -> Unit, val onDisable: () -> Unit,
    val onSaveLibrary: () -> Unit, val onRestoreLibrary: () -> Unit, val onRestoreFromFolder: () -> Unit,
    val onExclusions: () -> Unit,
    val progress: String? = null,
)

internal class AccountSettings(val onFocal: () -> Unit, val onCheckForUpdates: () -> Unit, val onCheckGitHub: () -> Unit, val updateChecking: Boolean, val updateBusy: Boolean, val updateContent: @Composable () -> Unit)

private fun <E : Enum<E>> readEnum(raw: String?, fallback: E, values: Array<E>): E = values.firstOrNull { it.name == raw } ?: fallback

private fun title(name: String) = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

// ---- Appearance ---------------------------------------------------------------------------------

@Composable internal fun AppearancePage(s: AppSettings) {
    SettingsGroup("Theme") {
        SettingsRadioGroup {
            ThemeMode.entries.forEach { SettingsRadioRow(it.label, it.description, it == s.themeMode) { s.onThemeMode(it) } }
        }
    }
    SettingsGroup("Colours", footer = "Ink colours on the page never change with the theme or accent.") {
        SettingsRadioGroup {
            ThemePalette.entries.forEach { option ->
                val enabled = option != ThemePalette.DYNAMIC || s.dynamicAvailable
                SettingsRadioRow(option.label, if (enabled) option.description else "Needs Android 12 or newer", option == s.themePalette, enabled) { s.onThemePalette(option) }
            }
        }
        SettingsDivider()
        SettingsSwitchRow("Pure black dark", "True black backgrounds whenever the dark theme is active.", s.amoled, s.onAmoled)
    }
    AccentGroup(s.themePalette)
    TextSizeGroup()
    SettingsGroup("Display") {
        SettingsPrefSwitch(AppPrefs.FULLSCREEN, AppPrefs.DEFAULT_FULLSCREEN, "Fullscreen", "Hide the status bar and gesture pill. Swipe from an edge to reveal them.")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.KEEP_SCREEN_ON, AppPrefs.DEFAULT_KEEP_SCREEN_ON, "Keep screen on", "The display never sleeps while Folio is open. Handy for long sessions and timed papers.")
    }
}

/** One colour of your own, applied to every Folio palette by re-tinting its accents. */
@Composable private fun AccentGroup(palette: ThemePalette) {
    val accent = rememberAccentState()
    var picking by remember { mutableStateOf(false) }
    val chosen = accent.accent
    // Black and white is the one palette an accent cannot re-tint, so the chooser says so rather
    // than accepting a colour that would never be drawn. The stored colour is kept untouched.
    val tinting = palette != ThemePalette.MONO
    SettingsGroup(
        "Accent colour",
        footer = if (tinting) "Replaces the wallpaper colours when both are on."
        else "Black and white keeps its own greys, so no accent colour is applied. Your choice is remembered for the other palettes.",
    ) {
        SettingsBlock {
            SettingsBlockHint(
                when {
                    !tinting -> "Not used by Black and white."
                    chosen == null -> "Using the palette's own colours."
                    else -> "Tints buttons, selections and highlights."
                }
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                AccentPresets.forEach { preset ->
                    ColorSwatch(preset, chosen != null && AccentTones.colorToArgb(preset) == AccentTones.colorToArgb(chosen), "Accent ${AccentTones.hex(preset)}", { accent.set(preset) }, enabled = tinting)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                OutlinedButton({ picking = true }, enabled = tinting, shapes = ButtonDefaults.shapes()) { Text("Pick a colour") }
                if (chosen != null) TextButton(accent::clear, enabled = tinting, shapes = ButtonDefaults.shapes()) { Text("Use palette colours") }
            }
        }
    }
    if (picking && tinting) {
        // The live preview lives with the dialog, so dismissing it leaves the accent alone.
        var preview by remember { mutableStateOf(chosen ?: AccentPresets.first()) }
        ColorDialog("Accent colour", onDismiss = { picking = false }) {
            ColorChooser(preview, "Accent", onPreview = { preview = it }, onConfirm = { accent.set(it); picking = false }, onClear = { accent.clear(); picking = false })
        }
    }
}

@Composable private fun ColorDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = FolioShapes.panel, modifier = Modifier.fillMaxWidth().padding(FolioSpacing.dp24)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
                TextButton(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Close") }
            }
        }
    }
}

/** Scales every sp-based label and heading, on top of the device's own font size. */
@Composable private fun TextSizeGroup() {
    val p = rememberPrefs()
    val scale by rememberPref(p, AppPrefs.UI_TEXT_SCALE) { AppPrefs.uiTextScale(it.getFloat(AppPrefs.UI_TEXT_SCALE, AppPrefs.DEFAULT_UI_TEXT_SCALE).takeIf { _ -> it.contains(AppPrefs.UI_TEXT_SCALE) }) }
    SettingsGroup("Text size", footer = "Multiplies your device's font size, so a larger system font still wins. Handwriting and typed text sizes are separate.") {
        SettingsSliderRow(
            "App text", "${(scale * 100).roundToInt()}%", scale, AppPrefs.UI_TEXT_SCALE_MIN..AppPrefs.UI_TEXT_SCALE_MAX,
            { p.write { putFloat(AppPrefs.UI_TEXT_SCALE, AppPrefs.uiTextScale(it)) } }, steps = 10,
            onReset = if (scale != AppPrefs.DEFAULT_UI_TEXT_SCALE) ({ p.write { putFloat(AppPrefs.UI_TEXT_SCALE, AppPrefs.DEFAULT_UI_TEXT_SCALE) } }) else null,
        )
    }
}

// ---- Writing ------------------------------------------------------------------------------------

@Composable internal fun WritingPage(s: AppSettings) {
    val p = rememberPrefs()
    val defaultTool by rememberPref(p, AppPrefs.DEFAULT_TOOL) { AppPrefs.defaultTool(it.getString(AppPrefs.DEFAULT_TOOL, null)) }
    val textSize by rememberPref(p, AppPrefs.TEXT_SIZE_KEY) { AppPrefs.textSize(it.getFloat(AppPrefs.TEXT_SIZE_KEY, AppPrefs.DEFAULT_TEXT_SIZE).takeIf { _ -> it.contains(AppPrefs.TEXT_SIZE_KEY) }) }
    val textAlign by rememberPref(p, "text.align") { readEnum(it.getString("text.align", null), TextAlignMode.LEFT, TextAlignMode.entries.toTypedArray()) }
    val shapeHoldMs by rememberPref(p, AppPrefs.SHAPE_HOLD_MS) { AppPrefs.shapeHoldMs(it.getLong(AppPrefs.SHAPE_HOLD_MS, AppPrefs.DEFAULT_SHAPE_HOLD_MS)) }
    SettingsGroup("Input") {
        SettingsSwitchRow("Draw with a finger", "Off: a finger scrolls and only the stylus writes. On: scroll with two fingers or the hand tool. Until you choose, this turns off by itself the first time a pen is used. Palm touches are ignored while the stylus writes.", s.finger, s.onFinger)
        SettingsDivider()
        SettingsPrefSwitch(EditorQuickPrefs.PULL_TO_ADD_PAGE, true, "Pull past the end to add a page", "Keep scrolling past the last page and let go to add a blank page. Off: use the Add page button.")
        SettingsDivider()
        SettingsSwitchRow("Tidy up shapes", "Draw, then pause with the pen down to preview a clean shape. Lift to keep it, or keep drawing to return to freehand. Supports lines, arrows, circles, ovals, triangles, quadrilaterals, pentagons, hexagons and stars. Undo restores your drawing.", s.shapeRecognition, s.onShapeRecognition)
        if (s.shapeRecognition) {
            SettingsDivider()
            SettingsSliderRow("Hold delay", "$shapeHoldMs ms", shapeHoldMs.toFloat(),
                AppPrefs.SHAPE_HOLD_MIN_MS.toFloat()..AppPrefs.SHAPE_HOLD_MAX_MS.toFloat(),
                { p.write { putLong(AppPrefs.SHAPE_HOLD_MS, AppPrefs.shapeHoldMs(it.toLong())) } }, steps = 23,
                onReset = if (shapeHoldMs != AppPrefs.DEFAULT_SHAPE_HOLD_MS)
                    ({ p.write { putLong(AppPrefs.SHAPE_HOLD_MS, AppPrefs.DEFAULT_SHAPE_HOLD_MS) } }) else null)
        }
    }
    SettingsGroup("Shapes") {
        SettingsPrefSwitch(EditorQuickPrefs.SHAPE_MEASUREMENTS, true, "Live measurements", "Show length and angle, or width × height, while drawing a shape.")
        SettingsDivider()
        SettingsPrefSwitch("mathSnap", true, "Snap to grid and 15°", "Lines snap to 15° and to the grid on Maths, Grid and Graph paper. Toggle any time in the editor.")
    }
    ToolbarGroups()
    SettingsGroup("Tool in hand", footer = "The tool a notebook opens with. Ink colour, width and opacity stay per tool in the editor's tool settings.") {
        DefaultToolPicker(defaultTool) { p.write { putString(AppPrefs.DEFAULT_TOOL, it.name) } }
    }
    SettingsGroup("Typed text", footer = "New text boxes use these. Existing boxes are unchanged.") {
        SettingsSliderRow(
            "Size", "${textSize.roundToInt()} pt", textSize, AppPrefs.TEXT_SIZE_MIN..AppPrefs.TEXT_SIZE_MAX,
            { p.write { putFloat(AppPrefs.TEXT_SIZE_KEY, AppPrefs.textSize(it)) } },
            onReset = if (textSize != AppPrefs.DEFAULT_TEXT_SIZE) ({ p.write { putFloat(AppPrefs.TEXT_SIZE_KEY, AppPrefs.DEFAULT_TEXT_SIZE) } }) else null,
        )
        SettingsDivider()
        SettingsSegmentedRow("Alignment", TextAlignMode.entries.map { it to title(it.name) }, textAlign, { p.write { putString("text.align", it.name) } })
    }
}

/**
 * The editor toolbar: its layout, which tools it carries, and what sits beside them. Every
 * option applies to both styles except the tab strip, which only the Goodnotes-inspired bar has.
 */
@Composable private fun ToolbarGroups() {
    val p = rememberPrefs()
    val style by rememberPref(p, AppPrefs.TOOLBAR_STYLE) { AppPrefs.toolbarStyle(it.getString(AppPrefs.TOOLBAR_STYLE, null)) }
    val options by rememberPref(p, *ToolbarOptions.KEYS) { ToolbarOptions.read(it) }
    val layouts = rememberToolbarLayoutState(p)
    val inkPrefs = LocalContext.current.let { context -> remember(context) { context.getSharedPreferences("ink-tools", 0) } }
    val presets = remember(inkPrefs) { ToolPresetState(inkPrefs) }
    var editTools by rememberSaveable { mutableStateOf(false) }
    SettingsGroup("Toolbar style") {
        SettingsRadioGroup {
            ToolbarStyle.entries.forEach { option ->
                SettingsRadioRow(option.label, option.description, option == style) { p.write { putString(AppPrefs.TOOLBAR_STYLE, option.name) } }
            }
        }
    }
    SettingsGroup("Toolbar", footer = "Anything you take off the toolbar stays one tap away: undo and redo in the … menu, the timer in the notebook menu.") {
        val layout = layouts.layout
        SettingsLinkRow(
            "Tools and order",
            "${layout.primary.size} on the strip" +
                (if (layout.overflow.isNotEmpty()) " · ${layout.overflow.size} under …" else "") +
                (if (layout.hidden.isNotEmpty()) " · ${layout.hidden.size} hidden" else "") +
                (if (layout.pinnedPresetIds.isNotEmpty()) " · ${layout.pinnedPresetIds.size} pinned" else ""),
            onClick = { editTools = true },
            leadingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    layout.primary.take(3).forEach { slot ->
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.size(28.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(toolIcon(slot.tools.first()), null, Modifier.size(16.dp)) }
                        }
                    }
                }
            },
        )
        SettingsDivider()
        SettingsSegmentedRow("Undo and redo", UndoButtons.entries.map { it to it.label }, options.undo,
            { p.write { putString(AppPrefs.TOOLBAR_UNDO, it.name) } })
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.TOOLBAR_COLOUR_DOTS, true, "Colour dots", "The pen and highlighter buttons show the colour each will draw in.")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.TOOLBAR_PINNED_PRESETS, true, "Pinned presets", "Show presets you have pinned beside the tools. Turning this off keeps them pinned.")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.TOOLBAR_TIMER, true, "Timer on the toolbar", "Show the exam timer and Focal chips beside the tools when there is room.")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.TOOLBAR_INK_OPTIONS_OPEN, false, "Start with ink options open", "Widths and colours appear under the toolbar as soon as a notebook opens.")
        if (style == ToolbarStyle.GOODNOTES) {
            SettingsDivider()
            SettingsPrefSwitch(AppPrefs.TOOLBAR_TABS, true, "Document tabs", "Show open documents as tabs above the tools. Off: a back button joins the bar.")
        }
    }
    if (editTools) ToolbarEditPanel(layouts, presets.presets, onDismiss = { editTools = false })
}

/** The tools a notebook can open with, as labelled tiles; shapes are a second, smaller row. */
@Composable private fun DefaultToolPicker(selected: Tool, onSelect: (Tool) -> Unit) {
    // Mark area only means something on an imported PDF, so it is never a sensible starting tool.
    val tools = listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASSO, Tool.TEXT, Tool.HAND, Tool.STICKY_NOTE)
    SettingsBlock(verticalSpacing = FolioSpacing.dp12) {
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            tools.forEach { tool -> ToolTile(tool, tool == selected, large = true) { onSelect(tool) } }
        }
        SettingsBlockHint("Or start with a shape")
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            ShapePickerTools.forEach { tool -> ToolTile(tool, tool == selected, large = false) { onSelect(tool) } }
        }
    }
}

@Composable private fun ToolTile(tool: Tool, selected: Boolean, large: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = toolLabel(tool)
    Surface(
        selected = selected, onClick = onClick,
        shape = FolioShapes.large,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
        modifier = Modifier.semantics { role = Role.RadioButton; contentDescription = label }
            .then(if (large) Modifier.width(96.dp).height(80.dp) else Modifier.height(40.dp)),
    ) {
        if (large) Column(Modifier.padding(FolioSpacing.dp8), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6, Alignment.CenterVertically)) {
            Icon(toolIcon(tool), null, Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface)
        } else Row(Modifier.padding(horizontal = FolioSpacing.dp12), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Icon(toolIcon(tool), null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

// ---- Stylus & touch -----------------------------------------------------------------------------

@Composable internal fun StylusPage(s: AppSettings) {
    val p = rememberPrefs()
    val palmMs by rememberPref(p, AppPrefs.PALM_MS) { AppPrefs.palmMs(it.getLong(AppPrefs.PALM_MS, AppPrefs.DEFAULT_PALM_MS).takeIf { _ -> it.contains(AppPrefs.PALM_MS) }) }
    val fastPan by rememberPref(p, AppPrefs.FAST_PAN) { it.getBoolean(AppPrefs.FAST_PAN, AppPrefs.DEFAULT_FAST_PAN) }
    val panMultiplier by rememberPref(p, AppPrefs.FAST_PAN_MULTIPLIER) { AppPrefs.fastPanMultiplier(it.getFloat(AppPrefs.FAST_PAN_MULTIPLIER, AppPrefs.DEFAULT_FAST_PAN_MULTIPLIER)) }
    SettingsGroup("Pencil double-tap", footer = "Works with a OnePlus or OPPO Pencil. Other styli keep their own system shortcut.") {
        SettingsRadioGroup {
            StylusShortcut.entries.forEach { SettingsRadioRow(it.label, it.description, s.stylus == it) { s.onStylus(it) } }
        }
    }
    SettingsGroup("Haptics") {
        SettingsSwitchRow(
            "Pen haptics",
            if (s.hapticsSupported) "Buzz the Pencil on every double tap it sends, whatever the action. Needs Bluetooth; confirmed on the OnePlus Pencil Pro. This is not the pen's soft writing feedback."
            else "Needs Android 12 or newer and a Bluetooth LE pencil.",
            s.haptics, s.onHaptics, s.hapticsSupported,
        )
    }
    SettingsGroup("Palm rejection and gestures", footer = "Pen pressure curves stay per tool in the editor.") {
        SettingsSliderRow(
            "Palm rejection", if (palmMs == 0L) "System only" else "$palmMs ms", palmMs.toFloat(), AppPrefs.PALM_MIN_MS.toFloat()..AppPrefs.PALM_MAX_MS.toFloat(),
            { p.write { putLong(AppPrefs.PALM_MS, AppPrefs.palmMs(it.roundToInt().toLong())) } },
            subtitle = "Protects against palm touches while the pen is in range and for this long afterwards, and takes back finger ink drawn just before the pen arrived. Deliberate two-finger zoom still works. 0 keeps Android palm cancellation and pen-only input while the tip is down.",
            onReset = if (palmMs != AppPrefs.DEFAULT_PALM_MS) ({ p.write { putLong(AppPrefs.PALM_MS, AppPrefs.DEFAULT_PALM_MS) } }) else null,
        )
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.FAST_PAN, AppPrefs.DEFAULT_FAST_PAN, "Fast pan", "Finger and two-finger drags move the page further than your fingers travel, and flings carry faster.")
        if (fastPan) {
            SettingsSliderRow(
                "Pan speed", "%.2g×".format(panMultiplier), panMultiplier, AppPrefs.FAST_PAN_MIN..AppPrefs.FAST_PAN_MAX,
                { p.write { putFloat(AppPrefs.FAST_PAN_MULTIPLIER, AppPrefs.fastPanMultiplier((it * 4).roundToInt() / 4f)) } },
                subtitle = "Multiplier on pan speed. 1× is normal.",
                onReset = if (panMultiplier != AppPrefs.DEFAULT_FAST_PAN_MULTIPLIER) ({ p.write { putFloat(AppPrefs.FAST_PAN_MULTIPLIER, AppPrefs.DEFAULT_FAST_PAN_MULTIPLIER) } }) else null,
            )
        }
        SettingsDivider()
        SettingsPrefSwitch(EditorQuickPrefs.MULTI_TOUCH_UNDO, true, "Two-finger tap to undo", "Two fingers undo, three redo, on the page canvas. Stylus and palm input never trigger it.")
    }
}

// ---- Erasing ------------------------------------------------------------------------------------

@Composable internal fun ErasingPage() {
    SettingsGroup("Eraser") {
        SettingsPrefSwitch(EditorQuickPrefs.ERASER_PRESSURE, true, "Pressure-sensitive", "Grows slightly with stronger pressure (about ±12%).")
        SettingsDivider()
        SettingsPrefSwitch(EditorQuickPrefs.ERASER_SINGLE_STROKE, false, "Single stroke", "Return to the previous tool after one eraser stroke.")
        SettingsDivider()
        SettingsPrefSwitch(EditorQuickPrefs.ERASER_WHOLE_STROKE, false, "Whole stroke", "Remove an entire stroke when touching any part of it.")
    }
    SettingsGroup("Scribble to erase") {
        SettingsBlock { ScribbleSettingsSection(showPracticeInitially = false) }
    }
}

// ---- Writing follow -----------------------------------------------------------------------------

// ---- Library & covers ---------------------------------------------------------------------------

@Composable internal fun LibraryPage(folders: List<Folder>) {
    val p = rememberPrefs()
    val sort by rememberPref(p, AppPrefs.LIB_SORT) { AppPrefs.librarySort(it.getString(AppPrefs.LIB_SORT, null)) }
    val kind by rememberPref(p, AppPrefs.LIB_KIND) { AppPrefs.libraryKind(it.getString(AppPrefs.LIB_KIND, null)) }
    val listView by rememberPref(p, AppPrefs.LIB_LIST) { it.getBoolean(AppPrefs.LIB_LIST, AppPrefs.DEFAULT_LIST_VIEW) }
    val paper by rememberPref(p, AppPrefs.DEFAULT_PAPER) { AppPrefs.defaultPaper(it.getString(AppPrefs.DEFAULT_PAPER, null)) }
    val cover by rememberPref(p, AppPrefs.DEFAULT_COVER) { AppPrefs.defaultCover(it.getInt(AppPrefs.DEFAULT_COVER, AppPrefs.DEFAULT_COVER_INDEX).takeIf { _ -> it.contains(AppPrefs.DEFAULT_COVER) } ?: AppPrefs.DEFAULT_COVER_INDEX) }
    val pageCover by rememberPref(p, AppPrefs.DEFAULT_PAGE_COVER) { it.getBoolean(AppPrefs.DEFAULT_PAGE_COVER, AppPrefs.DEFAULT_PAGE_COVER_ENABLED) }
    SettingsGroup("Shelf", footer = "These are also saved whenever you change sorting or view on the shelf itself.") {
        SettingsChipRow("Sort by", LibrarySort.entries.map { it to it.label }, sort, { p.write { putString(AppPrefs.LIB_SORT, it.name) } })
        SettingsDivider()
        SettingsChipRow("Show", LibraryKind.entries.map { it to it.label }, kind, { p.write { putString(AppPrefs.LIB_KIND, it.name) } })
        SettingsDivider()
        SettingsSegmentedRow("Layout", listOf(false to "Covers", true to "Compact list"), listView, { p.write { putBoolean(AppPrefs.LIB_LIST, it) } })
    }
    SettingsGroup("New notebooks") {
        SettingsChipRow("Paper", Paper.entries.map { it to paperLabel(it) }, paper, { p.write { putString(AppPrefs.DEFAULT_PAPER, it.name) } })
        SettingsDivider()
        SettingsBlock {
            SettingsBlockTitle("Cover design and colour")
            CoverPicker(cover, { p.write { putInt(AppPrefs.DEFAULT_COVER, AppPrefs.defaultCover(it)) } }, "Your next idea")
        }
        SettingsDivider()
        SettingsSwitchRow("First page as cover", "New notebooks show their first page on the shelf. Off uses the decorative cover.", pageCover,
            { p.write { putBoolean(AppPrefs.DEFAULT_PAGE_COVER, it) } },
            onReset = if (pageCover != AppPrefs.DEFAULT_PAGE_COVER_ENABLED) ({ p.write { putBoolean(AppPrefs.DEFAULT_PAGE_COVER, AppPrefs.DEFAULT_PAGE_COVER_ENABLED) } }) else null)
    }
    val quickRaw by rememberPref(p, AppPrefs.QUICK_FOLDER) { it.getString(AppPrefs.QUICK_FOLDER, null) }
    val quickFolder = AppPrefs.quickFolderId(quickRaw, folders)
    SettingsGroup("Quick notes and canvases", footer = "Where the Quick note and Canvas buttons save new notebooks.") {
        SettingsRadioGroup {
            SettingsRadioRow("Library", "Top level of the shelf", quickFolder == null) { p.write { remove(AppPrefs.QUICK_FOLDER) } }
            folders.map { it to LibraryFolders.label(folders, it.id) }.sortedBy { it.second }.forEach { (folder, label) ->
                SettingsRadioRow(label, null, quickFolder == folder.id) { p.write { putString(AppPrefs.QUICK_FOLDER, folder.id) } }
            }
        }
    }
    CustomCoverGroup()
}

/** Add, remove and preview the cover colours that sit after the built-in covers. */
@Composable private fun CustomCoverGroup() {
    val custom = rememberCustomCoverColors()
    val palette = rememberCoverPalette()
    var picking by remember { mutableStateOf(false) }
    val full = custom.size >= CoverPalette.MAX
    SettingsGroup("Your cover colours", footer = "Added colours join every notebook picker. Existing notebooks keep the cover they were given.") {
        SettingsBlock {
            if (custom.isEmpty()) SettingsBlockHint("None yet.")
            else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    // A notebook stores a cover *index*, so only the newest colour can be removed
                    // without moving the colours that follow it onto other notebooks.
                    custom.forEachIndexed { index, color ->
                        val newest = index == custom.lastIndex
                        ColorSwatch(color, false, if (newest) "Remove cover colour ${AccentTones.hex(color)}" else "Cover colour ${AccentTones.hex(color)}",
                            if (newest) ({ palette.remove(color) }) else ({ }))
                    }
                }
                SettingsBlockHint("Tap the newest colour to remove it. Removing from further down would change the covers of notebooks already using those colours.")
            }
            OutlinedButton({ picking = true }, enabled = !full, shapes = ButtonDefaults.shapes()) {
                Text(if (full) "Maximum ${CoverPalette.MAX} colours reached" else "Add a cover colour")
            }
        }
        // A colour already in the list is hidden: re-adding it would move it to the end and hand
        // its notebooks a different colour.
        val suggestions = SuggestedCoverColors.filter { s -> custom.none { AccentTones.colorToArgb(it) == AccentTones.colorToArgb(s) } }
        if (suggestions.isNotEmpty() && !full) {
            SettingsDivider()
            SettingsBlock {
                SettingsBlockTitle("Suggested colours")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    suggestions.forEach { color -> ColorSwatch(color, false, "Add cover colour ${AccentTones.hex(color)}", { palette.add(color) }) }
                }
                SettingsBlockHint("Tap to add one to your cover colours.")
            }
        }
    }
    if (picking) {
        var preview by remember { mutableStateOf(AccentPresets.first()) }
        ColorDialog("New cover colour", onDismiss = { picking = false }) {
            ColorChooser(AccentPresets.first(), "Cover colour", onPreview = { preview = it }, onConfirm = { palette.add(it); picking = false })
            Button({ palette.add(preview); picking = false }, shapes = ButtonDefaults.shapes()) { Text("Save colour") }
        }
    }
}

private fun paperLabel(paper: Paper): String = when (paper) {
    Paper.SPLIT_RULED -> "Split ruled"
    Paper.MATH_GRID -> "Maths grid"
    Paper.GRAPH -> "Graph"
    Paper.MC_SHEET -> "MC sheet"
    Paper.TIAN_GRID -> "Tian (田字格)"
    Paper.MI_GRID -> "Mi (米字格)"
    else -> title(paper.name)
}

// ---- Backup & restore ---------------------------------------------------------------------------

@Composable internal fun BackupPage(b: BackupSettings) {
    val auto = b.tree != null
    SettingsGroup(
        "Automatic backup",
        footer = "After saved changes and once a day, Folio checks for changes and copies only new data. It keeps the latest two restore points and shares unchanged pages, PDFs and images.",
    ) {
        SettingsLinkRow(
            if (auto) "Backing up to ${b.folderName ?: "the selected folder"}" else "Not set up",
            when {
                b.progress != null -> b.progress
                b.lastError != null -> "Issue: ${b.lastError}"
                b.lastSuccess > 0L -> "Last checked ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(b.lastSuccess))}"
                auto -> "No automatic backup has completed yet."
                else -> "Pick a folder, on this device or in a cloud drive, to start."
            },
            trailing = {},
        )
        if (b.progress != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp12))
        }
        SettingsDivider()
        SettingsLinkRow(if (auto) "Change backup folder" else "Choose backup folder", onClick = b.onChooseFolder, enabled = !b.busy)
        if (auto) {
            SettingsDivider()
            SettingsLinkRow("Back up now", onClick = b.onBackupNow, enabled = !b.busy)
            SettingsDivider()
            SettingsLinkRow("Turn off automatic backup", onClick = b.onDisable, enabled = !b.busy)
        }
    }
    SettingsGroup("Restore", footer = "Choose the same folder on any device to restore its latest backup. Keep the restore points and the Folio backup data folder together.") {
        SettingsLinkRow("Restore from backup folder", onClick = b.onRestoreFromFolder, enabled = !b.busy)
    }
    SettingsGroup("Backup exclusions", footer = "Leave textbooks or other notebooks out of automatic and library backups. Exporting a single notebook still includes everything.") {
        SettingsLinkRow("Excluded notebooks", if (b.excludedCount == 0) "None" else "${b.excludedCount} left out", onClick = b.onExclusions, enabled = !b.busy)
    }
    SettingsGroup("Library file", footer = "A compact, self-contained file for sharing or moving devices. Included notebooks keep all pages and undo history. Restoring adds copies beside your current notebooks; older backups still open.") {
        SettingsLinkRow("Save library backup", onClick = b.onSaveLibrary, enabled = !b.busy)
        SettingsDivider()
        SettingsLinkRow("Restore library backup", onClick = b.onRestoreLibrary, enabled = !b.busy)
    }
}

// ---- Timer & workspace --------------------------------------------------------------------------

@Composable internal fun WorkflowPage() {
    val p = rememberPrefs()
    val customMinutes by rememberPref(p, AppPrefs.TIMER_CUSTOM_MIN) { AppPrefs.timerCustomMinutes(it.getInt(AppPrefs.TIMER_CUSTOM_MIN, AppPrefs.DEFAULT_TIMER_CUSTOM_MIN).takeIf { _ -> it.contains(AppPrefs.TIMER_CUSTOM_MIN) }) }
    val reading by rememberPref(p, AppPrefs.TIMER_READING_MIN) { AppPrefs.timerReadingMinutes(it.getInt(AppPrefs.TIMER_READING_MIN, AppPrefs.DEFAULT_TIMER_READING_MIN).takeIf { _ -> it.contains(AppPrefs.TIMER_READING_MIN) }) }
    val autoStart by rememberPref(p, AppPrefs.TIMER_AUTO_START) { it.getBoolean(AppPrefs.TIMER_AUTO_START, AppPrefs.DEFAULT_TIMER_AUTO_START) }
    val idle by rememberPref(p, AppPrefs.TIMER_IDLE_MIN) { AppPrefs.timerIdleMinutes(it.getInt(AppPrefs.TIMER_IDLE_MIN, AppPrefs.DEFAULT_TIMER_IDLE_MIN).takeIf { _ -> it.contains(AppPrefs.TIMER_IDLE_MIN) }) }
    val png by rememberPref(p, AppPrefs.EXPORT_PNG_SCALE) { AppPrefs.pngScale(it.getFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE).takeIf { _ -> it.contains(AppPrefs.EXPORT_PNG_SCALE) }) }
    val split by rememberPref(p, AppPrefs.SPLIT_FRACTION) { AppPrefs.splitFraction(it.getFloat(AppPrefs.SPLIT_FRACTION, AppPrefs.DEFAULT_SPLIT).takeIf { _ -> it.contains(AppPrefs.SPLIT_FRACTION) }) }
    val shareShortcut by rememberPref(p, AppPrefs.SHARE_LONG_PRESS) { AppPrefs.shareShortcut(it.getString(AppPrefs.SHARE_LONG_PRESS, null)) }
    // The field holds what is being typed; only a value in range is stored.
    var typed by rememberSaveable { mutableStateOf(customMinutes.toString()) }
    val typedValue = typed.toIntOrNull()
    val typedInvalid = typedValue != null && typedValue !in AppPrefs.TIMER_CUSTOM_MIN_RANGE..AppPrefs.TIMER_CUSTOM_MAX
    LaunchedEffect(customMinutes) { if (typed.toIntOrNull() != customMinutes && typed.toIntOrNull()?.let { it in AppPrefs.TIMER_CUSTOM_MIN_RANGE..AppPrefs.TIMER_CUSTOM_MAX } != false) typed = customMinutes.toString() }

    SettingsGroup("Exam timer", footer = "Exam 1 (90) and Exam 2 (120) presets are unchanged.") {
        SettingsBlock {
            OutlinedTextField(
                typed, { raw ->
                    typed = raw.filter(Char::isDigit).take(3)
                    typed.toIntOrNull()?.takeIf { it in AppPrefs.TIMER_CUSTOM_MIN_RANGE..AppPrefs.TIMER_CUSTOM_MAX }?.let { p.write { putInt(AppPrefs.TIMER_CUSTOM_MIN, it) } }
                },
                Modifier.fillMaxWidth(), label = { Text("Custom timer, writing minutes") }, singleLine = true, isError = typedInvalid,
                supportingText = { Text(if (typedInvalid) "Enter 1–480" else "Prefills the Custom timer.") },
            )
        }
        SettingsDivider()
        SettingsSliderRow(
            "Custom timer, reading time", "$reading min", reading.toFloat(), AppPrefs.TIMER_READING_MIN_RANGE.toFloat()..AppPrefs.TIMER_READING_MAX.toFloat(),
            { p.write { putInt(AppPrefs.TIMER_READING_MIN, it.roundToInt()) } }, steps = AppPrefs.TIMER_READING_MAX - AppPrefs.TIMER_READING_MIN_RANGE - 1,
            onReset = if (reading != AppPrefs.DEFAULT_TIMER_READING_MIN) ({ p.write { putInt(AppPrefs.TIMER_READING_MIN, AppPrefs.DEFAULT_TIMER_READING_MIN) } }) else null,
        )
        SettingsDivider()
        SettingsSwitchRow("Resume on pen down", "A paused exam clock resumes on the next pen stroke. The pen never starts a new sitting by itself; start it from the timer panel.", autoStart,
            { p.write { putBoolean(AppPrefs.TIMER_AUTO_START, it) } },
            onReset = if (autoStart != AppPrefs.DEFAULT_TIMER_AUTO_START) ({ p.write { putBoolean(AppPrefs.TIMER_AUTO_START, AppPrefs.DEFAULT_TIMER_AUTO_START) } }) else null)
        SettingsDivider()
        SettingsSliderRow(
            "Stop after inactivity", if (idle <= 0) "Never" else "$idle min", idle.toFloat(), AppPrefs.TIMER_IDLE_MIN_RANGE.toFloat()..AppPrefs.TIMER_IDLE_MAX.toFloat(),
            { p.write { putInt(AppPrefs.TIMER_IDLE_MIN, it.roundToInt()) } }, steps = AppPrefs.TIMER_IDLE_MAX - AppPrefs.TIMER_IDLE_MIN_RANGE - 1,
            subtitle = "The clock stops once the pen has been idle this long and your next stroke resumes it. 0 keeps it running until you stop it. Leaving the editor or the screen going off parks it as before.",
            onReset = if (idle != AppPrefs.DEFAULT_TIMER_IDLE_MIN) ({ p.write { putInt(AppPrefs.TIMER_IDLE_MIN, AppPrefs.DEFAULT_TIMER_IDLE_MIN) } }) else null,
        )
    }
    SettingsGroup("Export", footer = "PDF exports keep vector ink; this only affects page images. Higher is crisper on large pages and larger to share.") {
        SettingsSliderRow(
            "Page image (PNG) sharpness", "${"%.1f".format(png)}×", png, AppPrefs.PNG_SCALE_MIN..AppPrefs.PNG_SCALE_MAX,
            { p.write { putFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.pngScale(it)) } },
            onReset = if (png != AppPrefs.DEFAULT_PNG_SCALE) ({ p.write { putFloat(AppPrefs.EXPORT_PNG_SCALE, AppPrefs.DEFAULT_PNG_SCALE) } }) else null,
        )
    }
    SettingsGroup("Share button", footer = "Long-press the share button in the editor to run this straight away. A tap still opens the export menu.") {
        SettingsChipRow("Long-press action", ShareShortcut.entries.map { it to it.label }, shareShortcut,
            { p.write { putString(AppPrefs.SHARE_LONG_PRESS, it.name) } })
    }
    SettingsGroup("Split view", footer = "Drag the divider any time; the last position is remembered.") {
        SettingsSliderRow(
            "Editor share", "${(split * 100).roundToInt()}%", split, SplitPanes.MIN_FRACTION..SplitPanes.MAX_FRACTION,
            { p.write { putFloat(AppPrefs.SPLIT_FRACTION, AppPrefs.splitFraction(it)) } },
            onReset = if (split != AppPrefs.DEFAULT_SPLIT) ({ p.write { putFloat(AppPrefs.SPLIT_FRACTION, AppPrefs.DEFAULT_SPLIT) } }) else null,
        )
    }
}

// ---- Mistake practice ---------------------------------------------------------------------------

/** The printed guide a new mistake-practice page starts with; the canvas itself stays infinite. */
@Composable internal fun MistakesPage() {
    val p = rememberPrefs()
    val paper by rememberPref(p, AppPrefs.MISTAKE_PAPER) { AppPrefs.mistakePaper(it.getString(AppPrefs.MISTAKE_PAPER, null)) }
    SettingsGroup("Practice pages", footer = "Every question you practise opens on its own infinite canvas; this picks the guide printed behind it. It applies to the next question; pages you have already written keep their own paper.") {
        SettingsChipRow("Paper guide", Paper.entries.map { it to paperLabel(it) }, paper, { p.write { putString(AppPrefs.MISTAKE_PAPER, it.name) } })
    }
    SettingsGroup("Split layout") {
        SettingsBlock { SettingsBlockHint("The question sits beside your writing. Drag the divider to give one more room, or double-tap it for the default split; the last position is remembered.") }
    }
}

// ---- Account & updates --------------------------------------------------------------------------

@Composable internal fun AccountPage(a: AccountSettings) {
    val prefs = rememberPrefs()
    val experimental by rememberPref(prefs, AppPrefs.EXPERIMENTAL_UPDATES) {
        it.getBoolean(AppPrefs.EXPERIMENTAL_UPDATES, AppPrefs.DEFAULT_EXPERIMENTAL_UPDATES)
    }
    SettingsGroup("Focal", footer = "One account for study sessions and mistake sync.") {
        SettingsLinkRow("Manage Focal account", onClick = a.onFocal)
    }
    SettingsGroup("Updates", footer = "Your notebooks stay on this device. Use Backup & restore to save or restore the whole library.") {
        SettingsPrefSwitch(AppPrefs.AUTO_UPDATE, AppPrefs.DEFAULT_AUTO_UPDATE, "Check on launch", "Check the selected source for a newer signed build.")
        SettingsDivider()
        SettingsSwitchRow("Enable experimental builds", "Get experimental builds from folio.flandolf.me. These may be less stable. Experimental builds are checked automatically when enabled.",
            experimental, { prefs.write { putBoolean(AppPrefs.EXPERIMENTAL_UPDATES, it) } }, enabled = !a.updateBusy)
        SettingsDivider()
        SettingsLinkRow(
            if (a.updateChecking) "Checking for updates…" else if (experimental) "Check experimental builds" else "Check for updates", onClick = a.onCheckForUpdates, enabled = !a.updateBusy,
            trailing = if (a.updateChecking) ({ LoadingIndicator(Modifier.size(24.dp)) }) else null,
        )
        if (experimental) SettingsLinkRow("Check GitHub Releases", onClick = a.onCheckGitHub, enabled = !a.updateBusy)
        a.updateContent()
    }
}
