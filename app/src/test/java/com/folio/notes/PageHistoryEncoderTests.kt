package com.folio.notes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageHistoryEncoderTests {
    private fun edit(index: Int) = PageEdit(StrokesEdit.Remove(listOf(index)))

    @Test fun unchangedStepsAreEncodedOnlyOnceEvenWhenMovingBetweenStacks() {
        var calls = 0
        val encoder = PageHistoryEncoder { calls++; PageJournal.encodeEdit(it).toString() }
        val first = edit(1)
        val second = edit(2)
        encoder.encode(PageJournal.History(listOf(first), emptyList()))
        val history = PageJournal.History(listOf(first, second), emptyList())
        assertEquals(history, PageJournal.decodeHistory(encoder.encode(history)))
        assertEquals(2, calls)
        val undone = PageJournal.History(listOf(first), listOf(second))
        assertEquals(undone, PageJournal.decodeHistory(encoder.encode(undone)))
        assertEquals(2, calls)
    }

    @Test fun droppedStepsAreEvictedAndEqualButDistinctEditsAreNotConfused() {
        var calls = 0
        val encoder = PageHistoryEncoder { calls++; PageJournal.encodeEdit(it).toString() }
        val first = edit(1)
        val equal = edit(1)
        encoder.encode(PageJournal.History(listOf(first, equal), emptyList()))
        assertEquals(2, calls)
        encoder.encode(PageJournal.History.EMPTY)
        encoder.encode(PageJournal.History(listOf(first), emptyList()))
        assertEquals(3, calls)
    }

    @Test fun wireFormatMatchesExistingCodecForAllChannelsAndEmptyStacks() {
        val ink = Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(1f, 2f, 1f)))
        val mixed = PageEdit(
            StrokesEdit.Add(listOf(ink)),
            listOf(TextBox(id = "text", x = 0f, y = 0f, text = "quote \" newline\n")),
            emptyList()
        )
        val encoder = PageHistoryEncoder()
        for (history in listOf(
            PageJournal.History.EMPTY,
            PageJournal.History(listOf(mixed, edit(0)), listOf(PageEdit(StrokesEdit.Set(emptyList()))))
        )) {
            val encoded = encoder.encode(history)
            assertTrue(JSONObject(PageJournal.encodeHistory(history)).similar(JSONObject(encoded)))
            assertEquals(history, PageJournal.decodeHistory(encoded))
        }
    }

    @Test fun rollingHistoryOnlyEncodesNewSteps() {
        var calls = 0
        val encoder = PageHistoryEncoder { calls++; PageJournal.encodeEdit(it).toString() }
        val steps = mutableListOf<PageEdit>()
        repeat(200) { index ->
            steps.add(edit(index))
            if (steps.size > 60) steps.removeAt(0)
            val history = PageJournal.History(steps.toList(), emptyList())
            assertEquals(history, PageJournal.decodeHistory(encoder.encode(history)))
        }
        assertEquals(200, calls)
    }
}
