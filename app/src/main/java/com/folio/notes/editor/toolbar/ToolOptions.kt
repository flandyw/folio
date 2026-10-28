@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes.editor.toolbar

import android.content.SharedPreferences
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.notes.app.AppPrefs
import com.folio.notes.editor.LocalNotebookToolbarPreferences
import com.folio.notes.ink.graph.GraphAxes
import com.folio.notes.ink.graph.GraphOrigin
import com.folio.notes.ink.graph.GraphStyle
import com.folio.notes.ink.graph.graphNumber
import com.folio.notes.ink.pen.PenPressure
import com.folio.notes.ink.recognition.ScribbleSettingsSection
import com.folio.notes.ink.shapes.ShapeAssistSettings
import com.folio.notes.input.guardUiTouches
import com.folio.notes.input.longPressAction
import com.folio.notes.input.rememberLongPressGuard
import com.folio.notes.library.semanticsLabel
import com.folio.notes.model.DrawingTools
import com.folio.notes.model.InkPoint
import com.folio.notes.model.ShapePickerTools
import com.folio.notes.model.Stroke
import com.folio.notes.model.StrokeStyle
import com.folio.notes.model.Tool
import com.folio.notes.settings.rememberPref
import com.folio.notes.settings.rememberPrefs
import com.folio.notes.ui.FolioButtonGroup
import com.folio.notes.ui.FolioExpand
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.folioDisclosure
import com.folio.notes.ui.folioSelected
import java.util.Locale
import kotlin.math.roundToInt

data class ToolOptions(
    val color: Int, val width: Float, val opacity: Float, val pressure: Boolean,
    val pressureSensitivity: Float = 1f, val pressureVariation: Float = 1f,
    val style: StrokeStyle = StrokeStyle.SOLID,
    val pressureCurve: Float = 1f
) {
    fun save(prefs: SharedPreferences, tool: Tool) {
        prefs.edit().putInt("${tool.name}.color", color).putFloat("${tool.name}.width", width)
            .putFloat("${tool.name}.opacity", opacity).putBoolean("${tool.name}.pressure", pressure)
            .putFloat("${tool.name}.pressureSensitivity", PenPressure.sensitivity(pressureSensitivity))
            .putFloat("${tool.name}.pressureVariation", PenPressure.variation(pressureVariation))
            .putFloat("${tool.name}.pressureCurve", PenPressure.curve(pressureCurve))
            .putString("${tool.name}.style", style.name).apply()
    }
    companion object {
        /** The laser's colours: bright enough to read over ink and paper alike, in light or dark. */
        val LASER_COLORS = listOf(0xFFE53935.toInt(), 0xFF2E7D32.toInt(), 0xFF1E88E5.toInt(), 0xFFD81B60.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt())
        /** Finer defaults for math: thin pen, tiny ruler-straight line, compact eraser. */
        fun defaults(tool: Tool) = ToolOptions(
            when (tool) { Tool.HIGHLIGHTER -> InkColors.swatches[2]; Tool.LASER -> LASER_COLORS.first(); else -> InkColors.swatches.first() },
            when (tool) { Tool.HIGHLIGHTER -> 18f; Tool.ERASER -> 26f; in ShapePickerTools -> 2f; else -> 2.2f },
            if (tool == Tool.HIGHLIGHTER) 72f / 255f else 1f, true)
        fun load(prefs: SharedPreferences, tool: Tool): ToolOptions {
            val d = defaults(tool)
            return ToolOptions(prefs.getInt("${tool.name}.color", d.color), prefs.getFloat("${tool.name}.width", d.width),
                prefs.getFloat("${tool.name}.opacity", d.opacity), prefs.getBoolean("${tool.name}.pressure", d.pressure),
                PenPressure.sensitivity(prefs.getFloat("${tool.name}.pressureSensitivity", 1f)),
                PenPressure.variation(prefs.getFloat("${tool.name}.pressureVariation", 1f)),
                try { StrokeStyle.valueOf(prefs.getString("${tool.name}.style", "SOLID") ?: "SOLID") } catch (_: Exception) { StrokeStyle.SOLID },
                PenPressure.curve(prefs.getFloat("${tool.name}.pressureCurve", 1f)))
        }
    }
}

