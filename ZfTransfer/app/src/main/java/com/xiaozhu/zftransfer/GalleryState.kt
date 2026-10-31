package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import java.util.UUID

/**
 * Photos read from the camera in this connection plus which of them are verified imports.
 * Mutated on the main thread only; [imageSession] is also read by the worker.
 */
class GalleryState {
    private val items = mutableListOf<CameraObject>()
    val photos: List<CameraObject> get() = items
    private val importedHandles = mutableSetOf<Long>()
    val imported: Set<Long> get() = importedHandles
    val completions = ImportCompletions()

    /** Changes on every new connection so late results for an older listing are dropped. */
    @Volatile var imageSession = UUID.randomUUID().toString()
        private set
    /** False from a new connection until its catalog has arrived. */
    @Volatile var ready = false
        private set
    /** Counts every catalog that replaced the photo list, so screens can tell when it changed. */
    var version = 0
        private set

    fun reset() {
        imageSession = UUID.randomUUID().toString()
        items.clear(); importedHandles.clear(); completions.beginBatch(); ready = false; version++
    }

    /** Replaces the photo list with the camera's whole catalog; ignored for a listing that was replaced meanwhile. */
    fun setCatalog(session: String, catalog: List<CameraObject>, verified: Set<Long>): Boolean {
        if (session != imageSession) return false
        items.clear(); items.addAll(catalog)
        importedHandles.clear(); importedHandles.addAll(verified)
        ready = true; version++
        return true
    }
    /** Imported is a property of the photo, so every copy of it on the other card follows. */
    fun markImported(handle: Long, verified: Boolean) {
        val handles = CopyGroups.copiesOf(items, handle)
        if (verified) importedHandles.addAll(handles) else importedHandles.removeAll(handles)
    }
    /** Replaces the evidence for [checked] photos, leaving every other handle untouched. */
    fun replaceEvidence(checked: Collection<Long>, verified: Set<Long>) {
        importedHandles.removeAll(checked.toSet()); importedHandles.addAll(verified)
    }
}
