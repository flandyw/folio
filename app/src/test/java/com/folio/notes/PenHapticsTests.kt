package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class PenHapticsTests {
    @Test fun functionPulseMatchesTheHardwareConfirmedPacket() {
        assertArrayEquals(byteArrayOf(0x2C, 0x92.toByte(), 0x01, 0x02, 0xFF.toByte(), 0x02), PenHaptics.functionPulse())
    }

    @Test fun serviceAndWriteCharacteristicsAreWellFormedAndDistinct() {
        assertEquals("18092dbc-2a69-11ec-8d3d-0242ac130003", PenHaptics.SERVICE.toString())
        assertEquals("18093046-2a69-11ec-8d3d-0242ac130003", PenHaptics.WRITE.toString())
        assertNotEquals(PenHaptics.SERVICE, PenHaptics.WRITE)
    }

    @Test fun profileWriteIsDeliberatelyNotPartOfThePulse() {
        // 2C 94 02 TT LL changes the pen's stored system profile; only the one-shot pulse is sent.
        assertEquals(6, PenHaptics.functionPulse().size)
        assertEquals(0x92.toByte(), PenHaptics.functionPulse()[1])
    }
}
