package com.folio.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

private val note = TextBox(id = "note", x = 20f, y = 30f, width = 120f, stickyHeight = 90f, size = 8f,
    text = "Keep me", stickyInk = listOf(Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(10f, 12f, .5f), InkPoint(30f, 40f)))))

private class NotesFixture(texts: List<TextBox> = listOf(note), layers: List<PageLayer> = emptyList()) {
    var page = NotePage(id = "source", texts = texts, layers = layers)
    val host = View(Context())
    var commits = 0
    var shown: TextBox? = null
    var lift: StickyLift? = null
    var dx = 0f
    var dy = 0f
    var scale = 1f
    val input = StickyNoteInput(host, { page }, { page = page.copy(texts = it); commits++ },
        { RectF(dx + it.x * scale, dy + it.y * scale, dx + (it.x + it.width) * scale, dy + (it.y + it.stickyHeight) * scale) },
        { box, _ -> shown = box })
    fun touch(action: Int, x: Float, y: Float, tool: Tool = Tool.STICKY_NOTE,
              tap: Boolean = false, create: Boolean = true, layer: Int = 0,
              history: List<Pair<Float, Float>> = emptyList()): Boolean = input.touch(
        MotionEvent(action, x, y, history = history), InkPoint((x - dx) / scale, (y - dy) / scale), tool, create, tap, layer)
    fun carry() { input.onLift = { lift = it } }
    fun draw(): List<TextBox> { InkRenderer.drawn.clear(); input.draw(Canvas(), PageLayers.view(page).texts, 1f); return InkRenderer.drawn.toList() }
}

private fun near(actual: Float, expected: Float) { check(abs(actual - expected) < .001f) { "$actual != $expected" } }

private fun layersAndOcclusion() {
    // Storage order differs from visual order; the upper visible layer must win.
    val upper = note.copy(id = "upper", layer = 1)
    val lower = note.copy(id = "lower", layer = 0)
    val layers = listOf(PageLayer(0, "Bottom"), PageLayer(1, "Top"))
    val f = NotesFixture(listOf(upper, lower), layers)
    f.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    f.touch(MotionEvent.ACTION_UP, 50f, 60f)
    check(f.input.focusedId == upper.id)
    // A locked upper note shields editable paper below; the hidden layer exposes it again.
    val locked = NotesFixture(listOf(lower, upper), layers.map { if (it.id == 1) it.copy(locked = true) else it })
    locked.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    locked.touch(MotionEvent.ACTION_MOVE, 80f, 80f)
    locked.touch(MotionEvent.ACTION_UP, 80f, 80f)
    check(locked.commits == 0 && locked.input.focusedId == null)
    val hidden = NotesFixture(listOf(lower, upper), layers.map { if (it.id == 1) it.copy(visible = false) else it })
    hidden.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    hidden.touch(MotionEvent.ACTION_UP, 50f, 60f)
    check(hidden.input.focusedId == lower.id)
    val lockedTop = PageLayers.view(locked.page).texts
    check(StickyNotes.at(lockedTop, 50f, 60f) { PageLayers.editable(locked.page.layers, it) } == null)
    // Focus controls under an overlapping note must not punch through its paper.
    val cover = note.copy(id = "cover", x = 130f, y = 110f, width = 60f, stickyHeight = 60f)
    val grip = NotesFixture(listOf(note, cover))
    grip.input.focus(note)
    grip.touch(MotionEvent.ACTION_DOWN, 140f, 120f)
    grip.touch(MotionEvent.ACTION_MOVE, 160f, 140f)
    grip.touch(MotionEvent.ACTION_UP, 160f, 140f)
    check(grip.page.texts.first() == note && grip.page.texts.last().x == 150f)
}

private fun cancellationAndSparseEvents() {
    val f = NotesFixture()
    f.input.focus(note); f.carry()
    f.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    f.touch(MotionEvent.ACTION_MOVE, 85f, 90f)
    check(f.shown?.x == 55f && f.lift != null)
    f.touch(MotionEvent.ACTION_CANCEL, 85f, 90f)
    check(f.shown == note && f.input.shown() == note && f.lift == null && f.commits == 0)
    // Returning the second finger to the normal handler permits native canvas/score zoom.
    f.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    f.touch(MotionEvent.ACTION_MOVE, 85f, 90f)
    check(!f.touch(MotionEvent.ACTION_POINTER_DOWN, 85f, 90f))
    check(!f.touch(MotionEvent.ACTION_MOVE, 100f, 100f))
    check(f.shown == note && !f.host.parent!!.disallowed && f.commits == 0)
    val sparse = NotesFixture()
    check(!sparse.touch(MotionEvent.ACTION_DOWN, 50f, 60f, Tool.HAND, tap = true))
    sparse.touch(MotionEvent.ACTION_UP, 100f, 100f, Tool.HAND, tap = true)
    check(sparse.input.focusedId == null)
    sparse.touch(MotionEvent.ACTION_DOWN, 50f, 60f, Tool.HAND, tap = true)
    sparse.touch(MotionEvent.ACTION_UP, 50f, 60f, Tool.HAND, tap = true, history = listOf(90f to 90f))
    check(sparse.input.focusedId == null)
    sparse.touch(MotionEvent.ACTION_DOWN, 50f, 60f, Tool.HAND, tap = true)
    sparse.touch(MotionEvent.ACTION_UP, 50f, 60f, Tool.HAND, tap = true)
    check(sparse.input.focusedId == note.id)
}

