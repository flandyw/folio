package com.folio.notes.storage

import com.folio.notes.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PageSnapshotBinaryTest {
    private val stroke = Stroke(Tool.PEN, 0xFF123456.toInt(), 2f,
        listOf(InkPoint(1f, 2f, 0.5f), InkPoint(3f, 4f, 1f)))

    private fun snapshot(layers: Boolean = false, grouped: Boolean = false): ByteArray {
        val edit = PageEdit(strokes = StrokesEdit.Remove(listOf(0)),
            groupId = if (grouped) "group-1" else null, actionSequence = if (grouped) 42 else 0)
        val page = NotePage(strokes = listOf(stroke), revision = 7,
            layers = if (layers) listOf(PageLayer(id = 1, name = "Ink")) else emptyList())
        return ByteArrayOutputStream().also {
            PageSnapshotBinary.write(it, page, 9, PageJournal.History(listOf(edit), listOf(edit)))
        }.toByteArray()
    }

    @Test fun supportedVersionsPreserveContentSequenceAndBothHistoryStacks() {
        for ((layers, grouped) in listOf(false to false, true to false, false to true)) {
            val bytes = snapshot(layers, grouped)
            assertEquals(if (grouped) 3 else if (layers) 2 else 1, bytes[4].toInt())
            val decoded = PageSnapshotBinary.read(bytes)
            assertEquals(listOf(stroke), decoded.strokes)
            assertEquals(7, decoded.revision)
            assertEquals(9, decoded.journalSeq)
            assertEquals(if (layers) listOf(PageLayer(id = 1, name = "Ink")) else emptyList<PageLayer>(), decoded.layers)
            assertEquals(1, decoded.history.undo.size)
            assertEquals(decoded.history.undo, decoded.history.redo)
            assertEquals(if (grouped) "group-1" else null, decoded.history.undo.single().groupId)
            assertEquals(if (grouped) 42L else 0L, decoded.history.undo.single().actionSequence)
            assertEquals(9 to 7, PageSnapshotBinary.peek(bytes, minOf(bytes.size, PageSnapshotBinary.HEAD_BYTES)))
        }
    }

    @Test fun everyTruncationAndSingleByteCorruptionIsRejected() {
        val bytes = snapshot(grouped = true)
        for (size in bytes.indices) {
            assertThrows(BinaryFormatException::class.java) { PageSnapshotBinary.read(bytes.copyOf(size)) }
        }
        for (at in bytes.indices) {
            val damaged = bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertThrows(BinaryFormatException::class.java) { PageSnapshotBinary.read(damaged) }
        }
    }

    @Test fun shortOrInvalidPeekLengthsReturnNull() {
        val bytes = snapshot()
        for (length in listOf(-1, 0, 4, 5, 6, Int.MAX_VALUE)) {
            assertNull(PageSnapshotBinary.peek(bytes, length))
        }
    }
}
