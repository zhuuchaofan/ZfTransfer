package com.xiaozhu.zftransfer

import java.net.SocketException
import java.net.SocketTimeoutException

/** Debounces a changing Wi-Fi endpoint and distinguishes transient transport failures. */
class ConnectionPreparation {
    private var endpoint: String? = null
    private var readyAt = 0L
    private var failures = 0
    @Synchronized fun waitMillis(network: Long, address: String, now: Long): Long {
        val key = "$network|$address"
        if (key != endpoint) { endpoint = key; readyAt = now + 1500 }
        return (readyAt - now).coerceAtLeast(0)
    }
    @Synchronized fun retryQuietly(error: Throwable): Boolean {
        val transient = generateSequence(error) { it.cause }.take(8).any { it is SocketException || it is SocketTimeoutException }
        if (!transient) return false
        failures++
        return failures < 3
    }
    @Synchronized fun resetNetwork() { endpoint = null; readyAt = 0 }
    @Synchronized fun reset() { resetNetwork(); failures = 0 }
}
