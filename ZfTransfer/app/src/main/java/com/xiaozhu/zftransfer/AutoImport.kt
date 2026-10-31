package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

/**
 * Photos taken after the app connected that should be downloaded on their own. The baseline is every
 * handle the camera listed when the session started, so the existing catalog is never pulled in bulk.
 */
object AutoImport {
    /**
     * Not yet imported JPEGs that are newer than [baseline], one copy per photo (two-card backup), in
     * shooting order. Recomputed on every refresh, so a photo that could not be fetched is tried again.
     */
    fun pending(catalog: List<CameraObject>, baseline: Set<Long>, isImported: (CameraObject) -> Boolean): List<CameraObject> =
        CopyGroups.unique(catalog.filter { it.canImport && it.handle !in baseline }, isImported)
            .filter { !isImported(it) }
            .reversed()
}
