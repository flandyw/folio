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
                    guides: List<WritingGuide> = emptyList(), private val interleaveFrames: Boolean = false) {
    val follow = WritingFollow(host)
    var now = 0L
    init {
        follow.reset(FollowPage(host.infinite, host.pageWidth, host.pageHeight))
        follow.preferences = prefs
        follow.guides = guides
        follow.enabled = true
    }
    fun write(points: List<InkPoint>, gap: Long = 150) {
        if (interleaveFrames) pause(gap) else now += gap
        follow.penDown(now)
        now += 120
        InkMark.of(points)?.let { host.ink += it }
        follow.strokeFinished(points, now)
    }
    /** Run real scheduled frames between strokes, including glides interrupted by the next pen-down. */
    fun pause(ms: Long) {
        val end = now + ms
        while (host.due != null) {
            val due = now + maxOf(host.due!!, 16L)
            if (due > end) { host.due = due - end; break }
            host.due = null
            now = due
            follow.tick(now)
        }
        now = end
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

/** Unannotated exam background; raster scale changes, page coordinates do not. */
private class PrintedPage(private val scale: Int = 1) {
    private val width = 840 * scale
    private val height = 1188 * scale
    private val pixels = IntArray(width * height) { -1 }
    fun rect(left: Int, top: Int, right: Int, bottom: Int, color: Int = 0xff333333.toInt()) {
        for (y in top * scale until bottom * scale) for (x in left * scale until right * scale) {
            pixels[y * width + x] = color
        }
    }
    fun rule(left: Int, right: Int, y: Int, dash: Int = right - left, gap: Int = 0) {
        for (x in left until right step dash + gap) rect(x, y, minOf(right, x + dash), y + 1)
    }
    fun prompt(left: Int, y: Int) {
        for (x in left until left + 160 step 9) rect(x, y, x + 4, y + 8)
    }
    fun analyze() = WritingGuides.analyze(pixels, width, height, 840f, 1188f)
}

fun main() {
    scenario("Solid, dashed and dotted response lines define areas at either raster scale") {
        for (scale in listOf(1, 2)) for ((dash, gap) in listOf(340 to 0, 5 to 4, 1 to 5)) {
            val page = PrintedPage(scale)
            for (y in listOf(100, 128, 156)) page.rule(40, 380, y, dash, gap)
            val detected = page.analyze()
            check(detected.guides.size == 3 && detected.areas.size == 1) { "$scale, $dash/$gap: $detected" }
            val area = detected.areas.single()
            check(area.left == 40f && area.right in 374f..380f && abs(area.top - 72f) < .5f && abs(area.bottom - 156f) < .5f)
            check(WritingGuides.areaAt(detected.areas, 80f, 90f) == area)
            check(WritingGuides.areaAt(detected.areas, 80f, 170f) == null)
            check(WritingGuides.next(detected.guides[0], detected.guides) == detected.guides[1])
            check(WritingGuides.next(detected.guides.last(), detected.guides) == null)
        }
    }
    scenario("Question gaps and neighbouring columns keep response areas separate") {
        val page = PrintedPage()
        for (left in listOf(40, 440)) for (y in listOf(100, 128, 156, 212, 240, 268)) {
            page.rule(left, left + 340, y)
        }
        val detected = page.analyze()
        check(detected.guides.size == 12 && detected.areas.size == 4) { "$detected" }
        for (left in listOf(40f, 440f)) {
            val last = detected.guides.single { it.left == left && it.y == 156f }
            check(WritingGuides.next(last, detected.guides) == null)
            check(WritingGuides.spacing(last, detected.guides) == 28f)
        }
    }
    scenario("Question text splits aligned rules even at the same line spacing") {
        val page = PrintedPage()
        for (left in listOf(40, 440)) for (y in listOf(100, 128, 156, 184, 212, 240)) page.rule(left, left + 340, y)
        page.prompt(40, 166)
        val detected = page.analyze()
        check(detected.guides.size == 12 && detected.areas.size == 3) { "$detected" }
        val left = detected.guides.single { it.left == 40f && it.y == 156f }
        val right = detected.guides.single { it.left == 440f && it.y == 156f }
        check(WritingGuides.next(left, detected.guides) == null)
        check(WritingGuides.next(right, detected.guides)?.y == 184f)
    }
    scenario("A long single response line is an area; short isolated underlines are not") {
        val page = PrintedPage()
        page.rule(40, 380, 100, 1, 5)
        page.rule(40, 140, 250)
        val detected = page.analyze()
        check(detected.guides.size == 1 && detected.areas.size == 1) { "$detected" }
        check(WritingGuides.next(detected.guides.single(), detected.guides) == null)
        check(LineReader.guideFor(InkBox(50f, 50f, 65f, 62f), detected.guides) == null)
    }
    scenario("Closed boxes, table borders, thick bars and printed text are not response areas") {
        val page = PrintedPage()
        page.rect(40, 80, 380, 81); page.rect(40, 240, 380, 241)
        page.rect(40, 80, 41, 241); page.rect(379, 80, 380, 241)
        page.rule(40, 380, 120); page.rule(40, 380, 160)
        page.rect(440, 100, 780, 108); page.rect(440, 128, 780, 136)
        page.prompt(440, 200); page.prompt(440, 228)
        val detected = page.analyze()
        check(detected.guides.isEmpty() && detected.areas.isEmpty()) { "$detected" }
    }
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
        t.now += 30; t.follow.tick(t.now)
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
        // At the edge of a zoomed view the room comes first, then the return follows by itself.
        t.settle()
        val marker = t.follow.marker(t.now) ?: error("no return: ${t.host.last.message}")
        check(marker.x == 40f && marker.y > 100f)
    }
    scenario("Detected response lines return at their edge and stop before the next question") {
        val page = PrintedPage()
        for (y in listOf(100, 128, 156, 212, 240)) page.rule(40, 380, y, 1, 5)
        val detected = page.analyze()
        val host = FakeHost(pageWidth = 840f, viewW = 840f, scale = 1f)
        val t = Trace(host, FollowPreferences(automaticReturn = true), detected.guides)
        t.follow.areas = detected.areas
        var x = 40f
        while (x < 370f) { t.write(letter(x)); x += 18f }
        check(t.host.last.message.startsWith("Next line in")) { t.host.last.message }
        t.settle()
        val marker = t.follow.marker(t.now) ?: error("no return")
        check(marker.x == 40f && marker.y == 128f)
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 156f)
        t.follow.nextLine(t.now); t.settle()
        check(t.host.last.message == "End of this answer area")
        x = 40f
        while (x < 370f) { t.write(letter(x, 156f)); x += 18f }
        check(t.host.last.message == "End of this answer area")
        check(t.host.due == null)
    }
    scenario("Right-to-left return uses the same detected response block") {
        val page = PrintedPage()
        for (y in listOf(100, 128)) page.rule(440, 780, y)
        val detected = page.analyze()
        val t = Trace(FakeHost(), FollowPreferences(direction = WritingDirection.RTL, automaticReturn = true), detected.guides)
        t.follow.areas = detected.areas
        for (x in 760 downTo 454 step 18) t.write(letter(x.toFloat()))
        check(t.host.last.message.startsWith("Next line in")) { t.host.last.message }
        t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
        check(t.follow.marker(t.now)?.x == 774f)
    }
    scenario("Maths Next line also stops at the detected answer area's last response line") {
        val page = PrintedPage()
        for (y in listOf(100, 128, 184, 212)) page.rule(40, 380, y)
        val detected = page.analyze()
        val t = Trace(prefs = FollowPreferences(mode = FollowMode.MATH), guides = detected.guides)
        t.follow.areas = detected.areas
        for (i in 0..3) t.write(letter(40f + i * 18f))
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
        t.follow.nextLine(t.now); t.settle()
        check(t.host.last.message == "End of this answer area")
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
    scenario("Small handwriting at high zoom glides through a full exam answer and returns exactly once") {
        val guides = listOf(100f, 128f, 156f).map { WritingGuide(40f, 800f, it) }
        val host = FakeHost(viewW = 480f, viewH = 600f, scale = 8f).apply { x = 30f; y = 50f }
        val t = Trace(host, FollowPreferences(automaticReturn = true), guides)
        for (baseline in listOf(100f, 128f)) {
            var returned = false
            for (x in 40..796 step 6) {
                t.write(letter(x.toFloat(), baseline, baseline - 4f, baseline, 4f)); t.settle()
                val marker = t.follow.marker(t.now)
                if (marker != null && marker.y == baseline + 28f) {
                    check(x >= 770) { "returned more than a word before the end: $x" }
                    check(marker.x == 40f)
                    check(marker.x in host.viewport().left..host.viewport().right)
                    check(abs((marker.y - host.y) / host.viewport().height - .55f) < .01f)
                    returned = true
                    break
                }
                check(x + 4f in host.viewport().left..host.viewport().right) { "pen left the view at $x: ${host.viewport()}" }
            }
            check(returned) { "never returned at $baseline: ${host.last}" }
        }
    }
    scenario("The last exam line still glides to reveal its ending at high zoom") {
        val host = FakeHost(viewW = 480f, viewH = 600f, scale = 8f).apply { x = 30f; y = 50f }
        val t = Trace(host, FollowPreferences(automaticReturn = true), listOf(WritingGuide(40f, 800f, 100f)))
        for (x in 40..790 step 6) {
            t.write(letter(x.toFloat(), 100f, 96f, 100f, 4f)); t.settle()
        }
        check(794f in host.viewport().left..host.viewport().right)
        check(t.follow.marker(t.now) == null)
        t.follow.nextLine(t.now)
        check(host.last.message == "End of this answer area")
    }
    scenario("A fast writer gets edge room between strokes without waiting for a whole glide") {
        val host = FakeHost(viewW = 480f, viewH = 600f, scale = 8f).apply { x = 30f; y = 50f }
        val t = Trace(host, FollowPreferences(automaticReturn = true),
            listOf(100f, 128f).map { WritingGuide(40f, 800f, it) }, interleaveFrames = true)
        for (x in 40..790 step 6) {
            t.write(letter(x.toFloat(), 100f, 96f, 100f, 4f), gap = 120)
            check(x + 4f in host.viewport().left..host.viewport().right) { "ran out of space at $x: ${host.viewport()}" }
        }
        t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
    }
    scenario("Without auto-return a canvas keeps gliding across many screens in either direction") {
        for (direction in WritingDirection.entries) {
            val t = Trace(FakeHost(infinite = true), FollowPreferences(direction = direction))
            val sign = if (direction == WritingDirection.LTR) 1f else -1f
            val start = if (sign > 0) 30f else 160f
            for (i in 0..60) {
                val x = start + i * 18f * sign
                t.write(letter(x)); t.settle()
                val end = if (sign > 0) x + 14f else x
                check(end in t.host.viewport().left..t.host.viewport().right) { "$direction stopped at $i" }
            }
            check(abs(t.host.x) > 600f)
            t.follow.nextLine(t.now); t.settle()
            check(t.follow.marker(t.now)?.x == if (sign > 0) start else start + 14f)
        }
    }
    scenario("Canvas auto-return keeps its chosen width across glides and several lines") {
        for (screens in listOf(1, 2, 4)) for (direction in WritingDirection.entries) {
            val t = Trace(FakeHost(infinite = true), FollowPreferences(automaticReturn = true,
                canvasLineScreens = screens, direction = direction))
            val sign = if (direction == WritingDirection.LTR) 1f else -1f
            val start = if (sign > 0) 30f else 170f
            val end = start + sign * 200f * screens
            var baseline = 100f
            repeat(3) {
                var returned: FollowMarker? = null
                for (i in 0..(screens * 34)) {
                    val leading = start + sign * i * 6f
                    val x = if (sign > 0) leading else leading - 4f
                    t.write(letter(x, baseline, baseline - 4f, baseline, 4f)); t.settle()
                    val marker = t.follow.marker(t.now)
                    if (marker != null && marker.y > baseline + 1f) {
                        check(abs(leading - end) < 32f) { "$screens screens wrapped at $leading, expected $end" }
                        check(marker.x == start)
                        returned = marker
                        break
                    }
                }
                baseline = (returned ?: error("$screens screens never returned ($direction): ${t.host.last}")).y
            }
        }
    }
    scenario("Writing past a cancelled canvas wrap reveals more space") {
        val t = Trace(FakeHost(infinite = true), FollowPreferences(automaticReturn = true, canvasLineScreens = 1))
        for (i in 0..25) t.write(letter(30f + i * 18f)) // Keep cancelling the pending wrap by continuing.
        t.settle()
        check(494f in t.host.viewport().left..t.host.viewport().right)
        check(t.follow.marker(t.now) == null)
    }
    scenario("Interrupting or double-tapping Next line never skips an unwritten line") {
        val t = Trace(guides = listOf(100f, 128f, 156f).map { WritingGuide(36f, 800f, it) })
        for (i in 0..20) t.write(letter(40f + i * 18f))
        t.settle()
        t.follow.nextLine(t.now)
        t.now += 100; t.follow.tick(t.now)
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 156f)
    }
    scenario("Undo during a pending glide cancels movement without needing a touch") {
        val t = Trace()
        for (i in 0..10) t.write(letter(30f + i * 18f))
        check(t.host.due != null)
        repeat(4) { t.host.ink.removeAt(t.host.ink.lastIndex) }
        val travel = t.moved { t.settle() }
        check(travel == (0f to 0f))
        check(t.host.last.message.startsWith("Writing changed"))
    }
    scenario("Next line and a stray dot cannot resurrect a completely erased line") {
        val t = Trace()
        for (i in 0..10) t.write(letter(30f + i * 18f))
        t.follow.touched(); t.host.ink.clear()
        val travel = t.moved { t.follow.nextLine(t.now); t.settle() }
        check(travel == (0f to 0f) && t.follow.marker(t.now) == null)
        check(t.host.last.message.startsWith("Write a line first"))
        val afterDot = t.moved { t.write(dot(220f, 85f)); t.settle() }
        check(afterDot == (0f to 0f))
    }
    scenario("Paused writing stays still and explicit Next line uses the newly written line") {
        val t = Trace(FakeHost(infinite = true))
        for (i in 0..4) t.write(letter(30f + i * 18f))
        t.follow.paused = true
        val travel = t.moved { for (i in 0..4) t.write(letter(30f + i * 18f, 200f)); t.settle() }
        check(travel == (0f to 0f))
        t.follow.nextLine(t.now); t.settle()
        check((t.follow.marker(t.now)?.y ?: 0f) > 200f)
        check(t.follow.paused)
    }
    scenario("After navigating, Next line reads the visible paragraph instead of the old one") {
        val t = Trace(FakeHost(infinite = true))
        for (i in 0..10) t.write(letter(30f + i * 18f))
        t.host.x = 500f; t.host.y = 400f
        t.follow.navigated()
        for (i in 0..4) t.host.ink += InkMark.of(letter(530f + i * 18f, 500f))!!
        t.follow.nextLine(t.now); t.settle()
        val marker = t.follow.marker(t.now) ?: error(t.host.last.message)
        check(marker.x == 530f && marker.y > 500f)
    }
    scenario("Automatic return holds before existing writing; explicit Next line can enter it") {
        val guides = listOf(100f, 128f).map { WritingGuide(40f, 380f, it) }
        val t = Trace(prefs = FollowPreferences(automaticReturn = true), guides = guides)
        for (i in 0..4) t.host.ink += InkMark.of(letter(40f + i * 18f, 128f))!!
        for (i in 0..18) t.write(letter(40f + i * 18f))
        check(t.host.last.message.startsWith("Next line already has ink")) { t.host.last.message }
        t.settle()
        check(t.follow.marker(t.now) == null)
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
    }
    scenario("Starting an adjacent answer block holds still even at the usual line spacing") {
        val guides = listOf(WritingGuide(40f, 380f, 180f, 0), WritingGuide(40f, 380f, 208f, 1))
        val t = Trace(prefs = FollowPreferences(keepHeight = true), guides = guides)
        for (i in 0..3) t.write(letter(40f + i * 18f, 180f))
        val travel = t.moved { t.write(letter(40f, 208f)); t.settle() }
        check(travel == (0f to 0f))
    }
    scenario("Pending movement targets an absolute viewport position") {
        val t = Trace(FakeHost(infinite = true))
        for (i in 0..10) t.write(letter(30f + i * 18f))
        t.host.x = 10f // Camera shifted before the first animation frame.
        t.settle()
        val reach = (224f - t.host.x) / t.host.viewport().width
        check(abs(reach - .42f) < .01f) { "relative target drifted: $reach" }
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
    scenario("Next line is a carriage return: down and back to the start of the answer line") {
        val page = PrintedPage()
        for (y in listOf(100, 128, 156)) page.rule(40, 380, y)
        val detected = page.analyze()
        // Zoomed in: the 340-unit response line is wider than the 200-unit view.
        val t = Trace(FakeHost(viewW = 400f, scale = 2f), guides = detected.guides)
        t.follow.areas = detected.areas
        for (i in 0..3) t.write(letter(40f + i * 18f))
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
        check(abs(t.host.x - (40f - 16f)) < 1f) { "start should sit at the leading edge, view left=${t.host.x}" }
        // Having written far along the next line, the following return comes all the way back.
        var x = 40f
        while (x < 330f) { t.write(letter(x, 128f)); x += 18f }
        t.settle()
        check(t.host.x > 100f) { "writing should have carried the view across: ${t.host.x}" }
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 156f && abs(t.host.x - 24f) < 1f) { "left=${t.host.x}" }
    }
    scenario("Next line from a fully visible line only brings the start into view") {
        val host = FakeHost(pageWidth = 840f, viewW = 840f, scale = 1f)
        val t = Trace(host, guides = listOf(100f, 128f).map { WritingGuide(36f, 400f, it) })
        for (i in 0..3) t.write(letter(40f + i * 18f))
        t.follow.nextLine(t.now); t.settle()
        check(t.host.x == 0f)
    }
    scenario("Automatic return fires a word before the end of an indented response line") {
        val page = PrintedPage()
        for (y in listOf(100, 128)) page.rule(40, 380, y)
        val detected = page.analyze()
        val t = Trace(FakeHost(viewW = 840f, scale = 1f), FollowPreferences(automaticReturn = true), detected.guides)
        t.follow.areas = detected.areas
        var x = 40f
        while (x < 300f) { t.write(letter(x)); x += 18f }
        check(!t.host.last.message.startsWith("Next line in")) { "too early: ${t.host.last.message}" }
        while (x < 334f) { t.write(letter(x)); x += 18f }
        check(t.host.last.message.startsWith("Next line in")) { "no room for another word: ${t.host.last.message}" }
        t.settle()
        check(t.follow.marker(t.now)?.let { it.x == 40f && it.y == 128f } == true)
    }
    scenario("Automatic return learns where this writer wraps") {
        val page = PrintedPage()
        for (y in listOf(100, 128, 156, 184)) page.rule(40, 380, y)
        val detected = page.analyze()
        val t = Trace(FakeHost(viewW = 840f, scale = 1f), FollowPreferences(automaticReturn = true), detected.guides)
        t.follow.areas = detected.areas
        fun fill(until: Float, y: Float) { var x = 40f; while (x < until) { t.write(letter(x, y)); x += 18f } }
        fill(290f, 100f)
        check(!t.host.last.message.startsWith("Next line in"))
        t.follow.nextLine(t.now); t.settle()
        fill(290f, 128f)
        check(t.host.last.message.startsWith("Next line in")) { "learned wrap: ${t.host.last.message}" }
    }
    scenario("Rules inside a ruled answer box are response lines; a sparse box is not") {
        val page = PrintedPage()
        page.rect(40, 80, 380, 81); page.rect(40, 240, 380, 241)
        page.rect(40, 80, 41, 241); page.rect(379, 80, 380, 241)
        for (y in listOf(120, 160)) page.rule(40, 380, y)
        check(page.analyze().guides.isEmpty())
        val boxed = PrintedPage()
        boxed.rect(40, 80, 380, 81); boxed.rect(40, 250, 380, 251)
        boxed.rect(40, 80, 41, 251); boxed.rect(379, 80, 380, 251)
        for (y in listOf(110, 138, 166, 194, 222)) boxed.rule(40, 380, y)
        val detected = boxed.analyze()
        check(detected.guides.map { it.y.toInt() } == listOf(110, 138, 166, 194, 222)) { "$detected" }
    }
    scenario("Detection streams rows, matches the whole-array scan and is reused from the cache") {
        val page = PrintedPage(scale = 2)
        for (y in 0 until 50) page.prompt(40, 20 + y * 18)      // a page of printed text
        for (y in listOf(1000, 1028, 1056)) page.rule(40, 380, y, 3, 3)
        val started = System.nanoTime()
        val found = page.analyze()
        val ms = (System.nanoTime() - started) / 1_000_000
        println("  (analyze 1680x2376 text page: $ms ms)")
        check(found.guides.size == 3 && found.areas.size == 1) { "$found" }
        val first = WritingGuides.cached("k") { found }
        check(WritingGuides.cached("k") { error("recomputed") } === first)
    }
    scenario("Page furniture is not an answer: header and divider rules, labelled gridlines, leader lines") {
        val page = PrintedPage()
        page.prompt(40, 28); page.rule(40, 780, 44)                 // running header and its rule
        page.rule(40, 780, 300)                                     // divider with the header's extent
        page.prompt(80, 452); page.rule(80, 645, 500)               // a real single-line answer
        for (y in listOf(700, 730, 760, 790)) {                     // chart gridlines with axis labels
            page.rect(60, y - 4, 74, y + 4); page.rule(80, 645, y)
        }
        page.rect(176, 900, 190, 908); page.rect(610, 900, 624, 908); page.rule(200, 600, 904) // contents leader
        // A two-line contents table: text at both ends of each leader, which sit 26 units apart.
        for (y in listOf(1000, 1026)) { page.rect(176, y - 4, 190, y + 4); page.rect(650, y - 4, 664, y + 4); page.rule(200, 640, y) }
        // A chart axis with tick marks across it.
        page.rule(100, 500, 1100)
        for (x in 100..500 step 80) page.rect(x, 1094, x + 1, 1106)
        val detected = page.analyze()
        check(detected.areas.size == 1 && detected.areas.single().bottom == 500f) { "$detected" }
    }
    scenario("An answer area starts under question text that reaches into its writing room") {
        val page = PrintedPage()
        page.prompt(40, 112)                                          // text ending at y = 120
        for (y in listOf(128, 156)) page.rule(40, 380, y)
        val tall = page.analyze().areas.single()
        check(tall.top in 119f..132f) { "area should start under the text: ${tall.top}" }
        val clear = PrintedPage()
        for (y in listOf(128, 156)) clear.rule(40, 380, y)
        check(abs(clear.analyze().areas.single().top - 100f) < .5f)  // nothing above: one line spacing
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
