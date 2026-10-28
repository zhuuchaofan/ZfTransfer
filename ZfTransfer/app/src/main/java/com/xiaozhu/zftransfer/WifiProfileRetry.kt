package com.xiaozhu.zftransfer

/** Automatic camera Wi-Fi requests: try once, then again only after the camera Wi-Fi was seen and a retry interval passed. */
class WifiProfileRetry(private val retryAfterMs: Long) {
    /** Also set by the user's own Wi-Fi actions: forgetting the profile counts as "do not auto-request". */
    var attempted = false
    private var lastRequestAt = 0L
    private var seenAvailable = false

    fun networkAvailable() { seenAvailable = true }
    fun requested(now: Long) { lastRequestAt = now }
    fun due(now: Long) = !attempted || (seenAvailable && now - lastRequestAt >= retryAfterMs)
}
