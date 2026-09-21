@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Composable internal fun ScribbleSettingsSection(showPracticeInitially: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(EditorQuickPrefs.SCRIBBLE_TO_ERASE, true)) }
    var sensitivity by remember { mutableFloatStateOf(ScribbleSensitivity.normalize(
        prefs.getFloat(EditorQuickPrefs.SCRIBBLE_SENSITIVITY, ScribbleSensitivity.DEFAULT))) }
    var showPractice by rememberSaveable { mutableStateOf(showPracticeInitially) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                EditorQuickPrefs.SCRIBBLE_TO_ERASE -> enabled = prefs.getBoolean(key, true)
                EditorQuickPrefs.SCRIBBLE_SENSITIVITY -> sensitivity = ScribbleSensitivity.normalize(
                    prefs.getFloat(key, ScribbleSensitivity.DEFAULT))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun setSensitivity(value: Float) {
        sensitivity = ScribbleSensitivity.normalize(value)
        prefs.edit().putFloat(EditorQuickPrefs.SCRIBBLE_SENSITIVITY, sensitivity).apply()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scribble to erase", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(enabled, {
                enabled = it
                prefs.edit().putBoolean(EditorQuickPrefs.SCRIBBLE_TO_ERASE, it).apply()
            }, modifier = Modifier.semanticsLabel("Scribble to erase"))
        }
        Text("Scrub back and forth over ink with the pen or highlighter. Applies to all notebooks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Sensitivity: ${(sensitivity * 100).roundToInt()}%")
        Slider(sensitivity, ::setSensitivity, enabled = enabled,
            modifier = Modifier.semanticsLabel("Scribble erase sensitivity"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("More deliberate", style = MaterialTheme.typography.labelSmall)
            Text("Easier to trigger", style = MaterialTheme.typography.labelSmall)
        }
        Text("Raise sensitivity if scrubbing takes too much effort. Lower it if handwriting erases ink. Every setting requires repeated contact with existing ink.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ setSensitivity(ScribbleSensitivity.DEFAULT) }, shapes = ButtonDefaults.shapes()) { Text("Reset sensitivity") }
            TextButton({ showPractice = !showPractice }, shapes = ButtonDefaults.shapes()) { Text(if (showPractice) "Hide test area" else "Test scribble to erase") }
        }
        if (showPractice) ScribblePractice(enabled, sensitivity)
    }
}

private fun scribblePracticePage() = NotePage(
    width = 420f, height = 210f, paper = Paper.PLAIN,
    strokes = (0..4).map { i ->
        Stroke(Tool.PEN, 0xFF2E5AAC.toInt(), 2.5f,
            listOf(InkPoint(170f + i * 20, 75f), InkPoint(170f + i * 20, 135f)))
    }
)

/** A disposable page using the editor's real input and erasing pipeline; never saved to a notebook. */
@Composable private fun ScribblePractice(enabled: Boolean, sensitivity: Float) {
    var page by remember { mutableStateOf(scribblePracticePage()) }
    var feedback by remember { mutableStateOf("Try scrubbing across the blue ink, or write your own sample.") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Test your scribble", style = MaterialTheme.typography.titleSmall)
        Text("Use your pen or finger here. This test area is not saved to your notes.", style = MaterialTheme.typography.bodySmall)
        AndroidView(
            factory = { context -> InkView(context).apply {
                tool = Tool.PEN; fingerDrawing = true; shapeRecognition = false
                snapEnabled = false; multiTouchUndo = false; pressureEnabled = false
                inkWidth = 2.2f; inkColor = 0xFF303431.toInt()
                contentDescription = "Scribble erase test area"
            } },
            modifier = Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(12.dp)),
            update = { view ->
                if (view.page !== page) view.bind(page, null)
                view.scribbleToErase = enabled
                view.scribbleSensitivity = sensitivity
                view.onStrokesChanged = { strokes ->
                    val erased = page.strokes.size - strokes.size
                    feedback = when {
                        erased > 0 -> "Erased $erased ${if (erased == 1) "stroke" else "strokes"}."
                        !enabled -> "Kept as ink — scribble to erase is off."
                        else -> "Kept as ink. Scrub over existing ink, or raise sensitivity."
                    }
                    page = page.copy(strokes = strokes, revision = page.revision + 1)
                }
            }
        )
        Text(feedback, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                page = scribblePracticePage()
                feedback = "Sample ink restored. Try again with your current sensitivity."
            },
                shapes = ButtonDefaults.shapes()) { Text("Reset test area") }
            TextButton({
                page = page.copy(strokes = emptyList(), revision = page.revision + 1)
                feedback = "Write something, then try scrubbing it out."
            },
                shapes = ButtonDefaults.shapes()) { Text("Clear") }
        }
    }
}
