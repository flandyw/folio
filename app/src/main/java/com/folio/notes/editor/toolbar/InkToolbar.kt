@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes.editor.toolbar

import com.folio.notes.ui.FolioIcons
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.folio.notes.input.guardUiTouches
import com.folio.notes.input.longPressAction
import com.folio.notes.input.rememberLongPressGuard
import com.folio.notes.library.semanticsLabel
import com.folio.notes.model.DrawingTools
import com.folio.notes.model.ShapePickerTools
import com.folio.notes.model.StrokeStyle
import com.folio.notes.model.Tool
import com.folio.notes.settings.largeEditorControls
import com.folio.notes.settings.showEditorToolNames
import com.folio.notes.ui.FolioExpand
import com.folio.notes.ui.FolioMenuItem
import com.folio.notes.ui.FolioMenuPopover
import com.folio.notes.ui.FolioPanel
import com.folio.notes.ui.FolioPopover
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.FolioToolToggle
import com.folio.notes.ui.MenuSectionHeader
import com.folio.notes.ui.SubmenuItem
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What one toolbar slot wears. [hint] is the rest of its tooltip; a slot with [options] opens the
 * tool settings when its active tool is tapped again or held.
 */
private class SlotSpec(val icon: ImageVector, val label: String, val hint: String = "", val options: Boolean = true)

private fun spec(slot: ToolbarSlot): SlotSpec = when (slot) {
    ToolbarSlot.PEN -> SlotSpec(FolioIcons.Pen, "Pen")
    ToolbarSlot.SHAPES -> SlotSpec(FolioIcons.Rectangle, "Shapes")
    ToolbarSlot.HIGHLIGHTER -> SlotSpec(FolioIcons.Highlighter, "Highlighter")
    ToolbarSlot.ERASER -> SlotSpec(FolioIcons.Eraser, "Eraser")
    ToolbarSlot.TEXT -> SlotSpec(FolioIcons.Text, "Text")
    ToolbarSlot.LASSO -> SlotSpec(FolioIcons.Lasso, "Lasso select")
    ToolbarSlot.HAND -> SlotSpec(FolioIcons.Hand, "Hand", " — follow links, move pictures, scroll and zoom")
    ToolbarSlot.STICKY_NOTE -> SlotSpec(FolioIcons.Sticky, "Sticky note", " — drag a rectangle, then type or draw", options = false)
    ToolbarSlot.MARK_AREA -> SlotSpec(FolioIcons.MarkArea, "Mark area", " — box a “[n marks]” label the scan missed", options = false)
    ToolbarSlot.LASER -> SlotSpec(FolioIcons.Laser, "Laser pointer", " — point and circle without writing")
    ToolbarSlot.RULER -> SlotSpec(FolioIcons.Ruler, "Ruler", " — draw straight along its edge; one finger moves it, two turn it", options = false)
}

private fun shapeIcon(tool: Tool): ImageVector = when (tool) {
    Tool.LINE -> FolioIcons.Line
    Tool.ELLIPSE -> FolioIcons.Ellipse
    Tool.TRIANGLE -> FolioIcons.Triangle
    Tool.DIAMOND -> FolioIcons.Diamond
    Tool.PENTAGON -> FolioIcons.Pentagon
    Tool.HEXAGON -> FolioIcons.Hexagon
    Tool.STAR -> FolioIcons.Star
    Tool.GRAPH -> FolioIcons.Graph
    else -> FolioIcons.Rectangle
}