private fun staleEditsAndLockedCommands() {
    val f = NotesFixture()
    f.input.focus(note); f.carry()
    f.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    f.touch(MotionEvent.ACTION_MOVE, 80f, 90f)
    val changed = note.copy(text = "Newer words", stickyColor = StickyNotes.papers[1], stickyInk = emptyList())
    f.page = f.page.copy(texts = listOf(changed))
    f.touch(MotionEvent.ACTION_UP, 100f, 110f)
    check(f.commits == 0 && f.page.texts.single() == changed && f.lift == null)
    f.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    f.touch(MotionEvent.ACTION_MOVE, 80f, 90f)
    f.page = f.page.copy(texts = emptyList())
    f.input.refresh()
    f.touch(MotionEvent.ACTION_UP, 100f, 110f)
    check(f.page.texts.isEmpty() && f.input.focusedId == null && f.commits == 0)
    val locked = NotesFixture(layers = listOf(PageLayer(0, "Locked", locked = true)))
    locked.input.focus(note)
    check(locked.input.focusedId == null)
    locked.input.setText(note.id, "Wrong")
    locked.input.setPaper(note.id, StickyNotes.papers[2])
    locked.input.setTextSize(note.id, 48f)
    locked.input.clearInk(note.id)
    locked.input.duplicate(note.id)
    locked.input.bringToFront(note.id)
    locked.input.delete(note.id)
    check(locked.commits == 0 && locked.page.texts.single() == note)
    val ordinary = note.copy(id = "ordinary", stickyHeight = 0f)
    val protected = NotesFixture(listOf(ordinary))
    protected.input.delete(ordinary.id)
    check(protected.commits == 0)
}

private fun creationResizeAndDetach() {
    val f = NotesFixture(emptyList())
    f.touch(MotionEvent.ACTION_DOWN, 300f, 300f)
    f.touch(MotionEvent.ACTION_UP, 300f, 300f)
    val created = f.page.texts.single()
    check(created.width == StickyNotes.DEFAULT_WIDTH && created.size == StickyNotes.TEXT_SIZE)
    // A clean tap that puts a focused note down must not drop another one.
    f.touch(MotionEvent.ACTION_DOWN, 1000f, 1000f)
    f.touch(MotionEvent.ACTION_UP, 1000f, 1000f)
    check(f.page.texts.size == 1 && f.input.focusedId == null)
    val dragged = StickyNotes.spanning(30000f, 30000f, 0f, 0f, 0, 0, Float.NaN)
    check(dragged.width == TextBox.MAX_WIDTH && dragged.stickyHeight == StickyNotes.MAX_HEIGHT)
    check(dragged.x + dragged.width == 30000f && dragged.y + dragged.stickyHeight == 30000f)
    check(InkCodec.decodeTexts(InkCodec.encodeTexts(listOf(dragged))).single() == dragged)
    check(StickyNotes.textSize(Float.NaN) == StickyNotes.TEXT_SIZE && StickyNotes.renderedTextSize(8f) == 8f)
    check(StickyNotes.renderedTextSize(4f) == 4f)
    val resizing = NotesFixture()
    resizing.input.focus(note)
    // Off-centre grip travel resizes without snapping the corner onto the press.
    resizing.touch(MotionEvent.ACTION_DOWN, 145f, 125f, Tool.PEN)
    resizing.touch(MotionEvent.ACTION_UP, 165f, 145f, Tool.PEN)
    check(resizing.page.texts.single().width == 140f && resizing.page.texts.single().stickyHeight == 110f)
    val detach = NotesFixture()
    detach.carry()
    detach.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    detach.touch(MotionEvent.ACTION_MOVE, 80f, 90f)
    // Scrolling under a stationary finger changes the page-space position of its window lift.
    detach.dy = -40f
    detach.input.onDrop = { StickyDrop.LOCAL }
    detach.input.dropLift()
    detach.input.reset()
    check(detach.page.texts.single().x == 50f && detach.page.texts.single().y == 100f)
}

