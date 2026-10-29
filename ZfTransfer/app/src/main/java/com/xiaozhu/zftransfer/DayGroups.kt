package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

/** One slot of the camera grid: a photo, a day heading that spans a whole row, or blank padding behind it. */
sealed interface GridCell {
    /** [index] points into the filtered photo list. */
    data class Photo(val index: Int) : GridCell
    /** Covers the filtered photos [first] until [first] + [count]. */
    data class Header(val day: String, val first: Int, val count: Int) : GridCell
    /** Blank cells behind a heading. A GridView advances by the height of the LAST cell of a row, so these match the heading. */
    object HeaderPad : GridCell
    /** Blank cells that fill the last row of a group; they match the height of a photo cell. */
    object TailPad : GridCell
}

/**
 * Groups consecutive photos taken on the same day. The camera's own order is kept, so pages that
 * load later simply extend the last group or start a new one. A GridView cannot span columns, so a
 * heading takes the first slot of a row, is drawn three columns wide, and the rest of its row is padding.
 */
object DayGroups {
    const val UNKNOWN_DAY = "未知日期"

    /** "20261028T120000" becomes "2026-10-28". */
    fun dayOf(captureDate: String): String {
        val digits = captureDate.take(8)
        return if (digits.length == 8 && digits.all { it.isDigit() }) "${digits.take(4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}" else UNKNOWN_DAY
    }

    fun plan(photos: List<CameraObject>, columns: Int = 3): List<GridCell> {
        val cells = ArrayList<GridCell>(photos.size + photos.size / 8 + 6)
        var start = 0
        while (start < photos.size) {
            val day = dayOf(photos[start].captureDate)
            var end = start + 1
            while (end < photos.size && dayOf(photos[end].captureDate) == day) end++
            cells.add(GridCell.Header(day, start, end - start))
            repeat(columns - 1) { cells.add(GridCell.HeaderPad) }
            for (index in start until end) cells.add(GridCell.Photo(index))
            repeat((columns - (end - start) % columns) % columns) { cells.add(GridCell.TailPad) }
            start = end
        }
        return cells
    }

    /** The heading that a tap on [position] belongs to: the heading itself or the padding in its row. */
    fun headerAt(cells: List<GridCell>, position: Int): GridCell.Header? {
        val cell = cells.getOrNull(position)
        if (cell is GridCell.Header) return cell
        if (cell !is GridCell.HeaderPad) return null
        for (back in 1..2) {
            val earlier = cells.getOrNull(position - back) ?: return null
            if (earlier is GridCell.Header) return earlier
            if (earlier !is GridCell.HeaderPad) return null
        }
        return null
    }

    fun photoIndexAt(cells: List<GridCell>, position: Int): Int? = (cells.getOrNull(position) as? GridCell.Photo)?.index

    fun photosOf(header: GridCell.Header, photos: List<CameraObject>): List<CameraObject> =
        (header.first until header.first + header.count).mapNotNull { photos.getOrNull(it) }
}
