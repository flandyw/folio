@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.folio.notes.FolioApplication
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.SidebarItem
import com.folio.notes.editor.GestureGuide
import com.folio.notes.editor.follow.FollowFeel
import com.folio.notes.editor.follow.FollowMode
import com.folio.notes.editor.follow.FollowPreferenceStore
import com.folio.notes.editor.follow.FollowPreferences
import com.folio.notes.editor.follow.FollowPreview
import com.folio.notes.editor.follow.WritingDirection
import com.folio.notes.editor.follow.WritingHand
import com.folio.notes.editor.sticky.StickyNotes
import com.folio.notes.editor.toolbar.EditorQuickPrefs
import com.folio.notes.editor.toolbar.ToolPresetState
import com.folio.notes.editor.toolbar.ToolbarEditPanel
import com.folio.notes.editor.toolbar.ToolbarOptions
import com.folio.notes.editor.toolbar.UndoButtons
import com.folio.notes.editor.toolbar.rememberToolbarLayoutState
import com.folio.notes.editor.toolbar.toolLabel
import com.folio.notes.export.ShareShortcut
import com.folio.notes.ink.pen.FeedbackStatus
import com.folio.notes.ink.pen.PenWritingFeedback
import com.folio.notes.ink.pen.WritingFeel
import com.folio.notes.ink.recognition.ScribbleSettingsSection
import com.folio.notes.ink.shapes.ShapeAssistSettings
import com.folio.notes.input.StylusShortcut
import com.folio.notes.library.CoverPicker
import com.folio.notes.model.Folder
import com.folio.notes.model.Paper
import com.folio.notes.model.Tool
import com.folio.notes.storage.LibraryFolders
import com.folio.notes.ui.AccentPresets
import com.folio.notes.ui.AccentTones
import com.folio.notes.ui.ColorChooser
import com.folio.notes.ui.ColorSwatch
import com.folio.notes.ui.CoverPalette
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.SuggestedCoverColors
import com.folio.notes.ui.ThemeMode
import com.folio.notes.ui.ThemePalette
import com.folio.notes.ui.rememberAccentState
import com.folio.notes.ui.rememberCoverPalette
import com.folio.notes.ui.rememberCustomCoverColors
import kotlin.math.roundToInt

// One composable per settings category. Settings holds only what has no better home: anything the
// editor's own tool options or the Library already offer (pen smoothing, eraser behaviour, shelf
// sorting, …) is changed there, not repeated here. Each page reads and writes its preferences
// through rememberPref; the few values that live in FolioApp arrive through AppSettings.

internal class AppSettings(
    val themeMode: ThemeMode, val onThemeMode: (ThemeMode) -> Unit,
    val themePalette: ThemePalette, val onThemePalette: (ThemePalette) -> Unit,
    val amoled: Boolean, val onAmoled: (Boolean) -> Unit,
    val finger: Boolean, val onFinger: (Boolean) -> Unit,
    val stylus: StylusShortcut, val onStylus: (StylusShortcut) -> Unit,
    val haptics: Boolean, val onHaptics: (Boolean) -> Unit,
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

internal class AccountSettings(val onFocal: () -> Unit, val onCheckForUpdates: () -> Unit, val onCheckGitHub: () -> Unit, val updateChecking: Boolean, val updateBusy: Boolean, val updateContent: @Composable () -> Unit, val onChatGpt: () -> Unit)

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

// ---- Appearance ---------------------------------------------------------------------------------

@Composable internal fun AppearancePage(s: AppSettings) {
    val p = rememberPrefs()
    SettingsGroup("Theme") {
        SettingsSegmentedRow("Mode", listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), s.themeMode, s.onThemeMode)
        SettingsDivider()
        SettingsSwitchRow("Pure black dark", "True black backgrounds whenever the dark theme is active.", s.amoled, s.onAmoled)
    }
    SettingsGroup("Colours", footer = "Ink colours on the page never change with the theme or accent.") {
        SettingsChipRow("Palette", ThemePalette.entries.filter { it != ThemePalette.DYNAMIC || s.dynamicAvailable }.map { it to it.label },
            s.themePalette, s.onThemePalette, subtitle = s.themePalette.description)
        SettingsDivider()
        AccentBlock(s.themePalette)
    }
    val scale by rememberPref(p, AppPrefs.UI_TEXT_SCALE) { AppPrefs.uiTextScale(it.getFloat(AppPrefs.UI_TEXT_SCALE, AppPrefs.DEFAULT_UI_TEXT_SCALE).takeIf { _ -> it.contains(AppPrefs.UI_TEXT_SCALE) }) }
    SettingsGroup("Size") {
        SettingsSliderRow(
            "Text size", percent(scale), scale, AppPrefs.UI_TEXT_SCALE_MIN..AppPrefs.UI_TEXT_SCALE_MAX,
            { p.write { putFloat(AppPrefs.UI_TEXT_SCALE, AppPrefs.uiTextScale(it)) } }, steps = 10,
            subtitle = "Scales Folio's labels on top of your device's font size.",
            onReset = if (scale != AppPrefs.DEFAULT_UI_TEXT_SCALE) ({ p.write { putFloat(AppPrefs.UI_TEXT_SCALE, AppPrefs.DEFAULT_UI_TEXT_SCALE) } }) else null,
        )
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.UX_LARGE_CONTROLS, AppPrefs.DEFAULT_UX_LARGE_CONTROLS, "Larger controls", "Make editor buttons and selection actions easier to tap.")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.UX_LABELS, AppPrefs.DEFAULT_UX_LABELS, "Show tool names", "Label the active tool and pinned presets in the editor.")
    }
    val hidden by rememberPref(p, SidebarItem.PREF_KEY) { SidebarItem.hidden(it.getString(SidebarItem.PREF_KEY, null)) }
    SettingsGroup("Sidebar", footer = "Library and Settings are always shown.") {
        SidebarItem.entries.forEachIndexed { index, item ->
            if (index > 0) SettingsDivider()
            SettingsSwitchRow(item.label, item.summary, item !in hidden, { on ->
                p.write { putString(SidebarItem.PREF_KEY, SidebarItem.encode(if (on) hidden - item else hidden + item)) }
            })
        }
    }
    SettingsGroup("Display") {
        SettingsPrefSwitch(AppPrefs.FULLSCREEN, AppPrefs.DEFAULT_FULLSCREEN, "Fullscreen", "Hide the status bar and gesture pill. Swipe from an edge to reveal them.")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.KEEP_SCREEN_ON, AppPrefs.DEFAULT_KEEP_SCREEN_ON, "Keep screen on", "The display never sleeps while Folio is open.")
    }
}