private fun acceptedDropsAndTransferRules() {
    val f = NotesFixture()
    f.carry(); f.input.onDrop = { StickyDrop.MOVED }
    f.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    f.touch(MotionEvent.ACTION_UP, 80f, 90f)
    check(f.draw().isEmpty()) // No return to the old position while Compose binds the removal.
    f.input.reset()
    check(f.draw().isEmpty()) // Changing tools while the bind is pending cannot reveal the source.
    f.page = f.page.copy(texts = emptyList()); f.input.refresh()
    f.page = f.page.copy(texts = listOf(note)); f.input.refresh()
    check(f.draw() == listOf(note)) // Undo can show the restored note again.
    val refused = NotesFixture()
    refused.carry(); refused.input.onDrop = { StickyDrop.REFUSED }
    refused.touch(MotionEvent.ACTION_DOWN, 50f, 60f)
    refused.touch(MotionEvent.ACTION_UP, 80f, 90f)
    check(refused.commits == 0 && refused.page.texts.single() == note && refused.draw() == listOf(note))
    val source = NotePage(id = "source", texts = listOf(note))
    val ordinary = TextBox(id = "ordinary", x = 1f, y = 2f, text = "Destination content")
    val target = NotePage(id = "target", texts = listOf(ordinary))
    val move = StickyNotes.transfer(source, target, note, -20f, 300f, 0, 2f)!!
    val placed = move.targetTexts.last()
    check(move.sourceTexts.isEmpty() && move.targetTexts.first() === ordinary)
    check(placed.id == note.id && placed.text == note.text && placed.stickyColor == note.stickyColor)
    check(placed.x == -20f && placed.y == 300f && placed.width == 240f && placed.stickyHeight == 180f && placed.size == 16f)
    near(placed.stickyInk.single().points.first().x, 20f)
    near(placed.stickyInk.single().width, 4f)
    check(placed.stickyInk.single().points.first().pressure == .5f)
    check(StickyNotes.transfer(source, target.copy(texts = listOf(note)), note, 0f, 0f, 0) == null)
    check(StickyNotes.transfer(source.copy(texts = listOf(note.copy(text = "Newer"))), target, note, 0f, 0f, 0) == null)
    check(StickyNotes.transfer(source, target.copy(layers = listOf(PageLayer(0, "Locked", locked = true))), note, 0f, 0f, 0) == null)
    check(StickyNotes.transfer(source.copy(layers = listOf(PageLayer(0, "Hidden", visible = false))), target, note, 0f, 0f, 0) == null)
    check(StickyNotes.transfer(source, target.copy(loaded = false), note, 0f, 0f, 0) == null)
    check(StickyNotes.transfer(source, source, note, 0f, 0f, 0) == null)
    check(StickyNotes.transfer(source, target, note, Float.NaN, 0f, 0) == null)
    check(StickyNotes.transfer(source, target, note, 0f, 0f, 0, 0f) == null)
}

private fun workspacePlacementAndInput() {
    val workspace = StickyWorkspaceView(Context())
    workspace.location[0] = 10; workspace.location[1] = 20
    workspace.attach()
    val geometry = EditorTransferCoordinator()
    val first = InkView(Context(), NotePage(id = "first")).also { it.attach() }
    val second = InkView(Context(), NotePage(id = "second")).also { it.attach() }
    val prefetch = InkView(Context(), NotePage(id = "prefetch")).also { it.attach() }
    geometry.regions[first.page.id] = Rect(30f, -50f, 630f, 750f)
    geometry.regions[second.page.id] = Rect(30f, 760f, 630f, 1560f)
    geometry.papers.putAll(geometry.regions)
    workspace.bind(listOf(second, prefetch, first), geometry)
    workspace.onDraw(Canvas())
    check(prefetch.drawn.isEmpty())
    check(first.drawn.single() == 20f to -70f && second.drawn.single() == 20f to 740f)
    workspace.viewTreeObserver.draw()
    val before = workspace.invalidations
    geometry.regions[first.page.id] = Rect(30f, -150f, 630f, 650f)
    workspace.viewTreeObserver.draw()
    check(workspace.invalidations == before + 1)
    val after = workspace.invalidations
    workspace.viewTreeObserver.draw()
    check(workspace.invalidations == after) // Stationary pages do not force continuous redraws.
    second.workspaceNotes = false
    val hiddenCount = second.drawn.size
    workspace.onDraw(Canvas())
    check(second.drawn.size == hiddenCount)
    second.workspaceNotes = true // Layout return uses the same registry handle.
    workspace.onDraw(Canvas())
    check(second.drawn.size == hiddenCount + 1)
    first.hit = { x, y -> x == 20f && y == 210f }
    workspace.onTouchEvent(MotionEvent(MotionEvent.ACTION_DOWN, 40f, 40f))
    check(first.event?.x == 20f && first.event?.y == 210f)
    workspace.bind(listOf(second), geometry)
    check(first.cancellations == 1)
    second.isLaidOut = false
    val count = second.drawn.size
    workspace.onDraw(Canvas())
    check(second.drawn.size == count)
    workspace.release(); workspace.detach()
    check(workspace.viewTreeObserver.listeners.isEmpty() && geometry.onGeometryChanged == null)
}

fun main() {
    layersAndOcclusion()
    cancellationAndSparseEvents()
    staleEditsAndLockedCommands()
    creationResizeAndDetach()
    acceptedDropsAndTransferRules()
    workspacePlacementAndInput()
    println("Sticky-note regressions: layer ordering/occlusion, protected actions, cancel/pinch handoff, sparse/history taps, stale edits, sizing round trips, resize, detach drops, accepted-drop visibility, scaled transfers and overlay placement/input passed")
}
