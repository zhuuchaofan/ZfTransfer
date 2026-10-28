package com.xiaozhu.zftransfer

/** Pure viewport geometry; never limits the underlying photo list. */
object FilmstripWindow {
    fun visible(scroll: Int, viewport: Int, step: Int, count: Int): IntRange {
        if (viewport <= 0 || step <= 0 || count <= 0) return IntRange.EMPTY
        val first = (scroll.coerceAtLeast(0) / step - 1).coerceIn(0, count - 1)
        val last = ((scroll.coerceAtLeast(0) + viewport) / step + 1).coerceIn(first, count - 1)
        return first..last
    }
    fun reveal(scroll: Int, viewport: Int, step: Int, selected: Int, count: Int): Int {
        if (viewport <= 0 || step <= 0 || selected !in 0 until count) return scroll.coerceAtLeast(0)
        val left = selected * step
        val right = left + step
        val desired = when { left < scroll -> left; right > scroll + viewport -> right - viewport; else -> scroll }
        return desired.coerceIn(0, (count * step - viewport).coerceAtLeast(0))
    }
}
