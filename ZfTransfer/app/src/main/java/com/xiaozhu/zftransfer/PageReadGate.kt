package com.xiaozhu.zftransfer

/** Shared admission for both scrolling surfaces, keyed by camera session and object cursor. */
class PageReadGate {
    private var lastRequest: Pair<String, Int>? = null
    fun resetGesture() { lastRequest = null }
    fun request(session: String, cursor: Int, total: Int, busy: Boolean, connected: Boolean, automatic: Boolean): Boolean {
        if (busy || !connected || cursor >= total || cursor < 0) return false
        val key = session to cursor
        if (automatic && lastRequest == key) return false
        lastRequest = key
        return true
    }
}
