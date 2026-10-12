package com.folio.notes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Frozen visible page geometry for one contact. No Android or per-sample Compose traffic. */
internal data class DocumentInkPage(val frame: DocumentPageGeometry.Frame, val layer: Int, val writable: Boolean)
internal class DocumentInkCapture(val sourceId: String, val template: Stroke, val pages: List<DocumentInkPage>) {
    private val byId = pages.associateBy { it.frame.pageId }
    private val source = byId.getValue(sourceId)
    val sourceFrame: DocumentPageGeometry.Frame get() = source.frame
    fun page(id: String): DocumentInkPage? = byId[id]
    private val ordered = pages.sortedBy { it.frame.paper.top }
    private var previous: InkPoint? = null
    private var activePage: String? = null
    private val pieces = LinkedHashMap<String, MutableList<MutableList<InkPoint>>>()
    val livePageId: String? get() = activePage
    var spanning = false; private set
    var blocked = false; private set
    val loop = ArrayList<InkPoint>()

    fun toWindow(point: InkPoint): InkPoint {
        val mapped = source.frame.toWindow(point.x, point.y)!!
        return InkPoint(mapped.x, mapped.y, point.pressure)
    }
    fun add(local: List<InkPoint>, lasso: Boolean) {
        for (point in local) {
            val window = toWindow(point)
            if (lasso) {
                loop += window
                if (!source.frame.paper.contains(window.x, window.y)) spanning = true
            } else {
                val before = previous
                if (before == null) {
                    pieces.getOrPut(sourceId) { mutableListOf() } += mutableListOf(point)
                    activePage = sourceId
                } else {
                    val minY = min(before.y, window.y); val maxY = max(before.y, window.y)
                    var low = 0; var high = ordered.size
                    while (low < high) { val middle = (low + high) / 2
                        if (ordered[middle].frame.paper.bottom < minY) low = middle + 1 else high = middle }
                    val hits = ArrayList<Triple<DocumentInkPage, InkPoint, InkPoint>>()
                    var i = low
                    while (i < ordered.size && ordered[i].frame.paper.top <= maxY) {
                        val page = ordered[i++]
                        clip(before, window, page.frame.paper)?.let { (a, b) -> hits += Triple(page, a, b) }
                    }
                    if (window.y < before.y) hits.reverse()
                    for ((page, a, b) in hits) {
                        if (!page.writable) { blocked = true; spanning = true; continue }
                        val frame = page.frame
                        if (frame.pageId != sourceId) spanning = true
                        fun local(p: InkPoint): InkPoint { val q = frame.toPage(p.x, p.y)!!; return InkPoint(q.x, q.y, p.pressure) }
                        val values = pieces.getOrPut(frame.pageId) { mutableListOf() }
                        val start = local(a); val finish = local(b)
                        val current = if (activePage == frame.pageId && values.isNotEmpty() && near(values.last().last(), start)) values.last()
                            else mutableListOf(start).also { values += it }
                        if (!near(current.last(), finish)) current += finish
                        activePage = frame.pageId
                    }
                    if (hits.isEmpty() || !hits.any { it.first.frame.paper.contains(window.x, window.y) }) activePage = null
                }
            }
            previous = window
        }
    }
    fun invalidate() { blocked = true; spanning = true }
    fun strokes(): Map<String, List<Stroke>> = pieces.mapValues { (id, values) ->
        val frame = byId.getValue(id)
        val width = template.width * source.frame.paper.width / source.frame.width * frame.frame.width / frame.frame.paper.width
        values.map { template.copy(width = width, points = it, layer = frame.layer) }
    }
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
