@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

data class ToolOptions(
    val color: Int, val width: Float, val opacity: Float, val pressure: Boolean,
    val pressureSensitivity: Float = 1f, val pressureVariation: Float = 1f,
    val style: StrokeStyle = StrokeStyle.SOLID
) {
    fun save(prefs: SharedPreferences, tool: Tool) {
        prefs.edit().putInt("${tool.name}.color", color).putFloat("${tool.name}.width", width)
            .putFloat("${tool.name}.opacity", opacity).putBoolean("${tool.name}.pressure", pressure)
            .putFloat("${tool.name}.pressureSensitivity", PenPressure.sensitivity(pressureSensitivity))
            .putFloat("${tool.name}.pressureVariation", PenPressure.variation(pressureVariation))
            .putString("${tool.name}.style", style.name).apply()
    }
    companion object {
        /** Finer defaults for math: thin pen, tiny ruler-straight line, compact eraser. */
        fun defaults(tool: Tool) = ToolOptions(
            if (tool == Tool.HIGHLIGHTER) InkColors.swatches[2] else InkColors.swatches.first(),
            when (tool) { Tool.HIGHLIGHTER -> 18f; Tool.ERASER -> 26f; in ShapePickerTools -> 2f; else -> 2.2f },
            if (tool == Tool.HIGHLIGHTER) 72f / 255f else 1f, true)
        fun load(prefs: SharedPreferences, tool: Tool): ToolOptions {
            val d = defaults(tool)
            return ToolOptions(prefs.getInt("${tool.name}.color", d.color), prefs.getFloat("${tool.name}.width", d.width),
                prefs.getFloat("${tool.name}.opacity", d.opacity), prefs.getBoolean("${tool.name}.pressure", d.pressure),
                PenPressure.sensitivity(prefs.getFloat("${tool.name}.pressureSensitivity", 1f)),
                PenPressure.variation(prefs.getFloat("${tool.name}.pressureVariation", 1f)),
                try { StrokeStyle.valueOf(prefs.getString("${tool.name}.style", "SOLID") ?: "SOLID") } catch (_: Exception) { StrokeStyle.SOLID })
        }
    }
}

/** Quick switches shown in the editor as a row over the page. */
object EditorQuickPrefs {
    const val ERASER_SINGLE_STROKE = "eraserSingleStroke"
    const val ERASER_PRESSURE = "eraserPressure"
    const val SCRIBBLE_TO_ERASE = "scribbleToErase"
    const val SCRIBBLE_SENSITIVITY = "scribbleSensitivity"
    const val ERASER_WHOLE_STROKE = "eraserWholeStroke"
    const val SHAPE_MEASUREMENTS = "shapeMeasurements"
    const val MULTI_TOUCH_UNDO = "multiTouchUndo"
}

