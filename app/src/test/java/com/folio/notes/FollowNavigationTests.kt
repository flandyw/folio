package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class FollowNavigationTests {
    private val area = WritingLane(50f, 80f, 400f, 180f)
    private val guides = listOf(WritingGuide(50f, 400f, 100f), WritingGuide(50f, 400f, 132f), WritingGuide(50f, 400f, 164f))

    @Test fun explicitReturnWorksBeforeTheEndOfAShortLine() {
        val advance = FollowNavigation.next(101f, area, guides, 40f)!!
        assertEquals(132f, advance.to.y, 0f)
        assertEquals(50f, advance.to.left, 0f)
    }
    @Test fun blankPaperUsesSelectedMarginsAndSpacing() {
        val advance = FollowNavigation.next(110f, area, emptyList(), 36f)!!
        assertEquals(WritingGuide(50f, 400f, 146f), advance.to)
    }
    @Test fun cannotLeaveTheAnswerAreaOrLastPrintedRule() {
        assertNull(FollowNavigation.next(170f, area, emptyList(), 32f))
        assertNull(FollowNavigation.next(164f, area, guides, 32f))
        assertNull(FollowNavigation.next(132f, area.copy(bottom = 150f), guides, 32f))
    }
    @Test fun ignoresRulesInAnotherAnswerColumn() {
        val otherColumn = listOf(WritingGuide(450f, 750f, 120f))
        assertEquals(132f, FollowNavigation.next(100f, area, guides + otherColumn, 32f)!!.to.y, 0f)
    }
    @Test fun endCueDoesNotRequireTouchingTheExactMargin() {
        assertTrue(FollowNavigation.nearEnd(listOf(InkPoint(380f, 100f)), area, WritingDirection.LTR))
        assertFalse(FollowNavigation.nearEnd(listOf(InkPoint(320f, 100f)), area, WritingDirection.LTR))
        assertTrue(FollowNavigation.nearEnd(listOf(InkPoint(65f, 100f)), area, WritingDirection.RTL))
        assertFalse(FollowNavigation.nearEnd(listOf(InkPoint(65f, 100f)), area, WritingDirection.LTR))
    }
    @Test fun tallFractionsAndUnderlinesCannotTriggerAutomaticReturn() {
        assertFalse(FollowNavigation.isTextStroke(listOf(InkPoint(390f, 80f), InkPoint(390f, 130f)), 32f))
        assertFalse(FollowNavigation.isTextStroke(listOf(InkPoint(200f, 100f), InkPoint(390f, 100f)), 32f))
        assertTrue(FollowNavigation.isTextStroke(listOf(InkPoint(375f, 90f), InkPoint(385f, 100f)), 32f))
    }
    @Test fun changingAnswerAreasCannotReturnOutsideTheNewArea() {
        val advance = FollowNavigation.next(20f, area, emptyList(), 32f)!!
        assertEquals(112f, advance.to.y, 0f)
        assertNull(FollowNavigation.next(220f, area, emptyList(), 32f))
    }
    @Test fun nextLineShortcutDoesNotChangeTheCurrentTool() {
        assertEquals(StylusShortcutEffect.NextLine, StylusShortcuts.effect(StylusShortcut.NEXT_LINE, Tool.PEN, Tool.HIGHLIGHTER))
        assertEquals(StylusShortcut.NEXT_LINE, StylusShortcut.of("NEXT_LINE"))
    }
}
