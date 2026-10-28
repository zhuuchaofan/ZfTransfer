package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

enum class PhotoFilter(val label: String) { ALL("全部"), NOT_IMPORTED("未导入"), IMPORTED("已导入") }

/** File format and import state are independent filter dimensions. HLG is not inferred from a suffix. */
enum class MediaFormat(val label: String, val available: Boolean = false) {
    ALL("全部格式",true), JPEG("JPEG",true), RAW("RAW"), VIDEO("视频"), HEIF("HIF / HEIF");
    fun matches(obj: CameraObject) = this==ALL || this==classify(obj.filename,obj.format)
    companion object {
        fun classify(filename: String, ptpFormat: Int = 0): MediaFormat? {
            val suffix=filename.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)
            return when(suffix) {
                "jpg","jpeg" -> JPEG
                "nef","nrw","dng" -> RAW
                "mov","mp4","avi","mpeg","mpg","m4v" -> VIDEO
                "hif","heif","heic" -> HEIF
                else -> when(ptpFormat) {
                    CameraObject.FORMAT_EXIF_JPEG -> JPEG
                    0x300A,0x300B,0x300C,0x300D -> VIDEO
                    else -> null
                }
            }
        }
    }
}

/** Only verified imports enter the imported filter; no filename or preview-cache guessing. */
object PhotoCatalog {
    fun visible(photos: List<CameraObject>, verified: Set<Long>, filter: PhotoFilter, format: MediaFormat = MediaFormat.ALL): List<CameraObject> =
        photos.filter { format.matches(it) && when (filter) {
            PhotoFilter.ALL -> true
            PhotoFilter.NOT_IMPORTED -> it.handle !in verified
            PhotoFilter.IMPORTED -> it.handle in verified
        } }

    /** Freeze existing preview membership until dismiss, append only newly eligible photos. */
    fun appendPreview(existing: List<CameraObject>, loaded: List<CameraObject>, verified: Set<Long>, filter: PhotoFilter, format: MediaFormat = MediaFormat.ALL): List<CameraObject> {
        val known = existing.map { it.handle }.toHashSet()
        return existing + visible(loaded, verified, filter, format).filter { known.add(it.handle) }
    }
}

/** One horizontal gesture moves at most one page; vertical browsing and zoom own their gestures. */
object SwipeNavigation {
    fun direction(dx: Float, dy: Float, threshold: Float, blocked: Boolean = false): Int {
        if(blocked || !dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx) < threshold ||
            kotlin.math.abs(dx) <= kotlin.math.abs(dy) * 1.5f) return 0
        return if(dx < 0) 1 else -1
    }
    fun adjacent(index: Int, count: Int, direction: Int) = (index + direction).coerceIn(0, (count-1).coerceAtLeast(0))
}

object CameraCatalogSummary {
    fun text(totalFiles: Int, readFiles: Int, loadedJpegs: Int): String =
        if(readFiles >= totalFiles) "相机共 $loadedJpegs 张 JPEG"
        else "相机共 $totalFiles 个文件 · 已读取 $loadedJpegs 张 JPEG"
}
