package io.github.noisetta.nikonpull.ptp

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraEventTest {
    @Test fun readsTheEventCodeAndItsParameters() {
        val data = LittleEndianWriter().apply { u16(0x4002); u32(7); u32(719753004L); u32(1); u32(2) }.toByteArray()
        val event = PtpDataParser.parseEvent(data)
        assertEquals(0x4002, event.code)
        assertEquals(listOf(719753004L, 1L, 2L), event.params)
    }
    @Test fun anEventWithoutParametersIsValid() {
        val data = LittleEndianWriter().apply { u16(0x400D); u32(9) }.toByteArray()
        val event = PtpDataParser.parseEvent(data)
        assertEquals(0x400D, event.code)
        assertEquals(emptyList<Long>(), event.params)
    }
    @Test fun onlyThreeParametersAreRead() {
        val data = LittleEndianWriter().apply { u16(0x4002); u32(1); u32(1); u32(2); u32(3); u32(4) }.toByteArray()
        assertEquals(listOf(1L, 2L, 3L), PtpDataParser.parseEvent(data).params)
    }
    @Test(expected = PtpException::class) fun aTruncatedEventIsRejected() {
        PtpDataParser.parseEvent(byteArrayOf(0x02))
    }
}
