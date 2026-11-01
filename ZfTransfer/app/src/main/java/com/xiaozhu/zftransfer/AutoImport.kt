package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

/**
 * Photos taken after the app first saw the camera that should be downloaded on their own. The baseline is
 * what the camera held at that moment and is kept while the app runs, so photos taken while the phone was
 * disconnected are fetched after the next connection, and the existing catalog is never pulled in bulk.
 */
object AutoImport {
    /** More new photos than this are left for the user to choose, instead of filling the phone unasked. */
    const val CATCH_UP_LIMIT = 100

    /**
     * What a photo is known by across connections. The camera's handles may be numbered afresh after it
     * restarts, which would make the whole card look new; the card, name and capture time do not change.
     */
    data class Key(val storageId: Long, val filename: String, val captureDate: String)
    fun keyOf(obj: CameraObject) = Key(obj.storageId, obj.filename, obj.captureDate)
    fun baselineOf(catalog: List<CameraObject>): Set<Key> = catalog.mapTo(HashSet()) { keyOf(it) }

    /**
     * Not yet imported JPEGs that are not in [baseline], one copy per photo (two-card backup), in shooting
     * order. Recomputed on every refresh, so a photo that could not be fetched is tried again.
     */
    fun pending(catalog: List<CameraObject>, baseline: Set<Key>, isImported: (CameraObject) -> Boolean): List<CameraObject> =
        CopyGroups.unique(catalog.filter { it.canImport && keyOf(it) !in baseline }, isImported)
            .filter { !isImported(it) }
            .reversed()
}
