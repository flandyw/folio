package com.folio.notes

import com.folio.notes.editor.layout.DocumentInkCapture
import com.folio.notes.editor.layout.DocumentInkPage
import com.folio.notes.editor.layout.DocumentMarking
import com.folio.notes.editor.layout.DocumentPageGeometry
import com.folio.notes.marking.MarkZones
import com.folio.notes.marking.Marking
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageImage
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.content

/** Geometry checks, using the same models and helpers as the editor. */
fun main() {
    val original = NotePage(
        strokes = listOf(Stroke(tool = Tool.PEN, color = 0, width = 2f, points = listOf(InkPoint(80f, 700f), InkPoint(120f, 720f)))),
        texts = listOf(TextBox(text = "Feedback", x = 40f, y = 400f)),
        images = listOf(PageImage(id = "picture", x = 40f, y = 900f, width = 100f, height = 80f)))
    val height: (TextBox) -> Float = { 40f }
    val opened = checkNotNull(Marking.makeRoom(original, 200f, 160f, height))
    val grown = original.copy(height = opened.height, strokes = opened.strokes, texts = opened.texts, images = opened.images)
    val closed = checkNotNull(Marking.removeRoom(grown, 200f, 160f, height))
    check(closed.height == original.height)
    check(closed.strokes == original.strokes && closed.texts == original.texts && closed.images == original.images)
    check(closed.moved == 3)
    check(Marking.removeRoom(grown, 550f, 160f, height) == null) // Text occupies the gap.
    check(Marking.removeRoom(grown, 850f, 160f, height) == null) // Ink occupies the gap.
    check(Marking.removeRoom(grown, 1040f, 160f, height) == null) // Picture occupies the gap.
    check(Marking.removeRoom(grown.copy(pdfIndex = 0), 200f, 160f, height) == null)
    check(Marking.removeRoom(grown, Float.NaN, 160f, height) == null)
    check(Marking.removeRoom(grown, 200f, -160f, height) == null)
    check(Marking.removeRoom(grown, 1300f, 160f, height) == null)
    val blank = checkNotNull(Marking.removeRoom(NotePage(height = 1348f), 1188f, 160f, height))
    check(blank.height == 1188f && blank.moved == 0)
    val canvas = checkNotNull(Marking.removeRoom(grown.copy(infinite = true), 200f, 160f, height))
    check(canvas.height == grown.height && canvas.texts == original.texts)
    // One rectangle crosses two differently scaled PDF pages, including an upward drag.
    val first = NotePage(id = "first", width = 100f, height = 100f, pdfIndex = 0)
    val second = NotePage(id = "second", width = 200f, height = 200f, pdfIndex = 1)
    fun hosted(page: NotePage, top: Float) = DocumentInkPage(DocumentPageGeometry.Frame(page.id, page.width, page.height,
        DocumentPageGeometry.Bounds(0f, top, 100f, top + 100f), DocumentPageGeometry.Bounds(0f, top, 100f, top + 100f)), 0, true)
    val frames = listOf(hosted(first, 0f), hosted(second, 106f))
    val capture = DocumentInkCapture("second", Stroke(Tool.MARK_AREA, color = 0, width = 2f, points = listOf(InkPoint(20f, 100f))), frames)
    capture.add(listOf(InkPoint(20f, 100f), InkPoint(160f, -120f)), false)
    val parts = checkNotNull(DocumentMarking.parts(capture, mapOf(first.id to first, second.id to second)))
    check(parts.size == 2 && parts.first().pageId == second.id)
    fun close(a: Float, b: Float) = check(kotlin.math.abs(a - b) < .001f) { "$a != $b" }
    close(parts[0].y, 0f); close(parts[0].height, 100f); close(parts[0].width, 140f)
    close(parts[1].y, 46f); close(parts[1].height, 54f); close(parts[1].width, 70f)
    check(DocumentMarking.parts(capture, mapOf(first.id to first.copy(loaded = false), second.id to second)) == null)
    check(DocumentMarking.parts(capture, mapOf(first.id to first.copy(pdfIndex = null), second.id to second)) == null)
    val root = parts.first().zone(4).copy(parts = parts)
    val continuation = checkNotNull(root.onPage(first))
    check(root.anchorPageId == second.id && continuation.canonical() == root)
    check(root.onPage(first.copy(id = "duplicate")) == null) // Notebook page IDs, not shared PDF indices.
    check(MarkZones.decode(MarkZones.encode(listOf(continuation))) == listOf(root))
    val legacy = "0,10.0,20.0,30.0,40.0,4"
    check(MarkZones.encode(MarkZones.decode(legacy)) == legacy)
    check(MarkZones.decode(MarkZones.encode(listOf(root.copy(parts = listOf(parts[0].copy(width = Float.NaN), parts[1]))))).isEmpty())
    check(MarkZones.total(listOf(root)) == 4)
    // An unrelated award on a continuation page is never touched by the linked award or clear.
    val unrelated = Marking.markBox("+2", 0f, 0f, Marking.DEFAULT_COLOR)
    val pages = mapOf(first.id to first.copy(texts = listOf(unrelated)), second.id to second)
    val awarded = checkNotNull(MarkZones.regionContent(continuation, pages, 3, Marking.DEFAULT_COLOR))
    check(awarded.getValue(first.id).texts == listOf(unrelated))
    check(awarded.getValue(second.id).texts.single().text == "+3")
    check(awarded.values.sumOf { it.strokes.size } == 2)
    check(MarkZones.awarded(root, second.copy(texts = awarded.getValue(second.id).texts)) == 3f)
    val forward = awarded.mapValues { (id, after) -> PageJournal.diff(pages.getValue(id).content(), after)!! }
    val withAward = pages.mapValues { (id, page) -> awarded.getValue(id).let { page.copy(strokes = it.strokes, texts = it.texts) } }
    val full = checkNotNull(MarkZones.regionContent(root, withAward, 4, Marking.DEFAULT_COLOR))
    check(full.getValue(second.id).texts.size == 1 && full.getValue(second.id).texts.single().text == "+4")
    check(full.values.flatMap { it.strokes }.all { it.tool == Tool.RECTANGLE })
    val cleared = checkNotNull(MarkZones.regionContent(root, withAward, null, 0))
    check(cleared == pages.mapValues { it.value.content() })
    forward.forEach { (id, edit) ->
        check(PageJournal.apply(awarded.getValue(id), PageJournal.invert(edit, pages.getValue(id).content())) == pages.getValue(id).content())
    }
    println("Linked PDF marking checks passed: upward mixed-scale capture, seam clipping, stable page identity, legacy/extended preferences, one award, re-award, clear and undo.")
    println("Marking space checks passed")
}
