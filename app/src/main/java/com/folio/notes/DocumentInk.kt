package com.folio.notes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Frozen visible page geometry for one contact. No Android or per-sample Compose traffic. */
internal data class DocumentInkPage(val frame: DocumentPageGeometry.Frame, val layer: Int, val writable: Boolean, val notes: List<TextBox> = emptyList(), val layers: List<PageLayer> = emptyList(), val loaded: Boolean = true, val infinite: Boolean = false)
internal class DocumentInkCapture(val sourceId: String, val template: Stroke, val pages: List<DocumentInkPage>) {
    private val byId = pages.associateBy { it.frame.pageId }
    private val source = byId.getValue(sourceId)
    val sourceFrame: DocumentPageGeometry.Frame get() = source.frame
    fun page(id: String): DocumentInkPage? = byId[id]
    private val ordered = pages.sortedBy { it.frame.paper.top }
    private var previous: InkPoint? = null
    private data class Target(val pageId: String, val noteId: String? = null)
    private var active: Target? = null
    private val pieces = LinkedHashMap<Target, MutableList<MutableList<InkPoint>>>()
    val livePageId: String? get() = active?.pageId
    var spanning = false; private set
    var blocked = false; private set
    val loop = ArrayList<InkPoint>()
    private var generatedPages: Map<String, List<Stroke>>? = null
    private var generatedNotes: Map<String, Map<String, List<Stroke>>>? = null
    private var first: InkPoint? = null
    private var last: InkPoint? = null

