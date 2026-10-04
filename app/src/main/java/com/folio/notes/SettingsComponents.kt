@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.folio.notes

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Building blocks for the settings screens. A page is a stack of SettingsGroups; a group is one
// rounded surface of rows (switch, slider, choice, link). Rows never draw their own container, so
// they read the same on a phone page and in the tablet's detail pane.

// ---- Preference state ---------------------------------------------------------------------------

@Composable internal fun rememberPrefs(): SharedPreferences {
    val context = LocalContext.current
    return remember(context) { context.getSharedPreferences("preferences", 0) }
}

/**
 * A preference as Compose state. [read] runs on first composition and again whenever [keys]
 * change in the store, so a value changed from the editor (or reset elsewhere) shows up live.
 * Writing goes through [SharedPreferences.edit] and lands back here through the same listener.
 */
@Composable internal fun <T> rememberPref(prefs: SharedPreferences, vararg keys: String, read: (SharedPreferences) -> T): State<T> {
    val state = remember(prefs) { mutableStateOf(read(prefs)) }
    val currentRead by rememberUpdatedState(read)
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) state.value = currentRead(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

internal inline fun SharedPreferences.write(block: SharedPreferences.Editor.() -> Unit) = edit().apply(block).apply()

// ---- Structure ----------------------------------------------------------------------------------

/** A labelled block of related rows on one surface, with an optional footnote underneath. */
@Composable internal fun SettingsGroup(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        if (title != null) {
            Text(
                title, Modifier.padding(horizontal = FolioSpacing.dp16).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            )
        }
        Surface(shape = FolioShapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = FolioSpacing.dp4), content = content)
        }
        if (footer != null) {
            Text(
                footer, Modifier.padding(horizontal = FolioSpacing.dp16),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = FolioSpacing.dp16), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

private val RowPadding = PaddingValues(horizontal = FolioSpacing.dp16, vertical = FolioSpacing.dp12)

@Composable private fun RowText(title: String, subtitle: String?, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else muted)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

/** The small "back to default" arrow, shown only once a value has moved away from its default. */
@Composable private fun ResetButton(title: String, visible: Boolean, onReset: () -> Unit) {
    if (!visible) return
    val hold = rememberLongPressGuard()
    IconButton(onClick = { hold.click(onReset)() }, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Rounded.RestartAlt, "Reset $title to default", Modifier.size(20.dp))
    }
}

// ---- Rows ---------------------------------------------------------------------------------------

/** A plain row, or a link when [onClick] is set (chevron appended unless [trailing] replaces it). */
@Composable internal fun SettingsLinkRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.clickableRow(enabled, onClick) else base
    Row(clickable.padding(RowPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16)) {
        if (leadingContent != null) leadingContent()
        else if (leading != null) Icon(leading, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        RowText(title, subtitle, enabled, Modifier.weight(1f))
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.clickableRow(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, role = Role.Button, onClick = onClick)

@Composable internal fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    /** Set when the value differs from its default; shows the reset arrow. */
    onReset: (() -> Unit)? = null,
) {
    val hold = rememberLongPressGuard()
    Row(
        Modifier.fillMaxWidth()
            .then(if (onReset != null) Modifier.longPressAction(hold, onReset) else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { v -> hold.click { onChange(v) }() })
            .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16),
    ) {
        RowText(title, subtitle, enabled, Modifier.weight(1f))
        ResetButton(title, onReset != null && enabled) { onReset?.invoke() }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A switch bound straight to a boolean preference. */
@Composable internal fun SettingsPrefSwitch(key: String, default: Boolean, title: String, subtitle: String? = null, enabled: Boolean = true) {
    val p = rememberPrefs()
    val checked by rememberPref(p, key) { it.getBoolean(key, default) }
    SettingsSwitchRow(title, subtitle, checked, { p.write { putBoolean(key, it) } }, enabled,
        onReset = if (checked != default) ({ p.write { putBoolean(key, default) } }) else null)
}

/** A titled slider with its current value on the right. [steps] follows [Slider]. */
@Composable internal fun SettingsSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    subtitle: String? = null,
    enabled: Boolean = true,
    onReset: (() -> Unit)? = null,
    lowLabel: String? = null,
    highLabel: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(RowPadding), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (onReset != null) ResetButton(title, enabled, onReset)
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value, onChange, valueRange = range, steps = steps, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "$title, $valueLabel" })
        if (lowLabel != null && highLabel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(lowLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(highLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A titled row of two or three mutually exclusive options, as a connected button group. */
@Composable internal fun <T> SettingsSegmentedRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(RowPadding), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        RowText(title, subtitle, enabled)
        FolioButtonGroup(Modifier.fillMaxWidth()) {
            options.forEach { (value, label) -> toggleableItem(selected == value, label, { if (enabled) onSelect(value) }) }
        }
    }
}

/** A titled, wrapping set of chips for longer option lists. */
@Composable internal fun <T> SettingsChipRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(RowPadding), verticalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
        RowText(title, subtitle)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp8)) {
            options.forEach { (value, label) ->
                FilterChip(selected == value, { onSelect(value) }, { Text(label) })
            }
        }
    }
}

/** One radio option with a description; place several inside [SettingsRadioGroup]. */
@Composable internal fun SettingsRadioRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioSpacing.dp16),
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        RowText(title, subtitle, enabled, Modifier.weight(1f))
    }
}

@Composable internal fun SettingsRadioGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.selectableGroup(), content = content)
}

/** Free-form content (swatches, status, buttons) given the standard row padding. */
@Composable internal fun SettingsBlock(verticalSpacing: Int = 8, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(RowPadding), verticalArrangement = Arrangement.spacedBy(verticalSpacing.dp), content = content)
}

@Composable internal fun SettingsBlockTitle(title: String, subtitle: String? = null) = RowText(title, subtitle)

@Composable internal fun SettingsBlockHint(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
