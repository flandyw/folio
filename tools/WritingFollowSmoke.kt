package com.folio.notes

import kotlin.math.abs

// Deterministic handwriting traces for the writing-follow engine. Page units; letters sit on a
// baseline with a 12-unit body, the shape of ordinary handwriting at a comfortable zoom.

private fun letter(x: Float, baseline: Float = 100f, top: Float = baseline - 12f, bottom: Float = baseline, width: Float = 14f) = listOf(
    InkPoint(x, top), InkPoint(x + width, top + (bottom - top) * .25f),
    InkPoint(x + width - 2f, bottom), InkPoint(x + width, bottom - 2f))

private fun descender(x: Float, baseline: Float = 100f) = letter(x, baseline, baseline - 12f, baseline + 14f)
private fun capital(x: Float, baseline: Float = 100f) = letter(x, baseline, baseline - 22f, baseline)
private fun dot(x: Float, y: Float) = listOf(InkPoint(x, y), InkPoint(x + 1f, y + 1f))
private fun rule(x: Float, y: Float, width: Float) = listOf(InkPoint(x, y), InkPoint(x + width / 2, y + .5f), InkPoint(x + width, y))

/** A screen over a page: content pans by screen pixels, ink lives in page units. */
private class FakeHost(var pageWidth: Float = 840f, var pageHeight: Float = 1188f, var infinite: Boolean = false,
                       var viewW: Float = 400f, var viewH: Float = 500f, override var scale: Float = 2f) : FollowHost {
    var x = 0f       // viewport left, page units
    var y = 0f       // viewport top, page units
    val ink = mutableListOf<InkMark>()
    var due: Long? = null
    var last = FollowStatus()
    override var busy = false
    var clampX = true
    override fun viewport() = InkBox(x, y, x + viewW / scale, y + viewH / scale)
    override fun panBy(dx: Float, dy: Float): Pair<Float, Float> {
        // Content moving left by dx pixels moves the viewport right by dx / scale.
        var nx = x - dx / scale
        var ny = y - dy / scale
        if (!infinite) {
            if (clampX) nx = nx.coerceIn(0f, maxOf(0f, pageWidth - viewW / scale))
            ny = ny.coerceIn(0f, maxOf(0f, pageHeight - viewH / scale))
        }
        val applied = -(nx - x) * scale to -(ny - y) * scale
        x = nx; y = ny
        return applied
    }
    override fun marks(area: InkBox) = ink.filter { it.box.intersects(area) }
    override fun schedule(delayMs: Long) { due = delayMs }
    override fun cancelSchedule() { due = null }
    override fun status(status: FollowStatus) { last = status }
    override fun redraw(delayMs: Long) = Unit
}

private class Trace(val host: FakeHost = FakeHost(), prefs: FollowPreferences = FollowPreferences(),
                    guides: List<WritingGuide> = emptyList()) {
    val follow = WritingFollow(host)
    var now = 0L
    init {
        follow.reset(FollowPage(host.infinite, host.pageWidth, host.pageHeight))
        follow.preferences = prefs
        follow.guides = guides
        follow.enabled = true
    }
    fun write(points: List<InkPoint>, gap: Long = 150) {
        now += gap
        follow.penDown(now)
        now += 120
        InkMark.of(points)?.let { host.ink += it }
        follow.strokeFinished(points, now)
    }
    /** Lets every scheduled frame run, as the view's handler would. */
    fun settle(limitMs: Long = 4000) {
        val end = now + limitMs
        while (now < end) {
            val wait = host.due ?: return
            host.due = null
            now += maxOf(wait, 16L)
            follow.tick(now)
        }
    }
    fun moved(block: () -> Unit): Pair<Float, Float> {
        val x = host.x; val y = host.y
        block()
        return host.x - x to host.y - y
    }
}

private var checks = 0
private fun scenario(name: String, block: () -> Unit) {
    try { block(); checks++; println("PASS $name") }
    catch (e: Throwable) { throw AssertionError(name, e) }
}