/** One colour of your own, applied to every Folio palette by re-tinting its accents. */
@Composable private fun AccentBlock(palette: ThemePalette) {
    val accent = rememberAccentState()
    var picking by remember { mutableStateOf(false) }
    val chosen = accent.accent
    // Black and white is the one palette an accent cannot re-tint; the stored colour is kept untouched.
    val tinting = palette != ThemePalette.MONO
    SettingsBlock {
        SettingsBlockTitle("Accent colour", when {
            !tinting -> "Not used by Black and white. Your choice is kept for the other palettes."
            chosen == null -> "Using the palette's own colours."
            else -> "Tints buttons, selections and highlights."
        })
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

// ---- Pen & touch --------------------------------------------------------------------------------

@Composable internal fun PenPage(s: AppSettings) {
    val p = rememberPrefs()
    val palmMs by rememberPref(p, AppPrefs.PALM_MS) { AppPrefs.palmMs(it.getLong(AppPrefs.PALM_MS, AppPrefs.DEFAULT_PALM_MS).takeIf { _ -> it.contains(AppPrefs.PALM_MS) }) }
    val fastPan by rememberPref(p, AppPrefs.FAST_PAN) { it.getBoolean(AppPrefs.FAST_PAN, AppPrefs.DEFAULT_FAST_PAN) }
    val panMultiplier by rememberPref(p, AppPrefs.FAST_PAN_MULTIPLIER) { AppPrefs.fastPanMultiplier(it.getFloat(AppPrefs.FAST_PAN_MULTIPLIER, AppPrefs.DEFAULT_FAST_PAN_MULTIPLIER)) }
    val startHold by rememberPref(p, AppPrefs.PEN_START_HOLD) { AppPrefs.penStartHold(it.getString(AppPrefs.PEN_START_HOLD, null)) }
    var guide by remember { mutableStateOf(false) }

    SettingsGroup("Touch") {
        SettingsSwitchRow("Draw with a finger", "Off: a finger scrolls and only the stylus writes. On: scroll with two fingers or the hand tool.", s.finger, s.onFinger)
        SettingsDivider()
        SettingsSliderRow(
            "Palm rejection", if (palmMs == 0L) "System only" else "$palmMs ms", palmMs.toFloat(), AppPrefs.PALM_MIN_MS.toFloat()..AppPrefs.PALM_MAX_MS.toFloat(),
            { p.write { putLong(AppPrefs.PALM_MS, AppPrefs.palmMs(it.roundToInt().toLong())) } },
            subtitle = "How long touches stay ignored after the pen leaves. Raise it if your palm leaves marks or moves the page.",
            onReset = if (palmMs != AppPrefs.DEFAULT_PALM_MS) ({ p.write { putLong(AppPrefs.PALM_MS, AppPrefs.DEFAULT_PALM_MS) } }) else null,
        )
        SettingsDivider()
        // One slider for the two stored values: 1× is "fast pan off", anything above is the multiplier.
        val pan = if (fastPan) panMultiplier else 1f
        SettingsSliderRow(
            "Pan speed", "%.2f".format(pan).trimEnd('0').trimEnd('.') + "×", pan, 1f..AppPrefs.FAST_PAN_MAX,
            { raw ->
                val v = (raw * 4).roundToInt() / 4f
                p.write {
                    putBoolean(AppPrefs.FAST_PAN, v >= AppPrefs.FAST_PAN_MIN)
                    if (v >= AppPrefs.FAST_PAN_MIN) putFloat(AppPrefs.FAST_PAN_MULTIPLIER, AppPrefs.fastPanMultiplier(v))
                }
            },
            steps = ((AppPrefs.FAST_PAN_MAX - 1f) * 4).roundToInt() - 1,
            subtitle = "How far a finger drag or fling moves the page. 1× follows your finger.",
            onReset = if (fastPan) ({ p.write { putBoolean(AppPrefs.FAST_PAN, false) } }) else null,
        )
        SettingsDivider()
        SettingsPrefSwitch(EditorQuickPrefs.MULTI_TOUCH_UNDO, true, "Two-finger tap to undo", "Tap the canvas with two fingers to undo, three to redo.")
        SettingsDivider()
        SettingsLinkRow("Gestures and shortcuts", "Everything the pen, fingers and keyboard can do.", onClick = { guide = true })
    }
    SettingsGroup("Pencil", footer = "Double-tap works with a OnePlus or OPPO Pencil. Other styli keep their own system shortcut.") {
        SettingsChipRow("Pencil double-tap", StylusShortcut.entries.map { it to it.label }, s.stylus, s.onStylus, subtitle = s.stylus.description)
        if (s.hapticsSupported) {
            SettingsDivider()
            SettingsSwitchRow("Double-tap buzz", "Buzz the Pencil on every double tap. Needs Bluetooth.", s.haptics, s.onHaptics)
        }
        if (PenWritingFeedback.isSupported()) WritingFeedbackRows()
    }
    SettingsGroup("Pen", footer = "Smoothing, soft stroke ends, pressure and the eraser's behaviour are in each tool's options in the editor.") {
        SettingsSegmentedRow("Hold at stroke start", listOf("highlighter" to "Highlighter", "eraser" to "Eraser", "off" to "Off"), startHold,
            { p.write { putString(AppPrefs.PEN_START_HOLD, AppPrefs.penStartHold(it)) } },
            subtitle = "Rest the pen tip still for half a second, then draw, to borrow this tool for one stroke.")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.HIGHLIGHTER_TILT, AppPrefs.DEFAULT_HIGHLIGHTER_TILT, "Tilt widens the highlighter", "Tilt the stylus for a broader highlight.")
    }
    SettingsGroup("Tidy up shapes") {
        SettingsBlock { ShapeAssistSettings() }
    }
    SettingsGroup("Scribble to erase") {
        SettingsBlock { ScribbleSettingsSection(showPracticeInitially = false) }
    }
    if (guide) GestureGuide { guide = false }
}

/** The Pencil Pro's own soft buzz while the tip is down, through ColorOS's pen service. */
@Composable private fun WritingFeedbackRows() {
    val p = rememberPrefs()
    val context = LocalContext.current
    val feedback = remember(context) { (context.applicationContext as? FolioApplication)?.penFeedback }
    val on by rememberPref(p, AppPrefs.WRITING_FEEDBACK) { it.getBoolean(AppPrefs.WRITING_FEEDBACK, AppPrefs.DEFAULT_WRITING_FEEDBACK) }
    val status = when (feedback?.status) {
        FeedbackStatus.Connected -> "Connected."
        FeedbackStatus.Connecting -> "Connecting to the pen service…"
        FeedbackStatus.Refused -> "ColorOS refused the pen service on this tablet."
        FeedbackStatus.Unavailable -> "This tablet has no ColorOS pen service."
        else -> "Connects when a notebook is open."
    }
    SettingsDivider()
    SettingsSwitchRow(
        "Buzz while writing",
        "The Pencil vibrates softly while its tip is down. Needs a licensed HiPaint or StarNote installed and Pencil vibration on in ColorOS. $status",
        on, { v -> p.write { putBoolean(AppPrefs.WRITING_FEEDBACK, v) } },
    )
    if (!on) return
    listOf(
        "Pen and shapes feel" to PenWritingFeedback.TEXTURE_BALLPEN,
        "Highlighter feel" to PenWritingFeedback.TEXTURE_MARKER,
        "Eraser feel" to PenWritingFeedback.TEXTURE_ERASER,
    ).forEach { (name, texture) ->
        val feel by rememberPref(p, WritingFeel.key(texture)) { WritingFeel.pick(it, texture) }
        SettingsChipRow(name, WritingFeel.entries.map { it to it.label }, feel, { p.write { putString(WritingFeel.key(texture), it.name) } })
    }
}

// ---- Editor -------------------------------------------------------------------------------------

@Composable internal fun EditorPage() {
    val p = rememberPrefs()
    val options by rememberPref(p, *ToolbarOptions.KEYS) { ToolbarOptions.read(it) }
    val layouts = rememberToolbarLayoutState(p)
    val inkPrefs = LocalContext.current.let { context -> remember(context) { context.getSharedPreferences("ink-tools", 0) } }
    val presets = remember(inkPrefs) { ToolPresetState(inkPrefs) }
    var editTools by rememberSaveable { mutableStateOf(false) }
    val defaultTool by rememberPref(p, AppPrefs.DEFAULT_TOOL) { AppPrefs.defaultTool(it.getString(AppPrefs.DEFAULT_TOOL, null)) }
    val maxZoom by rememberPref(p, AppPrefs.MAX_ZOOM) { AppPrefs.maxZoom(it.getFloat(AppPrefs.MAX_ZOOM, AppPrefs.DEFAULT_MAX_ZOOM)) }
    val textSize by rememberPref(p, AppPrefs.TEXT_SIZE_KEY) { AppPrefs.textSize(it.getFloat(AppPrefs.TEXT_SIZE_KEY, AppPrefs.DEFAULT_TEXT_SIZE).takeIf { _ -> it.contains(AppPrefs.TEXT_SIZE_KEY) }) }
    val stickyTextSize by rememberPref(p, AppPrefs.STICKY_TEXT_SIZE) { AppPrefs.stickyTextSize(it.getFloat(AppPrefs.STICKY_TEXT_SIZE, AppPrefs.DEFAULT_STICKY_TEXT_SIZE)) }
    val shareShortcut by rememberPref(p, AppPrefs.SHARE_LONG_PRESS) { AppPrefs.shareShortcut(it.getString(AppPrefs.SHARE_LONG_PRESS, null)) }

    SettingsGroup("Toolbar", footer = "Anything you take off the toolbar stays one tap away in the … menu.") {
        val layout = layouts.layout
        SettingsLinkRow(
            "Tools and order",
            "${layout.visible.size} on the toolbar" + if (layout.overflow.isNotEmpty()) " · ${layout.overflow.size} in the … menu" else "",
            onClick = { editTools = true },
        )
        SettingsDivider()
        SettingsSegmentedRow("Undo and redo", UndoButtons.entries.map { it to it.label }, options.undo,
            { p.write { putString(AppPrefs.TOOLBAR_UNDO, it.name) } })
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.TOOLBAR_TABS, true, "Document tabs", "Show open documents above the toolbar. Off shows a back button instead.")
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.TOOLBAR_TIMER, true, "Timer on the toolbar", "Show exam and Focal timers beside the tools when space allows.")
    }
    SettingsGroup("Opening a notebook") {
        // A starting tool chosen before this list was shortened (a shape, say) stays selectable.
        val tools = (listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASSO, Tool.TEXT, Tool.HAND, Tool.STICKY_NOTE) + defaultTool).distinct()
        SettingsChipRow("Starting tool", tools.map { it to toolLabel(it) }, defaultTool, { p.write { putString(AppPrefs.DEFAULT_TOOL, it.name) } })
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.UX_LOCAL_TOOLS, AppPrefs.DEFAULT_UX_LOCAL_TOOLS, "Remember tools per notebook", "Reopen each notebook with its last tool instead of the starting tool.")
    }
    SettingsGroup("Pages") {
        SettingsPrefSwitch(EditorQuickPrefs.PULL_TO_ADD_PAGE, true, "Pull past the end to add a page", "Scroll past the last page, then release to add a blank one.")
        SettingsDivider()
        SettingsSliderRow("Maximum zoom", percent(maxZoom), maxZoom, AppPrefs.MIN_MAX_ZOOM..AppPrefs.MAX_MAX_ZOOM,
            { p.write { putFloat(AppPrefs.MAX_ZOOM, AppPrefs.maxZoom(it.roundToInt().toFloat())) } }, steps = 6,
            onReset = if (maxZoom != AppPrefs.DEFAULT_MAX_ZOOM) ({ p.write { putFloat(AppPrefs.MAX_ZOOM, AppPrefs.DEFAULT_MAX_ZOOM) } }) else null)
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.UX_SNAP, AppPrefs.DEFAULT_UX_SNAP, "Snap moved selections", "Align moved selections to the grid and page margins. Hold Shift to move freely.")
    }
    SettingsGroup("Text", footer = "Sizes for new text boxes and sticky notes. Existing ones keep theirs.") {
        SettingsSliderRow(
            "Typed text size", "${textSize.roundToInt()} pt", textSize, AppPrefs.TEXT_SIZE_MIN..AppPrefs.TEXT_SIZE_MAX,
            { p.write { putFloat(AppPrefs.TEXT_SIZE_KEY, AppPrefs.textSize(it)) } },
            onReset = if (textSize != AppPrefs.DEFAULT_TEXT_SIZE) ({ p.write { putFloat(AppPrefs.TEXT_SIZE_KEY, AppPrefs.DEFAULT_TEXT_SIZE) } }) else null,
        )
        SettingsDivider()
        SettingsSliderRow(
            "Sticky note text size", "${stickyTextSize.roundToInt()} pt", stickyTextSize, StickyNotes.TEXT_SIZE_MIN..StickyNotes.TEXT_SIZE_MAX,
            { p.write { putFloat(AppPrefs.STICKY_TEXT_SIZE, AppPrefs.stickyTextSize(it)) } },
            onReset = if (stickyTextSize != AppPrefs.DEFAULT_STICKY_TEXT_SIZE) ({ p.write { putFloat(AppPrefs.STICKY_TEXT_SIZE, AppPrefs.DEFAULT_STICKY_TEXT_SIZE) } }) else null,
        )
    }
    SettingsGroup("Keyboard") {
        SettingsPrefSwitch(AppPrefs.UX_TOOL_KEYS, AppPrefs.DEFAULT_UX_TOOL_KEYS, "Tool letter shortcuts", "Use letter keys to switch tools while the canvas has focus.")
        SettingsDivider()
        ToolKeySettings()
    }
    SettingsGroup("Sharing") {
        SettingsChipRow("Share button long-press", ShareShortcut.entries.map { it to it.label }, shareShortcut,
            { p.write { putString(AppPrefs.SHARE_LONG_PRESS, it.name) } }, subtitle = "A tap still opens the export menu.")
    }
    if (editTools) ToolbarEditPanel(layouts, presets.presets, onDismiss = { editTools = false })
}