private fun shapeLabel(tool: Tool) = when (tool) {
    Tool.LINE -> "Straight line"
    Tool.GRAPH -> "Graph axes"
    else -> tool.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/** A tool's name for people, never its enum constant (no "Mark_area"). */
internal fun toolLabel(tool: Tool): String =
    if (tool in ShapePickerTools) shapeLabel(tool) else spec(ToolbarSlot.entries.first { tool in it.tools }).label

/** The glyph a tool wears on the toolbar. */
internal fun toolIcon(tool: Tool): ImageVector =
    if (tool in ShapePickerTools) shapeIcon(tool) else spec(ToolbarSlot.entries.first { tool in it.tools }).icon

/** Space a divider keeps from the tool tray beside it, so a selected tile never touches it. */
private val DividerAir = 4.dp

/** The strip's button edge; one more 2dp gap makes the pitch the capacity budget divides by. */
private val ToolbarButton: Dp @Composable get() = if (largeEditorControls()) 48.dp else 36.dp
private val ToolbarSlotPitch: Dp @Composable get() = (if (showEditorToolNames()) 76.dp else ToolbarButton) + 2.dp

/** The second-level menus under the toolbar's … menu. */
private enum class ToolSub { PRESETS }

@Composable private fun StripDivider(modifier: Modifier = Modifier, height: Dp = 24.dp) {
    Box(modifier.width(1.dp).height(height).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
}

/** A plain strip button with its tooltip; tools use [FolioToolToggle] instead. */
@Composable private fun StripButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(ToolbarButton), shapes = IconButtonDefaults.shapes()) { Icon(icon, label, Modifier.size(22.dp)) }
    }
}

/**
 * The ink tool strip: the tools, their quick colours, width and options, saved presets and the
 * overflow menu. It started life inside the editor and is shared with the music reader so a score
 * wears the same tools as a page. Callers supply the header above the strip and receive its
 * measured height, so page content can clear it.
 *
 * [hiddenSlots] removes tools from the strip and the overflow entirely, for a surface where they
 * mean nothing — the reader drops [ToolbarSlot.STICKY_NOTE] because a score has no space beside its
 * page. That is the only way a tool leaves: what the user hides from the strip waits in the … menu.
 * A null [onRuler] removes the ruler switch; [rulerOn] is its state.
 * [shapeTools] is the shape picker's own list, so a caller can drop [Tool.GRAPH] (a notebook's
 * paper tool rather than an annotation). How each tool behaves is set on its own settings page.
 */
