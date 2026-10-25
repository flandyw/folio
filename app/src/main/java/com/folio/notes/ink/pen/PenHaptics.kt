package com.folio.notes.ink.pen

import java.util.UUID

/**
 * The OnePlus Pencil Pro's BLE protocol, as recovered from IPeManager 16.12.2.
 *
 * Only the one-shot function pulse is implemented. It is the one command a normally signed app has
 * been confirmed to deliver directly over GATT, and is the fallback when the pen service brokered by
 * ColorOS (`PenWritingFeedback`, which also owns the continuous writing texture) is not connected.
 */
object PenHaptics {
    val SERVICE: UUID = UUID.fromString("18092dbc-2a69-11ec-8d3d-0242ac130003")
    val WRITE: UUID = UUID.fromString("18093046-2a69-11ec-8d3d-0242ac130003")

    /**
     * Fire-and-forget function pulse (`2C 92 01 02 FF 02`), confirmed on hardware.
     *
     * The per-byte meaning is *not* established: this is not known to be amplitude, frequency or
     * duration, so the packet is treated as an opaque constant rather than a tunable waveform. The
     * related profile write (`2C 94 02 TT LL`) is deliberately absent, because setting a texture
     * changes the pen's stored system profile and does not by itself produce a soft stroke feedback.
     */
    fun functionPulse(): ByteArray = byteArrayOf(0x2C, 0x92.toByte(), 0x01, 0x02, 0xFF.toByte(), 0x02)
}
