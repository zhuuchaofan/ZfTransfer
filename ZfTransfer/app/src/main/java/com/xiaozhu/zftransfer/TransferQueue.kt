package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

/** Incomplete photos only. Removed only after publication or verified skip. */
class TransferQueue {
    @Volatile var identity: String? = null
        private set
    private val items = mutableListOf<CameraObject>()
    val remaining: List<CameraObject> get() = synchronized(this) { items.toList() }
    @Synchronized fun replace(camera: String?, photos: List<CameraObject>) { identity = camera; items.clear(); items.addAll(photos) }
    @Synchronized fun completed(photo: CameraObject) { items.remove(photo) }
    @Synchronized fun canRetry(camera: String?) = identity != null && identity == camera && items.isNotEmpty()
    /**
     * The photo is still the one that was queued: same handle, card, format, name and capture time. Sizes are left
     * out. The whole-catalog read has no thumbnail size, and a photo listed while the camera was still writing it
     * has a smaller size than it ends up with; a retry downloads the camera's current description of the photo.
     */
    fun matches(expected: CameraObject, actual: CameraObject) =
        expected.copy(size = 0, thumbnailSize = 0) == actual.copy(size = 0, thumbnailSize = 0) &&
            expected.captureDate.isNotBlank() && actual.size > 0
}