// ---- Writing follow -----------------------------------------------------------------------------

/** The follow defaults that are a matter of taste; the rest keep their tuned values. */
@Composable internal fun FollowPage() {
    val p = rememberPrefs()
    val f by rememberPref(p, *FollowPreferenceStore.keys, read = FollowPreferenceStore::read)
    val hand by rememberPref(p, "writingHand", read = FollowPreferenceStore::hand)
    val change: (FollowPreferences) -> Unit = { FollowPreferenceStore.write(p, it) }
    val text = f.mode == FollowMode.TEXT

    SettingsGroup("Writing follow", footer = "The view stays still while your pen is down; touch down during a glide to stop it. Infinite canvases only follow after you choose Write a response.") {
        SettingsPrefSwitch("writingFollow", false, "Follow on pages", "Move the view as you write on notebook and PDF pages.")
        SettingsDivider()
        SettingsSegmentedRow("What you write", listOf(FollowMode.TEXT to "Text", FollowMode.MATH to "Maths"), f.mode, { change(f.copy(mode = it)) },
            subtitle = if (text) "Follows sideways and down, then returns to the start of the next line."
            else "Reveals room below your working and keeps its horizontal position.")
        SettingsDivider()
        SettingsSegmentedRow("Writing direction", listOf(WritingDirection.LTR to "Left → right", WritingDirection.RTL to "Right → left"), f.direction, { change(f.copy(direction = it)) })
        SettingsDivider()
        SettingsSegmentedRow("Hand holding the pen", listOf(WritingHand.RIGHT to "Right", WritingHand.LEFT to "Left"), hand, { p.write { putString("writingHand", it.name) } })
    }
    SettingsGroup("Movement") {
        val feel = FollowFeel.of(f)
        SettingsChipRow<FollowFeel?>("Feel", FollowFeel.entries.map { it to it.label }, feel, { it?.apply(f)?.let(change) },
            subtitle = if (feel == null) "Custom. Pick one to replace it." else "How soon, how far and how fast the view moves.")
        SettingsDivider()
        // (sideways, down). Maths never moves sideways, so it only offers the choices that differ there.
        val moves = if (text) listOf((true to true) to "Sideways and down", (false to true) to "Down only",
            (true to false) to "Sideways only", (false to false) to "Only when I tap")
            else listOf((f.horizontalFollow to true) to "Down the page", (f.horizontalFollow to false) to "Only when I tap")
        SettingsChipRow("Move the view", moves, f.horizontalFollow to f.verticalFollow,
            { (sideways, down) -> change(f.copy(horizontalFollow = sideways, verticalFollow = down)) })
        SettingsDivider()
        SettingsBlock { FollowPreview(f, hand) }
        SettingsSliderRow("Writing height", "${f.positionPercent}% down", f.position, .35f.. .7f, { change(f.copy(position = it)) },
            subtitle = "Where your line sits on screen after the view moves.", lowLabel = "Higher", highLabel = "Lower")
    }
    SettingsGroup("Next line", footer = "Printed lines take priority over spacing.") {
        SettingsSwitchRow("Automatic line return", if (text) "Write across the line, then pause near the margin to return."
            else "Text only. In Maths, tap Next line.", f.automaticReturn, { change(f.copy(automaticReturn = it)) }, enabled = text)
        SettingsDivider()
        SettingsSwitchRow("Learn my line spacing", "Match your handwriting’s line spacing after two natural line breaks.", f.adaptiveSpacing, { change(f.copy(adaptiveSpacing = it)) }, enabled = text)
        SettingsDivider()
        SettingsSliderRow("Line spacing", "${"%.1f".format(f.spacingMm)} mm", f.spacingMm, 4f..24f,
            { change(f.copy(spacing = FollowPreferences.fromMm(it))) }, steps = 19, subtitle = "7 mm resembles ruled paper.", lowLabel = "Closer", highLabel = "Further apart")
    }
    SettingsGroup("Answer areas", footer = "Select, detect or clear the area on the current page from the editor's Answer areas menu.") {
        SettingsPrefSwitch("follow.autoDetectAnswerAreas", false, "Detect answer areas automatically", "Look for answer lines on the current PDF page while following is on.")
    }
    SettingsGroup {
        // Also the way back for anyone who tuned a control that no longer has a row here.
        SettingsLinkRow("Reset writing follow", "Restore every writing-follow default. Saved answer areas and pinned peek views are kept.", onClick = {
            FollowPreferenceStore.write(p, FollowPreferences())
            p.write {
                putBoolean("writingFollow", false)
                putString("writingHand", WritingHand.RIGHT.name)
                putBoolean("follow.autoDetectAnswerAreas", false)
                putBoolean("follow.showAnswerAreas", true)
                putBoolean(AppPrefs.AUTO_PEEK, false)
                putFloat(AppPrefs.ZOOM_PANE_HEIGHT, AppPrefs.DEFAULT_ZOOM_PANE_HEIGHT)
            }
        }, trailing = {})
    }
}

