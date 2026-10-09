package com.folio.notes

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
            check(InkGeometry.isScribble(gesture, sensitivity)) { "$name at $sensitivity" }
            check(InkGeometry.isScribble(gesture.map { p(it.y, it.x) }, sensitivity)) { "vertical $name" }
            check(InkGeometry.isScribble(gesture.reversed(), sensitivity)) { "reverse $name" }
        }
    }
    val dense = sharp.zipWithNext().flatMap { (a, b) ->
        (0 until 50).map { n -> p(a.x + (b.x - a.x) * n / 50, a.y + (b.y - a.y) * n / 50) }
    } + sharp.last()
    check(InkGeometry.isScribble(dense))
    val letterW = listOf(p(0f, 0f), p(25f, 40f), p(50f, 0f), p(75f, 40f), p(100f, 0f))
    val circle = (0..100).map { p(50 * cos(it * 2 * PI / 100).toFloat(), 50 * sin(it * 2 * PI / 100).toFloat()) }
    for (ordinary in listOf(emptyList(), listOf(p(0f, 0f)), letterW, circle,
        listOf(p(0f, 0f), p(100f, 0f)), sharp.map { p(it.x / 20, it.y / 20) })) {
        check(!InkGeometry.isScribble(ordinary)) { "Ordinary writing accepted: $ordinary" }
    }
    check(!InkGeometry.isScribble(sharp + p(Float.NaN, 0f)))
    check(InkGeometry.isScribble(sharp, Float.NaN))
    val target = stroke(listOf(p(50f, -10f), p(50f, 40f)))
    val distant = stroke(listOf(p(150f, 0f), p(150f, 40f)))
    val once = stroke(listOf(p(50f, 1f), p(50f, 3f)))
    val originals = listOf(distant, target, once)
    check(InkGeometry.scribbleErase(originals, stroke(sharp), 0f) == listOf(distant, once))
    val untouched = listOf(distant, once)
    check(InkGeometry.scribbleErase(untouched, stroke(sharp), 0f) === untouched)
    check(InkGeometry.scribbleErase(listOf(target), stroke(rounded), 0f).isEmpty())

    // Multiple bends in one sweep must not masquerade as repeated contact.
    val bent = listOf(p(0f, 0f), p(35f, 0f), p(50f, 10f), p(65f, 0f), p(100f, 0f),
        p(0f, 40f), p(100f, 50f), p(0f, 60f), p(100f, 70f))
    val bendTarget = stroke(listOf(p(40f, 5f), p(60f, 5f)))
    val bendInk = listOf(bendTarget)
    check(InkGeometry.isScribble(bent))
    check(InkGeometry.scribbleErase(bendInk, stroke(bent), 0f) === bendInk)

    // A long line only crossed by the scrub survives; one the scrub wipes along its length goes.
    val longLine = stroke(listOf(p(50f, -400f), p(50f, 500f)))
    val crossed = listOf(longLine)
    check(InkGeometry.scribbleErase(crossed, stroke(sharp), 0f) === crossed) { "long line crossed by hatching" }
    val wiped = stroke(listOf(p(50f, -3f), p(50f, 28f)))
    check(InkGeometry.scribbleErase(listOf(wiped), stroke(sharp), 0f).isEmpty()) { "line under the scrub" }

    val layers = listOf(PageLayer.BASE, PageLayer(1, "Locked", locked = true),
        PageLayer(2, "Hidden", visible = false))
    val locked = target.copy(layer = 1)
    val hidden = target.copy(layer = 2)
    val layered = listOf(locked, target, hidden, distant)
    val erased = InkGeometry.scribbleErase(layered, stroke(sharp), 0f,
        canErase = { PageLayers.editable(layers, it.layer) })
    check(erased == listOf(locked, hidden, distant))
    val protected = listOf(locked, hidden)
    check(InkGeometry.scribbleErase(protected, stroke(sharp), 0f,
        canErase = { PageLayers.editable(layers, it.layer) }) === protected)
    println("Scribble erase checks passed: rounded turns, sampling, handwriting, contact and layers")
}
