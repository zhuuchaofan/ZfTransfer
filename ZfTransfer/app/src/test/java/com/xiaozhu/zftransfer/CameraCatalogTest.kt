package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.*
import org.junit.Test

class CameraCatalogTest {
    private fun obj(handle: Long, name: String, date: String, storage: Long = 1, format: Int = 0x3801, size: Long = 100) =
        CameraObject(handle, storage, format, size, 0, name, date)

    @Test fun twoCardsAreMergedNewestFirst() {
        val cardOne = listOf(obj(10, "a.JPG", "20261029T100000", 1), obj(9, "b.JPG", "20261020T100000", 1), obj(8, "c.JPG", "20260410T100000", 1))
        val cardTwo = listOf(obj(300, "d.JPG", "20261028T100000", 2), obj(299, "e.JPG", "20260721T100000", 2))
        val arranged = CameraCatalog.arrange(cardOne + cardTwo)
        assertEquals(listOf("a.JPG", "d.JPG", "b.JPG", "e.JPG", "c.JPG"), arranged.map { it.filename })
    }
    @Test fun aShotWrittenToBothCardsIsOnePhoto() {
        val first = obj(10, "a.JPG", "20261029T100000", storage = 65537, size = 500)
        val second = obj(300, "a.JPG", "20261029T100000", storage = 131073, size = 500)
        assertEquals(listOf(first), CameraCatalog.arrange(listOf(second, first)))
    }
    @Test fun theCopyThatIsAlreadyImportedIsTheOneKept() {
        val first = obj(10, "a.JPG", "20261029T100000", storage = 65537, size = 500)
        val second = obj(300, "a.JPG", "20261029T100000", storage = 131073, size = 500)
        assertEquals(listOf(second), CameraCatalog.arrange(listOf(first, second)) { it.storageId == 131073L })
    }
    @Test fun sameNameButDifferentTimeOrSizeAreDifferentPhotos() {
        val a = obj(1, "a.JPG", "20261029T100000", storage = 1, size = 500)
        val otherTime = obj(2, "a.JPG", "20261030T100000", storage = 2, size = 500)
        val otherSize = obj(3, "a.JPG", "20261029T100000", storage = 2, size = 600)
        assertEquals(3, CameraCatalog.arrange(listOf(a, otherTime, otherSize)).size)
    }
    @Test fun twoCopiesOfAVideoAreOneToo() {
        val v1 = obj(1, "v.MOV", "20261029T100000", storage = 1, format = 0x300D, size = 9_000)
        val v2 = obj(2, "v.MOV", "20261029T100000", storage = 2, format = 0x300D, size = 9_000)
        assertEquals(1, CameraCatalog.arrange(listOf(v1, v2)).size)
    }
    @Test fun foldersAndUnknownObjectsAreDropped() {
        val folder = obj(1, "DCIM", "20260101T000000", format = 0x3001)
        val other = obj(2, "x.bin", "20261029T100000", format = 0x3000)
        val photo = obj(3, "p.JPG", "20261029T100000")
        assertEquals(listOf(photo), CameraCatalog.arrange(listOf(folder, other, photo)))
    }
    @Test fun videosAreKept() {
        val video = obj(4, "v.MOV", "20261029T110000", format = 0x300D)
        assertEquals(listOf(video), CameraCatalog.arrange(listOf(video)))
    }
    @Test fun photosWithTheSameSecondKeepTheNewerHandleFirst() {
        val a = obj(5, "a.JPG", "20261029T100000"); val b = obj(6, "b.JPG", "20261029T100000")
        assertEquals(listOf(b, a), CameraCatalog.arrange(listOf(a, b)))
    }
    @Test fun photosWithoutADateGoLast() {
        val dated = obj(1, "a.JPG", "20260101T000000"); val undated = obj(2, "b.JPG", "")
        assertEquals(listOf(dated, undated), CameraCatalog.arrange(listOf(undated, dated)))
    }
    @Test fun anEmptyCameraGivesAnEmptyCatalog() {
        assertTrue(CameraCatalog.arrange(emptyList()).isEmpty())
    }

    // ---- which camera events mean the file list changed
    @Test fun anAddedOrRemovedObjectAndAFinishedCaptureTriggerARefresh() {
        assertTrue(CameraEvents.changesCatalog(0x4002))
        assertTrue(CameraEvents.changesCatalog(0x4003))
        assertTrue(CameraEvents.changesCatalog(0x400D))
    }
    @Test fun otherEventsDoNot() {
        assertFalse(CameraEvents.changesCatalog(0x4004))
        assertFalse(CameraEvents.changesCatalog(0xC101))
        assertFalse(CameraEvents.changesCatalog(0))
    }

    // ---- thumbnail reading order
    private val order = (1L..10L).toList()

    @Test fun whatIsOnScreenIsReadBeforeTheRest() {
        val batch = ThumbnailPlan.nextBatch(listOf(8, 9), order, 0, 4) { false }
        assertEquals(listOf(8L, 9L, 1L, 2L), batch.handles)
        assertEquals(2, batch.next)
    }
    @Test fun thumbnailsAlreadyReadAreSkipped() {
        val have = setOf(1L, 2L, 3L)
        val batch = ThumbnailPlan.nextBatch(emptyList(), order, 0, 3) { it in have }
        assertEquals(listOf(4L, 5L, 6L), batch.handles)
        assertEquals(6, batch.next)
    }
    @Test fun aFocusedHandleIsNotReadTwice() {
        val batch = ThumbnailPlan.nextBatch(listOf(1, 2), order, 0, 4) { false }
        assertEquals(listOf(1L, 2L, 3L, 4L), batch.handles)
    }
    @Test fun anEmptyBatchAtTheEndMeansEverythingIsRead() {
        val batch = ThumbnailPlan.nextBatch(listOf(3), order, 10, 4) { true }
        assertTrue(batch.handles.isEmpty())
        assertEquals(order.size, batch.next)
    }
}
