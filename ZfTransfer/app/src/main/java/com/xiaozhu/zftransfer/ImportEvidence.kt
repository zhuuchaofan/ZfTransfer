package com.xiaozhu.zftransfer

/** Unknown/missing evidence never confirms an import. URI readability is checked separately. */
object ImportEvidence {
    fun matches(expected: Long, stored: Long, pending: Int, mime: String?): Boolean =
        expected > 0 && stored == expected && pending == 0 && mime == "image/jpeg"
}
