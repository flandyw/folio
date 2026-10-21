package com.folio.notes.document

import com.folio.notes.ink.InkGeometry
import com.folio.notes.ink.recognition.ScribbleSensitivity
import com.folio.notes.model.CanvasSelection
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageLayers
import com.folio.notes.model.Stroke
import com.folio.notes.model.StrokeStyle
import com.folio.notes.storage.PageContent
import com.folio.notes.storage.content

/** Shared surface edits for continuous pages, single sheets and canvases. No Android state. */
internal object DocumentToolEdits {
    data class Options(val erasing: Boolean = false, val wholeStroke: Boolean = false, val pressure: Boolean = false,
        val scribble: Boolean = false, val scribbleSensitivity: Float = ScribbleSensitivity.DEFAULT,
        /** False for a held scrub, which is erasure only: where it wipes nothing it leaves no ink. */
        val scribbleInk: Boolean = true)
    fun content(page: NotePage, strokes: List<Stroke>, notes: Map<String, List<Stroke>>, options: Options): PageContent =
        page.content().copy(strokes = apply(page.strokes, strokes, options) { PageLayers.editable(page.layers, it.layer) },
            texts = page.texts.map { box -> notes[box.id]?.let { paths -> box.copy(stickyInk = apply(box.stickyInk, paths, options) { true }) } ?: box })
    /** Record the drawing before interpreting it as erasure. Undo can then keep intentional ink. */
    fun contentSteps(page: NotePage, strokes: List<Stroke>, notes: Map<String, List<Stroke>>, options: Options): List<PageContent> {
        val after = content(page, strokes, notes, options)
        if (options.scribble && !options.erasing) {
            val drawn = content(page, strokes, notes, options.copy(scribble = false))
            if (drawn != after) return listOf(drawn, after)
        }
        return listOf(after)
    }
    fun restyle(page: NotePage, selection: CanvasSelection, color: Int?, width: Float?, opacity: Float?, style: StrokeStyle?): PageContent {
        val replacements = java.util.IdentityHashMap<Stroke, Stroke>()
        selection.strokes.zip(InkGeometry.restyle(selection.strokes, color, width, opacity, style)).forEach { (before, after) -> replacements[before] = after }
        val notes = selection.texts.filter { it.isSticky }.map { it.id }.toSet()
        return page.content().copy(strokes = page.strokes.map { replacements[it] ?: it },
            texts = page.texts.map { box -> if (box.id in notes) box.copy(stickyInk = InkGeometry.restyle(box.stickyInk, color, width, opacity, style)) else box })
    }
    /** Consume each live path's new samples once; long eraser gestures stay linear in input. */
    class EraserSession {
        private val consumed = java.util.IdentityHashMap<List<InkPoint>, Int>()
        private val contents = HashMap<String, PageContent>()
        private fun fresh(paths: List<Stroke>): List<Stroke> = paths.mapNotNull { path ->
            val count = consumed[path.points] ?: 0
            if (count == path.points.size) return@mapNotNull null
            consumed[path.points] = path.points.size
            path.copy(points = path.points.subList((count - 1).coerceAtLeast(0), path.points.size))
        }
        fun content(page: NotePage, strokes: List<Stroke>, notes: Map<String, List<Stroke>>, options: Options): PageContent {
            val before = contents[page.id]?.let { page.copy(strokes = it.strokes, texts = it.texts) } ?: page
            return DocumentToolEdits.content(before, fresh(strokes), notes.mapValues { fresh(it.value) }, options).also { contents[page.id] = it }
        }
    }
    private fun apply(ink: List<Stroke>, paths: List<Stroke>, options: Options, editable: (Stroke) -> Boolean): List<Stroke> {
        if (!options.erasing) {
            if (options.scribble) {
                var scrubbed = ink
                paths.forEach { scrubbed = InkGeometry.scribbleErase(scrubbed, it, 14f, options.scribbleSensitivity, canErase = editable) }
                if (scrubbed !== ink || !options.scribbleInk) return scrubbed
            }
            return ink + paths.map { it.copy(points = it.points.toList()) }
        }
        var result = ink
        for (path in paths) {
            val centers = ArrayList<InkPoint>()
            // Fill coalesced gaps so erasing is a swept contact rather than isolated circles.
            path.points.forEachIndexed { i, p ->
                if (i > 0) {
                    val a = path.points[i - 1]
                    val steps = kotlin.math.ceil(kotlin.math.hypot(p.x - a.x, p.y - a.y) / (path.width / 4f).coerceAtLeast(.5f)).toInt().coerceAtLeast(1)
                    for (j in 1 until steps) { val t = j.toFloat() / steps; centers += InkPoint(a.x + (p.x - a.x) * t, a.y + (p.y - a.y) * t, a.pressure + (p.pressure - a.pressure) * t) }
                }
                centers += p
            }
            val radii = centers.map { path.width / 2f * if (options.pressure) InkGeometry.eraserScale(it.pressure) else 1f }
            result = result.flatMap { stroke ->
                if (!editable(stroke)) listOf(stroke)
                else if (options.wholeStroke) {
                    if (centers.indices.any { InkGeometry.hits(stroke, centers[it], radii[it]) }) emptyList() else listOf(stroke)
                } else InkGeometry.erase(stroke, centers, radii)
            }
        }
        return result
    }
}