// ---- Library ------------------------------------------------------------------------------------

@Composable internal fun LibraryPage(folders: List<Folder>) {
    val p = rememberPrefs()
    val paper by rememberPref(p, AppPrefs.DEFAULT_PAPER) { AppPrefs.defaultPaper(it.getString(AppPrefs.DEFAULT_PAPER, null)) }
    val cover by rememberPref(p, AppPrefs.DEFAULT_COVER) { AppPrefs.defaultCover(it.getInt(AppPrefs.DEFAULT_COVER, AppPrefs.DEFAULT_COVER_INDEX).takeIf { _ -> it.contains(AppPrefs.DEFAULT_COVER) } ?: AppPrefs.DEFAULT_COVER_INDEX) }
    SettingsGroup("New notebooks") {
        SettingsChipRow("Paper", Paper.entries.map { it to paperLabel(it) }, paper, { p.write { putString(AppPrefs.DEFAULT_PAPER, it.name) } })
        SettingsDivider()
        SettingsBlock {
            SettingsBlockTitle("Cover design and colour")
            CoverPicker(cover, { p.write { putInt(AppPrefs.DEFAULT_COVER, AppPrefs.defaultCover(it)) } }, "Your next idea")
        }
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.DEFAULT_PAGE_COVER, AppPrefs.DEFAULT_PAGE_COVER_ENABLED, "First page as cover", "New notebooks show their first page on the shelf. Off uses the decorative cover.")
    }
    CustomCoverGroup()
    val quickRaw by rememberPref(p, AppPrefs.QUICK_FOLDER) { it.getString(AppPrefs.QUICK_FOLDER, null) }
    val quickFolder = AppPrefs.quickFolderId(quickRaw, folders)
    SettingsGroup("Quick notes and canvases", footer = "Where the Quick note and Canvas buttons save new notebooks.") {
        SettingsRadioGroup {
            SettingsRadioRow("Folder you have open", "Or the top level of the shelf", quickFolder == null) { p.write { remove(AppPrefs.QUICK_FOLDER) } }
            folders.map { it to LibraryFolders.label(folders, it.id) }.sortedBy { it.second }.forEach { (folder, label) ->
                SettingsRadioRow(label, null, quickFolder == folder.id) { p.write { putString(AppPrefs.QUICK_FOLDER, folder.id) } }
            }
        }
    }
    SettingsGroup("Notebook list swipes") {
        for ((title, key, fallback) in listOf(Triple("Swipe right", AppPrefs.UX_SWIPE_RIGHT, "FAVOURITE"), Triple("Swipe left", AppPrefs.UX_SWIPE_LEFT, "TRASH"))) {
            val selected by rememberPref(p, key) { AppPrefs.uxSwipe(it.getString(key, null), fallback) }
            SettingsChipRow(title, listOf("FAVOURITE", "TRASH", "TAGS", "MOVE", "SHARE").map { it to it.lowercase().replaceFirstChar(Char::uppercase) }, selected,
                { p.write { putString(key, it) } })
        }
    }
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
                SettingsBlockTitle("Suggested colours", "Tap to add one to your cover colours.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    suggestions.forEach { color -> ColorSwatch(color, false, "Add cover colour ${AccentTones.hex(color)}", { palette.add(color) }) }
                }
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
    else -> paper.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

