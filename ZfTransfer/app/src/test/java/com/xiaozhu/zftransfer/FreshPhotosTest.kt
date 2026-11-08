package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.*
import org.junit.Test

class FreshPhotosTest {
    private val slot1 = 65537L
    private val slot2 = 131073L
    private fun obj(handle: Long, name: String, date: String, storage: Long = slot1, size: Long = 500, format: Int = 0x3801) =
        CameraObject(handle, storage, format, size, 0, name, date)

    @Test fun onlyPhotosShotAfterTheNewestImportAreFresh() {
        val imported = obj(1, "a.JPG", "20261107T100000")
        val olderMissed = obj(2, "b.JPG", "20261106T100000")
        val newer = obj(3, "c.JPG", "20261108T090000")
        val summary = FreshPhotos.of(listOf(newer, imported, olderMissed), setOf(1L))
        assertEquals(listOf(newer), summary.fresh)
        assertEquals(2, summary.notImported)
        assertTrue(summary.hasHistory)
    }
    @Test fun nothingIsOfferedBeforeTheFirstImport() {
        val summary = FreshPhotos.of(listOf(obj(1, "a.JPG", "20261107T100000"), obj(2, "b.NEF", "20261107T100001", format = 0x3000)), emptySet())
        assertTrue(summary.fresh.isEmpty())
        assertEquals(2, summary.notImported)
        assertFalse(summary.hasHistory)
    }
    @Test fun freshPhotosComeOutOldestFirstAndIncludeRaw() {
        val imported = obj(1, "a.JPG", "20261107T100000")
        val later = obj(4, "d.JPG", "20261108T100005")
        val raw = obj(3, "c.NEF", "20261108T100000", format = 0x3000)
        assertEquals(listOf(raw, later), FreshPhotos.of(listOf(later, raw, imported), setOf(1L)).fresh)
    }
    @Test fun videoIsNeverOffered() {
        val imported = obj(1, "a.JPG", "20261107T100000")
        val video = obj(2, "b.MOV", "20261108T100000", format = 0x300D)
        assertTrue(FreshPhotos.of(listOf(video, imported), setOf(1L)).fresh.isEmpty())
    }
    @Test fun aBackedUpShotCountsOnce() {
        val imported = obj(1, "a.JPG", "20261107T100000")
        val copy1 = obj(2, "b.JPG", "20261108T100000", storage = slot1)
        val copy2 = obj(3, "b.JPG", "20261108T100001", storage = slot2)
        val summary = FreshPhotos.of(listOf(copy1, copy2, imported), setOf(1L))
        assertEquals(1, summary.fresh.size)
        assertEquals(1, summary.notImported)
    }
}
