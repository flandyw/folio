@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.folio.notes.R

enum class InterfaceFont(val label: String) {
    ALLO("Allo"), SYSTEM("System sans"), SERIF("Serif"), MONOSPACE("Monospace");

    companion object {
        const val PREF_KEY = "display.interfaceFont"
        fun of(raw: String?): InterfaceFont = entries.firstOrNull { it.name == raw } ?: ALLO
    }
}

private val AlloFamily = FontFamily(
    Font(R.font.allo_regular, FontWeight.Normal),
    Font(R.font.allo_medium, FontWeight.Medium),
    Font(R.font.allo_semibold, FontWeight.SemiBold),
    Font(R.font.allo_bold, FontWeight.Bold),
)

val InterfaceFont.family: FontFamily get() = when (this) {
    InterfaceFont.ALLO -> AlloFamily
    InterfaceFont.SYSTEM -> FontFamily.SansSerif
    InterfaceFont.SERIF -> FontFamily.Serif
    InterfaceFont.MONOSPACE -> FontFamily.Monospace
}

internal fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
    displayLargeEmphasized = displayLargeEmphasized.copy(fontFamily = family),
    displayMediumEmphasized = displayMediumEmphasized.copy(fontFamily = family),
    displaySmallEmphasized = displaySmallEmphasized.copy(fontFamily = family),
    headlineLargeEmphasized = headlineLargeEmphasized.copy(fontFamily = family),
    headlineMediumEmphasized = headlineMediumEmphasized.copy(fontFamily = family),
    headlineSmallEmphasized = headlineSmallEmphasized.copy(fontFamily = family),
    titleLargeEmphasized = titleLargeEmphasized.copy(fontFamily = family),
    titleMediumEmphasized = titleMediumEmphasized.copy(fontFamily = family),
    titleSmallEmphasized = titleSmallEmphasized.copy(fontFamily = family),
    bodyLargeEmphasized = bodyLargeEmphasized.copy(fontFamily = family),
    bodyMediumEmphasized = bodyMediumEmphasized.copy(fontFamily = family),
    bodySmallEmphasized = bodySmallEmphasized.copy(fontFamily = family),
    labelLargeEmphasized = labelLargeEmphasized.copy(fontFamily = family),
    labelMediumEmphasized = labelMediumEmphasized.copy(fontFamily = family),
    labelSmallEmphasized = labelSmallEmphasized.copy(fontFamily = family),
)