private fun line(read: LineRead) = (read as? LineRead.Line)?.line ?: error("expected a line, got $read")

fun main() {
    scenario("Descenders, capitals and first-letter descenders share the letters' baseline") {
        val marks = listOf(descender(40f), letter(60f), capital(80f), descender(100f), letter(120f), letter(140f))
            .map { InkMark.of(it)!! }
        val read = line(LineReader.read(marks.last(), marks, emptyList(), FollowMode.TEXT))
        check(read.baseline == 100f) { "baseline ${read.baseline}" }
        check(read.left == 40f && read.right == 154f)
        val first = line(LineReader.read(marks.first(), marks, emptyList(), FollowMode.TEXT))
        check(first.baseline == 100f) { "first-letter descender gave ${first.baseline}" }
    }
    scenario("Dots, underlines and diagrams are not letters") {
        val marks = (0..5).map { InkMark.of(letter(40f + it * 20f))!! }
        check(LineReader.read(InkMark.of(dot(66f, 84f))!!, marks, emptyList(), FollowMode.TEXT) == LineRead.Minor)
        check(LineReader.read(InkMark.of(rule(40f, 104f, 120f))!!, marks, emptyList(), FollowMode.TEXT) == LineRead.Rule)
        val tall = InkMark.of(listOf(InkPoint(200f, 20f), InkPoint(230f, 140f), InkPoint(210f, 60f)))!!
        check(LineReader.read(tall, marks, emptyList(), FollowMode.TEXT) == LineRead.Tall)
    }
    scenario("A second column on the same height is a different line") {
        val left = (0..4).map { InkMark.of(letter(40f + it * 18f))!! }
        val right = (0..4).map { InkMark.of(letter(400f + it * 18f))!! }
        val read = line(LineReader.read(right.last(), left + right, emptyList(), FollowMode.TEXT))
        check(read.left == 400f) { "column leaked: ${read.left}" }
    }
    scenario("Line spacing is measured from the line above on blank paper") {
        val above = (0..4).map { InkMark.of(letter(40f + it * 18f, 100f))!! }
        val below = (0..4).map { InkMark.of(letter(40f + it * 18f, 132f))!! }
        val read = line(LineReader.read(below.last(), above + below, emptyList(), FollowMode.TEXT))
        check(read.measuredPitch && abs(read.pitch - 32f) < .01f) { "pitch ${read.pitch}" }
    }
    scenario("Printed rules decide the line even for a descender crossing the rule below") {
        val guides = listOf(100f, 128f, 156f).map { WritingGuide(36f, 400f, it) }
        val marks = listOf(letter(40f, 100f), descender(60f, 100f)).map { InkMark.of(it)!! }
        val read = line(LineReader.read(marks.last(), marks, guides, FollowMode.TEXT))
        check(read.guide == guides[0] && read.pitch == 28f)
    }
    scenario("Writing across moves the view sideways after a pause, never during the stroke") {
        val t = Trace()
        for (i in 0..7) t.write(letter(30f + i * 18f))
        check(t.host.x == 0f)
        val (dx, _) = t.moved { for (i in 8..10) { t.write(letter(30f + i * 18f)); t.settle() } }
        check(dx > 0f) { "did not follow sideways" }
    }
    scenario("The first stroke after navigating never moves the view") {
        val t = Trace()
        for (i in 0..9) t.write(letter(30f + i * 18f))
        t.settle()
        t.follow.navigated()
        val (dx, dy) = t.moved { t.write(letter(30f + 10 * 18f)); t.settle() }
        check(dx == 0f && dy == 0f)
    }
    scenario("Touching down cancels a pending move; a correction behind the frontier holds still") {
        val t = Trace()
        for (i in 0..10) t.write(letter(30f + i * 18f))
        check(t.host.due != null)
        t.follow.touched()
        check(t.host.due == null && !t.follow.isMoving)
        val (dx, _) = t.moved { t.write(letter(50f)); t.settle() }
        check(dx == 0f) { "correction moved the view" }
    }
    scenario("An interrupted move resumes from where the view is, without doubling") {
        val t = Trace()
        for (i in 0..10) t.write(letter(30f + i * 18f))
        t.now += 2000
        t.follow.tick(t.now)          // the pause has passed: the glide starts
        t.now += 60; t.follow.tick(t.now)
        t.follow.touched()            // stopped part way
        val partial = t.host.x
        t.write(dot(30f + 10 * 18f + 6f, 84f))  // dotting the last letter replans
        t.settle()
        val view = t.host.viewport()
        val reach = (30f + 10 * 18f + 14f - view.left) / view.width
        check(t.host.x > partial && reach in .38f..0.5f) { "frontier landed at $reach" }
    }
    scenario("Without height keeping the page holds still vertically until the bottom edge") {
        val t = Trace()
        for (i in 0..3) t.write(letter(30f + i * 18f, 180f))
        for (i in 0..3) t.write(letter(30f + i * 18f, 212f))
        t.settle()
        check(t.host.y == 0f) { "moved to ${t.host.y}" }
        for (i in 0..3) t.write(letter(30f + i * 18f, 244f))
        t.settle()
        check(t.host.y > 0f) { "never followed to the bottom edge" }
    }
    scenario("Writing sinking below the band moves the line back to the writing height") {
        val t = Trace(prefs = FollowPreferences(keepHeight = true))
        for (i in 0..3) t.write(letter(30f + i * 18f, 180f))
        for (i in 0..3) t.write(letter(30f + i * 18f, 212f))
        t.settle()
        val view = t.host.viewport()
        check(abs((212f - view.top) / view.height - .55f) < .01f) { "line at ${(212f - view.top) / view.height}" }
    }
    scenario("Next line on ruled paper steps rule by rule and stops at the end of the block") {
        val guides = listOf(100f, 128f, 156f).map { WritingGuide(36f, 400f, it) }
        val t = Trace(guides = guides)
        for (i in 0..3) t.write(letter(40f + i * 18f))
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 156f)
        t.follow.nextLine(t.now); t.settle()
        check(t.host.last.message.startsWith("End of this answer area"))
    }
    scenario("Automatic return waits for a pause at the end of a full line and returns to its start") {
        val host = FakeHost(pageWidth = 400f, viewW = 400f, scale = 2f)
        val t = Trace(host, FollowPreferences(automaticReturn = true))
        var x = 40f
        while (x < 340f) { t.write(letter(x)); x += 18f }
        check(t.host.last.message.startsWith("Next line in")) { t.host.last.message }
        t.settle()
        val marker = t.follow.marker(t.now) ?: error("no return")
        check(marker.x == 40f && marker.y > 100f)
    }
    scenario("A short note at the right edge does not trigger a return") {
        val host = FakeHost(pageWidth = 400f, viewW = 400f, scale = 2f)
        val t = Trace(host, FollowPreferences(automaticReturn = true))
        for (i in 0..4) t.write(letter(300f + i * 14f))
        check(!t.host.last.message.startsWith("Next line in"))
    }
    scenario("Back undoes follow moves one at a time and only what actually moved") {
        val t = Trace()
        for (i in 0..12) { t.write(letter(30f + i * 18f)); t.settle() }
        check(t.host.last.canGoBack)
        val moved = t.host.x
        while (t.host.last.canGoBack) t.follow.back(t.now)
        check(t.host.x == 0f && moved > 0f)
    }
    scenario("Right-to-left writing mirrors the sideways move") {
        val host = FakeHost(infinite = true)
        host.x = 0f
        val t = Trace(host, FollowPreferences(direction = WritingDirection.RTL))
        for (i in 0..12) { t.write(letter(180f - i * 18f)); t.settle() }
        check(t.host.x < 0f) { "RTL moved ${t.host.x}" }
    }
    scenario("On a canvas the line end stays where the writer could see it; sideways moves never pass it") {
        val host = FakeHost(infinite = true)
        val t = Trace(host, FollowPreferences(automaticReturn = true))
        var x = 20f
        repeat(40) { t.write(letter(x)); x += 18f; t.settle() }
        val view = t.host.viewport()
        check(view.left < x) { "the end ran out of view" }
        check(t.follow.marker(t.now) != null) { "never returned: ${t.host.last.message}" }
    }
    scenario("Tiny handwriting does not move the view") {
        val t = Trace(FakeHost(scale = .4f))
        val (dx, dy) = t.moved { for (i in 0..30) { t.write(letter(30f + i * 18f)); t.settle() } }
        check(dx == 0f && dy == 0f && t.host.last.message.startsWith("Zoom in"))
    }
    scenario("Maths moves only down, with the bottom of the working") {
        val t = Trace(prefs = FollowPreferences(mode = FollowMode.MATH))
        for (i in 0..3) t.write(letter(30f + i * 18f, 150f))
        t.write(rule(30f, 156f, 70f))
        for (i in 0..3) t.write(letter(30f + i * 18f, 175f))
        for (i in 0..3) t.write(letter(30f + i * 18f, 215f))
        t.settle()
        check(t.host.x == 0f && t.host.y > 0f) { "maths moved ${t.host.x}, ${t.host.y}" }
    }
    scenario("Undoing ink changes what the line is, with no stale frontier") {
        val t = Trace()
        for (i in 0..10) t.write(letter(30f + i * 18f))
        t.follow.touched()
        repeat(6) { t.host.ink.removeAt(t.host.ink.lastIndex) }
        val (dx, _) = t.moved { t.write(letter(30f + 5 * 18f)); t.settle() }
        check(dx == 0f) { "a stale frontier moved the view" }
    }
    scenario("Writing somewhere new holds once, then follows from there") {
        val t = Trace()
        for (i in 0..10) t.write(letter(30f + i * 18f))
        t.follow.touched()
        val (dx, dy) = t.moved { t.write(letter(30f, 230f)); t.settle() }
        check(dx == 0f && dy == 0f) { "a jump moved the view" }
        val (later, _) = t.moved { for (i in 1..10) t.write(letter(30f + i * 18f, 230f)); t.settle() }
        check(later > 0f) { "did not follow the new line" }
    }
    scenario("After Next line, writing on the placed line is progress, not a jump") {
        val guides = (0..8).map { WritingGuide(20f, 800f, 100f + it * 28f) }
        val t = Trace(guides = guides)
        for (i in 0..12) t.write(letter(30f + i * 18f))
        t.settle()
        t.follow.nextLine(t.now); t.settle()
        val (dx, _) = t.moved { for (i in 0..12) t.write(letter(30f + i * 18f, 128f)); t.settle() }
        check(dx > 0f) { "the new line did not follow: ${t.host.last.message}" }
    }
    scenario("Next line works from ink written before follow was turned on") {
        val t = Trace()
        t.follow.enabled = false
        for (i in 0..5) t.write(letter(30f + i * 18f))
        t.follow.enabled = true
        t.follow.nextLine(t.now); t.settle()
        val marker = t.follow.marker(t.now) ?: error(t.host.last.message)
        check(marker.x == 30f && marker.y > 100f)
    }
    scenario("Glide easing reaches its target exactly and reports clamped travel") {
        val motion = FollowMotion()
        motion.start(-100f, 0f, 200)
        var sum = 0f
        var t = 0L
        while (motion.active) {
            val step = motion.step(t); sum += step.dx; motion.applied(step.dx, 0f); t += 16
            if (step.finished) break
        }
        check(abs(sum + 100f) < .01f && abs(motion.appliedX + 100f) < .01f)
    }
    println("$checks writing follow traces passed")
}
