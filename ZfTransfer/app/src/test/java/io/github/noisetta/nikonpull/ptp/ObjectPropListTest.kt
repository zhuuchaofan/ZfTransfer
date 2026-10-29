package io.github.noisetta.nikonpull.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObjectPropListTest {
    private fun LittleEndianWriter.ptpString(value: String) {
        u8(value.length + 1)
        utf16NullTerminated(value)
    }
    private fun LittleEndianWriter.u8(value: Int) = bytes(byteArrayOf(value.toByte()))
    private fun LittleEndianWriter.u64(value: Long) { u32(value and 0xffffffffL); u32(value ushr 32) }
    private fun LittleEndianWriter.element(handle: Long, property: Int, type: Int, value: LittleEndianWriter.() -> Unit) {
        u32(handle); u16(property); u16(type); value()
    }

    @Test fun readsNumbersAndStringsOfEveryObject() {
        val data = LittleEndianWriter().apply {
            u32(4)
            element(7, 0xDC02, 0x0004) { u16(0x3801) }
            element(7, 0xDC01, 0x0006) { u32(0x10001) }
            element(7, 0xDC04, 0x0008) { u64(9_728_710L) }
            element(7, 0xDC07, 0xFFFF) { ptpString("_ZCF3867.JPG") }
        }.toByteArray()

        val values = PtpDataParser.parseObjectPropList(data)

        assertEquals(4, values.size)
        assertEquals(0x3801L, values[0].number)
        assertEquals(0x10001L, values[1].number)
        assertEquals(9_728_710L, values[2].number)
        assertEquals("_ZCF3867.JPG", values[3].text)
        assertNull(values[0].text)
        values.forEach { assertEquals(7L, it.handle) }
    }
    @Test fun anEmptyListIsValid() {
        assertEquals(emptyList<PtpDataParser.ObjectPropValue>(), PtpDataParser.parseObjectPropList(LittleEndianWriter().apply { u32(0) }.toByteArray()))
    }
    @Test fun anEmptyStringKeepsItsPlace() {
        val data = LittleEndianWriter().apply {
            u32(2)
            element(1, 0xDC08, 0xFFFF) { u8(0) }
            element(2, 0xDC08, 0xFFFF) { ptpString("20261029T101500") }
        }.toByteArray()
        val values = PtpDataParser.parseObjectPropList(data)
        assertEquals("", values[0].text)
        assertEquals("20261029T101500", values[1].text)
    }
    @Test(expected = PtpException::class) fun aCountLargerThanTheDataIsRejected() {
        PtpDataParser.parseObjectPropList(LittleEndianWriter().apply { u32(1_000) }.toByteArray())
    }
    @Test(expected = PtpException::class) fun aTruncatedValueIsRejected() {
        val data = LittleEndianWriter().apply {
            u32(1)
            u32(1); u16(0xDC04); u16(0x0008); u32(5) // 64-bit size cut short
        }.toByteArray()
        PtpDataParser.parseObjectPropList(data)
    }
    @Test(expected = PtpException::class) fun anUnsupportedTypeIsRejectedSoTheCallerCanFallBack() {
        val data = LittleEndianWriter().apply { u32(1); element(1, 0xDC07, 0x4006) { u32(0) } }.toByteArray()
        PtpDataParser.parseObjectPropList(data)
    }
}
