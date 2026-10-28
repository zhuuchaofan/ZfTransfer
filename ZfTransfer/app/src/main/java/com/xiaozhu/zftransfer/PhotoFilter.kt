package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

enum class PhotoFilter(val label: String) { ALL("全部"), NOT_IMPORTED("未导入"), IMPORTED("已导入") }

/** Only verified imports enter the imported filter; no filename or preview-cache guessing. */
object PhotoCatalog {
    fun visible(photos: List<CameraObject>, verified: Set<Long>, filter: PhotoFilter): List<CameraObject> =
        photos.filter { when (filter) {
            PhotoFilter.ALL -> true
            PhotoFilter.NOT_IMPORTED -> it.handle !in verified
            PhotoFilter.IMPORTED -> it.handle in verified
        } }

    /** Freeze existing preview membership until dismiss, append only newly eligible photos. */
    fun appendPreview(existing: List<CameraObject>, loaded: List<CameraObject>, verified: Set<Long>, filter: PhotoFilter): List<CameraObject> {
        val known = existing.map { it.handle }.toHashSet()
        return existing + visible(loaded, verified, filter).filter { known.add(it.handle) }
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
