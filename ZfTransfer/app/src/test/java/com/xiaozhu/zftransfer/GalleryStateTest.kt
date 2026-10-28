package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.*
import org.junit.Test

class GalleryStateTest {
    private fun photo(handle: Long) = CameraObject(handle, 1, 0x3801, 100, 10, "photo$handle.jpg", "20261028T120000")

    @Test fun resetStartsANewSessionAndDropsPhotosAndEvidence() {
        val gallery = GalleryState()
        val before = gallery.imageSession
        gallery.add(before, photo(1), verified = true)
        gallery.reset()
        assertNotEquals(before, gallery.imageSession)
        assertTrue(gallery.photos.isEmpty())
        assertTrue(gallery.imported.isEmpty())
    }
    @Test fun aPhotoReadForAReplacedListingIsIgnored() {
        val gallery = GalleryState()
        val stale = gallery.imageSession
        gallery.reset()
        assertFalse(gallery.add(stale, photo(1), verified = true))
        assertTrue(gallery.photos.isEmpty())
        assertTrue(gallery.imported.isEmpty())
    }
    @Test fun verifiedPhotosEnterTheImportedSetOnlyWhenAdded() {
        val gallery = GalleryState()
        gallery.add(gallery.imageSession, photo(1), verified = true)
        gallery.add(gallery.imageSession, photo(2), verified = false)
        assertEquals(listOf(1L, 2L), gallery.photos.map { it.handle })
        assertEquals(setOf(1L), gallery.imported)
    }
    @Test fun browsingStartsAtTheFirstHandleAndEndsAfterTheLast() {
        val gallery = GalleryState()
        gallery.startBrowse(listOf(9L, 8L))
        assertTrue(gallery.hasMore)
        gallery.advance(); gallery.advance()
        assertFalse(gallery.hasMore)
        gallery.startBrowse(listOf(7L))
        assertEquals(0, gallery.cursor)
        assertTrue(gallery.hasMore)
    }
    @Test fun automaticPageReadsAreAdmittedOncePerCursorButManualOnesAlways() {
        val gallery = GalleryState()
        gallery.startBrowse(listOf(1L, 2L, 3L))
        assertTrue(gallery.admitPage(busy = false, connected = true, automatic = true))
        assertFalse(gallery.admitPage(busy = false, connected = true, automatic = true))
        assertTrue(gallery.admitPage(busy = false, connected = true, automatic = false))
        gallery.beginBrowseGesture()
        assertTrue(gallery.admitPage(busy = false, connected = true, automatic = true))
    }
    @Test fun pageReadsAreRefusedWhenBusyDisconnectedOrFinished() {
        val gallery = GalleryState()
        gallery.startBrowse(listOf(1L))
        assertFalse(gallery.admitPage(busy = true, connected = true, automatic = false))
        assertFalse(gallery.admitPage(busy = false, connected = false, automatic = false))
        gallery.advance()
        assertFalse(gallery.admitPage(busy = false, connected = true, automatic = false))
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
