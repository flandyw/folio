@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import kotlin.math.roundToInt

/** The accent swatches offered next to the free colour chooser. */
val AccentPresets: List<Color> = listOf(
    Color(0xFFA64B30), // terracotta, Folio's own
    Color(0xFF4C662B), // sage
    Color(0xFF0061A4), // ocean
    Color(0xFF6A4AA3), // plum
    Color(0xFFB3261E), // red
    Color(0xFF00696E), // teal
    Color(0xFF7D5260), // rose
    Color(0xFF3F5B8C), // indigo
)

/** The lightness window the chooser offers: deep enough to read as ink, pale enough to sit on paper. */
private const val ACCENT_L_MIN = 0.22f
private const val ACCENT_L_MAX = 0.72f

/**
 * The user's own notebook cover colours, stored as ARGB ints after the built-in covers so
 * existing `cover` indexes in `library.json` keep pointing at the same colour.
 *
 * Encoding is a plain JSON array; decoding drops anything unparseable and never fails.
 */
object CoverPalette {
    const val MAX = 18

    fun encode(colors: List<Color>): String = JSONArray().apply {
        colors.forEach { put(AccentTones.colorToArgb(it)) }
    }.toString()

    fun decode(raw: String?): List<Color> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index -> Color(array.getInt(index)) }.take(MAX)
        } catch (_: Exception) { emptyList() }
    }

    /** Adds [color], replacing an identical existing entry, and keeps the list newest-last. */
    fun add(existing: List<Color>, color: Color): List<Color> {
        val argb = AccentTones.colorToArgb(color)
        val same = existing.any { AccentTones.colorToArgb(it) == argb }
        return ((if (same) existing.filterNot { AccentTones.colorToArgb(it) == argb } else existing) + color).takeLast(MAX)
    }

    fun remove(existing: List<Color>, color: Color): List<Color> =
        existing.filterNot { AccentTones.colorToArgb(it) == AccentTones.colorToArgb(color) }
}

/** Live accent colour, shared by the theme and the settings chooser so edits apply instantly. */
@Stable
class AccentState(private val prefs: SharedPreferences) {
    var accent: Color? by mutableStateOf(load())
        private set

    private fun load(): Color? = AccentTones.parseHex(prefs.getString(AppPrefs.ACCENT, null))

    /** Re-reads the stored accent, used when another screen changes it. */
    fun reload() {
        accent = load()
    }

    fun set(color: Color) {
        accent = color
        prefs.edit().putString(AppPrefs.ACCENT, AccentTones.hex(color)).apply()
    }

    /** Back to the palette's own colours. */
    fun clear() {
        accent = null
        prefs.edit().remove(AppPrefs.ACCENT).apply()
    }
}

/** The accent for this session, following preference changes made anywhere else. */
@Composable fun rememberAccentState(): AccentState {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    val state = remember(prefs) { AccentState(prefs) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppPrefs.ACCENT) state.reload()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** Extra cover colours, read from preferences and refreshed when another screen edits them. */
@Composable fun rememberCustomCoverColors(): List<Color> {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    var colors by remember { mutableStateOf(CoverPalette.decode(prefs.getString(AppPrefs.CUSTOM_COVERS, null))) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppPrefs.CUSTOM_COVERS) colors = CoverPalette.decode(prefs.getString(key, null))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return colors
}

/** Adds or removes one cover colour; the shelf and the new-notebook picker both follow. */
@Composable fun rememberCoverPalette(): CoverPaletteState {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("preferences", 0) }
    return remember(prefs) { CoverPaletteState(prefs) }
}

/** Writes to the stored cover colours; the shelf and pickers observe them. */
class CoverPaletteState(private val prefs: SharedPreferences) {
    private fun current(): List<Color> = CoverPalette.decode(prefs.getString(AppPrefs.CUSTOM_COVERS, null))

    fun add(color: Color) {
        prefs.edit().putString(AppPrefs.CUSTOM_COVERS, CoverPalette.encode(CoverPalette.add(current(), color))).apply()
    }

    fun remove(color: Color) {
        prefs.edit().putString(AppPrefs.CUSTOM_COVERS, CoverPalette.encode(CoverPalette.remove(current(), color))).apply()
    }
}

/** A round swatch that shows the accent and its check mark. Disabled swatches stay visible but fade, so a palette that ignores the accent still shows what is stored. */
@Composable
fun ColorSwatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val check = AccentTones.lightnessOf(color) > 0.6f
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = color,
        modifier = modifier.size(40.dp).alpha(if (enabled) 1f else .38f).semantics { contentDescription = label },
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(20.dp), tint = if (check) Color(0xFF2E302B) else Color.White)
        }
    }
}

/**
 * Hue / saturation / lightness sliders plus a live preview and a hex field.
 *
 * Used for both the app accent and notebook covers so a colour picked in one place behaves
 * the same in the other.
 */
@Composable
fun ColorChooser(
    initial: Color?,
    title: String,
    onPreview: (Color) -> Unit,
    onConfirm: (Color) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val start = initial ?: AccentPresets.first()
    var hue by remember { mutableFloatStateOf(AccentTones.hueOf(start)) }
    var saturation by remember { mutableFloatStateOf(AccentTones.saturationOf(start).coerceIn(0.05f, 1f)) }
    var lightness by remember { mutableFloatStateOf(AccentTones.lightnessOf(start).coerceIn(ACCENT_L_MIN, ACCENT_L_MAX)) }
    var hex by remember { mutableStateOf(AccentTones.hex(start)) }
    val current = AccentTones.of(hue, saturation, lightness)
    LaunchedEffect(current) {
        hex = AccentTones.hex(current)
        onPreview(current)
    }
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
            Box(
                Modifier.size(56.dp)
                    .background(current, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .semantics { contentDescription = "$title preview" },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp4)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text("Hue ${hue.roundToInt()}° · Saturation ${(saturation * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Hue", style = MaterialTheme.typography.labelLarge)
        Slider(hue, { hue = it }, valueRange = 0f..360f, modifier = Modifier.semantics { contentDescription = "Hue" })
        Text("Saturation", style = MaterialTheme.typography.labelLarge)
        Slider(saturation, { saturation = it }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = "Saturation" })
        Text("Lightness", style = MaterialTheme.typography.labelLarge)
        Slider(lightness, { lightness = it }, valueRange = ACCENT_L_MIN..ACCENT_L_MAX, modifier = Modifier.semantics { contentDescription = "Lightness" })
        OutlinedTextField(
            value = hex,
            onValueChange = { entered ->
                val parsed = AccentTones.parseHex(entered.take(7))
                hex = entered.take(7)
                parsed?.let {
                    hue = AccentTones.hueOf(it)
                    saturation = AccentTones.saturationOf(it).coerceIn(0.05f, 1f)
                    lightness = AccentTones.lightnessOf(it).coerceIn(ACCENT_L_MIN, ACCENT_L_MAX)
                }
            },
            label = { Text("Hex") },
            prefix = { Text("#") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onConfirm(AccentTones.of(hue, saturation, lightness)) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            Button({ onConfirm(AccentTones.of(hue, saturation, lightness)) }, shapes = ButtonDefaults.shapes()) { Text("Use this colour") }
            onClear?.let { clear -> TextButton(clear, shapes = ButtonDefaults.shapes()) { Text("Reset") } }
        }
    }
}