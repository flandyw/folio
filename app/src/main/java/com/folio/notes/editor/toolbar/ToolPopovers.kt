@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.editor.toolbar

import android.content.SharedPreferences
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.folio.notes.library.semanticsLabel
import com.folio.notes.ink.InkRenderer
import com.folio.notes.model.InkPoint
import com.folio.notes.model.ShapePickerTools
import com.folio.notes.model.Stroke
import com.folio.notes.model.StrokeStyle
import com.folio.notes.model.Tool
import com.folio.notes.ui.FolioPopover
import com.folio.notes.ui.FolioPopoverSurface
import com.folio.notes.ui.FolioShapes
import com.folio.notes.ui.FolioSpacing
import com.folio.notes.ui.folioSpring
import java.util.Locale
import kotlin.math.abs

/**
 * Per-tool-family widths behind the three width dots in the secondary toolbar. Pure rules (no
 * Android types) so the clamping and decoding are cheap to exercise; [WidthPresetState] persists them.
 */
object WidthPresets {
    const val COUNT = 3
    const val PEN = "PEN"
    const val HIGHLIGHTER = "HIGHLIGHTER"
    const val ERASER = "ERASER"
    const val SHAPE = "SHAPE"

    /** Each pen brush, the highlighter, the eraser and the shape family keep their own three widths. */
    fun group(tool: Tool, style: StrokeStyle = StrokeStyle.SOLID): String = when {
        tool == Tool.ERASER -> ERASER
        tool == Tool.HIGHLIGHTER -> HIGHLIGHTER
        tool in ShapePickerTools -> SHAPE
        style.isBrush && style in StrokeStyle.brushes(tool) -> "$PEN.${style.name}"
        else -> PEN
    }

    /** Every group, so stored rows load up front rather than during composition. */
    val groups: List<String> = listOf(PEN, HIGHLIGHTER, ERASER, SHAPE) +
        StrokeStyle.brushes(Tool.PEN).filter { it.isBrush }.map { group(Tool.PEN, it) }

    fun range(group: String): ClosedFloatingPointRange<Float> = when (group) {
        ERASER -> 4f..72f
        HIGHLIGHTER -> 4f..48f
        SHAPE -> 0.7f..10f
        else -> 0.7f..12f
    }

    fun defaults(group: String): List<Float> = when (group) {
        HIGHLIGHTER -> listOf(12f, 18f, 28f)
        ERASER -> listOf(14f, 26f, 42f)
        SHAPE -> listOf(1.2f, 2f, 3.5f)
        "$PEN.${StrokeStyle.FOUNTAIN.name}" -> listOf(1.6f, 2.6f, 4f)
        "$PEN.${StrokeStyle.PENCIL.name}" -> listOf(1.2f, 2f, 3.2f)
        "$PEN.${StrokeStyle.MARKER.name}", "$PEN.${StrokeStyle.CALLIGRAPHY.name}" -> listOf(3f, 5f, 8f)
        else -> listOf(1.4f, 2.2f, 3.5f)
    }

    /** Rounds to a tenth so the slider never stores 2.2000003, and keeps the value in range. */
    fun clamp(group: String, width: Float): Float {
        val range = range(group)
        return (Math.round(width.coerceIn(range.start, range.endInclusive) * 10f) / 10f).coerceIn(range.start, range.endInclusive)
    }

    fun encode(widths: List<Float>): String = widths.joinToString(",") { String.format(Locale.ROOT, "%.1f", it) }

    fun decode(group: String, value: String?): List<Float> {
        val base = defaults(group)
        val parsed = value?.split(',')?.mapNotNull { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) }.orEmpty()
        return (0 until COUNT).map { index -> clamp(group, parsed.getOrNull(index) ?: base[index]) }
    }

    /** The slot a width belongs to, or null when it was set freely with the slider. */
    fun selectedIndex(widths: List<Float>, width: Float): Int? {
        val nearest = widths.indices.minByOrNull { abs(widths[it] - width) } ?: return null
        return nearest.takeIf { abs(widths[it] - width) < widths[it] * 0.15f }
    }
}

@Stable
class WidthPresetState(private val prefs: SharedPreferences) {
    private val rows = mutableStateMapOf<String, List<Float>>()

    init {
        WidthPresets.groups.forEach { group ->
            rows[group] = WidthPresets.decode(group, prefs.getString(key(group), null))
        }
    }

    fun widths(tool: Tool, style: StrokeStyle = StrokeStyle.SOLID): List<Float> =
        rows[WidthPresets.group(tool, style)] ?: WidthPresets.defaults(WidthPresets.group(tool, style))

    fun set(tool: Tool, index: Int, width: Float, style: StrokeStyle = StrokeStyle.SOLID) {
        val group = WidthPresets.group(tool, style)
        val current = widths(tool, style)
        if (index !in current.indices) return
        persist(group, current.toMutableList().also { it[index] = WidthPresets.clamp(group, width) })
    }

