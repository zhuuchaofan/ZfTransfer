package com.xiaozhu.zftransfer

object MediumPreviewPolicy {
    fun usable(width: Int, height: Int, bytes: Int): Boolean =
        minOf(width, height) >= 300 && maxOf(width, height) in 600..2560 && bytes in 1..(8 * 1024 * 1024)
}
