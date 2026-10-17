@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.*

/** One set of controls in Settings and the pen palette, with a real, disposable drawing surface. */
@Composable internal fun ShapeAssistSettings(showPracticeInitially: Boolean = false) {
    val prefs = rememberPrefs()
    val enabled by rememberPref(prefs, "shapeRecognition") { it.getBoolean("shapeRecognition", false) }
    val delay by rememberPref(prefs, AppPrefs.SHAPE_HOLD_MS) { AppPrefs.shapeHoldMs(it.getLong(AppPrefs.SHAPE_HOLD_MS, AppPrefs.DEFAULT_SHAPE_HOLD_MS)) }
    val options by rememberPref(prefs, AppPrefs.SHAPE_ASSIST_OPTIONS) { ShapeAssistOptions.decode(it.getString(AppPrefs.SHAPE_ASSIST_OPTIONS, null)) }
    var practice by rememberSaveable { mutableStateOf(showPracticeInitially) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tidy up shapes", style = MaterialTheme.typography.titleSmall)
                Text("Draw → hold → lift", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(enabled, { prefs.edit().putBoolean("shapeRecognition", it).apply() }, modifier = Modifier.semanticsLabel("Tidy up shapes"))
        }
        ShapeAssistExample()
        Text("Pause gently to tidy a shape; small wobbles are fine. Then move the pen to resize or rotate it, and lift to keep it. Undo brings your drawing back.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled) {
            Text("Hold speed", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Quick" to 350L, "Balanced" to AppPrefs.DEFAULT_SHAPE_HOLD_MS, "Deliberate" to 1100L).forEach { (label, ms) ->
                    FilterChip(delay == ms, { prefs.edit().putLong(AppPrefs.SHAPE_HOLD_MS, ms).apply() }, label = { Text(label) })
                }
            }
            Text("${delay} ms", style = MaterialTheme.typography.labelMedium)
            Slider(delay.toFloat(), { prefs.edit().putLong(AppPrefs.SHAPE_HOLD_MS, AppPrefs.shapeHoldMs(it.toLong())).apply() },
                valueRange = AppPrefs.SHAPE_HOLD_MIN_MS.toFloat()..AppPrefs.SHAPE_HOLD_MAX_MS.toFloat(), steps = 23,
                modifier = Modifier.semanticsLabel("Shape hold delay"))
            ShapeAssistOptionControls(options, { prefs.edit().putString(AppPrefs.SHAPE_ASSIST_OPTIONS, it.encode()).apply() })
        }
        Text("Already drawn a diagram? Lasso it and choose Tidy up shapes. You can preview the result even with hold-to-shape off.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ practice = !practice }, shapes = ButtonDefaults.shapes()) { Text(if (practice) "Hide practice area" else "Try it here") }
        if (practice) ShapeAssistPractice(enabled, delay, options)
    }
}

@Composable internal fun ShapeAssistOptionControls(options: ShapeAssistOptions, onChange: (ShapeAssistOptions) -> Unit) {
    Text("Recognise", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(options.lines, { onChange(options.copy(lines = !options.lines)) }, label = { Text("Lines") })
        FilterChip(options.arrows, { onChange(options.copy(arrows = !options.arrows)) }, label = { Text("Arrows") })
        FilterChip(options.closed, { onChange(options.copy(closed = !options.closed)) }, label = { Text("Closed shapes") })
        FilterChip(options.curves, { onChange(options.copy(curves = !options.curves)) }, label = { Text("Curves & brackets") })
    }
    Text("Closed shapes include circles, ovals, boxes, rounded boxes, triangles, polygons of up to eight sides, stars and hearts. Arrows can have a curved shaft. Curves & brackets are arcs, square brackets and curly braces.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ShapeAssistSwitch("Perfect circles & squares", "Make nearly round ovals and nearly square boxes exact. Keep long ovals and rectangles.",
        options.perfect, options.closed) { onChange(options.copy(perfect = it)) }
    ShapeAssistSwitch("Equal-sided polygons", "Regular triangles, pentagons, hexagons, octagons, stars and pentagrams, at the angle you drew them.",
        options.regular, options.closed) { onChange(options.copy(regular = it)) }
    ShapeAssistSwitch("Straighten highlighter", "Hold an underline steady to straighten it. Keeps its colour, width and transparency.",
        options.highlighter, options.lines) { onChange(options.copy(highlighter = it)) }
}

