package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.*
import org.junit.Test

class FormatBrowseTest {
    private fun file(handle: Long, name: String, format: Int) = CameraObject(handle, 1, format, 1000, 10, name, "20261028T120000")
    private val jpeg = file(1, "a.JPG", 0x3801)
    private val raw = file(2, "b.NEF", 0x3000)
    private val hif = file(3, "c.HIF", 0x3801)
    private val video = file(4, "d.MOV", 0x300D)
    private val unknown = file(5, "e.bin", 0x3000)

    @Test fun onlyJpegCanBeImported() {
        assertTrue(jpeg.canImport)
        assertFalse(raw.canImport)
        assertFalse(video.canImport)
        assertFalse(unknown.canImport)
    }
    @Test fun aHifFileThatReportsTheJpegFormatCodeIsNotImportedAsJpeg() {
        assertFalse(hif.canImport)
        assertEquals(MediaFormat.HEIF, MediaFormat.of(hif))
    }
    @Test fun everyKnownFormatIsBrowsableThroughItsFilter() {
        val all = listOf(jpeg, raw, hif, video, unknown)
        assertEquals(listOf(jpeg), PhotoCatalog.visible(all, emptySet(), PhotoFilter.ALL, MediaFormat.JPEG))
        assertEquals(listOf(raw), PhotoCatalog.visible(all, emptySet(), PhotoFilter.ALL, MediaFormat.RAW))
        assertEquals(listOf(video), PhotoCatalog.visible(all, emptySet(), PhotoFilter.ALL, MediaFormat.VIDEO))
        assertEquals(listOf(hif), PhotoCatalog.visible(all, emptySet(), PhotoFilter.ALL, MediaFormat.HEIF))
    }
    @Test fun summaryNamesOnlyJpegsWhenThatIsAllThereIs() {
        assertEquals("相机共 2 张 JPEG", CameraCatalogSummary.of(listOf(jpeg, jpeg)))
        assertEquals("相机共 0 张 JPEG", CameraCatalogSummary.of(emptyList()))
    }
    @Test fun summaryBreaksDownMixedFormatsAndSkipsEmptyOnes() {
        assertEquals("相机共 4 个：JPEG 2 · RAW 1 · 视频 1", CameraCatalogSummary.of(listOf(jpeg, jpeg, raw, video)))
    }
    @Test fun previewListsNeverContainFilesThatCannotBePreviewed() {
        val appended = PhotoCatalog.appendPreview(emptyList(), listOf(jpeg, raw, video), emptySet(), PhotoFilter.ALL, MediaFormat.ALL)
        assertEquals(listOf(jpeg), appended)
    }
}