/** Quick switches shown in the editor as a row over the page. */
object EditorQuickPrefs {
    const val ERASER_SINGLE_STROKE = "eraserSingleStroke"
    const val PULL_TO_ADD_PAGE = "pullToAddPage"
    const val ERASER_PRESSURE = "eraserPressure"
    const val SCRIBBLE_TO_ERASE = "scribbleToErase"
    const val SCRIBBLE_SENSITIVITY = "scribbleSensitivity"
    const val SCRIBBLE_HOLD = "scribbleHold"
    const val ERASER_WHOLE_STROKE = "eraserWholeStroke"
    const val SHAPE_MEASUREMENTS = "shapeMeasurements"
    const val MULTI_TOUCH_UNDO = "multiTouchUndo"
}

/** A titled, collapsible group inside the tool popover, so the rarely used controls stay one tap away. */
@Composable private fun PopoverSection(title: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().clip(FolioShapes.small).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }.padding(vertical = FolioSpacing.dp8),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(Icons.Rounded.ExpandMore, if (open) "Collapse $title" else "Expand $title", Modifier.size(20.dp).folioDisclosure(open), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FolioExpand(open) { Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), content = content) }
    }
}

@Composable private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked, onChange, enabled = enabled)
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The tool's settings, laid out for a popover: the live sample, width and opacity first, then
 * colours, and everything else in collapsible sections. Each tool remembers its own values.
 */
@Composable fun ToolOptionsPanel(tool: Tool, options: ToolOptions, onChange: (ToolOptions) -> Unit, quick: QuickColorsState, presets: ToolPresetState? = null, onApplyPreset: ((ToolPreset) -> Unit)? = null) {
    val label = tool.name.lowercase().replaceFirstChar(Char::uppercase)
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("preferences", 0)
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (tool in ShapePickerTools) "Shape settings" else "$label settings", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (tool != Tool.HAND && tool != Tool.LASSO && tool != Tool.TEXT) TextButton({ onChange(ToolOptions.defaults(tool)) }, shapes = ButtonDefaults.shapes()) { Text("Reset") }
        }
        when (tool) {
            Tool.HAND -> Text("Drag to move the document. Pinch anywhere on the document to zoom all pages together.", style = MaterialTheme.typography.bodyMedium)
            Tool.LASSO -> Text("Draw a loop around ink, text and pictures to select them together, then drag the selection to move it. Preview and tidy shapes, copy, duplicate, restyle or delete it from the pill beside the selection; drag its corner handle to resize and its top handle to rotate.", style = MaterialTheme.typography.bodyMedium)
            Tool.STICKY_NOTE -> Text("Tap to drop a sticky note, or drag to draw one to size, on the page or beside it. Drag a note to move it and its corner handle to resize it. Tap a note for its menu, and tap it again to type. Anything you write or draw starting on a note belongs to the note and moves with it; the eraser over a note erases only the note. Notes beside the page stay out of exports.", style = MaterialTheme.typography.bodyMedium)
            Tool.LASER -> {
                Text("Point, circle and underline while you present. The trail fades on its own and nothing is written to the page. A hovering pen shows the pointer too.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    ToolOptions.LASER_COLORS.forEachIndexed { index, c ->
                        val selected = options.color == c
                        Surface(onClick = { onChange(options.copy(color = c)) }, shape = CircleShape, color = Color(c),
                            border = BorderStroke(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                            modifier = Modifier.size(36.dp).semanticsLabel("Laser colour ${index + 1}" + if (selected) ", selected" else "")) {}
                    }
                }
            }
            Tool.TEXT -> Text("Tap the page to type, or tap existing text to edit it in place. Use the toolbar to format it, the upper grip to move it and the lower grip to change its wrap width. Tap outside or Done to finish; Undo reverses the edit.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                // Two short pages instead of one long column of sections: what the ink looks like,
                // then how the tool behaves. Only the open page is composed, so the popover opens fast.
                var page by rememberSaveable(tool) { mutableIntStateOf(0) }
                FolioButtonGroup(Modifier.fillMaxWidth()) {
                    toggleableItem(page == 0, if (tool == Tool.ERASER) "Size" else "Style", { page = 0 }, weight = 1f)
                    toggleableItem(page == 1, "Behaviour", { page = 1 }, weight = 1f)
                }
                if (page == 0) ToolStylePage(tool, options, onChange, quick, presets, onApplyPreset, prefs)
                else ToolBehaviourPage(tool, options, onChange, prefs)
            }
        }
    }
}

