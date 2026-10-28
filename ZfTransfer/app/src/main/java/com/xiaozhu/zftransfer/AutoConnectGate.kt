package com.xiaozhu.zftransfer

class AutoConnectGate {
    private val attempted = mutableSetOf<String>()
    private val suppressed = mutableSetOf<Long>()
    private val retries = mutableMapOf<String, Int>()
    fun take(network: Long, address: String): Boolean =
        network !in suppressed && attempted.add("$network|$address")
    fun stop(network: Long?) { if (network != null) suppressed.add(network) }
    fun retryAfterFailure(network: Long, address: String): Boolean {
        val key = "$network|$address"
        val count = retries[key] ?: 0
        if (network in suppressed || count >= 2) return false
        retries[key] = count + 1
        attempted.remove(key)
        return true
    }
    fun lost(network: Long) {
        suppressed.remove(network)
        attempted.removeAll { it.startsWith("$network|") }
        retries.keys.removeAll { it.startsWith("$network|") }
    }
    fun reset() { attempted.clear(); suppressed.clear(); retries.clear() }

    companion object {
        fun isCameraCandidate(validated: Boolean, ssid: String?, address: String, knownAddress: String, requested: Boolean): Boolean =
            requested || ssid.orEmpty().trim('"').startsWith("NIKON", ignoreCase = true) || (!validated && address == knownAddress)
    }
}
