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
 * With "backup" recording the camera writes every shot to both cards: same name and size on two
 * storages, and a "created" time that differs by about a second because the second copy is written a
 * moment later. Those copies are one photo. Only a file that has exactly one such copy on each of several
 * cards counts; identical-looking files on a single card stay separate photos.
 */
object CopyGroups {
    /** How far apart the two copies' times may be. Observed on a Zf: one second. */
    private const val SAME_MOMENT_SECONDS = 2L
    private val timestamp = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})")

    /** Copies share a name and a size; their times are compared separately. */
    fun key(obj: CameraObject) = Pair(obj.filename, obj.size)

    private fun secondsOf(date: String): Long? {
        val m = timestamp.find(date) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return try { java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()).toEpochSecond(java.time.ZoneOffset.UTC) }
        catch (e: java.time.DateTimeException) { null }
    }

    private fun sameMoment(copies: List<CameraObject>): Boolean {
        val seconds = copies.map { secondsOf(it.captureDate) }
        if (seconds.any { it == null }) return copies.map { it.captureDate }.distinct().size == 1
        return seconds.maxOf { it!! } - seconds.minOf { it!! } <= SAME_MOMENT_SECONDS
    }

    private fun isOnePhoto(copies: List<CameraObject>) =
        copies.size > 1 && copies.groupBy { it.storageId }.values.all { it.size == 1 } && sameMoment(copies)

    /**
     * One copy per photo, in the order of [photos]. The copy that is already imported is kept, otherwise
     * the one on the lower-numbered storage.
     */
    fun unique(photos: List<CameraObject>, isImported: (CameraObject) -> Boolean = { false }): List<CameraObject> {
        val kept = HashSet<Long>()
        val merged = HashSet<Long>()
        for (copies in photos.groupBy { key(it) }.values) {
            if (isOnePhoto(copies)) {
                copies.forEach { merged.add(it.handle) }
                kept.add((copies.firstOrNull(isImported) ?: copies.minWith(compareBy<CameraObject>({ it.storageId }, { it.handle }))).handle)
            }
        }
        return photos.filter { it.handle !in merged || it.handle in kept }
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

    /**
     * Files with the same name on more than one card that were nevertheless not merged; worth a look in
     * the log, since a backup copy should be nearly identical.
     */
    fun nearMisses(photos: List<CameraObject>): List<List<CameraObject>> =
        photos.groupBy { it.filename }.values.filter { named ->
            named.map { it.storageId }.distinct().size > 1 && unique(named).size == named.size
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
