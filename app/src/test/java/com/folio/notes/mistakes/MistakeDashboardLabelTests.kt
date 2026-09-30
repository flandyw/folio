package com.folio.notes.mistakes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class MistakeDashboardLabelTests {
    @Test fun dueLabelsHighlightOverdueDaysAndUpcomingReviews() {
        val now = timestamp("2026-09-16T12:00:00.000Z")
        fun label(hours: Long) = dueLabel(isoTime(now + TimeUnit.HOURS.toMillis(hours)), now)
        assertEquals("3d overdue", label(-72))
        assertEquals("Due today", label(-2))
        assertEquals("Due today", label(0))
        assertEquals("Due today", label(2))
        assertEquals("Due tomorrow", label(24))
        assertEquals("Due in 3d", label(72))
    }

    @Test fun previewIsOneBoundedStringAcrossParagraphsListsAndMath() {
        val source = "Consider functions f and g.\n\n\$\$f(x) = x^2\$\$\n\n" +
            List(40) { "- Explain the domain and range of each function." }.joinToString("\n")
        val preview = RichTextParser.plainText(source, maxLength = 420)
        assertEquals(true, preview.length <= 420)
        assertEquals(false, preview.contains('\n'))
        assertEquals(true, preview.endsWith("…"))
    }
}
