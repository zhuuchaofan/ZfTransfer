package com.xiaozhu.zftransfer

import com.xiaozhu.zftransfer.CameraSupport.Level
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSupportTest {
    @Test fun zfIsRecognisedWithOrWithoutTheBrandPrefix() {
        assertEquals(Level.ZF, CameraSupport.levelOf("Nikon Corporation", "Z f"))
        assertEquals(Level.ZF, CameraSupport.levelOf("Nikon Corporation", "Zf"))
        assertEquals(Level.ZF, CameraSupport.levelOf("Nikon Corporation", "Nikon Zf"))
    }
    @Test fun otherNikonBodiesAreTriedButFlagged() {
        assertEquals(Level.OTHER_NIKON, CameraSupport.levelOf("Nikon Corporation", "Z 8"))
        assertEquals(Level.OTHER_NIKON, CameraSupport.levelOf("Nikon Corporation", "Z 6_3"))
        assertEquals(Level.OTHER_NIKON, CameraSupport.levelOf("Nikon Corporation", "D850"))
        assertEquals(Level.OTHER_NIKON, CameraSupport.levelOf("Nikon Corporation", "Z fc"))
    }
    @Test fun otherBrandsAreRefused() {
        assertEquals(Level.NOT_NIKON, CameraSupport.levelOf("Sony Corporation", "ILCE-7M4"))
        assertEquals(Level.NOT_NIKON, CameraSupport.levelOf("Canon Inc.", "EOS R6"))
    }
    @Test fun aMissingManufacturerIsNotRefused() {
        assertEquals(Level.OTHER_NIKON, CameraSupport.levelOf("", "Z 8"))
    }
}