@Composable private fun ToolStylePage(
    tool: Tool, options: ToolOptions, onChange: (ToolOptions) -> Unit, quick: QuickColorsState,
    presets: ToolPresetState?, onApplyPreset: ((ToolPreset) -> Unit)?, prefs: SharedPreferences
) {
    val group = WidthPresets.group(tool, options.style)
    val range = WidthPresets.range(group)
    if (tool == Tool.ERASER) StrokeSample(options.width.coerceAtMost(24f), MaterialTheme.colorScheme.outline)
    else InkSample(tool, options.color, options.width, options.opacity, options.style)
    if (StrokeStyle.brushes(tool).isNotEmpty()) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val widths = remember(context) { WidthPresetState(context.getSharedPreferences("ink-tools", 0)) }
        Text("Brush", style = MaterialTheme.typography.labelLarge)
        BrushPicker(tool, options) { onChange(options.withBrush(tool, it, widths)) }
    }
    PopoverSlider(if (tool == Tool.ERASER) "Eraser diameter" else "Width", String.format(Locale.ROOT, "%.1f pt", options.width), options.width, range,
        { onChange(options.copy(width = WidthPresets.clamp(group, it))) })
    if (tool != Tool.ERASER) {
        PopoverSlider("Opacity", "${(options.opacity * 100).roundToInt()}%", options.opacity, 0.05f..1f, { onChange(options.copy(opacity = it)) })
        InkColorsSection(options, onChange, quick, InkColors.groupOf(tool))
    }
    if (tool in ShapePickerTools) {
        Text("Line style", style = MaterialTheme.typography.labelLarge)
        // M3e button group: these are one choice, so they read as connected toggles.
        StrokeStyle.PATTERNS.chunked(3).forEach { row ->
            FolioButtonGroup { row.forEach { style -> toggleableItem(options.style == style, style.label, { onChange(options.copy(style = style)) }) } }
        }
    }
    if (presets != null && tool in DrawingTools) PopoverSection("Presets") { ToolPresetSection(tool, options, presets, onApplyPreset) }
}

/** A preference slider that only writes when the finger lifts, for settings that are costly to apply per frame. */
@Composable private fun CommitSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: (Float) -> String, onCommit: (Float) -> Unit
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    PopoverSlider(label, text(shown), shown, range, { dragging = it }, onFinished = { dragging?.let(onCommit); dragging = null })
}

