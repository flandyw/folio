package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression cover for high-stroke-count optimisations: cached width multipliers stay in step
 * with the batch pass, hoisted lasso bounds agree with the per-stroke walk, and dense erase
 * paths stay allocation-light (same-reference fast paths) without changing semantics.
 */
class DensePagePerfTests {
    private fun penStroke(seed: Int, n: Int = 24): Stroke {
        val pts = ArrayList<InkPoint>(n)
        var x = seed.toFloat() * 7f
        var y = seed.toFloat() * 3f
        repeat(n) { i ->
            x += 2.5f
            y += kotlin.math.sin((seed + i).toFloat() / 3f) * 2f
            pts += InkPoint(x, y, 0.6f + (i % 4) * 0.2f)
        }
        return Stroke(Tool.PEN, 0xFF000000.toInt(), 3f, pts)
    }

    @Test fun renderedWidthsMatchCentreAndIncremental() {
        val points = (0 until 80).map { InkPoint(it * 2.1f, kotlin.math.sin(it / 4f) * 5f, 0.8f) }
        val batch = InkRenderer.rendered(Stroke(Tool.PEN, 0, 3f, points))
        assertNotNull(batch.taper)
        assertNotNull(batch.widths)
        assertEquals(batch.centre.size, batch.widths!!.size)
        // Multipliers stay positive and bounded: pressure compression × taper floor..1.
        assertTrue(batch.widths!!.all { it in 0.3f..1.6f })

        val live = InkRenderer.IncrementalPenStroke()
        val drawn = live.update(points)
        assertEquals(batch.centre, drawn.centre)
        assertTrue(InkRenderer.widthsEqual(batch.widths, drawn.widths))
    }

    @Test fun hoistedLassoBoundsAgreeWithPerStrokeWalk() {
        val page = List(500) { penStroke(it) }
        val loop = listOf(
            InkPoint(0f, -50f), InkPoint(400f, -50f),
            InkPoint(400f, 400f), InkPoint(0f, 400f)
        )
        val bounds = InkGeometry.lassoBounds(loop)
        page.forEach { stroke ->
            assertEquals(
                InkGeometry.lassoSelects(loop, stroke),
                InkGeometry.lassoSelects(loop, bounds, stroke)
            )
        }
        // Rect fast path agrees too.
        val box = TextBox(x = 10f, y = 10f, width = 100f, text = "hi")
        assertEquals(
            InkGeometry.lassoSelectsText(loop, box, 20f),
            InkGeometry.lassoSelectsText(loop, bounds, box, 20f)
        )
    }

    @Test fun denseAppendPrefixIsDetectedByIdentity() {
        val page = List(5_000) { penStroke(it, n = 8) }
        // Same instances + one append: only the tail needs paint.
        assertEquals(page.size, appendedInkStart(page, page + penStroke(9_999)))
        // A structural copy (new instances) still requires a full replay.
        assertEquals(0, appendedInkStart(page, page.map { it.copy() }))
    }

    @Test fun scribbleEraseKeepsSemanticsOnDensePage() {
        // A scrub over the first stroke removes it; far ink is untouched.
        val target = Stroke(Tool.PEN, 0, 3f, listOf(
            InkPoint(0f, 0f), InkPoint(20f, 0f), InkPoint(40f, 0f), InkPoint(60f, 0f)))
        val far = Stroke(Tool.PEN, 0, 3f, listOf(
            InkPoint(500f, 500f), InkPoint(520f, 500f)))
        val scrub = Stroke(Tool.PEN, 0, 3f, listOf(
            InkPoint(0f, -4f), InkPoint(60f, 4f), InkPoint(0f, 4f),
            InkPoint(60f, -4f), InkPoint(0f, 0f), InkPoint(60f, 0f)))
        val out = InkGeometry.scribbleErase(listOf(target, far), scrub, 14f, 1f)
        assertTrue(out.none { it === target })
        assertTrue(out.any { it === far })
    }
}
