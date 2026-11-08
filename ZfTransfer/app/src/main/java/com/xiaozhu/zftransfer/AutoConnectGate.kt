package com.xiaozhu.zftransfer

class AutoConnectGate {
    private val attempted = mutableSetOf<String>()
    private val suppressed = mutableSetOf<Long>()
    fun take(network: Long, address: String): Boolean =
        network !in suppressed && attempted.add("$network|$address")
    fun stop(network: Long?) { if (network != null) suppressed.add(network) }
    fun lost(network: Long) {
        suppressed.remove(network)
        attempted.removeAll { it.startsWith("$network|") }
    }
    fun reset() { attempted.clear(); suppressed.clear() }

    companion object {
        fun isCameraCandidate(validated: Boolean, ssid: String?, address: String, knownAddress: String, requested: Boolean): Boolean =
            requested || ssid.orEmpty().trim('"').startsWith("NIKON", ignoreCase = true) || (!validated && address == knownAddress)
    }
}