@Composable private fun ShapeAssistSwitch(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semanticsLabel(title))
    }
}

@Composable private fun ShapeAssistExample() {
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val rough = remember(color) {
        Stroke(Tool.PEN, color, 2.5f, (0..96).map { i ->
            val angle = i * 2 * PI / 96
            val radius = 28f + 1.5f * sin(angle * 5).toFloat()
            InkPoint(40f + radius * cos(angle).toFloat(), 38f + radius * sin(angle).toFloat())
        })
    }
    val clean = remember(rough) { ShapeRecognition.tidy(rough).orEmpty() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = FolioShapes.medium) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeInkPreview(listOf(rough), Modifier.weight(1f).height(68.dp))
            Text("→", style = MaterialTheme.typography.titleLarge)
            ShapeInkPreview(clean, Modifier.weight(1f).height(68.dp))
        }
    }
}

/** Uses Folio's ink renderer; shared by the explanatory sample and the actual cleanup preview. */
@Composable internal fun ShapeInkPreview(strokes: List<Stroke>, modifier: Modifier = Modifier) {
    val bounds = remember(strokes) { InkGeometry.contentBounds(strokes, emptyList(), emptyList(), InkRenderer::textHeight, 80f, 80f) }
    Canvas(modifier.semanticsLabel("Shape preview")) {
        val w = (bounds.right - bounds.left).coerceAtLeast(1f)
        val h = (bounds.bottom - bounds.top).coerceAtLeast(1f)
        val zoom = minOf((size.width - 24.dp.toPx()).coerceAtLeast(1f) / w, (size.height - 24.dp.toPx()).coerceAtLeast(1f) / h).coerceAtMost(3f)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate((size.width - w * zoom) / 2f, (size.height - h * zoom) / 2f)
            native.scale(zoom, zoom)
            native.translate(-bounds.left, -bounds.top)
            strokes.forEach { InkRenderer.stroke(native, it) }
            native.restore()
        }
    }
}

@Composable private fun ShapeAssistPractice(enabled: Boolean, delay: Long, options: ShapeAssistOptions) {
    val input = LocalStylusActivity.current
    val inkColor = 0xFF303431.toInt()
    var page by remember { mutableStateOf(NotePage(width = 420f, height = 240f, paper = Paper.GRID)) }
    val history = remember { mutableStateListOf<List<Stroke>>() }
    var feedback by remember { mutableStateOf("Draw a circle, box or arrow, then hold the tip still.") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Practice · nothing here is saved", style = MaterialTheme.typography.labelLarge)
        if (!enabled) Text("Turn on Tidy up shapes above to try the hold gesture.", style = MaterialTheme.typography.bodySmall)
        AndroidView(factory = { context -> InkView(context).apply {
            tool = Tool.PEN; fingerDrawing = true; snapEnabled = false; multiTouchUndo = false
            scribbleToErase = false; pressureEnabled = false; inkWidth = 2.5f
            contentDescription = "Shape assist practice area"
        } }, modifier = Modifier.fillMaxWidth().aspectRatio(1.75f).clip(FolioShapes.medium), update = { view ->
            view.inputStylusActivity = input
            if (view.page !== page) view.bind(page, null)
            view.inkColor = inkColor
            view.shapeRecognition = enabled; view.shapeHoldMs = delay; view.shapeAssistOptions = options
            view.onShapeAssistPreview = { matched -> feedback = if (matched) "Shape ready. Move the pen to adjust it, then lift to keep it." else "Draw a shape, then pause gently to tidy it." }
            view.onStrokesChanged = { strokes ->
                history += page.strokes
                while (history.size > 20) history.removeAt(0)
                page = page.copy(strokes = strokes, revision = page.revision + 1)
                feedback = "Draw another shape, or use Undo to restore your freehand drawing."
            }
        })
        Text(feedback, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ page = page.copy(strokes = history.removeAt(history.lastIndex), revision = page.revision + 1); feedback = "Previous drawing restored." },
                enabled = history.isNotEmpty(), shapes = ButtonDefaults.shapes()) { Text("Undo") }
            TextButton({ page = page.copy(strokes = emptyList(), revision = page.revision + 1); history.clear(); feedback = "Draw a shape, then hold the tip still." }, shapes = ButtonDefaults.shapes()) { Text("Clear") }
        }
    }
}
