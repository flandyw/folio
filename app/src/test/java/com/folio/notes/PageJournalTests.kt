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

    @Test fun appendingStoresOnlyTheNewStrokes() {
        val edit = PageJournal.diff(content(a), content(a, b))!!
        assertEquals(StrokesEdit.Add(listOf(b)), edit.strokes)
        assertEquals(content(a, b), applyAll(content(a), edit))
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

    @Test fun movingAStrokeIsAWholeListEdit() {
        // A lasso transform builds new stroke objects, so identity cannot match them.
        val moved = a.copy(points = listOf(InkPoint(50f, 50f, 1f)))
        val edit = PageJournal.diff(content(a, b), content(moved, b))!!
        assertEquals(StrokesEdit.Set(listOf(moved, b)), edit.strokes)
        assertEquals(content(moved, b), applyAll(content(a, b), edit))
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
        val line = PageJournal.encode(5, edit)
        val record = PageJournal.decode(line)!!
        assertEquals(5, record.seq)
        assertEquals(edit, record.edit)
    }

    @Test fun aTornOrUnknownLineIsRejected() {
        assertNull(PageJournal.decode("{\"seq\":1,\"st\":{\"add\":[{\"tool\":"))
        assertNull(PageJournal.decode("not json at all"))
        assertNull(PageJournal.decode("{\"seq\":1}"))
        // An explicit empty list is a real edit, unlike a null channel.
        assertNotNull(PageJournal.decode(PageJournal.encode(1, PageEdit(strokes = StrokesEdit.Set(emptyList())))))
    }

    @Test fun replaySkipsRecordsTheSnapshotAlreadyHolds() {
        val records = listOf(
            JournalRecord(1, PageEdit(strokes = StrokesEdit.Add(listOf(a)))),
            JournalRecord(2, PageEdit(strokes = StrokesEdit.Add(listOf(b)))),
            JournalRecord(3, PageEdit(strokes = StrokesEdit.Add(listOf(c))))
        )
        // A snapshot at sequence 2 already contains a and b; only c replays.
        assertEquals(content(a, b, c), PageJournal.replay(content(a, b), 2, records))
        assertEquals(3, PageJournal.lastSeq(records))
    }

    @Test fun aTruncatedTailLeavesTheEarlierRecordsStanding() {
        val good = PageJournal.encode(1, PageEdit(strokes = StrokesEdit.Add(listOf(a))))
        val torn = "{\"seq\":2,\"st\":{\"add\":[{\"tool\":\"PEN\",\"points\":"
        val records = listOfNotNull(PageJournal.decode(good), PageJournal.decode(torn))
        assertEquals(content(a), PageJournal.replay(PageContent.EMPTY, 0, records))
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

    @Test fun aTornFinalLineIsTrimmedButCompleteOnesSurvive() {
        val first = PageJournal.encode(1, PageEdit(strokes = StrokesEdit.Add(listOf(a)))) + "\n"
        val second = PageJournal.encode(2, PageEdit(strokes = StrokesEdit.Add(listOf(b)))) + "\n"
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
