package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

object CameraCatalog {
    /**
     * Newest first across every memory card. Each card lists its own files in order and the camera
     * simply appends one list to the other, so the whole catalog is sorted here. Folders and other
     * objects that are neither photos nor videos are dropped.
     *
     * With "backup" recording the camera writes every shot to both cards: same name, size and time on
     * two storages. Those copies are one photo. The copy that is already imported is kept so its mark
     * survives; otherwise the one on the lower-numbered storage.
     */
    fun arrange(objects: List<CameraObject>, isImported: (CameraObject) -> Boolean = { false }): List<CameraObject> =
        objects.filter { MediaFormat.of(it) != null }
            .groupBy { Triple(it.filename, it.size, it.captureDate) }.values
            .map { copies ->
                if (copies.size == 1) copies[0]
                else copies.firstOrNull(isImported) ?: copies.minWith(compareBy<CameraObject>({ it.storageId }, { it.handle }))
            }
            .sortedWith(compareByDescending<CameraObject> { it.captureDate }.thenByDescending { it.handle })
}

object CameraEvents {
    private const val OBJECT_ADDED = 0x4002
    private const val OBJECT_REMOVED = 0x4003
    private const val CAPTURE_COMPLETE = 0x400D

    /** Events after which the camera's file list has probably changed. */
    fun changesCatalog(code: Int) = code == OBJECT_ADDED || code == OBJECT_REMOVED || code == CAPTURE_COMPLETE
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
