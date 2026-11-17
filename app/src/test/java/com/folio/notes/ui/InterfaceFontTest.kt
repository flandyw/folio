@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.folio.notes.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceFontTest {
    @Test fun missingOrUnknownPreferenceUsesAllo() {
        for (raw in listOf(null, "", "removed-font")) assertEquals(InterfaceFont.ALLO, InterfaceFont.of(raw))
        for (font in InterfaceFont.entries) assertEquals(font, InterfaceFont.of(font.name))
    }

    @Test fun everyTypographyRoleUsesSelectedFamilyAndKeepsItsMetrics() {
        val original = Typography(titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold))
        // Include emphasized styles: expressive controls can choose them independently.
        val roles: List<(Typography) -> TextStyle> = listOf(
            { it.displayLarge }, { it.displayMedium }, { it.displaySmall },
            { it.headlineLarge }, { it.headlineMedium }, { it.headlineSmall },
            { it.titleLarge }, { it.titleMedium }, { it.titleSmall },
            { it.bodyLarge }, { it.bodyMedium }, { it.bodySmall },
            { it.labelLarge }, { it.labelMedium }, { it.labelSmall },
            { it.displayLargeEmphasized }, { it.displayMediumEmphasized }, { it.displaySmallEmphasized },
            { it.headlineLargeEmphasized }, { it.headlineMediumEmphasized }, { it.headlineSmallEmphasized },
            { it.titleLargeEmphasized }, { it.titleMediumEmphasized }, { it.titleSmallEmphasized },
            { it.bodyLargeEmphasized }, { it.bodyMediumEmphasized }, { it.bodySmallEmphasized },
            { it.labelLargeEmphasized }, { it.labelMediumEmphasized }, { it.labelSmallEmphasized },
        )
        for (font in InterfaceFont.entries) {
            val changed = original.withFontFamily(font.family)
            for (role in roles) assertEquals(role(original).copy(fontFamily = font.family), role(changed))
        }
    }
}
