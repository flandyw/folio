package com.folio.notes

import kotlin.math.abs

private fun letter(x: Float, top: Float = 88f, bottom: Float = 100f, width: Float = 16f) = listOf(
    InkPoint(x, top), InkPoint(x + width, top + (bottom - top) * .25f),
    InkPoint(x + width - 2f, bottom), InkPoint(x + width, bottom - 2f))

private fun cursive(x: Float, width: Float, top: Float = 88f, bottom: Float = 100f): List<InkPoint> =
    (0..(width / 4f).toInt()).map { i -> InkPoint(x + i * 4f, if (i % 2 == 0) top else bottom) }

private fun seeded(direction: WritingDirection = WritingDirection.LTR, guides: List<WritingGuide> = emptyList()): WritingFollow {
    val follow = WritingFollow()
    val prefs = FollowPreferences(direction = direction)
    val xs = if (direction == WritingDirection.LTR) listOf(40f, 80f, 120f) else listOf(160f, 120f, 80f)
    xs.forEachIndexed { i, x -> check(follow.completed(letter(x), i * 200L, prefs, guides) == WritingProgress.SAME_LINE) }
    return follow
}

private var checks = 0
private fun scenario(name: String, block: () -> Unit) {
    try { block(); checks++; println("PASS $name") }
    catch (e: Throwable) { throw AssertionError(name, e) }
}

/** Deliver ordinary frames after the glide's initial zero frame. */
private fun advanceGlide(glide: FollowGlide, from: Long, to: Long,
                         apply: (FollowGlide.Step) -> Unit): FollowGlide.Step {
    var now = from
    var step = FollowGlide.Step()
    while (now < to) {
        now = minOf(now + 16, to)
        step = glide.step(now)
        apply(step)
    }
    return step
}

