package com.xiaozhu.zftransfer

/** A drag is a reversible range over the selection present at gesture start. */
class RangeSelection(private val initial: Set<Int>, private val anchor: Int) {
    fun at(end: Int): Set<Int> {
        val range = minOf(anchor, end)..maxOf(anchor, end)
        return if (anchor in initial) initial - range.toSet() else initial + range.toSet()
    }
}
