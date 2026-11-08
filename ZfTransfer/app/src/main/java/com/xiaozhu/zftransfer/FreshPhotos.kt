package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

/**
 * What the home screen offers to take in one tap: photos shot after the newest photo already on the phone.
 * Pure logic, so the rule is tested without a camera.
 */
object FreshPhotos {
    /**
     * [fresh] is oldest first, the order they are taken in. [hasHistory] is false while nothing from this camera
     * is on the phone yet: then there is no "since last time" and the screen asks the user to pick instead of
     * offering every photo on the card.
     */
    data class Summary(val fresh: List<CameraObject>, val notImported: Int, val hasHistory: Boolean)

    fun of(photos: List<CameraObject>, imported: Set<Long>): Summary {
        val unique = CopyGroups.unique(photos.filter { it.canImport }) { it.handle in imported }
        val newestImported = unique.filter { it.handle in imported }.maxOfOrNull { it.captureDate }
        val pending = unique.filter { it.handle !in imported }
        val fresh = if (newestImported == null) emptyList()
            else pending.filter { it.captureDate > newestImported }.sortedWith(compareBy({ it.captureDate }, { it.handle }))
        return Summary(fresh, pending.size, newestImported != null)
    }
}
