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
    fun matches(expected: CameraObject, actual: CameraObject) = expected == actual && expected.captureDate.isNotBlank() && expected.size > 0
}
