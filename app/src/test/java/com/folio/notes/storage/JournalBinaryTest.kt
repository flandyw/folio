package com.folio.notes.storage

import com.folio.notes.model.InkPoint
import com.folio.notes.model.Stroke
import com.folio.notes.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class JournalBinaryTest {
    private fun frame(seq: Int): ByteArray {
        val stroke = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f, listOf(InkPoint(1f, 2f, 1f), InkPoint(3f, 4f, 1f)))
        val edit = PageJournal.diff(PageContent.EMPTY, PageContent(strokes = listOf(stroke)))!!
        return JournalBinary.frame(PageTransaction(seq, seq, edit))
    }

    @Test fun tornTailIsDroppedButIntactRecordsSurvive() {
        val first = frame(1)
        val torn = frame(2).copyOf(10)
        assertEquals(listOf(1), JournalBinary.readAll(first + torn).map { it.seq })
        assertEquals(first.size, JournalBinary.completePrefixLength(first + torn))
    }

    @Test fun intactFrameThatWillNotDecodeThrowsInsteadOfHidingLaterRecords() {
        val bad = frame(2)
        bad[8] = 99 // unknown record version, then re-checksum so only decoding can reject it
        val crc = CRC32().also { it.update(bad, 8, bad.size - 8) }.value.toInt()
        ByteBuffer.wrap(bad, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
        val journal = frame(1) + bad + frame(3)
        assertEquals(journal.size, JournalBinary.completePrefixLength(journal))
        assertThrows(BinaryFormatException::class.java) { JournalBinary.readAll(journal) }
    }
}
