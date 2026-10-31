package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.*
import org.junit.Test

class AutoImportTest {
    private val slot1 = 65537L
    private val slot2 = 131073L
    private fun obj(handle: Long, name: String, date: String, storage: Long = slot1, size: Long = 500, format: Int = 0x3801) =
        CameraObject(handle, storage, format, size, 0, name, date)
    private val never = { _: CameraObject -> false }

    @Test fun onlyPhotosNewerThanTheBaselineAreTaken() {
        val old = obj(1, "old.JPG", "20261029T100000")
        val fresh = obj(2, "new.JPG", "20261031T100000")
        assertEquals(listOf(fresh), AutoImport.pending(listOf(fresh, old), setOf(1L), never))
    }
    @Test fun photosComeOutInShootingOrder() {
        val first = obj(2, "a.JPG", "20261031T100000")
        val second = obj(3, "b.JPG", "20261031T100005")
        assertEquals(listOf(first, second), AutoImport.pending(listOf(second, first), emptySet(), never))
    }
    @Test fun rawAndVideoAreLeftAlone() {
        val raw = obj(2, "a.NEF", "20261031T100000", format = 0x3000)
        val video = obj(3, "a.MOV", "20261031T100001", format = 0x300D)
        assertTrue(AutoImport.pending(listOf(raw, video), emptySet(), never).isEmpty())
    }
    @Test fun aBackedUpPhotoIsTakenOnce() {
        val one = obj(2, "a.JPG", "20261031T100000", slot1)
        val two = obj(3, "a.JPG", "20261031T100001", slot2)
        assertEquals(listOf(one), AutoImport.pending(listOf(two, one), emptySet(), never))
    }
    @Test fun anAlreadyImportedPhotoIsNotTakenAgain() {
        val one = obj(2, "a.JPG", "20261031T100000", slot1)
        val two = obj(3, "a.JPG", "20261031T100001", slot2)
        assertTrue(AutoImport.pending(listOf(two, one), emptySet()) { it.handle == 3L }.isEmpty())
    }
    @Test fun aPhotoThatFailedIsOfferedAgain() {
        val fresh = obj(2, "a.JPG", "20261031T100000")
        assertEquals(listOf(fresh), AutoImport.pending(listOf(fresh), emptySet(), never))
        assertEquals(listOf(fresh), AutoImport.pending(listOf(fresh), emptySet(), never))
    }
}