@Composable internal fun FloatingInkToolbar(
    onMainHeight: (Int) -> Unit = {},
    /** Height of everything the toolbar shows, quick bar included, for callers whose page must never sit under it. */
    onFullHeight: (Int) -> Unit = {},
    modifier: Modifier, tool: Tool, onTool: (Tool) -> Unit, options: ToolOptions, onOptions: (ToolOptions) -> Unit, quick: QuickColorsState,
    canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit, palette: Boolean, onPalette: (Boolean) -> Unit,
    markAreaAvailable: Boolean = false,
    textColor: Int = 0, onTextColor: ((Int) -> Unit)? = null,
    presets: List<ToolPreset> = emptyList(), onApplyPreset: ((ToolPreset) -> Unit)? = null,
    toolPresetsState: ToolPresetState? = null,
    toolbarLayoutState: ToolbarLayoutState? = null,
    hiddenSlots: Set<ToolbarSlot> = emptySet(),
    rulerOn: Boolean = false, onRuler: (() -> Unit)? = null,
    shapeTools: List<Tool> = ShapePickerTools.toList(),
    /** Flat on the editor bar; false floats the tools on their own pill (the music reader). */
    flat: Boolean = true,
    toolbarOptions: ToolbarOptions = ToolbarOptions(),
    header: @Composable (@Composable () -> Unit) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var shapePicker by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf<String?>(null) }
    var editToolbar by remember { mutableStateOf(false) }
    var quickBarOpen by rememberSaveable { mutableStateOf(toolbarOptions.inkOptionsOpen) }
    // The strip's own long-press opens Edit toolbar. A tool or pinned preset claims the
    // gesture first (and cancels the strip menu again if the strip handler ran first), so
    // a hold over a button only ever opens that button's own action.
    var childLongPressAt by remember { mutableLongStateOf(0L) }
    val stripGuard = rememberLongPressGuard()
    fun claimStripLongPress() {
        childLongPressAt = System.currentTimeMillis()
        editToolbar = false
        // The child's own hold stands alone; leave no strip claim to swallow a later tap.
        stripGuard.begin()
    }
    // Boxing a missed allocation only means something on an imported PDF, so elsewhere the tool stays out of the way.
    val toolbarLayout = (toolbarLayoutState?.layout ?: ToolbarLayouts.default()).without(
        hiddenSlots + setOfNotNull(ToolbarSlot.MARK_AREA.takeIf { !markAreaAvailable }, ToolbarSlot.RULER.takeIf { onRuler == null }))
    val pinnedPresets = remember(presets, toolbarLayout.pinnedPresetIds, toolbarOptions.pinnedPresets) {
        if (!toolbarOptions.pinnedPresets) emptyList()
        else toolbarLayout.pinnedPresetIds.mapNotNull { id -> presets.find { it.id == id } }
    }
    val context = LocalContext.current
    val toolPrefs = remember(context) { context.getSharedPreferences("ink-tools", 0) }
    val isShape = tool in ShapePickerTools
    val isDrawing = tool in DrawingTools
    // Text quick controls change the colour used for new text boxes.
    val showQuickBar = quickBarOpen && (isDrawing || tool == Tool.ERASER || (tool == Tool.TEXT && onTextColor != null))
    val feedback = LocalHapticFeedback.current
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    /** A light tick on real tool changes; tapping the active tool stays silent. */
    fun pick(next: Tool) {
        if (next != tool) tick()
        onTool(next)
    }
    var recentShapes by remember { mutableStateOf(ShapeRecents.decode(toolPrefs.getString(ShapeRecents.KEY, null))) }
    val lastShape = recentShapes.first()
    fun chooseShape(value: Tool) {
        recentShapes = ShapeRecents.push(recentShapes, value)
        toolPrefs.edit().putString(ShapeRecents.KEY, ShapeRecents.encode(recentShapes)).apply()
        pick(value)
    }
    // The highlighter has its own quick colours and presets; other ink tools share them.
    val colorGroup = InkColors.groupOf(tool)
    // Keep the pen’s saved brush visible while another tool is active.
    val penBrush = if (tool == Tool.PEN) options.style else StrokeStyle.safeValueOf(toolPrefs.getString("PEN.style", null) ?: "SOLID")
    val widthPresetState = remember(toolPrefs) { WidthPresetState(toolPrefs) }
    var colorSlotEditing by remember { mutableStateOf<Int?>(null) }
    var widthSlotEditing by remember { mutableStateOf<Int?>(null) }
    var brushPicker by remember { mutableStateOf(false) }
    // The popover opens under whichever control asked for it: the quick bar's settings icon, or the tool's own button.
    var paletteFromQuickBar by remember { mutableStateOf(false) }
    LaunchedEffect(palette) { if (!palette) paletteFromQuickBar = false }
    @Composable fun ToolSettingsPopover(fromQuickBar: Boolean = false) {
        if (palette && paletteFromQuickBar == fromQuickBar) FolioPopover(onDismiss = { onPalette(false) }, width = 344.dp) {
            ToolOptionsPanel(tool, options, onOptions, quick, toolPresetsState, onApplyPreset)
        }
    }
    @Composable fun ShapePicker() {
        if (shapePicker) ShapePickerPopover(tool, shapes = shapeTools, onPick = { chooseShape(it); shapePicker = false },
            onDrawHold = { pick(Tool.PEN); shapePicker = false }, onDismiss = { shapePicker = false })
    }

    @Composable fun SlotButton(slot: ToolbarSlot) {
        val active = tool in slot.tools
        val spec = spec(slot)
        Box(contentAlignment = Alignment.Center) {
            if (active) ToolSettingsPopover()
            when (slot) {
                ToolbarSlot.RULER -> FolioToolToggle(rulerOn, { tick(); onRuler?.invoke() }, spec.icon, if (rulerOn) "Hide ruler" else spec.label + spec.hint)
                ToolbarSlot.SHAPES -> {
                    val shape = if (isShape) tool else lastShape
                    // Tapping the shape in hand chooses another; holding opens its line settings.
                    FolioToolToggle(isShape, { onPalette(false); if (isShape) shapePicker = true else chooseShape(lastShape) }, shapeIcon(shape),
                        "Shapes, ${shapeLabel(shape).lowercase()}" + if (isShape) " — tap again to choose shape" else "",
                        onLongClick = { claimStripLongPress(); if (!isShape) pick(lastShape); onPalette(true) })
                    Icon(FolioIcons.ChevronDown, null, Modifier.align(Alignment.BottomEnd).size(10.dp),
                        tint = if (isShape) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    ShapePicker()
                }
                else -> {
                    val value = slot.tools.first()
                    FolioToolToggle(active, { if (active && spec.options) onPalette(true) else pick(value) },
                        if (slot == ToolbarSlot.PEN) brushIcon(Tool.PEN, penBrush) else spec.icon,
                        if (slot == ToolbarSlot.PEN) "Pen, ${penBrush.brushLabel(Tool.PEN).lowercase()}" else spec.label + spec.hint,
                        onLongClick = { claimStripLongPress(); pick(value); if (spec.options) onPalette(true) })
                }
            }
        }
    }

    @Composable fun PinnedPreset(preset: ToolPreset) = Box {
        val active = tool == preset.tool && options.color == preset.color && options.width == preset.width &&
            options.opacity == preset.opacity && options.style == preset.style
        FilterChip(
            selected = active,
            onClick = stripGuard.click { tick(); onApplyPreset?.invoke(preset) },
            label = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
            leadingIcon = { Box(Modifier.size(12.dp).background(Color(preset.color), CircleShape)) },
            modifier = Modifier.widthIn(max = 104.dp).height(28.dp).longPressAction(stripGuard) { claimStripLongPress(); presetMenu = preset.id }
        )
        FolioMenuPopover(presetMenu == preset.id, { presetMenu = null }, modifier = Modifier.guardUiTouches(), title = "Pinned preset") {
            FolioMenuItem({ Text("Unpin “${preset.name}” from toolbar") }, { presetMenu = null; toolbarLayoutState?.togglePin(preset.id) }, leadingIcon = { Icon(FolioIcons.Close, null) })
            FolioMenuItem({ Text("Tool settings") }, { presetMenu = null; onPalette(true) }, leadingIcon = { Icon(FolioIcons.Tune, null) })
        }
    }

    /** Everything the strip has no room for, and the settings that are changed once and left. */
    @Composable fun OverflowMenu(showUndo: Boolean, showRedo: Boolean, overflow: List<ToolbarSlot>) {
        var sub by remember { mutableStateOf<ToolSub?>(null) }
        val openSub: (ToolSub) -> Unit = { sub = if (sub == it) null else it }
        fun close() { menu = false; sub = null }
        FolioMenuPopover(menu, ::close, modifier = Modifier.guardUiTouches(), title = "Tools & actions") {
            if (!showUndo) FolioMenuItem({ Text("Undo") }, { undo(); close() }, enabled = canUndo, leadingIcon = { Icon(FolioIcons.Undo, null) })
            if (!showRedo) FolioMenuItem({ Text("Redo") }, { redo(); close() }, enabled = canRedo, leadingIcon = { Icon(FolioIcons.Redo, null) })
            if (overflow.isNotEmpty()) MenuSectionHeader("Tools")
            overflow.forEach { slot ->
                val spec = spec(slot)
                when (slot) {
                    ToolbarSlot.SHAPES -> FolioMenuItem({ Text(spec.label) }, { close(); onPalette(false); shapePicker = true },
                        leadingIcon = { Icon(shapeIcon(if (isShape) tool else lastShape), null) }, trailingIcon = { Icon(FolioIcons.ChevronRight, null) })
                    ToolbarSlot.RULER -> FolioMenuItem({ Text(spec.label) }, { onRuler?.invoke(); close() }, leadingIcon = { Icon(spec.icon, null) }, selected = rulerOn)
                    else -> FolioMenuItem({ Text(spec.label) }, { pick(slot.tools.first()); close() }, leadingIcon = { Icon(spec.icon, null) }, selected = tool in slot.tools)
                }
            }
            if (overflow.isNotEmpty()) HorizontalDivider()
            if (presets.isNotEmpty() && onApplyPreset != null) SubmenuItem("Presets…", FolioIcons.Bookmark, sub == ToolSub.PRESETS, { openSub(ToolSub.PRESETS) }) {
                MenuSectionHeader("Presets")
                presets.forEach { preset ->
                    FolioMenuItem({ Text("${preset.name} · ${toolLabel(preset.tool).lowercase()}") }, { onApplyPreset(preset); close() },
                        leadingIcon = { Box(Modifier.size(14.dp).background(Color(preset.color), CircleShape)) })
                }
            }
            if (toolbarLayoutState != null) FolioMenuItem({ Text("Edit toolbar") }, { close(); editToolbar = true }, leadingIcon = { Icon(FolioIcons.Reorder, null) })
        }
    }

    @Composable fun RowScope.Strip(showUndo: Boolean, showRedo: Boolean, primary: List<ToolbarSlot>, showPresets: Boolean) {
        // The … menu holds what the user put there plus whatever this pane is too narrow to show.
        val overflow = toolbarLayout.order.filterNot { it in primary }
        val hasTray = primary.isNotEmpty() || (showPresets && pinnedPresets.isNotEmpty())
        if (showUndo) StripButton(FolioIcons.Undo, "Undo", canUndo, stripGuard.click(undo))
        if (showRedo) StripButton(FolioIcons.Redo, "Redo", canRedo, stripGuard.click(redo))
        if (showUndo && hasTray) StripDivider(Modifier.padding(start = DividerAir / 2, end = DividerAir))
        if (hasTray) {
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                primary.forEach { slot -> key(slot) { SlotButton(slot) } }
                if (showPresets) pinnedPresets.forEach { preset -> key(preset.id) { PinnedPreset(preset) } }
            }
            StripDivider(Modifier.padding(start = DividerAir, end = DividerAir / 2))
        }
        Box {
            // A tool that lives in the menu still opens its settings and shape picker somewhere.
            if (primary.none { tool in it.tools }) ToolSettingsPopover()
            if (ToolbarSlot.SHAPES in overflow) ShapePicker()
            Row(verticalAlignment = Alignment.CenterVertically) {
                StripButton(FolioIcons.More, "More tools and actions", onClick = stripGuard.click { menu = true })
                StripButton(if (quickBarOpen) FolioIcons.ChevronUp else FolioIcons.ChevronDown, if (quickBarOpen) "Hide ink options" else "Show ink options",
                    onClick = stripGuard.click { quickBarOpen = !quickBarOpen })
            }
            OverflowMenu(showUndo, showRedo, overflow)
        }
    }

    @Composable fun QuickColors() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2), modifier = Modifier.padding(horizontal = FolioSpacing.dp2)) {
            quick.colors(colorGroup).forEachIndexed { index, c ->
                // Tapping the colour in use, or holding any dot, edits that slot in a popover.
                Box {
                    InkColorDot(c, options.color == c, {
                        tick()
                        if (options.color == c) colorSlotEditing = index else onOptions(options.copy(color = c))
                    }, touch = ToolbarButton - 4.dp, dot = 22.dp, label = "Quick colour ${index + 1}", onLongClick = { colorSlotEditing = index })
                    if (colorSlotEditing == index) ColorSlotPopover(
                        group = colorGroup, slot = index, color = c,
                        onPick = { picked -> quick.setSlot(colorGroup, index, picked); onOptions(options.copy(color = picked)) },
                        onReset = {
                            val original = InkColors.defaultQuick(colorGroup)[index]
                            quick.setSlot(colorGroup, index, original); onOptions(options.copy(color = original))
                        },
                        onDismiss = { colorSlotEditing = null }
                    )
                }
            }
        }
    }
    @Composable fun RecentShapes() {
        recentShapes.forEach { shape ->
            val selected = shape == tool
            IconButton({ chooseShape(shape) }, modifier = Modifier.size(ToolbarButton).semanticsLabel("${shapeLabel(shape)}${if (selected) ", selected" else ""}"),
                colors = IconButtonDefaults.iconButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)) {
                Icon(shapeIcon(shape), null, Modifier.size(20.dp))
            }
        }
    }
    /** The three widths as dots that grow with the stored width, so a customised slot still reads in order. */
    @Composable fun WidthDots() {
        val group = WidthPresets.group(tool, options.style)
        val range = WidthPresets.range(group)
        val widths = widthPresetState.widths(tool, options.style)
        val selectedIndex = WidthPresets.selectedIndex(widths, options.width)
        widths.forEachIndexed { index, w ->
            val selected = index == selectedIndex
            // Tapping the width in use, or holding any dot, edits that slot in a popover.
            Box {
                Box(
                    Modifier.size(ToolbarButton - 4.dp).clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .combinedClickable(
                            role = Role.RadioButton,
                            onClick = { tick(); if (selected) widthSlotEditing = index else onOptions(options.copy(width = w)) },
                            onLongClick = { feedback.performHapticFeedback(HapticFeedbackType.LongPress); widthSlotEditing = index }
                        )
                        .semanticsLabel("Width ${String.format(java.util.Locale.ROOT, "%.1f", w)}${if (selected) ", selected" else ""}"),
                    contentAlignment = Alignment.Center
                ) {
                    val size = (4f + 12f * sqrt(((w - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f))).dp
                    Box(Modifier.size(size).background(if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                }
                if (widthSlotEditing == index) WidthSlotPopover(
                    tool = tool, slot = index, width = if (selected) options.width else w, options = options,
                    onPick = { picked -> widthPresetState.set(tool, index, picked, options.style); onOptions(options.copy(width = picked)) },
                    onReset = {
                        widthPresetState.reset(tool, index, options.style)
                        onOptions(options.copy(width = WidthPresets.defaults(group)[index]))
                    },
                    onDismiss = { widthSlotEditing = null }
                )
            }
        }
    }
    /** The brush in use; tapping opens the brush cards, each drawn in the brush's own ink. */
    @Composable fun BrushSwitch() = Box {
        IconButton({ brushPicker = !brushPicker }, modifier = Modifier.size(ToolbarButton).semanticsLabel("Brush, ${options.style.brushLabel(tool).lowercase()}")) {
            Icon(brushIcon(tool, options.style), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (brushPicker) FolioPopover(onDismiss = { brushPicker = false }, width = 344.dp) {
            BrushPicker(tool, options) { brush -> tick(); onOptions(options.withBrush(tool, brush, widthPresetState)); brushPicker = false }
        }
    }
    /** The quick bar under the strip: brush, widths and colours for the tool in hand, and its full settings. */
    @Composable fun QuickBar() = BoxWithConstraints {
        // In a narrow pane, widths/shapes and colours get their own row so neither
        // group disappears behind the other. Each row can still scroll in tiny panes.
        val splitGroups = tool != Tool.TEXT && tool != Tool.ERASER && maxWidth < 440.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            EditorGlassSurface(Modifier.widthIn(max = 640.dp)) {
                Row(Modifier.padding(horizontal = FolioSpacing.dp4).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp4).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                        if (tool == Tool.TEXT && onTextColor != null) {
                            quick.colors(colorGroup).forEachIndexed { index, c ->
                                InkColorDot(c, textColor == c, { tick(); onTextColor(c) }, touch = ToolbarButton - 4.dp, dot = 22.dp, label = "Text colour ${index + 1}", onLongClick = { onPalette(true) })
                            }
                            StripDivider(height = 18.dp)
                            Text("Text colour", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        } else {
                            if (StrokeStyle.brushes(tool).isNotEmpty()) BrushSwitch()
                            if (isShape) RecentShapes() else WidthDots()
                            if (tool != Tool.ERASER && !splitGroups) { StripDivider(height = 18.dp); QuickColors() }
                        }
                    }
                    // Settings stay reachable even when the colours and widths need scrolling: width,
                    // opacity, colour and the tool's own options all live in that one popover.
                    Box {
                        IconButton({ paletteFromQuickBar = !palette; onPalette(!palette) }, modifier = Modifier.size(ToolbarButton)) {
                            Icon(FolioIcons.Tune, "Tool settings", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ToolSettingsPopover(fromQuickBar = true)
                    }
                }
            }
            if (splitGroups) EditorGlassSurface {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp6), verticalAlignment = Alignment.CenterVertically) { QuickColors() }
            }
        }
    }

    // The header hosts the main tools; quick controls remain directly beneath them.
    Column(modifier.guardUiTouches().fillMaxWidth().onSizeChanged { onFullHeight(it.height) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
        // Only the header and main strip reserve space; the quick bar floats over the page so
        // toggling it never moves the document.
        Box(Modifier.onSizeChanged { onMainHeight(it.height) }) {
            header {
                BoxWithConstraints {
                    // Undo and redo follow Settings, and narrow panes drop redo, then undo, into the … menu.
                    val showUndo = toolbarOptions.undo != UndoButtons.NONE && maxWidth >= 192.dp
                    val showRedo = showUndo && toolbarOptions.undo == UndoButtons.BOTH && maxWidth >= 360.dp
                    // 36dp buttons, 2dp gaps, two 1dp dividers with their air and 8dp outer padding.
                    // Budget the fixed buttons first, then whole tool slots; presets never crowd those slots out.
                    val fixedWidth = 85.dp + DividerAir * 3 / 2 +
                        (if (showUndo) 41.dp + DividerAir * 3 / 2 else 0.dp) + (if (showRedo) 38.dp else 0.dp)
                    val capacity = ((maxWidth - fixedWidth) / ToolbarSlotPitch).toInt().coerceIn(0, toolbarLayout.visible.size)
                    // Tools yield slots to the menu as the pane narrows, but the tool in hand keeps a place on the strip.
                    val primary = toolbarLayout.visible.take(capacity).toMutableList()
                    val activeSlot = toolbarLayout.visible.firstOrNull { tool in it.tools }
                    if (activeSlot != null && capacity > 0 && activeSlot !in primary) {
                        primary[primary.lastIndex] = activeSlot
                        primary.sortBy { toolbarLayout.order.indexOf(it) }
                    }
                    val showPresets = primary.size == toolbarLayout.visible.size &&
                        maxWidth >= fixedWidth + ToolbarSlotPitch * primary.size + 104.dp * pinnedPresets.size
                    val stripHold = Modifier.longPressAction(stripGuard) {
                        // A tool or preset that claimed the gesture first keeps it; its own action stands alone.
                        if (System.currentTimeMillis() - childLongPressAt <= 400) stripGuard.begin()
                        else if (toolbarLayoutState != null) editToolbar = true
                    }
                    val strip: @Composable () -> Unit = {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp4).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                            Strip(showUndo, showRedo, primary, showPresets)
                        }
                    }
                    // The editor bar already is the surface, so its tools sit on it without a pill.
                    if (flat) Box(stripHold.height(EditorFloatingGroupHeight), contentAlignment = Alignment.Center) { strip() }
                    else EditorGlassSurface(stripHold) { strip() }
                }
            }
        }
        FolioExpand(showQuickBar) { QuickBar() }
    }
    if (editToolbar && toolbarLayoutState != null) ToolbarEditPanel(toolbarLayoutState, presets) { editToolbar = false }
}

/**
 * Reorders toolbar tools, moves them between the strip and the … menu, and
 * pins saved presets beside them. Every tool is in one place or the other;
 * nothing is ever forced into the menu. Rows drag by their handle (long-press)
 * and also move with arrows so the sheet stays usable without fine motor control.
 */
@Composable internal fun ToolbarEditPanel(layoutState: ToolbarLayoutState, presets: List<ToolPreset>, onDismiss: () -> Unit) {
    val layout = layoutState.layout
    val rowHeight = 56.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragFrom by remember(layout.order) { mutableStateOf<Int?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    @Composable fun Move(up: Boolean, name: String, enabled: Boolean, onClick: () -> Unit) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
            Icon(if (up) FolioIcons.Up else FolioIcons.Down, "Move $name ${if (up) "up" else "down"}", Modifier.size(18.dp))
        }
    }
    FolioPanel(title = "Edit toolbar", onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = FolioSpacing.dp24).padding(bottom = FolioSpacing.dp24),
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)
        ) {
            Text("Also in a notebook: long-press the tool strip. A tool you take off the strip waits in the … menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Order", style = MaterialTheme.typography.titleSmall)
            Text("A pane too narrow for every tool moves the last ones into … until there is room; the tool in hand always keeps its place.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            layout.order.forEachIndexed { index, slot ->
                val dragging = dragFrom == index
                val inMenu = slot in layout.hidden
                val spec = spec(slot)
                Surface(
                    shape = FolioShapes.large,
                    color = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().height(rowHeight).zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragDelta else 0f }
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                        Icon(spec.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(horizontal = FolioSpacing.dp8)) {
                            Text(spec.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (inMenu) "In … menu" else "On the toolbar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Move(true, spec.label, index > 0) { layoutState.moveSlot(index, index - 1) }
                        Move(false, spec.label, index < layout.order.lastIndex) { layoutState.moveSlot(index, index + 1) }
                        if (inMenu) TextButton({ layoutState.show(slot) }, shapes = ButtonDefaults.shapes()) { Text("To toolbar") }
                        else TextButton({ layoutState.hide(slot) }, enabled = layout.visible.size > 1, shapes = ButtonDefaults.shapes()) { Text("To … menu") }
                        // A full 40dp grip: the glyph alone was too small to catch with a finger.
                        Box(Modifier.size(40.dp).pointerInput(slot, index, layout.order.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragFrom = index; dragDelta = 0f },
                                    onDrag = { change, amount -> change.consume(); dragDelta += amount.y },
                                    onDragEnd = {
                                        val from = dragFrom
                                        if (from != null) layoutState.moveSlot(from, (from + (dragDelta / rowHeightPx).roundToInt()).coerceIn(0, layout.order.lastIndex))
                                        dragFrom = null; dragDelta = 0f
                                    },
                                    onDragCancel = { dragFrom = null; dragDelta = 0f }
                                )
                            }, contentAlignment = Alignment.Center) {
                            Icon(FolioIcons.Lines, "Drag to reorder ${spec.label}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("Pinned presets · up to ${ToolbarLayouts.MAX_PINNED}", style = MaterialTheme.typography.titleSmall)
            if (presets.isEmpty()) {
                Text("Save a tool setup as a preset (Tool settings → Save current) to pin it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else presets.forEach { preset ->
                val pinnedIndex = layout.pinnedPresetIds.indexOf(preset.id)
                val pinned = pinnedIndex >= 0
                Surface(shape = FolioShapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = FolioSpacing.dp8, vertical = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(Color(preset.color), CircleShape))
                        Column(Modifier.weight(1f).padding(horizontal = FolioSpacing.dp8)) {
                            Text(preset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(toolLabel(preset.tool), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (pinned) {
                            Move(true, preset.name, pinnedIndex > 0) { layoutState.movePinned(pinnedIndex, pinnedIndex - 1) }
                            Move(false, preset.name, pinnedIndex < layout.pinnedPresetIds.lastIndex) { layoutState.movePinned(pinnedIndex, pinnedIndex + 1) }
                        }
                        TextButton({ layoutState.togglePin(preset.id) }, shapes = ButtonDefaults.shapes()) { Text(if (pinned) "Unpin" else "Pin") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8, Alignment.End)) {
                TextButton({ layoutState.reset() }, shapes = ButtonDefaults.shapes()) { Text("Reset toolbar") }
                Button(onDismiss, shapes = ButtonDefaults.shapes()) { Text("Done") }
            }
        }
    }
}
