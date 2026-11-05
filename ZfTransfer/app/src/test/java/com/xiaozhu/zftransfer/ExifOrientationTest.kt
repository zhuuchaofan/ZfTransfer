package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class ExifOrientationTest {
    @Test fun upright1AndUnknownValuesLeaveThePictureAlone() {
        for (tag in listOf(0, 1, 9, -1)) { assertEquals(0, ExifOrientation.degrees(tag)); assertFalse(ExifOrientation.mirrored(tag)) }
    }
    @Test fun portraitCameraShotsTurnNinetyDegreesEitherWay() {
        assertEquals(90, ExifOrientation.degrees(6)); assertEquals(270, ExifOrientation.degrees(8)); assertEquals(180, ExifOrientation.degrees(3))
    }
    @Test fun mirroredTagsAreTheFlippedOnes() {
        assertEquals(listOf(2, 4, 5, 7), (1..8).filter { ExifOrientation.mirrored(it) })
        assertEquals(90, ExifOrientation.degrees(5)); assertEquals(270, ExifOrientation.degrees(7))
    }
}
