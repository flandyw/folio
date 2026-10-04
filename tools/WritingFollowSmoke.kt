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
    /** Whether Next line may turn the page, and how many times it did. */
    var pageAvailable = false
    var pageFlips = 0
    override fun nextPage(): Boolean { if (pageAvailable) pageFlips++; return pageAvailable }
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
                    guides: List<WritingGuide> = emptyList(), private val interleaveFrames: Boolean = false,
                    val learner: FollowLearner = FollowLearner(), record: FollowTrace? = null) {
    val follow = WritingFollow(host, learner)
    var now = 0L
    val moves = mutableListOf<Triple<Long, Float, Float>>()
    private fun tickAt(t: Long) {
        val x = host.x; val y = host.y
        follow.tick(t)
        if (host.x != x || host.y != y) moves += Triple(t, host.x, host.y)
    }
    init {
        follow.reset(FollowPage(host.infinite, host.pageWidth, host.pageHeight))
        follow.preferences = prefs
        follow.guides = guides
        follow.trace = record
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
            tickAt(now)
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
            tickAt(now)
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
    /** A row of printed text, as it looks in the raster. */
    fun text(left: Int, y: Int, width: Int = 340) {
        for (x in left until left + width step 9) rect(x, y, x + 4, y + 8)
    }
    fun analyze(text: List<PdfTextLine> = emptyList()) = WritingGuides.analyze(pixels, width, height, 840f, 1188f, text)
}

private fun textLine(top: Float, text: String, left: Float = 40f, right: Float = 380f) =
    PdfTextLine(0, left, top, right, top + 10f, text)

/** Replays recorded events against a fresh engine on a fake screen, the way a trace file is replayed. */
private class Replay(events: List<TraceEvent>, learner: FollowLearner = FollowLearner()) {
    val host = FakeHost()
    val follow = WritingFollow(host, learner)
    val moves = mutableListOf<Triple<Long, Float, Float>>()
    var now = 0L
    private fun frames(until: Long) {
        while (host.due != null) {
            val due = now + maxOf(host.due!!, 16L)
            if (due > until) { host.due = due - until; break }
            host.due = null
            now = due
            val x = host.x; val y = host.y
            follow.tick(now)
            if (host.x != x || host.y != y) moves += Triple(now, host.x, host.y)
        }
        now = maxOf(now, until)
    }
    init {
        for (event in events) {
            if (event is TraceEvent.Moved) continue
            frames(event.t)
            when (event) {
                is TraceEvent.Context -> {
                    host.pageWidth = event.page.width; host.pageHeight = event.page.height; host.infinite = event.page.infinite
                    follow.reset(event.page); follow.preferences = event.preferences
                    follow.guides = event.guides; follow.areas = event.areas; follow.enabled = true
                }
                is TraceEvent.Down -> follow.penDown(event.t)
                is TraceEvent.Up -> {
                    event.view?.let {
                        host.scale = event.scale; host.x = it.left; host.y = it.top
                        host.viewW = it.width * event.scale; host.viewH = it.height * event.scale
                    }
                    host.ink += event.mark
                    follow.strokeFinished(event.mark, event.t)
                }
                is TraceEvent.Touch -> follow.touched()
                is TraceEvent.Next -> follow.nextLine(event.t)
                is TraceEvent.Back -> follow.back(event.t)
                is TraceEvent.Navigated -> follow.navigated()
                is TraceEvent.Hover -> follow.hover(event.x, event.y, event.t)
                is TraceEvent.HoverEnd -> follow.hoverEnded(event.t)
                is TraceEvent.Moved -> Unit
            }
        }
        frames(now + 4000)
    }
}

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        // `node tools/writing-follow-smoke.cjs trace.txt` replays a recorded session against the current engine.
        val events = FollowTrace.parse(java.io.File(args[0]).readText())
        val learner = FollowLearner()
        val replay = Replay(events, learner)
        val recorded = events.count { it is TraceEvent.Moved }
        println("${events.size} events, ${events.count { it is TraceEvent.Up }} strokes, $recorded recorded view moves, ${replay.moves.size} replayed")
        println("automatic moves ${learner.moves}, interrupted or undone ${learner.cancelled}, taken back ${learner.undone}")
        learner.describe(FollowPreferences()).forEach { println("  $it") }
        return
    }
    scenario("A sideways glide travels at a readable pace instead of snapping") {
        val t = Trace(FakeHost(viewW = 400f, scale = 2f), FollowPreferences())
        var x = 30f
        // Stop short of the edge, so the move is a considered glide rather than an urgent one.
        while (x < 160f) { t.write(letter(x)); x += 18f }
        t.settle()
        check(t.moves.size > 3 && t.host.x > 40f) { "no glide: ${t.host.x}" }
        val took = t.moves.last().first - t.moves.first().first
        check(took >= 250) { "glide took only $took ms" }
        var worst = 0f
        for (i in 1 until t.moves.size) {
            val frame = t.moves[i].first - t.moves[i - 1].first
            if (frame in 1..40) worst = maxOf(worst, abs(t.moves[i].second - t.moves[i - 1].second) * 2f / frame)
        }
        check(worst < 3.2f) { "peak $worst px/ms" }
    }
    scenario("Blank paper: a line that climbs still returns, to where the next line begins") {
        for (slope in listOf(0f, .03f, -.03f)) {
            val t = Trace(FakeHost(viewW = 400f, scale = 2f), FollowPreferences(automaticReturn = true), emptyList(), interleaveFrames = true)
            var x = 40f
            var i = 0
            val baseline = 300f
            while (x < 750f) {
                t.write(letter(x, baseline - slope * (x - 40f)), gap = if (i % 5 == 0) 450 else 150)
                x += 18f; i++
            }
            check(t.host.last.message.startsWith("Next line in") || t.follow.isMoving) { "slope $slope: ${t.host.last.message}" }
            val penUp = t.now
            t.settle()
            val marker = t.follow.marker(t.now) ?: error("slope $slope: no line placed (${t.host.last.message})")
            // The next line begins one line spacing under where this one began.
            check(abs(marker.x - 40f) < 1f && abs(marker.y - (baseline + 26.4f)) < 6f) { "slope $slope: marker $marker" }
            val start = t.moves.firstOrNull { it.first > penUp }?.first ?: error("slope $slope: no return")
            val end = t.moves.last().first
            check(end - penUp < 1300) { "slope $slope: return took ${end - penUp} ms after the pen came up" }
            check(t.host.x < 60f) { "slope $slope: view left at ${t.host.x}" }
            check(start >= penUp)
        }
    }
    scenario("A full line starts its return soon after the pen stops") {
        val page = PrintedPage()
        for (y in listOf(100, 128)) page.rule(40, 380, y)
        val detected = page.analyze()
        val t = Trace(FakeHost(viewW = 400f, scale = 2f), FollowPreferences(automaticReturn = true), detected.guides, interleaveFrames = true)
        t.follow.areas = detected.areas
        var x = 40f
        while (x < 340f) { t.write(letter(x)); x += 18f }
        val penUp = t.now
        t.settle()
        val started = t.moves.firstOrNull { it.first > penUp }?.first ?: error("never returned")
        check(started - penUp <= 700) { "return waited ${started - penUp} ms" }
        check(t.moves.last().first - penUp <= 1300) { "back at the start only ${t.moves.last().first - penUp} ms after the pen stopped" }
    }
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
        // Filling the last line never returns by itself into the next question.
        x = 40f
        while (x < 370f) { t.write(letter(x, 156f)); x += 18f }
        check(t.host.last.message == "End of this answer area")
        check(t.host.due == null)
        // Asking for Next line there goes on to the next question, which has no writing yet.
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 212f) { "no jump: ${t.host.last.message}" }
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
        // The last response line of the first answer area leads on to the next one.
        t.follow.nextLine(t.now); t.settle()
        check(t.follow.marker(t.now)?.y == 184f)
        t.follow.nextLine(t.now); t.settle()
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
    // ---- Learning from the writer, hover, prediction ----
    scenario("The learner is shared: rhythm, wrap and word room carry over, and forgetting clears them") {
        val learner = FollowLearner()
        repeat(6) { learner.rhythm.add(200L) }
        learner.learnWrap(.8f); learner.learnWordRoom(6f)
        check(learner.rhythm.wordGapMs() == 200L && learner.wrapReach == .8f && learner.wordRoom == 6f)
        check(learner.describe(FollowPreferences()).isNotEmpty())
        learner.forget()
        check(learner.rhythm.wordGapMs() == null && learner.wrapReach == null && learner.wordRoom == null)
        check(learner.describe(FollowPreferences()).isEmpty())
    }
    scenario("Interrupting automatic returns makes the next ones wait longer, then puts them on hold until asked again") {
        val page = PrintedPage()
        for (y in listOf(500, 528)) page.rule(40, 380, y)
        val detected = page.analyze()
        // Scrolled down the page, so the return has somewhere to travel.
        val t = Trace(FakeHost(viewW = 840f, viewH = 500f, scale = 1f).apply { y = 400f }, FollowPreferences(automaticReturn = true), detected.guides)
        t.follow.areas = detected.areas
        var x = 40f
        while (x < 340f) { t.write(letter(x, 500f)); x += 18f }
        check(t.host.last.message.startsWith("Next line in")) { t.host.last.message }
        val first = t.host.due!!
        // Writing the last word of a line cancels a pending return at every stroke: that is not a verdict.
        repeat(6) { t.follow.touched(); t.write(letter(358f, 500f)) }
        check(t.learner.cancelled == 0 && !t.learner.returnHeld)
        /** Lets the pause pass so the return starts moving, then writes again part-way through it. */
        fun interrupt() {
            t.now += 3000; t.follow.tick(t.now)
            t.now += 30; t.follow.tick(t.now)
            check(t.follow.isGliding) { "return never started: ${t.host.last.message}" }
            t.follow.touched()
            t.write(letter(358f, 500f))
        }
        interrupt(); interrupt()
        check(t.learner.cancelled == 2 && t.learner.returnFactor() == 1f) // too few outcomes to judge yet
        interrupt()
        val later = t.host.due!!
        check(t.learner.returnFactor() > 1.9f && later > first) { "factor ${t.learner.returnFactor()}, $first -> $later" }
        interrupt(); interrupt()
        t.write(letter(358f, 500f))
        check(t.learner.returnHeld && t.host.last.message.contains("on hold") && t.host.due == null) { t.host.last.message }
        // Turning automatic return on again is asking for another chance.
        t.follow.preferences = FollowPreferences(automaticReturn = false)
        t.follow.preferences = FollowPreferences(automaticReturn = true)
        check(!t.learner.returnHeld)
    }
    scenario("Writing on the line an automatic return placed counts as accepting it") {
        val page = PrintedPage()
        for (y in listOf(100, 128, 156)) page.rule(40, 380, y)
        val detected = page.analyze()
        val t = Trace(FakeHost(viewW = 840f, scale = 1f), FollowPreferences(automaticReturn = true), detected.guides)
        t.follow.areas = detected.areas
        var x = 40f
        while (x < 340f) { t.write(letter(x)); x += 18f }
        t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
        val before = t.learner.moves
        t.write(letter(40f, 128f))
        check(t.learner.moves == before + 1 && t.learner.cancelled == 0)
    }
    scenario("Taking back an automatic move soon after counts against it; a plain Next line never does") {
        val t = Trace()
        for (i in 0..12) { t.write(letter(30f + i * 18f)); t.settle() }
        check(t.host.last.canGoBack && t.learner.moves > 0)
        val before = t.learner.cancelled
        t.follow.back(t.now)
        check(t.learner.cancelled == before + 1 && t.learner.undone == 1)
        val guides = listOf(100f, 128f).map { WritingGuide(36f, 400f, it) }
        val explicit = Trace(guides = guides)
        for (i in 0..3) explicit.write(letter(40f + i * 18f))
        explicit.follow.nextLine(explicit.now); explicit.settle(); explicit.follow.back(explicit.now)
        check(explicit.learner.cancelled == 0 && explicit.learner.undone == 0)
    }
    scenario("Pressing Next line at the end of full lines three times offers automatic return, once") {
        val guides = listOf(100f, 128f, 156f, 184f).map { WritingGuide(40f, 380f, it) }
        val t = Trace(FakeHost(viewW = 840f, scale = 1f), guides = guides)
        for (baseline in listOf(100f, 128f, 156f)) {
            var x = 40f
            while (x < 360f) { t.write(letter(x, baseline)); x += 18f }
            t.follow.nextLine(t.now); t.settle()
        }
        check(t.host.last.suggestion == FollowSuggestion.AUTOMATIC_RETURN) { "no offer: ${t.host.last}" }
        t.follow.resolveSuggestion(FollowSuggestion.AUTOMATIC_RETURN)
        check(t.host.last.suggestion == null)
        var x = 40f
        while (x < 360f) { t.write(letter(x, 184f)); x += 18f }
        t.follow.nextLine(t.now)
        check(t.host.last.suggestion == null) { "offered again after being declined" }
        val off = Trace(FakeHost(viewW = 840f, scale = 1f), FollowPreferences(adaptive = false), guides)
        repeat(3) { i -> var y = 40f; while (y < 360f) { off.write(letter(y, 100f + i * 28f)); y += 18f }; off.follow.nextLine(off.now); off.settle() }
        check(off.host.last.suggestion == null)
    }
    scenario("Where the writer puts a line they start themselves nudges the writing height, within bounds") {
        val t = Trace(FakeHost(infinite = true), FollowPreferences(keepHeight = true))
        repeat(3) { i ->
            t.follow.navigated()
            val baseline = 600f + i * 300f
            t.host.x = 0f; t.host.y = baseline - .4f * 250f      // the writer panned so the line sits 40% down
            t.write(letter(30f, baseline)); t.settle()
        }
        check(abs(t.learner.heightShift(.55f) + .1f) < .001f) { "shift ${t.learner.heightShift(.55f)}" }
        t.follow.navigated()
        t.host.y = 0f
        for (i in 0..3) t.write(letter(30f + i * 18f, 180f))
        for (i in 0..3) t.write(letter(30f + i * 18f, 212f))
        t.settle()
        val view = t.host.viewport()
        check(abs((212f - view.top) / view.height - .45f) < .01f) { "line at ${(212f - view.top) / view.height}" }
        val fixed = Trace(FakeHost(infinite = true), FollowPreferences(keepHeight = true, adaptive = false))
        repeat(3) { i -> fixed.follow.navigated(); fixed.host.y = 600f + i * 300f - 100f; fixed.write(letter(30f, 600f + i * 300f)); fixed.settle() }
        check(fixed.learner.heightShift(.55f) == 0f) { "a non-adaptive engine learned a height" }
        fixed.follow.navigated(); fixed.host.y = 0f
        for (i in 0..3) fixed.write(letter(30f + i * 18f, 180f))
        for (i in 0..3) fixed.write(letter(30f + i * 18f, 212f))
        fixed.settle()
        check(abs((212f - fixed.host.y) / 250f - .55f) < .01f)
    }
    scenario("A fast writer's move is judged a little ahead of the pen; a slow or non-adaptive one is not") {
        fun run(prefs: FollowPreferences): Trace {
            val t = Trace(FakeHost(viewW = 400f, viewH = 500f, scale = 1f), prefs)
            for (i in 0..11) t.write(letter(30f + i * 18f))     // the line's end is about 60% across
            return t
        }
        val adaptive = run(FollowPreferences())
        check(adaptive.host.due != null && adaptive.learner.bodiesPerSecond!! > 3f) { "no early plan: ${adaptive.host.last.message}" }
        adaptive.settle()
        check(adaptive.host.x > 40f) { "moved only ${adaptive.host.x}" }
        check(run(FollowPreferences(adaptive = false)).host.due == null)
        // Slow writing (one stroke every 2.5 s) has no lead to look ahead with.
        val slow = Trace(FakeHost(viewW = 400f, viewH = 500f, scale = 1f))
        for (i in 0..11) slow.write(letter(30f + i * 18f), gap = 1400)
        check(slow.host.due == null) { "slow writer was moved early" }
    }
    scenario("A line's own words say how much room one more needs, so a wide-spaced writer returns earlier") {
        val page = PrintedPage()
        for (y in listOf(100, 128)) page.rule(40, 780, y)
        val detected = page.analyze()
        fun run(adaptive: Boolean): String {
            val t = Trace(FakeHost(viewW = 840f, scale = 1f), FollowPreferences(automaticReturn = true, adaptive = adaptive), detected.guides)
            t.follow.areas = detected.areas
            var x = 40f
            // Four-letter words with a wide gap: a word and its gap take about 8 letter heights.
            while (x < 700f) { for (i in 0..3) { t.write(letter(x, 100f)); x += 18f }; x += 26f }
            return t.host.last.message
        }
        check(run(true).startsWith("Next line in")) { run(true) }
        check(!run(false).startsWith("Next line in")) { run(false) }
        val boxes = (0..11).map { i -> val x = 40f + (i / 4) * 96f + (i % 4) * 18f; InkBox(x, 88f, x + 14f, 100f) }
        val span = LineReader.wordSpan(boxes, 12f)!!
        check(abs(span - 96f) < 1f) { "word span $span" }
        check(LineReader.wordSpan((0..11).map { InkBox(40f + it * 18f, 88f, 54f + it * 18f, 100f) }, 12f) == null)
    }
    scenario("A pen leaving hover range starts a pending move sooner; hovering at the frontier holds it back") {
        val t = Trace(FakeHost(viewW = 400f, viewH = 500f, scale = 1f))
        for (i in 0..15) t.write(letter(30f + i * 18f))
        val pause = t.host.due!!
        check(pause >= 200)
        t.follow.hoverEnded(t.now)
        check(t.host.due == 150L) { "due ${t.host.due}" }
        // A pen still hovering at the end of the writing: the move waits a moment, not forever.
        val held = Trace(FakeHost(viewW = 400f, viewH = 500f, scale = 1f))
        for (i in 0..15) held.write(letter(30f + i * 18f))
        var steps = 0
        held.now += 2000
        while (held.host.x == 0f && steps < 12) {
            held.follow.hover(310f, 92f, held.now)
            held.follow.tick(held.now)
            if (steps == 0) check(held.host.x == 0f && held.host.due != null) { "was not held" }
            held.now += 260; steps++
        }
        check(steps in 3..8 && held.host.x > 0f) { "held for $steps steps, x=${held.host.x}" }
    }
    scenario("A move that would leave a hovering pen over writing it hides is dropped") {
        val t = Trace(FakeHost(viewW = 400f, viewH = 500f, scale = 1f))
        for (i in 0..15) t.write(letter(30f + i * 18f))
        check(t.host.due != null)
        t.follow.hover(40f, 92f, t.now)       // the pen points at the start of the line: a correction
        check(t.host.due == null && t.host.last.message.startsWith("Holding still")) { t.host.last.message }
        val travel = t.moved { t.settle() }
        check(travel == (0f to 0f))
        // Hovering where the writing is going is fine.
        val ok = Trace(FakeHost(viewW = 400f, viewH = 500f, scale = 1f))
        for (i in 0..15) ok.write(letter(30f + i * 18f))
        ok.follow.hover(300f, 92f, ok.now)
        check(ok.host.due != null)
    }
    scenario("Hover never moves anything on its own, and a return still returns while the pen hovers over the line") {
        val page = PrintedPage()
        for (y in listOf(100, 128)) page.rule(40, 380, y)
        val detected = page.analyze()
        val t = Trace(FakeHost(viewW = 840f, scale = 1f), FollowPreferences(automaticReturn = true), detected.guides)
        t.follow.areas = detected.areas
        t.follow.hover(100f, 90f, 10L); t.follow.hoverEnded(20L)
        check(t.host.due == null && t.host.x == 0f)
        var x = 40f
        while (x < 340f) { t.write(letter(x)); x += 18f }
        check(t.host.last.message.startsWith("Next line in"))
        t.follow.hover(330f, 92f, t.now)
        check(t.host.due != null)
        t.settle()
        check(t.follow.marker(t.now)?.y == 128f)
    }

    // ---- Headlines, direction, maths ----
    scenario("A headline drawn over a word finishes it, as a dot does; an underline does not") {
        fun run(y: Float): Trace {
            val t = Trace(FakeHost(viewW = 400f, viewH = 500f, scale = 1f))
            for (i in 0..15) t.write(letter(30f + i * 18f))
            t.follow.touched()
            t.write(rule(240f, y, 74f))
            return t
        }
        check(run(88f).host.due != null) { "headline did not restart the move" }
        check(run(106f).host.due == null) { "an underline restarted the move" }
    }
    scenario("Strokes that run against the setting offer the other direction, once, and ordinary writing never does") {
        val rtl = Trace(FakeHost(infinite = true))
        for (i in 0..20) rtl.write(letter(400f - i * 18f))
        check(rtl.host.last.suggestion == FollowSuggestion.RIGHT_TO_LEFT) { "${rtl.host.last}" }
        rtl.follow.resolveSuggestion(FollowSuggestion.RIGHT_TO_LEFT)
        for (i in 0..20) rtl.write(letter(400f - i * 18f, 140f))
        check(rtl.host.last.suggestion == null)
        val ltr = Trace(FakeHost(infinite = true), FollowPreferences(direction = WritingDirection.RTL))
        for (i in 0..20) ltr.write(letter(30f + i * 18f))
        check(ltr.host.last.suggestion == FollowSuggestion.LEFT_TO_RIGHT)
        val plain = Trace(FakeHost(infinite = true))
        for (i in 0..20) plain.write(letter(30f + i * 18f))
        check(plain.host.last.suggestion == null)
    }
    fun equalsSign(x: Float, baseline: Float = 100f) = listOf(rule(x, baseline - 6f, 16f), rule(x, baseline, 16f))
    fun maths(t: Trace, baseline: Float, equalsAt: Float? = 100f, startAt: Float = 40f) {
        var x = startAt
        while (x < (equalsAt ?: 140f) - 20f) { t.write(letter(x, baseline)); x += 18f }
        equalsAt?.let { e -> equalsSign(e, baseline).forEach { t.write(it) }; t.write(letter(e + 24f, baseline)); t.write(letter(e + 42f, baseline)) }
    }
    scenario("Maths returns to the start of the whole row, and under the = once the writer lines working up on it") {
        val plain = Trace(prefs = FollowPreferences(mode = FollowMode.MATH))
        maths(plain, 100f)
        plain.follow.nextLine(plain.now); plain.settle()
        check(plain.follow.marker(plain.now)?.x == 40f) { "marker ${plain.follow.marker(plain.now)}" }
        val trained = Trace(prefs = FollowPreferences(mode = FollowMode.MATH), learner = FollowLearner().apply { learnRow(true) })
        maths(trained, 100f)
        trained.follow.nextLine(trained.now); trained.settle()
        check(trained.follow.marker(trained.now)?.x == 100f) { "marker ${trained.follow.marker(trained.now)}" }
    }
    scenario("A row of working that starts under the = above teaches Maths to line up on it") {
        val t = Trace(prefs = FollowPreferences(mode = FollowMode.MATH))
        maths(t, 100f)
        check(!t.learner.mathAligned)
        t.write(letter(100f, 132f)); t.write(letter(118f, 132f))
        check(t.learner.mathAligned)
        val left = Trace(prefs = FollowPreferences(mode = FollowMode.MATH))
        maths(left, 100f)
        left.write(letter(40f, 132f)); left.write(letter(58f, 132f))
        check(!left.learner.mathAligned)
    }
    scenario("Working full of = signs offers Maths mode in Text, once") {
        val t = Trace(FakeHost(infinite = true))
        for (row in 0..3) maths(t, 100f + row * 34f)
        check(t.host.last.suggestion == FollowSuggestion.MATHS_MODE) { "${t.host.last}" }
        t.follow.resolveSuggestion(FollowSuggestion.MATHS_MODE)
        for (row in 4..8) maths(t, 100f + row * 34f)
        check(t.host.last.suggestion == null)
        val text = Trace(FakeHost(infinite = true))
        for (row in 0..5) for (i in 0..8) text.write(letter(40f + i * 18f, 100f + row * 34f))
        check(text.host.last.suggestion == null)
        val math = Trace(FakeHost(infinite = true), FollowPreferences(mode = FollowMode.MATH))
        for (row in 0..5) maths(math, 100f + row * 34f)
        check(math.host.last.suggestion == null)
    }
    scenario("= signs and fraction bars are recognised, and plain writing has neither") {
        val body = 12f
        val equals = equalsSign(100f).map { InkMark.of(it)!! }
        check(LineReader.equalsAt(equals.map { it.box }, body) == 100f)
        check(LineReader.mathEvidence(equals, body))
        val fraction = listOf(letter(100f, 90f), rule(92f, 94f, 40f), letter(100f, 112f)).map { InkMark.of(it)!! }
        check(LineReader.mathEvidence(fraction, body))
        check(!LineReader.mathEvidence((0..8).map { InkMark.of(letter(40f + it * 18f))!! }, body))
        check(!LineReader.mathEvidence(listOf(InkMark.of(rule(40f, 110f, 80f))!!), body)) // a lone underline
    }

    // ---- Answer areas from the text layer, and moving between them ----
    scenario("White space under a question becomes an answer area when the paper says so and the page is blank there") {
        val lines = listOf(textLine(100f, "Question 1 (3 marks)"), textLine(118f, "Find the derivative of f(x)."),
            textLine(136f, "Hence solve the equation."), textLine(330f, "Question 2 (2 marks)"),
            textLine(348f, "State the range."), textLine(366f, "Explain your reasoning."))
        fun page(figure: Boolean): PrintedPage {
            val page = PrintedPage()
            lines.forEach { page.text(40, it.top.toInt()) }
            if (figure) page.rect(60, 200, 340, 300)
            return page
        }
        val found = page(false).analyze(lines)
        check(found.guides.isEmpty() && found.areas.size == 2) { "$found" }
        check(found.areas[0].top in 150f..157f && found.areas[0].bottom in 318f..326f && found.areas[0].left == 40f && found.areas[0].right == 380f) { "${found.areas[0]}" }
        check(found.areas[1].top > 370f && found.areas[1].bottom > 1000f)
        // A figure is a gap in the text but not in the ink.
        check(page(true).analyze(lines).areas.size == 1)
        // No text layer (a scan): nothing is guessed.
        check(page(false).analyze().areas.isEmpty())
    }
    scenario("Without marks or a question marker only a wide gap counts, and never the rest of the page") {
        fun areas(secondTop: Float): List<AnswerArea> {
            val lines = listOf(100f, 118f, 136f, 154f, secondTop, secondTop + 18f).map { textLine(it, "Some printed sentence of text here.") }
            val page = PrintedPage()
            lines.forEach { page.text(40, it.top.toInt()) }
            return page.analyze(lines).areas
        }
        check(areas(250f).size == 1)        // 86 units clear: about five lines of room
        check(areas(235f).isEmpty())        // 71 units: a paragraph break
    }
    scenario("Printed response lines win: no inferred area overlaps them") {
        val lines = listOf(textLine(100f, "Question 1 (3 marks)"), textLine(118f, "Find the derivative of f(x)."),
            textLine(136f, "Hence solve the equation."), textLine(330f, "Question 2 (2 marks)"),
            textLine(348f, "State the range."), textLine(366f, "Explain your reasoning."))
        val page = PrintedPage()
        lines.forEach { page.text(40, it.top.toInt()) }
        for (y in listOf(200, 228, 256)) page.rule(40, 380, y)
        val found = page.analyze(lines)
        check(found.guides.size == 3 && found.areas.size == 2) { "$found" }
        check(found.guides.all { it.block == 0 } && found.areas[0].bottom == 256f)
        check(found.areas.count { it.top < 320f } == 1)
    }
    scenario("Each column of a two-column page has its own answer spaces") {
        val lines = (listOf(40f to 380f, 440f to 780f)).flatMap { (l, r) ->
            listOf(textLine(100f, "Question 1 (3 marks)", l, r), textLine(118f, "Find the derivative.", l, r), textLine(136f, "Hence solve it.", l, r),
                textLine(330f, "Question 2 (2 marks)", l, r), textLine(348f, "State the range.", l, r), textLine(366f, "Explain your reasoning.", l, r))
        }
        val page = PrintedPage()
        lines.forEach { page.text(it.left.toInt(), it.top.toInt()) }
        val found = page.analyze(lines).areas
        check(found.size == 4 && found.count { it.right <= 380f } == 2 && found.count { it.left >= 440f } == 2) { "$found" }
    }
    scenario("A text box is mapped from the crop box onto the page, and degenerate ones are dropped") {
        val line = PdfTextLayout.mapLine(3, 59.5f, 100f, 297.5f, 112f, 595f, 842f, 840f, 1188f, " Question 1 ")!!
        check(line.pageIndex == 3 && abs(line.left - 84f) < .2f && abs(line.right - 420f) < .2f && abs(line.top - 141f) < .2f && line.text == "Question 1")
        check(PdfTextLayout.mapLine(0, 10f, 10f, 10.2f, 20f, 595f, 842f, 840f, 1188f, "x") == null)
        check(PdfTextLayout.mapLine(0, 10f, 10f, 50f, 20f, 595f, 842f, 840f, 1188f, "  ") == null)
    }
    fun twoAnswers(): Pair<List<AnswerArea>, Trace> {
        val areas = listOf(AnswerArea(40f, 150f, 380f, 320f), AnswerArea(40f, 360f, 380f, 700f), AnswerArea(40f, 740f, 380f, 900f))
        val t = Trace(FakeHost(viewW = 840f, viewH = 1188f, scale = 1f))
        t.follow.areas = areas
        return areas to t
    }
    scenario("Next line walks down a blank answer space, then on to the next question without writing") {
        val (_, t) = twoAnswers()
        for (i in 0..3) t.write(letter(40f + i * 18f, 180f))
        var y = 0f
        repeat(5) { t.follow.nextLine(t.now); t.settle(); y = t.follow.marker(t.now)!!.y; check(y in 190f..320f) { "line $y" } }
        // The space is used up: the next request goes to the second answer area.
        t.follow.nextLine(t.now); t.settle()
        val jump = t.follow.marker(t.now) ?: error(t.host.last.message)
        check(jump.x == 40f && jump.y in 380f..400f) { "jumped to $jump" }
        // Writing there is progress, and Back returns to where the view was.
        for (i in 0..3) t.write(letter(40f + i * 18f, jump.y))
        check(t.host.last.message != "Holding for your correction")
        t.follow.back(t.now)
    }
    scenario("The jump skips questions that already have writing and stops when none are left") {
        val (areas, t) = twoAnswers()
        for (i in 0..3) t.host.ink += InkMark.of(letter(40f + i * 18f, 400f))!!
        for (i in 0..3) t.write(letter(40f + i * 18f, 180f))
        repeat(6) { t.follow.nextLine(t.now); t.settle() }
        val marker = t.follow.marker(t.now) ?: error(t.host.last.message)
        check(marker.y in 760f..780f) { "went to $marker, not the third area ${areas[2]}" }
        for (i in 0..3) t.write(letter(40f + i * 18f, marker.y))
        repeat(8) { t.follow.nextLine(t.now); t.settle() }
        check(t.host.last.message == "End of this answer area") { t.host.last.message }
    }
    scenario("A neighbouring column's question comes after the one above it, in reading order") {
        val areas = listOf(AnswerArea(40f, 150f, 380f, 320f), AnswerArea(40f, 360f, 380f, 700f),
            AnswerArea(440f, 150f, 780f, 320f), AnswerArea(440f, 360f, 780f, 700f))
        val t = Trace(FakeHost(viewW = 840f, viewH = 1188f, scale = 1f))
        t.follow.areas = areas
        for (area in areas.take(2)) for (i in 0..3) t.host.ink += InkMark.of(letter(area.left + i * 18f, area.top + 30f))!!
        for (i in 0..3) t.write(letter(40f + i * 18f, 400f))
        repeat(12) { t.follow.nextLine(t.now); t.settle(); val m = t.follow.marker(t.now); if (m != null && m.x >= 440f) return@repeat }
        val m = t.follow.marker(t.now)!!
        check(m.x == 440f && m.y in 170f..200f) { "went to $m" }
    }
    scenario("At the end of the page Next line turns the page, and the new page carries on to its first rule") {
        val guides = listOf(100f, 128f).map { WritingGuide(36f, 400f, it) }
        val learner = FollowLearner()
        val first = Trace(FakeHost(viewW = 840f, viewH = 1188f, scale = 1f).apply { pageAvailable = true }, guides = guides, learner = learner)
        for (i in 0..3) first.write(letter(40f + i * 18f, 128f))
        first.follow.nextLine(first.now)
        check(first.host.pageFlips == 1 && first.host.last.message == "Next page") { first.host.last.message }
        val second = Trace(FakeHost(viewW = 840f, viewH = 1188f, scale = 1f), guides = guides, learner = learner)
        second.now = first.now + 300
        second.follow.continueFromPreviousPage(second.now); second.settle()
        check(second.follow.marker(second.now)?.y == 100f) { "no carry: ${second.host.last.message}" }
        // The carry is spent; it never fires twice, and never long afterwards.
        val third = Trace(FakeHost(viewW = 840f, viewH = 1188f, scale = 1f), guides = guides, learner = learner)
        third.follow.continueFromPreviousPage(third.now + 10_000)
        check(third.follow.marker(third.now) == null)
        // With no next page the end is reported as before.
        val last = Trace(FakeHost(viewW = 840f, viewH = 1188f, scale = 1f), guides = guides)
        for (i in 0..3) last.write(letter(40f + i * 18f, 128f))
        last.follow.nextLine(last.now)
        check(last.host.pageFlips == 0 && last.host.last.message == "End of this answer area")
    }

    scenario("Glyph runs group into pieces: a wide gap (a column, right-aligned marks) starts a new piece") {
        fun run(x: Float, baseline: Float, text: String, width: Float = text.length * 5f) = PdfGlyph(x, baseline, width, 10f, text)
        val glyphs = listOf(run(70f, 110f, "the"), run(40f, 110f, "Find"), run(500f, 110f, "(3 marks)"),
            run(40f, 128.5f, "State"), run(330f, 128f, "range"))
        val lines = PdfTextLayout.group(2, glyphs, 595f, 842f, 595f, 842f)
        check(lines.map { it.text } == listOf("Find the", "(3 marks)", "State", "range")) { lines.map { it.text }.toString() }
        check(lines.all { it.pageIndex == 2 } && abs(lines[0].left - 40f) < .01f && abs(lines[0].top - 100f) < .01f && lines[0].bottom > 110f)
        check(PdfTextLayout.group(0, emptyList(), 595f, 842f, 595f, 842f).isEmpty())
        check(PdfTextLayout.group(0, glyphs.map { it.copy(height = 0f) }, 595f, 842f, 595f, 842f).isEmpty())
    }
    scenario("Right-aligned marks beside every question do not turn one column into two") {
        val lines = ArrayList<PdfTextLine>()
        for ((i, top) in listOf(100f, 330f, 560f, 790f).withIndex()) {
            lines += PdfTextLine(0, 40f, top, 700f, top + 10f, "Question ${i + 1}")
            lines += PdfTextLine(0, 40f, top + 18f, 690f, top + 28f, "Find the value of x in the equation.")
            lines += PdfTextLine(0, 720f, top + 18f, 790f, top + 28f, "(${i + 2} marks)")
        }
        val page = PrintedPage()
        for (l in lines) page.text(l.left.toInt(), l.top.toInt(), (l.right - l.left).toInt())
        val found = page.analyze(lines).areas
        check(found.size >= 3 && found.all { it.left == 40f && it.right == 790f }) { "$found" }
    }
    // ---- Trace and replay ----
    scenario("A recorded session replays to the same moves, and a damaged line does not spoil the rest") {
        val record = FollowTrace()
        val guides = listOf(100f, 128f, 156f).map { WritingGuide(36f, 800f, it) }
        val host = FakeHost(viewW = 400f, viewH = 500f, scale = 2f)
        val t = Trace(host, FollowPreferences(automaticReturn = true), guides, interleaveFrames = true, record = record)
        for (i in 0..24) t.write(letter(40f + i * 18f), gap = if (i % 6 == 0) 420 else 150)
        t.follow.hover(300f, 90f, t.now); t.pause(200); t.follow.hoverEnded(t.now)
        t.follow.nextLine(t.now); t.settle()
        t.follow.back(t.now)
        check(t.moves.size > 3)
        val text = record.text()
        check(text.startsWith(FollowTrace.HEADER))
        val events = FollowTrace.parse(text + "garbage line\nU not numbers\n")
        check(events.first() is TraceEvent.Context && events.count { it is TraceEvent.Up } == 25)
        val again = Replay(events)
        check(again.moves.size == t.moves.size) { "replayed ${again.moves.size} moves, recorded ${t.moves.size}" }
        check(abs(again.host.x - t.host.x) < .5f && abs(again.host.y - t.host.y) < .5f) { "ends at ${again.host.x},${again.host.y} not ${t.host.x},${t.host.y}" }
        check(events.count { it is TraceEvent.Moved } == t.moves.size) { "recorded ${events.count { it is TraceEvent.Moved }} moves, saw ${t.moves.size}" }
    }
    scenario("A trace that dropped its start still carries the page and guides, and recording is bounded") {
        val record = FollowTrace(capacity = 40)
        val t = Trace(FakeHost(viewW = 400f, scale = 2f), guides = listOf(WritingGuide(36f, 400f, 100f)), record = record)
        for (i in 0..30) t.write(letter(30f + i * 18f))
        check(record.size <= 40)
        val events = FollowTrace.parse(record.text())
        val context = events.first() as TraceEvent.Context
        check(context.guides.size == 1 && context.page.width == 840f)
        record.clear()
        check(record.size == 0)
    }
    println("$checks writing follow traces passed")
}