@Composable private fun ToolBehaviourPage(tool: Tool, options: ToolOptions, onChange: (ToolOptions) -> Unit, prefs: SharedPreferences) {
    if (tool == Tool.PEN) {
        val smoothing by rememberPref(prefs, AppPrefs.PEN_SMOOTHING) { AppPrefs.penSmoothing(it.getFloat(AppPrefs.PEN_SMOOTHING, AppPrefs.DEFAULT_PEN_SMOOTHING)) }
        CommitSlider("Smoothing", smoothing, 0f..1f, { if (it == 0f) "Off" else "${(it * 100).roundToInt()}%" }) {
            prefs.edit().putFloat(AppPrefs.PEN_SMOOTHING, AppPrefs.penSmoothing(it)).apply()
        }
        // Soft ends and speed thinning are the ballpoint's; the other brushes carry their own feel.
        if (options.style == StrokeStyle.SOLID) {
            // Soft ends restyle every ballpoint stroke, so the costly re-render happens once, on release.
            val taper by rememberPref(prefs, AppPrefs.INK_TAPER) { AppPrefs.inkTaper(it.getFloat(AppPrefs.INK_TAPER, 0f)) }
            CommitSlider("Soft stroke ends", taper, 0f..1f, { if (it == 0f) "Off" else "${(it * 100).roundToInt()}%" }) {
                prefs.edit().putFloat(AppPrefs.INK_TAPER, AppPrefs.inkTaper(it)).apply()
            }
            val thinner by rememberPref(prefs, AppPrefs.PEN_SPEED_WIDTH) { it.getBoolean(AppPrefs.PEN_SPEED_WIDTH, AppPrefs.DEFAULT_PEN_SPEED_WIDTH) }
            SwitchRow("Thinner when fast", thinner, { prefs.edit().putBoolean(AppPrefs.PEN_SPEED_WIDTH, it).apply() })
        } else Text(when (options.style) {
            StrokeStyle.FOUNTAIN -> "The fountain pen swells under a firm press and thins on fast strokes."
            StrokeStyle.PENCIL -> "Pressure darkens the pencil."
            StrokeStyle.MARKER -> "The marker lays down one even width, whatever the pressure."
            else -> "The flat nib is thick across and thin along a 45° line; pressure widens it."
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (options.style != StrokeStyle.MARKER) SwitchRow("Pressure-sensitive width", options.pressure, { onChange(options.copy(pressure = it)) })
        if (options.pressure && options.style != StrokeStyle.MARKER) {
            FolioButtonGroup(Modifier.fillMaxWidth()) {
                PenPressure.Feel.entries.forEach { feel ->
                    toggleableItem(PenPressure.Feel.matching(options.pressureCurve, options.pressureSensitivity) == feel, feel.label,
                        { onChange(options.copy(pressureCurve = feel.curve, pressureSensitivity = feel.sensitivity)) }, weight = 1f)
                }
            }
            PopoverSection("Fine-tune pressure") {
                PopoverSlider("Sensitivity", String.format(Locale.ROOT, "%.2f×", options.pressureSensitivity), PenPressure.sensitivity(options.pressureSensitivity),
                    PenPressure.sensitivityRange, { onChange(options.copy(pressureSensitivity = it)) }, description = "Pen pressure sensitivity")
                PopoverSlider("Width variation", "${(options.pressureVariation * 100).roundToInt()}%", PenPressure.variation(options.pressureVariation),
                    PenPressure.variationRange, { onChange(options.copy(pressureVariation = it)) }, description = "Pen pressure width variation")
                PopoverSlider("Response curve", String.format(Locale.ROOT, "%.2f", options.pressureCurve), PenPressure.curve(options.pressureCurve),
                    PenPressure.curveRange, { onChange(options.copy(pressureCurve = it)) }, description = "Pen pressure response curve")
                TextButton({ onChange(options.copy(pressure = true, pressureSensitivity = 1f, pressureVariation = 1f, pressureCurve = 1f)) }, shapes = ButtonDefaults.shapes()) { Text("Reset pressure") }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val lowLatency by rememberPref(prefs, AppPrefs.LOW_LATENCY_INK) { it.getBoolean(AppPrefs.LOW_LATENCY_INK, AppPrefs.DEFAULT_LOW_LATENCY_INK) }
            SwitchRow("Low-latency ink", lowLatency, { prefs.edit().putBoolean(AppPrefs.LOW_LATENCY_INK, it).apply() },
                hint = "Ballpoint ink follows the tip more closely; other brushes predict the tip in their own shape. Turn off if it flickers on lift.")
        }
    }
    if (tool == Tool.HIGHLIGHTER) {
        val tilt by rememberPref(prefs, AppPrefs.HIGHLIGHTER_TILT) { it.getBoolean(AppPrefs.HIGHLIGHTER_TILT, AppPrefs.DEFAULT_HIGHLIGHTER_TILT) }
        SwitchRow("Tilt widens the highlighter", tilt, { prefs.edit().putBoolean(AppPrefs.HIGHLIGHTER_TILT, it).apply() })
    }
    if (tool in ShapePickerTools) {
        val measurements by rememberPref(prefs, EditorQuickPrefs.SHAPE_MEASUREMENTS) { it.getBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, true) }
        SwitchRow("Live measurements", measurements, { prefs.edit().putBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, it).apply() })
        val snap by rememberPref(prefs, "mathSnap") { it.getBoolean("mathSnap", true) }
        SwitchRow("Snap to grid and 15°", snap, { prefs.edit().putBoolean("mathSnap", it).apply() })
        if (tool == Tool.GRAPH) PopoverSection("Graph axes") { GraphStyleSection(options) }
    }
    if (tool == Tool.ERASER) {
        val pressure by rememberPref(prefs, EditorQuickPrefs.ERASER_PRESSURE) { it.getBoolean(EditorQuickPrefs.ERASER_PRESSURE, true) }
        SwitchRow("Pressure-sensitive size", pressure, { prefs.edit().putBoolean(EditorQuickPrefs.ERASER_PRESSURE, it).apply() })
        val whole by rememberPref(prefs, EditorQuickPrefs.ERASER_WHOLE_STROKE) { it.getBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, false) }
        SwitchRow("Erase whole strokes", whole, { prefs.edit().putBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, it).apply() })
        val single by rememberPref(prefs, EditorQuickPrefs.ERASER_SINGLE_STROKE) { it.getBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, false) }
        SwitchRow("Return to previous tool", single, { prefs.edit().putBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, it).apply() },
            hint = "After one eraser stroke.")
    }
    if (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool in ShapePickerTools) PopoverSection("Shape assist") { ShapeAssistSettings() }
    if (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.ERASER) PopoverSection("Scribble to erase") { ScribbleSettingsSection(showPracticeInitially = false) }
}