    fun reset(tool: Tool, index: Int, style: StrokeStyle = StrokeStyle.SOLID) {
        set(tool, index, WidthPresets.defaults(WidthPresets.group(tool, style))[index], style)
    }

    private fun persist(group: String, widths: List<Float>) {
        rows[group] = widths
        prefs.edit().putString(key(group), WidthPresets.encode(widths)).apply()
    }

    private fun key(group: String) = "widthPresets.$group"
}

/** Centres under the anchor, flipping above when there is no room beneath, and stays inside the window. */
internal class PopoverPositionProvider(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, maxX)
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - popupContentSize.height
        val fitsBelow = below + popupContentSize.height <= windowSize.height - margin
        val y = when {
            fitsBelow -> below
            above >= margin -> above
            else -> (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin)
        }
        return IntOffset(x, y)
    }
}

/**
 * A Goodnotes-style popover: a small elevated card that opens from the control it belongs to and
 * dismisses on any tap outside. Place it inside the anchor's `Box`; it measures that box.
 */
@Composable internal fun FolioPopover(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 320.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val provider = remember(density) { with(density) { PopoverPositionProvider(8.dp.roundToPx(), 8.dp.roundToPx()) } }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, folioSpring(), label = "popover")
    val configuration = LocalConfiguration.current
    // Keep palettes anchored in every window size; bound them to the usable window.
    val maxHeight = (configuration.screenHeightDp * 0.72f).dp
    val availableWidth = (configuration.screenWidthDp.dp - FolioSpacing.dp32).coerceAtLeast(0.dp)
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        FolioPopoverSurface(
            modifier = modifier.width(minOf(width, availableWidth)).heightIn(max = maxHeight),
            progress = progress,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), content = content)
        }
    }
}

/** A labelled slider row that shows its value, used for width and opacity in popovers. */
@Composable internal fun PopoverSlider(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, enabled: Boolean = true, description: String = label, onFinished: (() -> Unit)? = null
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.coerceIn(range), onChange, valueRange = range, enabled = enabled, onValueChangeFinished = onFinished, modifier = Modifier.semanticsLabel(description))
    }
}

/**
 * A sample written by the real renderer in the tool's colour, width, opacity and brush, so the
 * preview is exactly what the pen lays down: pressure swell, taper, texture and nib angle included.
 * Widths are page units drawn at one dp each, like the toolbar's width dots.
 */
@Composable internal fun InkSample(
    tool: Tool, color: Int, width: Float, opacity: Float, style: StrokeStyle,
    modifier: Modifier = Modifier, height: Dp = 56.dp,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceContainerLowest, FolioShapes.medium)) {
        val w = maxWidth.value; val h = maxHeight.value
        val stroke = remember(tool, color, width, opacity, style, w, h) {
            val drawn = width.coerceIn(.5f, h * .6f)
            val pad = drawn / 2f + 8f
            val swing = ((h - 2f * pad) / 2f).coerceAtLeast(0f)
            // One wave whose pressure rises and eases off, so pressure, taper and texture all show.
            Stroke(tool, color, drawn, (0..48).map { i ->
                val t = i / 48f
                val y = if (tool == Tool.HIGHLIGHTER) h / 2f else h / 2f + swing * kotlin.math.sin(t * 2f * Math.PI.toFloat())
                InkPoint(pad + t * (w - 2f * pad), y, .45f + .95f * kotlin.math.sin(t * Math.PI.toFloat()))
            }, opacity, style)
        }
        val rendered = remember(stroke) { InkRenderer.rendered(stroke) }
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save(); native.scale(density, density)
                InkRenderer.drawRendered(native, stroke, rendered)
                native.restore()
            }
        }
    }
}

/** The toolbar and picker glyph for [style] on [tool]. */
internal fun brushIcon(tool: Tool, style: StrokeStyle): ImageVector = when {
    tool == Tool.HIGHLIGHTER -> ToolIcons.Highlighter
    style == StrokeStyle.FOUNTAIN -> Icons.Rounded.HistoryEdu
    style == StrokeStyle.PENCIL -> Icons.Rounded.Draw
    style == StrokeStyle.MARKER -> Icons.Rounded.BorderColor
    style == StrokeStyle.CALLIGRAPHY -> Icons.Rounded.Brush
    else -> Icons.Rounded.Edit
}

/** [brush] keeps the current width when it is one of the brush's own slots, else starts on its middle one. */
internal fun ToolOptions.withBrush(tool: Tool, brush: StrokeStyle, widths: WidthPresetState): ToolOptions {
    val slots = widths.widths(tool, brush)
    return copy(style = brush, width = if (WidthPresets.selectedIndex(slots, width) != null) width else slots[1])
}

