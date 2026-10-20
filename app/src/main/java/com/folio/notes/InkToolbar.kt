@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

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
 * paper tool rather than an annotation). A null [onSnap] removes the canvas snap switch entirely.
 */

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun FloatingInkToolbar(
    onMainHeight: (Int) -> Unit = {},
    /** Height of everything the toolbar shows, quick bar included, for callers whose page must never sit under it. */
    onFullHeight: (Int) -> Unit = {},
    modifier: Modifier, tool: Tool, onTool: (Tool) -> Unit, options: ToolOptions, onOptions: (ToolOptions) -> Unit, quick: QuickColorsState,
    canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit, palette: Boolean, snapEnabled: Boolean, onSnap: ((Boolean) -> Unit)? = null, onPalette: (Boolean) -> Unit,
    eraserSingleStroke: Boolean = false, onEraserSingleStroke: ((Boolean) -> Unit)? = null,
    scribbleToErase: Boolean = true, onScribbleToErase: ((Boolean) -> Unit)? = null,
    eraserPressureEnabled: Boolean = true, onEraserPressure: ((Boolean) -> Unit)? = null,
    eraserWholeStroke: Boolean = false, onEraserWholeStroke: ((Boolean) -> Unit)? = null,
    shapeMeasurements: Boolean = true, onShapeMeasurements: ((Boolean) -> Unit)? = null,
    multiTouchUndo: Boolean = true, onMultiTouchUndo: ((Boolean) -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null, markAreaAvailable: Boolean = false,
    textColor: Int = 0, onTextColor: ((Int) -> Unit)? = null,
    presets: List<ToolPreset> = emptyList(), onApplyPreset: ((ToolPreset) -> Unit)? = null,
    toolPresetsState: ToolPresetState? = null,
    toolbarLayoutState: ToolbarLayoutState? = null,
    hiddenSlots: Set<ToolbarSlot> = emptySet(),
    rulerOn: Boolean = false, onRuler: (() -> Unit)? = null,
    shapeTools: List<Tool> = ShapePickerTools.toList(),
    actions: List<ToolbarAction> = emptyList(),
    /** Flat on the editor bar; false floats the tools on their own glass pill (the music reader). */
    flat: Boolean = true,
    toolbarOptions: ToolbarOptions = ToolbarOptions(),
    header: @Composable (@Composable () -> Unit) -> Unit
) {
    var shapes by remember { mutableStateOf(false) }
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
    val toolPrefsContext = LocalContext.current
    val toolPrefs = remember(toolPrefsContext) { toolPrefsContext.getSharedPreferences("ink-tools", 0) }
    val isShape = tool in ShapePickerTools
    val isDrawing = tool in DrawingTools
    // Text quick controls change the colour used for new text boxes.
    val showQuickBar = quickBarOpen && (isDrawing || tool == Tool.ERASER || (tool == Tool.TEXT && onTextColor != null))
    val feedback = LocalHapticFeedback.current
    /** A light tick on real tool changes; tapping the active tool stays silent. */
    fun pick(next: Tool) {
        if (next != tool) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
    // Dots on the pen/highlighter show their own stored colours, not the active tool's, so the
    // inactive button still reads correctly. Reads are in-memory SharedPreferences lookups.
    val penDot = if (tool == Tool.PEN) options.color else toolPrefs.getInt("PEN.color", 0xFF303431.toInt())
    val highlighterDot = if (tool == Tool.HIGHLIGHTER) options.color else toolPrefs.getInt("HIGHLIGHTER.color", 0xFFE9BF44.toInt())
    val widthRange = WidthPresets.range(WidthPresets.group(tool))
    val widthPresetState = remember(toolPrefs) { WidthPresetState(toolPrefs) }
    var colorSlotEditing by remember { mutableStateOf<Int?>(null) }
    var widthSlotEditing by remember { mutableStateOf<Int?>(null) }
    // The popover opens under whichever control asked for it: the quick bar's settings icon, or the tool's own button.
    var paletteFromQuickBar by remember { mutableStateOf(false) }
    LaunchedEffect(palette) { if (!palette) paletteFromQuickBar = false }
    @Composable fun ToolSettingsPopover(fromQuickBar: Boolean = false) {
        if (palette && paletteFromQuickBar == fromQuickBar) FolioPopover(onDismiss = { onPalette(false) }, width = 344.dp) {
            ToolOptionsPanel(tool, options, onOptions, quick, toolPresetsState, onApplyPreset)
        }
    }
    // A selected tool's tile reaches its button's edge, so a divider beside the tools keeps a
    // little air on that side (budgeted in DividerAir below).
    @Composable fun ToolbarDivider(modifier: Modifier = Modifier) {
        Box(modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
    }
    @Composable fun QuickColors() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2), modifier = Modifier.padding(horizontal = FolioSpacing.dp2)) {
            quick.colors(colorGroup).forEachIndexed { index, c ->
                // Tapping the colour in use, or holding any dot, edits that slot in a popover.
                Box {
                    InkColorDot(c, options.color == c, {
                        feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
    @Composable fun WidthDots() {
        val presets = widthPresetState.widths(tool)
        val selectedIndex = WidthPresets.selectedIndex(presets, options.width)
        presets.forEachIndexed { index, w ->
            val selected = index == selectedIndex
            // Tapping the width in use, or holding any dot, edits that slot in a popover.
            Box {
                Box(
                    Modifier.size(ToolbarButton - 4.dp).clip(CircleShape)
                        .combinedClickable(
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = {
                                feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (selected) widthSlotEditing = index else onOptions(options.copy(width = w))
                            },
                            onLongClick = { feedback.performHapticFeedback(HapticFeedbackType.LongPress); widthSlotEditing = index }
                        )
                        .semanticsLabel("Width ${String.format(java.util.Locale.ROOT, "%.1f", w)}${if (selected) ", selected" else ""}"),
                    contentAlignment = Alignment.Center
                ) {
                    // The stroke sample grows with the stored width, so a customised slot still reads in order.
                    val thickness = (1.5f + 4.5f * ((w - widthRange.start) / (widthRange.endInclusive - widthRange.start)).coerceIn(0f, 1f).let { kotlin.math.sqrt(it) }).dp
                    Box(Modifier.width(24.dp).height(thickness).background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                    if (selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = FolioSpacing.dp4).size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
                if (widthSlotEditing == index) WidthSlotPopover(
                    tool = tool, slot = index, width = if (selected) options.width else w, color = Color(options.color).copy(alpha = options.opacity),
                    onPick = { picked -> widthPresetState.set(tool, index, picked); onOptions(options.copy(width = picked)) },
                    onReset = {
                        widthPresetState.reset(tool, index)
                        onOptions(options.copy(width = WidthPresets.defaults(WidthPresets.group(tool))[index]))
                    },
                    onDismiss = { widthSlotEditing = null }
                )
            }
        }
    }
    @Composable fun WidthControl() {
        val hold = rememberLongPressGuard()
        // The one settings popover: width, opacity, colour and the tool's own options all live in it.
        Box {
            IconButton(hold.click { paletteFromQuickBar = !palette; onPalette(!palette) }, modifier = Modifier.size(ToolbarButton)) {
                Icon(Icons.Rounded.Tune, "Tool settings", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ToolSettingsPopover(fromQuickBar = true)
        }
    }
    @Composable fun ShapesSlot() {
        Box(contentAlignment = Alignment.Center) {
            val shapeIcon = shapeIcon(if (isShape) tool else lastShape)
            Box {
                FolioToolToggle(isShape, { onPalette(false); if (isShape) shapePicker = true else chooseShape(lastShape) }, shapeIcon,
                    if (isShape) "Shapes, ${shapeLabel(tool).lowercase()} — tap again to choose shape" else "Shapes, ${shapeLabel(lastShape).lowercase()}",
                    onLongClick = { claimStripLongPress(); if (!isShape) pick(lastShape); onPalette(true) })
                Icon(Icons.Rounded.ArrowDropDown, null,
                    Modifier.align(Alignment.BottomEnd).size(14.dp),
                    tint = if (isShape) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shapePicker) ShapePickerPopover(tool, shapes = shapeTools, onPick = { value ->
                chooseShape(value); shapePicker = false
            }, onDrawHold = { pick(Tool.PEN); shapePicker = false }, onDismiss = { shapePicker = false })
        }
    }
    @Composable fun ToolbarSlotButton(slot: ToolbarSlot) {
      Box {
        if (tool in slot.tools) ToolSettingsPopover()
        when (slot) {
            ToolbarSlot.PEN -> ToolButton(Tool.PEN, tool, Icons.Rounded.Edit, "Pen", indicatorColor = Color(penDot).takeIf { toolbarOptions.colourDots }, onLongPress = { claimStripLongPress(); pick(Tool.PEN); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.SHAPES -> ShapesSlot()
            ToolbarSlot.HIGHLIGHTER -> ToolButton(Tool.HIGHLIGHTER, tool, ToolIcons.Highlighter, "Highlighter", indicatorColor = Color(highlighterDot).takeIf { toolbarOptions.colourDots }, onLongPress = { claimStripLongPress(); pick(Tool.HIGHLIGHTER); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.ERASER -> ToolButton(Tool.ERASER, tool, ToolIcons.Eraser, "Eraser", onLongPress = { claimStripLongPress(); pick(Tool.ERASER); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.STICKY_NOTE -> ToolButton(Tool.STICKY_NOTE, tool, Icons.AutoMirrored.Rounded.StickyNote2, "Sticky note — drag a rectangle, then type or draw", onLongPress = { claimStripLongPress(); pick(Tool.STICKY_NOTE) }) { pick(it) }
            ToolbarSlot.TEXT -> ToolButton(Tool.TEXT, tool, Icons.Rounded.TextFields, "Text", onLongPress = { claimStripLongPress(); pick(Tool.TEXT); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.LASSO -> ToolButton(Tool.LASSO, tool, ToolIcons.Lasso, "Lasso select", onLongPress = { claimStripLongPress(); pick(Tool.LASSO); onPalette(true) }) { pick(it) }
            ToolbarSlot.MARK_AREA -> ToolButton(Tool.MARK_AREA, tool, Icons.Rounded.CropFree, "Mark area — box a “[n marks]” label the scan missed", onLongPress = { claimStripLongPress(); pick(Tool.MARK_AREA) }) { pick(it) }
            ToolbarSlot.HAND -> ToolButton(Tool.HAND, tool, Icons.Rounded.PanTool, "Hand — follow links, move pictures, scroll and zoom", onLongPress = { claimStripLongPress(); pick(Tool.HAND); onPalette(true) }) { pick(it) }
            ToolbarSlot.LASER -> ToolButton(Tool.LASER, tool, ToolIcons.Laser, "Laser pointer — point and circle without writing", indicatorColor = Color(options.color).takeIf { tool == Tool.LASER && toolbarOptions.colourDots }, onLongPress = { claimStripLongPress(); pick(Tool.LASER); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.RULER -> FolioToolToggle(rulerOn, { feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); onRuler?.invoke() }, ToolIcons.Ruler,
                if (rulerOn) "Hide ruler" else "Ruler — draw straight along its edge; one finger moves it, two turn it")
        }
      }
    }
    val controls: @Composable RowScope.(Boolean, Boolean, List<ToolbarSlot>, Boolean) -> Unit = { showRedo, showUndo, primary, showExtras ->
        // The … menu holds what the user put there plus whatever this pane is too narrow to show.
        val overflow = toolbarLayout.order.filterNot { it in primary }
        val hasTray = primary.isNotEmpty() || (showExtras && (pinnedPresets.isNotEmpty() || actions.isNotEmpty()))
        if (showUndo) TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text("Undo") } }, state = rememberTooltipState()) {
            IconButton(stripGuard.click(undo), enabled = canUndo, modifier = Modifier.size(ToolbarButton), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", Modifier.size(20.dp)) }
        }
        if (showRedo) TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text("Redo") } }, state = rememberTooltipState()) {
            IconButton(stripGuard.click(redo), enabled = canRedo, modifier = Modifier.size(ToolbarButton), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo", Modifier.size(20.dp)) }
        }
        if (showUndo && hasTray) ToolbarDivider(Modifier.padding(start = DividerAir / 2, end = DividerAir))
        // Tools yield slots to overflow as the pane narrows. Keep the selected tool on the
        // strip, rather than leaving it off-screen at an old horizontal scroll position.
        if (hasTray) {
            Row(
                Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)
            ) {
                primary.forEach { slot -> key(slot) { ToolbarSlotButton(slot) } }
                if (showExtras) pinnedPresets.forEach { preset ->
                    key(preset.id) {
                        val presetActive = tool == preset.tool && options.color == preset.color && options.width == preset.width &&
                            options.opacity == preset.opacity && options.style == preset.style
                        Box {
                            FilterChip(
                                selected = presetActive,
                                onClick = stripGuard.click { feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); onApplyPreset?.invoke(preset) },
                                label = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
                                leadingIcon = {
                                    Box(Modifier.size(12.dp).background(Color(preset.color), CircleShape)) { }
                                },
                                modifier = Modifier.widthIn(max = 104.dp).height(28.dp).longPressAction(stripGuard) { claimStripLongPress(); presetMenu = preset.id }
                            )
                            FolioMenuPopover(presetMenu == preset.id, { presetMenu = null }, modifier = Modifier.guardUiTouches(), title = "Pinned preset") {
                                FolioMenuItem({ Text("Unpin “${preset.name}” from toolbar") }, { presetMenu = null; toolbarLayoutState?.togglePin(preset.id) }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                                FolioMenuItem({ Text("Tool settings") }, { presetMenu = null; onPalette(true) }, leadingIcon = { Icon(Icons.Rounded.Tune, null) })
                            }
                        }
                    }
                }
                if (showExtras && actions.isNotEmpty()) {
                    ToolbarDivider()
                    actions.forEach { action ->
                        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(action.label) } }, state = rememberTooltipState()) {
                            IconButton(stripGuard.click(action.onClick), modifier = Modifier.size(ToolbarButton), shapes = IconButtonDefaults.shapes()) { Icon(action.icon, action.label, Modifier.size(20.dp)) }
                        }
                    }
                }
            }
            ToolbarDivider(Modifier.padding(start = DividerAir, end = DividerAir / 2))
        }
        // Overflow for less frequent actions — keep palette access separate from quick controls
        Box {
            if (primary.none { tool in it.tools }) ToolSettingsPopover()
            var toolSub by remember { mutableStateOf<ToolSub?>(null) }
            val openSub: (ToolSub) -> Unit = { toolSub = if (toolSub == it) null else it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text("More options") } }, state = rememberTooltipState()) {
                    IconButton(stripGuard.click { shapes = true }, modifier = Modifier.size(ToolbarButton)) { Icon(Icons.Rounded.MoreHoriz, "More options", Modifier.size(20.dp)) }
                }
                TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(if (quickBarOpen) "Hide ink options" else "Show ink options") } }, state = rememberTooltipState()) {
                    IconButton(stripGuard.click { quickBarOpen = !quickBarOpen }, modifier = Modifier.size(ToolbarButton)) {
                        Icon(if (quickBarOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (quickBarOpen) "Hide ink options" else "Show ink options", Modifier.size(20.dp))
                    }
                }
            }
            if (shapePicker && ToolbarSlot.SHAPES in overflow) {
                ShapePickerPopover(tool, shapes = shapeTools, onPick = { value -> chooseShape(value); shapePicker = false },
                    onDrawHold = { pick(Tool.PEN); shapePicker = false }, onDismiss = { shapePicker = false })
            }
            FolioMenuPopover(shapes, { shapes = false; toolSub = null }, modifier = Modifier.guardUiTouches(), title = "Tools & actions") {
                if (!showUndo) FolioMenuItem({ Text("Undo") }, { undo(); shapes = false }, enabled = canUndo,
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, null) })
                if (!showRedo) {
                    FolioMenuItem({ Text("Redo") }, { redo(); shapes = false }, enabled = canRedo,
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Redo, null) })
                    HorizontalDivider()
                }
                if (overflow.isNotEmpty()) MenuSectionHeader("Tools")
                overflow.forEach { slot ->
                    if (slot == ToolbarSlot.SHAPES) {
                        FolioMenuItem({ Text("Shapes") }, { shapes = false; onPalette(false); shapePicker = true },
                            leadingIcon = { Icon(shapeIcon(if (isShape) tool else lastShape), null) },
                            trailingIcon = { Icon(Icons.Rounded.ChevronRight, null) })
                    } else if (slot == ToolbarSlot.RULER) {
                        FolioMenuItem({ Text(if (rulerOn) "Ruler: on" else "Ruler: off") }, { onRuler?.invoke(); shapes = false },
                            leadingIcon = { Icon(toolbarSlotIcon(slot, tool, lastShape), null) },
                            selected = rulerOn)
                    } else {
                        val first = slot.tools.first()
                        FolioMenuItem({ Text(toolbarSlotLabel(slot)) }, { pick(first); shapes = false },
                            leadingIcon = { Icon(toolbarSlotIcon(slot, tool, lastShape), null) },
                            selected = tool in slot.tools)
                    }
                }
                if (overflow.isNotEmpty()) HorizontalDivider()
                if (!showExtras) actions.forEach { action ->
                    FolioMenuItem({ Text(action.label) }, { action.onClick(); shapes = false },
                        leadingIcon = { Icon(action.icon, null) })
                }
                if (presets.isNotEmpty() && onApplyPreset != null) {
                    SubmenuItem("Presets…", Icons.Rounded.Bookmark, toolSub == ToolSub.PRESETS, { openSub(ToolSub.PRESETS) }) {
                        MenuSectionHeader("Presets")
                        presets.forEach { preset ->
                            FolioMenuItem(
                                { Text("${preset.name} · ${toolLabel(preset.tool).lowercase()}") },
                                { onApplyPreset(preset); shapes = false },
                                leadingIcon = { Icon(Icons.Rounded.Bookmark, null) }
                            )
                        }
                    }
                }
                // Everything that tunes how a tool behaves lives in one submenu; select all is on the page's long-press menu.
                SubmenuItem("Behaviour…", Icons.Rounded.Tune, toolSub == ToolSub.TOOL, { openSub(ToolSub.TOOL) }) {
                    if (!isDrawing && tool != Tool.ERASER) {
                        if (onSnap != null) FolioMenuItem({ Text(if (snapEnabled) "Snap to grid: on" else "Snap to grid: off") }, { onSnap(!snapEnabled); shapes = false }, leadingIcon = { Icon(if (snapEnabled) Icons.Rounded.GridView else Icons.Rounded.GridOff, null) })
                        if (onShapeMeasurements != null) FolioMenuItem({ Text(if (shapeMeasurements) "Measurements: on" else "Measurements: off") }, { onShapeMeasurements(!shapeMeasurements); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Straighten, null) })
                    }
                    if (tool == Tool.ERASER || tool == Tool.PEN || tool == Tool.HIGHLIGHTER) {
                        if (onEraserSingleStroke != null) FolioMenuItem({ Text(if (eraserSingleStroke) "Single-stroke eraser: on" else "Single-stroke eraser: off") }, { onEraserSingleStroke(!eraserSingleStroke); shapes = false }, leadingIcon = { Icon(ToolIcons.Eraser, null) })
                        if (onEraserPressure != null) FolioMenuItem({ Text(if (eraserPressureEnabled) "Eraser pressure: on" else "Eraser pressure: off") }, { onEraserPressure(!eraserPressureEnabled); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Compress, null) })
                        if (onEraserWholeStroke != null) FolioMenuItem({ Text(if (eraserWholeStroke) "Whole-stroke eraser: on" else "Whole-stroke eraser: off") }, { onEraserWholeStroke(!eraserWholeStroke); shapes = false }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) })
                        if (onScribbleToErase != null) FolioMenuItem({ Text(if (scribbleToErase) "Scribble to erase: on" else "Scribble to erase: off") }, { onScribbleToErase(!scribbleToErase); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Brush, null) })
                    }
                    if (isShape) FolioMenuItem({ Text("Line style: ${options.style.label.lowercase()}") }, {
                        onOptions(options.copy(style = options.style.next()))
                    }, leadingIcon = { Icon(Icons.Rounded.Gesture, null) })
                    if (onMultiTouchUndo != null) FolioMenuItem({ Text(if (multiTouchUndo) "Two-finger undo: on" else "Two-finger undo: off") }, { onMultiTouchUndo(!multiTouchUndo); shapes = false }, leadingIcon = { Icon(Icons.Rounded.Gesture, null) })
                }
                if (toolbarLayoutState != null) FolioMenuItem({ Text("Edit toolbar") }, { shapes = false; editToolbar = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            }
        }
    }
    // The header hosts the main tools; quick controls remain directly beneath them.
    Column(modifier.guardUiTouches().fillMaxWidth().onSizeChanged { onFullHeight(it.height) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
        // Only the header and main strip reserve space; the quick bar floats over the page so
        // toggling it never moves the document.
        Column(Modifier.onSizeChanged { onMainHeight(it.height) }, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
            header {
                BoxWithConstraints {
                    // Undo and redo follow Settings, and narrow panes drop redo, then undo, into the … menu.
                    val showUndo = toolbarOptions.undo != UndoButtons.NONE && maxWidth >= 192.dp
                    val showRedo = showUndo && toolbarOptions.undo == UndoButtons.BOTH && maxWidth >= 360.dp
                    // 36dp buttons, 2dp gaps, two 1dp dividers with their air and 8dp outer padding.
                    // Budget the fixed buttons first, then whole tool slots; presets never crowd those slots out.
                    val fixedWidth = 85.dp + DividerAir * 3 / 2 +
                        (if (showUndo) 41.dp + DividerAir * 3 / 2 else 0.dp) + (if (showRedo) 38.dp else 0.dp)
                    // Leave two dp of slack for per-button pixel rounding at fractional densities.
                    val capacity = ((maxWidth - fixedWidth) / ToolbarSlotPitch).toInt()
                        .coerceIn(0, toolbarLayout.visible.size)
                    val primary = toolbarLayout.visible.take(capacity).toMutableList()
                    val activeSlot = toolbarLayout.visible.firstOrNull { tool in it.tools }
                    if (activeSlot != null && capacity > 0 && activeSlot !in primary) {
                        primary[primary.lastIndex] = activeSlot
                        primary.sortBy { toolbarLayout.order.indexOf(it) }
                    }
                    val extrasWidth = 104.dp * pinnedPresets.size + ToolbarSlotPitch * actions.size +
                        if (actions.isNotEmpty()) 3.dp else 0.dp
                    val showExtras = primary.size == toolbarLayout.visible.size &&
                        maxWidth >= fixedWidth + ToolbarSlotPitch * primary.size + extrasWidth
                    val stripHold = Modifier.longPressAction(stripGuard) {
                        if (System.currentTimeMillis() - childLongPressAt <= 400) {
                            // A tool or preset claimed the gesture first; its own action stands alone.
                            stripGuard.begin()
                        } else if (toolbarLayoutState != null) editToolbar = true
                    }
                    val strip: @Composable () -> Unit = {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp4).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                            controls(showRedo, showUndo, primary, showExtras)
                        }
                    }
                    // The editor bar already is the surface, so its tools sit on it without a pill.
                    if (flat) Box(stripHold.height(EditorFloatingGroupHeight), contentAlignment = Alignment.Center) { strip() }
                    else EditorGlassSurface(stripHold) { strip() }
                }
            }
        }
        FolioExpand(showQuickBar) {
            BoxWithConstraints {
                // In a narrow pane, widths/shapes and colours get their own row so neither
                // group disappears behind the other. Each row can still scroll in tiny panes.
                val splitQuickGroups = tool != Tool.TEXT && tool != Tool.ERASER && maxWidth < 440.dp
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                    EditorGlassSurface(Modifier.widthIn(max = 640.dp)) {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp4).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp4).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                                if (tool == Tool.TEXT && onTextColor != null) {
                                    quick.colors(colorGroup).forEachIndexed { index, c ->
                                        InkColorDot(c, textColor == c, { feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTextColor(c) }, touch = ToolbarButton - 4.dp, dot = 22.dp, label = "Text colour ${index + 1}", onLongClick = { onPalette(true) })
                                    }
                                    Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
                                    Text("Text colour", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                } else {
                                    if (isShape) RecentShapes() else WidthDots()
                                    if (tool != Tool.ERASER && !splitQuickGroups) {
                                        Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
                                        QuickColors()
                                    }
                                }
                            }
                            // Settings stays reachable even when the colours and widths need scrolling.
                            WidthControl()
                        }
                    }
                    if (splitQuickGroups) EditorGlassSurface {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp6),
                            verticalAlignment = Alignment.CenterVertically) { QuickColors() }
                    }
                }
            }
        }
    }
    if (editToolbar && toolbarLayoutState != null) {
        ToolbarEditPanel(
            layoutState = toolbarLayoutState,
            presets = presets,
            onDismiss = { editToolbar = false }
        )
    }
}

