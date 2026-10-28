package com.xiaozhu.zftransfer

/** Unknown/missing evidence never confirms an import. URI readability is checked separately. */
object ImportEvidence {
    /** Historical album access does not turn a file into proof of a camera import. */
    fun includeLocal(owned: Boolean, fullAlbumAccess: Boolean) = owned || fullAlbumAccess
    fun confirmsLocal(recorded: Boolean, owned: Boolean) = recorded || owned
    fun matches(expected: Long, stored: Long, pending: Int, mime: String?): Boolean =
        expected > 0 && stored == expected && pending == 0 && mime == "image/jpeg"
}
