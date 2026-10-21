package com.folio.notes.ink.recognition

import com.folio.notes.document.DocumentReducer
import com.folio.notes.document.DocumentToolEdits
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.Notebook
import com.folio.notes.model.PageLayer
import com.folio.notes.model.Stroke
import com.folio.notes.model.TextBox
import com.folio.notes.model.Tool
import com.folio.notes.model.page
import com.folio.notes.storage.PageContent
import com.folio.notes.storage.PageEdit
import com.folio.notes.storage.PageJournal
import com.folio.notes.storage.PageSnapshotBinary
import com.folio.notes.storage.content
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class ScribbleUndoTest {
    private fun stroke(x: Float, layer: Int = 0) = Stroke(Tool.PEN, 0xFF123456.toInt(), 2f,
        listOf(InkPoint(x, -3f), InkPoint(x, 28f)), layer = layer)
    private val scribble = Stroke(Tool.PEN, 0xFF987654.toInt(), 3f,
        (0..5).map { InkPoint(if (it % 2 == 0) 0f else 100f, it * 5f, .3f + it * .1f) }, layer = 3)
    private val options = DocumentToolEdits.Options(scribble = true)

    /** Replay serialized positional edits: restoring an erased middle stroke must keep stacking. */
    private fun assertUndoRedo(before: PageContent, steps: List<PageContent>) {
        var current = before
        val undo = ArrayList<PageEdit>()
        for (after in steps) {
            val forward = PageJournal.diff(current, after)!!
            undo += PageJournal.invert(forward, current)
            current = PageJournal.apply(current, forward)
            assertEquals(after, current)
        }
        val page = NotePage(id = "p", strokes = current.strokes, texts = current.texts, layers = current.layers)
        val bytes = ByteArrayOutputStream().also { PageSnapshotBinary.write(it, page, steps.size, PageJournal.History(undo, emptyList())) }
        val snapshot = PageSnapshotBinary.read(bytes.toByteArray())
        current = PageContent(snapshot.strokes, snapshot.texts, snapshot.images, snapshot.layers)
        val redo = ArrayList<PageEdit>()
        for (i in steps.indices.reversed()) {
            val inverse = snapshot.history.undo[i]
            redo += PageJournal.invert(inverse, current)
            current = PageJournal.apply(current, inverse)
            assertEquals(if (i == 0) before else steps[i - 1], current)
        }
        for ((i, forward) in redo.reversed().withIndex()) {
            current = PageJournal.apply(current, forward)
            assertEquals(steps[i], current)
        }
    }

    @Test fun undoRecoversPenAndHighlighterScribblesWithTheirOriginalStyle() {
        val layers = listOf(PageLayer.BASE, PageLayer(1, "Locked", locked = true),
            PageLayer(2, "Hidden", visible = false), PageLayer(3, "Drawing"))
        val page = NotePage(strokes = listOf(stroke(150f), stroke(50f), stroke(50f, 1), stroke(50f, 2), stroke(200f)), layers = layers)
        for (tool in listOf(Tool.PEN, Tool.HIGHLIGHTER)) {
            val drawn = scribble.copy(tool = tool, opacity = if (tool == Tool.HIGHLIGHTER) .3f else 1f)
            val steps = DocumentToolEdits.contentSteps(page, listOf(drawn), emptyMap(), options)
            assertEquals(2, steps.size)
            assertEquals(page.strokes + drawn, steps[0].strokes)
            assertEquals(listOf(page.strokes[0], page.strokes[2], page.strokes[3], page.strokes[4]), steps[1].strokes)
            assertUndoRedo(page.content(), steps)
        }
    }

    @Test fun undoRecoversScribblesOnStickyNotesAndPaperTogether() {
        val note = TextBox(id = "sticky", x = 20f, y = 30f, text = "Keep me", stickyHeight = 150f,
            stickyInk = listOf(stroke(150f), stroke(50f), stroke(200f)))
        val page = NotePage(strokes = listOf(stroke(150f), stroke(50f)), texts = listOf(note))
        val local = scribble.copy(layer = 0)
        val steps = DocumentToolEdits.contentSteps(page, listOf(scribble), mapOf(note.id to listOf(local)), options)
        assertEquals(2, steps.size)
        assertEquals(page.strokes + scribble, steps[0].strokes)
        assertEquals(note.stickyInk + local, steps[0].texts.single().stickyInk)
        assertEquals(listOf(page.strokes[0]), steps[1].strokes)
        assertEquals(listOf(note.stickyInk[0], note.stickyInk[2]), steps[1].texts.single().stickyInk)
        assertEquals(note.copy(stickyInk = steps[1].texts.single().stickyInk), steps[1].texts.single())
        assertUndoRedo(page.content(), steps)
    }

    @Test fun ordinaryDrawingDisabledRecognitionAndExplicitErasingKeepOneStep() {
        val target = NotePage(strokes = listOf(stroke(50f)))
        val untouched = NotePage(strokes = listOf(stroke(200f)))
        for ((page, settings) in listOf(untouched to options, target to options.copy(scribble = false))) {
            val steps = DocumentToolEdits.contentSteps(page, listOf(scribble), emptyMap(), settings)
            assertEquals(1, steps.size)
            assertEquals(page.strokes + scribble, steps.single().strokes)
            assertUndoRedo(page.content(), steps)
        }
        val line = scribble.copy(points = listOf(InkPoint(0f, 15f), InkPoint(100f, 15f)))
        assertEquals(1, DocumentToolEdits.contentSteps(target, listOf(line), emptyMap(), options).size)
        val erased = DocumentToolEdits.contentSteps(target, listOf(scribble.copy(tool = Tool.ERASER, width = 50f)),
            emptyMap(), options.copy(erasing = true, wholeStroke = true))
        assertEquals(1, erased.size)
        assertTrue(erased.single().strokes.isEmpty())
        assertUndoRedo(target.content(), erased)
    }

    @Test fun groupedUndoAndRedoRestoreTheEntireCrossPageDrawing() {
        val pages = listOf(NotePage(id = "a", strokes = listOf(stroke(50f))),
            NotePage(id = "b", strokes = listOf(stroke(200f))))
        val steps = pages.associate { it.id to DocumentToolEdits.contentSteps(it, listOf(scribble), emptyMap(), options) }
        var note = Notebook(id = "n", title = "Notebook", pages = pages)
        val history = pages.associate { it.id to PageJournal.History.EMPTY }.toMutableMap()
        var sequence = 0L
        fun install(change: DocumentReducer.GroupChange) {
            note = change.notebook
            change.transactions.forEach { (id, transaction) ->
                history[id] = PageJournal.foldHistory(history.getValue(id), listOf(transaction.copy(seq = (++sequence).toInt())), 0)
            }
        }
        fun commit(contents: Map<String, PageContent>) {
            val edits = contents.mapNotNull { (id, after) -> PageJournal.diff(note.pages.page(id)!!.content(), after)?.let { id to it } }.toMap()
            install(DocumentReducer.group(note, edits, note.pages.associate { it.id to it.revision }, "g${++sequence}", sequence, 0)!!)
        }
        fun replay(undo: Boolean) {
            val step = (if (undo) note.documentHistory.undo else note.documentHistory.redo).last()
            val tops = step.pages.associateWith { id -> (if (undo) history.getValue(id).undo else history.getValue(id).redo).last() }
            install(DocumentReducer.history(note, step, tops, undo, ++sequence, "history$sequence", 0)!!)
        }
        val drawn = steps.mapValues { it.value.first() }
        val erased = steps.mapValues { it.value.last() }
        commit(drawn); commit(erased)
        assertEquals(listOf("a"), note.documentHistory.undo.last().pages)
        replay(undo = true)
        assertEquals(drawn, note.pages.associate { it.id to it.content() })
        replay(undo = true)
        assertEquals(pages.associate { it.id to it.content() }, note.pages.associate { it.id to it.content() })
        replay(undo = false)
        assertEquals(drawn, note.pages.associate { it.id to it.content() })
        replay(undo = false)
        assertEquals(erased, note.pages.associate { it.id to it.content() })
    }
}
