package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class InkColorsTests {
    @Test fun writingPaletteHasTenDistinctOpaqueInks() {
        assertEquals(10, InkColors.swatches.size)
        assertEquals(10, InkColors.swatches.distinct().size)
        assertEquals(listOf("Writing inks"), InkColors.palettes.map { it.name })
        InkColors.swatches.forEach { assertEquals(255, it ushr 24) }
        assertEquals(InkColors.swatches.take(5), InkColors.defaultQuick)
    }

    @Test fun fineWritingHasContrastOnWhiteAndWarmPaper() {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val c = (color shr shift and 255) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        listOf(0xFFFFFFFF.toInt(), 0xFFFFF8E7.toInt()).forEach { paper ->
            InkColors.namedSwatches.forEach { (name, color) ->
                assertTrue("$name should have at least 4.5:1 contrast", (luminance(paper) + 0.05) / (luminance(color) + 0.05) >= 4.5)
            }
        }
    }

    @Test fun retiredPalettesResetButCustomRowsSurvive() {
        val custom = List(5) { 0xFF123456.toInt() }
        InkColors.groups.forEach { group ->
            listOf("Exam", "Highlighter", "Classic", "Pastel", "Vivid", "Cool", "Warm", "Iroshizuku", "Graphite").forEach { retired ->
                assertEquals(InkColors.defaultQuick(group), InkColors.restoredQuick(group, custom, retired))
            }
            assertEquals(custom, InkColors.restoredQuick(group, custom, ""))
            assertEquals(custom, InkColors.restoredQuick(group, custom, null))
            assertEquals(InkColors.defaultQuick(group), InkColors.restoredQuick(group, null, null))
        }
    }
}
