package com.folio.notes

/** Geometry checks, using the same models and helpers as the editor. */
fun main() {
    val original = NotePage(
        strokes = listOf(Stroke(tool = Tool.PEN, points = listOf(InkPoint(80f, 700f), InkPoint(120f, 720f)))),
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
    println("Marking space checks passed")
}