fun main() {
    val prefs = FollowPreferences(automaticReturn = true)
    val guides = listOf(100f, 128f, 156f).map { WritingGuide(36f, 300f, it) }
    scenario("Descenders and full-height f keep the old baseline and advance the frontier") {
        val follow = seeded()
        for (i in 0..7) {
            check(follow.completed(letter(160f + i * 20, 80f, 114f), 600L + i * 200, prefs) == WritingProgress.SAME_LINE)
            check(follow.state.baselineY == 100f && follow.state.candidateLane == null)
        }
        check(follow.state.frontierRight == 316f && follow.readyForReturn())
    }
    scenario("First-letter descender recovers on a blank page and snaps correctly on ruled paper") {
        for (rules in listOf(emptyList(), guides)) {
            val follow = WritingFollow()
            check(follow.completed(letter(40f, 80f, 114f), 0, prefs, rules) == WritingProgress.SAME_LINE)
            if (rules.isNotEmpty()) check(follow.state.baselineY == 100f)
            check(follow.completed(letter(65f), 200, prefs, rules) == WritingProgress.SAME_LINE)
            check(follow.state.baselineY == 100f)
        }
    }
    scenario("Mixed descender and short letters confirm a natural blank-page line change") {
        val follow = seeded()
        check(follow.completed(letter(40f, 108f, 142f), 800, prefs) == WritingProgress.NONE)
        check(follow.completed(letter(65f, 116f, 128f), 1000, prefs) == WritingProgress.NEW_LINE)
        check(follow.state.baselineY == 128f && follow.state.lineStartX == 40f)
        for (i in 0..3) {
            check(follow.completed(letter(90f + i * 20, 116f, 128f), 1200L + i * 200, prefs) == WritingProgress.SAME_LINE)
            check(follow.state.needsPlacement) { "A later letter lost the new-line glide" }
        }
        follow.placed()
        check(!follow.state.needsPlacement)
    }
    scenario("Tall capitals at the next line's start remain confirmable") {
        val follow = seeded()
        check(follow.completed(letter(40f, 86f, 128f), 800, prefs) == WritingProgress.NONE)
        check(follow.completed(letter(65f, 116f, 128f), 1000, prefs) == WritingProgress.NEW_LINE)
    }
    scenario("A clear one-stroke cursive line places itself without another confirmation mark") {
        val follow = seeded()
        check(follow.completed(cursive(40f, 260f, 116f, 128f), 800, prefs) == WritingProgress.NEW_LINE)
        val region = WritingLane(36f, 72f, 310f, 180f)
        check(follow.returnFor(region, emptyList(), prefs) == null)
        follow.placed()
        val next = follow.returnFor(region, emptyList(), prefs)!!
        check(next.from.y == 128f && next.to.y == 160f)
        follow.arrived(next)
        check(follow.returnFor(region, emptyList(), prefs) == null) { "A return must not chain into empty lines" }
    }
    scenario("A wrapping list item returns to its text; plain lines and manual returns keep the line start") {
        val region = WritingLane(36f, 72f, 310f, 180f)
        fun write(follow: WritingFollow, xs: List<Float>, width: Float) =
            xs.forEachIndexed { i, x -> check(follow.completed(letter(x, width = width), i * 200L, prefs) == WritingProgress.SAME_LINE) }
        val item = WritingFollow()
        write(item, listOf(40f), 8f)
        check(item.completed(letter(52f, 96f, 100f, 4f), 200, prefs) == WritingProgress.SAME_LINE)
        listOf(76f, 116f, 156f, 196f, 236f, 276f).forEachIndexed { i, x ->
            check(item.completed(letter(x), 400L + i * 200, prefs) == WritingProgress.SAME_LINE)
        }
        check(item.state.lineStartX == 40f && item.state.textStartX == 76f)
        check(item.returnFor(region, emptyList(), prefs)!!.to.left == 76f)
        check(FollowNavigation.next(100f, region, emptyList(), 28f, item.state.lineStartX)!!.to.left == 40f)
        val plain = WritingFollow()
        listOf(40f, 60f, 80f, 100f, 120f, 140f, 160f, 180f, 200f, 220f, 240f, 260f, 280f).forEachIndexed { i, x ->
            check(plain.completed(letter(x), i * 200L, prefs) == WritingProgress.SAME_LINE)
        }
        check(plain.state.textStartX == null && plain.returnFor(region, emptyList(), prefs)!!.to.left == 40f)
    }
    scenario("A cursive word's own dots and crossbars resume a glide; a smooth flat word is still text") {
        val follow = WritingFollow()
        check(follow.completed(cursive(40f, 200f, 88f, 100f), 0, prefs) == WritingProgress.SAME_LINE)
        check(follow.finishingMark(FollowNavigation.bounds(letter(100f, 84f, 86f, 4f))!!, prefs))
        check(follow.finishingMark(FollowNavigation.bounds(letter(60f, 90f, 92f, 14f))!!, prefs))
        check(!follow.finishingMark(FollowNavigation.bounds(letter(10f, 84f, 86f, 4f))!!, prefs))
        val flat = (0..18).map { i -> InkPoint(40f + i * 16f, if (i % 2 == 0) 90f else 100f) }
        check(FollowNavigation.isTextStroke(flat, 32f, 19f))
        val underline = listOf(InkPoint(40f, 100f), InkPoint(200f, 101f), InkPoint(340f, 100f))
        check(!FollowNavigation.isTextStroke(underline, 32f, 19f))
    }
    scenario("Printed rules detect a clear next line immediately, including small letters") {
        val follow = seeded(guides = guides)
        check(follow.completed(letter(40f, 124f, 128f, 4f), 800, prefs, guides) == WritingProgress.NEW_LINE)
        check(follow.state.needsPlacement && follow.state.baselineY == 128f)
        check(follow.completed(letter(48f, 124f, 128f, 4f), 1000, prefs, guides) == WritingProgress.SAME_LINE)
    }
    scenario("Printed spacing takes priority over a mismatched blank-page preference") {
        val follow = WritingFollow()
        check(follow.completed(letter(40f, 80f, 114f), 0, prefs.copy(spacing = 16f), guides) == WritingProgress.SAME_LINE)
        check(follow.state.baselineY == 100f)
    }
    scenario("One-stroke cursive words can finish a line; long straight underlines cannot") {
        val follow = WritingFollow()
        check(follow.completed(cursive(40f, 260f), 0, prefs) == WritingProgress.SAME_LINE)
        check(follow.readyForReturn())
        val region = WritingLane(36f, 72f, 310f, 156f)
        check(FollowNavigation.nearEnd(follow.state.frontierRight!!, region, prefs.direction))
        check(follow.completed(listOf(InkPoint(40f, 100f), InkPoint(300f, 100f)), 200, prefs) == WritingProgress.NONE)
        check(!follow.isTextStroke(listOf(InkPoint(40f, 100f), InkPoint(150f, 102f)), 32f))
    }
    scenario("Finishing dots and crossbars retain the accepted line end and reset the quiet period") {
        val follow = seeded()
        val dot = listOf(InkPoint(133f, 82f), InkPoint(134f, 83f))
        check(follow.completed(dot, 600, prefs) == WritingProgress.NONE)
        check(follow.finishingMark(FollowNavigation.bounds(dot)!!, prefs))
        check(follow.state.frontierRight == 136f && follow.state.baselineY == 100f)
        check(follow.finishingMark(FollowNavigation.bounds(letter(130f, 90f, 92f, 12f))!!, prefs))
        check(!follow.finishingMark(FollowNavigation.bounds(letter(40f, 90f, 92f, 12f))!!, prefs))
    }
    scenario("Corrections do not destroy a staged line change") {
        val follow = seeded()
        check(follow.completed(letter(40f, 116f, 128f), 800, prefs) == WritingProgress.NONE)
        val candidate = follow.state.candidateLane
        check(follow.completed(letter(80f), 1000, prefs) == WritingProgress.NONE)
        check(follow.state.candidateLane == candidate)
        check(follow.completed(letter(65f, 116f, 128f), 10000, prefs) == WritingProgress.NEW_LINE)
    }
    scenario("Deep descender corrections on ruled paper hold the old line instead of snapping down") {
        val follow = seeded(guides = guides)
        for (x in listOf(40f, 65f)) {
            check(follow.completed(letter(x, 80f, 120f), 800, prefs, guides) == WritingProgress.NONE)
            check(follow.state.baselineY == 100f && follow.state.candidateLane == null)
        }
        check(follow.completed(letter(40f, 86f, 128f), 1000, prefs, guides) == WritingProgress.NONE)
        check(follow.completed(letter(65f, 116f, 128f), 1200, prefs, guides) == WritingProgress.NEW_LINE)
    }
    scenario("Configured pauses stay fixed through fast writing, long gaps and finishing marks") {
        for (delay in listOf(300, 650, 2000)) {
            val custom = prefs.copy(returnDelayMs = delay)
            val follow = seeded()
            var now = 400L
            for ((i, gap) in listOf(60L, 110L, 450L, 1600L, 75L, 900L).withIndex()) {
                now += gap + 180
                check(follow.completed(letter(160f + i * 24), now, custom) == WritingProgress.SAME_LINE)
                val glide = FollowGlide()
                glide.start(-400f, 0f, now, custom.glideDelayMs, custom.glideDurationMs, 1000f, 1000f)
                check(glide.step(now + delay / 2 - 1).waitMs == 1L)
                check(glide.step(now + delay / 2).dx == 0f)
                glide.cancel()
                val frontier = follow.state.frontierRight!!
                val dot = listOf(InkPoint(frontier - 3f, 82f), InkPoint(frontier - 2f, 83f))
                now += delay + 30
                check(follow.completed(dot, now, custom) == WritingProgress.NONE)
                check(follow.finishingMark(FollowNavigation.bounds(dot)!!, custom))
                glide.start(-400f, 0f, now, custom.glideDelayMs, custom.glideDurationMs, 1000f, 1000f)
                check(glide.step(now + delay / 2 - 1).waitMs == 1L)
                glide.start(-400f, -32f, now, custom.automaticReturnDelayMs, custom.glideDurationMs, 1000f, 1000f)
                check(glide.step(now + delay - 1).waitMs == 1L)
                check(glide.step(now + delay).dx == 0f)
            }
        }
    }
    scenario("Dotting an i interrupts the edge glide and starts a fresh full pause") {
        for (direction in WritingDirection.entries) {
            val follow = seeded(direction)
            val custom = prefs.copy(direction = direction, glideDurationMs = 650)
            val frontier = if (direction == WritingDirection.LTR) follow.state.frontierRight!! else follow.state.frontierLeft!!
            val fraction = if (direction == WritingDirection.LTR) .94f else .06f
            val glide = FollowGlide()
            val dx = follow.horizontalShift(fraction, .5f, direction) * 1000f
            glide.start(dx, 0f, 400, custom.glideDelayMs, custom.glideDurationMs, 1000f, 1000f)
            check(glide.step(500).waitMs == 225L) { "The edge started moving during a letter gap" }
            glide.cancel()
            val dot = listOf(InkPoint(frontier - 2f, 82f), InkPoint(frontier - 1f, 83f))
            check(follow.completed(dot, 600, custom) == WritingProgress.NONE)
            check(follow.finishingMark(FollowNavigation.bounds(dot)!!, custom))
            glide.start(dx, 0f, 600, custom.glideDelayMs, custom.glideDurationMs, 1000f, 1000f)
            check(glide.step(800).waitMs == 125L)
            check(glide.step(925).dx == 0f)
            val half = advanceGlide(glide, 925, 1250) { glide.applied(it.dx, it.dy) }
            check(!half.finished) { "A Gentle glide was shortened at the edge" }
            check(glide.step(1266).dx != 0f)
        }
    }
    scenario("RTL uses the same descender, frontier, return and natural line mechanics") {
        val follow = seeded(WritingDirection.RTL)
        val rtl = prefs.copy(direction = WritingDirection.RTL)
        check(follow.completed(letter(40f, 80f, 114f), 800, rtl) == WritingProgress.SAME_LINE)
        check(follow.state.frontierLeft == 40f && follow.state.baselineY == 100f)
        check(follow.completed(letter(160f, 116f, 128f), 1000, rtl) == WritingProgress.NONE)
        check(follow.completed(letter(136f, 116f, 128f), 1200, rtl) == WritingProgress.NEW_LINE)
        check(follow.state.lineStartX == 176f)
    }
    scenario("Navigation resets placement and frontiers and suspends recognition briefly") {
        val follow = seeded()
        follow.completed(letter(160f), 600, prefs)
        follow.suspend(700)
        check(follow.state.baselineY == null && !follow.state.needsPlacement)
        check(follow.completed(letter(40f), 1000, prefs) == WritingProgress.NONE)
        check(follow.completed(letter(40f), 2300, prefs) == WritingProgress.SAME_LINE)
    }
    scenario("Infinite-canvas endpoints survive multiple following pans in both directions") {
        for (direction in WritingDirection.entries) {
            val start = if (direction == WritingDirection.LTR) 40f else 260f
            val original = FollowNavigation.infiniteRegion(WritingLane(0f, 0f, 300f, 500f), direction, start)
            for (offset in listOf(100f, 250f, 500f, 800f)) {
                val x = if (direction == WritingDirection.LTR) offset else -offset
                val lane = FollowNavigation.infiniteRegion(WritingLane(x, 20f, x + 300f, 520f), direction, start)
                check(lane.left == original.left && lane.right == original.right)
            }
        }
    }
    scenario("Answer-area margins allow tails but reject writing in another area") {
        val area = WritingLane(36f, 72f, 300f, 128f)
        check(FollowNavigation.contains(WritingLane(280f, 112f, 302f, 140f), area, 28f))
        check(!FollowNavigation.contains(WritingLane(280f, 150f, 300f, 168f), area, 28f))
        check(!FollowNavigation.contains(WritingLane(320f, 110f, 340f, 120f), area, 28f))
        check(FollowNavigation.next(128f, area, guides, 32f) == null)
    }
    scenario("Printed answer blocks keep their full extent and remain separate across questions/columns") {
        val otherColumn = guides.map { it.copy(left = 350f, right = 614f) }
        val nextQuestion = guides.map { it.copy(y = it.y + 180f) }
        val areas = WritingGuides.regions(guides + otherColumn + nextQuestion)
        check(areas.size == 3)
        val first = WritingGuides.regionAt(areas, 60f, 100f)!!
        check(WritingGuides.regionAt(areas, 60f, 156f) == first)
        check(FollowNavigation.contains(WritingLane(280f, 140f, 300f, 172f), first, 28f))
        check(FollowNavigation.next(156f, first, guides + otherColumn + nextQuestion, 32f) == null)
        check(FollowNavigation.next(128f, first, guides + otherColumn + nextQuestion, 32f)?.to == guides[2])
    }
    scenario("Manual arrival clears the old frontier and accepts small first letters") {
        val follow = seeded()
        follow.arrived(WritingAdvance(guides[0], guides[1]))
        check(follow.state.frontierRight == null && !follow.readyForReturn())
        check(follow.completed(letter(40f, 124f, 128f, 4f), 800, prefs, guides) == WritingProgress.SAME_LINE)
        check(follow.state.baselineY == 128f)
    }
    scenario("Maths grows downward without text classification or sideways returns") {
        val follow = WritingFollow()
        val math = prefs.copy(mode = FollowMode.MATH)
        check(follow.completed(letter(40f, 60f, 140f), 0, math) == WritingProgress.SAME_LINE)
        check(follow.completed(letter(60f, 80f, 138f), 200, math) == WritingProgress.NONE)
        check(follow.completed(letter(80f, 100f, 164f), 400, math) == WritingProgress.SAME_LINE)
        check(follow.state.baselineY == 164f && !follow.state.needsPlacement)
    }
    scenario("Invalid geometry and tall diagrams cannot establish or change a text lane") {
        val follow = seeded()
        check(follow.completed(listOf(InkPoint(Float.NaN, 100f)), 800, prefs) == WritingProgress.NONE)
        check(follow.completed(letter(160f, 40f, 150f), 1000, prefs) == WritingProgress.NONE)
        check(follow.state.baselineY == 100f)
        check(!FollowLegibility.isReadable(4f, 1f) && FollowLegibility.isReadable(4f, 2f))
    }
    scenario("Delayed frames start at zero; glides have smooth endpoints and exact total travel") {
        val glide = FollowGlide()
        glide.start(-200f, -56f, 0, 300, 280, 1000f, 1000f)
        check(glide.step(100).waitMs == 200L)
        val first = glide.step(900)
        check(first.dx == 0f && first.dy == 0f && !first.finished)
        var sumX = 0f; var sumY = 0f
        for (now in 916L..1180L step 16) {
            val step = glide.step(now)
            check(step.dx <= 0f && step.dy <= 0f)
            glide.applied(step.dx, step.dy)
            sumX += step.dx; sumY += step.dy
        }
        val last = glide.step(1200)
        glide.applied(last.dx, last.dy)
        sumX += last.dx; sumY += last.dy
        check(last.finished && abs(sumX + 200f) < .001f && abs(sumY + 56f) < .001f && glide.reachedLine)
    }
    scenario("Large pans take longer and keep the same speed across viewport sizes and directions") {
        for (size in listOf(500f, 1000f, 2000f)) for (sign in listOf(-1f, 1f)) {
            val glide = FollowGlide()
            glide.start(sign * size * .6f, sign * size * .2f, 0, 0, 180, size, size)
            glide.step(0)
            var x = 0f; var y = 0f
            val middle = advanceGlide(glide, 0, 211) {
                x += it.dx; y += it.dy; glide.applied(it.dx, it.dy)
            }
            check(!middle.finished && abs(x / size - sign * .3f) < .003f)
            val end = advanceGlide(glide, 211, 421) {
                x += it.dx; y += it.dy; glide.applied(it.dx, it.dy)
            }
            check(end.finished && abs(x / size - sign * .6f) < .001f && glide.reachedLine)
            check(abs(y / size - sign * .2f) < .001f)
        }
    }
    scenario("Busy frames slow the glide instead of jumping to the end") {
        val glide = FollowGlide()
        glide.start(-200f, -56f, 0, 0, 280, 1000f, 1000f)
        glide.step(0)
        val busy = glide.step(500)
        glide.applied(busy.dx, busy.dy)
        check(!busy.finished && abs(busy.dx) < 5f)
        var x = busy.dx; var y = busy.dy
        val end = advanceGlide(glide, 500, 756) {
            x += it.dx; y += it.dy; glide.applied(it.dx, it.dy)
        }
        check(end.finished && abs(x + 200f) < .001f && abs(y + 56f) < .001f && glide.reachedLine)
        glide.cancel()
        check(!glide.active && !glide.moved)
        glide.start(50f, 0f, 800, 100, 280, 1000f, 1000f)
        check(glide.step(850).waitMs == 50L && glide.step(900).dx == 0f)
    }
    scenario("Horizontal-only clamped movement cannot pretend to complete a vertical return") {
        val glide = FollowGlide()
        glide.start(-200f, -56f, 0, 0, 280, 1000f, 1000f)
        glide.step(0)
        advanceGlide(glide, 0, 280) { glide.applied(it.dx, 0f) }
        check(glide.moved && !glide.reachedLine)
    }
    scenario("Interrupt/replan uses remaining absolute travel and Back records only actual movement") {
        val history = FollowBackHistory()
        val state = seeded().state
        val glide = FollowGlide()
        var y = 300f
        val desiredY = 220f
        history.begin(state)
        glide.start(-100f, desiredY - y, 0, 0, 280, 1000f, 1000f)
        glide.step(0)
        advanceGlide(glide, 0, 140) {
            y += it.dy
            glide.applied(it.dx, it.dy)
            history.moved(it.dx, it.dy)
        }
        check(!glide.reachedLine)
        val previous = history.entry
        glide.cancel()
        history.cancelPending()
        history.begin(state)
        glide.start(0f, desiredY - y, 200, 0, 280, 1000f, 1000f)
        glide.step(200)
        val remainingY = desiredY - y
        advanceGlide(glide, 200, 480) {
            y += it.dy
            glide.applied(0f, it.dy)
            history.moved(0f, it.dy)
        }
        check(abs(y - desiredY) < .001f && glide.reachedLine)
        check(previous != null && abs(history.entry!!.y - remainingY) < .001f)
        val entry = history.entry
        history.begin(state)
        history.moved(0f, 0f)
        history.cancelPending()
        check(history.entry == entry)
    }
    scenario("Scaled handwriting traces stay stable across glyph sizes, spacing and direction") {
        for (direction in WritingDirection.entries) for (height in listOf(4f, 8f, 12f, 20f)) {
            val spacing = maxOf(16f, height * 2.5f)
            val follow = WritingFollow()
            val custom = prefs.copy(direction = direction, spacing = spacing)
            val baseline = 100f
            for (i in 0..12) {
                val x = if (direction == WritingDirection.LTR) 40f + i * height * 2 else 600f - i * height * 2
                val tail = if (i > 1 && i % 3 == 0) height * .7f else 0f
                check(follow.completed(letter(x, baseline - height, baseline + tail, height), i * 200L, custom) == WritingProgress.SAME_LINE)
                check(abs(follow.state.baselineY!! - baseline) < .01f)
            }
            val x = if (direction == WritingDirection.LTR) 40f else 600f
            check(follow.completed(letter(x, baseline + spacing - height, baseline + spacing, height), 3000, custom) == WritingProgress.NONE)
            val second = if (direction == WritingDirection.LTR) x + height * 2 else x - height * 2
            check(follow.completed(letter(second, baseline + spacing - height, baseline + spacing, height), 3200, custom) == WritingProgress.NEW_LINE)
        }
    }
    scenario("Split ruled paper gives each column its own rules, so a line ends at the divider") {
        val guides = WritingGuides.ruled(840f, 1188f, split = true)
        val plain = WritingGuides.ruled(840f, 1188f)
        check(guides.size == plain.size * 2 && plain.all { it.block == null })
        val left = guides.filter { it.block == 0 }
        val right = guides.filter { it.block == 1 }
        check(left.all { it.left == 36f && it.right == 420f } && right.all { it.left == 420f && it.right == 804f })
        // A return stays in its column and steps one rule down.
        val first = left.first()
        check(WritingGuides.next(first, guides) == left[1])
        check(WritingGuides.regions(guides).size == 2)
    }
    println("Writing follow: $checks scenarios passed.")
}
