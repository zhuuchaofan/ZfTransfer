package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

object CameraCatalog {
    private val newestFirst = compareByDescending<CameraObject> { it.captureDate }.thenByDescending { it.handle }

    /**
     * Every photo and video on every memory card, newest first. Each card lists its own files in order
     * and the camera simply appends one list to the other, so the whole catalog is sorted here. Folders
     * and other objects that are neither photos nor videos are dropped. Copies of one shot on two cards
     * are all kept; see [CopyGroups] for how they are treated as one photo.
     */
    fun arrange(objects: List<CameraObject>): List<CameraObject> =
        objects.filter { MediaFormat.of(it) != null }.sortedWith(newestFirst)
}

/**
 * With "backup" recording the camera writes every shot to both cards: same name, size and time on
 * two storages. Those copies are one photo. Only a file that has exactly one copy on each of several
 * cards counts as such; identical-looking files on a single card stay separate photos.
 */
object CopyGroups {
    fun key(obj: CameraObject) = Triple(obj.filename, obj.size, obj.captureDate)

    private fun isOnePhoto(copies: List<CameraObject>) = copies.size > 1 && copies.groupBy { it.storageId }.values.all { it.size == 1 }

    /**
     * One copy per photo, in the order of [photos]. The copy that is already imported is kept, otherwise
     * the one on the lower-numbered storage.
     */
    fun unique(photos: List<CameraObject>, isImported: (CameraObject) -> Boolean = { false }): List<CameraObject> {
        val kept = HashMap<Triple<String, Long, String>, Long>()
        for ((groupKey, copies) in photos.groupBy { key(it) }) {
            if (isOnePhoto(copies)) kept[groupKey] = (copies.firstOrNull(isImported) ?: copies.minWith(compareBy<CameraObject>({ it.storageId }, { it.handle }))).handle
        }
        return photos.filter { obj -> kept[key(obj)].let { it == null || it == obj.handle } }
    }

    /** A photo is imported when any of its copies is, so every copy of it is returned. */
    fun importedWithCopies(photos: List<CameraObject>, isImported: (CameraObject) -> Boolean): Set<Long> {
        val handles = HashSet<Long>()
        for (copies in photos.groupBy { key(it) }.values) {
            if (isOnePhoto(copies)) { if (copies.any(isImported)) copies.forEach { handles.add(it.handle) } }
            else copies.filter(isImported).forEach { handles.add(it.handle) }
        }
        return handles
    }

    /** The handles that stand for the same photo as [handle], itself included. */
    fun copiesOf(photos: List<CameraObject>, handle: Long): Set<Long> {
        val photo = photos.firstOrNull { it.handle == handle } ?: return setOf(handle)
        val copies = photos.filter { key(it) == key(photo) }
        return if (isOnePhoto(copies)) copies.map { it.handle }.toSet() else setOf(handle)
    }
}

/** The camera's memory cards, numbered by storage id the way the camera numbers its slots. */
object StorageCards {
    fun slots(photos: List<CameraObject>): List<Long> = photos.map { it.storageId }.distinct().sorted()
    fun label(slots: List<Long>, storageId: Long?) = if (storageId == null) "全部" else "卡 ${slots.indexOf(storageId) + 1}"
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
