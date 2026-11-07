package com.folio.notes.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class BinReaderTest {
    @Test fun varintRoundTripsEveryEncodingBoundary() {
        for (value in listOf(0, 1, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream().also { BinWriter(it).varint(value) }.toByteArray()
            val reader = BinReader(bytes)
            assertEquals(value, reader.varint())
            assertEquals(0, reader.remaining)
        }
    }

    @Test fun overflowingAndUnterminatedVarintsAreRejected() {
        for (last in 8..255) {
            val bytes = byteArrayOf(-1, -1, -1, -1, last.toByte())
            assertThrows(BinaryFormatException::class.java) { BinReader(bytes).varint() }
        }
        for (size in 0..4) {
            assertThrows(BinaryFormatException::class.java) { BinReader(ByteArray(size) { -128 }).varint() }
        }
    }

    @Test fun floatCountsAreCheckedBeforeMultiplicationOrAllocation() {
        for (count in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, 1 shl 30, 1)) {
            assertThrows(BinaryFormatException::class.java) { BinReader(byteArrayOf()).floats(count) }
        }
        val bytes = ByteArrayOutputStream().also { BinWriter(it).floats(floatArrayOf(1f, -2f)) }.toByteArray()
        val reader = BinReader(bytes)
        assertArrayEquals(floatArrayOf(1f, -2f), reader.floats(2), 0f)
        assertEquals(0, reader.remaining)
        assertTrue(reader.floats(0).isEmpty())
    }

    @Test fun invalidSlicesReportFormatErrors() {
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 2 to 0, 1 to Int.MAX_VALUE)) {
            assertThrows(BinaryFormatException::class.java) { BinReader(byteArrayOf(0), offset, length) }
        }
        assertEquals(0, BinReader(byteArrayOf(0), 1, 0).remaining)
    }
}