// ---- Backup & restore ---------------------------------------------------------------------------

@Composable internal fun BackupPage(b: BackupSettings) {
    val auto = b.tree != null
    SettingsGroup(
        "Automatic backup",
        footer = "After saved changes and once a day, Folio copies only what changed and keeps the latest two restore points.",
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
        SettingsDivider()
        SettingsLinkRow("Restore from backup folder", "Choose the same folder on any device to restore its latest backup.", onClick = b.onRestoreFromFolder, enabled = !b.busy)
    }
    SettingsGroup("Library file", footer = "A self-contained file for sharing or moving devices. Restoring adds copies beside your current notebooks.") {
        SettingsLinkRow("Save library backup", onClick = b.onSaveLibrary, enabled = !b.busy)
        SettingsDivider()
        SettingsLinkRow("Restore library backup", onClick = b.onRestoreLibrary, enabled = !b.busy)
    }
    SettingsGroup("Exclusions", footer = "Leave textbooks or other notebooks out of automatic and library backups. Exporting a single notebook still includes everything.") {
        SettingsLinkRow("Excluded notebooks", if (b.excludedCount == 0) "None" else "${b.excludedCount} left out", onClick = b.onExclusions, enabled = !b.busy)
    }
}

// ---- Study --------------------------------------------------------------------------------------

