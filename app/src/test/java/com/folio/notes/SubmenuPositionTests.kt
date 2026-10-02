package com.folio.notes

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class SubmenuPositionTests {
    private val provider = SubmenuPositionProvider(margin = 8, gap = 4)
    private val window = IntSize(1000, 800)
    private val menu = IntSize(240, 300)

    @Test fun opensBesideTheActualParentWidthAndAlignsItsFirstRow() {
        assertEquals(IntOffset(384, 392), provider.calculatePosition(
            IntRect(100, 400, 380, 448), window, LayoutDirection.Ltr, menu))
    }

    @Test fun rightEdgeFlipsToTheLeftInsteadOfJumpingToTheWindowCorner() {
        assertEquals(IntOffset(456, 392), provider.calculatePosition(
            IntRect(700, 400, 980, 448), window, LayoutDirection.Ltr, menu))
    }

    @Test fun bottomEdgeKeepsTheMenuInsideTheWindow() {
        assertEquals(IntOffset(384, 492), provider.calculatePosition(
            IntRect(100, 700, 380, 748), window, LayoutDirection.Ltr, menu))
    }

    @Test fun rightToLeftPrefersTheLeftSide() {
        assertEquals(IntOffset(156, 192), provider.calculatePosition(
            IntRect(400, 200, 680, 248), window, LayoutDirection.Rtl, menu))
    }

    @Test fun scalarPlacementPreservesCandidateOrderAndClamping() {
        for (direction in LayoutDirection.entries) {
            for (windowSize in listOf(IntSize(1000, 800), IntSize(300, 200), IntSize.Zero)) {
                for (popupSize in listOf(menu, IntSize(1200, 900), IntSize.Zero)) {
                    for (anchor in listOf(
                        IntRect(100, 400, 380, 448), IntRect(700, 700, 980, 748),
                        IntRect(-50, -50, 20, 20), IntRect(200, 100, 400, 148)
                    )) {
                        val right = anchor.right + 4
                        val left = anchor.left - 4 - popupSize.width
                        val candidates = if (direction == LayoutDirection.Ltr) listOf(right, left) else listOf(left, right)
                        val maxX = (windowSize.width - 8 - popupSize.width).coerceAtLeast(8)
                        val maxY = (windowSize.height - 8 - popupSize.height).coerceAtLeast(8)
                        val expectedX = candidates.firstOrNull { it in 8..maxX } ?: candidates.first().coerceIn(8, maxX)
                        assertEquals(
                            IntOffset(expectedX, (anchor.top - 8).coerceIn(8, maxY)),
                            provider.calculatePosition(anchor, windowSize, direction, popupSize)
                        )
                    }
                }
            }
        }
    }

    @Test fun narrowWindowClampsWithoutMovingToTheTop() {
        assertEquals(IntOffset(52, 192), provider.calculatePosition(
            IntRect(20, 200, 280, 248), IntSize(300, 800), LayoutDirection.Ltr, menu))
    }
}
