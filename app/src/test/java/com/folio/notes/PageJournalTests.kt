package com.folio.notes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageJournalTests {
    private fun stroke(x: Float) = Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(x, x, 1f)))
    private val a = stroke(1f)
    private val b = stroke(2f)
    private val c = stroke(3f)
    private val d = stroke(4f)

    private fun content(vararg strokes: Stroke) = PageContent(strokes = strokes.toList())
    private fun applyAll(base: PageContent, edit: PageEdit) = PageJournal.apply(base, edit)
    private fun transaction(seq: Int, forward: PageEdit) = PageTransaction(seq, forward = forward)
    /** A page of [count] distinct strokes, as a densely written page looks after a session. */
    private fun pageOf(count: Int) = PageContent(strokes = (0 until count).map { stroke(it.toFloat()) })

    @Test fun appendingStoresOnlyTheNewStrokes() {
        val edit = PageJournal.diff(content(a), content(a, b))!!
        assertEquals(StrokesEdit.Add(listOf(b)), edit.strokes)
        assertEquals(content(a, b), applyAll(content(a), edit))
    }

    @Test fun appendedStrokeCanBeUndoneAfterAFullPage() {
        val before = pageOf(4_000)
        val newStroke = stroke(4_001f)
        val forward = PageEdit(StrokesEdit.Add(listOf(newStroke)))
        val inverse = PageEdit(StrokesEdit.Remove(listOf(before.strokes.size)))
        val restoredForward = PageJournal.decode(PageJournal.encode(1, forward))!!.forward
        val restoredInverse = PageJournal.decode(PageJournal.encode(2, inverse))!!.forward
        val after = PageJournal.apply(before, restoredForward)
        assertEquals(before.strokes.size + 1, after.strokes.size)
        assertSame(newStroke, PageJournal.apply(before, forward).strokes.last())
        assertEquals(before.strokes, PageJournal.apply(after, restoredInverse).strokes)
    }

    @Test fun erasingStoresThePositionsThatLeft() {
        val edit = PageJournal.diff(content(a, b, c), content(a, c))!!
        assertEquals(StrokesEdit.Remove(listOf(1)), edit.strokes)
        assertEquals(content(a, c), applyAll(content(a, b, c), edit))
    }

    @Test fun removingMostOfThePageWritesTheSurvivorsInstead() {
        // Three casualties against one survivor: the survivors are the smaller thing to write.
        val edit = PageJournal.diff(content(a, b, c, d), content(a))!!
        assertEquals(StrokesEdit.Set(listOf(a)), edit.strokes)
        assertEquals(content(a), applyAll(content(a, b, c, d), edit))
    }

    @Test fun movingAStrokeIsAnInPlaceReplace() {
        // A lasso transform builds new stroke objects, so identity cannot match them; what it can do
        // is say which positions changed.
        val moved = a.copy(points = listOf(InkPoint(50f, 50f, 1f)))
        val edit = PageJournal.diff(content(a, b), content(moved, b))!!
        assertEquals(StrokesEdit.Replace(listOf(IndexedStroke(0, moved))), edit.strokes)
        assertEquals(content(moved, b), applyAll(content(a, b), edit))
    }

    @Test fun erasingOnADensePageNeverRewritesThePage() {
        val before = pageOf(10_000)
        val kept = before.strokes.filterIndexed { index, _ -> index !in listOf(821, 822, 830) }
        val after = before.copy(strokes = kept)
        val forward = PageJournal.diff(before, after)!!
        assertEquals(StrokesEdit.Remove(listOf(821, 822, 830)), forward.strokes)
        // Undo has to name the three strokes that left, not the 9,997 that stayed. It comes off the
        // forward edit, not off a second diff: a diff of the same two pages would describe the same
        // page as "three strokes appended at the end" and undo would move the ink instead.
        val inverse = PageJournal.invert(forward, before)!!
        assertTrue("undo must not carry the page", inverse.strokes !is StrokesEdit.Set)
        // Each stroke is addressed by how many of the survivors precede it, not by where it used
        // to be: 821 and 822 were neighbours, so the second lands right after the first.
        assertEquals(StrokesEdit.Insert(listOf(821, 821, 828).mapIndexed { rank, at ->
            IndexedStroke(at, before.strokes[listOf(821, 822, 830)[rank]])
        }), inverse.strokes)
        assertEquals(before, applyAll(after, inverse))
        // Both survive the wire without growing with the page.
        val line = PageJournal.encode(transaction(1, forward)) + PageJournal.encode(transaction(2, inverse))
        assertTrue("a 40KB line would mean the page leaked in", line.length < 4_000)
        assertEquals(forward.strokes, PageJournal.decode(PageJournal.encode(transaction(1, forward)))!!.forward.strokes)
    }

    @Test fun everyEditUndoesItselfBackToTheSamePage() {
        val dense = pageOf(40)
        val moved = dense.strokes[3].copy(points = listOf(InkPoint(90f, 90f, 1f)))
        val cases = listOf(
            dense to dense.copy(strokes = dense.strokes + stroke(99f)),                  // pen-up
            dense to dense.copy(strokes = dense.strokes.filterIndexed { i, _ -> i % 3 != 0 }), // eraser
            dense to dense.copy(strokes = dense.strokes.dropLast(5)),                     // a big erase
            dense to dense.copy(strokes = dense.strokes.drop(1)),                         // clear-ish
            dense to dense.copy(strokes = dense.strokes.toMutableList().also { it[3] = moved }),  // a lasso move
            dense to PageContent(strokes = listOf(moved) + dense.strokes),               // moved to the front
            dense to PageContent(strokes = listOf(moved) + dense.strokes.drop(1)),        // dropped and replaced
            dense to PageContent(strokes = dense.strokes.take(2) + listOf(moved, stroke(7f))), // mixed
            dense to dense.copy(strokes = dense.strokes.reversed())                      // a full reorder
        )
        for ((before, after) in cases) {
            val forward = PageJournal.diff(before, after) ?: continue
            assertEquals("diff must reach the new page", after, applyAll(before, forward))
            val inverse = PageJournal.invert(forward, before)
            assertEquals("undo must return the old page", before, applyAll(after, inverse))
            // The same pair has to survive the wire, which is where an index convention slips.
            val stored = PageJournal.decode(PageJournal.encode(transaction(1, forward)))!!.forward
            assertEquals(after, applyAll(before, stored))
            val storedBack = PageJournal.decode(PageJournal.encode(transaction(2, inverse)))!!.forward
            assertEquals(before, applyAll(after, storedBack))
        }
    }

    @Test fun aLassoOverADensePageStoresOnlyTheStrokesItTouched() {
        val before = pageOf(10_000)
        val moved = listOf(402, 403, 407).map { before.strokes[it].copy(points = listOf(InkPoint(9f, 9f, 1f))) }
        val after = before.strokes.toMutableList()
        listOf(402, 403, 407).forEachIndexed { slot, index -> after[index] = moved[slot] }
        val forward = PageJournal.diff(before, before.copy(strokes = after))!!
        assertEquals(StrokesEdit.Replace(listOf(402, 403, 407).map { IndexedStroke(it, moved[listOf(402, 403, 407).indexOf(it)]) }),
            forward.strokes)
        val inverse = PageJournal.invert(forward, before)!!
        assertEquals(StrokesEdit.Replace(listOf(402, 403, 407).map { IndexedStroke(it, before.strokes[it]) }), inverse.strokes)
        assertEquals(before, applyAll(before.copy(strokes = after), inverse))
    }

    @Test fun aStrokeDroppedFromTheMiddleComesBackWhereItWas() {
        val edit = PageJournal.diff(content(a, b), content(a, b, c))!!
        assertEquals(StrokesEdit.Add(listOf(c)), edit.strokes)
        val inserted = PageJournal.diff(content(a, b, c), content(a, b))!!
        assertEquals(StrokesEdit.Remove(listOf(2)), inserted.strokes)
        // A paste in the middle keeps the strokes around it where they were.
        val pasted = PageJournal.diff(content(a, b), content(a, c, b))!!
        assertEquals(StrokesEdit.Insert(listOf(IndexedStroke(1, c))), pasted.strokes)
        assertEquals(content(a, b), applyAll(content(a, c, b), PageJournal.invert(pasted, content(a, c, b))!!))
    }

    @Test fun oneEditCarriesInkTextAndPicturesTogether() {
        val box = TextBox(id = "t1", x = 1f, y = 2f, text = "hi")
        val picture = PageImage(id = "p1", x = 3f, y = 4f, width = 50f, height = 40f)
        val before = PageContent(strokes = listOf(a), texts = listOf(box), images = listOf(picture))
        val after = before.copy(strokes = listOf(a, b), texts = emptyList(), images = emptyList())
        val edit = PageJournal.diff(before, after)!!
        assertEquals(StrokesEdit.Add(listOf(b)), edit.strokes)
        assertEquals(emptyList<TextBox>(), edit.texts)
        assertEquals(emptyList<PageImage>(), edit.images)
        assertEquals(after, applyAll(before, edit))
    }

    @Test fun noChangeProducesNoEdit() {
        assertNull(PageJournal.diff(content(a, b), content(a, b)))
        assertNull(PageJournal.diff(PageContent(), PageContent()))
    }

    @Test fun aJournalLineRoundTrips() {
        val edit = PageEdit(StrokesEdit.Remove(listOf(0, 3)), texts = emptyList())
        val record = PageJournal.decode(PageJournal.encode(5, edit))!!
        assertEquals(5, record.seq)
        assertEquals(edit, record.forward)
    }

    @Test fun aTransactionRoundTripsWithItsUndoState() {
        val inverse = PageEdit(StrokesEdit.Remove(listOf(9)))
        val log = PageTransaction(7, revision = 41, forward = PageEdit(StrokesEdit.Add(listOf(b))),
            undoPush = inverse, clearRedo = true)
        val restored = PageJournal.decode(PageJournal.encode(log))!!
        assertEquals(log, restored)
        // Undoing it says so itself: the stack it left, the stack it came out of, and the step.
        val undone = PageTransaction(8, revision = 42, forward = inverse, redoPush = log.forward, undoPop = true)
        assertEquals(undone, PageJournal.decode(PageJournal.encode(undone))!!)
    }

    @Test fun aLineFromAnOlderBuildStillReads() {
        // The previous format put the edit at the top level and knew nothing about undo state.
        val line = "{\"seq\":3,\"st\":{\"add\":[{\"opacity\":1.0,\"tool\":\"PEN\",\"color\":0," +
            "\"width\":2.0,\"points\":[[1.0,2.0,1.0]]}]}}"
        val record = PageJournal.decode(line)!!
        assertEquals(3, record.seq)
        assertEquals(PageEdit(StrokesEdit.Add(listOf(stroke(1f).copy(points = listOf(InkPoint(1f, 2f, 1f)))))), record.forward)
        assertNull(record.undoPush)
    }

    @Test fun aTornOrUnknownLineIsRejected() {
        assertNull(PageJournal.decode("{\"seq\":1,\"st\":{\"add\":[{\"tool\":"))
        assertNull(PageJournal.decode("not json at all"))
        assertNull(PageJournal.decode("{\"seq\":1}"))
        // An explicit empty list is a real edit, unlike a null channel.
        assertNotNull(PageJournal.decode(PageJournal.encode(1, PageEdit(strokes = StrokesEdit.Set(emptyList())))))
    }

    @Test fun everyIndexedEditSurvivesTheWire() {
        val ink = stroke(7f)
        val edits = listOf(
            PageEdit(StrokesEdit.Add(listOf(ink))),
            PageEdit(StrokesEdit.Remove(listOf(0, 2))),
            PageEdit(StrokesEdit.Insert(listOf(IndexedStroke(1, ink)))),
            PageEdit(StrokesEdit.Replace(listOf(IndexedStroke(4, ink)))),
            PageEdit(StrokesEdit.Rewrite(listOf(3, 5), listOf(IndexedStroke(3, ink)))),
            PageEdit(StrokesEdit.Set(listOf(ink)))
        )
        for (edit in edits) {
            val line = PageJournal.encode(transaction(1, edit))
            assertEquals(edit.strokes, PageJournal.decode(line)!!.forward.strokes)
        }
        // The shapes are distinguishable on read, which is what the keys are for.
        assertTrue(JSONObject(PageJournal.encode(transaction(1, edits[2])))["f"].toString().contains("ins"))
        assertTrue(JSONObject(PageJournal.encode(transaction(1, edits[4])))["f"].toString().contains("del"))
    }

    @Test fun replaySkipsRecordsTheSnapshotAlreadyHolds() {
        val records = listOf(
            transaction(1, PageEdit(strokes = StrokesEdit.Add(listOf(a)))),
            transaction(2, PageEdit(strokes = StrokesEdit.Add(listOf(b)))),
            transaction(3, PageEdit(strokes = StrokesEdit.Add(listOf(c))))
        )
        // A snapshot at sequence 2 already contains a and b; only c replays.
        assertEquals(content(a, b, c), PageJournal.replay(content(a, b), 2, records))
        assertEquals(3, PageJournal.lastSeq(records))
    }

    @Test fun aTruncatedTailLeavesTheEarlierRecordsStanding() {
        val good = PageJournal.encode(transaction(1, PageEdit(strokes = StrokesEdit.Add(listOf(a)))))
        val torn = "{\"seq\":2,\"st\":{\"add\":[{\"tool\":\"PEN\",\"points\":"
        val records = listOfNotNull(PageJournal.decode(good), PageJournal.decode(torn))
        assertEquals(content(a), PageJournal.replay(PageContent.EMPTY, 0, records))
    }

    @Test fun historyFoldsOutOfTheLogOntoASnapshotsStacks() {
        val first = PageEdit(StrokesEdit.Remove(listOf(1)))
        val second = PageEdit(StrokesEdit.Remove(listOf(2)))
        val log = listOf(
            PageTransaction(1, forward = first, undoPush = PageEdit(StrokesEdit.Insert(listOf(IndexedStroke(1, b)))), clearRedo = true),
            PageTransaction(2, forward = second, undoPush = PageEdit(StrokesEdit.Insert(listOf(IndexedStroke(2, c)))), clearRedo = true),
            // Undo the second: it leaves the undo stack and joins the redo stack.
            PageTransaction(3, forward = PageJournal.decode(PageJournal.encode(1, second))!!.forward, undoPop = true,
                redoPush = second)
        )
        val folded = PageJournal.foldHistory(PageJournal.History.EMPTY, log, 0)
        assertEquals(1, folded.undo.size)
        assertEquals(listOf(second), folded.redo)
        // A snapshot taken after record 1 keeps one step; the record after it is not folded in yet.
        // A snapshot taken after record 1 keeps the stacks of that moment; the rest is still the
        // log's to apply, and record 1 must not be counted a second time on the way.
        val atOne = PageJournal.foldHistory(PageJournal.History.EMPTY, log.take(1), 0)
        assertEquals(1, atOne.undo.size)
        val resumed = PageJournal.foldHistory(atOne, log, 1)
        assertEquals(atOne.undo, resumed.undo)
        assertEquals(listOf(second), resumed.redo)
    }

    @Test fun aSnapshotThatAlreadyHoldsTheRecordsDoesNotTakeThemTwice() {
        // A crash between the snapshot landing and the journal being cleared leaves both copies of
        // the records. Ink skips what the snapshot holds; the stacks must do the same.
        val first = PageEdit(StrokesEdit.Remove(listOf(1)))
        val second = PageEdit(StrokesEdit.Remove(listOf(2)))
        val log = listOf(
            PageTransaction(1, forward = first, undoPush = PageEdit(StrokesEdit.Insert(listOf(IndexedStroke(1, b))))),
            PageTransaction(2, forward = second, undoPush = PageEdit(StrokesEdit.Insert(listOf(IndexedStroke(2, c)))))
        )
        val snapshotStacks = PageJournal.foldHistory(PageJournal.History.EMPTY, log, 0)
        assertEquals(2, snapshotStacks.undo.size)
        val reopened = PageJournal.foldHistory(snapshotStacks, log, 2)
        assertEquals("the log must be skipped, not replayed again", snapshotStacks, reopened)
    }

    @Test fun historyStaysBounded() {
        val log = (1..500).map { seq ->
            PageTransaction(seq, forward = PageEdit(texts = emptyList()),
                undoPush = PageEdit(texts = listOf(TextBox(id = "$seq", x = 0f, y = 0f, text = "$seq"))))
        }
        val folded = PageJournal.foldHistory(PageJournal.History.EMPTY, log, 0)
        assertEquals(PageJournal.HISTORY_LIMIT, folded.undo.size)
        assertEquals("500", (folded.undo.last().texts?.first()?.id))
    }

    @Test fun historyRoundTripsThroughTheCodec() {
        val undo = listOf(PageEdit(strokes = StrokesEdit.Remove(listOf(2))), PageEdit(texts = emptyList()))
        val redo = listOf(PageEdit(strokes = StrokesEdit.Add(listOf(d))))
        val restored = PageJournal.decodeHistory(PageJournal.encodeHistory(PageJournal.History(undo, redo)))
        assertEquals(undo, restored.undo)
        assertEquals(redo, restored.redo)
        assertEquals(PageJournal.History.EMPTY, PageJournal.decodeHistory(null))
        assertEquals(PageJournal.History.EMPTY, PageJournal.decodeHistory("garbage"))
    }

    @Test fun thePageCodecCarriesTheSnapshotSequence() {
        val page = NotePage(strokes = listOf(a))
        assertEquals(0, NotePageCodec.journalSeq(NotePageCodec.encode(page)))
        assertEquals(7, NotePageCodec.journalSeq(NotePageCodec.encode(page, journalSeq = 7)))
        // The sequence never leaks into the page itself, which still reads back unchanged.
        assertEquals(page.strokes, NotePageCodec.decode(NotePageCodec.encode(page, 7), page.asSummary()).strokes)
    }

    @Test fun aSnapshotCarriesTheStacksAndRevisionTheLogGaveUp() {
        val page = NotePage(revision = 12, strokes = listOf(a))
        val history = PageJournal.History(listOf(PageEdit(strokes = StrokesEdit.Remove(listOf(0)))), emptyList())
        val encoded = NotePageCodec.encode(page, journalSeq = 4, history = history)
        assertEquals(4, NotePageCodec.journalSeq(encoded))
        assertEquals(12, NotePageCodec.revision(encoded))
        assertEquals(history, NotePageCodec.history(encoded))
        // A snapshot from a build that had neither simply has none.
        assertNull(NotePageCodec.history(NotePageCodec.encode(page)))
        assertEquals(0, NotePageCodec.revision(NotePageCodec.encode(NotePage())))
    }

    @Test fun aTornFinalLineIsTrimmedButCompleteOnesSurvive() {
        val first = PageJournal.encode(transaction(1, PageEdit(strokes = StrokesEdit.Add(listOf(a))))) + "\n"
        val second = PageJournal.encode(transaction(2, PageEdit(strokes = StrokesEdit.Add(listOf(b))))) + "\n"
        val torn = "{\"seq\":3,\"st\":{\"add\":"
        val bytes = (first + second + torn).toByteArray(Charsets.UTF_8)
        assertEquals((first + second).toByteArray(Charsets.UTF_8).size, PageJournal.completePrefixLength(bytes))
        // A file that ends cleanly keeps every byte.
        assertEquals(first.toByteArray(Charsets.UTF_8).size, PageJournal.completePrefixLength(first.toByteArray(Charsets.UTF_8)))
        assertEquals(0, PageJournal.completePrefixLength(torn.toByteArray(Charsets.UTF_8)))
    }

    @Test fun anEmptyEditIsNeverWritten() {
        assertTrue(PageEdit().isEmpty)
        assertFalse(PageEdit(texts = emptyList()).isEmpty)
        assertFalse(PageEdit(strokes = StrokesEdit.Set(emptyList())).isEmpty)
    }
}
