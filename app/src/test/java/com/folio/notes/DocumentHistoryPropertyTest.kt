package com.folio.notes

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class DocumentHistoryPropertyTest {
    private fun stroke(random: Random) = Stroke(Tool.PEN, random.nextInt(), 2f,
        List(random.nextInt(1, 8)) { InkPoint(random.nextFloat(), random.nextFloat(), random.nextFloat()) })

    @Test fun randomizedEditInversionAndBinaryJournalReplayPreserveContentAndHistory() {
        val random = Random(53127)
        repeat(500) { trial ->
            val before = PageContent(strokes = List(random.nextInt(1, 60)) { stroke(random) })
            val after = when (trial % 5) {
                0 -> before.copy(strokes = before.strokes + List(random.nextInt(1, 6)) { stroke(random) })
                1 -> before.copy(strokes = before.strokes.filterIndexed { i, _ -> i % 3 != 0 })
                2 -> before.copy(strokes = before.strokes.toMutableList().also { it[random.nextInt(it.size)] = stroke(random) })
                3 -> before.copy(strokes = before.strokes.toMutableList().also { it.add(random.nextInt(it.size), stroke(random)) })
                else -> before.copy(strokes = before.strokes.reversed())
            }
            val edit = PageJournal.diff(before, after) ?: return@repeat
            val grouped = edit.copy(groupId = "g$trial", actionSequence = trial + 1L)
            val inverse = PageJournal.invert(grouped, before)
            assertEquals(after, PageJournal.apply(before, grouped))
            assertEquals(before, PageJournal.apply(after, inverse))
            assertEquals(grouped.groupId, inverse.groupId)
            val transaction = PageTransaction(1, 1, grouped, undoPush = inverse, clearRedo = true)
            val decoded = JournalBinary.readAll(JournalBinary.frame(transaction))
            assertEquals(listOf(transaction), decoded)
            assertEquals(after, PageJournal.replay(before, 0, decoded))
            assertEquals(listOf(inverse), PageJournal.foldHistory(PageJournal.History.EMPTY, decoded, 0).undo)
        }
    }
}
