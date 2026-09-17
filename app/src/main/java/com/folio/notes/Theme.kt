@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val CoverColors = listOf(Color(0xFFE5AD91), Color(0xFFC6CEB8), Color(0xFFCFC5E1), Color(0xFFBCD2DD), Color(0xFFE8D59E), Color(0xFFD7B9BF))

/** Whether the app follows the system dark setting or forces light/dark. */
enum class ThemeMode(val label: String, val description: String) {
    SYSTEM("System default", "Follow your device's light and dark setting"),
    LIGHT("Light", "Always use the light theme"),
    DARK("Dark", "Always use the dark theme");

    companion object {
        const val PREF_KEY = "themeMode"
        fun of(value: String?): ThemeMode = entries.find { it.name == value } ?: SYSTEM
    }
}

/** Which color story the app wears. DYNAMIC needs Android 12+ and falls back to FOLIO. */
enum class ThemePalette(val label: String, val description: String) {
    FOLIO("Folio warm", "Terracotta and sage on warm paper"),
    SAGE("Sage", "Calm greens for long writing sessions"),
    OCEAN("Ocean", "Cool blues that stay easy on the eyes"),
    PLUM("Plum", "Soft purple on warm surfaces"),
    DYNAMIC("Wallpaper colors", "Use your Android color palette (Android 12+)");

    companion object {
        const val PREF_KEY = "themePalette"
        fun of(value: String?): ThemePalette = entries.find { it.name == value } ?: FOLIO

        /** Earlier builds stored wallpaper colors as a boolean; keep that choice on upgrade. */
        fun migrate(stored: String?, legacyDynamic: Boolean?): ThemePalette =
            stored?.let(::of) ?: if (legacyDynamic == true) DYNAMIC else FOLIO
    }
}

/**
 * Pure theme decisions, kept free of Context/Compose so they stay JVM-testable.
 * The [ColorScheme] factories below consume these; UI code should too.
 */
object AppTheme {
    const val AMOLED_PREF_KEY = "amoledDark"

    /** Resolves light vs dark from the chosen mode and the system setting. */
    fun shouldUseDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    /** Wallpaper colors only exist on Android 12 (API 31)+; older devices keep Folio warm. */
    fun effectivePalette(palette: ThemePalette, sdkInt: Int): ThemePalette =
        if (palette == ThemePalette.DYNAMIC && sdkInt < 31) ThemePalette.FOLIO else palette

    /** Pure black backgrounds only change the dark theme. */
    fun isAmoledEffective(amoled: Boolean, dark: Boolean): Boolean = amoled && dark
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFA64B30), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC9), onPrimaryContainer = Color(0xFF592515),
    secondary = Color(0xFF5E6652), secondaryContainer = Color(0xFFE2E8D6),
    onSecondaryContainer = Color(0xFF1F241B),
    tertiary = Color(0xFF716183), tertiaryContainer = Color(0xFFECDDF8),
    onTertiaryContainer = Color(0xFF2A2138),
    background = Color(0xFFFBF8F3), surface = Color(0xFFFBF8F3),
    surfaceContainer = Color(0xFFF1EDE6), surfaceContainerLow = Color(0xFFF5F1EB),
    surfaceContainerHigh = Color(0xFFEAE5DC), surfaceContainerHighest = Color(0xFFE3DDD3),
    onSurface = Color(0xFF2E302B),
    onSurfaceVariant = Color(0xFF73746B), outline = Color(0xFFA3A39A), outlineVariant = Color(0xFFE0DED5),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)
// Warm dark companion to LightColors: same terracotta/sage/plum accents, but on a warm
// charcoal base so cards, sheets and the editor chrome stay tonal instead of falling back
// to the stock Material dark greys.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB595), onPrimary = Color(0xFF592515),
    primaryContainer = Color(0xFF7A3A26), onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFC6CEB8), onSecondary = Color(0xFF2A331F),
    secondaryContainer = Color(0xFF404A35), onSecondaryContainer = Color(0xFFE2E8D6),
    tertiary = Color(0xFFD4BDF0), onTertiary = Color(0xFF402D5A),
    tertiaryContainer = Color(0xFF574064), onTertiaryContainer = Color(0xFFECDDF8),
    background = Color(0xFF1C1D19), surface = Color(0xFF1C1D19),
    onSurface = Color(0xFFEDE8DF),
    surfaceContainer = Color(0xFF282924), surfaceContainerLow = Color(0xFF23241F),
    surfaceContainerHigh = Color(0xFF33342E), surfaceContainerHighest = Color(0xFF3E3F39),
    onSurfaceVariant = Color(0xFFC2C3B7), outline = Color(0xFF8D8E85), outlineVariant = Color(0xFF484943),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val SageLightColors = lightColorScheme(
    primary = Color(0xFF4C662B), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDA3), onPrimaryContainer = Color(0xFF102000),
    secondary = Color(0xFF55624C), secondaryContainer = Color(0xFFD8E7CA),
    onSecondaryContainer = Color(0xFF131F0D),
    tertiary = Color(0xFF386663), tertiaryContainer = Color(0xFFBCECE7),
    onTertiaryContainer = Color(0xFF00201D),
    background = Color(0xFFF7FAEF), surface = Color(0xFFF7FAEF),
    surfaceContainer = Color(0xFFECEFE3), surfaceContainerLow = Color(0xFFF1F4E8),
    surfaceContainerHigh = Color(0xFFE6E9DD), surfaceContainerHighest = Color(0xFFE0E3D6),
    onSurface = Color(0xFF1A1C16),
    onSurfaceVariant = Color(0xFF44483D), outline = Color(0xFF75796C), outlineVariant = Color(0xFFC5C8BA),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)