@Composable fun ToolOptionsPanel(tool: Tool, options: ToolOptions, onChange: (ToolOptions) -> Unit, quick: QuickColorsState, presets: ToolPresetState? = null) {
    val label = tool.name.lowercase().replaceFirstChar(Char::uppercase)
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("preferences", 0)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(FolioSpacing.dp24), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
        Text("$label settings", style = MaterialTheme.typography.headlineSmall)
        if (tool == Tool.HAND) {
            Text("Drag to move the document. Pinch anywhere on the document to zoom all pages together.")
        } else if (tool == Tool.LASSO) {
            Text("Draw a loop around ink, text and pictures to select them together, then drag the selection to move it. Copy, duplicate, restyle or delete it from the pill beside the selection; drag its corner handle to resize and its top handle to rotate.")
        } else if (tool == Tool.TEXT) {
            Text("Tap the page to write a heading or a label. Tap a box to edit it or drag it to move it. Text sits on top of your ink and travels with the page.")
        } else {
            Text("Saved independently for this tool.", style = MaterialTheme.typography.bodySmall)
            if (tool != Tool.ERASER) InkColorsSection(options, onChange, quick, InkColors.groupOf(tool))
            val range = when (tool) { Tool.ERASER -> 4f..72f; Tool.HIGHLIGHTER -> 4f..48f; in ShapePickerTools -> 0.7f..10f; else -> 0.7f..12f }
            Text("${if (tool == Tool.ERASER) "Eraser diameter" else "Stroke width"}: ${String.format(Locale.ROOT, "%.1f", options.width)} pt")
            Slider(options.width.coerceIn(range), { onChange(options.copy(width = it)) }, valueRange = range)
            if (tool != Tool.ERASER) {
                Text("Opacity: ${(options.opacity * 100).roundToInt()}%")
                Slider(options.opacity, { onChange(options.copy(opacity = it)) }, valueRange = 0.05f..1f)
                // Preview uses the same width, color and opacity as the selected tool.
                Box(Modifier.fillMaxWidth().height(72.dp).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth(0.8f).height(options.width.dp).background(Color(options.color).copy(alpha = options.opacity), CircleShape))
                }
            } else {
                Text("Cuts only the ink it touches, including shape outlines. Enable Whole-stroke eraser to remove an entire stroke or shape.", style = MaterialTheme.typography.bodySmall)
                var eraserPressure by remember { mutableStateOf(prefs.getBoolean(EditorQuickPrefs.ERASER_PRESSURE, true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pressure-sensitive size", Modifier.weight(1f))
                    Switch(eraserPressure, {
                        eraserPressure = it
                        prefs.edit().putBoolean(EditorQuickPrefs.ERASER_PRESSURE, it).apply()
                    })
                }
                Text("Slightly grows with stronger pressure (about ±12%).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tool == Tool.PEN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pressure-sensitive width", Modifier.weight(1f))
                    Switch(options.pressure, { onChange(options.copy(pressure = it)) })
                }
                Text("Sensitivity: ${String.format(Locale.ROOT, "%.2f", options.pressureSensitivity)}×")
                Slider(PenPressure.sensitivity(options.pressureSensitivity), { onChange(options.copy(pressureSensitivity = it)) },
                    enabled = options.pressure, valueRange = PenPressure.sensitivityRange,
                    modifier = Modifier.semanticsLabel("Pen pressure sensitivity"))
                Text("Higher sensitivity needs less pressure for thicker ink.", style = MaterialTheme.typography.bodySmall)
                Text("Width variation: ${(options.pressureVariation * 100).roundToInt()}%")
                Slider(PenPressure.variation(options.pressureVariation), { onChange(options.copy(pressureVariation = it)) },
                    enabled = options.pressure, valueRange = PenPressure.variationRange,
                    modifier = Modifier.semanticsLabel("Pen pressure width variation"))
                Text("0% keeps pressure width constant; 100% is the original response. Applies to new pen strokes only.", style = MaterialTheme.typography.bodySmall)
                TextButton({ onChange(options.copy(pressure = true, pressureSensitivity = 1f, pressureVariation = 1f)) }, shapes = ButtonDefaults.shapes()) {
                    Text("Reset pen pressure")
                }
                // Thin “exam” preset — one tap to get a crisp 1.4 pt pen used for workings.
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip({ onChange(options.copy(width = 1.4f, pressure = false)) }, { Text("Exam fine (1.4)") })
                    AssistChip({ onChange(options.copy(width = 2.2f, pressure = true, pressureSensitivity = 1f, pressureVariation = 1f)) }, { Text("Default (2.2)") })
                    AssistChip({ onChange(options.copy(width = 4f, pressure = false)) }, { Text("Bold (4.0)") })
                }
            }
            if (tool in ShapePickerTools) {
                if (tool == Tool.GRAPH) GraphStyleSection(options)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                    AssistChip({ onChange(options.copy(width = 1.2f)) }, { Text("Hairline") })
                    AssistChip({ onChange(options.copy(width = 2f)) }, { Text("Regular") })
                    AssistChip({ onChange(options.copy(width = 3.5f)) }, { Text("Heavy") })
                }
                Text("Line style", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // M3e button group: these three are one choice, so they read as connected toggles.
                FolioButtonGroup {
                    toggleableItem(options.style == StrokeStyle.SOLID, "Solid", { onChange(options.copy(style = StrokeStyle.SOLID)) })
                    toggleableItem(options.style == StrokeStyle.DASHED, "Dashed", { onChange(options.copy(style = StrokeStyle.DASHED)) })
                    toggleableItem(options.style == StrokeStyle.DOTTED, "Dotted", { onChange(options.copy(style = StrokeStyle.DOTTED)) })
                }
                Text("Dashed and dotted lines suit diagrams and maths sketches. Freehand pen stays solid.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Lines snap to 15° and to grid on Maths/Grid/Graph paper. Toggle snap in the editor.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (presets != null && tool in DrawingTools) {
                ToolPresetSection(tool, options, presets)
            }
            if (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.ERASER) {
                ScribbleSettingsSection(showPracticeInitially = false)
            }
            if (tool == Tool.ERASER) {
                var single by remember { mutableStateOf(prefs.getBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Single-stroke eraser", Modifier.weight(1f))
                    Switch(single, {
                        single = it
                        prefs.edit().putBoolean(EditorQuickPrefs.ERASER_SINGLE_STROKE, it).apply()
                    })
                }
                Text("When on, one eraser stroke then returns to the previous tool.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var whole by remember { mutableStateOf(prefs.getBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, false)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Whole-stroke eraser", Modifier.weight(1f))
                    Switch(whole, {
                        whole = it
                        prefs.edit().putBoolean(EditorQuickPrefs.ERASER_WHOLE_STROKE, it).apply()
                    })
                }
                Text("When on, touching any part of a stroke removes the entire stroke instead of cutting it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tool in ShapePickerTools) {
                var measurements by remember { mutableStateOf(prefs.getBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live measurements", Modifier.weight(1f))
                    Switch(measurements, {
                        measurements = it
                        prefs.edit().putBoolean(EditorQuickPrefs.SHAPE_MEASUREMENTS, it).apply()
                    })
                }
                Text("Shows length/angle or width×height while drawing the shape.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tool == Tool.LASSO) {
                var tapUndo by remember { mutableStateOf(prefs.getBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Two-finger tap undo", Modifier.weight(1f))
                    Switch(tapUndo, {
                        tapUndo = it
                        prefs.edit().putBoolean(EditorQuickPrefs.MULTI_TOUCH_UNDO, it).apply()
                    })
                }
                Text("Two fingers: undo, three fingers: redo — on the page canvas (not the toolbar).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({ onChange(ToolOptions.defaults(tool)) }, shapes = ButtonDefaults.shapes()) { Text("Reset $label settings") }
        }
    }
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
        Text("Origin", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            AssistChip({ update(style.copy(origin = GraphOrigin.CENTRE)) }, { Text("Centre") },
                leadingIcon = if (style.origin == GraphOrigin.CENTRE) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null)
            AssistChip({ update(style.copy(origin = GraphOrigin.CORNER)) }, { Text("Bottom left") },
                leadingIcon = if (style.origin == GraphOrigin.CORNER) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null)
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
        if (style.numbers) {
            Text("Units per division", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                GraphStyle.STEPS.forEach { step ->
                    AssistChip({ update(style.copy(step = step)) }, { Text(if (step == 1) "1" else "$step") },
                        leadingIcon = if (style.step == step) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null)
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
        if (style.numbers) Text("Counts 1, 2, 3 … outwards from the origin, so the step above sets what each division is worth.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("x and y labels", Modifier.weight(1f))
            Switch(style.letters, { update(style.copy(letters = it)) })
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "Writing ink for slot ${slot + 1}" else "Writing inks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton({ quick.applyPalette(group, InkColors.defaultPalette); load(quick.colors(group)) }, shapes = ButtonDefaults.shapes()) { Text("Reset quick colours") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            InkColors.namedSwatches.forEach { (name, color) ->
                InkColorDot(color, options.color == color, { pick(color) }, touch = 38.dp, dot = 24.dp, label = "$name #${InkColors.hex(color)}")
            }
        }

        OutlinedTextField(hex, { value ->
            hex = value.removePrefix("#").take(6)
            if (hex.length == 6 && hex.all { it in "0123456789abcdefABCDEF" }) pick((0xFF000000L or hex.toLong(16)).toInt())
        }, label = { Text("Custom colour (hex)") }, prefix = { Text("#") }, singleLine = true, isError = !validHex,
            supportingText = { if (!validHex) Text("Enter six hexadecimal digits") }, modifier = Modifier.fillMaxWidth())

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
                    trailingIcon = { Icon(Icons.Rounded.Close, "Delete ${preset.name}", Modifier.size(16.dp).clickable { quick.deletePreset(group, preset) }) }
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
                OutlinedTextField(presetName, { presetName = it.take(InkColors.MAX_PRESET_NAME) }, label = { Text("Preset name") }, singleLine = true)
            }
        },
        dismissButton = { TextButton({ naming = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        confirmButton = { TextButton({ quick.savePreset(group, presetName); naming = false }, enabled = presetName.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Save") } }
    )
}

/**
 * Favorite tool setups: saves the current tool's colour, width, opacity and line style under a
 * name, like GoodNotes' pen slots, so a revision black fine-liner and a diagram blue dashed
 * line are each one tap away in any notebook.
 */
@Composable private fun ToolPresetSection(tool: Tool, options: ToolOptions, presets: ToolPresetState) {
    var naming by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    val saved = presets.presets
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tool presets", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton({ presetName = ""; naming = true }, enabled = saved.size < ToolPresets.MAX_PRESETS, shapes = ButtonDefaults.shapes()) { Text("Save current") }
    }
    if (saved.isEmpty()) Text("Save this tool setup as a preset to bring it back in one tap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        saved.forEach { preset ->
            val styleSuffix = if (preset.style != StrokeStyle.SOLID) " · ${preset.style.name.lowercase()}" else ""
            val hold = rememberLongPressGuard()
            InputChip(
                selected = preset.tool == tool && preset.color == options.color && preset.width == options.width,
                onClick = hold.click { },
                label = { Text("${preset.name} · ${preset.tool.name.lowercase()}$styleSuffix") },
                modifier = Modifier.longPressAction(hold) { presets.delete(preset.id) },
                trailingIcon = { Icon(Icons.Rounded.Close, "Delete ${preset.name}", Modifier.size(16.dp).clickable { presets.delete(preset.id) }) }
            )
        }
        Text("Apply a preset from the toolbar's More menu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (naming) AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.guardUiTouches(),
        onDismissRequest = { naming = false },
        title = { Text("Save tool preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
                Text("Saves colour, width, opacity and line style for the ${tool.name.lowercase()} tool.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(presetName, { presetName = it.take(ToolPresets.MAX_NAME) }, label = { Text("Preset name") }, singleLine = true)
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
