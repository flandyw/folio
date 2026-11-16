package com.folio.notes

import com.folio.notes.document.DocumentToolEdits
import com.folio.notes.ink.recognition.Scribble
import com.folio.notes.model.NotePage
import com.folio.notes.model.InkPoint
import com.folio.notes.model.PageLayer
import com.folio.notes.model.PageLayers
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
import kotlin.math.*

/** Gesture and destructive-target regressions using the editor's real geometry. */
fun main() {
    fun stroke(points: List<InkPoint>, layer: Int = 0) = Stroke(Tool.PEN, 0, 1f, points, layer = layer)
    fun p(x: Float, y: Float) = InkPoint(x, y)
    val sharp = (0..5).map { p(if (it % 2 == 0) 0f else 100f, it * 5f) }
    // Smooth turns contain several small segments, rather than one sharp corner.
    val rounded = (0..200).map { i ->
        val t = i * 5 * PI / 200
        p((50 - 50 * cos(t)).toFloat(), i * .125f)
    }
    for ((name, gesture) in listOf("sharp" to sharp, "rounded" to rounded)) {
        for (sensitivity in listOf(0f, .5f, 1f)) {
            check(Scribble.isScribble(gesture, sensitivity)) { "$name at $sensitivity" }
            check(Scribble.isScribble(gesture.map { p(it.y, it.x) }, sensitivity)) { "vertical $name" }
            check(Scribble.isScribble(gesture.reversed(), sensitivity)) { "reverse $name" }
        }
    }
    fun dense(corners: List<InkPoint>) = corners.zipWithNext().flatMap { (a, b) ->
        (0 until 50).map { n -> p(a.x + (b.x - a.x) * n / 50, a.y + (b.y - a.y) * n / 50) }
    } + corners.last()
    check(Scribble.isScribble(dense(sharp)))
    val letterW = listOf(p(0f, 0f), p(25f, 40f), p(50f, 0f), p(75f, 40f), p(100f, 0f))
    val circle = (0..100).map { p(50 * cos(it * 2 * PI / 100).toFloat(), 50 * sin(it * 2 * PI / 100).toFloat()) }
    for (ordinary in listOf(emptyList(), listOf(p(0f, 0f)), letterW, circle,
        listOf(p(0f, 0f), p(100f, 0f)), sharp.map { p(it.x / 20, it.y / 20) })) {
        check(!Scribble.isScribble(ordinary)) { "Ordinary writing accepted: $ordinary" }
    }
    // A slow, careful scrub steps far between passes (taller than wide) or runs diagonally; the
    // axis is its own back-and-forth, not the box. Forward-running zigzag writing still is not a scrub.
    val stepped = (0..8).map { p(if (it % 2 == 0) 0f else 30f, it * 4f) }
    val diagonal = sharp.map { p(it.x * .7f - it.y * .7f, it.x * .7f + it.y * .7f) }
    for (scrub in listOf(stepped, dense(stepped), diagonal)) check(Scribble.isScribble(scrub)) { "scrub $scrub" }
    val www = (0..12).map { p(it * 10f, if (it % 2 == 0) 0f else 30f) }
    val mm = (0..300).map { i -> val t = i / 300.0 * 6 * PI; p((t * 8).toFloat(), (-abs(sin(t / 2)) * 30).toFloat()) }
    for (writing in listOf(www, mm)) check(!Scribble.isScribble(writing, 1f)) { "writing $writing" }
    // Scratching out a word: tall legs that barely advance along it, unlike `www` (a third of a leg per leg).
    val scratch = (0..40).map { p(it * 5f + if (it % 2 == 0) 0f else 1f, if (it % 2 == 0) 0f else 40f) }
    for (s in listOf(0f, .5f, 1f)) for (gesture in listOf(scratch, dense(scratch)))
        check(Scribble.isScribble(gesture, s)) { "scratch-out at $s" }
    // Every letter goes, stems parallel to the legs included, and the next word stays.
    val word = listOf(stroke(listOf(p(52f, 5f), p(52f, 35f))), stroke(listOf(p(100f, 20f), p(140f, 20f))),
        stroke((0..24).map { p(170f + 12 * cos(it * PI / 12).toFloat(), 20f + 12 * sin(it * PI / 12).toFloat()) }))
    val nextWord = stroke(listOf(p(240f, 5f), p(240f, 35f)))
    check(Scribble.erase(word + nextWord, stroke(scratch)) == listOf(nextWord)) { "scratched-out word" }
    check(!Scribble.isScribble(sharp + p(Float.NaN, 0f)))
    check(Scribble.isScribble(sharp, Float.NaN))
    val target = stroke(listOf(p(50f, -10f), p(50f, 40f)))
    val distant = stroke(listOf(p(150f, 0f), p(150f, 40f)))
    // Grazed by the first leg only; ink between two legs counts as touched by both.
    val once = stroke(listOf(p(50f, -2f), p(50f, -1f)))
    val originals = listOf(distant, target, once)
    check(Scribble.erase(originals, stroke(sharp)) == listOf(distant, once))
    val untouched = listOf(distant, once)
    check(Scribble.erase(untouched, stroke(sharp)) === untouched)
    check(Scribble.erase(listOf(target), stroke(rounded)).isEmpty())

    // One leg bending round a mark, the rest far below: crossing it twice is still one graze.
    val bent = listOf(p(0f, 0f), p(35f, 0f), p(50f, 10f), p(65f, 0f), p(100f, 0f),
        p(0f, 60f), p(100f, 70f), p(0f, 80f), p(100f, 90f))
    val bendTarget = stroke(listOf(p(40f, 5f), p(60f, 5f)))
    val bendInk = listOf(bendTarget)
    check(Scribble.isScribble(bent))
    check(Scribble.erase(bendInk, stroke(bent)) === bendInk)

    // A long line only crossed by the scrub survives; one the scrub wipes along its length goes.
    val longLine = stroke(listOf(p(50f, -400f), p(50f, 500f)))
    val crossed = listOf(longLine)
    check(Scribble.erase(crossed, stroke(sharp)) === crossed) { "long line crossed by hatching" }
    val wiped = stroke(listOf(p(50f, -3f), p(50f, 28f)))
    check(Scribble.erase(listOf(wiped), stroke(sharp)).isEmpty()) { "line under the scrub" }

    val layers = listOf(PageLayer.BASE, PageLayer(1, "Locked", locked = true),
        PageLayer(2, "Hidden", visible = false))
    val locked = target.copy(layer = 1)
    val hidden = target.copy(layer = 2)
    val layered = listOf(locked, target, hidden, distant)
    val erased = Scribble.erase(layered, stroke(sharp),
        canErase = { PageLayers.editable(layers, it.layer) })
    check(erased == listOf(locked, hidden, distant))
    val protected = listOf(locked, hidden)
    check(Scribble.erase(protected, stroke(sharp),
        canErase = { PageLayers.editable(layers, it.layer) }) === protected)
    // Hold-to-erase: a held scrub that wipes nothing leaves no ink; an ordinary one stays as ink.
    val blank = NotePage(width = 400f, height = 200f, strokes = listOf(distant))
    val scrub = stroke(sharp)
    fun scrubbed(ink: Boolean) = DocumentToolEdits.content(blank, listOf(scrub), emptyMap(),
        DocumentToolEdits.Options(scribble = true, scribbleInk = ink)).strokes
    check(scrubbed(true).size == 2 && scrubbed(false) == listOf(distant)) { "held scrub left ink" }
    val wipedPage = blank.copy(strokes = listOf(distant, target))
    check(DocumentToolEdits.content(wipedPage, listOf(scrub), emptyMap(),
        DocumentToolEdits.Options(scribble = true, scribbleInk = false)).strokes == listOf(distant)) { "held scrub erase" }
    // Certain scrubs erase without the model; anything letter-like, hollow or sparse still needs it.
    for (clear in listOf(sharp, rounded, dense(sharp), diagonal, stepped, scratch)) check(Scribble.isCertain(clear)) { "certain $clear" }
    val bigW = letterW.map { p(it.x * .3f, it.y) }
    val circles = (0..250).map { p(30 * cos(it * 2 * PI / 100).toFloat(), 30 * sin(it * 2 * PI / 100).toFloat()) }
    val fourLegs = sharp.take(5)
    for (unsure in listOf(bigW, circles, fourLegs, www, mm, sharp.map { p(it.x / 8, it.y / 8) }))
        check(!Scribble.isCertain(unsure)) { "not certain $unsure" }
    // Circling a word again and again never wipes what is inside the loop.
    val inside = listOf(stroke(listOf(p(-12f, 0f), p(12f, 0f))))
    check(Scribble.erase(inside, stroke(circles), 1f) === inside) { "circled word" }
    // Short strokes scrubbed along a long word: each leg is a fraction of the whole span.
    val along = (0..20).map { p(it / 2 * 10f + if (it % 2 == 0) 0f else 30f, it * .5f) }
    check(Scribble.isScribble(along) && Scribble.isScribble(dense(along))) { "scrub drifting along a word" }
    // Hand-drawn variety: any angle, size, leg count, wobble and sampling still reads as a certain
    // scrub and takes the ink under it; the same wobble on forward-running writing never does.
    val random = java.util.Random(7)
    fun wobble(points: List<InkPoint>, by: Float) = points.map { p(it.x + random.nextGaussian().toFloat() * by, it.y + random.nextGaussian().toFloat() * by) }
    fun turned(points: List<InkPoint>, angle: Double) = points.map {
        p((it.x * cos(angle) - it.y * sin(angle)).toFloat(), (it.x * sin(angle) + it.y * cos(angle)).toFloat()) }
    var missed = 0; var kept = 0
    repeat(400) {
        val legs = 5 + random.nextInt(8); val width = 40f + random.nextFloat() * 120f
        val depth = width * (.08f + random.nextFloat() * .5f); val angle = random.nextDouble() * 2 * PI
        val corners = (0..legs).map { i ->
            p((if (i % 2 == 0) 0f else width) + (random.nextFloat() - .5f) * width * .3f, depth * i / legs) }
        val scrubbed = stroke(turned(wobble(dense(corners), .6f), angle))
        if (!Scribble.isCertain(scrubbed.points)) missed++
        val under = stroke(turned(listOf(p(width * .35f, depth * .3f), p(width * .65f, depth * .7f)), angle))
        if (Scribble.erase(listOf(under), scrubbed).isNotEmpty()) kept++
    }
    check(missed <= 4 && kept <= 4) { "noisy scrubs: $missed of 400 not certain, $kept left ink behind" }
    var mistaken = 0
    repeat(400) {
        val humps = 3 + random.nextInt(8); val tall = 12f + random.nextFloat() * 40f
        val advance = tall * (.3f + random.nextFloat() * .5f); val angle = random.nextDouble() * 2 * PI
        // Zigzags and loops running along the line: `www`, `mmm`, `lll`, springs.
        val loops = random.nextBoolean()
        val writing = if (loops) (0..humps * 40).map { i -> val t = i / 40.0 * 2 * PI
            p((advance * t / PI + tall * .2 * sin(t)).toFloat(), (tall / 2 * cos(t)).toFloat()) }
        else dense((0..humps * 2).map { i -> p(i * advance, if (i % 2 == 0) 0f else tall) })
        if (Scribble.isCertain(turned(wobble(writing, .6f), angle), 1f)) mistaken++
    }
    check(mistaken == 0) { "$mistaken of 400 lines of writing read as certain scrubs" }
    println("Scribble erase checks passed: hold to erase, rounded turns, sampling, slow, drifting and diagonal scrubs, certainty, circling, noisy samples, handwriting, contact and layers")
}
