package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class ExifOrientationReadTest {
    private fun u16(v: Int, little: Boolean) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Int, little: Boolean) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte(), 0, 0) else byteArrayOf(0, 0, (v shr 8).toByte(), v.toByte())

    private fun tiffWithOrientation(value: Int, little: Boolean): ByteArray {
        val make = u16(0x010F, little) + u16(2, little) + u32(1, little) + u32(0, little)
        val orientation = u16(0x0112, little) + u16(3, little) + u32(1, little) + u16(value, little) + byteArrayOf(0, 0)
        return (if (little) "II".toByteArray() else "MM".toByteArray()) + u16(42, little) + u32(8, little) + u16(2, little) + make + orientation + u32(0, little)
    }

    private fun jpegWithOrientation(value: Int, little: Boolean = true): ByteArray {
        val app1 = "Exif".toByteArray() + byteArrayOf(0, 0) + tiffWithOrientation(value, little)
        val length = app1.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + app1
    }

    @Test fun readsThePortraitTagsFromEitherByteOrder() {
        assertEquals(8, ExifOrientation.read(jpegWithOrientation(8)))
        assertEquals(6, ExifOrientation.read(jpegWithOrientation(6, little = false)))
    }
    @Test fun readsTheTagFromARawTiffHeader() {
        assertEquals(6, ExifOrientation.read(tiffWithOrientation(6, little = true)))
    }
    @Test fun anUprightPhotoIsOne() { assertEquals(1, ExifOrientation.read(jpegWithOrientation(1))) }
    @Test fun anOutOfRangeValueIsTreatedAsUpright() { assertEquals(1, ExifOrientation.read(jpegWithOrientation(9))) }
    @Test fun aTooShortOrUnknownHeaderIsUpright() {
        assertEquals(1, ExifOrientation.read(ByteArray(0)))
        assertEquals(1, ExifOrientation.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())))
        assertEquals(1, ExifOrientation.read(ByteArray(100) { 7 }))
    }
    @Test fun aSegmentBeforeTheExifOneIsSkipped() {
        val full = jpegWithOrientation(6)
        val exif = full.copyOfRange(2, full.size)
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 1, 2)
        assertEquals(6, ExifOrientation.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + exif))
    }
}
