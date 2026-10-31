package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.*
import org.junit.Test

class StorageCardTest {
    private val slot1 = 65537L
    private val slot2 = 131073L
    private fun obj(handle: Long, name: String, date: String, storage: Long, size: Long = 500, format: Int = 0x3801) =
        CameraObject(handle, storage, format, size, 0, name, date)

    private val a1 = obj(10, "a.JPG", "20261029T100000", slot1)
    private val a2 = obj(300, "a.JPG", "20261029T100000", slot2)
    private val b1 = obj(11, "b.JPG", "20261028T100000", slot1)
    private val c2 = obj(301, "c.JPG", "20261027T100000", slot2)

    @Test fun arrangingKeepsEveryCopyOnEveryCard() {
        assertEquals(4, CameraCatalog.arrange(listOf(a1, a2, b1, c2)).size)
    }
    @Test fun aShotWrittenToBothCardsIsOnePhoto() {
        assertEquals(listOf(a1), CopyGroups.unique(listOf(a2, a1)))
    }
    @Test fun theCopyThatIsAlreadyImportedIsTheOneKept() {
        assertEquals(listOf(a2), CopyGroups.unique(listOf(a1, a2)) { it.storageId == slot2 })
    }
    @Test fun sameNameButDifferentTimeOrSizeAreDifferentPhotos() {
        val otherTime = obj(2, "a.JPG", "20261030T100000", slot2)
        val otherSize = obj(3, "a.JPG", "20261029T100000", slot2, size = 600)
        assertEquals(3, CopyGroups.unique(listOf(a1, otherTime, otherSize)).size)
    }
    @Test fun twoCopiesOfAVideoAreOneToo() {
        val v1 = obj(1, "v.MOV", "20261029T100000", slot1, size = 9_000, format = 0x300D)
        val v2 = obj(2, "v.MOV", "20261029T100000", slot2, size = 9_000, format = 0x300D)
        assertEquals(1, CopyGroups.unique(listOf(v1, v2)).size)
    }
    @Test fun identicalLookingFilesOnOneCardStayDistinct() {
        val x = obj(1, "same.JPG", "date", slot1)
        val y = obj(2, "same.JPG", "date", slot1)
        assertEquals(2, CopyGroups.unique(listOf(x, y)).size)
        assertEquals(setOf(2L), CopyGroups.importedWithCopies(listOf(x, y)) { it.handle == 2L })
        assertEquals(setOf(1L), CopyGroups.copiesOf(listOf(x, y), 1))
    }
    @Test fun anAmbiguousGroupIsNeverMerged() {
        // two files on card 1 and one on card 2 with the same name, size and time
        val x = obj(1, "same.JPG", "date", slot1)
        val y = obj(2, "same.JPG", "date", slot1)
        val z = obj(3, "same.JPG", "date", slot2)
        assertEquals(3, CopyGroups.unique(listOf(x, y, z)).size)
    }
    @Test fun importedOnOneCardMeansImportedOnBoth() {
        val imported = CopyGroups.importedWithCopies(listOf(a1, a2, b1, c2)) { it.handle == 10L }
        assertEquals(setOf(10L, 300L), imported)
    }
    @Test fun nothingImportedMeansNoHandles() {
        assertTrue(CopyGroups.importedWithCopies(listOf(a1, a2)) { false }.isEmpty())
    }

    @Test fun cardsAreNumberedByStorageIdNoMatterTheListOrder() {
        val slots = StorageCards.slots(listOf(c2, a2, a1, b1))
        assertEquals(listOf(slot1, slot2), slots)
        assertEquals("卡 1", StorageCards.label(slots, slot1))
        assertEquals("卡 2", StorageCards.label(slots, slot2))
        assertEquals("全部", StorageCards.label(slots, null))
    }
    @Test fun oneCardMeansNoChoice() {
        assertEquals(1, StorageCards.slots(listOf(a1, b1)).size)
    }

    @Test fun allCardsShowsEachPhotoOnce() {
        val shown = PhotoCatalog.visible(listOf(a1, a2, b1, c2), emptySet(), PhotoFilter.ALL, MediaFormat.ALL, null)
        assertEquals(listOf("a.JPG", "b.JPG", "c.JPG"), shown.map { it.filename })
    }
    @Test fun oneCardShowsOnlyThatCardsFilesIncludingTheOnesTheOtherCardLacks() {
        val all = listOf(a1, a2, b1, c2)
        assertEquals(listOf(a2, c2), PhotoCatalog.visible(all, emptySet(), PhotoFilter.ALL, MediaFormat.ALL, slot2))
        assertEquals(listOf(a1, b1), PhotoCatalog.visible(all, emptySet(), PhotoFilter.ALL, MediaFormat.ALL, slot1))
    }
    @Test fun theImportFilterWorksPerCard() {
        val all = listOf(a1, a2, b1, c2)
        val imported = setOf(10L, 300L)
        assertEquals(listOf(b1), PhotoCatalog.visible(all, imported, PhotoFilter.NOT_IMPORTED, MediaFormat.ALL, slot1))
        assertEquals(listOf(c2), PhotoCatalog.visible(all, imported, PhotoFilter.NOT_IMPORTED, MediaFormat.ALL, slot2))
    }
    @Test fun aPreviewKeepsToTheCardItWasOpenedOn() {
        val appended = PhotoCatalog.appendPreview(emptyList(), listOf(a1, a2, c2), emptySet(), PhotoFilter.ALL, MediaFormat.ALL, slot2)
        assertEquals(listOf(a2, c2), appended)
    }

    @Test fun theSummaryNamesTheCardWhenOneIsChosen() {
        assertEquals("卡 2共 2 张 JPEG", CameraCatalogSummary.of(listOf(a2, c2), "卡 2"))
        assertEquals("相机共 2 张 JPEG", CameraCatalogSummary.of(listOf(a1, b1)))
    }
}
