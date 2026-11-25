package com.folio.notes.music

import org.junit.Assert.*
import org.junit.Test

class MusicJumpTargetTest {
    @Test fun pastedSpacesAroundAPageNumberAreAccepted() {
        assertEquals(2, musicJumpTarget(" 3 ", 5, 0))
        assertEquals(0, musicJumpTarget("1\n", 5, 3))
        assertEquals(4, musicJumpTarget("\t5", 5, 0))
    }

    @Test fun pagesOutsideTheScoreTheSamePageAndNonNumbersAreRejected() {
        listOf("", "  ", "0", "6", "-1", "two", "3.5", "1 2", "99999999999").forEach {
            assertNull(it, musicJumpTarget(it, 5, 0))
        }
        assertNull(musicJumpTarget(" 1 ", 5, 0))
    }
}
