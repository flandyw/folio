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
@Composable fun FolioTheme(dynamic: Boolean = false, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) DarkColors else LightColors
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
