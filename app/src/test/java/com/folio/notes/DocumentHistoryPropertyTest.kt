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

    @Test fun groupedUndoRedoPlansReplayThroughRealBinaryJournals() {
        val random = Random(84019)
        repeat(100) { trial ->
            val before = Notebook(id = "n", title = "Notebook", pages = IndexedItems.of(List(17) { index ->
                NotePage(id = "p$index", strokes = List(random.nextInt(1, 8)) { stroke(random) })
            }) { it.id })
            val ids = before.pages.shuffled(random).take(3).map { it.id }
            val edits = ids.associateWith { id ->
                val page = before.pages.page(id)!!
                val changed = page.content().copy(strokes = page.strokes.reversed() + stroke(random),
                    texts = listOf(TextBox(x = 1f, y = 1f, text = "Answer $trial")))
                PageJournal.diff(page.content(), changed)!!
            }
            val created = DocumentReducer.group(before, edits, emptyMap(), "g", 10, 0)!!
            val undo = DocumentReducer.history(created.notebook, created.notebook.documentHistory.undo.single(),
                created.transactions.mapValues { it.value.undoPush }, true, 11, "undo", 1)!!
            val redo = DocumentReducer.history(undo.notebook, undo.notebook.documentHistory.redo.single(),
                undo.transactions.mapValues { it.value.redoPush }, false, 12, "redo", 2)!!
            ids.forEach { id ->
                val original = before.pages.page(id)!!.content()
                val changed = created.pages.getValue(id).content()
                assertEquals(original, undo.pages.getValue(id).content())
                assertEquals(changed, redo.pages.getValue(id).content())
                val transactions = listOf(created, undo, redo).mapIndexed { index, change ->
                    val tx = change.transactions.getValue(id).copy(seq = index + 1)
                    JournalBinary.readAll(JournalBinary.frame(tx)).single()
                }
                assertEquals(original, PageJournal.replay(original, 0, transactions.take(2)))
                assertEquals(changed, PageJournal.replay(original, 0, transactions))
                val undoneHistory = PageJournal.foldHistory(PageJournal.History.EMPTY, transactions.take(2), 0)
                assertTrue(undoneHistory.undo.isEmpty())
                assertEquals(listOf(transactions[1].redoPush), undoneHistory.redo)
                val redoneHistory = PageJournal.foldHistory(PageJournal.History.EMPTY, transactions, 0)
                assertTrue(redoneHistory.redo.isEmpty())
                assertEquals(listOf(transactions[2].undoPush), redoneHistory.undo)
            }
            before.pages.filterNot { it.id in ids }.forEach { page ->
                assertSame(page, redo.notebook.pages.page(page.id))
            }
        }
    }
}
