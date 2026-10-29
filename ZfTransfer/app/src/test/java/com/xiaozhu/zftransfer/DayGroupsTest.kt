package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.*
import org.junit.Test

class DayGroupsTest {
    private fun shot(handle: Long, date: String) = CameraObject(handle, 1, 0x3801, 100, 10, "p$handle.jpg", date)
    private val HP = GridCell.HeaderPad
    private val TP = GridCell.TailPad
    private fun P(i: Int) = GridCell.Photo(i)

    @Test fun formatsTheCaptureDateAsADay() {
        assertEquals("2026-10-28", DayGroups.dayOf("20261028T120000"))
        assertEquals("2026-10-28", DayGroups.dayOf("20261028"))
    }
    @Test fun missingOrBrokenDatesFallBackToUnknown() {
        assertEquals(DayGroups.UNKNOWN_DAY, DayGroups.dayOf(""))
        assertEquals(DayGroups.UNKNOWN_DAY, DayGroups.dayOf("2026"))
        assertEquals(DayGroups.UNKNOWN_DAY, DayGroups.dayOf("2026-10-2"))
    }
    @Test fun noPhotosMeansNoCells() {
        assertTrue(DayGroups.plan(emptyList()).isEmpty())
    }
    @Test fun aHeadingTakesAWholeRowAndEveryGroupStartsOnANewRow() {
        val photos = listOf(shot(1, "20261028T1"), shot(2, "20261028T2"), shot(3, "20261028T3"), shot(4, "20261028T4"),
            shot(5, "20261027T1"), shot(6, "20261027T2"))
        val cells = DayGroups.plan(photos)
        assertEquals(listOf(
            GridCell.Header("2026-10-28", 0, 4), HP, HP, P(0), P(1), P(2), P(3), TP, TP,
            GridCell.Header("2026-10-27", 4, 2), HP, HP, P(4), P(5), TP,
        ), cells)
        cells.indices.filter { cells[it] is GridCell.Header }.forEach { assertEquals(0, it % 3) }
    }
    @Test fun aFullLastRowNeedsNoPadding() {
        val cells = DayGroups.plan(listOf(shot(1, "20261028"), shot(2, "20261028"), shot(3, "20261028")))
        assertEquals(listOf(GridCell.Header("2026-10-28", 0, 3), HP, HP, P(0), P(1), P(2)), cells)
    }
    @Test fun theSameDayAppearingAgainLaterGetsItsOwnHeading() {
        val cells = DayGroups.plan(listOf(shot(1, "20261028"), shot(2, "20261027"), shot(3, "20261028")))
        assertEquals(3, cells.count { it is GridCell.Header })
    }
    @Test fun aTapOnAHeadingOrItsPaddingFindsTheHeading() {
        val cells = DayGroups.plan(listOf(shot(1, "20261028"), shot(2, "20261027")))
        val first = cells[0] as GridCell.Header
        assertEquals(first, DayGroups.headerAt(cells, 0))
        assertEquals(first, DayGroups.headerAt(cells, 1))
        assertEquals(first, DayGroups.headerAt(cells, 2))
    }
    @Test fun paddingAfterTheLastPhotoOfAGroupIsNotAHeading() {
        val cells = DayGroups.plan(listOf(shot(1, "20261028"), shot(2, "20261027")))
        // [H HP HP][P0 TP TP][H HP HP][P1 TP TP]
        assertNull(DayGroups.headerAt(cells, 4))
        assertNull(DayGroups.headerAt(cells, 5))
        assertNull(DayGroups.headerAt(cells, 3))
    }
    @Test fun photoPositionsMapBackToTheFilteredList() {
        val cells = DayGroups.plan(listOf(shot(1, "20261028"), shot(2, "20261027")))
        assertEquals(0, DayGroups.photoIndexAt(cells, 3))
        assertEquals(1, DayGroups.photoIndexAt(cells, 9))
        assertNull(DayGroups.photoIndexAt(cells, 0))
        assertNull(DayGroups.photoIndexAt(cells, 4))
        assertNull(DayGroups.photoIndexAt(cells, 99))
    }
    @Test fun aHeadingOwnsExactlyThePhotosOfItsDay() {
        val photos = listOf(shot(1, "20261028"), shot(2, "20261028"), shot(3, "20261027"))
        val header = DayGroups.plan(photos)[0] as GridCell.Header
        assertEquals(listOf(1L, 2L), DayGroups.photosOf(header, photos).map { it.handle })
    }
}
