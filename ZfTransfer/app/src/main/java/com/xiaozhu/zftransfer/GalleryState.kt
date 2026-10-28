package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import java.util.UUID

/**
 * Photos read from the camera in this connection plus which of them are verified imports.
 * Mutated on the main thread only; [imageSession] and [cursor] are also read by the worker.
 */
class GalleryState {
    private val items = mutableListOf<CameraObject>()
    val photos: List<CameraObject> get() = items
    private val importedHandles = mutableSetOf<Long>()
    val imported: Set<Long> get() = importedHandles
    val completions = ImportCompletions()
    private val pageReads = PageReadGate()

    /** Changes on every new connection so late results for an older listing are dropped. */
    @Volatile var imageSession = UUID.randomUUID().toString()
        private set
    var handles = emptyList<Long>()
        private set
    @Volatile var cursor = 0
        private set
    val hasMore get() = cursor < handles.size

    fun reset() {
        imageSession = UUID.randomUUID().toString()
        items.clear(); importedHandles.clear(); completions.beginBatch()
    }
    fun startBrowse(listing: List<Long>) { handles = listing; cursor = 0 }
    fun advance() { cursor++ }

    fun beginBrowseGesture() = pageReads.resetGesture()
    fun admitPage(busy: Boolean, connected: Boolean, automatic: Boolean) =
        pageReads.request(imageSession, cursor, handles.size, busy, connected, automatic)

    /** Ignores a photo read for a listing that was replaced in the meantime. */
    fun add(session: String, obj: CameraObject, verified: Boolean): Boolean {
        if (session != imageSession) return false
        items.add(obj)
        if (verified) importedHandles.add(obj.handle)
        return true
    }
    fun markImported(handle: Long, verified: Boolean) {
        if (verified) importedHandles.add(handle) else importedHandles.remove(handle)
    }
    /** Replaces the evidence for [checked] photos, leaving every other handle untouched. */
    fun replaceEvidence(checked: Collection<Long>, verified: Set<Long>) {
        importedHandles.removeAll(checked.toSet()); importedHandles.addAll(verified)
    }
}