/** One card per brush the tool offers, each showing that brush's own ink in the current colour. */
@Composable internal fun BrushPicker(tool: Tool, options: ToolOptions, onPick: (StrokeStyle) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp6)) {
        StrokeStyle.brushes(tool).forEach { brush ->
            val selected = options.style == brush
            Surface(onClick = { onPick(brush) }, shape = FolioShapes.medium,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.width(if (tool == Tool.HIGHLIGHTER) 120.dp else 76.dp)
                    .semanticsLabel("${brush.brushLabel(tool)} brush" + if (selected) ", selected" else "")) {
                Column(Modifier.padding(FolioSpacing.dp4), horizontalAlignment = Alignment.CenterHorizontally) {
                    val group = WidthPresets.group(tool, brush)
                    InkSample(tool, options.color, WidthPresets.defaults(group)[1].coerceAtMost(if (tool == Tool.HIGHLIGHTER) 16f else 6f),
                        options.opacity, brush, height = 36.dp)
                    Row(Modifier.padding(top = FolioSpacing.dp4), verticalAlignment = Alignment.CenterVertically) {
                        Icon(brushIcon(tool, brush), null, Modifier.size(14.dp))
                        Text(brush.brushLabel(tool), Modifier.padding(start = FolioSpacing.dp2), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A thick/thin sample stroke in the tool's own colour and opacity, so a slider change is visible at once. */
@Composable internal fun StrokeSample(width: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest, FolioShapes.medium), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth(0.8f).height(width.coerceIn(1f, 48f).dp).background(color, CircleShape))
    }
}

/** Colours offered when filling a quick slot: the group's palette, then the rest of the writing inks. */
private val ExtraInks: List<Int> = listOf(
    0xFF000000, 0xFFD32F2F, 0xFFEF6C00, 0xFFF9A825, 0xFF2E7D32, 0xFF00838F, 0xFF1565C0, 0xFF6A1B9A, 0xFFC2185B, 0xFF6D4C41, 0xFF757575, 0xFFFFFFFF
).map { it.toInt() }

/**
 * Edits one quick-colour slot: tap a swatch, type a hex value or drag the hue/shade sliders.
 * Every pick is written to the slot straight away ([onPick]), so there is nothing to confirm.
 */
@Composable internal fun ColorSlotPopover(
    group: String, slot: Int, color: Int, onPick: (Int) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit
) {
    var hex by remember(color) { mutableStateOf(InkColors.hex(color)) }
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var hue by remember(color) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(color) { mutableFloatStateOf(hsv[1]) }
    var value by remember(color) { mutableFloatStateOf(hsv[2]) }
    fun pushHsv() = onPick(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) or (0xFF shl 24))
    val validHex = hex.length == 6 && hex.all { it in "0123456789abcdefABCDEF" }
    val hexKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val swatches = (InkColors.paletteFor(group).colors + InkColors.swatches + ExtraInks).distinct()

    FolioPopover(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp12)) {
            Box(Modifier.size(32.dp).background(Color(color), CircleShape).then(Modifier.semanticsLabel("Current colour #${InkColors.hex(color)}")))
            Text("Quick colour ${slot + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onReset, shapes = ButtonDefaults.shapes()) { Text("Reset") }
        }
        FlowRowCompat(swatches, color, onPick)
        PopoverSlider("Hue", "${hue.toInt()}°", hue, 0f..360f, { hue = it; pushHsv() }, description = "Hue")
        PopoverSlider("Saturation", "${(sat * 100).toInt()}%", sat, 0f..1f, { sat = it; pushHsv() }, description = "Saturation")
        PopoverSlider("Brightness", "${(value * 100).toInt()}%", value, 0f..1f, { value = it; pushHsv() }, description = "Brightness")
        OutlinedTextField(hex, { input ->
            hex = input.removePrefix("#").take(6)
            if (hex.length == 6 && hex.all { it in "0123456789abcdefABCDEF" }) onPick((0xFF000000L or hex.toLong(16)).toInt())
        }, label = { Text("Hex") }, prefix = { Text("#") }, singleLine = true, isError = !validHex,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                if (hex.length == 6 && hex.all { it in "0123456789abcdefABCDEF" }) onPick((0xFF000000L or hex.toLong(16)).toInt())
                hexKeyboard?.hide()
            }),
            modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FlowRowCompat(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        colors.forEach { c -> InkColorDot(c, selected == c, { onPick(c) }, touch = 44.dp, dot = 28.dp) }
    }
}

/** Edits one of the three width dots: a slider with a live sample, applied as you drag. */
@Composable internal fun WidthSlotPopover(
    tool: Tool, slot: Int, width: Float, options: ToolOptions, onPick: (Float) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit
) {
    val group = WidthPresets.group(tool, options.style)
    val range = WidthPresets.range(group)
    val label = if (tool == Tool.ERASER) "Eraser size" else "Stroke width"
    FolioPopover(onDismiss, width = 300.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label ${slot + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onReset, shapes = ButtonDefaults.shapes()) { Text("Reset") }
        }
        if (tool == Tool.ERASER) StrokeSample(4f, MaterialTheme.colorScheme.outline)
        else InkSample(tool, options.color, width, options.opacity, options.style)
        PopoverSlider(label, String.format(Locale.ROOT, "%.1f pt", width), width, range, { onPick(WidthPresets.clamp(group, it)) })
    }
}
