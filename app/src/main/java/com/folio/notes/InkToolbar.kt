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
 * [hiddenSlots] removes tools from the strip and the overflow entirely — the editor never hides
 * one, the reader hides [ToolbarSlot.STICKY_NOTE] because a score has no space beside its page.
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
    shapeTools: List<Tool> = ShapePickerTools.toList(),
    actions: List<ToolbarAction> = emptyList(),
    header: @Composable (@Composable () -> Unit) -> Unit
) {
    var shapes by remember { mutableStateOf(false) }
    var shapePicker by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf<String?>(null) }
    var editToolbar by remember { mutableStateOf(false) }
    var quickBarOpen by rememberSaveable { mutableStateOf(false) }
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
    // Boxing a missed allocation only means something on an imported PDF, so elsewhere the tool stays out of the way;
    // where it applies it rides on top of the strip rather than pushing another tool into the overflow.
    val toolbarLayout = (toolbarLayoutState?.layout ?: ToolbarLayouts.default()).let { base ->
        if (hiddenSlots.isEmpty()) base
        else base.copy(hidden = base.hidden + hiddenSlots, maxPrimary = base.maxPrimary.coerceAtLeast(hiddenSlots.count { it in base.primary }))
    }.let { base ->
        if (!markAreaAvailable) base.copy(hidden = base.hidden + ToolbarSlot.MARK_AREA)
        else if (ToolbarSlot.MARK_AREA in base.hidden) base else base.copy(maxPrimary = base.maxPrimary + 1)
    }
    val pinnedPresets = remember(presets, toolbarLayout.pinnedPresetIds) {
        toolbarLayout.pinnedPresetIds.mapNotNull { id -> presets.find { it.id == id } }
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
            ToolOptionsPanel(tool, options, onOptions, quick, toolPresetsState)
        }
    }
    @Composable fun ToolbarDivider() {
        Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
    }
    @Composable fun QuickColors() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2), modifier = Modifier.padding(horizontal = FolioSpacing.dp2)) {
            quick.colors(colorGroup).forEachIndexed { index, c ->
                // Tapping the colour in use, or holding any dot, edits that slot in a popover.
                Box {
                    InkColorDot(c, options.color == c, {
                        feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (options.color == c) colorSlotEditing = index else onOptions(options.copy(color = c))
                    }, label = "Quick colour ${index + 1}", onLongClick = { colorSlotEditing = index })
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
            IconButton({ chooseShape(shape) }, modifier = Modifier.size(40.dp).semanticsLabel("${shapeLabel(shape)}${if (selected) ", selected" else ""}"),
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
                    Modifier.size(40.dp).clip(CircleShape)
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
            IconButton(hold.click { paletteFromQuickBar = !palette; onPalette(!palette) }, modifier = Modifier.size(40.dp)) {
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
                    if (isShape) "Shapes, ${tool.name.lowercase()} — tap again to choose shape" else "Shapes, ${lastShape.name.lowercase()}",
                    onLongClick = { claimStripLongPress(); if (!isShape) pick(lastShape); onPalette(true) })
                Icon(Icons.Rounded.ArrowDropDown, null,
                    Modifier.align(Alignment.BottomEnd).size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shapePicker) ShapePickerPopover(tool, shapes = shapeTools, onPick = { value ->
                chooseShape(value); shapePicker = false
            }, onDismiss = { shapePicker = false })
        }
    }
    @Composable fun ToolbarSlotButton(slot: ToolbarSlot) {
      Box {
        if (tool in slot.tools) ToolSettingsPopover()
        when (slot) {
            ToolbarSlot.PEN -> ToolButton(Tool.PEN, tool, Icons.Rounded.Edit, "Pen", indicatorColor = Color(penDot), onLongPress = { claimStripLongPress(); pick(Tool.PEN); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.SHAPES -> ShapesSlot()
            ToolbarSlot.HIGHLIGHTER -> ToolButton(Tool.HIGHLIGHTER, tool, Icons.Rounded.BorderColor, "Highlighter", indicatorColor = Color(highlighterDot), onLongPress = { claimStripLongPress(); pick(Tool.HIGHLIGHTER); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.ERASER -> ToolButton(Tool.ERASER, tool, Icons.Rounded.AutoFixNormal, "Eraser", onLongPress = { claimStripLongPress(); pick(Tool.ERASER); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.STICKY_NOTE -> ToolButton(Tool.STICKY_NOTE, tool, Icons.AutoMirrored.Rounded.StickyNote2, "Sticky note — drag a rectangle, then type or draw", onLongPress = { claimStripLongPress(); pick(Tool.STICKY_NOTE) }) { pick(it) }
            ToolbarSlot.TEXT -> ToolButton(Tool.TEXT, tool, Icons.Rounded.TextFields, "Text", onLongPress = { claimStripLongPress(); pick(Tool.TEXT); onPalette(true) }) { if (it == tool) onPalette(true) else pick(it) }
            ToolbarSlot.LASSO -> ToolButton(Tool.LASSO, tool, Icons.Rounded.Gesture, "Lasso select", onLongPress = { claimStripLongPress(); pick(Tool.LASSO); onPalette(true) }) { pick(it) }
            ToolbarSlot.MARK_AREA -> ToolButton(Tool.MARK_AREA, tool, Icons.Rounded.CropFree, "Mark area — box a “[n marks]” label the scan missed", onLongPress = { claimStripLongPress(); pick(Tool.MARK_AREA) }) { pick(it) }
            ToolbarSlot.HAND -> ToolButton(Tool.HAND, tool, Icons.Rounded.PanTool, "Hand — follow links, move pictures, scroll and zoom", onLongPress = { claimStripLongPress(); pick(Tool.HAND); onPalette(true) }) { pick(it) }
        }
      }
    }
    val controls: @Composable RowScope.(Boolean, Boolean, List<ToolbarSlot>, Boolean) -> Unit = { compactTools, showUndo, primary, showExtras ->
        val overflow = toolbarLayout.visible.filterNot { it in primary }
        val hasTray = primary.isNotEmpty() || (showExtras && (pinnedPresets.isNotEmpty() || actions.isNotEmpty()))
        if (showUndo) TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text("Undo") } }, state = rememberTooltipState()) {
            IconButton(stripGuard.click(undo), enabled = canUndo, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", Modifier.size(20.dp)) }
        }
        if (!compactTools) TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text("Redo") } }, state = rememberTooltipState()) {
            IconButton(stripGuard.click(redo), enabled = canRedo, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo", Modifier.size(20.dp)) }
        }
        if (showUndo && hasTray) ToolbarDivider()
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
                        Box {
                            FilterChip(
                                selected = tool == preset.tool && options.color == preset.color && options.width == preset.width &&
                                    options.opacity == preset.opacity && options.style == preset.style,
                                onClick = stripGuard.click { feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); onApplyPreset?.invoke(preset) },
                                label = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
                                leadingIcon = {
                                    Box(Modifier.size(12.dp).background(Color(preset.color), CircleShape)) { }
                                },
                                modifier = Modifier.widthIn(max = 112.dp).height(32.dp).longPressAction(stripGuard) { claimStripLongPress(); presetMenu = preset.id }
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
                            IconButton(stripGuard.click(action.onClick), modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) { Icon(action.icon, action.label, Modifier.size(20.dp)) }
                        }
                    }
                }
            }
            ToolbarDivider()
        }
        // Overflow for less frequent actions — keep palette access separate from quick controls
        Box {
            if (primary.none { tool in it.tools }) ToolSettingsPopover()
            var toolSub by remember { mutableStateOf<ToolSub?>(null) }
            val openSub: (ToolSub) -> Unit = { toolSub = if (toolSub == it) null else it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(stripGuard.click { shapes = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreHoriz, "More options", Modifier.size(20.dp)) }
                IconButton(stripGuard.click { quickBarOpen = !quickBarOpen }, modifier = Modifier.size(40.dp)) {
                    Icon(if (quickBarOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (quickBarOpen) "Hide ink options" else "Show ink options", Modifier.size(20.dp))
                }
            }
            if (shapePicker && ToolbarSlot.SHAPES in overflow) {
                ShapePickerPopover(tool, shapes = shapeTools, onPick = { value -> chooseShape(value); shapePicker = false },
                    onDismiss = { shapePicker = false })
            }
            FolioMenuPopover(shapes, { shapes = false; toolSub = null }, modifier = Modifier.guardUiTouches(), title = "Tools & actions") {
                if (!showUndo) FolioMenuItem({ Text("Undo") }, { undo(); shapes = false }, enabled = canUndo,
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, null) })
                if (compactTools) {
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
                                { Text("${preset.name} · ${preset.tool.name.lowercase()}") },
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
                        if (onEraserSingleStroke != null) FolioMenuItem({ Text(if (eraserSingleStroke) "Single-stroke eraser: on" else "Single-stroke eraser: off") }, { onEraserSingleStroke(!eraserSingleStroke); shapes = false }, leadingIcon = { Icon(Icons.Rounded.AutoFixNormal, null) })
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
        verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        // Only the header and main strip reserve space; the quick bar floats over the page so
        // toggling it never moves the document.
        Column(Modifier.onSizeChanged { onMainHeight(it.height) }, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            header {
                BoxWithConstraints {
                    val compactTools = maxWidth < 360.dp
                    val showUndo = maxWidth >= 184.dp
                    // 40dp buttons, 2dp gaps, two 1dp dividers and 12dp outer padding. Budget the
                    // fixed buttons first, then whole tool slots; presets never crowd those slots out.
                    val fixedWidth = when {
                        !showUndo -> 97.dp
                        compactTools -> 142.dp
                        else -> 184.dp
                    }
                    // Leave two dp of slack for per-button pixel rounding at fractional densities.
                    val capacity = ((maxWidth - fixedWidth) / 42.dp).toInt()
                        .coerceIn(0, toolbarLayout.primary.size)
                    val primary = toolbarLayout.primary.take(capacity).toMutableList()
                    val activeSlot = toolbarLayout.visible.firstOrNull { tool in it.tools }
                    if (activeSlot != null && capacity > 0 && activeSlot !in primary) {
                        primary[primary.lastIndex] = activeSlot
                        primary.sortBy { toolbarLayout.order.indexOf(it) }
                    }
                    val extrasWidth = 114.dp * pinnedPresets.size + 42.dp * actions.size +
                        if (actions.isNotEmpty()) 3.dp else 0.dp
                    val showExtras = primary.size == toolbarLayout.primary.size &&
                        maxWidth >= fixedWidth + 42.dp * primary.size + extrasWidth
                    EditorGlassSurface(
                        Modifier.longPressAction(stripGuard) {
                            if (System.currentTimeMillis() - childLongPressAt <= 400) {
                                // A tool or preset claimed the gesture first; its own action stands alone.
                                stripGuard.begin()
                            } else if (toolbarLayoutState != null) editToolbar = true
                        }
                    ) {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp6, vertical = FolioSpacing.dp2).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
                            controls(compactTools, showUndo, primary, showExtras)
                        }
                    }
                }
            }
        }
        FolioExpand(showQuickBar) {
            BoxWithConstraints {
                // In a narrow pane, widths/shapes and colours get their own row so neither
                // group disappears behind the other. Each row can still scroll in tiny panes.
                val splitQuickGroups = tool != Tool.TEXT && tool != Tool.ERASER && maxWidth < 440.dp
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                    EditorGlassSurface(Modifier.widthIn(max = 640.dp)) {
                        Row(Modifier.padding(horizontal = FolioSpacing.dp6).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()).padding(horizontal = FolioSpacing.dp4).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
                                if (tool == Tool.TEXT && onTextColor != null) {
                                    quick.colors(colorGroup).forEachIndexed { index, c ->
                                        InkColorDot(c, textColor == c, { feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTextColor(c) }, label = "Text colour ${index + 1}", onLongClick = { onPalette(true) })
                                    }
                                    Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
                                    Text("Text colour", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                } else {
                                    if (isShape) RecentShapes() else WidthDots()
                                    if (tool != Tool.ERASER && !splitQuickGroups) {
                                        Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
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
}

private fun toolbarSlotIcon(slot: ToolbarSlot, tool: Tool, lastShape: Tool): androidx.compose.ui.graphics.vector.ImageVector = when (slot) {
    ToolbarSlot.PEN -> Icons.Rounded.Edit
    ToolbarSlot.SHAPES -> shapeIcon(if (tool in ShapePickerTools) tool else lastShape)
    ToolbarSlot.HIGHLIGHTER -> Icons.Rounded.BorderColor
    ToolbarSlot.ERASER -> Icons.Rounded.AutoFixNormal
    ToolbarSlot.STICKY_NOTE -> Icons.AutoMirrored.Rounded.StickyNote2
    ToolbarSlot.TEXT -> Icons.Rounded.TextFields
    ToolbarSlot.LASSO -> Icons.Rounded.Gesture
    ToolbarSlot.HAND -> Icons.Rounded.PanTool
    ToolbarSlot.MARK_AREA -> Icons.Rounded.CropFree
}

/**
 * Reorders, hides and pins toolbar tools. The strip shows the first 5–7
 * visible tools; the rest live under "…". Saved presets can be pinned beside
 * them. Rows drag by their handle (long-press) and also move with arrows so
 * the sheet stays usable without fine motor control.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun ToolbarEditPanel(
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
            Text("Long-press the tool strip any time to come back here. Hidden tools leave the strip and the … menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Primary tools · first ${layout.maxPrimary} shown", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                listOf(5, 6, 7).forEach { count ->
                    FilterChip(layout.maxPrimary == count, { layoutState.setMaxPrimary(count) }, { Text("$count") })
                }
            }
            Text("Order", style = MaterialTheme.typography.titleSmall)
            layout.order.forEachIndexed { index, slot ->
                val dragging = dragFrom == index
                val primary = slot in layout.primary
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
                                when {
                                    slot in layout.hidden -> "Hidden"
                                    primary -> "On strip · ${index + 1}"
                                    else -> "Under …"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton({ layoutState.moveSlot(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.ArrowUpward, "Move ${toolbarSlotLabel(slot)} up", Modifier.size(18.dp))
                        }
                        IconButton({ layoutState.moveSlot(index, index + 1) }, enabled = index < layout.order.lastIndex, modifier = Modifier.size(40.dp), shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.ArrowDownward, "Move ${toolbarSlotLabel(slot)} down", Modifier.size(18.dp))
                        }
                        if (slot in layout.hidden) {
                            TextButton({ layoutState.show(slot) }, shapes = ButtonDefaults.shapes()) { Text("Show") }
                        } else {
                            TextButton({ layoutState.hide(slot) }, enabled = layout.visible.size > 1, shapes = ButtonDefaults.shapes()) { Text("Hide") }
                        }
                        Icon(
                            Icons.Rounded.DragHandle, "Drag to reorder ${toolbarSlotLabel(slot)}",
                            Modifier.size(20.dp).pointerInput(slot, index, layout.order.size) {
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
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                Text(preset.tool.name.lowercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Tool.LINE -> Icons.AutoMirrored.Rounded.ShowChart
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
    else -> tool.name.lowercase().replaceFirstChar(Char::uppercase)
}

/** The second-level menus under the toolbar's ⋯ menu. */
private enum class ToolSub { PRESETS, TOOL }