    fun toWindow(point: InkPoint): InkPoint {
        val mapped = source.frame.toWindow(point.x, point.y)!!
        return InkPoint(mapped.x, mapped.y, point.pressure)
    }
    private fun noteBounds(page: DocumentInkPage, note: TextBox): DocumentPageGeometry.Bounds {
        val a = page.frame.toWindow(note.x, note.y)!!
        val b = page.frame.toWindow(note.x + note.width, note.y + note.stickyHeight)!!
        return DocumentPageGeometry.Bounds(a.x, a.y, b.x, b.y)
    }
    private fun target(point: InkPoint): Target? {
        // Notes are opaque surfaces, including their parts outside the paper.
        for (page in ordered.asReversed()) {
            val note = page.notes.lastOrNull { noteBounds(page, it).contains(point.x, point.y) }
            if (note != null) return Target(page.frame.pageId, note.id)
        }
        return ordered.firstOrNull { (it.infinite || it.frame.paper.contains(point.x, point.y)) }?.let { Target(it.frame.pageId) }
    }
    private fun append(target: Target?, a: InkPoint, b: InkPoint) {
        if (target == null) { active = null; return }
        val page = byId.getValue(target.pageId)
        if (target.pageId != sourceId || target.noteId != null) spanning = true
        val writable = target.noteId?.let { id -> page.notes.first { it.id == id }.let { PageLayers.editable(page.layers, it.layer) } } ?: page.writable
        if (!page.loaded) { blocked = true; spanning = true; active = null; return }
        if (!writable && template.tool != Tool.ERASER) { blocked = true; spanning = true; active = null; return }
        if (!writable && target.noteId != null) { active = null; return }
        fun local(p: InkPoint): InkPoint {
            val q = page.frame.toPage(p.x, p.y)!!
            val note = target.noteId?.let { id -> page.notes.first { it.id == id } }
            return InkPoint(q.x - (note?.x ?: 0f), q.y - (note?.y ?: 0f), p.pressure)
        }
        val start = local(a); val finish = local(b)
        val values = pieces.getOrPut(target) { mutableListOf() }
        val current = if (active == target && values.isNotEmpty() && near(values.last().last(), start)) values.last()
            else mutableListOf(start).also { values += it }
        if (!near(current.last(), finish)) current += finish
        active = target
    }
    fun add(local: List<InkPoint>, lasso: Boolean) {
        if (template.tool == Tool.ERASER) spanning = true
        for (point in local) {
            if (first == null) first = point
            last = point
            val window = toWindow(point)
            if (lasso) {
                loop += window
                if (!source.frame.paper.contains(window.x, window.y)) spanning = true
            } else {
                val before = previous
                if (before == null) append(target(window), window, window)
                else {
                    // Partition at every surface edge; a coalesced sample can cross several notes
                    // and pages. Midpoint ownership prevents writing beneath opaque notes.
                    val cuts = sortedSetOf(0f, 1f)
                    val dx = window.x - before.x; val dy = window.y - before.y
                    fun edges(rect: DocumentPageGeometry.Bounds) {
                        if (clip(before, window, rect) == null) return
                        if (dx != 0f) for (x in listOf(rect.left, rect.right)) ((x - before.x) / dx).takeIf { it > 0f && it < 1f }?.let(cuts::add)
                        if (dy != 0f) for (y in listOf(rect.top, rect.bottom)) ((y - before.y) / dy).takeIf { it > 0f && it < 1f }?.let(cuts::add)
                    }
                    for (page in ordered) { if (!page.infinite) edges(page.frame.paper); page.notes.forEach { edges(noteBounds(page, it)) } }
                    fun at(t: Float) = InkPoint(before.x + dx * t, before.y + dy * t, before.pressure + (window.pressure - before.pressure) * t)
                    cuts.toList().zipWithNext().forEach { (a, b) -> append(target(at((a + b) / 2f)), at(a), at(b)) }
                }
            }
            previous = window
        }
    }
    /** Shapes are generated once in source coordinates, then their outlines are split. */
    fun generated(strokes: List<Stroke>): DocumentInkCapture {
        val result = DocumentInkCapture(sourceId, template, pages)
        val paper = LinkedHashMap<String, MutableList<Stroke>>()
        val notes = LinkedHashMap<String, MutableMap<String, MutableList<Stroke>>>()
        for (stroke in strokes) {
            val outline = DocumentInkCapture(sourceId, stroke.copy(tool = Tool.LINE), pages)
            outline.add(InkGeometry.pathPoints(stroke), false)
            outline.strokes().forEach { (id, values) -> paper.getOrPut(id) { ArrayList() }.addAll(values) }
            outline.noteStrokes().forEach { (id, values) -> values.forEach { (note, ink) -> notes.getOrPut(id) { LinkedHashMap() }.getOrPut(note) { ArrayList() }.addAll(ink) } }
            result.spanning = result.spanning || outline.spanning
            result.blocked = result.blocked || outline.blocked
        }
        result.generatedPages = paper
        result.generatedNotes = notes
        if (blocked) result.invalidate()
        return result
    }
    fun dragStroke(): Stroke = template.copy(points = listOfNotNull(first, last).distinct())
    fun invalidate() { blocked = true; spanning = true }
    private fun strokeValues(target: Target, values: List<List<InkPoint>>): List<Stroke> {
        val page = byId.getValue(target.pageId)
        val width = template.width * source.frame.paper.width / source.frame.width * page.frame.width / page.frame.paper.width
        val tool = if (template.tool in ShapePickerTools) Tool.LINE else template.tool
        return values.map { template.copy(tool = tool, width = width, points = it, layer = if (target.noteId == null) page.layer else 0) }
    }
    fun strokes(): Map<String, List<Stroke>> = generatedPages ?: pieces.filterKeys { it.noteId == null }.map { (target, values) -> target.pageId to strokeValues(target, values) }.toMap()
    fun noteStrokes(): Map<String, Map<String, List<Stroke>>> = generatedNotes ?: pieces.filterKeys { it.noteId != null }.entries.groupBy { it.key.pageId }
        .mapValues { (_, entries) -> entries.associate { it.key.noteId!! to strokeValues(it.key, it.value) } }
    fun loopFor(frame: DocumentPageGeometry.Frame): List<InkPoint> = loop.map { p ->
        val q = frame.toPage(p.x, p.y)!!; InkPoint(q.x, q.y, p.pressure)
    }
    companion object {
        private fun near(a: InkPoint, b: InkPoint) = abs(a.x - b.x) < .0001f && abs(a.y - b.y) < .0001f && abs(a.pressure - b.pressure) < .0001f
        /** Liang–Barsky clipping interpolates both boundary positions and pressure. Gaps hold no ink. */
        fun clip(a: InkPoint, b: InkPoint, rect: DocumentPageGeometry.Bounds): Pair<InkPoint, InkPoint>? {
            if (!rect.valid) return null
            var enter = 0f; var leave = 1f
            val dx = b.x - a.x; val dy = b.y - a.y
            fun boundary(p: Float, q: Float): Boolean {
                if (p == 0f) return q >= 0
                val t = q / p
                if (p < 0) { if (t > leave) return false; enter = max(enter, t) }
                else { if (t < enter) return false; leave = min(leave, t) }
                return true
            }
            if (!boundary(-dx, a.x - rect.left) || !boundary(dx, rect.right - a.x) ||
                !boundary(-dy, a.y - rect.top) || !boundary(dy, rect.bottom - a.y)) return null
            if (leave - enter < .000001f && (dx != 0f || dy != 0f)) return null
            fun at(t: Float) = InkPoint(a.x + t * dx, a.y + t * dy, a.pressure + t * (b.pressure - a.pressure))
            return at(enter) to at(leave)
        }
    }
}
