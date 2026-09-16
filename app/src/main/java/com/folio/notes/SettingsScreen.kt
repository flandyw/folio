package com.folio.notes

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable fun SettingsScreen(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, themePalette: ThemePalette, onThemePalette: (ThemePalette) -> Unit, amoled: Boolean, onAmoled: (Boolean) -> Unit, finger: Boolean, onFinger: (Boolean) -> Unit, stylus: StylusShortcut, onStylus: (StylusShortcut) -> Unit, haptics: Boolean, onHaptics: (Boolean) -> Unit, shapeRecognition: Boolean, onShapeRecognition: (Boolean) -> Unit, onCheckForUpdates: () -> Unit, updateChecking: Boolean, onBack: () -> Unit, onExamTrack: () -> Unit = {}) {
    val context = LocalContext.current
    val hapticsSupported = remember(context) { PenHapticsManager.isSupported(context) }
    val dynamicAvailable = Build.VERSION.SDK_INT >= 31
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close settings") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onExamTrack) { Text("ExamTrack · Sign in and manage mistake sync") }
            HorizontalDivider()
            Text("Your writing space", style = MaterialTheme.typography.headlineMedium)
            Text("Make room for your ideas. These preferences apply to all notebooks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Stylus", style = MaterialTheme.typography.titleMedium)
            Text("On a OnePlus or OPPO device, double-tapping the Pencil starts the action you pick here. Other styli keep their own system shortcut. Palm touches are ignored while the stylus is writing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup().padding(8.dp)) {
                    StylusShortcut.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(stylus == option, role = Role.RadioButton, onClick = { onStylus(option) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(stylus == option, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column { Text(option.label, style = MaterialTheme.typography.titleSmall); Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            PreferenceSwitch("Pen haptics", if (hapticsSupported) "Buzz the Pencil on every double tap it sends, whatever the action. Changing tools by hand stays silent. The one-shot pulse is confirmed on the OnePlus Pencil Pro; it needs Bluetooth, and it is not the pen's soft writing feedback." else "Needs Android 12 or newer and a Bluetooth LE pencil.", haptics, onHaptics, hapticsSupported)
            HorizontalDivider()
            Text("Writing & appearance", style = MaterialTheme.typography.titleMedium)
            PreferenceSwitch("Draw with a finger", "When off, use a finger to scroll and a stylus to write. When on, scroll with two fingers or the hand tool. Palm touches are ignored while the stylus writes.", finger, onFinger)
            PreferenceSwitch("Tidy up shapes", "Draw a rough line, square, circle or triangle with the pen and it becomes a clean shape when you lift the pen. Undo brings your own drawing back.", shapeRecognition, onShapeRecognition)
            Text("Appearance", style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup().padding(8.dp)) {
                    ThemeMode.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(option == themeMode, role = Role.RadioButton, onClick = { onThemeMode(option) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(option == themeMode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column { Text(option.label, style = MaterialTheme.typography.titleSmall); Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Text("Color theme", style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.selectableGroup().padding(8.dp)) {
                    ThemePalette.entries.forEach { option ->
                        val enabled = option != ThemePalette.DYNAMIC || dynamicAvailable
                        Row(Modifier.fillMaxWidth().selectable(option == themePalette, enabled = enabled, role = Role.RadioButton, onClick = { onThemePalette(option) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(option == themePalette, onClick = null, enabled = enabled)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(option.label, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (!enabled) "Needs Android 12 or newer" else option.description,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            PreferenceSwitch("Pure black dark", "Use true black backgrounds whenever the dark theme is active. Accents and ink colors stay the same.", amoled, onAmoled)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("App updates", style = MaterialTheme.typography.titleSmall)
                    Text("Check GitHub for a newer signed Folio release.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onCheckForUpdates, enabled = !updateChecking) {
                    if (updateChecking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Check")
                }
            }
            Text("Your notebooks stay on this device. Export a PDF to keep a copy or share your work.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, enabled = enabled)
    }
}
