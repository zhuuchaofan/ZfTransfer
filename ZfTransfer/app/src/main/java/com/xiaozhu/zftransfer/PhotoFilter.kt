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