/** Space a divider keeps from the tool tray beside it, so a selected tile never touches it. */
private val DividerAir = 4.dp

/** The strip's button edge; one more 2dp gap makes the pitch the capacity budget divides by. */
private val ToolbarButton: Dp
    @Composable get() { val p = rememberPrefs(); val large by rememberPref(p, AppPrefs.UX_LARGE_CONTROLS) { it.getBoolean(AppPrefs.UX_LARGE_CONTROLS, AppPrefs.DEFAULT_UX_LARGE_CONTROLS) }; return if (large) 48.dp else 36.dp }
private val ToolbarSlotPitch: Dp @Composable get() = (if (showEditorToolNames()) 76.dp else ToolbarButton) + 2.dp

/** A one-tap action shown beside the tools in the dock. */
internal class ToolbarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

private fun toolbarSlotLabel(slot: ToolbarSlot): String = when (slot) {
    ToolbarSlot.PEN -> "Pen"
    ToolbarSlot.SHAPES -> "Shapes"
    ToolbarSlot.HIGHLIGHTER -> "Highlighter"
    ToolbarSlot.ERASER -> "Eraser"
    ToolbarSlot.STICKY_NOTE -> "Sticky note"
    ToolbarSlot.TEXT -> "Text"
    ToolbarSlot.LASSO -> "Lasso select"
    ToolbarSlot.HAND -> "Hand"
    ToolbarSlot.MARK_AREA -> "Mark area"
    ToolbarSlot.LASER -> "Laser pointer"
    ToolbarSlot.RULER -> "Ruler"
}

