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
    tertiary = Color(0xFF716183), tertiaryContainer = Color(0xFFECDDF8),
    background = Color(0xFFFBF8F3), surface = Color(0xFFFBF8F3),
    surfaceContainer = Color(0xFFF1EDE6), surfaceContainerLow = Color(0xFFF5F1EB),
    surfaceContainerHigh = Color(0xFFEAE5DC), onSurface = Color(0xFF2E302B),
    onSurfaceVariant = Color(0xFF73746B), outlineVariant = Color(0xFFE0DED5)
)
@Composable fun FolioTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) darkColorScheme(primary = Color(0xFFFFB595), secondary = Color(0xFFC6CEB8), background = Color(0xFF1C1D19), surface = Color(0xFF1C1D19)) else LightColors
    MaterialTheme(colorScheme = colors, typography = Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 44.sp),
        headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 39.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)
    ), content = content)
}
