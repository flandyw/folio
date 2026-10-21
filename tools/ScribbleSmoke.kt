package com.folio.notes

import com.folio.notes.document.DocumentToolEdits
import com.folio.notes.ink.InkGeometry
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
            check(InkGeometry.isScribble(gesture, sensitivity)) { "$name at $sensitivity" }
            check(InkGeometry.isScribble(gesture.map { p(it.y, it.x) }, sensitivity)) { "vertical $name" }
            check(InkGeometry.isScribble(gesture.reversed(), sensitivity)) { "reverse $name" }
        }
    }
    fun dense(corners: List<InkPoint>) = corners.zipWithNext().flatMap { (a, b) ->
        (0 until 50).map { n -> p(a.x + (b.x - a.x) * n / 50, a.y + (b.y - a.y) * n / 50) }
    } + corners.last()
    check(InkGeometry.isScribble(dense(sharp)))
    val letterW = listOf(p(0f, 0f), p(25f, 40f), p(50f, 0f), p(75f, 40f), p(100f, 0f))
    val circle = (0..100).map { p(50 * cos(it * 2 * PI / 100).toFloat(), 50 * sin(it * 2 * PI / 100).toFloat()) }
    for (ordinary in listOf(emptyList(), listOf(p(0f, 0f)), letterW, circle,
        listOf(p(0f, 0f), p(100f, 0f)), sharp.map { p(it.x / 20, it.y / 20) })) {
        check(!InkGeometry.isScribble(ordinary)) { "Ordinary writing accepted: $ordinary" }
    }
    // A slow, careful scrub steps far between passes (taller than wide) or runs diagonally; the
    // axis is its own back-and-forth, not the box. Forward-running zigzag writing still is not a scrub.
    val stepped = (0..8).map { p(if (it % 2 == 0) 0f else 30f, it * 4f) }
    val diagonal = sharp.map { p(it.x * .7f - it.y * .7f, it.x * .7f + it.y * .7f) }
    for (scrub in listOf(stepped, dense(stepped), diagonal)) check(InkGeometry.isScribble(scrub)) { "scrub $scrub" }
    val www = (0..12).map { p(it * 10f, if (it % 2 == 0) 0f else 30f) }
    val mm = (0..300).map { i -> val t = i / 300.0 * 6 * PI; p((t * 8).toFloat(), (-abs(sin(t / 2)) * 30).toFloat()) }
    for (writing in listOf(www, mm)) check(!InkGeometry.isScribble(writing, 1f)) { "writing $writing" }
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
    // Hold-to-erase: a held scrub that wipes nothing leaves no ink; an ordinary one stays as ink.
    val blank = NotePage(width = 400f, height = 200f, strokes = listOf(distant))
    val scrub = stroke(sharp)
    fun scrubbed(ink: Boolean) = DocumentToolEdits.content(blank, listOf(scrub), emptyMap(),
        DocumentToolEdits.Options(scribble = true, scribbleInk = ink)).strokes
    check(scrubbed(true).size == 2 && scrubbed(false) == listOf(distant)) { "held scrub left ink" }
    val wipedPage = blank.copy(strokes = listOf(distant, target))
    check(DocumentToolEdits.content(wipedPage, listOf(scrub), emptyMap(),
        DocumentToolEdits.Options(scribble = true, scribbleInk = false)).strokes == listOf(distant)) { "held scrub erase" }
    println("Scribble erase checks passed: hold to erase, rounded turns, sampling, slow and diagonal scrubs, handwriting, contact and layers")
}