private fun toolbarSlotIcon(slot: ToolbarSlot, tool: Tool, lastShape: Tool): androidx.compose.ui.graphics.vector.ImageVector = when (slot) {
    ToolbarSlot.PEN -> Icons.Rounded.Edit
    ToolbarSlot.SHAPES -> shapeIcon(if (tool in ShapePickerTools) tool else lastShape)
    ToolbarSlot.HIGHLIGHTER -> ToolIcons.Highlighter
    ToolbarSlot.ERASER -> ToolIcons.Eraser
    ToolbarSlot.STICKY_NOTE -> Icons.AutoMirrored.Rounded.StickyNote2
    ToolbarSlot.TEXT -> Icons.Rounded.TextFields
    ToolbarSlot.LASSO -> ToolIcons.Lasso
    ToolbarSlot.HAND -> Icons.Rounded.PanTool
    ToolbarSlot.MARK_AREA -> Icons.Rounded.CropFree
    ToolbarSlot.LASER -> ToolIcons.Laser
    ToolbarSlot.RULER -> ToolIcons.Ruler
}

/**
 * Reorders toolbar tools, moves them between the strip and the … menu, and
 * pins saved presets beside them. Every tool is in one place or the other;
 * nothing is ever forced into the menu. Rows drag by their handle (long-press)
 * and also move with arrows so the sheet stays usable without fine motor control.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun ToolbarEditPanel(
    layoutState: ToolbarLayoutState,
    presets: List<ToolPreset>,
    onDismiss: () -> Unit
) {
    val layout = layoutState.layout
    val rowHeight = 56.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragFrom by remember(layout.order) { mutableStateOf<Int?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
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
                Surface(
                    shape = FolioShapes.large,
                    color = if (dragging) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().height(rowHeight)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragDelta else 0f }
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            toolbarSlotIcon(slot, Tool.PEN, Tool.LINE), null,
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(Modifier.weight(1f).padding(horizontal = FolioSpacing.dp8)) {
                            Text(toolbarSlotLabel(slot), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (inMenu) "In … menu" else "On the toolbar",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton({ layoutState.moveSlot(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.ArrowUpward, "Move ${toolbarSlotLabel(slot)} up", Modifier.size(18.dp))
                        }
                        IconButton({ layoutState.moveSlot(index, index + 1) }, enabled = index < layout.order.lastIndex, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.ArrowDownward, "Move ${toolbarSlotLabel(slot)} down", Modifier.size(18.dp))
                        }
                        if (inMenu) {
                            TextButton({ layoutState.show(slot) }, shapes = ButtonDefaults.shapes()) { Text("To toolbar") }
                        } else {
                            TextButton({ layoutState.hide(slot) }, enabled = layout.visible.size > 1, shapes = ButtonDefaults.shapes()) { Text("To … menu") }
                        }
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
                            Icon(Icons.Rounded.DragHandle, "Drag to reorder ${toolbarSlotLabel(slot)}", Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("Pinned presets · up to ${ToolbarLayouts.MAX_PINNED}", style = MaterialTheme.typography.titleSmall)
            if (presets.isEmpty()) {
                Text("Save a tool setup as a preset (Tool settings → Save current) to pin it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                presets.forEach { preset ->
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
                                IconButton({ layoutState.movePinned(pinnedIndex, pinnedIndex - 1) }, enabled = pinnedIndex > 0, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.ArrowUpward, "Move ${preset.name} up", Modifier.size(18.dp))
                                }
                                IconButton({ layoutState.movePinned(pinnedIndex, pinnedIndex + 1) }, enabled = pinnedIndex < layout.pinnedPresetIds.lastIndex, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.ArrowDownward, "Move ${preset.name} down", Modifier.size(18.dp))
                                }
                            }
                            TextButton({ layoutState.togglePin(preset.id) }, shapes = ButtonDefaults.shapes()) { Text(if (pinned) "Unpin" else "Pin") }
                        }
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

@Composable private fun ToolButton(value: Tool, selected: Tool, icon: ImageVector, label: String, indicatorColor: Color? = null, onLongPress: () -> Unit, change: (Tool) -> Unit) {
    // The tooltip yields to the long-press: holding a tool opens its settings instead.
    FolioToolToggle(value == selected, { change(value) }, icon, label, indicatorColor = indicatorColor, onLongClick = onLongPress)
}

private val GraphAxesIcon by lazy {
    ImageVector.Builder("GraphAxes", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
            moveTo(3f, 12f); lineTo(21f, 12f)
            moveTo(12f, 3f); lineTo(12f, 21f)
            moveTo(6f, 9f); lineTo(3f, 12f); lineTo(6f, 15f)
            moveTo(18f, 9f); lineTo(21f, 12f); lineTo(18f, 15f)
            moveTo(9f, 6f); lineTo(12f, 3f); lineTo(15f, 6f)
            moveTo(9f, 18f); lineTo(12f, 21f); lineTo(15f, 18f)
        }
    }.build()
}

private fun shapeIcon(tool: Tool): androidx.compose.ui.graphics.vector.ImageVector = when (tool) {
    Tool.LINE -> ToolIcons.Line
    Tool.ELLIPSE -> Icons.Rounded.Circle
    Tool.TRIANGLE -> Icons.Rounded.ChangeHistory
    Tool.DIAMOND -> Icons.Rounded.Diamond
    Tool.PENTAGON -> Icons.Rounded.Pentagon
    Tool.HEXAGON -> Icons.Rounded.Hexagon
    Tool.STAR -> Icons.Rounded.StarBorder
    Tool.GRAPH -> GraphAxesIcon
    else -> Icons.Rounded.CropSquare
}

private fun shapeLabel(tool: Tool) = when (tool) {
    Tool.LINE -> "Straight line"
    Tool.GRAPH -> "Graph axes"
    else -> tool.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/** A tool's name for people, never its enum constant (no "Mark_area"). */
internal fun toolLabel(tool: Tool): String =
    if (tool in ShapePickerTools) shapeLabel(tool)
    else ToolbarSlot.entries.first { tool in it.tools }.let(::toolbarSlotLabel)

/** The glyph a toolbar slot wears; the ruler has no tool, so slots are not always tools. */
internal fun toolbarSlotIcon(slot: ToolbarSlot): ImageVector = toolbarSlotIcon(slot, Tool.PEN, Tool.LINE)

/** The glyph a tool wears on the toolbar. */
internal fun toolIcon(tool: Tool): ImageVector =
    if (tool in ShapePickerTools) shapeIcon(tool)
    else toolbarSlotIcon(ToolbarSlot.entries.first { tool in it.tools }, tool, Tool.LINE)

/** The second-level menus under the toolbar's ⋯ menu. */
private enum class ToolSub { PRESETS, TOOL }
