package com.xiaozhu.zftransfer

/** Unknown/missing evidence never confirms an import. URI readability is checked separately. */
object ImportEvidence {
    private val RAW_MIMES = setOf("image/x-nikon-nef", "image/x-nikon-nrw", "image/x-adobe-dng", "image/dng", "image/x-dng")
    /** Historical album access does not turn a file into proof of a camera import. */
    fun includeLocal(owned: Boolean, fullAlbumAccess: Boolean) = owned || fullAlbumAccess
    fun confirmsLocal(recorded: Boolean, owned: Boolean) = recorded || owned
    /** Every MIME type an import is stored under: JPEG plus the RAW types MediaStore may report. */
    val MIMES: List<String> = listOf("image/jpeg") + RAW_MIMES
    fun importableMime(mime: String?) = mime in MIMES
    fun isRawMime(mime: String?) = mime in RAW_MIMES
    fun matches(expected: Long, stored: Long, pending: Int, mime: String?): Boolean =
        expected > 0 && stored == expected && pending == 0 && importableMime(mime)
}
