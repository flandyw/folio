package com.folio.notes

import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.*
import org.junit.Test

class UiTouchGuardTests {
    @Test fun aPenNoPageHasTakenIsHeldBackFromThePageColumn() {
        // Both pen tips are pens; nothing else is.
        assertTrue(isPen(PointerType.Stylus))
        assertTrue(isPen(PointerType.Eraser))
        assertFalse(isPen(PointerType.Touch))
        // A page without an ink surface yet, a gap between pages or the margin beside them must not
        // drag the document.
        assertTrue(holdsPenFromScrolling(PointerType.Stylus, false))
        assertTrue(holdsPenFromScrolling(PointerType.Eraser, false))
        // A page, button or slider that took the pen keeps it, so writing is never interrupted.
        assertFalse(holdsPenFromScrolling(PointerType.Stylus, true))
        assertFalse(holdsPenFromScrolling(PointerType.Eraser, true))
        // Fingers and mice are how the workspace is navigated, so they are always let through.
        assertFalse(holdsPenFromScrolling(PointerType.Touch, false))
        assertFalse(holdsPenFromScrolling(PointerType.Mouse, false))
    }

    @Test fun ordinaryFingerInputWorksBeforeAnyPenActivity() {
        val gesture = UiTouchGesture(StylusActivity())
        assertFalse(gesture.reject(1, true, true, 1000))
        assertFalse(gesture.reject(1, true, false, 1050))
    }

    @Test fun aPalmCannotBecomeAClickWhenTheSuppressionWindowExpires() {
        val stylus = StylusActivity().apply { record(1000) }
        val gesture = UiTouchGesture(stylus)
        assertTrue(gesture.reject(1, true, true, 1100))
        assertTrue(gesture.reject(1, true, true, 1700))
        assertTrue(gesture.reject(1, true, false, 1800))
        // A deliberate new gesture works after the resting contact has lifted.
        assertFalse(gesture.reject(1, true, true, 1900))
    }

    @Test fun penArrivalCancelsAFingerAlreadyHeldOnAControl() {
        val stylus = StylusActivity()
        val gesture = UiTouchGesture(stylus)
        assertFalse(gesture.reject(1, true, true, 1000))
        stylus.record(1010)
        assertTrue(gesture.reject(1, true, true, 1020))
        assertTrue(gesture.reject(1, true, false, 2000))
    }

    @Test fun dialogsSharePenActivityAndKeepPenAndMouseUsable() {
        val stylus = StylusActivity().apply { record(1000) }
        val editor = UiTouchGesture(stylus)
        val dialog = UiTouchGesture(stylus)
        assertTrue(editor.reject(1, true, true, 1100))
        assertTrue(dialog.reject(2, true, true, 1100))
        assertFalse(dialog.reject(3, false, true, 1100))
        assertFalse(dialog.reject(3, false, false, 1150))
        assertFalse(dialog.reject(4, true, true, 1500))
        assertTrue(dialog.reject(2, true, false, 1600))
    }
}
