package com.folio.notes

import com.folio.notes.editor.layout.DocumentPageGeometry
import com.folio.notes.editor.layout.DocumentViewport
import kotlin.math.abs

fun documentGeometrySmoke() {
    fun close(actual: Float, expected: Float) { check(abs(actual - expected) < .05f) { "$actual != $expected" } }
    val sheets = listOf(
        DocumentPageGeometry.Sheet("portrait", 600f, 900f),
        DocumentPageGeometry.Sheet("landscape", 1200f, 600f, -80f, 720f),
        DocumentPageGeometry.Sheet("short", 400f, 200f),
    )
    val layout = DocumentPageGeometry.layout(sheets, 600f, 6f)
    close(layout[1].top, 906f)
    close(layout[1].paperTop, 946f)
    close(layout[2].top, 1312f)
    val anchor = DocumentPageGeometry.anchor(layout[1], layout[1].paperTop + 125f)
    close(anchor.localY, 250f)
    val resized = DocumentPageGeometry.layout(listOf(sheets[2], sheets[0], sheets[1]), 300f, 0f)
    close(DocumentPageGeometry.restore(resized, anchor)!!, resized[2].paperTop + 62.5f)
    check(DocumentPageGeometry.restore(resized.filterNot { it.sheet.pageId == anchor.pageId }, anchor) == null)
    for (scale in listOf(.5f, 1f, 2f, 4f)) {
        val frame = DocumentPageGeometry.Frame("a", 600f, 900f,
            DocumentPageGeometry.Bounds(-200f, 30f, -200f + 600f * scale, 30f + 900f * scale),
            DocumentPageGeometry.Bounds(-300f, 30f - 100f * scale, 600f * scale, 30f + 1100f * scale))
        for ((x, y) in listOf(-40f to -80f, 0f to 0f, 300f to 450f, 650f to 1000f)) {
            val screen = frame.toWindow(x, y)!!
            val page = frame.toPage(screen.x, screen.y)!!
            close(page.x, x); close(page.y, y)
        }
    }
    val frames = layout.map { p -> DocumentPageGeometry.Frame(p.sheet.pageId, p.sheet.width, p.sheet.height,
        DocumentPageGeometry.Bounds(100f, p.paperTop, 700f, p.paperTop + p.sheet.height * p.scale),
        DocumentPageGeometry.Bounds(0f, p.top, 800f, p.bottom)) }
    val index = DocumentPageGeometry.Index(frames.reversed())
    check(index.nearest(-100f)?.pageId == "portrait")
    check(index.nearest(903f)?.pageId == "portrait") // Seam ties favour the preceding page.
    check(index.nearest(905f)?.pageId == "landscape")
    check(index.nearest(920f)?.pageId == "landscape") // Margin note above its paper.
    check(index.hit(200f, 920f, "portrait") == null) // Workspace isn't paper.
    check(index.hit(200f, 950f, "portrait")?.pageId == "landscape")
    check(index.hit(200f, 950f, "landscape") == null)
    check(index.nearest(Float.NaN) == null)
    val invalid = frames[0].copy(paper = DocumentPageGeometry.Bounds(0f, 0f, 0f, 1f))
    check(invalid.toPage(1f, 1f) == null)
    check(DocumentPageGeometry.Index(listOf(invalid)).nearest(0f) == null)
    val long = DocumentPageGeometry.layout((0 until 10_000).map { DocumentPageGeometry.Sheet("$it", 600f, 900f) }, 600f, 6f)
    val lastAnchor = DocumentPageGeometry.Anchor("9999", 500f)
    close(DocumentPageGeometry.restore(long, lastAnchor)!!, long.last().paperTop + 500f)
    check(DocumentViewport.currentPage(listOf(DocumentViewport.VisiblePage(0, -800, 900),
        DocumentViewport.VisiblePage(1, 106, 400)), 0, 600, 2) == 1)
    check(DocumentViewport.currentPage(listOf(DocumentViewport.VisiblePage(2, 0, 40)), 0, 600, 2) == null)
    println("Document geometry smoke passed: mixed PDFs, margin notes, transforms, seam targets, stable anchors and 10,000 pages.")
}
