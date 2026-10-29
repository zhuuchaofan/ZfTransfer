package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.*
import org.junit.Test

class GalleryStateTest {
    private fun photo(handle: Long) = CameraObject(handle, 1, 0x3801, 100, 10, "photo$handle.jpg", "20261028T120000")

    @Test fun resetStartsANewSessionAndDropsPhotosAndEvidence() {
        val gallery = GalleryState()
        val before = gallery.imageSession
        gallery.setCatalog(before, listOf(photo(1)), setOf(1L))
        gallery.reset()
        assertNotEquals(before, gallery.imageSession)
        assertTrue(gallery.photos.isEmpty())
        assertTrue(gallery.imported.isEmpty())
        assertFalse(gallery.ready)
    }
    @Test fun aCatalogForAReplacedListingIsIgnored() {
        val gallery = GalleryState()
        val stale = gallery.imageSession
        gallery.reset()
        assertFalse(gallery.setCatalog(stale, listOf(photo(1)), setOf(1L)))
        assertTrue(gallery.photos.isEmpty())
        assertTrue(gallery.imported.isEmpty())
        assertFalse(gallery.ready)
    }
    @Test fun theCatalogReplacesEverythingAndMarksTheGalleryReady() {
        val gallery = GalleryState()
        assertFalse(gallery.ready)
        assertTrue(gallery.setCatalog(gallery.imageSession, listOf(photo(1), photo(2)), setOf(2L)))
        assertTrue(gallery.setCatalog(gallery.imageSession, listOf(photo(3)), emptySet()))
        assertEquals(listOf(3L), gallery.photos.map { it.handle })
        assertTrue(gallery.imported.isEmpty())
        assertTrue(gallery.ready)
    }
    @Test fun verifiedPhotosAreTheImportedSet() {
        val gallery = GalleryState()
        gallery.setCatalog(gallery.imageSession, listOf(photo(1), photo(2)), setOf(1L))
        assertEquals(setOf(1L), gallery.imported)
    }
    @Test fun refreshingEvidenceOnlyTouchesTheCheckedHandles() {
        val gallery = GalleryState()
        gallery.markImported(1, true); gallery.markImported(2, true); gallery.markImported(3, true)
        gallery.replaceEvidence(checked = listOf(1L, 2L), verified = setOf(2L))
        assertEquals(setOf(2L, 3L), gallery.imported)
    }
    @Test fun markingImportedCanBeUndone() {
        val gallery = GalleryState()
        gallery.markImported(5, true)
        gallery.markImported(5, false)
        assertFalse(5L in gallery.imported)
    }
}