@Composable internal fun StudyPage() {
    val p = rememberPrefs()
    val customMinutes by rememberPref(p, AppPrefs.TIMER_CUSTOM_MIN) { AppPrefs.timerCustomMinutes(it.getInt(AppPrefs.TIMER_CUSTOM_MIN, AppPrefs.DEFAULT_TIMER_CUSTOM_MIN).takeIf { _ -> it.contains(AppPrefs.TIMER_CUSTOM_MIN) }) }
    val reading by rememberPref(p, AppPrefs.TIMER_READING_MIN) { AppPrefs.timerReadingMinutes(it.getInt(AppPrefs.TIMER_READING_MIN, AppPrefs.DEFAULT_TIMER_READING_MIN).takeIf { _ -> it.contains(AppPrefs.TIMER_READING_MIN) }) }
    val idle by rememberPref(p, AppPrefs.TIMER_IDLE_MIN) { AppPrefs.timerIdleMinutes(it.getInt(AppPrefs.TIMER_IDLE_MIN, AppPrefs.DEFAULT_TIMER_IDLE_MIN).takeIf { _ -> it.contains(AppPrefs.TIMER_IDLE_MIN) }) }
    val paper by rememberPref(p, AppPrefs.MISTAKE_PAPER) { AppPrefs.mistakePaper(it.getString(AppPrefs.MISTAKE_PAPER, null)) }
    val zoom by rememberPref(p, AppPrefs.MISTAKE_ZOOM) { AppPrefs.mistakeZoom(it.getFloat(AppPrefs.MISTAKE_ZOOM, AppPrefs.DEFAULT_MISTAKE_ZOOM)) }

    SettingsGroup("Exam timer", footer = "The Exam 1 (90) and Exam 2 (120) presets are fixed; these set the Custom timer.") {
        // Five-minute steps; an exact length can still be typed in the timer panel.
        SettingsSliderRow(
            "Custom timer", "$customMinutes min", customMinutes.toFloat(), 5f..AppPrefs.TIMER_CUSTOM_MAX.toFloat(),
            { p.write { putInt(AppPrefs.TIMER_CUSTOM_MIN, (it / 5).roundToInt() * 5) } }, steps = AppPrefs.TIMER_CUSTOM_MAX / 5 - 2,
            onReset = if (customMinutes != AppPrefs.DEFAULT_TIMER_CUSTOM_MIN) ({ p.write { putInt(AppPrefs.TIMER_CUSTOM_MIN, AppPrefs.DEFAULT_TIMER_CUSTOM_MIN) } }) else null,
        )
        SettingsDivider()
        SettingsSliderRow(
            "Reading time", "$reading min", reading.toFloat(), AppPrefs.TIMER_READING_MIN_RANGE.toFloat()..AppPrefs.TIMER_READING_MAX.toFloat(),
            { p.write { putInt(AppPrefs.TIMER_READING_MIN, it.roundToInt()) } }, steps = AppPrefs.TIMER_READING_MAX - AppPrefs.TIMER_READING_MIN_RANGE - 1,
            onReset = if (reading != AppPrefs.DEFAULT_TIMER_READING_MIN) ({ p.write { putInt(AppPrefs.TIMER_READING_MIN, AppPrefs.DEFAULT_TIMER_READING_MIN) } }) else null,
        )
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.TIMER_AUTO_START, AppPrefs.DEFAULT_TIMER_AUTO_START, "Resume on pen down", "A paused exam clock resumes on the next pen stroke. The pen never starts a new sitting.")
        SettingsDivider()
        SettingsSliderRow(
            "Stop after inactivity", if (idle <= 0) "Never" else "$idle min", idle.toFloat(), AppPrefs.TIMER_IDLE_MIN_RANGE.toFloat()..AppPrefs.TIMER_IDLE_MAX.toFloat(),
            { p.write { putInt(AppPrefs.TIMER_IDLE_MIN, it.roundToInt()) } }, steps = AppPrefs.TIMER_IDLE_MAX - AppPrefs.TIMER_IDLE_MIN_RANGE - 1,
            subtitle = "The clock stops once the pen has been idle this long; your next stroke resumes it.",
            onReset = if (idle != AppPrefs.DEFAULT_TIMER_IDLE_MIN) ({ p.write { putInt(AppPrefs.TIMER_IDLE_MIN, AppPrefs.DEFAULT_TIMER_IDLE_MIN) } }) else null,
        )
    }
    SettingsGroup("Mistake practice", footer = "Paper and zoom apply to the next question you practise. Pages you have already written keep theirs.") {
        SettingsChipRow("Paper guide", Paper.entries.map { it to paperLabel(it) }, paper, { p.write { putString(AppPrefs.MISTAKE_PAPER, it.name) } })
        SettingsDivider()
        SettingsSliderRow("Starting zoom", percent(zoom), zoom, .5f..3f,
            { p.write { putFloat(AppPrefs.MISTAKE_ZOOM, AppPrefs.mistakeZoom(it)) } }, steps = 24,
            onReset = if (zoom != AppPrefs.DEFAULT_MISTAKE_ZOOM) ({ p.write { putFloat(AppPrefs.MISTAKE_ZOOM, AppPrefs.DEFAULT_MISTAKE_ZOOM) } }) else null)
        SettingsDivider()
        SettingsPrefSwitch(AppPrefs.UX_REVIEW_CONFIRM, AppPrefs.DEFAULT_UX_REVIEW_CONFIRM, "Confirm answer reveal and skip", "Turn off for single-tap review.")
    }
}

// ---- Account & updates --------------------------------------------------------------------------

@Composable internal fun AccountPage(a: AccountSettings) {
    val prefs = rememberPrefs()
    val experimental by rememberPref(prefs, AppPrefs.EXPERIMENTAL_UPDATES) {
        it.getBoolean(AppPrefs.EXPERIMENTAL_UPDATES, AppPrefs.DEFAULT_EXPERIMENTAL_UPDATES)
    }
    SettingsGroup("Accounts") {
        SettingsLinkRow("Focal account", "One account for study sessions and mistake sync.", onClick = a.onFocal)
        SettingsDivider()
        SettingsLinkRow("ChatGPT", "Review your working and keep feedback as sticky notes.", onClick = a.onChatGpt)
    }
    SettingsGroup("Updates") {
        SettingsPrefSwitch(AppPrefs.AUTO_UPDATE, AppPrefs.DEFAULT_AUTO_UPDATE, "Check on launch", "Look for a newer signed version when Folio opens.")
        SettingsDivider()
        SettingsSwitchRow("Experimental builds", "Get earlier, less stable builds from folio.flandolf.me.",
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
