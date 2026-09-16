package com.folio.notes

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ThemeOptionsTests {
    @Test fun modeFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of("NOT_A_MODE"))
        assertEquals(ThemeMode.DARK, ThemeMode.of("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.of("LIGHT"))
    }

    @Test fun paletteFallsBackToFolio() {
        assertEquals(ThemePalette.FOLIO, ThemePalette.of(null))
        assertEquals(ThemePalette.FOLIO, ThemePalette.of("NOT_A_PALETTE"))
        assertEquals(ThemePalette.OCEAN, ThemePalette.of("OCEAN"))
    }

    @Test fun legacyWallpaperBooleanMigratesToDynamic() {
        assertEquals(ThemePalette.DYNAMIC, ThemePalette.migrate(null, true))
        assertEquals(ThemePalette.FOLIO, ThemePalette.migrate(null, false))
        assertEquals(ThemePalette.FOLIO, ThemePalette.migrate(null, null))
        // An explicit stored palette always wins over the legacy flag.
        assertEquals(ThemePalette.SAGE, ThemePalette.migrate("SAGE", true))
        assertEquals(ThemePalette.FOLIO, ThemePalette.migrate("BOGUS", null))
    }

    @Test fun modeResolvesAgainstTheSystemSetting() {
        assertTrue(AppTheme.shouldUseDark(ThemeMode.SYSTEM, true))
        assertFalse(AppTheme.shouldUseDark(ThemeMode.SYSTEM, false))
        assertFalse(AppTheme.shouldUseDark(ThemeMode.LIGHT, true))
        assertTrue(AppTheme.shouldUseDark(ThemeMode.DARK, false))
    }

    @Test fun wallpaperFallsBackBelowAndroid12() {
        assertEquals(ThemePalette.FOLIO, AppTheme.effectivePalette(ThemePalette.DYNAMIC, 30))
        assertEquals(ThemePalette.DYNAMIC, AppTheme.effectivePalette(ThemePalette.DYNAMIC, 31))
        assertEquals(ThemePalette.DYNAMIC, AppTheme.effectivePalette(ThemePalette.DYNAMIC, 36))
        assertEquals(ThemePalette.SAGE, AppTheme.effectivePalette(ThemePalette.SAGE, 30))
    }

    @Test fun amoledOnlyAppliesToDark() {
        assertTrue(AppTheme.isAmoledEffective(true, true))
        assertFalse(AppTheme.isAmoledEffective(true, false))
        assertFalse(AppTheme.isAmoledEffective(false, true))
        assertFalse(AppTheme.isAmoledEffective(false, false))
    }

    @Test fun everyOptionHasALabel() {
        assertEquals(ThemeMode.entries.size, ThemeMode.entries.map { it.name }.distinct().size)
        ThemeMode.entries.forEach { assertTrue(it.label.isNotBlank()); assertTrue(it.description.isNotBlank()) }
        assertEquals(ThemePalette.entries.size, ThemePalette.entries.map { it.name }.distinct().size)
        ThemePalette.entries.forEach { assertTrue(it.label.isNotBlank()); assertTrue(it.description.isNotBlank()) }
    }

    @Test fun palettesHaveDistinctPrimariesAndLightDiffersFromDark() {
        val lightPrimaries = ThemePalette.entries.filterNot { it == ThemePalette.DYNAMIC }.map { lightSchemeFor(it).primary }
        assertEquals(lightPrimaries.size, lightPrimaries.distinct().size)
        val darkPrimaries = ThemePalette.entries.filterNot { it == ThemePalette.DYNAMIC }.map { darkSchemeFor(it).primary }
        assertEquals(darkPrimaries.size, darkPrimaries.distinct().size)
        ThemePalette.entries.filterNot { it == ThemePalette.DYNAMIC }.forEach {
            assertNotEquals(lightSchemeFor(it).background, darkSchemeFor(it).background)
        }
    }

    @Test fun amoledKeepsAccentsButBlacksTheBackground() {
        ThemePalette.entries.filterNot { it == ThemePalette.DYNAMIC }.forEach { palette ->
            val dark = darkSchemeFor(palette)
            val black = dark.withAmoled()
            assertEquals(Color.Black, black.background)
            assertEquals(Color.Black, black.surface)
            assertEquals(dark.primary, black.primary)
            assertEquals(dark.onSurface, black.onSurface)
        }
        // Light schemes never go through AMOLED, so the helper must not be applied there by callers;
        // the transform itself is dark-only by contract in FolioTheme via isAmoledEffective.
        assertFalse(AppTheme.isAmoledEffective(true, false))
    }
}