private val SageDarkColors = darkColorScheme(
    primary = Color(0xFFB1D18A), onPrimary = Color(0xFF1F3708),
    primaryContainer = Color(0xFF354E1D), onPrimaryContainer = Color(0xFFCDEDA3),
    secondary = Color(0xFFBCC7A9), onSecondary = Color(0xFF2A331F),
    secondaryContainer = Color(0xFF3E4834), onSecondaryContainer = Color(0xFFD8E7CA),
    tertiary = Color(0xFFA0D0CB), onTertiary = Color(0xFF003735),
    tertiaryContainer = Color(0xFF1F4E4A), onTertiaryContainer = Color(0xFFBCECE7),
    background = Color(0xFF1A1C16), surface = Color(0xFF1A1C16),
    onSurface = Color(0xFFE3E4D5),
    surfaceContainer = Color(0xFF25281F), surfaceContainerLow = Color(0xFF20231A),
    surfaceContainerHigh = Color(0xFF2F3327), surfaceContainerHighest = Color(0xFF3A3F30),
    onSurfaceVariant = Color(0xFFC2C8B0), outline = Color(0xFF8D9382), outlineVariant = Color(0xFF44483D),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)
private val OceanLightColors = lightColorScheme(
    primary = Color(0xFF0061A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF), onPrimaryContainer = Color(0xFF001D35),
    secondary = Color(0xFF535F70), secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778), tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    background = Color(0xFFF6FAFD), surface = Color(0xFFF6FAFD),
    surfaceContainer = Color(0xFFECEFF3), surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFE6EAEE), surfaceContainerHighest = Color(0xFFE0E4E8),
    onSurface = Color(0xFF191C20),
    onSurfaceVariant = Color(0xFF43474E), outline = Color(0xFF73777F), outlineVariant = Color(0xFFC3C7CF),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)
private val OceanDarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF), onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D), onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB), onSecondary = Color(0xFF253141),
    secondaryContainer = Color(0xFF3B4858), onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BDE4), onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F), onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF101418), surface = Color(0xFF101418),
    onSurface = Color(0xFFE1E2E8),
    surfaceContainer = Color(0xFF1F2329), surfaceContainerLow = Color(0xFF1B1E23),
    surfaceContainerHigh = Color(0xFF2A2F36), surfaceContainerHighest = Color(0xFF353B42),
    onSurfaceVariant = Color(0xFFC3C7CF), outline = Color(0xFF8D9199), outlineVariant = Color(0xFF43474E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)
private val PlumLightColors = lightColorScheme(
    primary = Color(0xFF6A4AA3), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF625B71), secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFBF7FD), surface = Color(0xFFFBF7FD),
    surfaceContainer = Color(0xFFF0EBF1), surfaceContainerLow = Color(0xFFF5F0F6),
    surfaceContainerHigh = Color(0xFFEAE4EB), surfaceContainerHighest = Color(0xFFE4DEE5),
    onSurface = Color(0xFF1D1B20),
    onSurfaceVariant = Color(0xFF49454F), outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)
private val PlumDarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218), surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceContainer = Color(0xFF252128), surfaceContainerLow = Color(0xFF1F1B24),
    surfaceContainerHigh = Color(0xFF2F2B36), surfaceContainerHighest = Color(0xFF3A3540),
    onSurfaceVariant = Color(0xFFCAC4D0), outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** Static light scheme for [palette]; DYNAMIC resolves separately from the wallpaper. */
fun lightSchemeFor(palette: ThemePalette): ColorScheme = when (palette) {
    ThemePalette.SAGE -> SageLightColors
    ThemePalette.OCEAN -> OceanLightColors
    ThemePalette.PLUM -> PlumLightColors
    else -> LightColors
}

/** Static dark scheme for [palette]; DYNAMIC resolves separately from the wallpaper. */
fun darkSchemeFor(palette: ThemePalette): ColorScheme = when (palette) {
    ThemePalette.SAGE -> SageDarkColors
    ThemePalette.OCEAN -> OceanDarkColors
    ThemePalette.PLUM -> PlumDarkColors
    else -> DarkColors
}

/** True-black backgrounds for OLED screens; accents stay untouched so ink keeps its hue. */
fun ColorScheme.withAmoled(): ColorScheme = copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainer = Color(0xFF111111), surfaceContainerLow = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF1A1A1A), surfaceContainerHighest = Color(0xFF242424)
)

@Composable fun FolioTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    palette: ThemePalette = ThemePalette.FOLIO,
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = AppTheme.shouldUseDark(mode, systemDark)
    val effective = AppTheme.effectivePalette(palette, Build.VERSION.SDK_INT)
    val base = if (effective == ThemePalette.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) darkSchemeFor(effective) else lightSchemeFor(effective)
    val colors = if (AppTheme.isAmoledEffective(amoled, dark)) base.withAmoled() else base
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), typography = Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 44.sp),
        headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 39.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    ), content = content)
}
