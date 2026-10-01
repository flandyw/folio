package com.folio.notes.mistakes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MistakeDashboardLabelTests {
    @Test fun dueLabelsHighlightOverdueDaysAndUpcomingReviews() {
        val zone = ZoneId.of("Australia/Melbourne")
        val now = timestamp("2026-10-01T02:00:00.000Z") // noon in Melbourne
        fun label(hours: Long) = dueLabel(isoTime(now + TimeUnit.HOURS.toMillis(hours)), now, zone)
        assertEquals("3d overdue", label(-72))
        assertEquals("Due today", label(-2))
        assertEquals("Due today", label(0))
        assertEquals("Later today · 14:00", label(2))
        assertEquals("Due tomorrow", label(13)) // less than 20 hours, but a different day
        assertEquals("Due tomorrow", label(24))
        assertEquals("Due in 3d", label(72))
    }

    @Test fun labelsAndDueQueueAgreeUntilTheScheduledTimeArrives() {
        val zone = ZoneId.of("Australia/Melbourne")
        val now = timestamp("2026-10-01T02:00:00.000Z")
        val dueAt = "2026-10-01T08:00:00.000Z" // 18:00, later today
        val card = requireNotNull(ExamTrackMistakeCodec.decode(org.json.JSONObject()
            .put("id", "exam-2").put("attemptId", "a").put("question", "Q3e(ii)")
            .put("category", "Reasoning").put("explanation", "").put("correction", "")
            .put("createdAt", dueAt).put("updatedAt", dueAt).put("dueAt", dueAt).toString()))
        assertEquals("Later today · 18:00", dueLabel(dueAt, now, zone))
        assertEquals(0, MistakeScheduler.getDueMistakes(listOf(card), now).size)
        assertEquals("Due today", dueLabel(dueAt, timestamp(dueAt), zone))
        assertEquals(1, MistakeScheduler.getDueMistakes(listOf(card), timestamp(dueAt)).size)
        assertEquals("1d overdue", dueLabel(dueAt, timestamp("2026-10-01T14:00:00.000Z"), zone))
        assertEquals("Due tomorrow", dueLabel("2026-10-04T13:00:00.000Z", timestamp("2026-10-03T14:30:00.000Z"), zone))
    }

    @Test fun previewIsOneBoundedStringAcrossParagraphsListsAndMath() {
        val source = "Consider functions f and g.\n\n\$\$f(x) = x^2\$\$\n\n" +
            List(40) { "- Explain the domain and range of each function." }.joinToString("\n")
        val preview = RichTextParser.plainText(source, maxLength = 420)
        assertEquals(true, preview.length <= 420)
        assertEquals(false, preview.contains('\n'))
        assertEquals(true, preview.endsWith("…"))
    }

    @Test fun previewPreservesMathAcrossParagraphsAndListsAsOneInlineParagraph() {
        val source = "Let \\(f\\) be differentiable.\n\n\\[\\boxed{\\frac{1}{2}}\\]\n\n" +
            "- Find \\(g'(7)\\).\n- **Explain** your answer."
        val preview = RichTextParser.previewInlines(source)
        assertEquals(listOf("f", "\\boxed{\\frac{1}{2}}", "g'(7)"),
            preview.filterIsInstance<RichInline.Math>().map { it.latex })
        assertEquals(true, preview.filterIsInstance<RichInline.Math>().all { !it.display })
        assertEquals(false, preview.any { it == RichInline.Break })
        assertEquals(true, preview.filterIsInstance<RichInline.Run>().all { '\n' !in it.text })
        assertEquals(true, preview.filterIsInstance<RichInline.Run>().any { it.bold && it.text == "Explain" })
    }

    @Test fun previewBudgetNeverCutsThroughMathAndKeepsCodeLiteral() {
        val latex = "\\boxed{\\frac{1}{2}}"
        val preview = RichTextParser.previewInlines("Answer \\($latex\\) and explain", maxLength = 10)
        assertEquals(listOf(latex), preview.filterIsInstance<RichInline.Math>().map { it.latex })
        assertEquals(RichInline.Run("…"), preview.last())
        val code = RichTextParser.previewInlines("`\\frac{1}{2}`")
        assertEquals(true, code.none { it is RichInline.Math })
        assertEquals(true, code.filterIsInstance<RichInline.Run>().any { it.code })
    }

}
