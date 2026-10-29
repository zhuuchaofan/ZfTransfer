package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

object CameraCatalog {
    /**
     * Newest first across every memory card. Each card lists its own files in order and the camera
     * simply appends one list to the other, so the whole catalog is sorted here. Folders and other
     * objects that are neither photos nor videos are dropped.
     */
    fun arrange(objects: List<CameraObject>): List<CameraObject> =
        objects.filter { MediaFormat.of(it) != null }
            .sortedWith(compareByDescending<CameraObject> { it.captureDate }.thenByDescending { it.handle })
}

object ThumbnailPlan {
    class Batch(val handles: List<Long>, val next: Int)

    /**
     * The next thumbnails to read: first what is on screen ([focus]), then everything else in display
     * order starting at [from]. A batch that comes back empty means there is nothing left to read.
     */
    fun nextBatch(focus: List<Long>, order: List<Long>, from: Int, limit: Int, have: (Long) -> Boolean): Batch {
        val picked = LinkedHashSet<Long>()
        for (handle in focus) {
            if (picked.size >= limit) break
            if (!have(handle)) picked.add(handle)
        }
        var next = from
        while (picked.size < limit && next < order.size) {
            val handle = order[next++]
            if (!have(handle)) picked.add(handle)
        }
        return Batch(picked.toList(), next)
    }
}