/**
 * How the graph tool dresses the axes it draws. The choices live in one preference string the
 * editor watches, so a change here lands on the page immediately; the preview is drawn by
 * [GraphAxes] itself, so it can never drift from what a drag produces.
 */
@Composable private fun GraphStyleSection(options: ToolOptions) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("preferences", 0)
    var style by remember { mutableStateOf(GraphStyle.load(prefs)) }
    fun update(next: GraphStyle) { style = next; GraphStyle.save(prefs, next) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Text("Drag to size the diagram. Everything below is drawn as ordinary ink, so it can still be edited and erased by hand.",
            style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(132.dp).clip(FolioShapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .semanticsLabel("Graph preview")) {
            val pad = 14.dp.toPx()
            val draft = Stroke(Tool.GRAPH, options.color, options.width,
                listOf(InkPoint(pad, pad), InkPoint(size.width - pad, size.height - pad)), options.opacity, options.style)
            val ink = Color(options.color).copy(alpha = options.opacity)
            val weight = options.width.dp.toPx().coerceAtLeast(1f)
            GraphAxes.strokes(draft, style).forEach { stroke ->
                stroke.points.zipWithNext().forEach { (from, to) ->
                    drawLine(ink, Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = weight)
                }
            }
        }
        Text("Quick setups", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            GraphStyle.PRESETS.forEach { (name, preset) ->
                // A preset keeps the shape toggle the student chose: it is about the paper, not the graph.
                FilterChip(selected = style == preset.copy(square = style.square), onClick = { update(preset.copy(square = style.square)) }, label = { Text(name) })
            }
        }
        Text("Axes cross", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            GraphOrigin.entries.forEach { origin ->
                FilterChip(selected = style.origin == origin, onClick = { update(style.copy(origin = origin)) }, label = { Text(origin.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Square diagram", Modifier.weight(1f))
            Switch(style.square, { update(style.copy(square = it)) })
        }
        Text("Squares the drag to the shorter side, the way exam graphs are usually drawn.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Divisions per half axis", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            GraphStyle.DIVISIONS.forEach { count ->
                FilterChip(
                    selected = style.divisions == count,
                    onClick = { update(style.copy(divisions = count)) },
                    label = { Text(if (count == 0) "None" else count.toString()) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Square grid cells", Modifier.weight(1f))
            Switch(style.squareCells, { update(style.copy(squareCells = it)) }, enabled = style.divisions > 0)
        }
        if (style.numbers) {
            Text(if (style.piDen > 0) "x axis counts in multiples of" else "Units per division", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                if (style.piDen > 0) {
                    GraphStyle.PI_DENOMINATORS.forEach { den ->
                        FilterChip(selected = style.piDen == den, onClick = { update(style.copy(piDen = den)) },
                            label = { Text(if (den == 1) "π" else "π/$den") })
                    }
                } else {
                    GraphStyle.STEPS.forEach { step ->
                        FilterChip(selected = style.step == step, onClick = { update(style.copy(step = step)) }, label = { Text(graphNumber(step)) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("x axis in π (trigonometry)", Modifier.weight(1f))
                Switch(style.piDen > 0, { update(style.copy(piDen = if (it) 2 else 0)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Different units on y", Modifier.weight(1f))
                Switch(style.stepY > 0.0, { update(style.copy(stepY = if (it) style.step else 0.0)) })
            }
            if (style.stepY > 0.0) {
                Text("y units per division", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    GraphStyle.STEPS.forEach { step ->
                        FilterChip(selected = style.stepY == step, onClick = { update(style.copy(stepY = step)) }, label = { Text(graphNumber(step)) })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Grid lines", Modifier.weight(1f))
            Switch(style.grid, { update(style.copy(grid = it)) }, enabled = style.divisions > 0)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tick marks", Modifier.weight(1f))
            Switch(style.ticks, { update(style.copy(ticks = it)) }, enabled = style.divisions > 0)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Numbers on the axes", Modifier.weight(1f))
            Switch(style.numbers, { update(style.copy(numbers = it)) }, enabled = style.divisions > 0)
        }
        if (style.numbers) Text("Counts outwards from the origin, so the units above set what each division is worth.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("x and y labels", Modifier.weight(1f))
            Switch(style.letters, { update(style.copy(letters = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Label the origin O", Modifier.weight(1f))
            Switch(style.originLabel, { update(style.copy(originLabel = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Arrowheads", Modifier.weight(1f))
            Switch(style.arrows, { update(style.copy(arrows = it)) })
        }
        TextButton({ update(GraphStyle.DEFAULT) }, shapes = ButtonDefaults.shapes()) { Text("Reset graph settings") }
    }
}

/**
 * A round ink swatch, shared by the toolbar quick colours and the colour sheet so every colour in
 * the app is the same circle. The check mark flips to black on light colours so it stays readable.
 * A long-press runs [onLongClick] (usually "more colours") beside the tap that picks the colour.
 */
@Composable fun InkColorDot(color: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, touch: Dp = 40.dp, dot: Dp = 26.dp, label: String = "Ink colour", onLongClick: (() -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier.size(touch).clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick?.let { action -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); action() } })
            .semanticsLabel("$label #${InkColors.hex(color)}${if (selected) ", selected" else ""}"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(dot).folioSelected(selected).background(Color(color), CircleShape).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(dot * 0.55f), tint = if (InkColors.isLight(color)) Color.Black else Color.White)
        }
    }
}

/**
 * The colour controls in the tool sheet: the editable quick row, curated writing inks,
 * custom hex colours and the user's saved presets. [quick] is the same state the toolbar swatches render.
 * [group] keeps the highlighter's row and presets apart from the ink tools'.
 */
@Composable private fun InkColorsSection(options: ToolOptions, onChange: (ToolOptions) -> Unit, quick: QuickColorsState, group: String) {
    val quickColors = quick.colors(group)
    val quickPresets = quick.presets(group)
    var editing by remember { mutableStateOf(false) }
    var slot by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var hex by remember(options.color) { mutableStateOf(InkColors.hex(options.color)) }
    val validHex = hex.length == 6 && hex.all { it in "0123456789abcdefABCDEF" }
    val hexKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    /** A picked colour becomes the ink, and while customising is also stored in the highlighted slot. */
    fun pick(color: Int) {
        if (editing) quick.setSlot(group, slot.coerceIn(0, quickColors.lastIndex), color)
        onChange(options.copy(color = color))
    }

    /** Loading a palette or preset resets the whole row, so leave customising mode behind. */
    fun load(row: List<Int>) {
        editing = false
        onChange(options.copy(color = row.first()))
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp10)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quick colours", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton({ editing = !editing }, shapes = ButtonDefaults.shapes()) { Text(if (editing) "Done" else "Customise") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            quickColors.forEachIndexed { index, color ->
                InkColorDot(
                    color = color,
                    selected = if (editing) index == slot else options.color == color,
                    onClick = { if (editing) slot = index else pick(color) },
                    label = "Quick colour ${index + 1}"
                )
            }
        }
        FolioExpand(editing) {
            Text("Tap a colour below to store it in slot ${slot + 1}, or tap another slot above to change which one you are editing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text("Palettes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
            InkColors.palettes.forEach { palette ->
                FilterChip(
                    selected = quick.palette(group) == palette.name,
                    onClick = { quick.applyPalette(group, palette); load(quick.colors(group)) },
                    label = { Text(palette.name) },
                    leadingIcon = {
                        Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                            palette.colors.take(InkColors.SLOT_COUNT).forEach { Box(Modifier.size(12.dp).background(Color(it), CircleShape).border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)) }
                        }
                    }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "Writing ink for slot ${slot + 1}" else "Writing inks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton({ quick.applyPalette(group, InkColors.defaultPalette); load(quick.colors(group)) }, shapes = ButtonDefaults.shapes()) { Text("Reset quick colours") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            InkColors.namedSwatches.forEach { (name, color) ->
                InkColorDot(color, options.color == color, { pick(color) }, touch = 38.dp, dot = 24.dp, label = "$name #${InkColors.hex(color)}")
            }
        }

        listOf(
            (if (editing) "Vivid ink for slot ${slot + 1}" else "Vivid colours") to InkColors.vividSwatches,
            (if (editing) "Pastel ink for slot ${slot + 1}" else "Pastel colours") to InkColors.pastelSwatches
        ).forEach { (title, swatches) ->
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                swatches.forEach { (name, color) ->
                    InkColorDot(color, options.color == color, { pick(color) }, touch = 38.dp, dot = 24.dp, label = "$name #${InkColors.hex(color)}")
                }
            }
        }

        OutlinedTextField(hex, { value ->
            hex = value.removePrefix("#").take(6)
            if (hex.length == 6 && hex.all { it in "0123456789abcdefABCDEF" }) pick((0xFF000000L or hex.toLong(16)).toInt())
        }, label = { Text("Custom colour (hex)") }, prefix = { Text("#") }, singleLine = true, isError = !validHex,
            supportingText = { if (!validHex) Text("Enter six hexadecimal digits") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                if (validHex) pick((0xFF000000L or hex.toLong(16)).toInt())
                hexKeyboard?.hide()
            }),
            modifier = Modifier.fillMaxWidth())

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saved presets", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton({ presetName = ""; naming = true }, enabled = quickPresets.size < InkColors.MAX_PRESETS || quickPresets.any { it.colors == quickColors }, shapes = ButtonDefaults.shapes()) { Text("Save current") }
        }
        if (quickPresets.isEmpty()) Text("Save your quick colours to bring the same five back in any notebook.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            quickPresets.forEach { preset ->
                val hold = rememberLongPressGuard()
                InputChip(
                    selected = preset.colors == quickColors,
                    onClick = hold.click { quick.applyPreset(group, preset); load(quick.colors(group)) },
                    label = { Text(preset.name) },
                    modifier = Modifier.longPressAction(hold) { quick.deletePreset(group, preset) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Delete ${preset.name}", Modifier.size(18.dp).clickable(role = Role.Button) { quick.deletePreset(group, preset) }) }
                )
            }
        }
    }

    if (naming) AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = { naming = false },
        title = { Text("Save quick colours") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Name this preset so you can bring these colours back in any notebook.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(presetName, { presetName = it.take(InkColors.MAX_PRESET_NAME) }, label = { Text("Preset name") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        if (presetName.isNotBlank()) { quick.savePreset(group, presetName); naming = false }
                    }))
            }
        },
        dismissButton = { TextButton({ naming = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        confirmButton = { TextButton({ quick.savePreset(group, presetName); naming = false }, enabled = presetName.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Save") } }
    )
}

/**
 * Favorite tool setups: saves the current tool's colour, width, opacity and line style or brush under a
 * name, like GoodNotes' pen slots, so a revision black fine-liner and a diagram blue dashed
 * line are each one tap away in any notebook.
 */
@Composable private fun ToolPresetSection(tool: Tool, options: ToolOptions, presets: ToolPresetState, onApply: ((ToolPreset) -> Unit)?) {
    val appSettings = rememberPrefs()
    val layout = rememberToolbarLayoutState(LocalNotebookToolbarPreferences.current ?: appSettings)
    var naming by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    val saved = presets.presets
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tool presets", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton({ presetName = ""; naming = true }, enabled = saved.size < ToolPresets.MAX_PRESETS, shapes = ButtonDefaults.shapes()) { Text("Save current") }
    }
    if (saved.isEmpty()) Text("Save this tool setup as a preset to bring it back in one tap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        saved.forEach { preset ->
            val styleSuffix = if (preset.style != StrokeStyle.SOLID) " · ${preset.style.brushLabel(preset.tool).lowercase()}" else ""
            val hold = rememberLongPressGuard()
            InputChip(
                selected = preset.tool == tool && preset.color == options.color && preset.width == options.width && preset.style == options.style,
                onClick = hold.click { onApply?.invoke(preset) },
                label = { Text("${preset.name} · ${preset.tool.name.lowercase()}$styleSuffix") },
                                trailingIcon = { Row {
                    IconButton({ layout.togglePin(preset.id) }) { Icon(Icons.Rounded.PushPin, if (preset.id in layout.layout.pinnedPresetIds) "Unpin ${preset.name}" else "Pin ${preset.name} to toolbar", tint = if (preset.id in layout.layout.pinnedPresetIds) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton({ presets.delete(preset.id) }) { Icon(Icons.Rounded.Close, "Delete ${preset.name}") }
                } }
            )
        }
        Text("Tap to apply. Pin a preset here to keep it on the toolbar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (naming) AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = { naming = false },
        title = { Text("Save tool preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Saves colour, width, opacity and ${if (StrokeStyle.brushes(tool).isEmpty()) "line style" else "brush"} for the ${tool.name.lowercase()} tool.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(presetName, { presetName = it.take(ToolPresets.MAX_NAME) }, label = { Text("Preset name") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        if (presetName.isNotBlank() && presets.save(presetName, tool, options, options.style)) naming = false
                    }))
            }
        },
        dismissButton = { TextButton({ naming = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        confirmButton = {
            TextButton({
                if (presets.save(presetName, tool, options, options.style)) naming = false
            }, enabled = presetName.isNotBlank(),
                shapes = ButtonDefaults.shapes()) { Text("Save") }
        }
    )
}
