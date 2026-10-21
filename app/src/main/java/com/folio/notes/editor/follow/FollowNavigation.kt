package com.folio.notes.editor.follow

import com.folio.notes.model.InkPoint
import kotlin.math.abs
import kotlin.math.hypot

object FollowNavigation {
    /** A sideways trigger is useful only while the answer area still extends beyond the visible edge. */
    fun hasRoomAhead(left: Float, right: Float, area: WritingLane, direction: WritingDirection): Boolean =
        if (direction == WritingDirection.LTR) right < area.right else left > area.left

    /** A lane narrower than this is a mistake, not a column; returns never aim that far in. */
    const val MIN_LANE_UNITS = 48f

    fun bounds(points: List<InkPoint>): WritingLane? {
        if (points.isEmpty() || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        return WritingLane(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    /** Margins contain the letter body; a tail or crossbar may protrude a little. */
    fun contains(box: WritingLane, region: WritingLane, spacing: Float): Boolean {
        val xSlack = minOf(8f, spacing * .2f)
        val ySlack = spacing * .6f
        return box.left >= region.left - xSlack && box.right <= region.right + xSlack &&
            box.top >= region.top - ySlack && box.bottom <= region.bottom + ySlack
    }

    fun isTextStroke(points: List<InkPoint>, spacing: Float, descenderAllowance: Float = 0f): Boolean {
        val box = bounds(points) ?: return false
        if (!spacing.isFinite() || spacing <= 0f ||
            box.height >= spacing * .9f + descenderAllowance.coerceIn(0f, spacing * .6f)) return false
        // Long cursive words may cover the whole lane. Their repeated up/down travel
        // distinguishes them from a long underline without an arbitrary word-width cap.
        val direct = hypot(box.width, box.height)
        var travel = 0f
        for (i in 1..points.lastIndex) travel += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        // A flat, smooth cursive word has less travel than a looped one, but unlike an underline it has height.
        return if (box.width > 220f) travel > direct * 1.35f || (box.height >= spacing * .25f && travel > direct * 1.15f)
        else box.width <= maxOf(64f, spacing * 2f) || travel > direct * 1.15f
    }

    /**
     * The writing lane on an unbounded canvas: the visible viewport inset by the end margin,
     * with the writer's own start column kept as the leading edge, even when off screen.
     *
     * An infinite page carries a nominal page box it is not bound by, so a lane measured from
     * page coordinates puts "end of line" somewhere the writer cannot see. Anchoring the lane
     * to the viewport instead makes the line end the visible edge at every zoom, which is what
     * lets automatic line return work on a canvas at all.
     *
     * The trailing edge is measured from the start column plus the viewport width, not from
     * the current viewport edge, so following pans do not chase the end of the line away:
     * same-line pans move the viewport but leave the page-space line end where it was, and
     * writing reliably reaches it.
     */
    fun infiniteRegion(viewport: WritingLane, direction: WritingDirection, startX: Float? = null,
                       endMargin: Float = .08f): WritingLane {
        val width = viewport.right - viewport.left
        if (!width.isFinite() || width <= 0f) return viewport
        // Canvas coordinates have no physical size. Fixed page-unit caps change the
        // visible margin with zoom and can make a narrow viewport's lane wider than itself.
        val margin = width * endMargin.coerceIn(.02f, .2f)
        // A full viewport lane inset on both sides; the trailing edge below derives from
        // this width so pans never move it.
        val laneWidth = width - 2f * margin
        // Following can move the start column off screen. Only deliberate navigation
        // resets it (WritingFollow.suspend); falling back here would chase the line end away.
        val remembered = startX?.takeIf { it.isFinite() }
        val (left, right) = if (direction == WritingDirection.LTR) {
            if (remembered != null) remembered to remembered + laneWidth
            else {
                val trailing = viewport.right - margin
                (viewport.left + margin) to trailing
            }
        } else {
            if (remembered != null) remembered - laneWidth to remembered
            else {
                val leading = viewport.left + margin
                leading to (viewport.right - margin)
            }
        }
        // The viewport top is not a paper boundary. A return from a baseline above
        // the screen still advances exactly one line, even after an interrupted pan.
        return WritingLane(left, -Float.MAX_VALUE, right, Float.MAX_VALUE)
    }

    fun next(baseline: Float, region: WritingLane, guides: List<WritingGuide>, spacing: Float,
             lineStartX: Float? = null, direction: WritingDirection = WritingDirection.LTR,
             overrideStart: Boolean = false): WritingAdvance? {
        if (!baseline.isFinite() || baseline > region.bottom) return null
        val currentY = baseline.coerceAtLeast(region.top)
        val eligible = guides.filter { it.left >= region.left - 6 && it.right <= region.right + 6 && it.y in region.top..region.bottom }
        val current = eligible.minByOrNull { abs(it.y - currentY) }?.takeIf { abs(it.y - currentY) <= 16f }
        val next = current?.let { WritingGuides.next(it, eligible) }
        // A detected answer block ends at its last rule. Do not spill into the next question.
        if (current != null && next == null) return null
        val y = next?.y ?: (currentY + spacing.coerceIn(16f, 96f))
        if (y > region.bottom) return null
        // Printed rules keep their margins unless the writer deliberately overrides the indent.
        val start = lineStartX?.takeIf { it.isFinite() }?.coerceIn(region.left, region.right)
        val left = if (direction == WritingDirection.LTR) start ?: region.left else region.left
        val right = if (direction == WritingDirection.RTL) start ?: region.right else region.right
        val destination = if (next != null && overrideStart && start != null)
            if (direction == WritingDirection.LTR) next.copy(left = start.coerceIn(next.left, next.right))
            else next.copy(right = start.coerceIn(next.left, next.right))
            else next ?: WritingGuide(left, right, y)
        return WritingAdvance(current ?: WritingGuide(left, right, baseline), destination)
    }
    /**
     * The line above [baseline], for going back to correct or add to it: the printed rule before
     * the current one, or one spacing up on blank paper, never above the area's top.
     */
    fun previous(baseline: Float, region: WritingLane, guides: List<WritingGuide>, spacing: Float,
                 lineStartX: Float? = null, direction: WritingDirection = WritingDirection.LTR): WritingAdvance? {
        if (!baseline.isFinite() || baseline < region.top) return null
        val currentY = baseline.coerceAtMost(region.bottom)
        val eligible = guides.filter { it.left >= region.left - 6 && it.right <= region.right + 6 && it.y in region.top..region.bottom }
        val current = eligible.minByOrNull { abs(it.y - currentY) }?.takeIf { abs(it.y - currentY) <= 16f }
        val previous = current?.let { line -> eligible.filter { WritingGuides.next(it, eligible) == line }.maxByOrNull { it.y } }
        // The first rule of an answer block has nothing above it in that block.
        if (current != null && previous == null) return null
        val y = previous?.y ?: (currentY - spacing.coerceIn(16f, 96f))
        if (y < region.top) return null
        val start = lineStartX?.takeIf { it.isFinite() }?.coerceIn(region.left, region.right)
        val left = if (direction == WritingDirection.LTR) start ?: region.left else region.left
        val right = if (direction == WritingDirection.RTL) start ?: region.right else region.right
        return WritingAdvance(current ?: WritingGuide(left, right, baseline),
            previous ?: WritingGuide(left, right, y))
    }

    fun nearEnd(points: List<InkPoint>, region: WritingLane, direction: WritingDirection, endMargin: Float = .08f): Boolean {
        if (points.isEmpty()) return false
        val frontier = if (direction == WritingDirection.LTR) points.maxOf { it.x } else points.minOf { it.x }
        return nearEnd(frontier, region, direction, endMargin)
    }

    fun nearEnd(frontier: Float, region: WritingLane, direction: WritingDirection, endMargin: Float = .08f): Boolean {
        if (!frontier.isFinite()) return false
        val proportionalMargin = region.width * endMargin.coerceIn(.02f, .2f)
        val margin = if (region.unbounded) proportionalMargin else proportionalMargin.coerceIn(8f, 96f)
        return if (direction == WritingDirection.LTR) frontier >= region.right - margin
        else frontier <= region.left + margin
    }
}

/**
 * How big writing has to look on screen before following the view is worth doing.
 *
 * A page has an absolute scale, so a zoom factor means something on a printed page. An infinite
 * canvas zooms relative to whatever the view happens to fit, where the same 1.0x can be tiny or
 * huge, so the test is the size of a line of handwriting in screen pixels instead.
 */
object FollowLegibility {
    /** About the smallest handwriting worth gliding the view for. */
    const val MIN_STROKE_PX = 8f

    /** The current lane's median stroke height, measured on screen. */
    fun strokePx(laneHeight: Float, scale: Float): Float =
        if (laneHeight.isFinite() && scale.isFinite() && scale > 0f) laneHeight * scale else 0f

    fun isReadable(laneHeight: Float, scale: Float): Boolean = strokePx(laneHeight, scale) >= MIN_STROKE_PX
}
