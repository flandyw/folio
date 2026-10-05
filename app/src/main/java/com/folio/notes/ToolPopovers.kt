@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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

    /** Pen, highlighter, eraser and the shape family each keep their own three widths. */
    fun group(tool: Tool): String = when {
        tool == Tool.ERASER -> ERASER
        tool == Tool.HIGHLIGHTER -> HIGHLIGHTER
        tool in ShapePickerTools -> SHAPE
        else -> PEN
    }

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
        listOf(WidthPresets.PEN, WidthPresets.HIGHLIGHTER, WidthPresets.ERASER, WidthPresets.SHAPE).forEach { group ->
            rows[group] = WidthPresets.decode(group, prefs.getString(key(group), null))
        }
    }

    fun widths(tool: Tool): List<Float> = rows[WidthPresets.group(tool)] ?: WidthPresets.defaults(WidthPresets.group(tool))

    fun set(tool: Tool, index: Int, width: Float) {
        val group = WidthPresets.group(tool)
        val current = widths(tool)
        if (index !in current.indices) return
        persist(group, current.toMutableList().also { it[index] = WidthPresets.clamp(group, width) })
    }

    fun reset(tool: Tool, index: Int) {
        val group = WidthPresets.group(tool)
        set(tool, index, WidthPresets.defaults(group)[index])
    }

    private fun persist(group: String, widths: List<Float>) {
        rows[group] = widths
        prefs.edit().putString(key(group), WidthPresets.encode(widths)).apply()
    }

    private fun key(group: String) = "widthPresets.$group"
}

/** Centres under the anchor, flipping above when there is no room beneath, and stays inside the window. */
private class PopoverPositionProvider(private val margin: Int, private val gap: Int) : PopupPositionProvider {
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
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            modifier = modifier.guardUiTouches().graphicsLayer {
                alpha = progress.coerceIn(0f, 1f)
                scaleX = .94f + .06f * progress; scaleY = scaleX
                transformOrigin = TransformOrigin(.5f, 0f)
            }.width(width).heightIn(max = maxHeight),
            shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp, shadowElevation = 12.dp
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(FolioSpacing.dp16), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp12), content = content)
        }
    }
}

/** A labelled slider row that shows its value, used for width and opacity in popovers. */
@Composable internal fun PopoverSlider(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, enabled: Boolean = true, description: String = label
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.coerceIn(range), onChange, valueRange = range, enabled = enabled, modifier = Modifier.semanticsLabel(description))
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
        }, label = { Text("Hex") }, prefix = { Text("#") }, singleLine = true, isError = !validHex, modifier = Modifier.fillMaxWidth())
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
    tool: Tool, slot: Int, width: Float, color: Color, onPick: (Float) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit
) {
    val range = WidthPresets.range(WidthPresets.group(tool))
    val label = if (tool == Tool.ERASER) "Eraser size" else "Stroke width"
    FolioPopover(onDismiss, width = 300.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label ${slot + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onReset, shapes = ButtonDefaults.shapes()) { Text("Reset") }
        }
        StrokeSample(if (tool == Tool.ERASER) 4f else width, if (tool == Tool.ERASER) MaterialTheme.colorScheme.outline else color)
        PopoverSlider(label, String.format(Locale.ROOT, "%.1f pt", width), width, range, { onPick(WidthPresets.clamp(WidthPresets.group(tool), it)) })
    }
}
